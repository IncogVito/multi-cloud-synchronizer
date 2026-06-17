package com.cloudsync.service;

import com.cloudsync.model.dto.StatsResponse;
import com.cloudsync.model.entity.ICloudAccount;
import com.cloudsync.model.entity.StorageDevice;
import com.cloudsync.repository.AccountRepository;
import com.cloudsync.repository.PhotoRepository;
import com.cloudsync.repository.StorageDeviceRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

@Singleton
public class StatsService {

    private static final Logger LOG = LoggerFactory.getLogger(StatsService.class);

    private final PhotoRepository photoRepository;
    private final AccountRepository accountRepository;
    private final StorageDeviceRepository storageDeviceRepository;

    public StatsService(PhotoRepository photoRepository,
                        AccountRepository accountRepository,
                        StorageDeviceRepository storageDeviceRepository) {
        this.photoRepository = photoRepository;
        this.accountRepository = accountRepository;
        this.storageDeviceRepository = storageDeviceRepository;
    }

    public StatsResponse getStats(String accountId) {
        ICloudAccount account = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown account: " + accountId));
        String storageDeviceId = account.getStorageDeviceId();

        StorageDevice device = storageDeviceId != null
                ? storageDeviceRepository.findById(storageDeviceId).orElse(null)
                : null;
        Long diskCapacity = device != null ? device.getSizeBytes() : null;

        Long diskFreeBytes = null;
        String syncFolderPath = account.getSyncFolderPath();
        if (syncFolderPath != null && !syncFolderPath.isBlank()) {
            try {
                diskFreeBytes = Files.getFileStore(Path.of(syncFolderPath)).getUsableSpace();
            } catch (IOException e) {
                LOG.warn("Could not read free disk space for {}: {}", syncFolderPath, e.getMessage());
            }
        }

        long diskCount = photoRepository.countBySyncedToDiskAndAccountId(true, accountId);
        Long diskSize = photoRepository.sumFileSizeOnDiskByAccount(accountId);

        long icloudCount = photoRepository.countByAccountIdAndExistsOnIcloud(accountId, true);
        Long icloudSizeVal = photoRepository.sumFileSizeOnIcloud(accountId);
        long icloudSize = icloudSizeVal != null ? icloudSizeVal : 0;

        long iphoneCount = photoRepository.countByAccountIdAndExistsOnIphone(accountId, true);
        Long iphoneSizeVal = photoRepository.sumFileSizeOnIphone(accountId);
        long iphoneSize = iphoneSizeVal != null ? iphoneSizeVal : 0;

        Instant icloudLastSync = account.getLastSyncAt();

        Instant iphoneLastSync = icloudLastSync;

        Instant diskLastSync = device != null ? device.getLastIndexedAt() : null;

        return new StatsResponse(
                diskCount,
                diskSize,
                diskCapacity,
                diskFreeBytes,
                diskLastSync,
                icloudCount,
                icloudSize == 0 ? null : icloudSize,
                icloudLastSync,
                iphoneCount,
                iphoneSize == 0 ? null : iphoneSize,
                iphoneLastSync
        );
    }
}
