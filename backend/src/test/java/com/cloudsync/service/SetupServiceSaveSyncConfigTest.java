package com.cloudsync.service;

import com.cloudsync.model.dto.SyncConfigRequest;
import com.cloudsync.model.entity.ICloudAccount;
import com.cloudsync.repository.AccountRepository;
import com.cloudsync.repository.PhotoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code saveSyncConfig} is the single persistence point for an account's sync folder.
 * It must set {@code syncFolderPath}, {@code storageDeviceId} and {@code organizeBy}.
 */
@ExtendWith(MockitoExtension.class)
class SetupServiceSaveSyncConfigTest {

    @Mock DiskSetupService diskSetupService;
    @Mock AccountRepository accountRepository;
    @Mock PhotoRepository photoRepository;

    SetupService service;

    @BeforeEach
    void setUp() {
        service = new SetupService(diskSetupService, accountRepository, photoRepository);
    }

    @Test
    void saveSyncConfig_persistsSyncFolderPathAndStorageDevice(@TempDir Path folder) {
        ICloudAccount account = new ICloudAccount();
        account.setId("acc-a");
        when(accountRepository.findById("acc-a")).thenReturn(Optional.of(account));
        // not mounted → skip under-disk check, exercise persistence directly
        when(diskSetupService.getDriveStatus())
                .thenReturn(new DiskSetupService.DriveStatus(false, null, null, null, null, null, null));

        service.saveSyncConfig("acc-a",
                new SyncConfigRequest(folder.toString(), "dev-1", "YEAR"));

        ArgumentCaptor<ICloudAccount> captor = ArgumentCaptor.forClass(ICloudAccount.class);
        verify(accountRepository).update(captor.capture());
        ICloudAccount saved = captor.getValue();
        assertThat(saved.getSyncFolderPath()).isEqualTo(folder.toString());
        assertThat(saved.getStorageDeviceId()).isEqualTo("dev-1");
        assertThat(saved.getOrganizeBy()).isEqualTo("YEAR");
    }
}
