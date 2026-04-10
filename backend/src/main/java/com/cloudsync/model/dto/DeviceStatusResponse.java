package com.cloudsync.model.dto;

import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;

@Serdeable
public record DeviceStatusResponse(
        String id,
        String deviceType,
        String status,
        boolean connected,
        Instant lastCheckedAt,
        String details,
        Boolean mounted
) {}
