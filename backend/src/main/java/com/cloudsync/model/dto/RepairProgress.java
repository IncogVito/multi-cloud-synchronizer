package com.cloudsync.model.dto;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record RepairProgress(int checked, int fixed, int total, boolean done) {}
