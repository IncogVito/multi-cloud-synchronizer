package com.cloudsync.client;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.http.client.annotation.Client;

@Client(id = "icloud-large", value = "${icloud.service.url}")
public interface ICloudLargeDownloadClient {

    @Get("/photos/download")
    HttpResponse<byte[]> downloadPhoto(
            @QueryValue("photo_id") String photoId,
            @Header("X-Session-ID") String sessionId);
}
