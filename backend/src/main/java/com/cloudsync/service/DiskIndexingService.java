package com.cloudsync.service;

import com.cloudsync.model.dto.AppContext;
import com.cloudsync.model.dto.DiskIndexProgressEvent;
import com.cloudsync.model.entity.Photo;
import com.cloudsync.model.enums.MediaType;
import com.cloudsync.repository.PhotoRepository;
import com.cloudsync.repository.StorageDeviceRepository;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.mov.QuickTimeDirectory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

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
    private final AppContextService appContextService;
    private final DiskIndexStateHolder stateHolder;
    private final ExecutorService syncVirtualThreadExecutor;
    private final StorageDeviceRepository storageDeviceRepository;

    private final AtomicBoolean running = new AtomicBoolean(false);

    public DiskIndexingService(PhotoRepository photoRepository,
                               AppContextService appContextService,
                               DiskIndexStateHolder stateHolder,
                               @Named("syncVirtualThreadExecutor") ExecutorService syncVirtualThreadExecutor,
                               StorageDeviceRepository storageDeviceRepository) {
        this.photoRepository = photoRepository;
        this.appContextService = appContextService;
        this.stateHolder = stateHolder;
        this.syncVirtualThreadExecutor = syncVirtualThreadExecutor;
        this.storageDeviceRepository = storageDeviceRepository;
    }

    public boolean isRunning() {
        return running.get();
    }

    /**
     * Start async disk indexing using the active app context.
     * Scans the base path for media files and saves them to the DB.
     */
    public void startIndexing() {
        AppContext ctx = appContextService.requireActive();
        if (running.compareAndSet(false, true)) {
            emitEvent("SCANNING", 0, 0, 0.0, null, 0);
            CompletableFuture.runAsync(
                    () -> doIndex(ctx.basePath(), ctx.storageDeviceId()),
                    syncVirtualThreadExecutor
            );
        }
    }

    private void doIndex(String folderPath, String storageDeviceId) {
        try {
            Path root = Path.of(folderPath);
            if (!Files.isDirectory(root)) {
                emitEvent("ERROR", 0, 0, 0.0, "Folder nie istnieje: " + folderPath, 0);
                running.set(false);
                return;
            }

            List<Path> mediaFiles = collectMediaFiles(root);
            int total = mediaFiles.size();
            LOG.info("DiskIndexing: found {} media files in {}", total, folderPath);

            // Reset syncedToDisk flag — will be re-set only for files actually found on disk
            List<Photo> currentlySynced = photoRepository.findByStorageDeviceIdAndSyncedToDisk(storageDeviceId, true);
            resetSyncedToDisk(currentlySynced);

            // Path → Photo map (all photos with a path, including deleted) for reconciliation
            Map<String, Photo> existingByPath = buildExistingPhotosByPath(storageDeviceId);

            int scanned = 0;
            List<Photo> toSave = new ArrayList<>();
            List<Photo> toUpdate = new ArrayList<>();

            for (Path file : mediaFiles) {
                String absPath = file.toAbsolutePath().toString();
                Photo existing = existingByPath.get(absPath);

                if (existing != null) {
                    existing.setSyncedToDisk(true);
                    if (existing.isDeleted()) {
                        existing.setDeleted(false);
                        existing.setDeletedDate(null);
                    }
                    toUpdate.add(existing);
                } else {
                    Photo photo = buildPhotoFromFile(file, storageDeviceId);
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
                    emitEvent("SCANNING", scanned, total, pct, null, 0);
                }
            }

            if (!toSave.isEmpty()) photoRepository.saveAll(toSave);
            if (!toUpdate.isEmpty()) photoRepository.updateAll(toUpdate);

            LOG.info("DiskIndexing complete: {} files processed", total);
            emitDoneEvent(scanned, total, 0);
            storageDeviceRepository.findById(storageDeviceId).ifPresent(d -> {
                d.setLastIndexedAt(Instant.now());
                storageDeviceRepository.update(d);
            });
        } catch (Exception e) {
            LOG.error("DiskIndexing failed: {}", e.getMessage(), e);
            emitEvent("ERROR", 0, 0, 0.0, e.getMessage(), 0);
        } finally {
            running.set(false);
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

    private Map<String, Photo> buildExistingPhotosByPath(String storageDeviceId) {
        Map<String, Photo> map = new HashMap<>();
        try {
            photoRepository.findAllWithFilePathByStorageDeviceId(storageDeviceId)
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

    private Photo buildPhotoFromFile(Path file, String storageDeviceId) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
            String filename = file.getFileName().toString();
            String absPath = file.toAbsolutePath().toString();
            long size = attrs.size();

            // Prefer EXIF CreationDate; fall back to filesystem times
            Instant createdDate = readExifCreationDate(file);
            if (createdDate == null) {
                createdDate = attrs.creationTime().toInstant();
                if (createdDate.toEpochMilli() == 0L) {
                    createdDate = attrs.lastModifiedTime().toInstant();
                }
            }

            // TODO: Delegate to builder
            Photo photo = new Photo();
            photo.setId(UUID.randomUUID().toString());
            photo.setFilename(filename);
            photo.setFilePath(absPath);
            photo.setFileSize(size);
            photo.setCreatedDate(createdDate);
            photo.setImportedDate(Instant.now());
            photo.setSyncedToDisk(true);
            photo.setSourceProvider("LOCAL");
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

    /**
     * Reads the actual capture date from image/video metadata (EXIF DateTimeOriginal,
     * then EXIF DateTime, then QuickTime creation_time). Returns null if no metadata
     * date is available, so the caller can fall back to filesystem timestamps.
     */
    // TODO: Move to somekind of helper
    private Instant readExifCreationDate(Path file) {
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(file.toFile());

            // JPEG / TIFF / HEIC with embedded EXIF — most accurate tag
            ExifSubIFDDirectory exifSub = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
            if (exifSub != null) {
                var date = exifSub.getDate(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL);
                // TODO: Maybe worth to use TAG_DATETIME_DIGITIZED
                if (date != null) return date.toInstant();
            }

            // EXIF IFD0 DateTime (some cameras write only this tag)
            ExifIFD0Directory exif0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (exif0 != null) {
                var date = exif0.getDate(ExifIFD0Directory.TAG_DATETIME);
                if (date != null) return date.toInstant();
            }

            // MOV / MP4 / HEIC QuickTime creation_time atom
            QuickTimeDirectory qt = metadata.getFirstDirectoryOfType(QuickTimeDirectory.class);
            if (qt != null) {
                var date = qt.getDate(QuickTimeDirectory.TAG_CREATION_TIME);
                if (date != null) return date.toInstant();
            }
        } catch (Exception e) {
            LOG.debug("Could not read EXIF from {}: {}", file.getFileName(), e.getMessage());
        }
        return null;
    }

    // ── Reorganize ────────────────────────────────────────────────────────────

    /**
     * Preview photos on the active device that are outside a year/month folder structure.
     */
    public Map<String, Object> reorganizePreview() {
        AppContext ctx = appContextService.requireActive();
        Path basePath = Path.of(ctx.basePath());
        List<Photo> candidates = findUnorganized(ctx.storageDeviceId(), basePath);

        List<String> samples = candidates.stream()
                .map(p -> Path.of(p.getFilePath()).getFileName().toString())
                .limit(5)
                .toList();

        List<String> estimatedFolders = candidates.stream()
                .filter(p -> p.getCreatedDate() != null)
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
     * Move unorganized photos on the active device into year/month subdirectories.
     */
    public Map<String, Object> reorganize() {
        AppContext ctx = appContextService.requireActive();
        Path basePath = Path.of(ctx.basePath());
        List<Photo> candidates = findUnorganized(ctx.storageDeviceId(), basePath);

        int moved = 0;
        int errors = 0;
        for (int i = 0; i < candidates.size(); i += BATCH_SIZE) {
            List<Photo> batch = candidates.subList(i, Math.min(i + BATCH_SIZE, candidates.size()));
            for (Photo photo : batch) {
                try {
                    Path currentPath = Path.of(photo.getFilePath());
                    Path destDir = resolveDestDir(ctx.basePath(), photo.getCreatedDate());
                    Path destPath = resolveWithoutCollision(destDir, currentPath.getFileName());
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

    private List<Photo> findUnorganized(String storageDeviceId, Path basePath) {
        return photoRepository.findByStorageDeviceIdAndSyncedToDisk(storageDeviceId, true)
                .stream()
                .filter(p -> p.getFilePath() != null)
                .filter(p -> {
                    Path filePath = Path.of(p.getFilePath());
                    if (!Files.exists(filePath)) return false;
                    Path parent = filePath.getParent();
                    if (parent == null) return true;
                    try {
                        Path rel = basePath.relativize(parent);
                        String relStr = rel.toString().replace('\\', '/');
                        return !relStr.matches("\\d{4}/\\d{2}");
                    } catch (IllegalArgumentException e) {
                        return true;
                    }
                })
                .toList();
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

    private Path resolveWithoutCollision(Path targetDir, Path filename) {
        Path target = targetDir.resolve(filename);
        if (!Files.exists(target)) return target;

        String name = filename.toString();
        int dotIdx = name.lastIndexOf('.');
        String base = dotIdx >= 0 ? name.substring(0, dotIdx) : name;
        String ext = dotIdx >= 0 ? name.substring(dotIdx) : "";

        int i = 1;
        while (true) {
            Path candidate = targetDir.resolve(base + "_" + i + ext);
            if (!Files.exists(candidate)) return candidate;
            i++;
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

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

    private void emitDoneEvent(int scanned, int total, int newlyDeleted) {
        DiskIndexProgressEvent event = new DiskIndexProgressEvent("DONE");
        event.setScanned(scanned);
        event.setTotal(total);
        event.setPercentComplete(100.0);
        event.setNewlyDeleted(newlyDeleted);
        stateHolder.updateAndEmit(event);
    }

    private void emitEvent(String phase, int scanned, int total, double pct, String error, int newlyDeleted) {
        DiskIndexProgressEvent event = new DiskIndexProgressEvent(phase);
        event.setScanned(scanned);
        event.setTotal(total);
        event.setPercentComplete(pct);
        event.setError(error);
        event.setNewlyDeleted(newlyDeleted);
        stateHolder.updateAndEmit(event);
    }
}
