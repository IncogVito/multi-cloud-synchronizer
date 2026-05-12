package com.cloudsync.controller;

import com.cloudsync.model.dto.DeletionProgress;
import com.cloudsync.model.dto.JobSummary;
import com.cloudsync.model.dto.JobsListResponse;
import com.cloudsync.model.dto.TaskHistoryDetailDto;
import com.cloudsync.model.dto.TaskHistoryPageDto;
import com.cloudsync.model.dto.ThumbnailProgress;
import com.cloudsync.service.DeletionJob;
import com.cloudsync.service.DeletionJobService;
import com.cloudsync.service.TaskHistoryService;
import com.cloudsync.service.ThumbnailJob;
import com.cloudsync.service.ThumbnailJobService;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.QueryValue;
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
import java.util.Optional;

@Tag(name = "Jobs")
@Controller("/api/jobs")
@Secured(SecurityRule.IS_AUTHENTICATED)
@ExecuteOn(TaskExecutors.BLOCKING)
public class JobsController {

    private final DeletionJobService deletionJobService;
    private final ThumbnailJobService thumbnailJobService;
    private final TaskHistoryService taskHistoryService;

    public JobsController(DeletionJobService deletionJobService,
                          ThumbnailJobService thumbnailJobService,
                          TaskHistoryService taskHistoryService) {
        this.deletionJobService = deletionJobService;
        this.thumbnailJobService = thumbnailJobService;
        this.taskHistoryService = taskHistoryService;
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

    @Operation(summary = "List task history (paginated)")
    @Get("/history")
    public TaskHistoryPageDto listHistory(
            @QueryValue(defaultValue = "0") int page,
            @QueryValue(defaultValue = "20") int size,
            @QueryValue Optional<String> type,
            @QueryValue Optional<String> status) {
        return taskHistoryService.listHistory(page, size, type.orElse(null), status.orElse(null));
    }

    @Operation(summary = "Get task history detail by ID")
    @Get("/history/{taskId}")
    public TaskHistoryDetailDto getTaskDetail(@PathVariable String taskId) {
        return taskHistoryService.getDetail(taskId)
                .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, "Task not found: " + taskId));
    }
}
