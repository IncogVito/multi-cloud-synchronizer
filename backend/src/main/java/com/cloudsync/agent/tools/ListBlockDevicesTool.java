package com.cloudsync.agent.tools;

import com.cloudsync.agent.AgentTool;
import com.cloudsync.util.ShellExecutor;
import jakarta.inject.Singleton;

@Singleton
public class ListBlockDevicesTool implements AgentTool {

    private final ShellExecutor shell;

    public ListBlockDevicesTool(ShellExecutor shell) {
        this.shell = shell;
    }

    @Override
    public String getName() { return "list_block_devices"; }

    @Override
    public String getDescription() {
        return "Lists all block devices on the system using lsblk -J. Returns JSON output.";
    }

    @Override
    public String execute(String argument) {
        ShellExecutor.ShellResult result = shell.execute("lsblk", "-J");
        return result.isSuccess() ? result.stdout() : "Error: " + result.stderr();
    }
}
