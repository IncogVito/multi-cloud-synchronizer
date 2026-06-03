package com.cloudsync.provider;

import com.cloudsync.client.ICloudDeleteClient;
import com.cloudsync.client.ICloudDownloadRetryClient;
import com.cloudsync.client.ICloudLargeDownloadClient;
import com.cloudsync.client.ICloudServiceClient;
import com.cloudsync.model.dto.ICloudBatchDeleteRequest;
import com.cloudsync.model.dto.ICloudBatchDeleteResponse;
import com.cloudsync.model.dto.ICloudBatchDeleteResult;
import com.cloudsync.model.dto.ICloudPhotoAsset;
import com.cloudsync.model.dto.ICloudPhotoDeleteItem;
import com.cloudsync.model.dto.ICloudPhotoListResponse;
import com.cloudsync.model.dto.ICloudPrefetchStatus;
import com.cloudsync.model.dto.PhotoAsset;
import com.cloudsync.model.dto.PhotoDeleteItem;
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
    private final ICloudDeleteClient deleteClient;
    private final ICloudLargeDownloadClient largeClient;

    public ICloudSyncProvider(ICloudServiceClient client, ICloudDownloadRetryClient retryClient,
                               ICloudDeleteClient deleteClient, ICloudLargeDownloadClient largeClient) {
        this.client = client;
        this.retryClient = retryClient;
        this.deleteClient = deleteClient;
        this.largeClient = largeClient;
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
                    .map(a -> new PhotoAsset(a.id(), a.filename(), a.size(), a.createdDate(), a.width(), a.height(), a.assetToken(), a.assetRecordName()))
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
    public byte[] downloadLargePhoto(String photoId, String sessionId) throws IOException {
        Exception lastException = null;
        for (int attempt = 1; attempt <= MAX_DOWNLOAD_ATTEMPTS; attempt++) {
            try {
                HttpResponse<byte[]> response = largeClient.downloadPhoto(photoId, sessionId);
                byte[] data = response.body();
                if (data == null) throw new IOException("Empty response for photo " + photoId);
                if (attempt > 1) {
                    LOG.info("Large download succeeded on attempt {} for photo {}", attempt, photoId);
                }
                return data;
            } catch (Exception e) {
                lastException = e;
                LOG.warn("Large download attempt {}/{} failed for photo {}: {}", attempt, MAX_DOWNLOAD_ATTEMPTS, photoId, e.getMessage());
            }
        }
        throw new IOException("Large download failed after " + MAX_DOWNLOAD_ATTEMPTS + " attempts for photo " + photoId, lastException);
    }

    @Override
    public void deletePhoto(String photoId, String sessionId) {
        client.deletePhoto(photoId, sessionId);
    }

    @Override
    public List<ICloudBatchDeleteResult> batchDeletePhotos(List<PhotoDeleteItem> items, String sessionId) {
        try {
            List<ICloudPhotoDeleteItem> payload = items.stream()
                    .map(i -> new ICloudPhotoDeleteItem(i.photoId(), i.assetRecordName()))
                    .toList();
            ICloudBatchDeleteResponse body = deleteClient.batchDeletePhotos(
                    new ICloudBatchDeleteRequest(payload), sessionId).body();
            if (body == null || body.results() == null) {
                LOG.warn("Empty batch-delete response for {} photos", items.size());
                return items.stream().map(i -> new ICloudBatchDeleteResult(i.photoId(), false, "empty response")).toList();
            }
            return body.results();
        } catch (Exception e) {
            LOG.warn("Batch delete failed: {}", e.getMessage());
            return items.stream().map(i -> new ICloudBatchDeleteResult(i.photoId(), false, e.getMessage())).toList();
        }
    }
}
