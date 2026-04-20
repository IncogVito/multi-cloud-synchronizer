package com.cloudsync.model.dto;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record ThumbnailJobResponse(String jobId, ThumbnailProgress progress) {}
