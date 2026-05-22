package com.cloudsync.model.dto;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record DatabaseBackupProgress(boolean done, String backupPath, String error) {}
