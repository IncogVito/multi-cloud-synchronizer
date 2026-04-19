package com.cloudsync.controller;

import com.cloudsync.model.dto.AgentStepEvent;
import com.cloudsync.service.DiskDetectionAgent;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.sse.Event;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

@Tag(name = "Agent")
@Controller("/api/agent")
@Secured(SecurityRule.IS_AUTHENTICATED)
public class AgentController {

    private static final Logger LOG = LoggerFactory.getLogger(AgentController.class);

    private final DiskDetectionAgent diskDetectionAgent;

    public AgentController(DiskDetectionAgent diskDetectionAgent) {
        this.diskDetectionAgent = diskDetectionAgent;
    }

    @Operation(summary = "Detect and mount drive", description = "Runs the AI disk detection agent and streams SSE step events")
    @ApiResponse(responseCode = "200", description = "SSE stream with agent steps")
    @Post("/detect-drive")
    @Produces(MediaType.TEXT_EVENT_STREAM)
    public Publisher<Event<AgentStepEvent>> detectDrive() {
        Flux<AgentStepEvent> steps = diskDetectionAgent.detectDrive();
        return steps.map(step -> Event.of(step).name("agent-step"));
    }
}
