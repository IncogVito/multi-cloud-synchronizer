package com.cloudsync.client;

import com.cloudsync.model.dto.ICloudBatchDeleteRequest;
import com.cloudsync.model.dto.ICloudBatchDeleteResponse;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.*;
import io.micronaut.http.client.annotation.Client;

/**
 * HTTP client for iCloud batch-delete operations.
 * Configured with a 300s read-timeout to accommodate sequential per-photo iCloud API calls.
 */
@Client(id = "icloud-delete", value = "${icloud.service.url}")
public interface ICloudDeleteClient {

    @Post("/photos/batch-delete")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    HttpResponse<ICloudBatchDeleteResponse> batchDeletePhotos(
            @Body ICloudBatchDeleteRequest request,
            @Header("X-Session-ID") String sessionId);
}
