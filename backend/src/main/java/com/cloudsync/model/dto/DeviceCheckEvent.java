package com.cloudsync.model.dto;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record DeviceCheckEvent(
        String deviceType,
        String status,
        String stepDescription,
        String details,
        boolean terminal
) {}
