package com.cloudsync.service;

import com.cloudsync.model.dto.DiskIndexProgressEvent;
import com.cloudsync.model.entity.ICloudAccount;
import com.cloudsync.model.entity.Photo;
import com.cloudsync.model.enums.MediaType;
import com.cloudsync.repository.AccountRepository;
import com.cloudsync.repository.PhotoRepository;
import com.cloudsync.repository.StorageDeviceRepository;
import com.cloudsync.util.ExifDateUtil;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * Account-scoped disk indexing (issue #8). Scans {@code account.getSyncFolderPath()} for media
 * files and persists them tagged with the account's id and storage device. Progress is broadcast
 * per-account via {@link DiskIndexStateHolder}.
 */
@Singleton
public class DiskIndexingService {

    private static final Logger LOG = LoggerFactory.getLogger(DiskIndexingService.class);

    private static final Set<String> MEDIA_EXTENSIONS = Set.of(
            "jpg", "jpeg", "heic", "heif", "png", "gif", "bmp", "tiff", "tif", "webp",
            "mp4", "m4v", "mov", "avi", "mkv", "3gp"
    );
    private static final Set<String> VIDEO_EXTENSIONS = Set.of("mp4", "m4v", "mov", "avi", "mkv", "3gp");

    private static final int BATCH_SIZE = 100;

    private final PhotoRepository photoRepository;
    private final AccountRepository accountRepository;
    private final DiskIndexStateHolder stateHolder;
    private final ExecutorService syncVirtualThreadExecutor;
    private final StorageDeviceRepository storageDeviceRepository;

    private final ConcurrentHashMap<String, AtomicBoolean> running = new ConcurrentHashMap<>();

    public DiskIndexingService(PhotoRepository photoRepository,
                               AccountRepository accountRepository,
                               DiskIndexStateHolder stateHolder,
                               @Named("syncVirtualThreadExecutor") ExecutorService syncVirtualThreadExecutor,
                               StorageDeviceRepository storageDeviceRepository) {
        this.photoRepository = photoRepository;
        this.accountRepository = accountRepository;
        this.stateHolder = stateHolder;
        this.syncVirtualThreadExecutor = syncVirtualThreadExecutor;
        this.storageDeviceRepository = storageDeviceRepository;
    }

    public boolean isRunning(String accountId) {
        AtomicBoolean flag = running.get(accountId);
        return flag != null && flag.get();
    }

    /**
     * Start async disk indexing for the given account. Scans {@code account.getSyncFolderPath()}
     * for media files and saves them to the DB. Progress is broadcast via SSE through
     * {@link DiskIndexStateHolder}.
     */
    public void startIndexing(String accountId) {
        ICloudAccount account = requireAccount(accountId);
        String folderPath = account.getSyncFolderPath();
        String storageDeviceId = account.getStorageDeviceId();
        AtomicBoolean flag = running.computeIfAbsent(accountId, k -> new AtomicBoolean(false));
        if (flag.compareAndSet(false, true)) {
            emitEvent(accountId, "SCANNING", 0, 0, 0.0, null, 0);
            CompletableFuture.runAsync(
                    () -> doIndex(accountId, folderPath, storageDeviceId),
                    syncVirtualThreadExecutor
            );
        }
    }

    private void doIndex(String accountId, String folderPath, String storageDeviceId) {
        try {
            if (folderPath == null) {
                emitEvent(accountId, "ERROR", 0, 0, 0.0, "Konto nie ma ustawionego folderu synchronizacji", 0);
                return;
            }
            Path root = Path.of(folderPath);
            if (!Files.isDirectory(root)) {
                emitEvent(accountId, "ERROR", 0, 0, 0.0, "Folder nie istnieje: " + folderPath, 0);
                return;
            }

            List<Path> mediaFiles = collectMediaFiles(root);
            int total = mediaFiles.size();
            LOG.info("DiskIndexing[{}]: found {} media files in {}", accountId, total, folderPath);

            // Reset syncedToDisk flag — will be re-set only for files actually found on disk
            List<Photo> currentlySynced = photoRepository.findByAccountIdAndSyncedToDisk(accountId, true);
            resetSyncedToDisk(currentlySynced);

            // Path → Photo map (all photos with a path) for reconciliation
            Map<String, Photo> existingByPath = buildExistingPhotosByPath(accountId);

            int scanned = 0;
            List<Photo> toSave = new ArrayList<>();
            List<Photo> toUpdate = new ArrayList<>();

            for (Path file : mediaFiles) {
                String absPath = file.toAbsolutePath().toString();
                Photo existing = existingByPath.get(absPath);

                if (existing != null) {
                    existing.setSyncedToDisk(true);
                    toUpdate.add(existing);
                } else {
                    Photo photo = buildPhotoFromFile(file, accountId, storageDeviceId);
                    if (photo != null) {
                        toSave.add(photo);
                        existingByPath.put(absPath, photo);
                    }
                }
                scanned++;

                if (toSave.size() >= BATCH_SIZE) {
                    photoRepository.saveAll(toSave);
                    toSave.clear();
                }
                if (toUpdate.size() >= BATCH_SIZE) {
                    photoRepository.updateAll(toUpdate);
                    toUpdate.clear();
                }

                if (scanned % 50 == 0 || scanned == total) {
                    double pct = total > 0 ? (double) scanned / total * 100 : 100.0;
                    emitEvent(accountId, "SCANNING", scanned, total, pct, null, 0);
                }
            }

            if (!toSave.isEmpty()) photoRepository.saveAll(toSave);
            if (!toUpdate.isEmpty()) photoRepository.updateAll(toUpdate);

            LOG.info("DiskIndexing[{}] complete: {} files processed", accountId, total);
            emitDoneEvent(accountId, scanned, total, 0);
            if (storageDeviceId != null) {
                storageDeviceRepository.findById(storageDeviceId).ifPresent(d -> {
                    d.setLastIndexedAt(Instant.now());
                    storageDeviceRepository.update(d);
                });
            }
        } catch (Exception e) {
            LOG.error("DiskIndexing[{}] failed: {}", accountId, e.getMessage(), e);
            emitEvent(accountId, "ERROR", 0, 0, 0.0, e.getMessage(), 0);
        } finally {
            AtomicBoolean flag = running.get(accountId);
            if (flag != null) flag.set(false);
        }
    }

    private void resetSyncedToDisk(List<Photo> photos) {
        List<Photo> batch = new ArrayList<>();
        for (Photo photo : photos) {
            photo.setSyncedToDisk(false);
            batch.add(photo);
            if (batch.size() >= BATCH_SIZE) {
                photoRepository.updateAll(batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) photoRepository.updateAll(batch);
    }

    private Map<String, Photo> buildExistingPhotosByPath(String accountId) {
        Map<String, Photo> map = new HashMap<>();
        try {
            photoRepository.findAllWithFilePathByAccountId(accountId)
                    .forEach(p -> map.put(p.getFilePath(), p));
        } catch (Exception e) {
            LOG.warn("Could not load existing photos by path: {}", e.getMessage());
        }
        return map;
    }

    private List<Path> collectMediaFiles(Path root) throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> isMediaFile(p.getFileName().toString()))
                .forEach(files::add);
        }
        return files;
    }

    private Photo buildPhotoFromFile(Path file, String accountId, String storageDeviceId) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
            String filename = file.getFileName().toString();
            String absPath = file.toAbsolutePath().toString();
            long size = attrs.size();

            // Prefer EXIF CreationDate; fall back to filesystem times
            ExifDateUtil.CaptureDate capture = ExifDateUtil.readCaptureDateWithZone(file, null);
            Instant createdDate = capture.instant();
            if (createdDate == null) {
                createdDate = attrs.creationTime().toInstant();
                if (createdDate.toEpochMilli() == 0L) {
                    createdDate = attrs.lastModifiedTime().toInstant();
                }
            }

            Photo photo = new Photo();
            photo.setId(UUID.randomUUID().toString());
            photo.setFilename(filename);
            photo.setFilePath(absPath);
            photo.setFileSize(size);
            photo.setCreatedDate(createdDate);
            photo.setCreatedDateTimezone(ExifDateUtil.offsetId(capture.offset()));
            photo.setImportedDate(Instant.now());
            photo.setSyncedToDisk(true);
            photo.setSourceProvider("LOCAL");
            photo.setAccountId(accountId);
            photo.setStorageDeviceId(storageDeviceId);
            photo.setMediaType(detectMediaType(filename));
            photo.setSyncStatus("SYNCED");
            photo.setExistsOnIcloud(false);
            return photo;
        } catch (IOException e) {
            LOG.warn("Could not read attributes for {}: {}", file, e.getMessage());
            return null;
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ICloudAccount requireAccount(String accountId) {
        return accountRepository.findById(accountId)
                .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, "Account not found: " + accountId));
    }

    private boolean isMediaFile(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0) return false;
        String ext = filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        return MEDIA_EXTENSIONS.contains(ext);
    }

    private String detectMediaType(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0) return MediaType.PHOTO.name();
        String ext = filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        return VIDEO_EXTENSIONS.contains(ext) ? MediaType.VIDEO.name() : MediaType.PHOTO.name();
    }

    private void emitDoneEvent(String accountId, int scanned, int total, int newlyDeleted) {
        DiskIndexProgressEvent event = new DiskIndexProgressEvent("DONE");
        event.setScanned(scanned);
        event.setTotal(total);
        event.setPercentComplete(100.0);
        event.setNewlyDeleted(newlyDeleted);
        stateHolder.updateAndEmit(accountId, event);
    }

    private void emitEvent(String accountId, String phase, int scanned, int total, double pct, String error, int newlyDeleted) {
        DiskIndexProgressEvent event = new DiskIndexProgressEvent(phase);
        event.setScanned(scanned);
        event.setTotal(total);
        event.setPercentComplete(pct);
        event.setError(error);
        event.setNewlyDeleted(newlyDeleted);
        stateHolder.updateAndEmit(accountId, event);
    }
}
