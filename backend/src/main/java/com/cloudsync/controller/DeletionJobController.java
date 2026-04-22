package com.cloudsync.controller;

import com.cloudsync.model.dto.DeletionJobRequest;
import com.cloudsync.model.dto.DeletionJobStartResponse;
import com.cloudsync.model.dto.DeletionProgress;
import com.cloudsync.service.DeletionJob;
import com.cloudsync.service.DeletionJobService;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.*;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.http.sse.Event;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;

@Tag(name = "Deletion Jobs")
@Controller("/api/photos/deletion-jobs")
@Secured(SecurityRule.IS_AUTHENTICATED)
@ExecuteOn(TaskExecutors.BLOCKING)
public class DeletionJobController {

    private final DeletionJobService deletionJobService;

    public DeletionJobController(DeletionJobService deletionJobService) {
        this.deletionJobService = deletionJobService;
    }

    @Operation(summary = "Start a batch deletion job")
    @Post
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public DeletionJobStartResponse startDeletionJob(@Body DeletionJobRequest request) {
        return deletionJobService.startJob(request.accountId(), request.photoIds(), request.provider());
    }

    @Operation(summary = "Get deletion job status")
    @Get("/{jobId}")
    @Produces(MediaType.APPLICATION_JSON)
    public DeletionProgress getDeletionJob(@PathVariable String jobId) {
        return deletionJobService.getJob(jobId)
                .map(DeletionJob::currentProgress)
                .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, "Job not found: " + jobId));
    }

    @Operation(summary = "Stream deletion job progress (SSE)")
    @Get(value = "/{jobId}/progress", produces = MediaType.TEXT_EVENT_STREAM)
    public Publisher<Event<DeletionProgress>> getDeletionJobProgress(@PathVariable String jobId) {
        return deletionJobService.getJob(jobId)
                .map(job -> (Publisher<Event<DeletionProgress>>) Flux.from(job.subscribe()).map(Event::of))
                .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, "Job not found: " + jobId));
    }

    @Operation(summary = "Cancel a deletion job")
    @Delete("/{jobId}")
    public HttpResponse<Void> cancelDeletionJob(@PathVariable String jobId) {
        deletionJobService.cancelJob(jobId);
        return HttpResponse.noContent();
    }
}
