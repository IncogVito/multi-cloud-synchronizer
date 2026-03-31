package com.cloudsync.agent.tools;

import com.cloudsync.agent.AgentTool;
import com.cloudsync.util.ShellExecutor;
import jakarta.inject.Singleton;

@Singleton
public class CheckDiskSpaceTool implements AgentTool {

    private final ShellExecutor shell;

    public CheckDiskSpaceTool(ShellExecutor shell) {
        this.shell = shell;
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
        ShellExecutor.ShellResult result = shell.execute("df", "-h", path);
        return result.isSuccess() ? result.stdout() : "Error: " + result.stderr();
    }
}
