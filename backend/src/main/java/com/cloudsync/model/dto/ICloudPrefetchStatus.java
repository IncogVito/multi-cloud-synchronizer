package com.cloudsync.model.dto;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record ICloudPrefetchStatus(
    String status,
    int fetched,
    Integer total
) {}
