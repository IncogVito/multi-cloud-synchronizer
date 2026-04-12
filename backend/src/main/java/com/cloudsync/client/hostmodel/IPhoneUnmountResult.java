package com.cloudsync.client.hostmodel;

import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.core.annotation.Nullable;

@Serdeable
public record IPhoneUnmountResult(boolean unmounted, @Nullable String error) {}
