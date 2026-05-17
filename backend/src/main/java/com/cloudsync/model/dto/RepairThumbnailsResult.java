package com.cloudsync.model.dto;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record RepairThumbnailsResult(String jobId, RepairProgress progress) {}
