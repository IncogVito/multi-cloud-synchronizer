package com.cloudsync.controller;

import com.cloudsync.model.dto.BatchPhotoRequest;
import com.cloudsync.model.dto.PhotoListResponse;
import com.cloudsync.model.dto.PhotoResponse;
import com.cloudsync.service.PhotoService;
import com.cloudsync.service.SyncService;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.*;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.io.IOException;

@Tag(name = "Photos")
@Controller("/api/photos")
@Secured(SecurityRule.IS_AUTHENTICATED)
@ExecuteOn(TaskExecutors.BLOCKING)
public class PhotoController {

    private final PhotoService photoService;
    private final SyncService syncService;

    public PhotoController(PhotoService photoService, SyncService syncService) {
        this.photoService = photoService;
        this.syncService = syncService;
    }

    @Operation(summary = "List photos", description = "Returns paginated photos with optional filters")
    @ApiResponse(responseCode = "200", description = "Photo list")
    @Get
    @Produces(MediaType.APPLICATION_JSON)
    public PhotoListResponse listPhotos(
            @QueryValue(defaultValue = "") String accountId,
            @QueryValue(defaultValue = "") String synced,
            @QueryValue(defaultValue = "0") int page,
            @QueryValue(defaultValue = "20") int size) {

        String accountIdParam = accountId.isBlank() ? null : accountId;
        Boolean syncedParam = synced.isBlank() ? null : Boolean.parseBoolean(synced);
        return photoService.listPhotos(accountIdParam, syncedParam, page, size);
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
        return photoService.getFullPhotoBytes(id)
                .map(HttpResponse::ok)
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
