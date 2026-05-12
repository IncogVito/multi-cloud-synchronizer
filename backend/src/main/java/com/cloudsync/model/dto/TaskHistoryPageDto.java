package com.cloudsync.model.dto;

import io.micronaut.serde.annotation.Serdeable;

import java.util.List;

@Serdeable
public record TaskHistoryPageDto(
        List<TaskHistoryDto> tasks,
        long totalElements,
        int totalPages,
        int page,
        int size
) {}
