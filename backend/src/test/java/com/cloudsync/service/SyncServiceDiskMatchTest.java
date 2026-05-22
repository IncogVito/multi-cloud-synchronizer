package com.cloudsync.service;

import com.cloudsync.model.dto.PhotoAsset;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncServiceDiskMatchTest {

    private static PhotoAsset asset(String filename, Long size) {
        return new PhotoAsset("id-" + filename, filename, size, null, null, null, null, null);
    }

    private static Map<String, Long> disk(String... pairs) {
        var m = new java.util.HashMap<String, Long>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put(pairs[i].toLowerCase(Locale.ROOT), Long.parseLong(pairs[i + 1]));
        }
        return m;
    }

    @Test
    void exactMatchByNameAndSize_returnsTrue() {
        var diskFiles = disk("IMG_1234.HEIC", "1000");
        assertTrue(SyncService.isAlreadyOnDisk(diskFiles, asset("IMG_1234.HEIC", 1000L)));
    }

    @Test
    void caseInsensitiveMatch_returnsTrue() {
        var diskFiles = disk("img_1234.heic", "1000");
        assertTrue(SyncService.isAlreadyOnDisk(diskFiles, asset("IMG_1234.HEIC", 1000L)));
    }

    @Test
    void sizeMismatch_returnsFalse() {
        var diskFiles = disk("IMG_1234.HEIC", "1000");
        assertFalse(SyncService.isAlreadyOnDisk(diskFiles, asset("IMG_1234.HEIC", 999L)));
    }

    @Test
    void videoSuffixAssetMatchesLegacyUnsuffixedDiskFile() {
        // Disk holds legacy IMG_1234.MOV (downloaded before _VIDEO scheme).
        // New scan produces IMG_1234_VIDEO.MOV with same size — should not re-download.
        var diskFiles = disk("IMG_1234.MOV", "5000");
        assertTrue(SyncService.isAlreadyOnDisk(diskFiles, asset("IMG_1234_VIDEO.MOV", 5000L)));
    }

    @Test
    void videoSuffixAssetSizeMismatchAgainstLegacy_returnsFalse() {
        var diskFiles = disk("IMG_1234.MOV", "5000");
        assertFalse(SyncService.isAlreadyOnDisk(diskFiles, asset("IMG_1234_VIDEO.MOV", 9999L)));
    }

    @Test
    void heicAndMovPair_onlyMovMatchesLegacyDisk() {
        // Live Photo pair: disk has only the MOV; HEIC should still need download.
        var diskFiles = disk("IMG_1234.MOV", "5000");
        assertTrue(SyncService.isAlreadyOnDisk(diskFiles, asset("IMG_1234_VIDEO.MOV", 5000L)));
        assertFalse(SyncService.isAlreadyOnDisk(diskFiles, asset("IMG_1234.HEIC", 1000L)));
    }

    @Test
    void nullSize_returnsFalse() {
        var diskFiles = disk("IMG_1234.HEIC", "1000");
        assertFalse(SyncService.isAlreadyOnDisk(diskFiles, asset("IMG_1234.HEIC", null)));
    }

    @Test
    void emptyDisk_returnsFalse() {
        assertFalse(SyncService.isAlreadyOnDisk(Map.of(), asset("IMG_1234.HEIC", 1000L)));
    }

    @Test
    void stripVideoSuffix_removesSuffixPreservingExt() {
        assertEquals("IMG_1234.MOV", SyncService.stripVideoSuffix("IMG_1234_VIDEO.MOV"));
        assertEquals("IMG_1234.mov", SyncService.stripVideoSuffix("IMG_1234_VIDEO.mov"));
    }

    @Test
    void stripVideoSuffix_returnsNullWhenNoSuffix() {
        assertNull(SyncService.stripVideoSuffix("IMG_1234.MOV"));
    }

    @Test
    void stripVideoSuffix_returnsNullWhenNoExtension() {
        assertNull(SyncService.stripVideoSuffix("IMG_1234_VIDEO"));
    }
}
