package com.cloudsync.agent.tools;

import com.cloudsync.agent.AgentTool;
import com.cloudsync.client.HostAgentClient;
import com.cloudsync.exception.HostAgentException;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;

import java.nio.file.Files;
import java.nio.file.Path;

@Singleton
public class CheckMountPointTool implements AgentTool {

    private final HostAgentClient hostAgent;
    private final String externalDrivePath;

    public CheckMountPointTool(HostAgentClient hostAgent,
                               @Value("${app.external-drive-path}") String externalDrivePath) {
        this.hostAgent = hostAgent;
        this.externalDrivePath = externalDrivePath;
    }

    @Override
    public String getName() { return "check_mount_point"; }

    @Override
    public String getDescription() {
        return "Checks whether a given path is mounted. Argument: path (defaults to configured external drive path).";
    }

    @Override
    public String execute(String path) {
        if (path == null || path.isBlank()) {
            path = externalDrivePath;
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
