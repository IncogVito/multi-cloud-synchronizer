package com.cloudsync.client.hostmodel;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record MountDriveResult(boolean mounted, String device, String mountPoint, String message) {}
