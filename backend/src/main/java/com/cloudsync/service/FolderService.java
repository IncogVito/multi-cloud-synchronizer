package com.cloudsync.service;

import com.cloudsync.model.dto.CreateFolderRequest;
import com.cloudsync.model.dto.FolderResponse;
import com.cloudsync.model.dto.UpdateFolderRequest;
import com.cloudsync.model.entity.Photo;
import com.cloudsync.model.entity.VirtualFolder;
import com.cloudsync.model.enums.FolderType;
import com.cloudsync.repository.FolderRepository;
import com.cloudsync.repository.PhotoFolderAssignmentRepository;
import com.cloudsync.repository.PhotoRepository;
import jakarta.inject.Singleton;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Singleton
public class FolderService {

    private final FolderRepository folderRepository;
    private final PhotoFolderAssignmentRepository assignmentRepository;
    private final PhotoRepository photoRepository;

    public FolderService(FolderRepository folderRepository,
                         PhotoFolderAssignmentRepository assignmentRepository,
                         PhotoRepository photoRepository) {
        this.folderRepository = folderRepository;
        this.assignmentRepository = assignmentRepository;
        this.photoRepository = photoRepository;
    }

    public List<FolderResponse> getFolderTree() {
        List<VirtualFolder> allFolders = StreamSupport
                .stream(folderRepository.findAll().spliterator(), false)
                .toList();

        Map<String, List<VirtualFolder>> byParent = allFolders.stream()
                .filter(f -> f.getParentId() != null)
                .collect(Collectors.groupingBy(VirtualFolder::getParentId));

        return allFolders.stream()
                .filter(f -> f.getParentId() == null)
                .map(f -> toResponse(f, byParent))
                .toList();
    }

    public FolderResponse createFolder(CreateFolderRequest request) {
        VirtualFolder folder = new VirtualFolder();
        folder.setId(UUID.randomUUID().toString());
        folder.setName(request.name());
        folder.setParentId(request.parentId());
        folder.setFolderType(request.folderType() != null ? request.folderType() : FolderType.CUSTOM.name());
        folder.setSortOrder(request.sortOrder() != null ? request.sortOrder() : 0);
        folder.setCreatedAt(Instant.now());
        folderRepository.save(folder);
        return toResponse(folder, Map.of());
    }

    public Optional<FolderResponse> updateFolder(String id, UpdateFolderRequest request) {
        return folderRepository.findById(id).map(folder -> {
            if (request.name() != null) folder.setName(request.name());
            if (request.parentId() != null) folder.setParentId(request.parentId());
            if (request.sortOrder() != null) folder.setSortOrder(request.sortOrder());
            folderRepository.update(folder);
            return toResponse(folder, Map.of());
        });
    }

    public boolean deleteFolder(String id) {
        if (!folderRepository.existsById(id)) return false;
        assignmentRepository.deleteAllByFolderId(id);
        folderRepository.deleteById(id);
        return true;
    }

    public List<Photo> getPhotosInFolder(String folderId) {
        List<String> photoIds = assignmentRepository.findPhotoIdsByFolderId(folderId);
        return photoIds.stream()
                .map(photoRepository::findById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList();
    }

    public void assignPhotosToFolder(String folderId, List<String> photoIds) {
        for (String photoId : photoIds) {
            assignmentRepository.assign(photoId, folderId);
        }
    }

    /**
     * Auto-organize photos into year/month virtual folders.
     */
    public void autoOrganize(String granularity) {
        Iterable<Photo> allPhotos = photoRepository.findAll();
        for (Photo photo : allPhotos) {
            if (photo.getCreatedDate() == null) continue;

            var local = photo.getCreatedDate().atZone(ZoneId.systemDefault());
            int year = local.getYear();
            int month = local.getMonthValue();

            String yearFolderName = String.valueOf(year);
            VirtualFolder yearFolder = getOrCreateFolder(yearFolderName, null, FolderType.YEAR.name());

            if ("MONTH".equalsIgnoreCase(granularity)) {
                String monthFolderName = String.format("%02d", month);
                VirtualFolder monthFolder = getOrCreateFolder(monthFolderName, yearFolder.getId(), FolderType.MONTH.name());
                assignmentRepository.assign(photo.getId(), monthFolder.getId());
            } else {
                assignmentRepository.assign(photo.getId(), yearFolder.getId());
            }
        }
    }

    private VirtualFolder getOrCreateFolder(String name, String parentId, String type) {
        List<VirtualFolder> existing = parentId != null
                ? folderRepository.findByParentId(parentId)
                : folderRepository.findByParentIdIsNull();

        return existing.stream()
                .filter(f -> f.getName().equals(name))
                .findFirst()
                .orElseGet(() -> {
                    VirtualFolder f = new VirtualFolder();
                    f.setId(UUID.randomUUID().toString());
                    f.setName(name);
                    f.setParentId(parentId);
                    f.setFolderType(type);
                    f.setCreatedAt(Instant.now());
                    return folderRepository.save(f);
                });
    }

    private FolderResponse toResponse(VirtualFolder folder, Map<String, List<VirtualFolder>> byParent) {
        List<FolderResponse> children = byParent.getOrDefault(folder.getId(), List.of())
                .stream()
                .map(child -> toResponse(child, byParent))
                .toList();
        return new FolderResponse(
                folder.getId(),
                folder.getName(),
                folder.getParentId(),
                folder.getFolderType(),
                folder.getSortOrder(),
                folder.getCreatedAt(),
                children
        );
    }
}
