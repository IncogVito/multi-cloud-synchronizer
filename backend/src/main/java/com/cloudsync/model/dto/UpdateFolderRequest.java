package com.cloudsync.model.dto;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record UpdateFolderRequest(String name, String parentId, Integer sortOrder) {}
