package com.cloudsync.client.hostmodel;

import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.core.annotation.Nullable;

@Serdeable
public record DriveStatus(boolean available, @Nullable String path, @Nullable Long freeBytes, @Nullable Long totalBytes) {}
