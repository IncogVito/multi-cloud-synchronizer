package com.cloudsync.provider;

import com.cloudsync.client.ICloudServiceClient;
import com.cloudsync.model.dto.ICloudPhotoAsset;
import com.cloudsync.model.dto.ICloudPhotoListResponse;
import com.cloudsync.model.dto.ICloudPrefetchStatus;
import com.cloudsync.model.dto.PhotoAsset;
import com.cloudsync.model.dto.PrefetchStatus;
import io.micronaut.http.HttpResponse;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Singleton
@Named("ICLOUD")
public class ICloudSyncProvider implements PhotoSyncProvider {

    private static final int PAGE_SIZE = 200;

    private final ICloudServiceClient client;

    public ICloudSyncProvider(ICloudServiceClient client) {
        this.client = client;
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
        HttpResponse<byte[]> response = client.downloadPhoto(photoId, sessionId);
        byte[] data = response.body();
        if (data == null) throw new IOException("Empty response for photo " + photoId);
        return data;
    }

    @Override
    public void deletePhoto(String photoId, String sessionId) {
        client.deletePhoto(photoId, sessionId);
    }
}
