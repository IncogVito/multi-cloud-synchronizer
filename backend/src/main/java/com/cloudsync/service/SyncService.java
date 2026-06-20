package com.cloudsync.service;

import com.cloudsync.exception.PhotoNotSyncedException;
import com.cloudsync.exception.SyncFolderNotConfiguredException;
import com.cloudsync.model.dto.PhotoAsset;
import com.cloudsync.model.dto.PrefetchStatus;
import com.cloudsync.model.dto.SyncProgressEvent;
import com.cloudsync.model.dto.SyncStartResponse;
import com.cloudsync.model.entity.ICloudAccount;
import com.cloudsync.model.entity.Photo;
import com.cloudsync.model.enums.MediaType;
import com.cloudsync.model.enums.SyncPhase;
import com.cloudsync.model.enums.SyncStatus;
import com.cloudsync.provider.PhotoSyncProvider;
import com.cloudsync.repository.AccountRepository;
import com.cloudsync.repository.PhotoRepository;
import com.cloudsync.util.ExifDateUtil;
import com.cloudsync.util.MediaPathUtil;
import com.cloudsync.util.Messages;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Singleton
public class SyncService {

    private static final Logger LOG = LoggerFactory.getLogger(SyncService.class);
    private static final int BATCH_SIZE = 100;
    private static final int EMIT_EVERY_N = 5;
    private static final Duration EMIT_MAX_INTERVAL = Duration.ofSeconds(3);

    private final PhotoRepository photoRepository;
    private final AccountRepository accountRepository;
    private final ThumbnailService thumbnailService;
    private final ThumbnailJobService thumbnailJobService;
    private final SyncStateHolder syncStateHolder;
    private final AppContextService appContextService;
    private final TaskHistoryService taskHistoryService;
    private final ExecutorService syncVirtualThreadExecutor;
    private final Map<String, PhotoSyncProvider> providers;

    private final Semaphore downloadSemaphore;
    private final Semaphore iphoneDownloadSemaphore;
    private final ConcurrentHashMap<String, AtomicBoolean> cancellationFlags = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> lastEmitTime = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> downloadedSinceEmit = new ConcurrentHashMap<>();
    /**
     * Tracks which provider is active for the current metadata-fetch phase per accountId.
     */
    private final ConcurrentHashMap<String, String> activeProviderType = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> downloadTotalCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> activeSyncTaskId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> largeCompletedCount = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> largeFailedCount = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> largeTotalCount = new ConcurrentHashMap<>();

    public SyncService(PhotoRepository photoRepository,
                       AccountRepository accountRepository,
                       ThumbnailService thumbnailService,
                       ThumbnailJobService thumbnailJobService,
                       SyncStateHolder syncStateHolder,
                       AppContextService appContextService,
                       TaskHistoryService taskHistoryService,
                       @Named("syncVirtualThreadExecutor") ExecutorService syncVirtualThreadExecutor,
                       @Named("ICLOUD") PhotoSyncProvider iCloudProvider,
                       @Named("IPHONE") PhotoSyncProvider iPhoneProvider,
                       @Value("${sync.download-concurrency:0}") int configuredConcurrency) {
        this.photoRepository = photoRepository;
        this.accountRepository = accountRepository;
        this.thumbnailService = thumbnailService;
        this.thumbnailJobService = thumbnailJobService;
        this.syncStateHolder = syncStateHolder;
        this.appContextService = appContextService;
        this.taskHistoryService = taskHistoryService;
        this.syncVirtualThreadExecutor = syncVirtualThreadExecutor;
        this.providers = Map.of(
                iCloudProvider.providerType(), iCloudProvider,
                iPhoneProvider.providerType(), iPhoneProvider
        );
        int concurrency = configuredConcurrency > 0
                ? configuredConcurrency
                : Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
        this.downloadSemaphore = new Semaphore(concurrency);
        this.iphoneDownloadSemaphore = new Semaphore(concurrency);
    }

    // ── public API ────────────────────────────────────────────────────────────

    /**
     * Start async sync for the given provider (default: "ICLOUD").
     * Returns immediately after triggering prefetch.
     * Progress is broadcast via SSE through SyncStateHolder.
     */
    public SyncStartResponse startSync(String accountId, String providerType) {
        ICloudAccount account = requireAccount(accountId);
        requireSyncFolderPath(account);


        // FIXME: Maybe more abstract?
        // FIXME: use enum, not stringly-typed. And validate at API boundary (e.g. controller).
        if ("ICLOUD".equalsIgnoreCase(providerType)) {
            requireValidSession(account);
        }

        PhotoSyncProvider provider = resolveProvider(providerType);
        activeProviderType.put(accountId, provider.providerType());

        resetCancellation(accountId);

        String syncTaskId = UUID.randomUUID().toString();
        activeSyncTaskId.put(accountId, syncTaskId);
        taskHistoryService.createTask(syncTaskId, "SYNC", accountId, provider.providerType(), 0);

        // TODO: extract to different method of prefetch.
        emitEvent(accountId, SyncPhase.FETCHING_METADATA, e -> e.setMetadataFetched(0));
        provider.prefetch(account.getSessionId());


        CompletableFuture.runAsync(
                () -> pollMetadataAndContinue(accountId, account, provider),
                syncVirtualThreadExecutor);

        return new SyncStartResponse(accountId, SyncPhase.FETCHING_METADATA, Messages.MSG_FETCHING_LIST, Instant.now());
    }

    /**
     * Confirm download after AWAITING_CONFIRMATION. Starts the actual download of pending photos.
     * Each photo carries its own sourceProvider so the correct client is used per download.
     */
    public void confirmSync(String accountId) {
        ICloudAccount account = requireAccount(accountId);
        requireSyncFolderPath(account);
        String providerType = activeProviderType.getOrDefault(accountId, "ICLOUD");
        resetCancellation(accountId);
        CompletableFuture.runAsync(() -> downloadPendingPhotosAsync(accountId, account, providerType), syncVirtualThreadExecutor);
    }

    /**
     * Cancel any in-progress sync (metadata polling or photo downloading).
     * Photos stuck in DOWNLOADING are reset to PENDING so they can be retried.
     */
    public void cancelSync(String accountId) {
        cancellationFlags.computeIfAbsent(accountId, k -> new AtomicBoolean(false)).set(true);
        resetDownloadingToPending(accountId);
        emitEvent(accountId, SyncPhase.CANCELLED, e -> {
        });
        String taskId = activeSyncTaskId.remove(accountId);
        if (taskId != null) {
            String providerType = activeProviderType.getOrDefault(accountId, "ICLOUD");
            int synced = (int) photoRepository.countByAccountIdAndSyncStatusAndSourceProvider(
                    accountId, com.cloudsync.model.enums.SyncStatus.SYNCED.name(), providerType);
            int failed = (int) photoRepository.countByAccountIdAndSyncStatusAndSourceProvider(
                    accountId, com.cloudsync.model.enums.SyncStatus.FAILED.name(), providerType);
            taskHistoryService.completeTask(taskId, "CANCELLED", synced, failed, null);
        }
        LOG.info(Messages.LOG_SYNC_CANCELLED, accountId);
    }

    /**
     * Preview which photos are outside the year/month folder structure.
     */
    public Map<String, Object> reorganizePreview(String accountId) {
        ICloudAccount account = requireAccount(accountId);
        Path basePath = Path.of(requireSyncFolderPath(account));
        List<Photo> candidates = findUnorganizedPhotos(accountId, basePath);

        List<String> samples = candidates.stream()
                .map(p -> Path.of(p.getFilePath()).getFileName().toString())
                .limit(5)
                .toList();

        List<String> estimatedFolders = candidates.stream()
                .map(p -> {
                    Path dest = resolveDestDir(account.getSyncFolderPath(), p);
                    return basePath.relativize(dest).toString();
                })
                .distinct()
                .sorted()
                .toList();

        return Map.of(
                "unorganizedCount", candidates.size(),
                "samples", samples,
                "estimatedFolders", estimatedFolders
        );
    }

    /**
     * Move unorganized photos into year/month subdirectories.
     */
    public Map<String, Object> reorganize(String accountId) {
        ICloudAccount account = requireAccount(accountId);
        Path basePath = Path.of(requireSyncFolderPath(account));
        List<Photo> candidates = findUnorganizedPhotos(accountId, basePath);

        int moved = 0;
        int errors = 0;
        for (int i = 0; i < candidates.size(); i += BATCH_SIZE) {
            List<Photo> batch = candidates.subList(i, Math.min(i + BATCH_SIZE, candidates.size()));
            for (Photo photo : batch) {
                try {
                    Path currentPath = Path.of(photo.getFilePath());
                    Path destDir = resolveDestDir(account.getSyncFolderPath(), photo);
                    Path destPath = destDir.resolve(currentPath.getFileName());
                    Files.createDirectories(destDir);
                    Files.move(currentPath, destPath);
                    photo.setFilePath(destPath.toString());
                    photoRepository.update(photo);
                    moved++;
                } catch (IOException e) {
                    LOG.error(Messages.LOG_DOWNLOAD_FAILED, photo.getId(), e.getMessage());
                    errors++;
                }
            }
        }

        return Map.of("moved", moved, "errors", errors);
    }

    private List<Photo> findUnorganizedPhotos(String accountId, Path basePath) {
        return photoRepository.findByAccountIdAndSyncedToDisk(accountId, true).stream()
                .filter(p -> p.getFilePath() != null)
                .filter(p -> {
                    Path filePath = Path.of(p.getFilePath());
                    if (!Files.exists(filePath)) return false;
                    Path parent = filePath.getParent();
                    if (parent == null) return true;
                    try {
                        Path rel = basePath.relativize(parent);
                        return !rel.toString().matches("\\d{4}[\\\\/]\\d{2}") && !rel.toString().matches("\\d{4}/\\d{2}");
                    } catch (IllegalArgumentException e) {
                        return true;
                    }
                })
                .toList();
    }

    /**
     * Delete photos from iCloud. Photos must already be synced to disk.
     */
    public void deleteFromICloud(String accountId, List<String> photoIds) {
        ICloudAccount account = requireAccount(accountId);
        PhotoSyncProvider iCloudProvider = resolveProvider("ICLOUD");
        for (String photoId : photoIds) {
            Photo photo = requirePhoto(photoId);
            requireSyncedToDisk(photo);
            if (photo.getIcloudPhotoId() != null) {
                iCloudProvider.deletePhoto(photo.getIcloudPhotoId(), account.getSessionId());
            }
            photo.setExistsOnIcloud(false);
            photoRepository.update(photo);
        }
    }

    /**
     * Delete photos from iPhone (marks existsOnIphone=false; physical deletion via USB not yet implemented).
     */
    public void deleteFromIPhone(String accountId, List<String> photoIds) {
        ICloudAccount account = requireAccount(accountId);
        PhotoSyncProvider iPhoneProvider = resolveProvider("IPHONE");
        for (String photoId : photoIds) {
            Photo photo = requirePhoto(photoId);
            requireSyncedToDisk(photo);
            photo.setExistsOnIphone(false);
            photoRepository.update(photo);

            // FIX Me -rollback if unsuccesful
            if (photo.getIphoneLocation() == null)
                throw new IllegalStateException(Messages.ERR_IPHONE_MISSING_LOCATION + photo.getId());
            iPhoneProvider.deletePhoto(photo.getIphoneLocation(), account.getSessionId());
        }
    }

    // ── cancellation ──────────────────────────────────────────────────────────

    private void resetCancellation(String accountId) {
        cancellationFlags.computeIfAbsent(accountId, k -> new AtomicBoolean(false)).set(false);
    }

    private boolean isCancelled(String accountId) {
        AtomicBoolean flag = cancellationFlags.get(accountId);
        return flag != null && flag.get();
    }

    private void resetDownloadingToPending(String accountId) {
        List<Photo> downloading = photoRepository.findByAccountIdAndSyncStatus(accountId, SyncStatus.DOWNLOADING.name());
        for (Photo photoInDownloadingState : downloading) {
            photoInDownloadingState.setSyncStatus(SyncStatus.PENDING.name());
            photoRepository.update(photoInDownloadingState);
        }
    }

    // ── metadata polling ──────────────────────────────────────────────────────

    private void pollMetadataAndContinue(String accountId, ICloudAccount account, PhotoSyncProvider provider) {
        try {

            // TODO: Maybe extract to some method.
            while (true) {
                Thread.sleep(1000);
                if (isCancelled(accountId)) return;

                PrefetchStatus status = provider.getPrefetchStatus(account.getSessionId());
                if (status == null) continue;

                emitMetadataProgress(accountId, status);

                if (Messages.FETCH_STATUS_READY.equals(status.status())) {
                    if (!isCancelled(accountId)) {
                        compareAndPersist(accountId, account, provider);
                    }
                    return;
                }
                if (Messages.FETCH_STATUS_ERROR.equals(status.status())) {
                    emitError(accountId, status.error());
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn(Messages.LOG_POLL_INTERRUPTED, accountId);
        } catch (Exception e) {
            LOG.error(Messages.LOG_POLL_FAILED, accountId, e.getMessage());
            emitError(accountId, e.getMessage());
        }
    }

    private void emitMetadataProgress(String accountId, PrefetchStatus status) {
        emitEvent(accountId, SyncPhase.FETCHING_METADATA, e -> {
            e.setMetadataFetched(status.fetched());
            e.setTotalOnCloud(status.total() != null ? status.total() : 0);
            if ("mounting".equals(status.status())) {
                e.setCurrentFile("MOUNTING");
            }
        });
    }

    // ── compare & persist ─────────────────────────────────────────────────────

    // TODO: Change the method name
    // TODO: Keep change logs -> Display them later.
    private void compareAndPersist(String accountId, ICloudAccount account, PhotoSyncProvider provider) {
        try {
            List<PhotoAsset> remotePhotos = provider.listAllPhotos(account.getSessionId());
            if (isCancelled(accountId)) return;

            Path destDir = Path.of(requireSyncFolderPath(account));
            Map<String, Long> diskFiles = scanDiskFiles(destDir);
            Map<String, Photo> externalIdToExistingPhoto = loadExistingPhotosAsMap(accountId, provider.providerType());
            Map<String, Photo> filenameToSyncedPhoto = loadSyncedPhotosByFilename(accountId);

            List<Photo> toSave = new ArrayList<>();
            List<Photo> toUpdate = new ArrayList<>();

            for (PhotoAsset asset : remotePhotos) {

                // TODO: One method to check all of the metadata and everything.

                if (checkSyncedAndUpdateMetadata(externalIdToExistingPhoto, asset, provider.providerType(), toUpdate))
                    continue;
                if (isAlreadyOnDisk(diskFiles, asset)) {
                    updateCrossProviderFlag(filenameToSyncedPhoto, asset, provider.providerType(), toUpdate);
                    continue;
                }
                classifyAsset(asset, accountId, account.getStorageDeviceId(), provider.providerType(), externalIdToExistingPhoto, toSave, toUpdate);
            }

            int newlyDeleted = markPhotosDeletedFromCloud(provider.providerType(), remotePhotos, externalIdToExistingPhoto, toUpdate);

            if (isCancelled(accountId)) return;

            emitEvent(accountId, SyncPhase.PERSISTING_METADATA, e -> e.setPending(toSave.size() + toUpdate.size()));
            saveInBatches(toSave);
            updateInBatches(toUpdate);

            if (!isCancelled(accountId)) {
                emitAwaitingConfirmation(accountId, remotePhotos.size(), newlyDeleted);
            }
        } catch (Exception e) {
            LOG.error(Messages.LOG_COMPARE_FAILED, accountId, e.getMessage());
            emitError(accountId, e.getMessage());
        }
    }

    private Map<String, Long> scanDiskFiles(Path dir) throws IOException {
        if (!Files.exists(dir)) return Map.of();
        Map<String, Long> result = new HashMap<>();
        try (var stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile).forEach(p -> {
                String key = p.getFileName().toString().toLowerCase(Locale.ROOT);
                try {
                    result.put(key, Files.size(p));
                } catch (IOException e) {
                    result.put(key, -1L);
                }
            });
        }
        return result;
    }

    private Path resolveDestDir(String basePath, Photo photo) {
        return MediaPathUtil.resolveDateDir(basePath, photo.getCreatedDate(), photo.getCreatedDateTimezone());
    }

    private Map<String, Photo> loadSyncedPhotosByFilename(String accountId) {
        // Include pending (synced_to_disk=false) records so cross-provider flag update fires before
        // a duplicate record can be created for the same physical file.
        return photoRepository.findByAccountId(accountId).stream()
                .filter(p -> p.getFilename() != null)
                .collect(Collectors.toMap(p -> p.getFilename().toLowerCase(Locale.ROOT), p -> p, (a, b) ->
                        b.isSyncedToDisk() ? b : a));
    }

    /**
     * When a remote asset is already on disk (matched by filename+size), update the cross-provider
     * existence flag on the existing DB record so we can tell which providers own each photo.
     * E.g. a photo synced from iCloud that also appears on iPhone gets existsOnIphone=true.
     */
    private void updateCrossProviderFlag(Map<String, Photo> syncedByFilename, PhotoAsset asset,
                                         String providerType, List<Photo> toUpdate) {
        String sanitized = sanitizeFilename(asset.filename());
        if (sanitized == null) return;
        Photo existing = syncedByFilename.get(sanitized.toLowerCase(Locale.ROOT));
        if (existing == null) {
            String legacy = stripVideoSuffix(sanitized);
            if (legacy != null) {
                existing = syncedByFilename.get(legacy.toLowerCase(Locale.ROOT));
            }
        }
        if (existing == null) return;
        // Guard against size mismatch (shouldn't happen — isAlreadyOnDisk already verified it, but be safe)
        if (existing.getFileSize() != null && asset.size() != null && !existing.getFileSize().equals(asset.size())) {
            return;
        }

        /**
         * Use enums. Extract to method. And do switch on providerType.
         */
        boolean changed = false;
        if ("IPHONE".equals(providerType)) {
            if (!Boolean.TRUE.equals(existing.getExistsOnIphone())) {
                existing.setExistsOnIphone(true);
                changed = true;
            }
            if (!asset.id().equals(existing.getIphoneLocation())) {
                existing.setIphoneLocation(asset.id());
                changed = true;
            }
        } else if ("ICLOUD".equals(providerType)) {
            if (!existing.isExistsOnIcloud()) {
                existing.setExistsOnIcloud(true);
                changed = true;
            }
            if (existing.getIcloudPhotoId() == null) {
                existing.setIcloudPhotoId(asset.id());
                changed = true;
            }
            if (asset.assetRecordName() != null && existing.getIcloudAssetRecordName() == null) {
                existing.setIcloudAssetRecordName(asset.assetRecordName());
                changed = true;
            }
            if (asset.assetToken() != null && existing.getAssetToken() == null) {
                existing.setAssetToken(asset.assetToken());
                changed = true;
            }
        }
        if (asset.createdDate() != null && !asset.createdDate().equals(existing.getCreatedDate())) {
            existing.setCreatedDate(asset.createdDate());
            changed = true;
        }
        if (changed) toUpdate.add(existing);
    }

    private Map<String, Photo> loadExistingPhotosAsMap(String accountId, String providerType) {
        return indexExistingByExternalId(photoRepository.findByAccountId(accountId), providerType);
    }

    /**
     * Index existing photos by their external (iCloud) id for the given provider.
     * <p>
     * Duplicate icloudPhotoIds can exist in the DB (e.g. a legacy null-source row plus a typed row
     * for the same asset, or two rows created by an earlier mis-targeted sync). A plain
     * {@code toMap} throws {@code IllegalStateException: Duplicate key …} on those, aborting the
     * whole compare-and-persist. Merge instead: keep the most "complete" row — prefer one already
     * synced to disk, then one with a SYNCED status — so the surviving record is the safest to update.
     */
    static Map<String, Photo> indexExistingByExternalId(List<Photo> photos, String providerType) {
        return photos.stream()
                .filter(p -> p.getIcloudPhotoId() != null)
                .filter(p -> providerType.equals(p.getSourceProvider()) || p.getSourceProvider() == null)
                .collect(Collectors.toMap(Photo::getIcloudPhotoId, p -> p, SyncService::preferMoreComplete));
    }

    private static Photo preferMoreComplete(Photo a, Photo b) {
        if (a.isSyncedToDisk() != b.isSyncedToDisk()) {
            return a.isSyncedToDisk() ? a : b;
        }
        boolean aSynced = SyncStatus.SYNCED.name().equals(a.getSyncStatus());
        boolean bSynced = SyncStatus.SYNCED.name().equals(b.getSyncStatus());
        if (aSynced != bSynced) {
            return aSynced ? a : b;
        }
        return a;
    }

    private boolean checkSyncedAndUpdateMetadata(Map<String, Photo> existingByExternalId,
                                                 PhotoAsset asset,
                                                 String providerType, List<Photo> toUpdate) {
        Photo existing = existingByExternalId.get(asset.id());

        // TODO: Don't skip previous errors
        if (existing == null || !SyncStatus.SYNCED.name().equals(existing.getSyncStatus())) {
            return false;
        }
        boolean changed = false;
        if (!existing.isExistsOnIcloud() && "ICLOUD".equals(providerType)) {
            existing.setExistsOnIcloud(true);
            changed = true;
        }
        if ("IPHONE".equals(providerType) && !asset.id().equals(existing.getIphoneLocation())) {
            existing.setIphoneLocation(asset.id());
            changed = true;
        }
        if (asset.createdDate() != null && !asset.createdDate().equals(existing.getCreatedDate())) {
            existing.setCreatedDate(asset.createdDate());
            changed = true;
        }
        if ("ICLOUD".equals(providerType) && asset.assetRecordName() != null
                && !asset.assetRecordName().equals(existing.getIcloudAssetRecordName())) {
            existing.setIcloudAssetRecordName(asset.assetRecordName());
            changed = true;
        }
        if (changed && !toUpdate.contains(existing)) {
            toUpdate.add(existing);
        }
        return true;
    }

    private int markPhotosDeletedFromCloud(String providerType, List<PhotoAsset> remotePhotos,
                                           Map<String, Photo> existingByExternalId, List<Photo> toUpdate) {
        Set<String> remoteIds = remotePhotos.stream().map(PhotoAsset::id).collect(Collectors.toSet());
        Set<String> alreadyQueued = toUpdate.stream().map(Photo::getId).collect(Collectors.toSet());
        int count = 0;
        for (Photo photo : existingByExternalId.values()) {
            if (alreadyQueued.contains(photo.getId())) continue;
            if ("ICLOUD".equals(providerType)
                    && photo.isExistsOnIcloud()
                    && !remoteIds.contains(photo.getIcloudPhotoId())) {
                photo.setExistsOnIcloud(false);
                toUpdate.add(photo);
                count++;
            } else if ("IPHONE".equals(providerType)
                    && Boolean.TRUE.equals(photo.getExistsOnIphone())
                    && !remoteIds.contains(photo.getIphoneLocation())
                    && !remoteIds.contains(photo.getIcloudPhotoId())) {
                photo.setExistsOnIphone(false);
                photo.setIphoneLocation(null);
                toUpdate.add(photo);
                count++;
            }
        }
        if (count > 0) LOG.info(Messages.LOG_PHOTOS_MARKED_DELETED, count);
        return count;
    }

    static boolean isAlreadyOnDisk(Map<String, Long> diskFiles, PhotoAsset asset) {
        String sanitized = sanitizeFilename(asset.filename());
        if (sanitized == null || asset.size() == null) return false;

        Long diskSize = diskFiles.get(sanitized.toLowerCase(Locale.ROOT));
        if (diskSize != null && diskSize.equals(asset.size())) return true;

        // Live Photo MOV: asset filename may carry _VIDEO suffix while disk holds the legacy
        // unsuffixed copy (downloaded before the suffix scheme existed). Match by name+size.
        String legacy = stripVideoSuffix(sanitized);
        if (legacy != null) {
            diskSize = diskFiles.get(legacy.toLowerCase(Locale.ROOT));
            return diskSize != null && diskSize.equals(asset.size());
        }
        return false;
    }

    static String stripVideoSuffix(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0) return null;
        String base = filename.substring(0, dot);
        if (!base.endsWith("_VIDEO")) return null;
        return base.substring(0, base.length() - "_VIDEO".length()) + filename.substring(dot);
    }

    private void classifyAsset(PhotoAsset asset, String accountId, String storageDeviceId,
                               String providerType, Map<String, Photo> existingByExternalId,
                               List<Photo> toSave, List<Photo> toUpdate) {
        Photo existing = existingByExternalId.get(asset.id());
        if (existing != null) {
            existing.setSyncStatus(SyncStatus.PENDING.name());
            if ("IPHONE".equals(providerType)) {
                existing.setIphoneLocation(asset.id());
            }
            toUpdate.add(existing);
        } else {
            toSave.add(buildPhoto(asset, accountId, storageDeviceId, providerType));
        }
    }

    private void saveInBatches(List<Photo> photos) {
        for (int i = 0; i < photos.size(); i += BATCH_SIZE) {
            List<Photo> batch = photos.subList(i, Math.min(i + BATCH_SIZE, photos.size()));
            photoRepository.saveAll(batch);
            LOG.debug(Messages.LOG_BATCH_SAVED, batch.size());
        }
    }

    private void updateInBatches(List<Photo> photos) {
        for (int i = 0; i < photos.size(); i += BATCH_SIZE) {
            List<Photo> batch = photos.subList(i, Math.min(i + BATCH_SIZE, photos.size()));
            photoRepository.updateAll(batch);
            LOG.debug(Messages.LOG_BATCH_UPDATED, batch.size());
        }
    }

    private void emitAwaitingConfirmation(String accountId, int totalOnCloud, int newlyDeleted) {
        String providerType = activeProviderType.getOrDefault(accountId, "ICLOUD");
        long pending = photoRepository.countByAccountIdAndSyncStatusAndSourceProvider(accountId, SyncStatus.PENDING.name(), providerType)
                + photoRepository.countByAccountIdAndSyncStatusAndSourceProvider(accountId, SyncStatus.FAILED.name(), providerType);
        long synced = photoRepository.countByAccountIdAndSyncStatusAndSourceProvider(accountId, SyncStatus.SYNCED.name(), providerType);
        emitEvent(accountId, SyncPhase.AWAITING_CONFIRMATION, e -> {
            e.setTotalOnCloud(totalOnCloud);
            e.setPending((int) pending);
            e.setSynced((int) synced);
            e.setNewlyDeleted(newlyDeleted);
        });
    }

    // ── download ──────────────────────────────────────────────────────────────

    private void downloadPendingPhotosAsync(String accountId, ICloudAccount account, String providerType) {
        lastEmitTime.remove(accountId);
        downloadedSinceEmit.remove(accountId);
        List<Photo> pending = Stream.concat(
                        photoRepository.findByAccountIdAndSyncStatus(accountId, SyncStatus.PENDING.name()).stream(),
                        photoRepository.findByAccountIdAndSyncStatus(accountId, SyncStatus.FAILED.name()).stream())
                .filter(p -> providerType.equals(p.getSourceProvider()))
                .distinct()
                .toList();

        List<Photo> regularPending = pending.stream().filter(p -> !isLargeVideoFile(p)).toList();
        List<Photo> largePending = pending.stream().filter(SyncService::isLargeVideoFile).toList();

        long alreadySynced = photoRepository.countByAccountIdAndSyncStatusAndSourceProvider(
                accountId, SyncStatus.SYNCED.name(), providerType);
        downloadTotalCache.put(accountId, regularPending.size() + alreadySynced);

        List<CompletableFuture<Void>> regularFutures = regularPending.stream()
                .map(photo -> CompletableFuture.runAsync(
                        () -> downloadWithSemaphore(photo, account, accountId, false),
                        syncVirtualThreadExecutor))
                .toList();

        CompletableFuture.allOf(regularFutures.toArray(new CompletableFuture[0]))
                .thenRun(() -> {
                    if (!isCancelled(accountId)) {
                        startLargeFilesPhase(accountId, account, providerType, largePending, pending);
                    }
                });
    }

    private void startLargeFilesPhase(String accountId, ICloudAccount account,
                                       String providerType, List<Photo> largePending, List<Photo> allPending) {
        if (largePending.isEmpty()) {
            finishDownload(accountId, providerType, allPending);
            return;
        }

        largeCompletedCount.put(accountId, new AtomicInteger(0));
        largeFailedCount.put(accountId, new AtomicInteger(0));
        largeTotalCount.put(accountId, largePending.size());

        emitEvent(accountId, SyncPhase.DOWNLOADING_LARGE, e -> {
            e.setSynced(0);
            e.setFailed(0);
            e.setPending(largePending.size());
            e.setTotalOnCloud(largePending.size());
            e.setPercentComplete(0);
        });

        List<CompletableFuture<Void>> largeFutures = largePending.stream()
                .map(photo -> CompletableFuture.runAsync(
                        () -> downloadWithSemaphore(photo, account, accountId, true),
                        syncVirtualThreadExecutor))
                .toList();

        CompletableFuture.allOf(largeFutures.toArray(new CompletableFuture[0]))
                .thenRun(() -> {
                    if (!isCancelled(accountId)) {
                        finishDownload(accountId, providerType, allPending);
                    }
                });
    }

    private void finishDownload(String accountId, String providerType, List<Photo> allPending) {
        if (isCancelled(accountId)) return;
        String thumbnailJobId = null;
        if ("IPHONE".equals(providerType)) {
            List<Photo> newlyDownloaded = allPending.stream()
                    .filter(p -> SyncStatus.SYNCED.name().equals(p.getSyncStatus()))
                    .filter(p -> p.getFilePath() != null)
                    .toList();
            backfillIPhoneExifDates(accountId, newlyDownloaded);
        }
        thumbnailJobId = startThumbnailJobForSync(accountId);
        if (!isCancelled(accountId)) {
            emitDoneEvent(accountId, thumbnailJobId);
        }
    }

    private static final int EXIF_BACKFILL_EMIT_INTERVAL = 200;

    void backfillIPhoneExifDates(String accountId, List<Photo> iphonePhotos) {
        if (iphonePhotos.isEmpty()) return;

        int total = iphonePhotos.size();
        LOG.info("EXIF backfill start: {} iPhone photos [account={}]", total, accountId);
        emitEvent(accountId, SyncPhase.EXIF_BACKFILL, e -> {
            e.setTotalOnCloud(total);
            e.setMetadataFetched(0);
            e.setPercentComplete(0);
        });

        int updated = 0;
        int processed = 0;
        for (Photo photo : iphonePhotos) {
            if (isCancelled(accountId)) return;
            try {
                Path file = Path.of(photo.getFilePath());
                if (!Files.exists(file)) { processed++; continue; }
                ExifDateUtil.CaptureDate capture = ExifDateUtil.readCaptureDateWithZone(file, null);
                Instant exifDate = capture.instant();
                String exifTz = ExifDateUtil.offsetId(capture.offset());
                if (exifDate != null
                        && (!exifDate.equals(photo.getCreatedDate())
                            || !java.util.Objects.equals(exifTz, photo.getCreatedDateTimezone()))) {
                    photo.setCreatedDate(exifDate);
                    photo.setCreatedDateTimezone(exifTz);
                    photoRepository.update(photo);
                    updated++;
                }
            } catch (Exception e) {
                LOG.warn("EXIF backfill failed for {}: {}", photo.getId(), e.getMessage());
            }
            processed++;
            if (processed % EXIF_BACKFILL_EMIT_INTERVAL == 0) {
                final int proc = processed;
                emitEvent(accountId, SyncPhase.EXIF_BACKFILL, e -> {
                    e.setTotalOnCloud(total);
                    e.setMetadataFetched(proc);
                    e.setPercentComplete((double) proc / total * 100);
                });
            }
        }
        LOG.info("EXIF backfill done: {} updated [account={}]", updated, accountId);
    }

    private void downloadWithSemaphore(Photo photo, ICloudAccount account, String accountId, boolean isLarge) {
        if (isCancelled(accountId)) return;
        Semaphore semaphore = "IPHONE".equals(photo.getSourceProvider()) ? iphoneDownloadSemaphore : downloadSemaphore;
        try {
            semaphore.acquire();
            try {
                if (isCancelled(accountId)) return;
                downloadOne(photo, account, accountId, isLarge);
            } finally {
                semaphore.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void downloadOne(Photo photo, ICloudAccount account, String accountId, boolean isLarge) {
        try {
            markDownloading(photo);

            PhotoSyncProvider provider = resolveProvider(photo.getSourceProvider());
            String photoId;
            if ("IPHONE".equals(photo.getSourceProvider())) {
                if (photo.getIphoneLocation() == null)
                    throw new IllegalStateException(Messages.ERR_IPHONE_MISSING_LOCATION + photo.getId());
                photoId = photo.getIphoneLocation();
            } else {
                photoId = photo.getIcloudPhotoId() != null ? photo.getIcloudPhotoId() : photo.getId();
            }
            byte[] data = isLarge
                    ? provider.downloadLargePhoto(photoId, account.getSessionId())
                    : provider.downloadPhoto(photoId, account.getSessionId());

            if (isCancelled(accountId)) {
                photo.setSyncStatus(SyncStatus.PENDING.name());
                photoRepository.update(photo);
                return;
            }

            String filename = resolveFilename(photo, photoId);
            Path destDir = resolveDestDir(account.getSyncFolderPath(), photo);
            Path destFile = writePhotoToDisk(data, destDir, filename);

            markSynced(photo, destFile, (long) data.length);
            if (isLarge) emitLargeProgress(accountId, true);
            else maybeEmitDownloadProgress(accountId);
        } catch (Exception e) {
            if (isCancelled(accountId)) {
                photo.setSyncStatus(SyncStatus.PENDING.name());
                photoRepository.update(photo);
                return;
            }
            LOG.error(Messages.LOG_MOVE_FAILED, photo.getId(), e.getMessage());
            markFailed(photo, accountId, isLarge);
        }
    }

    private void markDownloading(Photo photo) {
        photo.setSyncStatus(SyncStatus.DOWNLOADING.name());
        photoRepository.update(photo);
    }

    private String resolveFilename(Photo photo, String photoId) {
        String raw = photo.getFilename() != null ? photo.getFilename() : photoId + ".jpg";
        return sanitizeFilename(raw);
    }

    private Path writePhotoToDisk(byte[] data, Path destDir, String filename) throws IOException {
        Files.createDirectories(destDir);
        Path destFile = destDir.resolve(filename);
        Files.write(destFile, data);
        return destFile;
    }

    private void markSynced(Photo photo, Path destFile, long size) {
        photo.setFilePath(destFile.toString());
        photo.setFileSize(size);
        photo.setSyncedToDisk(true);
        photo.setSyncStatus(SyncStatus.SYNCED.name());
        photoRepository.update(photo);
    }

    private void markFailed(Photo photo, String accountId, boolean isLarge) {
        photo.setSyncStatus(SyncStatus.FAILED.name());
        photoRepository.update(photo);
        if (isLarge) emitLargeProgress(accountId, false);
        else maybeEmitDownloadProgress(accountId);
    }

    private void emitLargeProgress(String accountId, boolean synced) {
        AtomicInteger completed = largeCompletedCount.computeIfAbsent(accountId, k -> new AtomicInteger());
        AtomicInteger failed = largeFailedCount.computeIfAbsent(accountId, k -> new AtomicInteger());
        if (synced) completed.incrementAndGet();
        else failed.incrementAndGet();
        int total = largeTotalCount.getOrDefault(accountId, 0);
        int done = completed.get() + failed.get();
        emitEvent(accountId, SyncPhase.DOWNLOADING_LARGE, e -> {
            e.setSynced(completed.get());
            e.setFailed(failed.get());
            e.setPending(Math.max(0, total - done));
            e.setTotalOnCloud(total);
            e.setPercentComplete(total > 0 ? (double) done / total * 100 : 0);
        });
    }

    private void maybeEmitDownloadProgress(String accountId) {
        int count = downloadedSinceEmit.computeIfAbsent(accountId, k -> new AtomicInteger()).incrementAndGet();
        if (shouldEmitProgress(accountId, count)) {
            downloadedSinceEmit.get(accountId).set(0);
            lastEmitTime.put(accountId, Instant.now());
            emitDownloadProgress(accountId);
        }
    }

    private boolean shouldEmitProgress(String accountId, int completedSinceLastEmit) {
        if (completedSinceLastEmit >= EMIT_EVERY_N) return true;
        if (Duration.between(lastEmitTime.getOrDefault(accountId, Instant.EPOCH), Instant.now()).compareTo(EMIT_MAX_INTERVAL) > 0) return true;
        return isFinalBatch(accountId);
    }

    private boolean isFinalBatch(String accountId) {
        String providerType = activeProviderType.getOrDefault(accountId, "ICLOUD");
        long remaining = photoRepository.countByAccountIdAndSyncStatusAndSourceProvider(accountId, SyncStatus.PENDING.name(), providerType)
                + photoRepository.countByAccountIdAndSyncStatusAndSourceProvider(accountId, SyncStatus.DOWNLOADING.name(), providerType);
        return remaining < EMIT_EVERY_N;
    }

    private void emitDownloadProgress(String accountId) {
        String providerType = activeProviderType.getOrDefault(accountId, "ICLOUD");
        long synced = photoRepository.countByAccountIdAndSyncStatusAndSourceProvider(accountId, SyncStatus.SYNCED.name(), providerType);
        long failed = photoRepository.countByAccountIdAndSyncStatusAndSourceProvider(accountId, SyncStatus.FAILED.name(), providerType);
        long pending = photoRepository.countByAccountIdAndSyncStatusAndSourceProvider(accountId, SyncStatus.PENDING.name(), providerType)
                + photoRepository.countByAccountIdAndSyncStatusAndSourceProvider(accountId, SyncStatus.DOWNLOADING.name(), providerType);
        long total = downloadTotalCache.getOrDefault(accountId, synced + failed + pending);

        // Disk stats — read from AppContext (no extra I/O cost, uses cached value)
        Long diskFreeBytes = null;
        Long diskPhotoCount = null;
        try {
            var ctx = appContextService.getActive();
            if (ctx.isPresent()) {
                diskFreeBytes = ctx.get().freeBytes();
                diskPhotoCount = photoRepository.countBySyncedToDiskAndStorageDeviceId(true, ctx.get().storageDeviceId());
            }
        } catch (Exception ignored) {
        }

        final Long finalDiskFreeBytes = diskFreeBytes;
        final Long finalDiskPhotoCount = diskPhotoCount;

        emitEvent(accountId, SyncPhase.DOWNLOADING, e -> {
            e.setSynced((int) synced);
            e.setFailed((int) failed);
            e.setPending((int) pending);
            e.setTotalOnCloud((int) total);
            e.setPercentComplete(total > 0 ? (double) (synced + failed) / total * 100 : 0);
            e.setDiskFreeBytes(finalDiskFreeBytes);
            e.setDiskPhotoCount(finalDiskPhotoCount);
        });
    }

    private String startThumbnailJobForSync(String accountId) {
        List<String> candidateIds = photoRepository.findByAccountIdAndSyncedToDisk(accountId, true).stream()
                .filter(p -> p.getThumbnailPath() == null || !Files.exists(Path.of(p.getThumbnailPath())))
                .map(Photo::getId)
                .toList();
        if (candidateIds.isEmpty()) return null;
        return thumbnailJobService.startJob(null, candidateIds).jobId();
    }

    private void emitDoneEvent(String accountId, String thumbnailJobId) {
        long synced = photoRepository.countByAccountIdAndSyncStatus(accountId, SyncStatus.SYNCED.name());
        long failed = photoRepository.countByAccountIdAndSyncStatus(accountId, SyncStatus.FAILED.name());
        long total = photoRepository.countByAccountId(accountId);
        emitEvent(accountId, SyncPhase.DONE, e -> {
            e.setSynced((int) synced);
            e.setFailed((int) failed);
            e.setTotalOnCloud((int) total);
            e.setThumbnailJobId(thumbnailJobId);
            e.setPercentComplete(100.0);
        });
        accountRepository.findById(accountId).ifPresent(a -> {
            a.setLastSyncAt(Instant.now());
            accountRepository.update(a);
        });
        String taskId = activeSyncTaskId.remove(accountId);
        if (taskId != null) {
            taskHistoryService.completeTask(taskId, "COMPLETED", (int) synced, (int) failed, null);
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private PhotoSyncProvider resolveProvider(String providerType) {
        if (providerType == null) providerType = "ICLOUD";
        PhotoSyncProvider provider = providers.get(providerType.toUpperCase());
        if (provider == null) throw new IllegalArgumentException(Messages.ERR_UNKNOWN_PROVIDER + providerType);
        return provider;
    }

    private ICloudAccount requireAccount(String accountId) {
        return accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException(Messages.ERR_ACCOUNT_NOT_FOUND + accountId));
    }

    private String requireSyncFolderPath(ICloudAccount account) {
        String path = account.getSyncFolderPath();
        if (path == null || path.isBlank()) {
            throw new SyncFolderNotConfiguredException(account.getId());
        }
        return path;
    }

    private Photo requirePhoto(String photoId) {
        return photoRepository.findById(photoId)
                .orElseThrow(() -> new IllegalArgumentException(Messages.ERR_PHOTO_NOT_FOUND + photoId));
    }

    private void requireValidSession(ICloudAccount account) {
        if (account.getSessionId() == null) {
            throw new IllegalStateException(Messages.ERR_NO_ACTIVE_SESSION);
        }
    }

    private void requireSyncedToDisk(Photo photo) {
        if (!photo.isSyncedToDisk()) {
            throw new PhotoNotSyncedException(photo.getId());
        }
    }

    /**
     * Replace "/" in filename to prevent path traversal issues and broken icloud-service lookups.
     */
    static String sanitizeFilename(String filename) {
        if (filename == null) return null;
        return filename.replace("/", "_");
    }

    private void emitError(String accountId, String errorMessage) {
        String detail = (errorMessage == null || errorMessage.isBlank()) ? Messages.ERR_SYNC_FAILED : errorMessage;
        SyncProgressEvent event = new SyncProgressEvent(accountId, SyncPhase.ERROR);
        event.setErrorMessage(detail);
        syncStateHolder.updateAndEmit(accountId, event);
        String taskId = activeSyncTaskId.get(accountId);
        if (taskId != null) {
            taskHistoryService.recordSyncPhaseEnd(taskId, SyncPhase.ERROR.name(), detail);
            taskHistoryService.completeTask(taskId, "FAILED", 0, 0, detail);
        }
    }

    private void emitEvent(String accountId, SyncPhase phase, Consumer<SyncProgressEvent> configure) {
        SyncProgressEvent event = new SyncProgressEvent(accountId, phase);
        configure.accept(event);
        syncStateHolder.updateAndEmit(accountId, event);
        String taskId = activeSyncTaskId.get(accountId);
        if (taskId != null) {
            taskHistoryService.recordSyncPhaseStart(taskId, phase.name());
        }
    }

    private static final Set<String> VIDEO_EXTENSIONS = Set.of("mp4", "m4v", "mov", "avi", "mkv");

    private static boolean isLargeVideoFile(Photo photo) {
        return photo.getFilename() != null && photo.getFilename().toLowerCase(Locale.ROOT).endsWith(".mov");
    }

    private Photo buildPhoto(PhotoAsset asset, String accountId, String storageDeviceId, String providerType) {
        Photo p = new Photo();
        p.setId(UUID.randomUUID().toString());
        p.setIcloudPhotoId(asset.id());
        p.setIcloudAssetRecordName(asset.assetRecordName());
        p.setAccountId(accountId);
        p.setFilename(sanitizeFilename(asset.filename()));
        p.setFileSize(asset.size());
        p.setAssetToken(asset.assetToken());
        p.setCreatedDate(asset.createdDate());
        p.setImportedDate(Instant.now());
        p.setSyncStatus(SyncStatus.PENDING.name());
        p.setExistsOnIcloud("ICLOUD".equals(providerType));
        p.setExistsOnIphone("IPHONE".equals(providerType) ? Boolean.TRUE : null);
        p.setSourceProvider(providerType);
        if ("IPHONE".equals(providerType)) p.setIphoneLocation(asset.id());
        p.setMediaType(detectMediaType(asset.filename()));
        if (storageDeviceId != null) p.setStorageDeviceId(storageDeviceId);
        return p;
    }

    private static String detectMediaType(String filename) {
        if (filename == null) return MediaType.PHOTO.name();
        int dot = filename.lastIndexOf('.');
        if (dot < 0) return MediaType.PHOTO.name();
        String ext = filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        return VIDEO_EXTENSIONS.contains(ext) ? MediaType.VIDEO.name() : MediaType.PHOTO.name();
    }
}
