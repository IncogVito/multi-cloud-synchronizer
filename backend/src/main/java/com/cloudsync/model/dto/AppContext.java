package com.cloudsync.model.dto;

import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;

@Serdeable
public record AppContext(
        String storageDeviceId,
        String storageDeviceLabel,
        String mountPoint,
        Long freeBytes,
        Instant setAt,
        boolean degraded
) {}
