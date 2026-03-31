package com.cloudsync.model.dto;

import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;
import java.util.List;

@Serdeable
public record FolderResponse(
        String id,
        String name,
        String parentId,
        String folderType,
        Integer sortOrder,
        Instant createdAt,
        List<FolderResponse> children
) {}
