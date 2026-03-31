package com.cloudsync.agent.tools;

import com.cloudsync.agent.AgentTool;
import com.cloudsync.util.ShellExecutor;
import jakarta.inject.Singleton;

import java.nio.file.Files;
import java.nio.file.Path;

@Singleton
public class CheckMountPointTool implements AgentTool {

    private final ShellExecutor shell;

    public CheckMountPointTool(ShellExecutor shell) {
        this.shell = shell;
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
        ShellExecutor.ShellResult result = shell.execute("mountpoint", "-q", path);
        boolean mounted = result.isSuccess();
        boolean exists = Files.exists(Path.of(path));
        return String.format("{\"path\": \"%s\", \"mounted\": %s, \"exists\": %s}", path, mounted, exists);
    }
}
