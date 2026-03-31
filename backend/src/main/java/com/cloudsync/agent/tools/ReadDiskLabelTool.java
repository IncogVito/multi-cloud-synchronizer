package com.cloudsync.agent.tools;

import com.cloudsync.agent.AgentTool;
import com.cloudsync.util.ShellExecutor;
import jakarta.inject.Singleton;

@Singleton
public class ReadDiskLabelTool implements AgentTool {

    private final ShellExecutor shell;

    public ReadDiskLabelTool(ShellExecutor shell) {
        this.shell = shell;
    }

    @Override
    public String getName() { return "read_disk_label"; }

    @Override
    public String getDescription() {
        return "Reads the label/filesystem info of a block device. Argument: device path (e.g. /dev/sdb1).";
    }

    @Override
    public String execute(String device) {
        if (device == null || device.isBlank()) {
            return "Error: device path required";
        }
        ShellExecutor.ShellResult result = shell.execute("blkid", device);
        return result.isSuccess() ? result.stdout() : "Error: " + result.stderr();
    }
}
