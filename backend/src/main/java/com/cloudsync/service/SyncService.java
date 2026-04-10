package com.cloudsync.service;

import com.cloudsync.client.ICloudServiceClient;
import com.cloudsync.exception.PhotoNotSyncedException;
import com.cloudsync.model.dto.AppContext;
import com.cloudsync.model.dto.ICloudPhotoAsset;
import com.cloudsync.model.dto.ICloudPhotoListResponse;
import com.cloudsync.model.dto.ICloudPrefetchStatus;
import com.cloudsync.model.dto.SyncProgressEvent;
import com.cloudsync.model.dto.SyncStartResponse;
import com.cloudsync.model.entity.ICloudAccount;
import com.cloudsync.model.entity.Photo;
import com.cloudsync.model.enums.SyncPhase;
import com.cloudsync.model.enums.SyncStatus;
import com.cloudsync.repository.AccountRepository;
import com.cloudsync.repository.PhotoRepository;
import io.micronaut.http.HttpResponse;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Singleton
public class SyncService {

    private static final Logger LOG = LoggerFactory.getLogger(SyncService.class);
    private static final int BATCH_SIZE = 100;
    private static final int PAGE_SIZE = 200;
    private static final int EMIT_EVERY_N = 5;
    private static final Duration EMIT_MAX_INTERVAL = Duration.ofSeconds(3);

    private final PhotoRepository photoRepository;
    private final AccountRepository accountRepository;
    private final ICloudServiceClient iCloudServiceClient;
    private final ThumbnailService thumbnailService;
    private final SyncStateHolder syncStateHolder;
    private final AppContextService appContextService;
    private final ExecutorService syncVirtualThreadExecutor;
    private final Semaphore downloadSemaphore = new Semaphore(50);
    private final ConcurrentHashMap<String, AtomicBoolean> cancellationFlags = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> lastEmitTime = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> downloadedSinceEmit = new ConcurrentHashMap<>();

    public SyncService(PhotoRepository photoRepository,
                       AccountRepository accountRepository,
                       ICloudServiceClient iCloudServiceClient,
                       ThumbnailService thumbnailService,
                       SyncStateHolder syncStateHolder,
                       AppContextService appContextService,
                       @Named("syncVirtualThreadExecutor") ExecutorService syncVirtualThreadExecutor) {
        this.photoRepository = photoRepository;
        this.accountRepository = accountRepository;
        this.iCloudServiceClient = iCloudServiceClient;
        this.thumbnailService = thumbnailService;
        this.syncStateHolder = syncStateHolder;
        this.appContextService = appContextService;
        this.syncVirtualThreadExecutor = syncVirtualThreadExecutor;
    }

    // ── public API ────────────────────────────────────────────────────────────

    /**
     * Start async sync. Returns immediately after triggering prefetch.
     * Progress is broadcast via SSE through SyncStateHolder.
     */
    public SyncStartResponse startSync(String accountId) {
        AppContext ctx = appContextService.requireActive();
        ICloudAccount account = requireAccount(accountId);
        requireValidSession(account);

        resetCancellation(accountId);
        emitEvent(accountId, SyncPhase.FETCHING_METADATA, e -> e.setMetadataFetched(0));
        iCloudServiceClient.prefetchPhotos(account.getSessionId());
        CompletableFuture.runAsync(() -> pollMetadataAndContinue(accountId, account, ctx), syncVirtualThreadExecutor);

        return new SyncStartResponse(accountId, SyncPhase.FETCHING_METADATA, "Pobieranie listy z iCloud...", Instant.now());
    }

    /**
     * Confirm download after AWAITING_CONFIRMATION. Starts the actual download of pending photos.
     */
    public void confirmSync(String accountId) {
        ICloudAccount account = requireAccount(accountId);
        AppContext ctx = appContextService.requireActive();
        resetCancellation(accountId);
        CompletableFuture.runAsync(() -> downloadPendingPhotosAsync(accountId, account, ctx), syncVirtualThreadExecutor);
    }

    /**
     * Cancel any in-progress sync (metadata polling or photo downloading).
     * Photos stuck in DOWNLOADING are reset to PENDING so they can be retried.
     */
    public void cancelSync(String accountId) {
        cancellationFlags.computeIfAbsent(accountId, k -> new AtomicBoolean(false)).set(true);
        resetDownloadingToPending(accountId);
        emitEvent(accountId, SyncPhase.CANCELLED, e -> {});
        LOG.info("Sync cancelled for account {}", accountId);
    }

    /**
     * Preview which photos are outside the year/month folder structure.
     */
    public Map<String, Object> reorganizePreview(String accountId) {
        AppContext ctx = appContextService.requireActive();
        Path basePath = Path.of(ctx.basePath());
        List<Photo> candidates = findUnorganizedPhotos(accountId, basePath);

        List<String> samples = candidates.stream()
                .map(p -> Path.of(p.getFilePath()).getFileName().toString())
                .limit(5)
                .toList();

        List<String> estimatedFolders = candidates.stream()
                .map(p -> {
                    Path dest = resolveDestDir(ctx.basePath(), p.getCreatedDate());
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
        AppContext ctx = appContextService.requireActive();
        Path basePath = Path.of(ctx.basePath());
        List<Photo> candidates = findUnorganizedPhotos(accountId, basePath);

        int moved = 0;
        int errors = 0;
        for (int i = 0; i < candidates.size(); i += BATCH_SIZE) {
            List<Photo> batch = candidates.subList(i, Math.min(i + BATCH_SIZE, candidates.size()));
            for (Photo photo : batch) {
                try {
                    Path currentPath = Path.of(photo.getFilePath());
                    Path destDir = resolveDestDir(ctx.basePath(), photo.getCreatedDate());
                    Path destPath = destDir.resolve(currentPath.getFileName());
                    Files.createDirectories(destDir);
                    Files.move(currentPath, destPath);
                    photo.setFilePath(destPath.toString());
                    photoRepository.update(photo);
                    moved++;
                } catch (IOException e) {
                    LOG.error("Failed to move photo {}: {}", photo.getId(), e.getMessage());
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
                    // File is unorganized if its parent is not a yyyy/MM directory under basePath
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
        for (String photoId : photoIds) {
            Photo photo = requirePhoto(photoId);
            requireSyncedToDisk(photo);
            if (photo.getIcloudPhotoId() != null) {
                iCloudServiceClient.deletePhoto(photo.getIcloudPhotoId(), account.getSessionId());
            }
            photo.setExistsOnIcloud(false);
            photoRepository.update(photo);
        }
    }

    /**
     * Delete photos from iPhone (via icloud-service devices endpoint).
     */
    public void deleteFromIPhone(String accountId, List<String> photoIds) {
        for (String photoId : photoIds) {
            Photo photo = requirePhoto(photoId);
            requireSyncedToDisk(photo);
            photo.setExistsOnIphone(false);
            photoRepository.update(photo);
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
        for (Photo p : downloading) {
            p.setSyncStatus(SyncStatus.PENDING.name());
            photoRepository.update(p);
        }
    }

    // ── metadata polling ──────────────────────────────────────────────────────

    private void pollMetadataAndContinue(String accountId, ICloudAccount account, AppContext ctx) {
        try {
            while (true) {
                Thread.sleep(1000);
                if (isCancelled(accountId)) return;

                ICloudPrefetchStatus status = fetchPrefetchStatus(account.getSessionId());
                if (status == null) continue;

                emitMetadataProgress(accountId, status);

                if ("ready".equals(status.status())) {
                    if (!isCancelled(accountId)) compareAndPersist(accountId, account, ctx);
                    return;
                }
                if ("error".equals(status.status())) {
                    emitError(accountId);
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("pollMetadataAndContinue interrupted for account {}", accountId);
        } catch (Exception e) {
            LOG.error("pollMetadataAndContinue failed for account {}: {}", accountId, e.getMessage());
            emitError(accountId);
        }
    }

    private ICloudPrefetchStatus fetchPrefetchStatus(String sessionId) {
        HttpResponse<ICloudPrefetchStatus> resp = iCloudServiceClient.getPrefetchStatus(sessionId);
        return resp.body();
    }

    private void emitMetadataProgress(String accountId, ICloudPrefetchStatus status) {
        emitEvent(accountId, SyncPhase.FETCHING_METADATA, e -> {
            e.setMetadataFetched(status.fetched());
            e.setTotalOnCloud(status.total() != null ? status.total() : 0);
        });
    }

    // ── compare & persist ─────────────────────────────────────────────────────

    private void compareAndPersist(String accountId, ICloudAccount account, AppContext ctx) {
        try {
            List<ICloudPhotoAsset> iCloudPhotos = fetchAllICloudPhotos(account.getSessionId());
            if (isCancelled(accountId)) return;

            Path destDir = Path.of(ctx.basePath());
            Map<String, Long> diskFiles = scanDiskFiles(destDir);
            Map<String, Photo> existingByIcloudId = loadExistingPhotosAsMap(accountId);

            List<Photo> toSave = new ArrayList<>();
            List<Photo> toUpdate = new ArrayList<>();

            for (ICloudPhotoAsset asset : iCloudPhotos) {
                System.out.println(asset.createdDateMs());
                if (isAlreadySynced(existingByIcloudId, asset)) continue;
                if (isAlreadyOnDisk(diskFiles, asset)) continue;
                classifyAsset(asset, accountId, ctx.storageDeviceId(), existingByIcloudId, toSave, toUpdate);
            }

            if (isCancelled(accountId)) return;

            emitEvent(accountId, SyncPhase.PERSISTING_METADATA, e -> e.setPending(toSave.size() + toUpdate.size()));
            saveInBatches(toSave);
            updateInBatches(toUpdate);

            if (!isCancelled(accountId)) {
                emitAwaitingConfirmation(accountId, iCloudPhotos.size());
            }
        } catch (Exception e) {
            LOG.error("compareAndPersist failed for {}: {}", accountId, e.getMessage());
            emitError(accountId);
        }
    }

    private List<ICloudPhotoAsset> fetchAllICloudPhotos(String sessionId) {
        List<ICloudPhotoAsset> all = new ArrayList<>();
        int offset = 0;
        while (true) {
            HttpResponse<ICloudPhotoListResponse> resp = iCloudServiceClient.listPhotos(sessionId, PAGE_SIZE, offset);
            ICloudPhotoListResponse page = resp.body();
            List<ICloudPhotoAsset> batch = (page != null && page.photos() != null) ? page.photos() : List.of();
            all.addAll(batch);
            if (batch.size() < PAGE_SIZE) break;
            offset += PAGE_SIZE;
        }
        return all;
    }

    private Map<String, Long> scanDiskFiles(Path dir) throws IOException {
        if (!Files.exists(dir)) return Map.of();
        Map<String, Long> result = new HashMap<>();
        try (var stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile).forEach(p -> {
                try {
                    result.put(p.getFileName().toString(), Files.size(p));
                } catch (IOException e) {
                    result.put(p.getFileName().toString(), -1L);
                }
            });
        }
        return result;
    }

    private Path resolveDestDir(String basePath, Instant createdDate) {
        if (createdDate == null) {
            return Path.of(basePath, "unknown");
        }
        LocalDate date = createdDate.atZone(ZoneId.systemDefault()).toLocalDate();
        return Path.of(basePath,
                String.valueOf(date.getYear()),
                String.format("%02d", date.getMonthValue()));
    }

    private Map<String, Photo> loadExistingPhotosAsMap(String accountId) {
        return photoRepository.findByAccountId(accountId).stream()
                .filter(p -> p.getIcloudPhotoId() != null)
                .collect(Collectors.toMap(Photo::getIcloudPhotoId, p -> p));
    }

    private boolean isAlreadySynced(Map<String, Photo> existingByIcloudId, ICloudPhotoAsset asset) {
        Photo existing = existingByIcloudId.get(asset.id());
        return existing != null && SyncStatus.SYNCED.name().equals(existing.getSyncStatus());
    }

    private boolean isAlreadyOnDisk(Map<String, Long> diskFiles, ICloudPhotoAsset asset) {
        String sanitized = sanitizeFilename(asset.filename());
        Long diskSize = diskFiles.get(sanitized);
        return diskSize != null && asset.size() != null && diskSize.equals(asset.size());
    }

    private void classifyAsset(ICloudPhotoAsset asset, String accountId, String storageDeviceId,
                               Map<String, Photo> existingByIcloudId,
                               List<Photo> toSave, List<Photo> toUpdate) {
        Photo existing = existingByIcloudId.get(asset.id());
        if (existing != null) {
            existing.setSyncStatus(SyncStatus.PENDING.name());
            toUpdate.add(existing);
        } else {
            toSave.add(buildPhoto(asset, accountId, storageDeviceId));
        }
    }

    private void saveInBatches(List<Photo> photos) {
        for (int i = 0; i < photos.size(); i += BATCH_SIZE) {
            List<Photo> batch = photos.subList(i, Math.min(i + BATCH_SIZE, photos.size()));
            photoRepository.saveAll(batch);
            LOG.debug("Saved batch of {} new photos", batch.size());
        }
    }

    private void updateInBatches(List<Photo> photos) {
        for (int i = 0; i < photos.size(); i += BATCH_SIZE) {
            List<Photo> batch = photos.subList(i, Math.min(i + BATCH_SIZE, photos.size()));
            photoRepository.updateAll(batch);
            LOG.debug("Updated batch of {} photos to PENDING", batch.size());
        }
    }

    private void emitAwaitingConfirmation(String accountId, int totalOnCloud) {
        long pending = photoRepository.countByAccountIdAndSyncStatus(accountId, SyncStatus.PENDING.name());
        long synced = photoRepository.countByAccountIdAndSyncStatus(accountId, SyncStatus.SYNCED.name());
        emitEvent(accountId, SyncPhase.AWAITING_CONFIRMATION, e -> {
            e.setTotalOnCloud(totalOnCloud);
            e.setPending((int) pending);
            e.setSynced((int) synced);
        });
    }

    // ── download ──────────────────────────────────────────────────────────────

    private void downloadPendingPhotosAsync(String accountId, ICloudAccount account, AppContext ctx) {
        lastEmitTime.remove(accountId);
        downloadedSinceEmit.remove(accountId);
        List<Photo> pending = photoRepository.findByAccountIdAndSyncStatus(accountId, SyncStatus.PENDING.name());

        List<CompletableFuture<Void>> futures = pending.stream()
                .map(photo -> CompletableFuture.runAsync(
                        () -> downloadWithSemaphore(photo, account, accountId, ctx),
                        syncVirtualThreadExecutor))
                .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenRun(() -> {
                    if (!isCancelled(accountId)) {
                        generateThumbnailsPhase(accountId);
                        emitDoneEvent(accountId);
                    }
                });
    }

    private void downloadWithSemaphore(Photo photo, ICloudAccount account, String accountId, AppContext ctx) {
        if (isCancelled(accountId)) return;
        try {
            downloadSemaphore.acquire();
            try {
                if (isCancelled(accountId)) return;
                downloadOne(photo, account, accountId, ctx);
            } finally {
                downloadSemaphore.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void downloadOne(Photo photo, ICloudAccount account, String accountId, AppContext ctx) {
        try {
            markDownloading(photo);

            String photoId = photo.getIcloudPhotoId() != null ? photo.getIcloudPhotoId() : photo.getId();
            byte[] data = downloadPhotoData(photoId, account.getSessionId());

            if (isCancelled(accountId)) {
                photo.setSyncStatus(SyncStatus.PENDING.name());
                photoRepository.update(photo);
                return;
            }

            String filename = resolveFilename(photo, photoId);
            Path destDir = resolveDestDir(ctx.basePath(), photo.getCreatedDate());
            Path destFile = writePhotoToDisk(data, destDir, filename);

            markSynced(photo, destFile, (long) data.length);
            maybeEmitDownloadProgress(accountId);
        } catch (Exception e) {
            if (isCancelled(accountId)) {
                photo.setSyncStatus(SyncStatus.PENDING.name());
                photoRepository.update(photo);
                return;
            }
            LOG.error("Failed to download photo {}: {}", photo.getId(), e.getMessage());
            markFailed(photo, accountId);
        }
    }

    private void markDownloading(Photo photo) {
        photo.setSyncStatus(SyncStatus.DOWNLOADING.name());
        photoRepository.update(photo);
    }

    private byte[] downloadPhotoData(String photoId, String sessionId) throws IOException {
        HttpResponse<byte[]> response = iCloudServiceClient.downloadPhoto(photoId, sessionId);
        byte[] data = response.body();
        if (data == null) throw new IOException("Empty response for photo " + photoId);
        return data;
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

    private void markFailed(Photo photo, String accountId) {
        photo.setSyncStatus(SyncStatus.FAILED.name());
        photoRepository.update(photo);
        maybeEmitDownloadProgress(accountId);
    }

    private void maybeEmitDownloadProgress(String accountId) {
        int count = downloadedSinceEmit.computeIfAbsent(accountId, k -> new AtomicInteger()).incrementAndGet();
        Instant last = lastEmitTime.getOrDefault(accountId, Instant.EPOCH);
        if (count >= EMIT_EVERY_N || Duration.between(last, Instant.now()).compareTo(EMIT_MAX_INTERVAL) > 0) {
            downloadedSinceEmit.get(accountId).set(0);
            lastEmitTime.put(accountId, Instant.now());
            emitDownloadProgress(accountId);
        }
    }

    private void emitDownloadProgress(String accountId) {
        long synced = photoRepository.countByAccountIdAndSyncStatus(accountId, SyncStatus.SYNCED.name());
        long failed = photoRepository.countByAccountIdAndSyncStatus(accountId, SyncStatus.FAILED.name());
        long pending = photoRepository.countByAccountIdAndSyncStatus(accountId, SyncStatus.PENDING.name())
                + photoRepository.countByAccountIdAndSyncStatus(accountId, SyncStatus.DOWNLOADING.name());
        long total = synced + failed + pending;
        emitEvent(accountId, SyncPhase.DOWNLOADING, e -> {
            e.setSynced((int) synced);
            e.setFailed((int) failed);
            e.setPending((int) pending);
            e.setTotalOnCloud((int) total);
            e.setPercentComplete(total > 0 ? (double) (synced + failed) / total * 100 : 0);
        });
    }

    private void generateThumbnailsPhase(String accountId) {
        List<Photo> candidates = photoRepository.findByAccountIdAndSyncedToDisk(accountId, true).stream()
                .filter(p -> p.getThumbnailPath() == null || !Files.exists(Path.of(p.getThumbnailPath())))
                .toList();

        if (candidates.isEmpty()) return;

        int total = candidates.size();
        AtomicInteger done = new AtomicInteger(0);

        emitEvent(accountId, SyncPhase.GENERATING_THUMBNAILS, e -> {
            e.setPending(total);
            e.setSynced(0);
            e.setPercentComplete(0.0);
        });

        thumbnailService.generateMissing(candidates, photo -> {
            int d = done.incrementAndGet();
            emitEvent(accountId, SyncPhase.GENERATING_THUMBNAILS, e -> {
                e.setSynced(d);
                e.setPending(total - d);
                e.setPercentComplete((double) d / total * 100);
            });
        });
    }

    private void emitDoneEvent(String accountId) {
        long synced = photoRepository.countByAccountIdAndSyncStatus(accountId, SyncStatus.SYNCED.name());
        long failed = photoRepository.countByAccountIdAndSyncStatus(accountId, SyncStatus.FAILED.name());
        long total = photoRepository.countByAccountId(accountId);
        emitEvent(accountId, SyncPhase.DONE, e -> {
            e.setSynced((int) synced);
            e.setFailed((int) failed);
            e.setTotalOnCloud((int) total);
            e.setPercentComplete(100.0);
        });
        accountRepository.findById(accountId).ifPresent(a -> {
            a.setLastSyncAt(Instant.now());
            accountRepository.update(a);
        });
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ICloudAccount requireAccount(String accountId) {
        return accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + accountId));
    }

    private Photo requirePhoto(String photoId) {
        return photoRepository.findById(photoId)
                .orElseThrow(() -> new IllegalArgumentException("Photo not found: " + photoId));
    }

    private void requireValidSession(ICloudAccount account) {
        if (account.getSessionId() == null) {
            throw new IllegalStateException("Account has no active session. Please log in first.");
        }
    }

    private void requireSyncedToDisk(Photo photo) {
        if (!photo.isSyncedToDisk()) {
            throw new PhotoNotSyncedException(photo.getId());
        }
    }

    /** Replace "/" in filename to prevent path traversal issues and broken icloud-service lookups. */
    private String sanitizeFilename(String filename) {
        if (filename == null) return null;
        return filename.replace("/", "_");
    }

    private void emitError(String accountId) {
        syncStateHolder.updateAndEmit(accountId, new SyncProgressEvent(accountId, SyncPhase.ERROR));
    }

    private void emitEvent(String accountId, SyncPhase phase, Consumer<SyncProgressEvent> configure) {
        SyncProgressEvent event = new SyncProgressEvent(accountId, phase);
        configure.accept(event);
        syncStateHolder.updateAndEmit(accountId, event);
    }

    private Photo buildPhoto(ICloudPhotoAsset asset, String accountId, String storageDeviceId) {
        Photo p = new Photo();
        p.setId(UUID.randomUUID().toString());
        p.setIcloudPhotoId(asset.id());
        p.setAccountId(accountId);
        p.setFilename(sanitizeFilename(asset.filename()));
        p.setFileSize(asset.size());
        p.setAssetToken(asset.assetToken());
        p.setCreatedDate(asset.createdDate());
        p.setImportedDate(Instant.now());
        p.setSyncStatus(SyncStatus.PENDING.name());
        p.setExistsOnIcloud(true);
        if (storageDeviceId != null) p.setStorageDeviceId(storageDeviceId);
        return p;
    }
}
