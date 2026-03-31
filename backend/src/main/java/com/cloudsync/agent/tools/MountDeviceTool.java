package com.cloudsync.agent.tools;

import com.cloudsync.agent.AgentTool;
import com.cloudsync.util.ShellExecutor;
import jakarta.inject.Singleton;

/**
 * Mounts a device at a given mount point.
 * Argument format: "device:mountPoint" e.g. "/dev/sdb1:/mnt/external-drive"
 */
@Singleton
public class MountDeviceTool implements AgentTool {

    private final ShellExecutor shell;

    public MountDeviceTool(ShellExecutor shell) {
        this.shell = shell;
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

        // Create mount point if it doesn't exist
        shell.execute("mkdir", "-p", mountPoint);

        ShellExecutor.ShellResult result = shell.execute("mount", device, mountPoint);
        if (result.isSuccess()) {
            return String.format("{\"mounted\": true, \"device\": \"%s\", \"mountPoint\": \"%s\"}", device, mountPoint);
        }
        return String.format("{\"mounted\": false, \"error\": \"%s\"}", result.stderr().replace("\"", "'"));
    }
}
