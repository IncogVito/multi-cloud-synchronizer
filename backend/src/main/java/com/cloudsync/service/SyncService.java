package com.cloudsync.service;

import com.cloudsync.client.ICloudServiceClient;
import com.cloudsync.exception.DriveNotAvailableException;
import com.cloudsync.exception.PhotoNotSyncedException;
import com.cloudsync.model.dto.ICloudPhotoAsset;
import com.cloudsync.model.dto.ICloudPrefetchStatus;
import com.cloudsync.model.dto.SyncProgressEvent;
import com.cloudsync.model.dto.SyncStartResponse;
import com.cloudsync.model.entity.ICloudAccount;
import com.cloudsync.model.entity.Photo;
import com.cloudsync.model.enums.SyncPhase;
import com.cloudsync.model.enums.SyncStatus;
import com.cloudsync.repository.AccountRepository;
import com.cloudsync.repository.PhotoRepository;
import io.micronaut.context.annotation.Value;
import io.micronaut.http.HttpResponse;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;

@Singleton
public class SyncService {

    private static final Logger LOG = LoggerFactory.getLogger(SyncService.class);

    private final PhotoRepository photoRepository;
    private final AccountRepository accountRepository;
    private final ICloudServiceClient iCloudServiceClient;
    private final ThumbnailService thumbnailService;
    private final DiskSetupService diskSetupService;
    private final SyncStateHolder syncStateHolder;
    private final AppContextService appContextService;
    private final ExecutorService syncVirtualThreadExecutor;
    private final Semaphore downloadSemaphore = new Semaphore(10);

    @Value("${EXTERNAL_DRIVE_PATH:/mnt/external-drive}")
    private String externalDrivePath;

    public SyncService(PhotoRepository photoRepository,
                       AccountRepository accountRepository,
                       ICloudServiceClient iCloudServiceClient,
                       ThumbnailService thumbnailService,
                       DiskSetupService diskSetupService,
                       SyncStateHolder syncStateHolder,
                       AppContextService appContextService,
                       @Named("syncVirtualThreadExecutor") ExecutorService syncVirtualThreadExecutor) {
        this.photoRepository = photoRepository;
        this.accountRepository = accountRepository;
        this.iCloudServiceClient = iCloudServiceClient;
        this.thumbnailService = thumbnailService;
        this.diskSetupService = diskSetupService;
        this.syncStateHolder = syncStateHolder;
        this.appContextService = appContextService;
        this.syncVirtualThreadExecutor = syncVirtualThreadExecutor;
    }

    /**
     * Start async sync. Returns immediately after triggering prefetch.
     * Progress is broadcast via SSE through SyncStateHolder.
     */
    public SyncStartResponse startSync(String accountId) {
        appContextService.requireActive();
        ICloudAccount account = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + accountId));

        checkDriveAvailable();

        if (account.getSessionId() == null) {
            throw new IllegalStateException("Account has no active session. Please log in first.");
        }

        SyncProgressEvent initialEvent = new SyncProgressEvent(accountId, SyncPhase.FETCHING_METADATA);
        initialEvent.setMetadataFetched(0);
        syncStateHolder.updateAndEmit(accountId, initialEvent);

        iCloudServiceClient.prefetchPhotos(account.getSessionId());

        CompletableFuture.runAsync(() -> pollMetadataAndContinue(accountId, account), syncVirtualThreadExecutor);

        return new SyncStartResponse(accountId, SyncPhase.FETCHING_METADATA,
                "Pobieranie listy z iCloud...", Instant.now());
    }

    private void pollMetadataAndContinue(String accountId, ICloudAccount account) {
        try {
            while (true) {
                Thread.sleep(1000);
                HttpResponse<ICloudPrefetchStatus> resp = iCloudServiceClient.getPrefetchStatus(account.getSessionId());
                ICloudPrefetchStatus status = resp.body();
                if (status == null) continue;

                SyncProgressEvent event = new SyncProgressEvent(accountId, SyncPhase.FETCHING_METADATA);
                event.setMetadataFetched(status.fetched());
                event.setTotalOnCloud(status.total() != null ? status.total() : 0);
                syncStateHolder.updateAndEmit(accountId, event);

                if ("ready".equals(status.status())) {
                    compareAndPersist(accountId, account);
                    return;
                }
                if ("error".equals(status.status())) {
                    SyncProgressEvent errEvent = new SyncProgressEvent(accountId, SyncPhase.ERROR);
                    syncStateHolder.updateAndEmit(accountId, errEvent);
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("pollMetadataAndContinue interrupted for account {}", accountId);
        } catch (Exception e) {
            LOG.error("pollMetadataAndContinue failed for account {}: {}", accountId, e.getMessage());
            SyncProgressEvent errEvent = new SyncProgressEvent(accountId, SyncPhase.ERROR);
            syncStateHolder.updateAndEmit(accountId, errEvent);
        }
    }

    private void compareAndPersist(String accountId, ICloudAccount account) {
        try {
            HttpResponse<com.cloudsync.model.dto.ICloudPhotoListResponse> resp =
                    iCloudServiceClient.listPhotos(account.getSessionId(), 10000, 0);
            com.cloudsync.model.dto.ICloudPhotoListResponse listResp = resp.body();
            List<ICloudPhotoAsset> iCloudPhotos = listResp != null ? listResp.photos() : List.of();

            Path destDir = Path.of(externalDrivePath, "photos", accountId);
            Set<String> diskFiles = Set.of();
            if (Files.exists(destDir)) {
                try (var stream = Files.list(destDir)) {
                    diskFiles = stream.map(p -> p.getFileName().toString()).collect(Collectors.toSet());
                }
            }

            Set<String> syncedIds = photoRepository.findByAccountIdAndSyncStatus(accountId, SyncStatus.SYNCED.name())
                    .stream().map(Photo::getIcloudPhotoId).collect(Collectors.toSet());

            final Set<String> diskFilesF = diskFiles;
            int newCount = 0;
            for (ICloudPhotoAsset asset : iCloudPhotos) {
                if (syncedIds.contains(asset.id()) || diskFilesF.contains(asset.filename())) {
                    continue;
                }
                Optional<Photo> existing = photoRepository.findByIcloudPhotoId(asset.id());
                if (existing.isPresent()) {
                    Photo p = existing.get();
                    p.setSyncStatus(SyncStatus.PENDING.name());
                    photoRepository.update(p);
                } else {
                    Photo p = new Photo();
                    p.setId(UUID.randomUUID().toString());
                    p.setIcloudPhotoId(asset.id());
                    p.setAccountId(accountId);
                    p.setFilename(asset.filename());
                    p.setFileSize(asset.size());
                    p.setAssetToken(asset.assetToken());
                    p.setImportedDate(Instant.now());
                    p.setSyncStatus(SyncStatus.PENDING.name());
                    p.setExistsOnIcloud(true);
                    diskSetupService.findMountedDevice().ifPresent(d -> p.setStorageDeviceId(d.getId()));
                    photoRepository.save(p);
                    newCount++;
                }
            }

            long total = iCloudPhotos.size();
            long pending = photoRepository.countByAccountIdAndSyncStatus(accountId, SyncStatus.PENDING.name());
            long synced = photoRepository.countByAccountIdAndSyncStatus(accountId, SyncStatus.SYNCED.name());

            SyncProgressEvent event = new SyncProgressEvent(accountId, SyncPhase.COMPARING);
            event.setTotalOnCloud((int) total);
            event.setPending((int) pending);
            event.setSynced((int) synced);
            syncStateHolder.updateAndEmit(accountId, event);

            downloadPendingPhotosAsync(accountId, account);
        } catch (Exception e) {
            LOG.error("compareAndPersist failed for {}: {}", accountId, e.getMessage());
            SyncProgressEvent errEvent = new SyncProgressEvent(accountId, SyncPhase.ERROR);
            syncStateHolder.updateAndEmit(accountId, errEvent);
        }
    }

    private void downloadPendingPhotosAsync(String accountId, ICloudAccount account) {
        List<Photo> pending = photoRepository.findByAccountIdAndSyncStatus(accountId, SyncStatus.PENDING.name());

        List<CompletableFuture<Void>> futures = pending.stream()
                .map(photo -> CompletableFuture.runAsync(() -> {
                    try {
                        downloadSemaphore.acquire();
                        try {
                            downloadOne(photo, account, accountId);
                        } finally {
                            downloadSemaphore.release();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }, syncVirtualThreadExecutor))
                .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenRun(() -> {
                    long synced = photoRepository.countByAccountIdAndSyncStatus(accountId, SyncStatus.SYNCED.name());
                    long failed = photoRepository.countByAccountIdAndSyncStatus(accountId, SyncStatus.FAILED.name());
                    long total = photoRepository.countByAccountId(accountId);
                    SyncProgressEvent doneEvent = new SyncProgressEvent(accountId, SyncPhase.DONE);
                    doneEvent.setSynced((int) synced);
                    doneEvent.setFailed((int) failed);
                    doneEvent.setTotalOnCloud((int) total);
                    doneEvent.setPercentComplete(100.0);
                    syncStateHolder.updateAndEmit(accountId, doneEvent);

                    accountRepository.findById(accountId).ifPresent(a -> {
                        a.setLastSyncAt(Instant.now());
                        accountRepository.update(a);
                    });
                });
    }

    private void downloadOne(Photo photo, ICloudAccount account, String accountId) {
        try {
            photo.setSyncStatus(SyncStatus.DOWNLOADING.name());
            photoRepository.update(photo);

            SyncProgressEvent downloadingEvent = new SyncProgressEvent(accountId, SyncPhase.DOWNLOADING);
            downloadingEvent.setCurrentFile(photo.getFilename());
            syncStateHolder.updateAndEmit(accountId, downloadingEvent);

            String photoId = photo.getIcloudPhotoId() != null ? photo.getIcloudPhotoId() : photo.getId();
            HttpResponse<byte[]> download = iCloudServiceClient.downloadPhoto(photoId, account.getSessionId());
            byte[] data = download.body();
            if (data == null) throw new IOException("Empty response for photo " + photoId);

            String filename = photo.getFilename() != null ? photo.getFilename() : photoId + ".jpg";
            Path destDir = Path.of(externalDrivePath, "photos", accountId);
            Files.createDirectories(destDir);
            Path destFile = destDir.resolve(filename);
            Files.write(destFile, data);

            photo.setFilePath(destFile.toString());
            photo.setFileSize((long) data.length);
            photo.setSyncedToDisk(true);
            photo.setSyncStatus(SyncStatus.SYNCED.name());
            photoRepository.update(photo);
            thumbnailService.generateThumbnailAsync(photo);

            emitDownloadProgress(accountId);
        } catch (Exception e) {
            LOG.error("Failed to download photo {}: {}", photo.getId(), e.getMessage());
            photo.setSyncStatus(SyncStatus.FAILED.name());
            photoRepository.update(photo);
            emitDownloadProgress(accountId);
        }
    }

    private void emitDownloadProgress(String accountId) {
        long synced = photoRepository.countByAccountIdAndSyncStatus(accountId, SyncStatus.SYNCED.name());
        long failed = photoRepository.countByAccountIdAndSyncStatus(accountId, SyncStatus.FAILED.name());
        long pending = photoRepository.countByAccountIdAndSyncStatus(accountId, SyncStatus.PENDING.name())
                + photoRepository.countByAccountIdAndSyncStatus(accountId, SyncStatus.DOWNLOADING.name());
        long total = synced + failed + pending;

        SyncProgressEvent event = new SyncProgressEvent(accountId, SyncPhase.DOWNLOADING);
        event.setSynced((int) synced);
        event.setFailed((int) failed);
        event.setPending((int) pending);
        event.setTotalOnCloud((int) total);
        event.setPercentComplete(total > 0 ? (double) (synced + failed) / total * 100 : 0);
        syncStateHolder.updateAndEmit(accountId, event);
    }

    /**
     * Delete photos from iCloud. Photos must already be synced to disk.
     */
    public void deleteFromICloud(String accountId, List<String> photoIds) {
        ICloudAccount account = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + accountId));

        for (String photoId : photoIds) {
            Photo photo = photoRepository.findById(photoId)
                    .orElseThrow(() -> new IllegalArgumentException("Photo not found: " + photoId));

            if (!photo.isSyncedToDisk()) {
                throw new PhotoNotSyncedException(photoId);
            }
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
            Photo photo = photoRepository.findById(photoId)
                    .orElseThrow(() -> new IllegalArgumentException("Photo not found: " + photoId));

            if (!photo.isSyncedToDisk()) {
                throw new PhotoNotSyncedException(photoId);
            }
            photo.setExistsOnIphone(false);
            photoRepository.update(photo);
        }
    }

    private void checkDriveAvailable() {
        Path drive = Path.of(externalDrivePath);
        if (!Files.exists(drive) || !Files.isDirectory(drive)) {
            throw new DriveNotAvailableException("External drive not available at: " + externalDrivePath);
        }
    }
}
