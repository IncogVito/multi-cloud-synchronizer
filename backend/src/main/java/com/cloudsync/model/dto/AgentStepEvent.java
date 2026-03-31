package com.cloudsync.model.dto;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record AgentStepEvent(
        Object step,        // int or "final"
        String action,
        String result,
        Boolean success,
        String message
) {}
