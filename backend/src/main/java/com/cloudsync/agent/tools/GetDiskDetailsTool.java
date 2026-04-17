package com.cloudsync.agent.tools;

import com.cloudsync.agent.AgentTool;
import com.cloudsync.client.HostAgentClient;
import com.cloudsync.client.hostmodel.DiskDetailsResult;
import com.cloudsync.exception.HostAgentException;
import io.micronaut.serde.ObjectMapper;
import jakarta.inject.Singleton;

/**
 * Returns detailed info for a specific block device: partitions, fstype, UUID, label.
 * Argument: device path (e.g. "/dev/sdb").
 */
@Singleton
public class GetDiskDetailsTool implements AgentTool {

    private final HostAgentClient hostAgent;
    private final ObjectMapper objectMapper;

    public GetDiskDetailsTool(HostAgentClient hostAgent, ObjectMapper objectMapper) {
        this.hostAgent = hostAgent;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() { return "get_disk_details"; }

    @Override
    public String getDescription() {
        return "Returns detailed info for a block device: partitions, fstype, UUID, label. Argument: device path (e.g. '/dev/sdb').";
    }

    @Override
    public String execute(String argument) {
        if (argument == null || argument.isBlank()) {
            return "{\"error\":\"device path required\"}";
        }
        try {
            DiskDetailsResult result = hostAgent.getDiskDetails(argument.trim());
            return objectMapper.writeValueAsString(result);
        } catch (HostAgentException e) {
            return "{\"error\":\"" + e.getMessage().replace("\"", "\\\"") + "\"}";
        } catch (Exception e) {
            return "{\"error\":\"unexpected error\"}";
        }
    }
}
