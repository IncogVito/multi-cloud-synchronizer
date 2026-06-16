package com.cloudsync.service;

import com.cloudsync.model.entity.ICloudAccount;
import com.cloudsync.model.entity.Photo;
import com.cloudsync.model.enums.SyncStatus;
import com.cloudsync.provider.PhotoSyncProvider;
import com.cloudsync.repository.AccountRepository;
import com.cloudsync.repository.PhotoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/**
 * Tests that reorganizePreview and reorganize use account.getSyncFolderPath()
 * as the base path, NOT the global ctx.basePath().
 *
 * Bug: photos belonging to account A (stored under device/folder-a/2023/06/photo.jpg)
 * were reported as "unorganized" when viewed from account B (syncFolderPath = device/folder-b/)
 * because the relativization was done against the device root instead of the account folder.
 */
@ExtendWith(MockitoExtension.class)
class SyncServiceReorganizeTest {

    @Mock PhotoRepository photoRepository;
    @Mock AccountRepository accountRepository;
    @Mock ThumbnailService thumbnailService;
    @Mock ThumbnailJobService thumbnailJobService;
    @Mock SyncStateHolder syncStateHolder;
    @Mock AppContextService appContextService;
    @Mock TaskHistoryService taskHistoryService;
    @Mock PhotoSyncProvider iCloudProvider;
    @Mock PhotoSyncProvider iPhoneProvider;

    SyncService service;

    @BeforeEach
    void setUp() {
        when(iCloudProvider.providerType()).thenReturn("ICLOUD");
        when(iPhoneProvider.providerType()).thenReturn("IPHONE");

        service = new SyncService(
                photoRepository, accountRepository,
                thumbnailService, thumbnailJobService,
                syncStateHolder, appContextService,
                taskHistoryService,
                Executors.newVirtualThreadPerTaskExecutor(),
                iCloudProvider, iPhoneProvider,
                0
        );
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ICloudAccount account(String id, String syncFolderPath) {
        ICloudAccount a = new ICloudAccount();
        a.setId(id);
        a.setSyncFolderPath(syncFolderPath);
        a.setSessionId("session-" + id);
        return a;
    }

    private Photo syncedPhoto(String id, String filePath) {
        Photo p = new Photo();
        p.setId(id);
        p.setSyncedToDisk(true);
        p.setSyncStatus(SyncStatus.SYNCED.name());
        p.setFilePath(filePath);
        p.setCreatedDate(Instant.parse("2023-06-15T12:00:00Z"));
        return p;
    }

    // ── reorganizePreview ─────────────────────────────────────────────────────

    /**
     * A photo stored at <accountSyncFolder>/2023/06/photo.jpg is correctly organized.
     * reorganizePreview must report 0 unorganized photos when the base path is the
     * account's syncFolderPath, NOT the device root.
     *
     * Before the fix, ctx.basePath() (device root) was used as base, so the relative
     * path became "folder-a/2023/06" which does NOT match \d{4}/\d{2} — wrongly counted
     * as unorganized.
     */
    @Test
    void reorganizePreview_organizedPhotoUnderAccountFolder_reportsZeroUnorganized(
            @TempDir Path deviceRoot) throws Exception {

        // Account A has its own subfolder on the device
        Path accountFolder = deviceRoot.resolve("folder-a");
        Path organizedDir = accountFolder.resolve("2023").resolve("06");
        Files.createDirectories(organizedDir);
        Path photoFile = Files.createFile(organizedDir.resolve("photo.HEIC"));

        ICloudAccount accountA = account("acc-a", accountFolder.toString());
        Photo photo = syncedPhoto("p1", photoFile.toString());

        when(accountRepository.findById("acc-a")).thenReturn(Optional.of(accountA));
        when(photoRepository.findByAccountIdAndSyncedToDisk("acc-a", true)).thenReturn(List.of(photo));

        Map<String, Object> result = service.reorganizePreview("acc-a");

        assertEquals(0, result.get("unorganizedCount"),
                "A photo under <accountFolder>/2023/06/ must not be reported as unorganized");
    }

    /**
     * A photo stored at the account's syncFolderPath root (not in a year/month subdirectory)
     * IS unorganized and must be reported.
     */
    @Test
    void reorganizePreview_photoAtSyncFolderRoot_reportsAsUnorganized(
            @TempDir Path deviceRoot) throws Exception {

        Path accountFolder = deviceRoot.resolve("folder-a");
        Files.createDirectories(accountFolder);
        Path photoFile = Files.createFile(accountFolder.resolve("photo.HEIC"));

        ICloudAccount accountA = account("acc-a", accountFolder.toString());
        Photo photo = syncedPhoto("p1", photoFile.toString());

        when(accountRepository.findById("acc-a")).thenReturn(Optional.of(accountA));
        when(photoRepository.findByAccountIdAndSyncedToDisk("acc-a", true)).thenReturn(List.of(photo));

        Map<String, Object> result = service.reorganizePreview("acc-a");

        assertEquals(1, result.get("unorganizedCount"),
                "A photo directly under <accountFolder>/ (not in year/month) must be reported as unorganized");
    }

    /**
     * Cross-account isolation: account B's reorganizePreview must not misclassify
     * its own organized photos even though ctx.basePath() (device root) differs
     * from account.getSyncFolderPath().
     *
     * Two accounts on the same device, each with an organized photo.
     * When querying account B, its photo under folder-b/2023/06/ must be organized.
     */
    @Test
    void reorganizePreview_accountBDoesNotMisclassifyItsOwnOrganizedPhoto(
            @TempDir Path deviceRoot) throws Exception {

        Path folderB = deviceRoot.resolve("folder-b");
        Path organizedDirB = folderB.resolve("2023").resolve("06");
        Files.createDirectories(organizedDirB);
        Path photoB = Files.createFile(organizedDirB.resolve("photo_b.HEIC"));

        ICloudAccount accountB = account("acc-b", folderB.toString());
        Photo photo = syncedPhoto("p-b", photoB.toString());

        when(accountRepository.findById("acc-b")).thenReturn(Optional.of(accountB));
        when(photoRepository.findByAccountIdAndSyncedToDisk("acc-b", true)).thenReturn(List.of(photo));

        Map<String, Object> result = service.reorganizePreview("acc-b");

        assertEquals(0, result.get("unorganizedCount"),
                "Account B's photo under <folderB>/2023/06/ must not be reported as unorganized " +
                "even when ctx.basePath() is the device root");
    }

    // ── reorganize ────────────────────────────────────────────────────────────

    /**
     * reorganize must move a photo from the account folder root into
     * <accountSyncFolder>/2023/06/ and update the DB record.
     * The destination path must be relative to the account's syncFolderPath,
     * not to the global device root.
     */
    @Test
    void reorganize_movesUnorganizedPhotoIntoAccountSubfolder(
            @TempDir Path deviceRoot) throws Exception {

        Path accountFolder = deviceRoot.resolve("folder-a");
        Files.createDirectories(accountFolder);
        Path photoFile = Files.createFile(accountFolder.resolve("photo.HEIC"));

        ICloudAccount accountA = account("acc-a", accountFolder.toString());
        Photo photo = syncedPhoto("p1", photoFile.toString());
        photo.setCreatedDate(Instant.parse("2023-06-15T12:00:00Z"));

        when(accountRepository.findById("acc-a")).thenReturn(Optional.of(accountA));
        when(photoRepository.findByAccountIdAndSyncedToDisk("acc-a", true)).thenReturn(List.of(photo));

        Map<String, Object> result = service.reorganize("acc-a");

        assertEquals(1, result.get("moved"));
        assertEquals(0, result.get("errors"));

        // The file must now live under the ACCOUNT folder, not device root
        Path expectedDest = accountFolder.resolve("2023").resolve("06").resolve("photo.HEIC");
        assertEquals(expectedDest.toString(), photo.getFilePath(),
                "Photo must be moved into <accountFolder>/2023/06/, not <deviceRoot>/2023/06/");
    }
}
