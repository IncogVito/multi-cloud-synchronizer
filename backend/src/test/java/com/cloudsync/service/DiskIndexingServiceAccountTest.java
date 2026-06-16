package com.cloudsync.service;

import com.cloudsync.model.entity.ICloudAccount;
import com.cloudsync.model.entity.Photo;
import com.cloudsync.repository.AccountRepository;
import com.cloudsync.repository.PhotoRepository;
import com.cloudsync.repository.StorageDeviceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Account-scoped disk indexing (issue #8): {@code startIndexing(accountId)} must scan only
 * {@code account.getSyncFolderPath()} — NOT the global device base path — and persist the
 * discovered media tagged with that {@code accountId} and the account's {@code storageDeviceId}.
 */
@ExtendWith(MockitoExtension.class)
class DiskIndexingServiceAccountTest {

    @Mock PhotoRepository photoRepository;
    @Mock AccountRepository accountRepository;
    @Mock DiskIndexStateHolder stateHolder;
    @Mock StorageDeviceRepository storageDeviceRepository;

    DiskIndexingService service;

    @BeforeEach
    void setUp() {
        service = new DiskIndexingService(
                photoRepository,
                accountRepository,
                stateHolder,
                Executors.newVirtualThreadPerTaskExecutor(),
                storageDeviceRepository
        );
    }

    private ICloudAccount account(String id, String syncFolderPath, String storageDeviceId) {
        ICloudAccount a = new ICloudAccount();
        a.setId(id);
        a.setSyncFolderPath(syncFolderPath);
        a.setStorageDeviceId(storageDeviceId);
        return a;
    }

    private void awaitDone(String accountId) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (service.isRunning(accountId) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertFalse(service.isRunning(accountId), "indexing did not finish within timeout");
    }

    @Test
    void startIndexing_scansAccountSyncFolder_andTagsPhotosWithAccount(@TempDir Path deviceRoot) throws Exception {
        Path accountFolder = deviceRoot.resolve("folder-a");
        Files.createDirectories(accountFolder);
        Files.createFile(accountFolder.resolve("photo.jpg"));
        Files.createFile(accountFolder.resolve("clip.mp4"));
        Files.createFile(accountFolder.resolve("notes.txt")); // non-media, must be ignored

        ICloudAccount accountA = account("acc-a", accountFolder.toString(), "dev-1");

        when(accountRepository.findById("acc-a")).thenReturn(Optional.of(accountA));
        when(photoRepository.findByAccountIdAndSyncedToDisk("acc-a", true)).thenReturn(List.of());
        when(photoRepository.findAllWithFilePathByAccountId("acc-a")).thenReturn(List.of());
        lenient().when(storageDeviceRepository.findById(any())).thenReturn(Optional.empty());

        service.startIndexing("acc-a");
        awaitDone("acc-a");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Photo>> captor = ArgumentCaptor.forClass(List.class);
        verify(photoRepository, atLeastOnce()).saveAll(captor.capture());

        List<Photo> saved = captor.getAllValues().stream().flatMap(List::stream).toList();
        assertEquals(2, saved.size(), "only the 2 media files must be indexed, not the .txt");
        assertTrue(saved.stream().allMatch(p -> "acc-a".equals(p.getAccountId())),
                "indexed photos must be tagged with the accountId");
        assertTrue(saved.stream().allMatch(p -> "dev-1".equals(p.getStorageDeviceId())),
                "indexed photos must carry the account's storageDeviceId");
    }

    @Test
    void isRunning_isPerAccount_unknownAccountNotRunning() {
        assertFalse(service.isRunning("nobody"));
    }
}
