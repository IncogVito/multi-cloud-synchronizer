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
import java.util.Optional;

@Singleton
public class PhotoService {

    private final PhotoRepository photoRepository;
    private final AppContextService appContextService;

    public PhotoService(PhotoRepository photoRepository, AppContextService appContextService) {
        this.photoRepository = photoRepository;
        this.appContextService = appContextService;
    }

    public PhotoListResponse listPhotos(String accountId, Boolean synced, int page, int size) {
        appContextService.requireActive();
        Pageable pageable = Pageable.from(page, size);
        Page<Photo> result;

        if (accountId != null && synced != null) {
            List<Photo> photos = photoRepository.findByAccountIdAndSyncedToDisk(accountId, synced);
            return new PhotoListResponse(photos.stream().map(this::toResponse).toList(), photos.size(), page, size);
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

    public Optional<byte[]> getFullPhotoBytes(String id) throws IOException {
        Optional<Photo> photo = photoRepository.findById(id);
        if (photo.isEmpty() || !photo.get().isSyncedToDisk()) {
            return Optional.empty();
        }
        Path path = Path.of(photo.get().getFilePath());
        if (!Files.exists(path)) return Optional.empty();
        return Optional.of(Files.readAllBytes(path));
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
                photo.getExistsOnIphone(),
                photo.getMediaType()
        );
    }
}
