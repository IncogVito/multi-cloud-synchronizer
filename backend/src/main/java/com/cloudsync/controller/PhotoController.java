package com.cloudsync.controller;

import com.cloudsync.model.dto.BatchPhotoRequest;
import com.cloudsync.model.dto.GenerateThumbnailsRequest;
import com.cloudsync.model.dto.MissingThumbnailsCount;
import com.cloudsync.model.dto.MonthSummaryResponse;
import com.cloudsync.model.dto.PhotoListResponse;
import com.cloudsync.model.dto.PhotoResponse;
import com.cloudsync.model.dto.ThumbnailJobResponse;
import com.cloudsync.model.dto.ThumbnailProgress;
import com.cloudsync.service.PhotoService;
import com.cloudsync.service.SyncService;
import com.cloudsync.service.ThumbnailJobService;
import com.cloudsync.service.ThumbnailService;
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
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.List;

@Tag(name = "Photos")
@Controller("/api/photos")
@Secured(SecurityRule.IS_AUTHENTICATED)
@ExecuteOn(TaskExecutors.BLOCKING)
public class PhotoController {

    private final PhotoService photoService;
    private final SyncService syncService;
    private final ThumbnailService thumbnailService;
    private final ThumbnailJobService thumbnailJobService;

    public PhotoController(PhotoService photoService, SyncService syncService,
                           ThumbnailService thumbnailService, ThumbnailJobService thumbnailJobService) {
        this.photoService = photoService;
        this.syncService = syncService;
        this.thumbnailService = thumbnailService;
        this.thumbnailJobService = thumbnailJobService;
    }

    @Operation(summary = "List photos", description = "Returns paginated photos with optional filters. Use yearMonth (YYYY-MM) or year (YYYY) to scope to a specific time range.")
    @ApiResponse(responseCode = "200", description = "Photo list")
    @Get
    @Produces(MediaType.APPLICATION_JSON)
    public PhotoListResponse listPhotos(
            @QueryValue(defaultValue = "") String accountId,
            @QueryValue(defaultValue = "") String synced,
            @QueryValue(defaultValue = "") String storageDeviceId,
            @QueryValue(defaultValue = "") String yearMonth,
            @QueryValue(defaultValue = "") String year,
            @QueryValue(defaultValue = "0") int page,
            @QueryValue(defaultValue = "20") int size) {

        String accountIdParam = accountId.isBlank() ? null : accountId;
        Boolean syncedParam = synced.isBlank() ? null : Boolean.parseBoolean(synced);
        String deviceIdParam = storageDeviceId.isBlank() ? null : storageDeviceId;
        String yearMonthParam = yearMonth.isBlank() ? null : yearMonth;
        String yearParam = year.isBlank() ? null : year;
        return photoService.listPhotos(accountIdParam, syncedParam, deviceIdParam, yearMonthParam, yearParam, page, size);
    }

    @Operation(summary = "Photos grouped by month", description = "Returns photo counts and size totals per calendar month, sorted newest-first")
    @ApiResponse(responseCode = "200", description = "Month summaries")
    @Get("/months-summary")
    @Produces(MediaType.APPLICATION_JSON)
    public List<MonthSummaryResponse> getMonthsSummary(
            @QueryValue String storageDeviceId,
            @QueryValue(defaultValue = "") String accountId) {

        String accountIdParam = accountId.isBlank() ? null : accountId;
        return photoService.getMonthsSummary(storageDeviceId, accountIdParam);
    }

    @Operation(summary = "Count photos missing thumbnails")
    @ApiResponse(responseCode = "200", description = "Count of synced photos without thumbnail")
    @Get("/missing-thumbnails-count")
    @Produces(MediaType.APPLICATION_JSON)
    public MissingThumbnailsCount countMissingThumbnails(
            @QueryValue(defaultValue = "") String storageDeviceId) {

        long count = storageDeviceId.isBlank()
                ? thumbnailService.countMissing()
                : thumbnailService.countMissingByDevice(storageDeviceId);
        return new MissingThumbnailsCount(count);
    }

    @Operation(summary = "Start thumbnail generation job", description = "Returns jobId immediately. Job runs server-side and survives page navigation.")
    @ApiResponse(responseCode = "200", description = "Job started")
    @Post("/thumbnail-jobs")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public ThumbnailJobResponse startThumbnailJob(@Body GenerateThumbnailsRequest request) {
        String deviceId = request != null ? request.storageDeviceId() : null;
        List<String> photoIds = request != null ? request.photoIds() : null;
        return thumbnailJobService.startJob(deviceId, photoIds);
    }

    @Operation(summary = "Stream thumbnail job progress (SSE)", description = "Reconnect-safe: replays all past progress events on reconnect.")
    @ApiResponse(responseCode = "200", description = "SSE progress stream")
    @ApiResponse(responseCode = "404", description = "Job not found")
    @Get(value = "/thumbnail-jobs/{jobId}/progress", produces = MediaType.TEXT_EVENT_STREAM)
    public Publisher<Event<ThumbnailProgress>> getThumbnailJobProgress(@PathVariable String jobId) {
        return thumbnailJobService.getJob(jobId)
                .map(job -> (Publisher<Event<ThumbnailProgress>>) Flux.from(job.subscribe()).map(Event::of))
                .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, "Job not found: " + jobId));
    }

    @Operation(summary = "Cancel thumbnail generation job")
    @ApiResponse(responseCode = "204", description = "Cancelled")
    @Delete("/thumbnail-jobs/{jobId}")
    public HttpResponse<Void> cancelThumbnailJob(@PathVariable String jobId) {
        thumbnailJobService.cancelJob(jobId);
        return HttpResponse.noContent();
    }

    @Operation(summary = "Get photo details")
    @ApiResponse(responseCode = "200", description = "Photo found")
    @ApiResponse(responseCode = "404", description = "Photo not found")
    @Get("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public HttpResponse<PhotoResponse> getPhoto(@PathVariable String id) {
        return photoService.getPhoto(id)
                .map(HttpResponse::ok)
                .orElse(HttpResponse.notFound());
    }

    @Operation(summary = "Get photo thumbnail")
    @ApiResponse(responseCode = "200", description = "JPEG thumbnail")
    @ApiResponse(responseCode = "404", description = "Photo not found")
    @Get("/{id}/thumbnail")
    @Produces("image/jpeg")
    public HttpResponse<byte[]> getThumbnail(@PathVariable String id) throws IOException {
        return photoService.getThumbnailBytes(id)
                .map(HttpResponse::ok)
                .orElse(HttpResponse.notFound());
    }

    @Operation(summary = "Get full photo from disk")
    @ApiResponse(responseCode = "200", description = "Photo binary")
    @ApiResponse(responseCode = "404", description = "Photo not found")
    @Get("/{id}/full")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public HttpResponse<byte[]> getFullPhoto(@PathVariable String id) throws IOException {
        return photoService.getFullPhotoData(id)
                .<HttpResponse<byte[]>>map(data -> HttpResponse.ok(data.bytes())
                        .contentType(new MediaType(data.mimeType())))
                .orElse(HttpResponse.notFound());
    }

    @Operation(summary = "Delete photos from iCloud", description = "Batch delete; photos must already be synced to disk")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @Delete("/icloud")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public HttpResponse<Void> deleteFromICloud(
            @QueryValue String accountId,
            @Body BatchPhotoRequest request) {
        syncService.deleteFromICloud(accountId, request.photoIds());
        return HttpResponse.noContent();
    }

    @Operation(summary = "Delete photos from iPhone", description = "Batch delete; photos must already be synced to disk")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @Delete("/iphone")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public HttpResponse<Void> deleteFromIPhone(
            @QueryValue String accountId,
            @Body BatchPhotoRequest request) {
        syncService.deleteFromIPhone(accountId, request.photoIds());
        return HttpResponse.noContent();
    }
}
