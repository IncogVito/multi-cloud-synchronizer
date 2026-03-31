package com.cloudsync.model.dto;

import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;

@Serdeable
public record AccountResponse(
        String id,
        String appleId,
        String displayName,
        boolean hasActiveSession,
        Instant lastSyncAt,
        Instant createdAt
) {}
