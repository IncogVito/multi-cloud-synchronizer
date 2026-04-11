package com.cloudsync.service;

import com.cloudsync.model.dto.PhotoListResponse;
import com.cloudsync.model.dto.PhotoResponse;
import com.cloudsync.model.entity.Photo;
import com.cloudsync.repository.PhotoRepository;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Singleton
public class PhotoService {

    private final PhotoRepository photoRepository;
    private final AppContextService appContextService;

    public PhotoService(PhotoRepository photoRepository, AppContextService appContextService) {
        this.photoRepository = photoRepository;
        this.appContextService = appContextService;
    }

    public PhotoListResponse listPhotos(String accountId, Boolean synced, String storageDeviceId, int page, int size) {
        appContextService.requireActive();
        Pageable pageable = Pageable.from(page, size);
        Page<Photo> result;

        if (accountId != null && synced != null) {
            List<Photo> photos = photoRepository.findByAccountIdAndSyncedToDisk(accountId, synced);
            return new PhotoListResponse(photos.stream().map(this::toResponse).toList(), photos.size(), page, size);
        } else if (storageDeviceId != null && synced != null) {
            result = photoRepository.findBySyncedToDiskAndStorageDeviceId(synced, storageDeviceId, pageable);
        } else if (accountId != null) {
            result = photoRepository.findByAccountId(accountId, pageable);
        } else if (synced != null) {
            result = photoRepository.findBySyncedToDisk(synced, pageable);
        } else {
            result = photoRepository.findAll(pageable);
        }

        return new PhotoListResponse(
                result.getContent().stream().map(this::toResponse).toList(),
                result.getTotalSize(),
                page,
                size
        );
    }

    public Optional<PhotoResponse> getPhoto(String id) {
        appContextService.requireActive();
        return photoRepository.findById(id).map(this::toResponse);
    }

    public Optional<byte[]> getThumbnailBytes(String id) throws IOException {
        Optional<Photo> photo = photoRepository.findById(id);
        if (photo.isEmpty() || photo.get().getThumbnailPath() == null) {
            return Optional.empty();
        }
        Path path = Path.of(photo.get().getThumbnailPath());
        if (!Files.exists(path)) return Optional.empty();
        return Optional.of(Files.readAllBytes(path));
    }

    public record FullPhotoData(byte[] bytes, String mimeType) {}

    private static final Set<String> HEIC_EXTENSIONS = Set.of("heic", "heif");
    private static final Set<String> VIDEO_EXTENSIONS = Set.of("mp4", "m4v", "mov", "avi", "mkv");

    public Optional<byte[]> getFullPhotoBytes(String id) throws IOException {
        return getFullPhotoData(id).map(FullPhotoData::bytes);
    }

    public Optional<FullPhotoData> getFullPhotoData(String id) throws IOException {
        Optional<Photo> photo = photoRepository.findById(id);
        if (photo.isEmpty() || !photo.get().isSyncedToDisk()) {
            return Optional.empty();
        }
        Path path = Path.of(photo.get().getFilePath());
        if (!Files.exists(path)) return Optional.empty();

        String ext = extension(path);

        if (HEIC_EXTENSIONS.contains(ext)) {
            byte[] jpeg = convertHeicToJpeg(path);
            return Optional.of(new FullPhotoData(jpeg, "image/jpeg"));
        }

        String mimeType = switch (ext) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "tiff", "tif" -> "image/tiff";
            case "dng" -> "image/x-adobe-dng";
            case "mp4", "m4v" -> "video/mp4";
            case "mov" -> "video/quicktime";
            case "avi" -> "video/x-msvideo";
            case "mkv" -> "video/x-matroska";
            default -> "application/octet-stream";
        };

        return Optional.of(new FullPhotoData(Files.readAllBytes(path), mimeType));
    }

    private byte[] convertHeicToJpeg(Path source) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(
                "convert",
                source.toAbsolutePath() + "[0]",
                "jpg:-"
        );
        pb.redirectErrorStream(false);
        Process process = pb.start();
        byte[] jpeg;
        try {
            jpeg = process.getInputStream().readAllBytes();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                String err = new String(process.getErrorStream().readAllBytes());
                throw new IOException("ImageMagick convert failed (exit " + exitCode + "): " + err);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("HEIC conversion interrupted", e);
        }
        return jpeg;
    }

    private static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    public PhotoResponse toResponse(Photo photo) {
        return new PhotoResponse(
                photo.getId(),
                photo.getIcloudPhotoId(),
                photo.getAccountId(),
                photo.getFilename(),
                photo.getFilePath(),
                photo.getThumbnailPath(),
                photo.getFileSize(),
                photo.getWidth(),
                photo.getHeight(),
                photo.getCreatedDate(),
                photo.getImportedDate(),
                photo.getChecksum(),
                photo.isSyncedToDisk(),
                photo.isExistsOnIcloud(),
                Boolean.TRUE.equals(photo.getExistsOnIphone()),
                photo.getMediaType()
        );
    }
}
