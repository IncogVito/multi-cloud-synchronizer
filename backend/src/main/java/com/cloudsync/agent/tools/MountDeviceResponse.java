package com.cloudsync.agent.tools;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record MountDeviceResponse(boolean mounted, String device, String mountPoint, String message, String error) {
}
