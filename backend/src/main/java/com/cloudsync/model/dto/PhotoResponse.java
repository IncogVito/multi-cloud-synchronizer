package com.cloudsync.model.dto;

import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;

@Serdeable
public record PhotoResponse(
        String id,
        String icloudPhotoId,
        String accountId,
        String filename,
        String filePath,
        String thumbnailPath,
        Long fileSize,
        Integer width,
        Integer height,
        Instant createdDate,
        Instant importedDate,
        String checksum,
        boolean syncedToDisk,
        boolean existsOnIcloud,
        boolean existsOnIphone,
        String mediaType
) {}
