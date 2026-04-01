package com.cloudsync.service;

import com.cloudsync.client.ICloudServiceClient;
import com.cloudsync.exception.DriveNotAvailableException;
import com.cloudsync.exception.PhotoNotSyncedException;
import com.cloudsync.model.entity.ICloudAccount;
import com.cloudsync.model.entity.Photo;
import com.cloudsync.repository.AccountRepository;
import com.cloudsync.repository.PhotoRepository;
import io.micronaut.context.annotation.Value;
import io.micronaut.http.HttpResponse;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Singleton
public class SyncService {

    private static final Logger LOG = LoggerFactory.getLogger(SyncService.class);

    private final PhotoRepository photoRepository;
    private final AccountRepository accountRepository;
    private final ICloudServiceClient iCloudServiceClient;
    private final ThumbnailService thumbnailService;
    private final DiskSetupService diskSetupService;

    @Value("${EXTERNAL_DRIVE_PATH:/mnt/external-drive}")
    private String externalDrivePath;

    public SyncService(PhotoRepository photoRepository,
                       AccountRepository accountRepository,
                       ICloudServiceClient iCloudServiceClient,
                       ThumbnailService thumbnailService,
                       DiskSetupService diskSetupService) {
        this.photoRepository = photoRepository;
        this.accountRepository = accountRepository;
        this.iCloudServiceClient = iCloudServiceClient;
        this.thumbnailService = thumbnailService;
        this.diskSetupService = diskSetupService;
    }

    /**
     * Synchronize photos from iCloud to external disk for a given account.
     * Downloads new photos not yet on disk.
     *
     * @return count of synced photos
     */
    public int syncFromICloud(String accountId) {
        ICloudAccount account = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + accountId));

        checkDriveAvailable();

        if (account.getSessionId() == null) {
            throw new IllegalStateException("Account has no active session. Please log in first.");
        }

        HttpResponse<Map<String, Object>> response = iCloudServiceClient.listPhotos(account.getSessionId(), 1000, 0);
        Map<String, Object> body = response.body();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> iCloudPhotos = (List<Map<String, Object>>) body.getOrDefault("photos", List.of());

        int synced = 0;
        for (Map<String, Object> iCloudPhoto : iCloudPhotos) {
            String iCloudId = String.valueOf(iCloudPhoto.get("id"));
            Optional<Photo> existing = photoRepository.findByIcloudPhotoId(iCloudId);
            if (existing.isPresent() && existing.get().isSyncedToDisk()) {
                continue;
            }

            try {
                Photo photo = downloadAndSave(account, iCloudPhoto, iCloudId);
                thumbnailService.generateThumbnailAsync(photo);
                synced++;
            } catch (Exception e) {
                LOG.error("Failed to sync photo {}: {}", iCloudId, e.getMessage());
            }
        }

        account.setLastSyncAt(Instant.now());
        accountRepository.update(account);
        return synced;
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
     * // OPEN: iPhone deletion not yet implemented in icloud-service.
     */
    public void deleteFromIPhone(String accountId, List<String> photoIds) {
        for (String photoId : photoIds) {
            Photo photo = photoRepository.findById(photoId)
                    .orElseThrow(() -> new IllegalArgumentException("Photo not found: " + photoId));

            if (!photo.isSyncedToDisk()) {
                throw new PhotoNotSyncedException(photoId);
            }
            // TODO: call icloud-service device endpoint when available
            photo.setExistsOnIphone(false);
            photoRepository.update(photo);
        }
    }

    private Photo downloadAndSave(ICloudAccount account, Map<String, Object> meta, String iCloudId)
            throws IOException {
        HttpResponse<byte[]> download = iCloudServiceClient.downloadPhoto(iCloudId, account.getSessionId());
        byte[] data = download.body();
        if (data == null) throw new IOException("Empty response for photo " + iCloudId);

        String filename = String.valueOf(meta.getOrDefault("filename", iCloudId + ".jpg"));
        Path destDir = Path.of(externalDrivePath, "photos", account.getId());
        Files.createDirectories(destDir);
        Path destFile = destDir.resolve(filename);
        Files.write(destFile, data);

        Optional<Photo> existing = photoRepository.findByIcloudPhotoId(iCloudId);
        Photo photo = existing.orElseGet(() -> {
            Photo p = new Photo();
            p.setId(UUID.randomUUID().toString());
            p.setIcloudPhotoId(iCloudId);
            p.setAccountId(account.getId());
            p.setImportedDate(Instant.now());
            return p;
        });

        photo.setFilename(filename);
        photo.setFilePath(destFile.toString());
        photo.setFileSize((long) data.length);
        photo.setSyncedToDisk(true);
        photo.setExistsOnIcloud(true);
        photo.setMediaType(String.valueOf(meta.getOrDefault("media_type", "PHOTO")));
        diskSetupService.findMountedDevice().ifPresent(d -> photo.setStorageDeviceId(d.getId()));

        if (existing.isPresent()) {
            photoRepository.update(photo);
        } else {
            photoRepository.save(photo);
        }
        return photo;
    }

    private void checkDriveAvailable() {
        Path drive = Path.of(externalDrivePath);
        if (!Files.exists(drive) || !Files.isDirectory(drive)) {
            throw new DriveNotAvailableException("External drive not available at: " + externalDrivePath);
        }
    }
}
