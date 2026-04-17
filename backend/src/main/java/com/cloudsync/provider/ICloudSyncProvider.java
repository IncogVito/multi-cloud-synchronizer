package com.cloudsync.provider;

import com.cloudsync.client.ICloudDownloadRetryClient;
import com.cloudsync.client.ICloudServiceClient;
import com.cloudsync.model.dto.ICloudPhotoAsset;
import com.cloudsync.model.dto.ICloudPhotoListResponse;
import com.cloudsync.model.dto.ICloudPrefetchStatus;
import com.cloudsync.model.dto.PhotoAsset;
import com.cloudsync.model.dto.PrefetchStatus;
import io.micronaut.http.HttpResponse;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Singleton
@Named("ICLOUD")
public class ICloudSyncProvider implements PhotoSyncProvider {

    private static final Logger LOG = LoggerFactory.getLogger(ICloudSyncProvider.class);
    private static final int PAGE_SIZE = 200;
    private static final int MAX_DOWNLOAD_ATTEMPTS = 2;

    private final ICloudServiceClient client;
    private final ICloudDownloadRetryClient retryClient;

    public ICloudSyncProvider(ICloudServiceClient client, ICloudDownloadRetryClient retryClient) {
        this.client = client;
        this.retryClient = retryClient;
    }

    @Override
    public String providerType() {
        return "ICLOUD";
    }

    @Override
    public void prefetch(String sessionId) {
        client.prefetchPhotos(sessionId);
    }

    @Override
    public PrefetchStatus getPrefetchStatus(String sessionId) {
        HttpResponse<ICloudPrefetchStatus> resp = client.getPrefetchStatus(sessionId);
        ICloudPrefetchStatus s = resp.body();
        if (s == null) return null;
        return new PrefetchStatus(s.status(), s.fetched(), s.total());
    }

    @Override
    public List<PhotoAsset> listAllPhotos(String sessionId) {
        List<PhotoAsset> all = new ArrayList<>();
        int offset = 0;
        while (true) {
            HttpResponse<ICloudPhotoListResponse> resp = client.listPhotos(sessionId, PAGE_SIZE, offset);
            ICloudPhotoListResponse page = resp.body();
            List<ICloudPhotoAsset> batch = (page != null && page.photos() != null) ? page.photos() : List.of();
            batch.stream()
                    .map(a -> new PhotoAsset(a.id(), a.filename(), a.size(), a.createdDate(), a.width(), a.height(), a.assetToken()))
                    .forEach(all::add);
            if (batch.size() < PAGE_SIZE) break;
            offset += PAGE_SIZE;
        }
        return all;
    }

    @Override
    public byte[] downloadPhoto(String photoId, String sessionId) throws IOException {
        Exception lastException = null;
        for (int attempt = 1; attempt <= MAX_DOWNLOAD_ATTEMPTS; attempt++) {
            try {
                HttpResponse<byte[]> response = attempt == 1
                        ? client.downloadPhoto(photoId, sessionId)
                        : retryClient.downloadPhoto(photoId, sessionId);
                byte[] data = response.body();
                if (data == null) throw new IOException("Empty response for photo " + photoId);
                if (attempt > 1) {
                    LOG.info("Download succeeded on attempt {} for photo {}", attempt, photoId);
                }
                return data;
            } catch (Exception e) {
                lastException = e;
                LOG.warn("Download attempt {}/{} failed for photo {}: {}", attempt, MAX_DOWNLOAD_ATTEMPTS, photoId, e.getMessage());
            }
        }
        throw new IOException("Download failed after " + MAX_DOWNLOAD_ATTEMPTS + " attempts for photo " + photoId, lastException);
    }

    @Override
    public void deletePhoto(String photoId, String sessionId) {
        client.deletePhoto(photoId, sessionId);
    }
}
