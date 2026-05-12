package com.cloudsync.model.dto;

import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;
import java.util.List;

@Serdeable
public record TaskHistoryDetailDto(
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
        Long durationMs,
        List<TaskSyncPhaseDto> phases,
        List<TaskItemDto> items
) {
    @Serdeable
    public record TaskSyncPhaseDto(
            String phase,
            Instant startedAt,
            Instant completedAt,
            String errorMessage,
            Long durationMs
    ) {}

    @Serdeable
    public record TaskItemDto(
            String itemStatus,
            String photoId,
            String photoName,
            String errorMessage
    ) {}
}
