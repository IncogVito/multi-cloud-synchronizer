package com.cloudsync.client.hostmodel;

import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.core.annotation.Nullable;

@Serdeable
public record DiskInfo(
        String name,
        String path,
        String size,
        String type,
        @Nullable String mountpoint,
        @Nullable String label,
        String vendor,
        String model
) {}
