package com.cloudsync.model.dto;

import java.time.Instant;

public record ICloudPhotoAsset(
    String id,
    String filename,
    Long size,
    Instant createdDate,
    Integer width,
    Integer height,
    String assetToken
) {}
