package com.cloudsync.model.dto;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record SyncConfigRequest(String syncFolderPath, String storageDeviceId, String organizeBy) {}
