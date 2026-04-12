package com.cloudsync.agent.tools;

import com.cloudsync.agent.AgentTool;
import com.cloudsync.client.HostAgentClient;
import com.cloudsync.client.hostmodel.DeviceIdResult;
import com.cloudsync.exception.HostAgentException;
import jakarta.inject.Singleton;

@Singleton
public class ReadDiskLabelTool implements AgentTool {

    private final HostAgentClient hostAgent;

    public ReadDiskLabelTool(HostAgentClient hostAgent) {
        this.hostAgent = hostAgent;
    }

    @Override
    public String getName() { return "read_disk_label"; }

    @Override
    public String getDescription() {
        return "Reads the label/UUID of a block device. Argument: device path (e.g. /dev/sdb1).";
    }

    @Override
    public String execute(String device) {
        if (device == null || device.isBlank()) {
            return "Error: device path required";
        }
        try {
            DeviceIdResult result = hostAgent.readDeviceId(device);
            return String.format("{\"device\":\"%s\",\"uuid\":%s,\"label\":%s}",
                    result.device(),
                    result.uuid() != null ? "\"" + result.uuid() + "\"" : "null",
                    result.label() != null ? "\"" + result.label() + "\"" : "null");
        } catch (HostAgentException e) {
            return "Error: " + e.getMessage();
        }
    }
}
