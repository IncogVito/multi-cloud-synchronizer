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
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;

@Controller("/api/agent")
@Secured(SecurityRule.IS_AUTHENTICATED)
public class AgentController {

    private final DiskDetectionAgent diskDetectionAgent;

    public AgentController(DiskDetectionAgent diskDetectionAgent) {
        this.diskDetectionAgent = diskDetectionAgent;
    }

    /**
     * Triggers the disk detection AI agent and streams step events as Server-Sent Events.
     */
    @Post("/detect-drive")
    @Produces(MediaType.TEXT_EVENT_STREAM)
    public Publisher<Event<AgentStepEvent>> detectDrive() {
        Flux<AgentStepEvent> steps = diskDetectionAgent.detectDrive();
        return steps.map(step -> Event.of(step).name("agent-step"));
    }
}
