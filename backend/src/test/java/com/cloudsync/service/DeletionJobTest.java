package com.cloudsync.service;

import com.cloudsync.model.dto.DeletionProgress;
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
}
