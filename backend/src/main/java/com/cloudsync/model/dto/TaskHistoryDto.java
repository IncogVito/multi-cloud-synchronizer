package com.cloudsync.model.dto;

import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;

@Serdeable
public record TaskHistoryDto(
        String id,
        String type,
        String accountId,
        String provider,
        String status,
        Instant createdAt,
        Instant completedAt,
        int totalItems,
        int succeededItems,
        int failedItems,
        String errorMessage,
        Long durationMs
) {}
