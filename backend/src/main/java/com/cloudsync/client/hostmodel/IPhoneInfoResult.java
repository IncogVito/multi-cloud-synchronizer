package com.cloudsync.client.hostmodel;

import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.core.annotation.Nullable;

@Serdeable
public record IPhoneInfoResult(
        String udid,
        String deviceName,
        String productType,
        String iosVersion,
        String serialNumber,
        @Nullable Long totalCapacityBytes,
        @Nullable Long freeBytes,
        @Nullable Integer batteryPercent
) {}
