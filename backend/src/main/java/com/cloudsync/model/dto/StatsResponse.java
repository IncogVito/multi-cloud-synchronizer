package com.cloudsync.model.dto;

import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;

@Serdeable
public record StatsResponse(
        long diskPhotoCount,
        Long diskSizeBytes,
        Long diskCapacityBytes,
        Long diskFreeBytes,
        Instant diskLastSyncAt,

        long icloudPhotoCount,
        Long icloudSizeBytes,
        Instant icloudLastSyncAt,

        long iphonePhotoCount,
        Long iphoneSizeBytes,
        Instant iphoneLastSyncAt
) {}
