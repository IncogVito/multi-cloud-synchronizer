package com.cloudsync.service;

import com.cloudsync.model.dto.DeletionJobStartResponse;
import com.cloudsync.model.dto.ICloudBatchDeleteResult;
import com.cloudsync.model.dto.PhotoDeleteItem;
import com.cloudsync.model.entity.ICloudAccount;
import com.cloudsync.model.entity.Photo;
import com.cloudsync.provider.PhotoSyncProvider;
import com.cloudsync.repository.AccountRepository;
import com.cloudsync.repository.PhotoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class DeletionJobServiceTest {

    private PhotoRepository photoRepository;
    private AccountRepository accountRepository;
    private TaskHistoryService taskHistoryService;
    private PhotoSyncProvider iCloudProvider;
    private PhotoSyncProvider iPhoneProvider;
    private DeletionJobService service;

    @BeforeEach
    void setUp() {
        photoRepository = mock(PhotoRepository.class);
        accountRepository = mock(AccountRepository.class);
        taskHistoryService = mock(TaskHistoryService.class);
        iCloudProvider = mock(PhotoSyncProvider.class);
        iPhoneProvider = mock(PhotoSyncProvider.class);
        service = new DeletionJobService(photoRepository, accountRepository, taskHistoryService,
                iCloudProvider, iPhoneProvider, 50);

        when(accountRepository.findById(anyString())).thenReturn(Optional.of(account("acc1", "session-1")));
    }

    // --- eligibility ---

    @Test
    void startJob_skipsPhotosNotOnICloud() {
        Photo onCloud = photo("p1", true, null);
        Photo notOnCloud = photo("p2", false, null);
        when(photoRepository.findByIdIn(List.of("p1", "p2"))).thenReturn(List.of(onCloud, notOnCloud));
        when(iCloudProvider.batchDeletePhotos(anyList(), anyString()))
                .thenReturn(List.of(new ICloudBatchDeleteResult("icloud-p1", true, null)));

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
                .thenReturn(List.of(new ICloudBatchDeleteResult("/phone/p1", true, null)));

        DeletionJobStartResponse resp = service.startJob("acc1", List.of("p1", "p2"), "IPHONE");

        assertThat(resp.accepted()).isEqualTo(1);
        assertThat(resp.skipped()).isEqualTo(1);
    }

    @Test
    void startJob_returnsZeroAcceptedWhenNoneEligible() {
        when(photoRepository.findByIdIn(List.of("p1"))).thenReturn(List.of(photo("p1", false, null)));

        DeletionJobStartResponse resp = service.startJob("acc1", List.of("p1"), "ICLOUD");

        assertThat(resp.accepted()).isEqualTo(0);
        assertThat(resp.skipped()).isEqualTo(1);
    }

    @Test
    void startJob_createsJobThatCanBeQueried() {
        when(photoRepository.findByIdIn(any())).thenReturn(List.of(photo("p1", true, null)));
        when(iCloudProvider.batchDeletePhotos(anyList(), anyString()))
                .thenReturn(List.of(new ICloudBatchDeleteResult("icloud-p1", true, null)));

        DeletionJobStartResponse resp = service.startJob("acc1", List.of("p1"), "ICLOUD");

        assertThat(service.getJob(resp.jobId())).isPresent();
    }

    // --- cancel ---

    @Test
    void cancelJob_returnsFalseForUnknownId() {
        assertThat(service.cancelJob("nonexistent")).isFalse();
    }

    @Test
    void cancelJob_returnsTrueForKnownJob() throws InterruptedException {
        when(photoRepository.findByIdIn(any())).thenReturn(List.of(photo("p1", true, null)));
        when(iCloudProvider.batchDeletePhotos(anyList(), anyString()))
                .thenAnswer(inv -> {
                    Thread.sleep(200);
                    return List.of(new ICloudBatchDeleteResult("icloud-p1", true, null));
                });

        DeletionJobStartResponse resp = service.startJob("acc1", List.of("p1"), "ICLOUD");

        assertThat(service.cancelJob(resp.jobId())).isTrue();
    }

    // --- summaries ---

    @Test
    void allJobSummaries_includesDeletionType() {
        when(photoRepository.findByIdIn(any())).thenReturn(List.of(photo("p1", true, null)));
        when(iCloudProvider.batchDeletePhotos(anyList(), anyString()))
                .thenReturn(List.of(new ICloudBatchDeleteResult("icloud-p1", true, null)));

        service.startJob("acc1", List.of("p1"), "ICLOUD");

        assertThat(service.allJobSummaries()).isNotEmpty();
        assertThat(service.allJobSummaries().get(0).type()).isEqualTo("DELETION");
    }

    // --- execution ---

    @Test
    void runJob_updatesDbAfterSuccessfulDeletion() throws InterruptedException {
        when(photoRepository.findByIdIn(any())).thenReturn(List.of(photo("p1", true, null)));
        when(iCloudProvider.batchDeletePhotos(anyList(), anyString()))
                .thenReturn(List.of(new ICloudBatchDeleteResult("icloud-p1", true, null)));

        DeletionJobStartResponse resp = service.startJob("acc1", List.of("p1"), "ICLOUD");
        awaitDone(resp.jobId());

        verify(photoRepository, atLeastOnce()).update(argThat(p -> !p.isExistsOnIcloud()));
    }

    @Test
    void runJob_recordsFailureWhenProviderReturnsError() throws InterruptedException {
        when(photoRepository.findByIdIn(any())).thenReturn(List.of(photo("p1", true, null)));
        when(iCloudProvider.batchDeletePhotos(anyList(), anyString()))
                .thenReturn(List.of(new ICloudBatchDeleteResult("icloud-p1", false, "iCloud error")));

        DeletionJobStartResponse resp = service.startJob("acc1", List.of("p1"), "ICLOUD");
        awaitDone(resp.jobId());

        var job = service.getJob(resp.jobId()).orElseThrow();
        assertThat(job.getFailed()).isEqualTo(1);
        assertThat(job.getDeleted()).isEqualTo(0);
    }

    // --- sub-chunk behaviour ---

    @Test
    void runJob_callsBatchDeleteInSubChunksOfFive() throws InterruptedException {
        List<Photo> photos = photos(12, true);
        when(photoRepository.findByIdIn(any())).thenReturn(photos);
        when(iCloudProvider.batchDeletePhotos(anyList(), anyString()))
                .thenAnswer(inv -> successResults(inv.getArgument(0)));

        DeletionJobStartResponse resp = service.startJob("acc1", ids(photos), "ICLOUD");
        awaitDone(resp.jobId());

        // 12 photos / 5 per sub-chunk = 3 invocations
        verify(iCloudProvider, times(3)).batchDeletePhotos(anyList(), anyString());
    }

    @Test
    void runJob_eachSubChunkContainsAtMostFiveIds() throws InterruptedException {
        List<Photo> photoList = photos(12, true);
        List<List<PhotoDeleteItem>> capturedBatches = new ArrayList<>();
        when(photoRepository.findByIdIn(any())).thenReturn(photoList);
        when(iCloudProvider.batchDeletePhotos(anyList(), anyString()))
                .thenAnswer(inv -> {
                    capturedBatches.add(new ArrayList<>(inv.getArgument(0)));
                    return successResults(inv.getArgument(0));
                });

        DeletionJobStartResponse resp = service.startJob("acc1", ids(photoList), "ICLOUD");
        awaitDone(resp.jobId());

        assertThat(capturedBatches).hasSize(3);
        assertThat(capturedBatches.get(0)).hasSize(5);
        assertThat(capturedBatches.get(1)).hasSize(5);
        assertThat(capturedBatches.get(2)).hasSize(2);
    }

    @Test
    void runJob_emitsProgressAfterEachSubChunk() throws InterruptedException {
        List<Photo> photoList = photos(10, true);
        when(photoRepository.findByIdIn(any())).thenReturn(photoList);
        when(iCloudProvider.batchDeletePhotos(anyList(), anyString()))
                .thenAnswer(inv -> successResults(inv.getArgument(0)));

        DeletionJobStartResponse resp = service.startJob("acc1", ids(photoList), "ICLOUD");
        List<Integer> deletedSnapshots = new ArrayList<>();
        service.getJob(resp.jobId()).orElseThrow().subscribe()
                .subscribe(p -> deletedSnapshots.add(p.deleted()));

        awaitDone(resp.jobId());

        // should see intermediate count of 5, then 10
        assertThat(deletedSnapshots).contains(5, 10);
    }

    @Test
    void runJob_continuesRemainingSubChunksWhenOneSubChunkThrows() throws InterruptedException {
        List<Photo> photoList = photos(10, true);
        when(photoRepository.findByIdIn(any())).thenReturn(photoList);
        when(iCloudProvider.batchDeletePhotos(anyList(), anyString()))
                .thenThrow(new RuntimeException("timeout"))
                .thenAnswer(inv -> successResults(inv.getArgument(0)));

        DeletionJobStartResponse resp = service.startJob("acc1", ids(photoList), "ICLOUD");
        awaitDone(resp.jobId());

        // first sub-chunk re-queued after exception and eventually succeeds → all 10 deleted
        var job = service.getJob(resp.jobId()).orElseThrow();
        assertThat(job.getFailed()).isEqualTo(0);
        assertThat(job.getDeleted()).isEqualTo(10);
    }

    @Test
    void runJob_stopsProcessingSubChunksAfterCancel() throws InterruptedException {
        List<Photo> photoList = photos(15, true);
        CountDownLatch firstBatchStarted = new CountDownLatch(1);
        when(photoRepository.findByIdIn(any())).thenReturn(photoList);
        when(iCloudProvider.batchDeletePhotos(anyList(), anyString()))
                .thenAnswer(inv -> {
                    firstBatchStarted.countDown();
                    Thread.sleep(150);
                    return successResults(inv.getArgument(0));
                });

        DeletionJobStartResponse resp = service.startJob("acc1", ids(photoList), "ICLOUD");
        firstBatchStarted.await(1, TimeUnit.SECONDS);
        service.cancelJob(resp.jobId());

        Thread.sleep(300);

        // only the in-flight sub-chunk completes; remaining sub-chunks are skipped
        verify(iCloudProvider, times(1)).batchDeletePhotos(anyList(), anyString());
    }

    @Test
    void runJob_recordsNoSessionFailureWhenAccountHasNoSession() throws InterruptedException {
        when(accountRepository.findById("acc1")).thenReturn(Optional.of(account("acc1", null)));
        List<Photo> photoList = photos(5, true);
        when(photoRepository.findByIdIn(any())).thenReturn(photoList);

        DeletionJobStartResponse resp = service.startJob("acc1", ids(photoList), "ICLOUD");
        awaitDone(resp.jobId());

        verify(iCloudProvider, never()).batchDeletePhotos(anyList(), anyString());
        assertThat(service.getJob(resp.jobId()).orElseThrow().getFailed()).isEqualTo(5);
    }

    // --- job merging ---

    @Test
    void startJob_whenRunningJobExistsForSameProvider_returnsSameJobId() throws InterruptedException {
        CountDownLatch firstBatchStarted = new CountDownLatch(1);
        when(photoRepository.findByIdIn(List.of("p1"))).thenReturn(List.of(photo("p1", true, null)));
        when(photoRepository.findByIdIn(List.of("p2"))).thenReturn(List.of(photo("p2", true, null)));
        when(iCloudProvider.batchDeletePhotos(anyList(), anyString()))
                .thenAnswer(inv -> {
                    firstBatchStarted.countDown();
                    Thread.sleep(500);
                    return successResults(inv.getArgument(0));
                });

        DeletionJobStartResponse first = service.startJob("acc1", List.of("p1"), "ICLOUD");
        firstBatchStarted.await(2, TimeUnit.SECONDS);

        DeletionJobStartResponse second = service.startJob("acc1", List.of("p2"), "ICLOUD");

        assertThat(second.jobId()).isEqualTo(first.jobId());
    }

    @Test
    void startJob_whenRunningJobExistsForSameProvider_allPhotosAreProcessed() throws InterruptedException {
        CountDownLatch firstBatchStarted = new CountDownLatch(1);
        when(photoRepository.findByIdIn(List.of("p1"))).thenReturn(List.of(photo("p1", true, null)));
        when(photoRepository.findByIdIn(List.of("p2"))).thenReturn(List.of(photo("p2", true, null)));
        when(iCloudProvider.batchDeletePhotos(anyList(), anyString()))
                .thenAnswer(inv -> {
                    firstBatchStarted.countDown();
                    Thread.sleep(100);
                    return successResults(inv.getArgument(0));
                })
                .thenAnswer(inv -> successResults(inv.getArgument(0)));

        DeletionJobStartResponse first = service.startJob("acc1", List.of("p1"), "ICLOUD");
        firstBatchStarted.await(2, TimeUnit.SECONDS);
        service.startJob("acc1", List.of("p2"), "ICLOUD");

        awaitDone(first.jobId());

        var job = service.getJob(first.jobId()).orElseThrow();
        assertThat(job.getTotal()).isEqualTo(2);
        assertThat(job.getDeleted()).isEqualTo(2);
    }

    @Test
    void startJob_whenRunningJobExistsForSameProvider_mergedPhotoIdsAreTrackedInJob() throws InterruptedException {
        CountDownLatch firstBatchStarted = new CountDownLatch(1);
        when(photoRepository.findByIdIn(List.of("p1"))).thenReturn(List.of(photo("p1", true, null)));
        when(photoRepository.findByIdIn(List.of("p2"))).thenReturn(List.of(photo("p2", true, null)));
        when(iCloudProvider.batchDeletePhotos(anyList(), anyString()))
                .thenAnswer(inv -> {
                    firstBatchStarted.countDown();
                    Thread.sleep(100);
                    return successResults(inv.getArgument(0));
                })
                .thenAnswer(inv -> successResults(inv.getArgument(0)));

        DeletionJobStartResponse first = service.startJob("acc1", List.of("p1"), "ICLOUD");
        firstBatchStarted.await(2, TimeUnit.SECONDS);
        service.startJob("acc1", List.of("p2"), "ICLOUD");

        awaitDone(first.jobId());

        var job = service.getJob(first.jobId()).orElseThrow();
        assertThat(job.getPhotoIds()).containsExactlyInAnyOrder("p1", "p2");
    }

    @Test
    void startJob_whenExistingJobForSameProviderIsDone_createsNewJob() throws InterruptedException {
        when(photoRepository.findByIdIn(any())).thenReturn(List.of(photo("p1", true, null)));
        when(iCloudProvider.batchDeletePhotos(anyList(), anyString()))
                .thenAnswer(inv -> successResults(inv.getArgument(0)));

        DeletionJobStartResponse first = service.startJob("acc1", List.of("p1"), "ICLOUD");
        awaitDone(first.jobId());

        when(photoRepository.findByIdIn(List.of("p2"))).thenReturn(List.of(photo("p2", true, null)));
        DeletionJobStartResponse second = service.startJob("acc1", List.of("p2"), "ICLOUD");

        assertThat(second.jobId()).isNotEqualTo(first.jobId());
    }

    @Test
    void startJob_differentProviders_eachGetsOwnJob() throws InterruptedException {
        CountDownLatch icloudStarted = new CountDownLatch(1);
        when(photoRepository.findByIdIn(List.of("p1"))).thenReturn(List.of(photo("p1", true, null)));
        when(photoRepository.findByIdIn(List.of("p2"))).thenReturn(List.of(photo("p2", null, true)));
        when(iCloudProvider.batchDeletePhotos(anyList(), anyString()))
                .thenAnswer(inv -> {
                    icloudStarted.countDown();
                    Thread.sleep(500);
                    return successResults(inv.getArgument(0));
                });
        when(iPhoneProvider.batchDeletePhotos(anyList(), anyString()))
                .thenAnswer(inv -> { Thread.sleep(500); return successResults(inv.getArgument(0)); });

        DeletionJobStartResponse icloud = service.startJob("acc1", List.of("p1"), "ICLOUD");
        icloudStarted.await(2, TimeUnit.SECONDS);
        DeletionJobStartResponse iphone = service.startJob("acc1", List.of("p2"), "IPHONE");

        assertThat(iphone.jobId()).isNotEqualTo(icloud.jobId());
    }

    @Test
    void startJob_multipleSequentialMerges_allPhotosProcessed() throws InterruptedException {
        AtomicInteger callCount = new AtomicInteger(0);
        CountDownLatch firstStarted = new CountDownLatch(1);
        when(photoRepository.findByIdIn(List.of("p1"))).thenReturn(List.of(photo("p1", true, null)));
        when(photoRepository.findByIdIn(List.of("p2"))).thenReturn(List.of(photo("p2", true, null)));
        when(photoRepository.findByIdIn(List.of("p3"))).thenReturn(List.of(photo("p3", true, null)));
        when(iCloudProvider.batchDeletePhotos(anyList(), anyString()))
                .thenAnswer(inv -> {
                    if (callCount.getAndIncrement() == 0) firstStarted.countDown();
                    Thread.sleep(50);
                    return successResults(inv.getArgument(0));
                });

        DeletionJobStartResponse first = service.startJob("acc1", List.of("p1"), "ICLOUD");
        firstStarted.await(2, TimeUnit.SECONDS);
        service.startJob("acc1", List.of("p2"), "ICLOUD");
        service.startJob("acc1", List.of("p3"), "ICLOUD");

        awaitDone(first.jobId());

        var job = service.getJob(first.jobId()).orElseThrow();
        assertThat(job.getTotal()).isEqualTo(3);
        assertThat(job.getDeleted()).isEqualTo(3);
    }

    // --- retry ---

    @Test
    void runJob_retriesPhotoThreeTimesOnResultFailureBeforeCountingAsFailed() throws InterruptedException {
        when(photoRepository.findByIdIn(any())).thenReturn(List.of(photo("p1", true, null)));
        when(iCloudProvider.batchDeletePhotos(anyList(), anyString()))
                .thenReturn(List.of(new ICloudBatchDeleteResult("icloud-p1", false, "iCloud error")));

        DeletionJobStartResponse resp = service.startJob("acc1", List.of("p1"), "ICLOUD");
        awaitDone(resp.jobId());

        verify(iCloudProvider, times(3)).batchDeletePhotos(anyList(), anyString());
        var job = service.getJob(resp.jobId()).orElseThrow();
        assertThat(job.getFailed()).isEqualTo(1);
        assertThat(job.getDeleted()).isEqualTo(0);
    }

    @Test
    void runJob_successOnSecondAttempt_countsAsDeletedNotFailed() throws InterruptedException {
        when(photoRepository.findByIdIn(any())).thenReturn(List.of(photo("p1", true, null)));
        when(iCloudProvider.batchDeletePhotos(anyList(), anyString()))
                .thenReturn(List.of(new ICloudBatchDeleteResult("icloud-p1", false, "transient")))
                .thenAnswer(inv -> successResults(inv.getArgument(0)));

        DeletionJobStartResponse resp = service.startJob("acc1", List.of("p1"), "ICLOUD");
        awaitDone(resp.jobId());

        var job = service.getJob(resp.jobId()).orElseThrow();
        assertThat(job.getDeleted()).isEqualTo(1);
        assertThat(job.getFailed()).isEqualTo(0);
    }

    @Test
    void runJob_retriesPhotoThreeTimesOnExceptionBeforeCountingAsFailed() throws InterruptedException {
        when(photoRepository.findByIdIn(any())).thenReturn(List.of(photo("p1", true, null)));
        when(iCloudProvider.batchDeletePhotos(anyList(), anyString()))
                .thenThrow(new RuntimeException("network error"));

        DeletionJobStartResponse resp = service.startJob("acc1", List.of("p1"), "ICLOUD");
        awaitDone(resp.jobId());

        verify(iCloudProvider, times(3)).batchDeletePhotos(anyList(), anyString());
        var job = service.getJob(resp.jobId()).orElseThrow();
        assertThat(job.getFailed()).isEqualTo(1);
        assertThat(job.getDeleted()).isEqualTo(0);
    }

    @Test
    void runJob_exceptionThenSuccess_onRetry_countsAsDeleted() throws InterruptedException {
        when(photoRepository.findByIdIn(any())).thenReturn(List.of(photo("p1", true, null)));
        when(iCloudProvider.batchDeletePhotos(anyList(), anyString()))
                .thenThrow(new RuntimeException("transient"))
                .thenAnswer(inv -> successResults(inv.getArgument(0)));

        DeletionJobStartResponse resp = service.startJob("acc1", List.of("p1"), "ICLOUD");
        awaitDone(resp.jobId());

        var job = service.getJob(resp.jobId()).orElseThrow();
        assertThat(job.getDeleted()).isEqualTo(1);
        assertThat(job.getFailed()).isEqualTo(0);
    }

    @Test
    void runJob_intermediateRetries_doNotAppearAsFailedInProgress() throws InterruptedException {
        when(photoRepository.findByIdIn(any())).thenReturn(List.of(photo("p1", true, null)));
        when(iCloudProvider.batchDeletePhotos(anyList(), anyString()))
                .thenReturn(List.of(new ICloudBatchDeleteResult("icloud-p1", false, "err")))
                .thenReturn(List.of(new ICloudBatchDeleteResult("icloud-p1", false, "err")))
                .thenAnswer(inv -> successResults(inv.getArgument(0)));

        DeletionJobStartResponse resp = service.startJob("acc1", List.of("p1"), "ICLOUD");
        awaitDone(resp.jobId());

        // two failures then success: photo should end up deleted, never counted as permanently failed
        var job = service.getJob(resp.jobId()).orElseThrow();
        assertThat(job.getFailed()).isEqualTo(0);
        assertThat(job.getDeleted()).isEqualTo(1);
    }

    // --- helpers ---

    private void awaitDone(String jobId) throws InterruptedException {
        for (int i = 0; i < 40; i++) {
            if (service.getJob(jobId).map(DeletionJob::isDone).orElse(false)) return;
            Thread.sleep(50);
        }
    }

    private List<Photo> photos(int count, boolean onIcloud) {
        List<Photo> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(photo("p" + i, onIcloud, null));
        }
        return list;
    }

    private List<String> ids(List<Photo> photos) {
        return photos.stream().map(Photo::getId).toList();
    }

    private List<ICloudBatchDeleteResult> successResults(List<PhotoDeleteItem> items) {
        return items.stream()
                .map(item -> new ICloudBatchDeleteResult(item.photoId(), true, null))
                .toList();
    }

    private Photo photo(String id, Boolean existsOnIcloud, Boolean existsOnIphone) {
        Photo p = new Photo();
        p.setId(id);
        p.setIcloudPhotoId("icloud-" + id);
        p.setIphoneLocation("/phone/" + id);
        p.setFilename(id + ".jpg");
        if (existsOnIcloud != null) p.setExistsOnIcloud(existsOnIcloud);
        if (existsOnIphone != null) p.setExistsOnIphone(existsOnIphone);
        return p;
    }

    private ICloudAccount account(String id, String sessionId) {
        ICloudAccount acc = new ICloudAccount();
        acc.setId(id);
        acc.setSessionId(sessionId);
        return acc;
    }
}
