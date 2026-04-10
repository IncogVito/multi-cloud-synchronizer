package com.cloudsync.model.dto;

import io.micronaut.serde.annotation.Serdeable;
import java.time.Instant;

/**
 * Provider-agnostic photo asset returned by any {@link com.cloudsync.provider.PhotoSyncProvider}.
 */
@Serdeable
public record PhotoAsset(
    String id,
    String filename,
    Long size,
    Instant createdDate,
    Integer width,
    Integer height,
    String assetToken
) {}
