package com.cloudsync.service;

import com.cloudsync.model.entity.Photo;
import com.cloudsync.repository.PhotoRepository;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Singleton
public class ThumbnailService {

    private static final Logger LOG = LoggerFactory.getLogger(ThumbnailService.class);
    private static final int THUMBNAIL_SIZE = 300;
    private static final float THUMBNAIL_QUALITY = 0.8f;

    private static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "heic", "heif", "webp", "tiff", "tif");
    private static final Set<String> VIDEO_EXTENSIONS = Set.of("mov", "mp4", "m4v", "avi", "mkv");

    private final PhotoRepository photoRepository;
    private final ExecutorService thumbnailExecutor;
    private final String thumbnailDir;

    public ThumbnailService(PhotoRepository photoRepository,
                            @Named("thumbnailExecutor") ExecutorService thumbnailExecutor,
                            @Named("thumbnailDir") String thumbnailDir) {
        this.photoRepository = photoRepository;
        this.thumbnailExecutor = thumbnailExecutor;
        this.thumbnailDir = thumbnailDir;
    }

    /**
     * Check which photos in the list are missing thumbnails and generate them in parallel.
     * Runs synchronously — call from a background thread.
     * {@code onGenerated} is called after each photo (success or failure) for progress tracking.
     * {@code isCancelled} is checked before each photo — if true, the photo is skipped.
     */
    public void generateMissing(List<Photo> candidates, Consumer<Photo> onGenerated, Supplier<Boolean> isCancelled) {
        List<CompletableFuture<Void>> futures = candidates.stream().map(photo ->
            CompletableFuture.runAsync(() -> {
                if (isCancelled.get()) return;
                try {
                    generateThumbnail(photo);
                } catch (Exception e) {
                    LOG.warn("Could not generate thumbnail for photo {}: {}", photo.getId(), e.getMessage());
                } finally {
                    onGenerated.accept(photo);
                }
            }, thumbnailExecutor)
        ).toList();
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }

    public long countMissing() {
        return photoRepository.countMissingThumbnails();
    }

    public long countMissingByDevice(String storageDeviceId) {
        return photoRepository.countMissingThumbnailsByDevice(storageDeviceId);
    }

    public List<Photo> findCandidates(String storageDeviceId) {
        if (storageDeviceId != null && !storageDeviceId.isBlank()) {
            return photoRepository.findSyncedWithoutThumbnailByDevice(storageDeviceId);
        }
        return photoRepository.findSyncedWithoutThumbnail();
    }

    public void generateThumbnail(Photo photo) throws IOException {
        if (photo.getFilePath() == null) {
            LOG.warn("Photo {} has no file path, skipping thumbnail", photo.getId());
            return;
        }

        Path sourceFile = Path.of(photo.getFilePath());
        if (!Files.exists(sourceFile)) {
            LOG.warn("Source file does not exist for photo {}: {}", photo.getId(), sourceFile);
            return;
        }

        String ext = extension(sourceFile);

        if (!IMAGE_EXTENSIONS.contains(ext) && !VIDEO_EXTENSIONS.contains(ext)) {
            LOG.debug("Unsupported file type for thumbnail: {}", sourceFile.getFileName());
            return;
        }

        Path thumbDir = Path.of(thumbnailDir);
        Files.createDirectories(thumbDir);
        Path thumbFile = thumbDir.resolve(photo.getId() + ".jpg");

        if (VIDEO_EXTENSIONS.contains(ext)) {
            generateFfmpegThumbnail(sourceFile, thumbFile);
        } else {
            generateVipsThumbnail(sourceFile, thumbFile);
        }

        photo.setThumbnailPath(thumbFile.toString());
        photoRepository.update(photo);
        LOG.debug("Thumbnail generated for photo {}: {}", photo.getId(), thumbFile);
    }

    private void generateFfmpegThumbnail(Path source, Path thumbFile) throws IOException {
        // Seek 3s in before -i for fast input seeking; avoids common black opening frames.
        // scale=-2:300 keeps aspect, height divisible by 2 (encoder requirement).
        ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-y",
                "-ss", "3",
                "-i", source.toAbsolutePath().toString(),
                "-vframes", "1",
                "-vf", "scale=-2:" + THUMBNAIL_SIZE,
                "-q:v", "2",
                thumbFile.toAbsolutePath().toString()
        );
        pb.redirectErrorStream(true);
        Process process = pb.start();
        int exitCode;
        try {
            exitCode = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("ffmpeg interrupted", e);
        }
        if (exitCode != 0) {
            String output = new String(process.getInputStream().readAllBytes());
            throw new IOException("ffmpeg failed (exit " + exitCode + "): " + output);
        }
    }

    private void generateVipsThumbnail(Path source, Path thumbFile) throws IOException {
        // vipsthumbnail: streaming pipeline, SIMD-optimised, handles JPEG/PNG/HEIC/WEBP in one path.
        // --crop centre preserves aspect ratio and fills the square.
        String outputArg = thumbFile.toAbsolutePath() + "[Q=" + (int) (THUMBNAIL_QUALITY * 100) + "]";
        ProcessBuilder pb = new ProcessBuilder(
                "vipsthumbnail",
                source.toAbsolutePath().toString(),
                "-s", THUMBNAIL_SIZE + "x" + THUMBNAIL_SIZE,
                "--crop",
                "-o", outputArg
        );
        pb.redirectErrorStream(true);
        Process process = pb.start();
        int exitCode;
        try {
            exitCode = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("vipsthumbnail interrupted", e);
        }
        if (exitCode != 0) {
            String output = new String(process.getInputStream().readAllBytes());
            throw new IOException("vipsthumbnail failed (exit " + exitCode + "): " + output);
        }
    }

    private static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }
}
