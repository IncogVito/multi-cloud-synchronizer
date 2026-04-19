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

import java.time.Instant;
import java.util.List;
import java.util.Objects;

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

    public StatsResponse getStats(String storageDeviceId) {
        StorageDevice device = storageDeviceRepository.findById(storageDeviceId).orElse(null);
        Long diskCapacity = device != null ? device.getSizeBytes() : null;

        long diskCount = photoRepository.countBySyncedToDiskAndStorageDeviceId(true, storageDeviceId);
        Long diskSize = photoRepository.sumFileSizeOnDisk(storageDeviceId);

        long icloudCount = photoRepository.countByStorageDeviceIdAndExistsOnIcloud(storageDeviceId);
        Long icloudSizeVal = photoRepository.sumFileSizeOnIcloudByDevice(storageDeviceId);
        long icloudSize = icloudSizeVal != null ? icloudSizeVal : 0;

        long iphoneCount = photoRepository.countByStorageDeviceIdAndExistsOnIphone(storageDeviceId);
        Long iphoneSizeVal = photoRepository.sumFileSizeOnIphoneByDevice(storageDeviceId);
        long iphoneSize = iphoneSizeVal != null ? iphoneSizeVal : 0;

        List<ICloudAccount> accounts = accountRepository.findByStorageDeviceId(storageDeviceId);

        Instant icloudLastSync = accounts.stream()
                .map(ICloudAccount::getLastSyncAt)
                .filter(Objects::nonNull)
                .max(Instant::compareTo)
                .orElse(null);

        Instant iphoneLastSync = icloudLastSync;

        Instant diskLastSync = icloudLastSync;

        return new StatsResponse(
                diskCount,
                diskSize,
                diskCapacity,
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
