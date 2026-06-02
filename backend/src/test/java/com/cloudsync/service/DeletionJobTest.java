package com.cloudsync.service;

import com.cloudsync.model.dto.DeletionProgress;
import com.cloudsync.model.entity.Photo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class DeletionJobTest {

    @Test
    void newJob_startsWithRunningStatusAndZeroCounts() {
        DeletionJob job = new DeletionJob("j1", "acc1", "ICLOUD", List.of("p1", "p2"));

        assertThat(job.getStatus()).isEqualTo("RUNNING");
        assertThat(job.getTotal()).isEqualTo(2);
        assertThat(job.getDeleted()).isEqualTo(0);
        assertThat(job.getFailed()).isEqualTo(0);
        assertThat(job.isDone()).isFalse();
        assertThat(job.isCancelled()).isFalse();
    }

    @Test
    void recordSuccess_incrementsDeletedCount() {
        DeletionJob job = new DeletionJob("j1", "acc1", "ICLOUD", List.of("p1", "p2"));

        job.recordSuccess(List.of("p1"));

        assertThat(job.getDeleted()).isEqualTo(1);
        assertThat(job.getFailed()).isEqualTo(0);
    }

    @Test
    void recordFailure_incrementsFailedCount() {
        DeletionJob job = new DeletionJob("j1", "acc1", "ICLOUD", List.of("p1", "p2"));

        job.recordFailure(List.of("p1"));

        assertThat(job.getFailed()).isEqualTo(1);
        assertThat(job.getDeleted()).isEqualTo(0);
    }

    @Test
    void recordSuccessAndFailure_accumulateIndependently() {
        DeletionJob job = new DeletionJob("j1", "acc1", "ICLOUD", List.of("p1", "p2", "p3"));

        job.recordSuccess(List.of("p1", "p2"));
        job.recordFailure(List.of("p3"));

        assertThat(job.getDeleted()).isEqualTo(2);
        assertThat(job.getFailed()).isEqualTo(1);
    }

    @Test
    void markDone_setsStatusToCompleted() {
        DeletionJob job = new DeletionJob("j1", "acc1", "ICLOUD", List.of("p1"));

        job.markDone();

        assertThat(job.getStatus()).isEqualTo("COMPLETED");
        assertThat(job.isDone()).isTrue();
    }

    @Test
    void markFailed_setsStatusToFailed() {
        DeletionJob job = new DeletionJob("j1", "acc1", "ICLOUD", List.of("p1"));

        job.markFailed();

        assertThat(job.getStatus()).isEqualTo("FAILED");
        assertThat(job.isDone()).isTrue();
    }

    @Test
    void cancel_setsIsCancelledTrue() {
        DeletionJob job = new DeletionJob("j1", "acc1", "ICLOUD", List.of("p1"));

        job.cancel();

        assertThat(job.isCancelled()).isTrue();
        assertThat(job.isDone()).isTrue();
    }

    @Test
    void currentProgress_reflectsAccumulatedState() {
        DeletionJob job = new DeletionJob("j1", "acc1", "ICLOUD", List.of("p1", "p2", "p3"));
        job.recordSuccess(List.of("p1"));
        job.recordFailure(List.of("p2"));

        DeletionProgress p = job.currentProgress();

        assertThat(p.total()).isEqualTo(3);
        assertThat(p.deleted()).isEqualTo(1);
        assertThat(p.failed()).isEqualTo(1);
        assertThat(p.done()).isFalse();
    }

    @Test
    void markDone_finalProgressContainsSuccessfulAndFailedIds() {
        DeletionJob job = new DeletionJob("j1", "acc1", "ICLOUD", List.of("p1", "p2"));
        job.recordSuccess(List.of("p1"));
        job.recordFailure(List.of("p2"));

        AtomicReference<DeletionProgress> last = new AtomicReference<>();
        job.subscribe().subscribe(last::set);
        job.markDone();

        DeletionProgress p = last.get();
        assertThat(p.done()).isTrue();
        assertThat(p.successfulIds()).containsExactly("p1");
        assertThat(p.failedIds()).containsExactly("p2");
    }

    @Test
    void subscribe_emitsProgressOnEachRecord() {
        DeletionJob job = new DeletionJob("j1", "acc1", "ICLOUD", List.of("p1", "p2"));
        AtomicReference<DeletionProgress> last = new AtomicReference<>();
        job.subscribe().subscribe(last::set);

        job.recordSuccess(List.of("p1"));
        assertThat(last.get().deleted()).isEqualTo(1);

        job.recordSuccess(List.of("p2"));
        assertThat(last.get().deleted()).isEqualTo(2);
    }

    @Test
    void getPhotoIds_returnsImmutableCopy() {
        DeletionJob job = new DeletionJob("j1", "acc1", "ICLOUD", List.of("p1", "p2"));

        List<String> ids = job.getPhotoIds();

        assertThat(ids).containsExactly("p1", "p2");
    }

    // --- addPhotoIds ---

    @Test
    void addPhotoIds_increasesTotal() {
        DeletionJob job = new DeletionJob("j1", "acc1", "ICLOUD", List.of("p1"));

        job.addPhotoIds(List.of("p2", "p3"));

        assertThat(job.getTotal()).isEqualTo(3);
    }

    @Test
    void addPhotoIds_appendsToTrackedIds() {
        DeletionJob job = new DeletionJob("j1", "acc1", "ICLOUD", List.of("p1"));

        job.addPhotoIds(List.of("p2"));

        assertThat(job.getPhotoIds()).containsExactlyInAnyOrder("p1", "p2");
    }

    // --- requeueRetriable ---

    @Test
    void requeueRetriable_firstFailure_returnsEmptyPermanentList() {
        DeletionJob job = new DeletionJob("j1", "acc1", "ICLOUD", List.of("p1"));

        List<Photo> permanent = job.requeueRetriable(List.of(photo("p1")));

        assertThat(permanent).isEmpty();
    }

    @Test
    void requeueRetriable_secondFailure_returnsEmptyPermanentList() {
        DeletionJob job = new DeletionJob("j1", "acc1", "ICLOUD", List.of("p1"));
        Photo p = photo("p1");

        job.requeueRetriable(List.of(p));
        List<Photo> permanent = job.requeueRetriable(List.of(p));

        assertThat(permanent).isEmpty();
    }

    @Test
    void requeueRetriable_thirdFailure_returnsPermanentList() {
        DeletionJob job = new DeletionJob("j1", "acc1", "ICLOUD", List.of("p1"));
        Photo p = photo("p1");

        job.requeueRetriable(List.of(p));
        job.requeueRetriable(List.of(p));
        List<Photo> permanent = job.requeueRetriable(List.of(p));

        assertThat(permanent).containsExactly(p);
    }

    @Test
    void requeueRetriable_photoIsAvailableInQueueAfterRequeue() throws InterruptedException {
        DeletionJob job = new DeletionJob("j1", "acc1", "ICLOUD", List.of("p1"));
        Photo p = photo("p1");

        job.requeueRetriable(List.of(p));

        List<Photo> batch = job.pollBatch(10, 100);
        assertThat(batch).containsExactly(p);
    }

    @Test
    void requeueRetriable_doesNotIncrementFailedOrDeletedCount() {
        DeletionJob job = new DeletionJob("j1", "acc1", "ICLOUD", List.of("p1"));

        job.requeueRetriable(List.of(photo("p1")));
        job.requeueRetriable(List.of(photo("p1")));

        assertThat(job.getFailed()).isEqualTo(0);
        assertThat(job.getDeleted()).isEqualTo(0);
    }

    @Test
    void requeueRetriable_onlyExhaustedPhotosReturnedAsPermanent() {
        DeletionJob job = new DeletionJob("j1", "acc1", "ICLOUD", List.of("p1", "p2"));
        Photo p1 = photo("p1");
        Photo p2 = photo("p2");

        // exhaust p1 to attempt 2, leave p2 untouched
        job.requeueRetriable(List.of(p1));
        job.requeueRetriable(List.of(p1));

        // third call: p1 at attempt 3 → permanent; p2 at attempt 1 → re-queued
        List<Photo> permanent = job.requeueRetriable(List.of(p1, p2));

        assertThat(permanent).containsExactly(p1);
    }

    // --- helpers ---

    private static Photo photo(String id) {
        Photo p = new Photo();
        p.setId(id);
        return p;
    }
}
