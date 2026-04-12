package com.cloudsync.agent.tools;

import com.cloudsync.agent.AgentTool;
import com.cloudsync.client.HostAgentClient;
import com.cloudsync.client.hostmodel.DriveStatus;
import com.cloudsync.exception.HostAgentException;
import jakarta.inject.Singleton;

@Singleton
public class CheckDiskSpaceTool implements AgentTool {

    private final HostAgentClient hostAgent;

    public CheckDiskSpaceTool(HostAgentClient hostAgent) {
        this.hostAgent = hostAgent;
    }

    @Override
    public String getName() { return "check_disk_space"; }

    @Override
    public String getDescription() {
        return "Checks free disk space at a given path. Argument: path (e.g. /mnt/external-drive).";
    }

    @Override
    public String execute(String path) {
        if (path == null || path.isBlank()) {
            path = "/mnt/external-drive";
        }
        try {
            DriveStatus status = hostAgent.checkDrive(path);
            return String.format("{\"available\":%s,\"path\":%s,\"free_bytes\":%s,\"total_bytes\":%s}",
                    status.available(),
                    status.path() != null ? "\"" + status.path() + "\"" : "null",
                    status.freeBytes() != null ? status.freeBytes() : "null",
                    status.totalBytes() != null ? status.totalBytes() : "null");
        } catch (HostAgentException e) {
            return "Error: " + e.getMessage();
        }
    }
}
