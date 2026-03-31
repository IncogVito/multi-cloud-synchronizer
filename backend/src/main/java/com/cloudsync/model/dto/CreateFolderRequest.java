package com.cloudsync.model.dto;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record CreateFolderRequest(String name, String parentId, String folderType, Integer sortOrder) {}
