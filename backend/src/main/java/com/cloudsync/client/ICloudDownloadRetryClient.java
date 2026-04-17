package com.cloudsync.client;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.http.client.annotation.Client;

/**
 * Secondary iCloud HTTP client used exclusively for photo download retries.
 * Configured with a longer read-timeout (90s) than the primary client (30s).
 */
@Client(id = "icloud-retry", value = "${icloud.service.url}")
public interface ICloudDownloadRetryClient {

    @Get("/photos/download")
    HttpResponse<byte[]> downloadPhoto(
            @QueryValue("photo_id") String photoId,
            @Header("X-Session-ID") String sessionId);
}
