package com.cloudsync.model.dto;

import com.cloudsync.model.enums.SyncPhase;
import io.micronaut.serde.annotation.Serdeable;
import java.time.Instant;

@Serdeable
public record SyncStartResponse(
    String accountId,
    SyncPhase phase,
    String message,
    Instant startedAt
) {}
