package com.cloudsync.model.dto;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record DatabaseBackupResult(String jobId, DatabaseBackupProgress progress) {}
