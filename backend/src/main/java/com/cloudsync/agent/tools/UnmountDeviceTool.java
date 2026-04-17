package com.cloudsync.agent.tools;

import com.cloudsync.agent.AgentTool;
import com.cloudsync.client.HostAgentClient;
import com.cloudsync.client.hostmodel.UnmountDriveResult;
import com.cloudsync.exception.HostAgentException;
import jakarta.inject.Singleton;

/**
 * Unmounts a mount point via the host agent.
 * Argument: mount point path (e.g. "/mnt/external-drive").
 */
@Singleton
public class UnmountDeviceTool implements AgentTool {

    private final HostAgentClient hostAgent;

    public UnmountDeviceTool(HostAgentClient hostAgent) {
        this.hostAgent = hostAgent;
    }

    @Override
    public String getName() { return "unmount_device"; }

    @Override
    public String getDescription() {
        return "Unmounts a mount point. Argument: mount point path (e.g. '/mnt/external-drive').";
    }

    @Override
    public String execute(String argument) {
        if (argument == null || argument.isBlank()) {
            return "{\"error\":\"mount point path required\"}";
        }
        try {
            UnmountDriveResult result = hostAgent.unmountDrive(argument.trim());
            return String.format("{\"success\":%s,\"message\":\"%s\"}",
                    result.success(), result.message().replace("\"", "\\\""));
        } catch (HostAgentException e) {
            return "{\"success\":false,\"error\":\"" + e.getMessage().replace("\"", "\\\"") + "\"}";
        }
    }
}
