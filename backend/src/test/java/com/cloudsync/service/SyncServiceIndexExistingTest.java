package com.cloudsync.service;

import com.cloudsync.model.entity.Photo;
import com.cloudsync.model.enums.SyncStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class SyncServiceIndexExistingTest {

    private static Photo photo(String id, String icloudPhotoId, String sourceProvider,
                               boolean syncedToDisk, String syncStatus) {
        Photo p = new Photo();
        p.setId(id);
        p.setIcloudPhotoId(icloudPhotoId);
        p.setSourceProvider(sourceProvider);
        p.setSyncedToDisk(syncedToDisk);
        p.setSyncStatus(syncStatus);
        return p;
    }

    @Test
    void duplicateIcloudPhotoId_doesNotThrow() {
        // Reproduces: "Duplicate key … (attempted merging values Photo@… and Photo@…)"
        Photo a = photo("row-a", "EXT-1", "ICLOUD", false, SyncStatus.PENDING.name());
        Photo b = photo("row-b", "EXT-1", "ICLOUD", false, SyncStatus.PENDING.name());

        Map<String, Photo> result = assertDoesNotThrow(
                () -> SyncService.indexExistingByExternalId(List.of(a, b), "ICLOUD"));

        assertEquals(1, result.size());
    }

    @Test
    void duplicate_prefersSyncedToDiskRow() {
        Photo pending = photo("row-pending", "EXT-1", "ICLOUD", false, SyncStatus.PENDING.name());
        Photo onDisk = photo("row-on-disk", "EXT-1", "ICLOUD", true, SyncStatus.SYNCED.name());

        Map<String, Photo> result = SyncService.indexExistingByExternalId(List.of(pending, onDisk), "ICLOUD");

        assertSame(onDisk, result.get("EXT-1"));
    }

    @Test
    void includesNullSourceProviderRows() {
        Photo legacy = photo("row-legacy", "EXT-9", null, false, SyncStatus.PENDING.name());

        Map<String, Photo> result = SyncService.indexExistingByExternalId(List.of(legacy), "ICLOUD");

        assertSame(legacy, result.get("EXT-9"));
    }
}
