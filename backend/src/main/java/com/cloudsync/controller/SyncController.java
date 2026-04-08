package com.cloudsync.controller;

import com.cloudsync.model.dto.SyncProgressEvent;
import com.cloudsync.model.dto.SyncStartResponse;
import com.cloudsync.service.SyncService;
import com.cloudsync.service.SyncStateHolder;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.*;
import io.micronaut.http.sse.Event;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;

import java.util.Optional;

@Controller("/api/sync")
@Secured(SecurityRule.IS_AUTHENTICATED)
public class SyncController {

    private final SyncService syncService;
    private final SyncStateHolder syncStateHolder;

    public SyncController(SyncService syncService, SyncStateHolder syncStateHolder) {
        this.syncService = syncService;
        this.syncStateHolder = syncStateHolder;
    }

    @Post("/{accountId}")
    @ExecuteOn(TaskExecutors.BLOCKING)
    @Produces(MediaType.APPLICATION_JSON)
    public SyncStartResponse startSync(@PathVariable String accountId) {
        return syncService.startSync(accountId);
    }

    @Get(value = "/{accountId}/events", produces = MediaType.TEXT_EVENT_STREAM)
    public Publisher<Event<SyncProgressEvent>> syncEvents(@PathVariable String accountId) {
        return Flux.from(syncStateHolder.subscribe(accountId))
                .map(Event::of);
    }

    @Get("/{accountId}/status")
    @Produces(MediaType.APPLICATION_JSON)
    public Optional<SyncProgressEvent> getSyncStatus(@PathVariable String accountId) {
        return syncStateHolder.getSnapshot(accountId);
    }
}
