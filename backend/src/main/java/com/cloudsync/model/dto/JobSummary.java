package com.cloudsync.model.dto;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record JobSummary(String jobId, String type, String status, int total, int done, int failed, String label) {}
