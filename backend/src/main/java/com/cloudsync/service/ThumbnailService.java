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

@Singleton
public class ThumbnailService {

    private static final Logger LOG = LoggerFactory.getLogger(ThumbnailService.class);
    private static final int THUMBNAIL_SIZE = 300;
    private static final float THUMBNAIL_QUALITY = 0.8f;

    private final PhotoRepository photoRepository;

    @Value("${app.thumbnail-dir:/mnt/external-drive/thumbnails}")
    private String thumbnailDir;

    public ThumbnailService(PhotoRepository photoRepository) {
        this.photoRepository = photoRepository;
    }

    @Async
    public void generateThumbnailAsync(Photo photo) {
        try {
            generateThumbnail(photo);
        } catch (Exception e) {
            LOG.error("Failed to generate thumbnail for photo {}: {}", photo.getId(), e.getMessage());
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

        Path thumbDir = Path.of(thumbnailDir);
        Files.createDirectories(thumbDir);

        Path thumbFile = thumbDir.resolve(photo.getId() + ".jpg");

        Thumbnails.of(sourceFile.toFile())
                .size(THUMBNAIL_SIZE, THUMBNAIL_SIZE)
                .outputFormat("jpg")
                .outputQuality(THUMBNAIL_QUALITY)
                .toFile(thumbFile.toFile());

        photo.setThumbnailPath(thumbFile.toString());
        photoRepository.update(photo);
        LOG.debug("Thumbnail generated for photo {}: {}", photo.getId(), thumbFile);
    }
}
