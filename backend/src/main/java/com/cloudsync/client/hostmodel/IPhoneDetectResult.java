package com.cloudsync.client.hostmodel;

import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.core.annotation.Nullable;

@Serdeable
public record IPhoneDetectResult(boolean connected, @Nullable String deviceName, @Nullable String udid) {}
