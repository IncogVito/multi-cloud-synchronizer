package com.cloudsync.controller;

import com.cloudsync.model.dto.BatchPhotoRequest;
import com.cloudsync.model.dto.PhotoListResponse;
import com.cloudsync.model.dto.PhotoResponse;
import com.cloudsync.service.PhotoService;
import com.cloudsync.service.SyncService;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.*;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;

import java.io.IOException;
import java.util.Map;

@Controller("/api/photos")
@Secured(SecurityRule.IS_AUTHENTICATED)
public class PhotoController {

    private final PhotoService photoService;
    private final SyncService syncService;

    public PhotoController(PhotoService photoService, SyncService syncService) {
        this.photoService = photoService;
        this.syncService = syncService;
    }

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

    @Get("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public HttpResponse<PhotoResponse> getPhoto(@PathVariable String id) {
        return photoService.getPhoto(id)
                .map(HttpResponse::ok)
                .orElse(HttpResponse.notFound());
    }

    @Get("/{id}/thumbnail")
    @Produces("image/jpeg")
    public HttpResponse<byte[]> getThumbnail(@PathVariable String id) throws IOException {
        return photoService.getThumbnailBytes(id)
                .map(HttpResponse::ok)
                .orElse(HttpResponse.notFound());
    }

    @Get("/{id}/full")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public HttpResponse<byte[]> getFullPhoto(@PathVariable String id) throws IOException {
        return photoService.getFullPhotoBytes(id)
                .map(HttpResponse::ok)
                .orElse(HttpResponse.notFound());
    }

    @Post("/sync")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public HttpResponse<Map<String, Object>> syncPhotos(@QueryValue String accountId) {
        int synced = syncService.syncFromICloud(accountId);
        return HttpResponse.ok(Map.of("synced", synced, "accountId", accountId));
    }

    @Delete("/icloud")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public HttpResponse<Void> deleteFromICloud(
            @QueryValue String accountId,
            @Body BatchPhotoRequest request) {
        syncService.deleteFromICloud(accountId, request.photoIds());
        return HttpResponse.noContent();
    }

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
