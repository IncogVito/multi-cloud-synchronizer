package com.cloudsync.service;

import com.cloudsync.agent.AgentExecutor;
import com.cloudsync.model.dto.AgentStepEvent;
import jakarta.inject.Singleton;
import reactor.core.publisher.Flux;

/**
 * Service facade for the disk detection AI agent.
 * Delegates to AgentExecutor which drives the tool-calling loop.
 */
@Singleton
public class DiskDetectionAgent {

    private final AgentExecutor agentExecutor;

    public DiskDetectionAgent(AgentExecutor agentExecutor) {
        this.agentExecutor = agentExecutor;
    }

    public Flux<AgentStepEvent> detectDrive() {
        return agentExecutor.run();
    }
}
