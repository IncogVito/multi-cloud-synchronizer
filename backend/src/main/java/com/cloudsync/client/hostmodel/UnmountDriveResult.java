package com.cloudsync.client.hostmodel;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record UnmountDriveResult(boolean success, String message) {}
