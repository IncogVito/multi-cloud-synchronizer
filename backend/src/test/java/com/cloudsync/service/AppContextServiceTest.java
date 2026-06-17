package com.cloudsync.service;

import com.cloudsync.model.dto.AppContext;
import com.cloudsync.model.entity.AppContextEntity;
import com.cloudsync.model.entity.StorageDevice;
import com.cloudsync.repository.AppContextRepository;
import com.cloudsync.repository.StorageDeviceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * D3: app_context carries device context only (no base_path). The folder of an account
 * lives on {@code account.sync_folder_path}. The built {@link AppContext} therefore exposes
 * device + mount point, and is loadable without any base path.
 */
@ExtendWith(MockitoExtension.class)
class AppContextServiceTest {

    @Mock AppContextRepository repository;
    @Mock StorageDeviceRepository storageDeviceRepository;

    AppContextService service;

    @BeforeEach
    void setUp() {
        service = new AppContextService(repository, storageDeviceRepository);
    }

    private AppContextEntity contextRow(String deviceId) {
        AppContextEntity e = new AppContextEntity();
        e.setId(1);
        e.setStorageDeviceId(deviceId);
        e.setSetAt(Instant.parse("2024-06-01T10:00:00Z"));
        return e;
    }

    private StorageDevice device(String id, String mountPoint) {
        StorageDevice d = new StorageDevice();
        d.setId(id);
        d.setLabel("My Drive");
        d.setMountPoint(mountPoint);
        return d;
    }

    @Test
    void getActive_buildsDeviceContextWithoutBasePath() {
        when(repository.findById(1)).thenReturn(Optional.of(contextRow("dev-1")));
        // a mount point that is definitely not mounted on the build host
        when(storageDeviceRepository.findById("dev-1"))
                .thenReturn(Optional.of(device("dev-1", "/mnt/cloudsync-not-mounted-xyz")));

        Optional<AppContext> ctx = service.getActive();

        assertThat(ctx).isPresent();
        assertThat(ctx.get().storageDeviceId()).isEqualTo("dev-1");
        assertThat(ctx.get().storageDeviceLabel()).isEqualTo("My Drive");
        assertThat(ctx.get().mountPoint()).isEqualTo("/mnt/cloudsync-not-mounted-xyz");
        // unmounted → degraded, free space unavailable
        assertThat(ctx.get().degraded()).isTrue();
        assertThat(ctx.get().freeBytes()).isNull();
    }

    @Test
    void getActive_emptyWhenNoStorageDeviceConfigured() {
        when(repository.findById(1)).thenReturn(Optional.of(contextRow(null)));

        assertThat(service.getActive()).isEmpty();
    }
}
