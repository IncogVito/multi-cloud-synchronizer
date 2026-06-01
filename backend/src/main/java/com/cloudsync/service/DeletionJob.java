package com.cloudsync.service;

import com.cloudsync.model.dto.DeletionProgress;
import com.cloudsync.model.entity.Photo;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class DeletionJob {

    private final String jobId;
    private final String accountId;
    private final String provider;
    private final List<String> photoIds = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger total = new AtomicInteger(0);
    private final AtomicInteger deleted = new AtomicInteger(0);
    private final AtomicInteger failed = new AtomicInteger(0);
    private final List<String> successfulIds = Collections.synchronizedList(new ArrayList<>());
    private final List<String> failedIds = Collections.synchronizedList(new ArrayList<>());
    private final Instant createdAt = Instant.now();
    private volatile String status = "RUNNING";

    private final LinkedBlockingQueue<Photo> pendingQueue = new LinkedBlockingQueue<>();
    private final Sinks.Many<DeletionProgress> sink = Sinks.many().replay().all();

    public DeletionJob(String jobId, String accountId, String provider, List<String> initialPhotoIds) {
        this.jobId = jobId;
        this.accountId = accountId;
        this.provider = provider;
        this.photoIds.addAll(initialPhotoIds);
        this.total.set(initialPhotoIds.size());
    }

    void addPhotoIds(List<String> ids) {
        photoIds.addAll(ids);
        total.addAndGet(ids.size());
    }

    void enqueuePhotos(List<Photo> photos) {
        pendingQueue.addAll(photos);
    }

    void mergePhotos(List<Photo> photos) {
        addPhotoIds(photos.stream().map(Photo::getId).toList());
        pendingQueue.addAll(photos);
    }

    List<Photo> pollBatch(int maxSize, long timeoutMs) throws InterruptedException {
        List<Photo> batch = new ArrayList<>();
        Photo first = pendingQueue.poll(timeoutMs, TimeUnit.MILLISECONDS);
        if (first == null) return batch;
        batch.add(first);
        pendingQueue.drainTo(batch, maxSize - 1);
        return batch;
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
        sink.tryEmitNext(new DeletionProgress(deleted.get(), total.get(), failed.get(), true,
                List.copyOf(successfulIds), List.copyOf(failedIds)));
        sink.tryEmitComplete();
    }

    void markFailed() {
        status = "FAILED";
        sink.tryEmitNext(new DeletionProgress(deleted.get(), total.get(), failed.get(), true,
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
    public List<String> getPhotoIds() { return List.copyOf(photoIds); }
    public int getTotal() { return total.get(); }
    public int getDeleted() { return deleted.get(); }
    public int getFailed() { return failed.get(); }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }

    public DeletionProgress currentProgress() {
        return new DeletionProgress(deleted.get(), total.get(), failed.get(), isDone(),
                List.copyOf(successfulIds), List.copyOf(failedIds));
    }

    public Flux<DeletionProgress> subscribe() {
        return sink.asFlux();
    }

    private void emitProgress(boolean done) {
        sink.tryEmitNext(new DeletionProgress(deleted.get(), total.get(), failed.get(), done,
                List.copyOf(successfulIds), List.copyOf(failedIds)));
    }
}
