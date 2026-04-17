package com.cloudsync.agent;

import com.cloudsync.client.hostmodel.DiskInfo;
import com.cloudsync.client.HostAgentClient;
import com.cloudsync.client.hostmodel.DriveStatus;
import com.cloudsync.exception.HostAgentException;
import com.cloudsync.model.dto.AgentStepEvent;
import com.cloudsync.model.entity.StorageDevice;
import com.cloudsync.repository.StorageDeviceRepository;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Iterative agent executor: drives the tool-calling loop and emits SSE events.
 *
 * Execution strategy:
 * 1. Scripted fast path — deterministic, no LLM cost.
 *    a. Check if drive already mounted → done.
 *    b. If app context has a registered device path → try mounting it.
 *    c. Else list disks; if exactly 1 candidate → auto-mount.
 * 2. LLM fallback — only when scripted path cannot decide (0 or 2+ disks, mount failed).
 */
@Singleton
public class AgentExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(AgentExecutor.class);
    private static final int MAX_STEPS = 10;

    private final LlmProvider llmProvider;
    private final List<AgentTool> tools;
    private final String systemPrompt;
    private final HostAgentClient hostAgent;
    private final StorageDeviceRepository storageDeviceRepository;
    private final String externalDrivePath;

    public AgentExecutor(LlmProvider llmProvider,
                         List<AgentTool> tools,
                         HostAgentClient hostAgent,
                         StorageDeviceRepository storageDeviceRepository,
                         @Value("${app.external-drive-path}") String externalDrivePath) {
        this.llmProvider = llmProvider;
        this.tools = tools;
        this.hostAgent = hostAgent;
        this.storageDeviceRepository = storageDeviceRepository;
        this.externalDrivePath = externalDrivePath;
        this.systemPrompt = buildSystemPrompt(externalDrivePath, tools);
    }

    private static String buildSystemPrompt(String externalDrivePath, List<AgentTool> tools) {
        String toolList = tools.stream()
                .map(t -> "- " + t.getName() + ": " + t.getDescription())
                .collect(Collectors.joining("\n"));
        return """
                You are a disk detection agent running inside a Docker container on a Linux host.
                Your goal is to detect and verify an external drive is mounted at %s.

                Available tools:
                %s

                Use tools step by step. If the expected mount point is missing or inaccessible, try unmounting \
                and remounting the device — this often resolves stale mount state.
                Stop when you confirm the drive is mounted and accessible, \
                or when you determine no suitable drive is available.
                Be concise. Prefer /dev/sd* or /dev/nvme* devices. \
                Do not mount any device that hosts the root filesystem, /boot, or swap.
                """.formatted(externalDrivePath, toolList);
    }

    /**
     * Run the agent and return a Flux of SSE step events.
     */
    public Flux<AgentStepEvent> run() {
        return Flux.create(sink -> {
            try {
                if (tryScriptedPath(sink)) {
                    sink.complete();
                    return;
                }
                // Scripted path couldn't resolve → fall back to LLM
                LOG.info("Scripted path inconclusive, falling back to LLM agent");
                sink.next(new AgentStepEvent(0, "agent", "Scripted detection inconclusive, starting LLM agent", null, null));
                runLlmLoop(sink);
            } catch (Exception e) {
                LOG.error("Agent execution failed", e);
                sink.error(e);
            }
        });
    }

    // ── scripted fast path ────────────────────────────────────────────────────

    /**
     * Returns true if the drive was confirmed mounted (success or definitive failure).
     * Returns false when the outcome is ambiguous and LLM should take over.
     */
    private boolean tryScriptedPath(FluxSink<AgentStepEvent> sink) {
        int step = 0;

        // Step 1: Check if drive is already mounted
        step++;
        try {
            DriveStatus status = hostAgent.checkDrive(externalDrivePath);
            String result = String.format("{\"available\":%s,\"path\":%s,\"free_bytes\":%s}",
                    status.available(),
                    status.path() != null ? "\"" + status.path() + "\"" : "null",
                    status.freeBytes() != null ? status.freeBytes() : "null");
            sink.next(new AgentStepEvent(step, "check_mount_point", result, null, null));

            if (status.available()) {
                sink.next(new AgentStepEvent("final", null, null, true,
                        "Drive already mounted at " + externalDrivePath + ". Free: " + formatBytes(status.freeBytes())));
                return true;
            }
        } catch (HostAgentException e) {
            LOG.warn("check_drive failed in scripted path: {}", e.getMessage());
            sink.next(new AgentStepEvent(step, "check_mount_point",
                    "{\"available\":false,\"error\":\"" + e.getMessage() + "\"}", null, null));
        }

        // Step 2: Try registered device from DB if known
        step++;
        String knownDevicePath = findRegisteredDevicePath();
        if (knownDevicePath != null) {
            sink.next(new AgentStepEvent(step, "list_block_devices",
                    "[{\"path\":\"" + knownDevicePath + "\",\"source\":\"registered\"}]", null, null));
            return tryMount(sink, ++step, knownDevicePath);
        }

        // Step 3: List available disks
        List<DiskInfo> disks;
        try {
            disks = hostAgent.listDisks();
            String diskJson = disks.stream()
                    .map(d -> String.format("{\"name\":\"%s\",\"path\":\"%s\",\"size\":\"%s\",\"mountpoint\":%s}",
                            d.name(), d.path(), d.size(),
                            d.mountpoint() != null ? "\"" + d.mountpoint() + "\"" : "null"))
                    .collect(Collectors.joining(",", "[", "]"));
            sink.next(new AgentStepEvent(step, "list_block_devices", diskJson, null, null));
        } catch (HostAgentException e) {
            LOG.warn("list_disks failed in scripted path: {}", e.getMessage());
            sink.next(new AgentStepEvent(step, "list_block_devices",
                    "{\"error\":\"" + e.getMessage() + "\"}", null, null));
            return false;
        }

        if (disks.isEmpty()) {
            sink.next(new AgentStepEvent("final", null, null, false, "No external drives detected."));
            return true; // Definitive: no drives
        }

        if (disks.size() == 1) {
            // Single candidate — safe to auto-mount
            return tryMount(sink, ++step, disks.get(0).path());
        }

        // Multiple candidates: user must choose — hand off to LLM
        LOG.info("Found {} disk candidates, handing to LLM for selection", disks.size());
        return false;
    }

    private boolean tryMount(FluxSink<AgentStepEvent> sink, int step, String devicePath) {
        try {
            var result = hostAgent.mountDrive(devicePath, externalDrivePath);
            String mountJson = String.format("{\"mounted\":%s,\"device\":\"%s\",\"mount_point\":\"%s\",\"message\":\"%s\"}",
                    result.mounted(), result.device(), result.mountPoint(),
                    result.message().replace("\"", "\\\""));
            sink.next(new AgentStepEvent(step, "mount_device", mountJson, null, null));

            if (result.mounted()) {
                DriveStatus status = null;
                try { status = hostAgent.checkDrive(externalDrivePath); } catch (HostAgentException ignored) {}
                String freeStr = status != null ? formatBytes(status.freeBytes()) : "unknown";
                sink.next(new AgentStepEvent("final", null, null, true,
                        "Drive mounted at " + externalDrivePath + ". Device: " + devicePath + ". Free: " + freeStr));
                return true;
            }

            sink.next(new AgentStepEvent("final", null, null, false,
                    "Mount failed for " + devicePath + ": " + result.message()));
            return true; // Definitive failure
        } catch (HostAgentException e) {
            LOG.warn("mount_drive failed for {}: {}", devicePath, e.getMessage());
            sink.next(new AgentStepEvent(step, "mount_device",
                    "{\"mounted\":false,\"error\":\"" + e.getMessage() + "\"}", null, null));
            return false; // Let LLM try alternative strategies
        }
    }

    private String findRegisteredDevicePath() {
        try {
            Iterable<StorageDevice> devices = storageDeviceRepository.findAll();
            for (StorageDevice d : devices) {
                if (d.getDevicePath() != null && !d.getDevicePath().isBlank()) {
                    return d.getDevicePath();
                }
            }
        } catch (Exception e) {
            LOG.debug("Could not query registered devices: {}", e.getMessage());
        }
        return null;
    }

    private static String formatBytes(Long bytes) {
        if (bytes == null) return "unknown";
        if (bytes >= 1_073_741_824L) return String.format("%.1f GB", bytes / 1_073_741_824.0);
        if (bytes >= 1_048_576L) return String.format("%.1f MB", bytes / 1_048_576.0);
        return bytes + " B";
    }

    // ── LLM loop ──────────────────────────────────────────────────────────────

    private void runLlmLoop(FluxSink<AgentStepEvent> sink) {
        Map<String, AgentTool> toolMap = tools.stream()
                .collect(Collectors.toMap(AgentTool::getName, Function.identity()));

        List<String> history = new ArrayList<>();
        int step = 0;

        while (step < MAX_STEPS) {
            step++;
            LlmProvider.AgentAction action = llmProvider.nextAction(systemPrompt, history, tools);

            if (action.isFinalAnswer()) {
                sink.next(new AgentStepEvent("final", null, null, true, action.finalMessage()));
                break;
            }

            AgentTool tool = toolMap.get(action.toolName());
            if (tool == null) {
                String msg = "Unknown tool: " + action.toolName();
                LOG.warn(msg);
                sink.next(new AgentStepEvent(step, action.toolName(), msg, false, null));
                break;
            }

            String result = tool.execute(action.toolArgument());
            LOG.debug("LLM step {}: tool={} arg={} result={}", step, action.toolName(), action.toolArgument(), result);

            sink.next(new AgentStepEvent(step, action.toolName(), result, null, null));

            history.add("Tool call: " + action.toolName() + "(" + action.toolArgument() + ")");
            history.add("Result: " + result);
        }

        if (step >= MAX_STEPS) {
            sink.next(new AgentStepEvent("final", null, null, false, "Max steps reached without resolution"));
        }

        sink.complete();
    }
}
