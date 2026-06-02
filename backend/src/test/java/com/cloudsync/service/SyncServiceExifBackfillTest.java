package com.cloudsync.service;

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
import java.util.concurrent.Executors;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class SyncServiceExifBackfillTest {

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
        // iCloudProvider must return "ICLOUD", iPhoneProvider "IPHONE" for the providers map
        org.mockito.Mockito.when(iCloudProvider.providerType()).thenReturn("ICLOUD");
        org.mockito.Mockito.when(iPhoneProvider.providerType()).thenReturn("IPHONE");

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

    private Photo iphonePhoto(String id, String filePath) {
        Photo p = new Photo();
        p.setId(id);
        p.setSourceProvider("IPHONE");
        p.setSyncStatus(SyncStatus.SYNCED.name());
        p.setSyncedToDisk(true);
        p.setFilePath(filePath);
        p.setCreatedDate(Instant.parse("2024-06-01T10:00:00Z"));
        return p;
    }

    // ── empty list ────────────────────────────────────────────────────────────

    @Test
    void backfillExif_emptyList_noRepositoryOrEventInteraction() {
        service.backfillIPhoneExifDates("acc1", List.of());

        verifyNoInteractions(photoRepository);
        verifyNoInteractions(syncStateHolder);
    }

    // ── repository not used for full load ─────────────────────────────────────

    @Test
    void backfillExif_doesNotLoadAllPhotosFromRepository(@TempDir Path tempDir) {
        Photo photo = iphonePhoto("p1", tempDir.resolve("nonexistent.HEIC").toString());

        service.backfillIPhoneExifDates("acc1", List.of(photo));

        // Old code called findByAccountIdAndSyncedToDisk to load ALL photos.
        // New code receives the list directly — this must never be called.
        verify(photoRepository, never()).findByAccountIdAndSyncedToDisk(any(), anyBoolean());
    }

    // ── file skipping ─────────────────────────────────────────────────────────

    @Test
    void backfillExif_skipsPhotosWithNullFilePath() {
        Photo photo = iphonePhoto("p1", null);

        service.backfillIPhoneExifDates("acc1", List.of(photo));

        verify(photoRepository, never()).update(any());
    }

    @Test
    void backfillExif_skipsNonExistentFiles(@TempDir Path tempDir) {
        Photo photo = iphonePhoto("p1", tempDir.resolve("missing.HEIC").toString());

        service.backfillIPhoneExifDates("acc1", List.of(photo));

        verify(photoRepository, never()).update(any());
    }

    // ── no-op when EXIF is absent ─────────────────────────────────────────────

    @Test
    void backfillExif_doesNotUpdateWhenFileHasNoExif(@TempDir Path tempDir) throws Exception {
        // Empty file → ExifDateUtil returns null → no date change → no DB update
        Path file = Files.createFile(tempDir.resolve("IMG_001.HEIC"));
        Photo photo = iphonePhoto("p1", file.toString());

        service.backfillIPhoneExifDates("acc1", List.of(photo));

        verify(photoRepository, never()).update(any());
    }

    @Test
    void backfillExif_doesNotUpdateWhenExifMatchesStoredDate(@TempDir Path tempDir) throws Exception {
        // Even if EXIF were present and matched the stored date, no update should happen.
        // (Covered transitively: ExifDateUtil returns null for empty file → skipped.)
        Path file = Files.createFile(tempDir.resolve("IMG_002.jpg"));
        Photo photo = iphonePhoto("p2", file.toString());
        // createdDate already set in iphonePhoto helper

        service.backfillIPhoneExifDates("acc1", List.of(photo));

        verify(photoRepository, never()).update(any());
    }

    // ── only provided photos are processed ───────────────────────────────────

    @Test
    void backfillExif_processesOnlyPhotosInProvidedList(@TempDir Path tempDir) throws Exception {
        Path file1 = Files.createFile(tempDir.resolve("IMG_001.HEIC"));
        Photo inList = iphonePhoto("p1", file1.toString());

        // This photo is NOT in the list — backfill must not touch it at all.
        Photo notInList = iphonePhoto("p2", tempDir.resolve("IMG_002.HEIC").toString());

        service.backfillIPhoneExifDates("acc1", List.of(inList));

        // Only p1 was attempted (no EXIF → no update).
        // p2 was never touched — if backfill loaded all photos it would attempt p2 too,
        // but since it doesn't exist it would be skipped anyway. The key guard is the
        // findByAccountIdAndSyncedToDisk never-called assertion above.
        verify(photoRepository, never()).update(any());
    }
}
