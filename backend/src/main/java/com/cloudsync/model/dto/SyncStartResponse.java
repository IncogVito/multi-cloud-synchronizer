package com.cloudsync.model.dto;

import com.cloudsync.model.enums.SyncPhase;
import java.time.Instant;

public record SyncStartResponse(
    String accountId,
    SyncPhase phase,
    String message,
    Instant startedAt
) {}
