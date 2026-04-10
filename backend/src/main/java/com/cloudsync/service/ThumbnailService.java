package com.cloudsync.service;

import com.cloudsync.model.entity.Photo;
import com.cloudsync.repository.PhotoRepository;
import io.micronaut.context.annotation.Value;
import io.micronaut.scheduling.annotation.Async;
import jakarta.inject.Singleton;
import net.coobird.thumbnailator.Thumbnails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

@Singleton
public class ThumbnailService {

    private static final Logger LOG = LoggerFactory.getLogger(ThumbnailService.class);
    private static final int THUMBNAIL_SIZE = 300;
    private static final float THUMBNAIL_QUALITY = 0.8f;

    private static final Set<String> VIDEO_EXTENSIONS = Set.of("mov", "mp4", "m4v", "avi", "mkv");
    private static final Set<String> HEIC_EXTENSIONS = Set.of("heic", "heif");

    private final PhotoRepository photoRepository;

    @Value("${app.thumbnail-dir:/mnt/external-drive/thumbnails}")
    private String thumbnailDir;

    public ThumbnailService(PhotoRepository photoRepository) {
        this.photoRepository = photoRepository;
    }

    /**
     * Check which photos in the list are missing thumbnails and generate them.
     * Runs synchronously — call from a background thread.
     * {@code onGenerated} is called after each photo (success or failure) for progress tracking.
     */
    public void generateMissing(List<Photo> candidates, Consumer<Photo> onGenerated) {
        for (Photo photo : candidates) {
            try {
                generateThumbnail(photo);
            } catch (Exception e) {
                LOG.warn("Could not generate thumbnail for photo {}: {}", photo.getId(), e.getMessage());
            } finally {
                onGenerated.accept(photo);
            }
        }
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

        if (VIDEO_EXTENSIONS.contains(ext)) {
            LOG.debug("Skipping thumbnail for video file: {}", sourceFile.getFileName());
            return;
        }

        Path thumbDir = Path.of(thumbnailDir);
        Files.createDirectories(thumbDir);
        Path thumbFile = thumbDir.resolve(photo.getId() + ".jpg");

        if (HEIC_EXTENSIONS.contains(ext)) {
            generateHeicThumbnail(sourceFile, thumbFile);
        } else {
            Thumbnails.of(sourceFile.toFile())
                    .size(THUMBNAIL_SIZE, THUMBNAIL_SIZE)
                    .outputFormat("jpg")
                    .outputQuality(THUMBNAIL_QUALITY)
                    .toFile(thumbFile.toFile());
        }

        photo.setThumbnailPath(thumbFile.toString());
        photoRepository.update(photo);
        LOG.debug("Thumbnail generated for photo {}: {}", photo.getId(), thumbFile);
    }

    private void generateHeicThumbnail(Path source, Path thumbFile) throws IOException {
        Path tempJpg = Files.createTempFile("heic-convert-", ".jpg");
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "convert", source.toAbsolutePath().toString(), tempJpg.toAbsolutePath().toString()
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();
            int exitCode;
            try {
                exitCode = process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("HEIC conversion interrupted", e);
            }
            if (exitCode != 0) {
                String output = new String(process.getInputStream().readAllBytes());
                throw new IOException("ImageMagick convert failed (exit " + exitCode + "): " + output);
            }

            Thumbnails.of(tempJpg.toFile())
                    .size(THUMBNAIL_SIZE, THUMBNAIL_SIZE)
                    .outputFormat("jpg")
                    .outputQuality(THUMBNAIL_QUALITY)
                    .toFile(thumbFile.toFile());
        } finally {
            Files.deleteIfExists(tempJpg);
        }
    }

    private static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }
}
