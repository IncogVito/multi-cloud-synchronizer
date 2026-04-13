package com.cloudsync.agent;

import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Fallback LLM provider: simple heuristic-based agent used when OpenRouter API key is not set.
 *
 * Strategy:
 * 1. check_mount_point → if already mounted, done
 * 2. list_block_devices → find candidate /dev/sd* or /dev/nvme*
 * 3. mount_device found_device:<mountPath>
 * 4. check_disk_space <mountPath>
 * 5. Final answer
 */
@Singleton
public class FallbackLlmProvider implements LlmProvider {

    private static final Logger LOG = LoggerFactory.getLogger(FallbackLlmProvider.class);

    private final String externalDrivePath;

    public FallbackLlmProvider(@Value("${app.external-drive-path}") String externalDrivePath) {
        this.externalDrivePath = externalDrivePath;
    }

    @Override
    public AgentAction nextAction(String systemPrompt, List<String> history, List<AgentTool> tools) {
        LOG.debug("FallbackLlmProvider step, history size={}", history.size());

        int step = history.size() / 2;  // each step adds 2 entries (tool call + result)

        return switch (step) {
            case 0 -> AgentAction.callTool("check_mount_point", externalDrivePath);
            case 1 -> {
                String lastResult = getLastResult(history);
                if (lastResult.contains("\"mounted\": true")) {
                    yield AgentAction.callTool("check_disk_space", externalDrivePath);
                }
                yield AgentAction.callTool("list_block_devices", "");
            }
            case 2 -> {
                String lastResult = getLastResult(history);
                if (lastResult.contains("\"mounted\": true")) {
                    // Was already mounted at step 1
                    yield AgentAction.finalAnswer("Drive already mounted at " + externalDrivePath + ".");
                }
                // Try to find a candidate device from lsblk output
                String candidate = findCandidateDevice(lastResult);
                if (candidate != null) {
                    yield AgentAction.callTool("mount_device", candidate + ":" + externalDrivePath);
                }
                yield AgentAction.finalAnswer("No suitable external drive device found.");
            }
            case 3 -> {
                String lastResult = getLastResult(history);
                if (lastResult.contains("\"mounted\": true")) {
                    yield AgentAction.callTool("check_disk_space", externalDrivePath);
                }
                yield AgentAction.finalAnswer("Failed to mount drive: " + lastResult);
            }
            default -> AgentAction.finalAnswer("Drive detection complete.");
        };
    }

    private String getLastResult(List<String> history) {
        if (history.size() < 2) return "";
        return history.get(history.size() - 1);
    }

    private String findCandidateDevice(String lsblkOutput) {
        // Simple heuristic: look for /dev/sdb, /dev/sdc, /dev/nvme1n1 etc.
        // Excludes sda (usually system disk)
        String[] lines = lsblkOutput.split("\n");
        for (String line : lines) {
            if (line.contains("sdb") || line.contains("sdc") || line.contains("nvme1")) {
                // Extract first token that looks like a device name
                if (line.contains("\"sdb")) return "/dev/sdb1";
                if (line.contains("\"sdc")) return "/dev/sdc1";
                if (line.contains("\"nvme1")) return "/dev/nvme1n1p1";
            }
        }
        return null;
    }
}
