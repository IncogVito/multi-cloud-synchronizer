package com.cloudsync.model.dto;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record ThumbnailProgress(int processed, int total, boolean done, int errors) {}
