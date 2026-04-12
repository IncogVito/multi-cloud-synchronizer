package com.cloudsync.client.hostmodel;

import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.core.annotation.Nullable;

@Serdeable
public record IPhoneMountResult(
        boolean mounted,
        @Nullable String mountPath,
        @Nullable String udid,
        @Nullable String error
) {}
