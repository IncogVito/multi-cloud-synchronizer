package com.cloudsync.agent.tools;

import com.cloudsync.agent.AgentTool;
import com.cloudsync.client.HostAgentClient;
import com.cloudsync.client.hostmodel.DiskInfo;
import com.cloudsync.exception.HostAgentException;
import jakarta.inject.Singleton;

import java.util.List;

@Singleton
public class ListBlockDevicesTool implements AgentTool {

    private final HostAgentClient hostAgent;

    public ListBlockDevicesTool(HostAgentClient hostAgent) {
        this.hostAgent = hostAgent;
    }

    @Override
    public String getName() { return "list_block_devices"; }

    @Override
    public String getDescription() {
        return "Lists all block devices on the system. Returns JSON array of disk info.";
    }

    @Override
    public String execute(String argument) {
        try {
            List<DiskInfo> disks = hostAgent.listDisks();
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < disks.size(); i++) {
                DiskInfo d = disks.get(i);
                if (i > 0) sb.append(",");
                sb.append(String.format(
                        "{\"name\":\"%s\",\"path\":\"%s\",\"size\":\"%s\",\"type\":\"%s\",\"mountpoint\":%s,\"label\":%s,\"vendor\":\"%s\",\"model\":\"%s\"}",
                        d.name(), d.path(), d.size(), d.type(),
                        d.mountpoint() != null ? "\"" + d.mountpoint() + "\"" : "null",
                        d.label() != null ? "\"" + d.label() + "\"" : "null",
                        d.vendor(), d.model()
                ));
            }
            sb.append("]");
            return sb.toString();
        } catch (HostAgentException e) {
            return "Error: " + e.getMessage();
        }
    }
}
