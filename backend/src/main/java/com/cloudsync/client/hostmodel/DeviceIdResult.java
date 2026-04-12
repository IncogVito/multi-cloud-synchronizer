package com.cloudsync.client.hostmodel;

import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.core.annotation.Nullable;

@Serdeable
public record DeviceIdResult(@Nullable String uuid, @Nullable String label, String device) {}
