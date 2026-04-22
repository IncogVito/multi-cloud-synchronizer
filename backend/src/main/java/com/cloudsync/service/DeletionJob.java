package com.cloudsync.service;

import com.cloudsync.model.dto.DeletionProgress;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class DeletionJob {

    private final String jobId;
    private final String accountId;
    private final String provider;
    private final List<String> photoIds;
    private final int total;
    private final AtomicInteger deleted = new AtomicInteger(0);
    private final AtomicInteger failed = new AtomicInteger(0);
    private final List<String> successfulIds = Collections.synchronizedList(new ArrayList<>());
    private final List<String> failedIds = Collections.synchronizedList(new ArrayList<>());
    private final Instant createdAt = Instant.now();
    private volatile String status = "RUNNING";

    private final Sinks.Many<DeletionProgress> sink = Sinks.many().replay().limit(1);

    public DeletionJob(String jobId, String accountId, String provider, List<String> photoIds) {
        this.jobId = jobId;
        this.accountId = accountId;
        this.provider = provider;
        this.photoIds = List.copyOf(photoIds);
        this.total = photoIds.size();
    }

    void recordSuccess(List<String> ids) {
        successfulIds.addAll(ids);
        deleted.addAndGet(ids.size());
        emitProgress(false);
    }

    void recordFailure(List<String> ids) {
        failedIds.addAll(ids);
        failed.addAndGet(ids.size());
        emitProgress(false);
    }

// FIX: Status extract to enums. use mthod to gather emitNext arg.
    void markDone() {
        status = "COMPLETED";
        sink.tryEmitNext(new DeletionProgress(deleted.get(), total, failed.get(), true,
                List.copyOf(successfulIds), List.copyOf(failedIds)));
        sink.tryEmitComplete();
    }

    void markFailed() {
        status = "FAILED";
        sink.tryEmitNext(new DeletionProgress(deleted.get(), total, failed.get(), true,
                List.copyOf(successfulIds), List.copyOf(failedIds)));
        sink.tryEmitComplete();
    }

    void cancel() {
        status = "CANCELLED";
    }

    public boolean isCancelled() {
        return "CANCELLED".equals(status);
    }

    public boolean isDone() {
        return "COMPLETED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status);
    }

    public String getJobId() { return jobId; }
    public String getAccountId() { return accountId; }
    public String getProvider() { return provider; }
    public List<String> getPhotoIds() { return photoIds; }
    public int getTotal() { return total; }
    public int getDeleted() { return deleted.get(); }
    public int getFailed() { return failed.get(); }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }

    public DeletionProgress currentProgress() {
        return new DeletionProgress(deleted.get(), total, failed.get(), isDone(),
                List.copyOf(successfulIds), List.copyOf(failedIds));
    }

    public Flux<DeletionProgress> subscribe() {
        return sink.asFlux();
    }

    private void emitProgress(boolean done) {
        sink.tryEmitNext(new DeletionProgress(deleted.get(), total, failed.get(), done,
                List.copyOf(successfulIds), List.copyOf(failedIds)));
    }
}
