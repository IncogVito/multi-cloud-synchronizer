package com.cloudsync.client;

import com.cloudsync.model.dto.ICloudBatchDeleteRequest;
import com.cloudsync.model.dto.ICloudBatchDeleteResponse;
import com.cloudsync.model.dto.ICloudPhotoListResponse;
import com.cloudsync.model.dto.ICloudPrefetchStatus;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.*;
import io.micronaut.http.client.annotation.Client;

import java.util.List;
import java.util.Map;

/**
 * Declarative HTTP client for the icloud-service (Python/FastAPI microservice).
 */
@Client("${icloud.service.url}")
public interface ICloudServiceClient {

    @Get("/health")
    HttpResponse<Map<String, Object>> health();

    @Post("/auth/login")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    HttpResponse<Map<String, Object>> login(@Body Map<String, String> request);

    @Post("/auth/2fa")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    HttpResponse<Map<String, Object>> twoFa(@Body Map<String, String> request);

    @Get("/auth/sessions")
    HttpResponse<List<Map<String, Object>>> listSessions();

    @Delete("/auth/sessions/{sessionId}")
    HttpResponse<Void> deleteSession(@PathVariable String sessionId);

    @Post("/photos/prefetch")
    @Consumes(MediaType.APPLICATION_JSON)
    HttpResponse<Map<String, Object>> prefetchPhotos(
            @Header("X-Session-ID") String sessionId);

    @Get("/photos/prefetch/status")
    @Consumes(MediaType.APPLICATION_JSON)
    HttpResponse<ICloudPrefetchStatus> getPrefetchStatus(
            @Header("X-Session-ID") String sessionId);

    @Get("/photos")
    @Consumes(MediaType.APPLICATION_JSON)
    HttpResponse<ICloudPhotoListResponse> listPhotos(
            @Header("X-Session-ID") String sessionId,
            @QueryValue(defaultValue = "100") int limit,
            @QueryValue(defaultValue = "0") int offset);

    @Get("/photos/download")
    HttpResponse<byte[]> downloadPhoto(
            @QueryValue("photo_id") String photoId,
            @Header("X-Session-ID") String sessionId);

    @Get("/photos/thumbnail")
    HttpResponse<byte[]> downloadThumbnail(
            @QueryValue("photo_id") String photoId,
            @Header("X-Session-ID") String sessionId,
            @QueryValue(defaultValue = "256") int size);

    @Delete("/photos/delete")
    HttpResponse<Map<String, Object>> deletePhoto(
            @QueryValue("photo_id") String photoId,
            @Header("X-Session-ID") String sessionId);

    @Post("/photos/batch-delete")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    HttpResponse<ICloudBatchDeleteResponse> batchDeletePhotos(
            @Body ICloudBatchDeleteRequest request,
            // FIX: create SessionId annotation
            @Header("X-Session-ID") String sessionId);
}
