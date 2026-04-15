package com.cloudsync.agent.tools;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record MountDeviceResponse(boolean mounted, String device, String mountPoint, String message, String error) {
}
