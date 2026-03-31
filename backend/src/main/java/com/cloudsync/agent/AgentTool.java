package com.cloudsync.agent;

/**
 * Interface for tools available to the DiskDetectionAgent.
 */
public interface AgentTool {

    /** Unique name used by the LLM to invoke this tool (e.g. "list_block_devices"). */
    String getName();

    /** Human-readable description of what this tool does. */
    String getDescription();

    /**
     * Execute the tool with the given argument string.
     * The argument format is tool-specific (may be empty, a path, a device name, etc.).
     */
    String execute(String argument);
}
