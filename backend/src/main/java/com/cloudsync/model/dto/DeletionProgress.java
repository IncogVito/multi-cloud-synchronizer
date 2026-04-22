package com.cloudsync.model.dto;

import io.micronaut.serde.annotation.Serdeable;

import java.util.List;

@Serdeable
public record DeletionProgress(
        int deleted,
        int total,
        int failed,
        boolean done,
        List<String> successfulIds,
        List<String> failedIds
) {}
