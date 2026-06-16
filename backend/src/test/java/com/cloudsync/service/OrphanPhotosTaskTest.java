package com.cloudsync.service;

import com.cloudsync.model.entity.ICloudAccount;
import com.cloudsync.model.entity.Photo;
import com.cloudsync.repository.AccountRepository;
import com.cloudsync.repository.PhotoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrphanPhotosTaskTest {

    @Mock PhotoRepository photoRepository;
    @Mock AccountRepository accountRepository;
    @Mock TaskHistoryService taskHistoryService;

    OrphanPhotosJobService service;

    @BeforeEach
    void setUp() {
        service = new OrphanPhotosJobService(photoRepository, accountRepository, taskHistoryService);
    }

    private ICloudAccount account(String id, String deviceId) {
        ICloudAccount a = new ICloudAccount();
        a.setId(id);
        a.setStorageDeviceId(deviceId);
        return a;
    }

    private Photo orphan(String id, String deviceId) {
        Photo p = new Photo();
        p.setId(id);
        p.setAccountId(null);
        p.setStorageDeviceId(deviceId);
        p.setFilename("img_" + id + ".jpg");
        return p;
    }

    @Test
    void countOrphans_returnsCountForAccountDevice() {
        when(accountRepository.findById("acc-1")).thenReturn(Optional.of(account("acc-1", "dev-1")));
        when(photoRepository.countByAccountIdIsNullAndStorageDeviceId("dev-1")).thenReturn(3L);

        assertThat(service.countOrphans("acc-1")).isEqualTo(3L);
    }

    @Test
    void countOrphans_returnsZeroWhenAccountMissing() {
        when(accountRepository.findById("missing")).thenReturn(Optional.empty());

        assertThat(service.countOrphans("missing")).isZero();
    }

    @Test
    void runJob_assignsOnlyNullAccountPhotosOnDevice() {
        when(accountRepository.findById("acc-1")).thenReturn(Optional.of(account("acc-1", "dev-1")));
        List<Photo> orphans = List.of(orphan("p1", "dev-1"), orphan("p2", "dev-1"));
        when(photoRepository.findByAccountIdIsNullAndStorageDeviceId("dev-1")).thenReturn(orphans);

        OrphanPhotosJob job = new OrphanPhotosJob("job-1");
        service.runJob(job, "acc-1");

        ArgumentCaptor<Photo> captor = ArgumentCaptor.forClass(Photo.class);
        verify(photoRepository, times(2)).update(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(p -> assertThat(p.getAccountId()).isEqualTo("acc-1"));
        assertThat(job.currentProgress().assigned()).isEqualTo(2);
        assertThat(job.currentProgress().total()).isEqualTo(2);
        assertThat(job.isDone()).isTrue();
    }

    @Test
    void runJob_reportsAccurateAssignedCountAndCompletes() {
        when(accountRepository.findById("acc-1")).thenReturn(Optional.of(account("acc-1", "dev-1")));
        when(photoRepository.findByAccountIdIsNullAndStorageDeviceId("dev-1"))
                .thenReturn(List.of(orphan("p1", "dev-1")));

        OrphanPhotosJob job = new OrphanPhotosJob("job-1");
        service.runJob(job, "acc-1");

        verify(taskHistoryService).completeTask("job-1", "COMPLETED", 1, 0, null);
    }

    @Test
    void runJob_completesWithZeroWhenNoOrphans() {
        when(accountRepository.findById("acc-1")).thenReturn(Optional.of(account("acc-1", "dev-1")));
        when(photoRepository.findByAccountIdIsNullAndStorageDeviceId("dev-1")).thenReturn(List.of());

        OrphanPhotosJob job = new OrphanPhotosJob("job-1");
        service.runJob(job, "acc-1");

        verify(photoRepository, times(0)).update(org.mockito.ArgumentMatchers.any(Photo.class));
        verify(taskHistoryService).completeTask("job-1", "COMPLETED", 0, 0, null);
        assertThat(job.currentProgress().assigned()).isZero();
    }

    @Test
    void startJob_createsTaskHistoryWithAssignOrphanType() {
        when(accountRepository.findById("acc-1")).thenReturn(Optional.of(account("acc-1", "dev-1")));
        when(photoRepository.countByAccountIdIsNullAndStorageDeviceId("dev-1")).thenReturn(2L);
        lenient().when(photoRepository.findByAccountIdIsNullAndStorageDeviceId("dev-1")).thenReturn(List.of());

        service.startJob("acc-1");

        verify(taskHistoryService).createTask(anyString(), eq("ASSIGN_ORPHAN_PHOTOS"), eq("acc-1"), isNull(), eq(2));
    }
}
