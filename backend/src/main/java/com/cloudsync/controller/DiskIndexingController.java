package com.cloudsync.controller;

import com.cloudsync.model.dto.DiskIndexProgressEvent;
import com.cloudsync.service.DiskIndexStateHolder;
import com.cloudsync.service.DiskIndexingService;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.*;
import io.micronaut.http.sse.Event;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.Optional;

@Controller("/api/disk-index")
@Secured(SecurityRule.IS_AUTHENTICATED)
public class DiskIndexingController {

    private static final Logger LOG = LoggerFactory.getLogger(DiskIndexingController.class);

    private final DiskIndexingService diskIndexingService;
    private final DiskIndexStateHolder stateHolder;

    public DiskIndexingController(DiskIndexingService diskIndexingService, DiskIndexStateHolder stateHolder) {
        this.diskIndexingService = diskIndexingService;
        this.stateHolder = stateHolder;
    }

    @Post("/start")
    @ExecuteOn(TaskExecutors.BLOCKING)
    @Produces(MediaType.APPLICATION_JSON)
    public HttpResponse<?> startIndexing() {
        if (diskIndexingService.isRunning()) {
            return HttpResponse.ok(Map.of("status", "ALREADY_RUNNING"));
        }
        try {
            diskIndexingService.startIndexing();
            return HttpResponse.ok(Map.of("status", "STARTED"));
        } catch (Exception e) {
            LOG.warn("Failed to start disk indexing: {}", e.getMessage());
            return HttpResponse.serverError(Map.of("error", e.getMessage()));
        }
    }

    @Get(value = "/events", produces = MediaType.TEXT_EVENT_STREAM)
    public Publisher<Event<DiskIndexProgressEvent>> events() {
        return Flux.from(stateHolder.subscribe()).map(Event::of);
    }

    @Get("/status")
    @Produces(MediaType.APPLICATION_JSON)
    public Optional<DiskIndexProgressEvent> status() {
        return stateHolder.getSnapshot();
    }

    @Get("/reorganize-preview")
    @ExecuteOn(TaskExecutors.BLOCKING)
    @Produces(MediaType.APPLICATION_JSON)
    public HttpResponse<?> reorganizePreview() {
        try {
            return HttpResponse.ok(diskIndexingService.reorganizePreview());
        } catch (Exception e) {
            LOG.warn("reorganize preview failed: {}", e.getMessage());
            return HttpResponse.serverError(Map.of("error", e.getMessage()));
        }
    }

    @Post("/reorganize")
    @ExecuteOn(TaskExecutors.BLOCKING)
    @Produces(MediaType.APPLICATION_JSON)
    public HttpResponse<?> reorganize() {
        try {
            return HttpResponse.ok(diskIndexingService.reorganize());
        } catch (Exception e) {
            LOG.warn("reorganize failed: {}", e.getMessage());
            return HttpResponse.serverError(Map.of("error", e.getMessage()));
        }
    }
}
