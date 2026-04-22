package com.cloudsync.service;

import com.cloudsync.model.dto.DeletionJobStartResponse;
import com.cloudsync.model.dto.ICloudBatchDeleteResult;
import com.cloudsync.model.entity.Photo;
import com.cloudsync.provider.PhotoSyncProvider;
import com.cloudsync.repository.PhotoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class DeletionJobServiceTest {

    private PhotoRepository photoRepository;
    private PhotoSyncProvider iCloudProvider;
    private PhotoSyncProvider iPhoneProvider;
    private DeletionJobService service;

    @BeforeEach
    void setUp() {
        photoRepository = mock(PhotoRepository.class);
        iCloudProvider = mock(PhotoSyncProvider.class);
        iPhoneProvider = mock(PhotoSyncProvider.class);
        service = new DeletionJobService(photoRepository, iCloudProvider, iPhoneProvider, 50);
    }

    @Test
    void startJob_skipsPhotosNotOnICloud() {
        Photo onCloud = photo("p1", true, null);
        Photo notOnCloud = photo("p2", false, null);
        when(photoRepository.findByIdIn(List.of("p1", "p2"))).thenReturn(List.of(onCloud, notOnCloud));
        when(iCloudProvider.batchDeletePhotos(anyList(), anyString()))
                .thenReturn(List.of(new ICloudBatchDeleteResult("p1", true, null)));

        DeletionJobStartResponse resp = service.startJob("acc1", List.of("p1", "p2"), "ICLOUD");

        assertThat(resp.accepted()).isEqualTo(1);
        assertThat(resp.skipped()).isEqualTo(1);
        assertThat(resp.jobId()).isNotBlank();
    }

    @Test
    void startJob_skipsPhotosNotOnIPhone() {
        Photo onPhone = photo("p1", null, true);
        Photo notOnPhone = photo("p2", null, false);
        when(photoRepository.findByIdIn(List.of("p1", "p2"))).thenReturn(List.of(onPhone, notOnPhone));
        when(iPhoneProvider.batchDeletePhotos(anyList(), anyString()))
                .thenReturn(List.of(new ICloudBatchDeleteResult("p1", true, null)));

        DeletionJobStartResponse resp = service.startJob("acc1", List.of("p1", "p2"), "IPHONE");

        assertThat(resp.accepted()).isEqualTo(1);
        assertThat(resp.skipped()).isEqualTo(1);
    }

    @Test
    void startJob_returnsZeroAcceptedWhenNoneEligible() {
        Photo notOnCloud = photo("p1", false, null);
        when(photoRepository.findByIdIn(List.of("p1"))).thenReturn(List.of(notOnCloud));

        DeletionJobStartResponse resp = service.startJob("acc1", List.of("p1"), "ICLOUD");

        assertThat(resp.accepted()).isEqualTo(0);
        assertThat(resp.skipped()).isEqualTo(1);
    }

    @Test
    void startJob_createsJobThatCanBeQueried() {
        Photo p = photo("p1", true, null);
        when(photoRepository.findByIdIn(any())).thenReturn(List.of(p));
        when(iCloudProvider.batchDeletePhotos(anyList(), anyString()))
                .thenReturn(List.of(new ICloudBatchDeleteResult("p1", true, null)));

        DeletionJobStartResponse resp = service.startJob("acc1", List.of("p1"), "ICLOUD");

        assertThat(service.getJob(resp.jobId())).isPresent();
    }

    @Test
    void cancelJob_returnsFalseForUnknownId() {
        assertThat(service.cancelJob("nonexistent")).isFalse();
    }

    @Test
    void cancelJob_returnsTrueForKnownJob() throws InterruptedException {
        Photo p = photo("p1", true, null);
        when(photoRepository.findByIdIn(any())).thenReturn(List.of(p));
        when(iCloudProvider.batchDeletePhotos(anyList(), anyString()))
                .thenAnswer(inv -> {
                    Thread.sleep(200);
                    return List.of(new ICloudBatchDeleteResult("p1", true, null));
                });

        DeletionJobStartResponse resp = service.startJob("acc1", List.of("p1"), "ICLOUD");
        boolean cancelled = service.cancelJob(resp.jobId());

        assertThat(cancelled).isTrue();
    }

    @Test
    void allJobSummaries_includesDeletionType() {
        Photo p = photo("p1", true, null);
        when(photoRepository.findByIdIn(any())).thenReturn(List.of(p));
        when(iCloudProvider.batchDeletePhotos(anyList(), anyString()))
                .thenReturn(List.of(new ICloudBatchDeleteResult("p1", true, null)));

        service.startJob("acc1", List.of("p1"), "ICLOUD");

        assertThat(service.allJobSummaries()).isNotEmpty();
        assertThat(service.allJobSummaries().get(0).type()).isEqualTo("DELETION");
    }

    @Test
    void runJob_updatesDbAfterSuccessfulDeletion() throws InterruptedException {
        Photo p = photo("p1", true, null);
        when(photoRepository.findByIdIn(any())).thenReturn(List.of(p));
        when(iCloudProvider.batchDeletePhotos(anyList(), anyString()))
                .thenReturn(List.of(new ICloudBatchDeleteResult("p1", true, null)));

        DeletionJobStartResponse resp = service.startJob("acc1", List.of("p1"), "ICLOUD");

        // Wait for virtual thread to finish
        Thread.sleep(300);

        verify(photoRepository, atLeastOnce()).update(argThat(photo ->
                !photo.isExistsOnIcloud() && photo.isDeleted()
        ));
    }

    @Test
    void runJob_recordsFailureWhenProviderReturnsError() throws InterruptedException {
        Photo p = photo("p1", true, null);
        when(photoRepository.findByIdIn(any())).thenReturn(List.of(p));
        when(iCloudProvider.batchDeletePhotos(anyList(), anyString()))
                .thenReturn(List.of(new ICloudBatchDeleteResult("p1", false, "iCloud error")));

        DeletionJobStartResponse resp = service.startJob("acc1", List.of("p1"), "ICLOUD");

        Thread.sleep(300);

        var job = service.getJob(resp.jobId());
        assertThat(job).isPresent();
        assertThat(job.get().getFailed()).isEqualTo(1);
        assertThat(job.get().getDeleted()).isEqualTo(0);
    }

    private Photo photo(String id, Boolean existsOnIcloud, Boolean existsOnIphone) {
        Photo p = new Photo();
        p.setId(id);
        p.setIcloudPhotoId("icloud-" + id);
        p.setIphoneLocation("/phone/" + id);
        if (existsOnIcloud != null) p.setExistsOnIcloud(existsOnIcloud);
        if (existsOnIphone != null) p.setExistsOnIphone(existsOnIphone);
        return p;
    }
}
