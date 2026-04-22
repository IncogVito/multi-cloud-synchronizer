package com.cloudsync.provider;

import com.cloudsync.model.dto.ICloudBatchDeleteResult;
import com.cloudsync.model.dto.PhotoAsset;
import com.cloudsync.model.dto.PrefetchStatus;

import java.io.IOException;
import java.util.List;

/**
 * Abstraction over a remote photo source (iCloud, iPhone, …).
 * All implementations are registered as named singletons: @Named("ICLOUD"), @Named("IPHONE"), etc.
 */
public interface PhotoSyncProvider {

    /** Short identifier matching the {@code source_provider} column in the photos table. */
    String providerType();

    /**
     * Trigger background prefetch of photo metadata on the remote service.
     * Returns immediately; poll {@link #getPrefetchStatus} until {@code "ready"} or {@code "error"}.
     */
    void prefetch(String sessionId);

    /** Poll the prefetch job started by {@link #prefetch}. Returns {@code null} when not available yet. */
    PrefetchStatus getPrefetchStatus(String sessionId);

    /** Fetch the full photo list (all pages) once the prefetch is ready. */
    List<PhotoAsset> listAllPhotos(String sessionId);

    /** Download the raw bytes of a single photo. */
    byte[] downloadPhoto(String photoId, String sessionId) throws IOException;

    /** Permanently delete a photo from the remote source. */
    void deletePhoto(String photoId, String sessionId);

    /** Batch delete photos. Returns per-photo results. Default: sequential single deletes. */
    default List<ICloudBatchDeleteResult> batchDeletePhotos(List<String> photoIds, String sessionId) {
        return photoIds.stream().map(id -> {
            try {
                deletePhoto(id, sessionId);
                return new ICloudBatchDeleteResult(id, true, null);
            } catch (Exception e) {
                return new ICloudBatchDeleteResult(id, false, e.getMessage());
            }
        }).toList();
    }
}
