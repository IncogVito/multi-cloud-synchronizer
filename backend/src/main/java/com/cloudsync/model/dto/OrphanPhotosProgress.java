package com.cloudsync.model.dto;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record OrphanPhotosProgress(int processed, int total, int assigned, boolean done) {}
