package com.cloudsync.service;

import com.cloudsync.exception.SyncFolderNotConfiguredException;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Sync path must use {@code account.getSyncFolderPath()} as the only source of truth for
 * the physical destination folder — NOT the global {@code app_context.basePath()}.
 *
 * <ul>
 *   <li>{@code startSync}/{@code confirmSync} fail fast with {@link SyncFolderNotConfiguredException}
 *       when the account has no sync folder configured.</li>
 *   <li>Downloaded photos are written under the account's own sync folder.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SyncServiceSyncFolderPathTest {

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

    private ICloudAccount account(String id, String syncFolderPath) {
        ICloudAccount a = new ICloudAccount();
        a.setId(id);
        a.setSyncFolderPath(syncFolderPath);
        a.setSessionId("session-" + id);
        return a;
    }

    // ── fail-fast guards ────────────────────────────────────────────────────────

    @Test
    void startSync_nullSyncFolderPath_throwsConfiguredException() {
        ICloudAccount account = account("acc-a", null);
        when(accountRepository.findById("acc-a")).thenReturn(Optional.of(account));

        assertThrows(SyncFolderNotConfiguredException.class,
                () -> service.startSync("acc-a", "ICLOUD"));
    }

    @Test
    void confirmSync_nullSyncFolderPath_throwsConfiguredException() {
        ICloudAccount account = account("acc-a", null);
        when(accountRepository.findById("acc-a")).thenReturn(Optional.of(account));

        assertThrows(SyncFolderNotConfiguredException.class,
                () -> service.confirmSync("acc-a"));
    }

    // ── download destination ────────────────────────────────────────────────────

    @Test
    void confirmSync_writesDownloadedPhotoUnderAccountSyncFolder(@TempDir Path deviceRoot) throws Exception {
        Path accountFolder = deviceRoot.resolve("folder-a");
        Files.createDirectories(accountFolder);

        ICloudAccount account = account("acc-a", accountFolder.toString());

        Photo photo = new Photo();
        photo.setId("p1");
        photo.setAccountId("acc-a");
        photo.setSourceProvider("ICLOUD");
        photo.setIcloudPhotoId("icloud-1");
        photo.setFilename("IMG_1.jpg");
        photo.setSyncStatus(SyncStatus.PENDING.name());
        photo.setCreatedDate(Instant.parse("2023-06-15T12:00:00Z"));

        when(accountRepository.findById("acc-a")).thenReturn(Optional.of(account));
        when(photoRepository.findByAccountIdAndSyncStatus("acc-a", SyncStatus.PENDING.name()))
                .thenReturn(List.of(photo));
        when(photoRepository.findByAccountIdAndSyncStatus("acc-a", SyncStatus.FAILED.name()))
                .thenReturn(List.of());
        lenient().when(photoRepository.findByAccountIdAndSyncedToDisk(eq("acc-a"), eq(true)))
                .thenReturn(List.of());
        when(iCloudProvider.downloadPhoto(eq("icloud-1"), any()))
                .thenReturn(new byte[]{1, 2, 3, 4});

        service.confirmSync("acc-a");

        Path expected = accountFolder.resolve("2023").resolve("06").resolve("IMG_1.jpg");
        awaitFileExists(expected, Duration.ofSeconds(5));

        assertTrue(Files.exists(expected),
                "Downloaded photo must be written under <accountSyncFolder>/2023/06/, got filePath=" + photo.getFilePath());
    }

    private void awaitFileExists(Path path, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (Files.exists(path)) return;
            Thread.sleep(25);
        }
    }
}
