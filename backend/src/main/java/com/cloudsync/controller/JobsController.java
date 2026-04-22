package com.cloudsync.controller;

import com.cloudsync.model.dto.DeletionProgress;
import com.cloudsync.model.dto.JobSummary;
import com.cloudsync.model.dto.JobsListResponse;
import com.cloudsync.model.dto.ThumbnailProgress;
import com.cloudsync.service.DeletionJob;
import com.cloudsync.service.DeletionJobService;
import com.cloudsync.service.ThumbnailJob;
import com.cloudsync.service.ThumbnailJobService;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
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

import java.util.ArrayList;
import java.util.List;

@Tag(name = "Jobs")
@Controller("/api/jobs")
@Secured(SecurityRule.IS_AUTHENTICATED)
@ExecuteOn(TaskExecutors.BLOCKING)
public class JobsController {

    private final DeletionJobService deletionJobService;
    private final ThumbnailJobService thumbnailJobService;

    public JobsController(DeletionJobService deletionJobService, ThumbnailJobService thumbnailJobService) {
        this.deletionJobService = deletionJobService;
        this.thumbnailJobService = thumbnailJobService;
    }

    @Operation(summary = "List all active and recent jobs")
    @Get
    public JobsListResponse listJobs() {

// FIX: Create a facade service that aggregates job summaries from all job types, instead of doing it manually here        
        List<JobSummary> all = new ArrayList<>(deletionJobService.allJobSummaries());
        thumbnailJobService.allJobSummaries().forEach(all::add);
        return new JobsListResponse(all);
    }

    @Operation(summary = "Stream progress for any job type by ID (SSE)")
    @Get(value = "/{jobId}/progress", produces = MediaType.TEXT_EVENT_STREAM)
    public Publisher<?> getJobProgress(@PathVariable String jobId) {

        // FIX: Use facade service 
        var deletion = deletionJobService.getJob(jobId);
        if (deletion.isPresent()) {
            DeletionJob job = deletion.get();
            return (Publisher<Event<DeletionProgress>>) Flux.from(job.subscribe()).map(Event::of);
        }

        var thumbnail = thumbnailJobService.getJob(jobId);
        if (thumbnail.isPresent()) {
            ThumbnailJob job = thumbnail.get();
            return (Publisher<Event<ThumbnailProgress>>) Flux.from(job.subscribe()).map(Event::of);
        }

        throw new HttpStatusException(HttpStatus.NOT_FOUND, "Job not found: " + jobId);
    }
}
