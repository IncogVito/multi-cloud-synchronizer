package com.cloudsync.service;

import com.cloudsync.model.dto.StatsResponse;
import com.cloudsync.model.entity.ICloudAccount;
import com.cloudsync.model.entity.StorageDevice;
import com.cloudsync.repository.AccountRepository;
import com.cloudsync.repository.PhotoRepository;
import com.cloudsync.repository.StorageDeviceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StatsServiceTest {

    @Mock PhotoRepository photoRepository;
    @Mock AccountRepository accountRepository;
    @Mock StorageDeviceRepository storageDeviceRepository;
    @Mock AppContextService appContextService;

    StatsService service;

    @BeforeEach
    void setUp() {
        service = new StatsService(photoRepository, accountRepository, storageDeviceRepository, appContextService);
    }

    private ICloudAccount account(String id, String deviceId) {
        ICloudAccount a = new ICloudAccount();
        a.setId(id);
        a.setStorageDeviceId(deviceId);
        a.setLastSyncAt(Instant.parse("2024-06-01T10:00:00Z"));
        return a;
    }

    @Test
    void getStats_twoAccountsSameDevice_returnIndependentCounts() {
        String deviceId = "dev-1";
        when(accountRepository.findById("acc-A")).thenReturn(Optional.of(account("acc-A", deviceId)));
        when(accountRepository.findById("acc-B")).thenReturn(Optional.of(account("acc-B", deviceId)));

        StorageDevice device = new StorageDevice();
        device.setId(deviceId);
        device.setSizeBytes(1_000L);
        when(storageDeviceRepository.findById(deviceId)).thenReturn(Optional.of(device));
        when(appContextService.getActive()).thenReturn(Optional.empty());

        // Account A photos
        when(photoRepository.countBySyncedToDiskAndAccountId(true, "acc-A")).thenReturn(5L);
        when(photoRepository.countByAccountIdAndExistsOnIcloud("acc-A", true)).thenReturn(7L);
        when(photoRepository.countByAccountIdAndExistsOnIphone("acc-A", true)).thenReturn(2L);

        // Account B photos (different totals, same device)
        when(photoRepository.countBySyncedToDiskAndAccountId(true, "acc-B")).thenReturn(11L);
        when(photoRepository.countByAccountIdAndExistsOnIcloud("acc-B", true)).thenReturn(3L);
        when(photoRepository.countByAccountIdAndExistsOnIphone("acc-B", true)).thenReturn(0L);

        StatsResponse a = service.getStats("acc-A");
        StatsResponse b = service.getStats("acc-B");

        assertThat(a.diskPhotoCount()).isEqualTo(5L);
        assertThat(a.icloudPhotoCount()).isEqualTo(7L);
        assertThat(a.iphonePhotoCount()).isEqualTo(2L);

        assertThat(b.diskPhotoCount()).isEqualTo(11L);
        assertThat(b.icloudPhotoCount()).isEqualTo(3L);
        assertThat(b.iphonePhotoCount()).isEqualTo(0L);

        // disk capacity still derived internally from the account's storage device
        assertThat(a.diskCapacityBytes()).isEqualTo(1_000L);
        assertThat(b.diskCapacityBytes()).isEqualTo(1_000L);
    }

    @Test
    void getStats_unknownAccount_throws() {
        when(accountRepository.findById("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getStats("nope"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
