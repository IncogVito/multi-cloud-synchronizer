package com.cloudsync.model.dto;

import io.micronaut.serde.annotation.Serdeable;

/**
 * Provider-agnostic prefetch status returned by any {@link com.cloudsync.provider.PhotoSyncProvider}.
 */
@Serdeable
public record PrefetchStatus(
    String status,
    int fetched,
    Integer total
) {}
