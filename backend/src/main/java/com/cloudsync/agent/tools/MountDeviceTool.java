package com.cloudsync.agent.tools;

import com.cloudsync.agent.AgentTool;
import com.cloudsync.client.HostAgentClient;
import com.cloudsync.client.hostmodel.MountDriveResult;
import com.cloudsync.exception.HostAgentException;
import io.micronaut.serde.ObjectMapper;
import jakarta.inject.Singleton;

/**
 * Mounts a device at a given mount point via the host agent.
 * Argument format: "device:mountPoint" e.g. "/dev/sdb1:/mnt/external-drive"
 */
@Singleton
public class MountDeviceTool implements AgentTool {

    private final HostAgentClient hostAgent;
    private final ObjectMapper objectMapper;

    public MountDeviceTool(HostAgentClient hostAgent, ObjectMapper objectMapper) {
        this.hostAgent = hostAgent;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() { return "mount_device"; }

    @Override
    public String getDescription() {
        return "Mounts a block device at a mount point. Argument: 'device:mountPoint' (e.g. '/dev/sdb1:/mnt/external-drive').";
    }

    @Override
    public String execute(String argument) {
        if (argument == null || !argument.contains(":")) {
            return "Error: argument must be 'device:mountPoint'";
        }
        String[] parts = argument.split(":", 2);
        String device = parts[0].trim();
        String mountPoint = parts[1].trim();

        try {
            MountDriveResult result = hostAgent.mountDrive(device, mountPoint);
            MountDeviceResponse response = new MountDeviceResponse(
                    result.mounted(), result.device(), result.mountPoint(), result.message(), null);
            return objectMapper.writeValueAsString(response);
        } catch (HostAgentException e) {
            try {
                return objectMapper.writeValueAsString(new MountDeviceResponse(false, null, null, null, e.getMessage()));
            } catch (Exception jsonEx) {
                return "{\"mounted\": false, \"error\": \"serialization failed\"}";
            }
        } catch (Exception e) {
            return "{\"mounted\": false, \"error\": \"unexpected error\"}";
        }
    }
}
