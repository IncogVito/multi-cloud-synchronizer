package com.cloudsync.agent.tools;

import com.cloudsync.agent.AgentTool;
import com.cloudsync.client.HostAgentClient;
import com.cloudsync.client.hostmodel.MountDriveResult;
import com.cloudsync.exception.HostAgentException;
import jakarta.inject.Singleton;

/**
 * Mounts a device at a given mount point via the host agent.
 * Argument format: "device:mountPoint" e.g. "/dev/sdb1:/mnt/external-drive"
 */
@Singleton
public class MountDeviceTool implements AgentTool {

    private final HostAgentClient hostAgent;

    public MountDeviceTool(HostAgentClient hostAgent) {
        this.hostAgent = hostAgent;
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
            return String.format("{\"mounted\": %s, \"device\": \"%s\", \"mountPoint\": \"%s\", \"message\": \"%s\"}",
                    result.mounted(), result.device(), result.mountPoint(),
                    result.message().replace("\"", "'"));
        } catch (HostAgentException e) {
            return String.format("{\"mounted\": false, \"error\": \"%s\"}", e.getMessage().replace("\"", "'"));
        }
    }
}
