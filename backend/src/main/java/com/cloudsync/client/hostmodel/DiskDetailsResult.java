package com.cloudsync.client.hostmodel;

import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.core.annotation.Nullable;

import java.util.List;
import java.util.Map;

@Serdeable
public record DiskDetailsResult(
        String device,
        String size,
        @Nullable String fstype,
        @Nullable String uuid,
        @Nullable String label,
        @Nullable String mountpoint,
        List<Map<String, Object>> partitions
) {}
