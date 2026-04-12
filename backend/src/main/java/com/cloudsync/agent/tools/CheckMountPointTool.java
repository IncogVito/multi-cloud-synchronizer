package com.cloudsync.agent.tools;

import com.cloudsync.agent.AgentTool;
import com.cloudsync.client.HostAgentClient;
import com.cloudsync.exception.HostAgentException;
import jakarta.inject.Singleton;

import java.nio.file.Files;
import java.nio.file.Path;

@Singleton
public class CheckMountPointTool implements AgentTool {

    private final HostAgentClient hostAgent;

    public CheckMountPointTool(HostAgentClient hostAgent) {
        this.hostAgent = hostAgent;
    }

    @Override
    public String getName() { return "check_mount_point"; }

    @Override
    public String getDescription() {
        return "Checks whether a given path is mounted. Argument: path (e.g. /mnt/external-drive).";
    }

    @Override
    public String execute(String path) {
        if (path == null || path.isBlank()) {
            path = "/mnt/external-drive";
        }
        boolean exists = Files.exists(Path.of(path));
        boolean mounted;
        try {
            mounted = hostAgent.checkDrive(path).available();
        } catch (HostAgentException e) {
            mounted = false;
        }
        return String.format("{\"path\": \"%s\", \"mounted\": %s, \"exists\": %s}", path, mounted, exists);
    }
}
