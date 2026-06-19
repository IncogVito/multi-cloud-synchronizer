package com.cloudsync.service;

import com.cloudsync.model.dto.ReindexDatesProgress;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

public class ReindexDatesJob {

    private final String id;
    private volatile int total = 0;
    private final Instant createdAt = Instant.now();
    private final AtomicInteger checked = new AtomicInteger(0);
    private final AtomicInteger updated = new AtomicInteger(0);
    private final AtomicInteger moved = new AtomicInteger(0);
    private final AtomicInteger errors = new AtomicInteger(0);
    private volatile boolean done = false;
    private volatile boolean cancelled = false;

    private final Sinks.Many<ReindexDatesProgress> sink = Sinks.many().replay().limit(1);

    ReindexDatesJob(String id) {
        this.id = id;
    }

    void setTotal(int total) {
        this.total = total;
    }

    /** A photo whose stored date already matched its metadata — nothing to do. */
    void onPhotoOk() {
        checked.incrementAndGet();
        emit(false);
    }

    /** Stored date was corrected; {@code movedFile} indicates the file was relocated on disk too. */
    void onPhotoUpdated(boolean movedFile) {
        checked.incrementAndGet();
        updated.incrementAndGet();
        if (movedFile) moved.incrementAndGet();
        emit(false);
    }

    void onPhotoError() {
        checked.incrementAndGet();
        errors.incrementAndGet();
        emit(false);
    }

    void markDone() {
        done = true;
        emit(true);
        sink.tryEmitComplete();
    }

    void cancel() { cancelled = true; }

    public boolean isCancelled() { return cancelled; }
    public boolean isDone() { return done; }
    public String getId() { return id; }
    public Instant getCreatedAt() { return createdAt; }

    public ReindexDatesProgress currentProgress() {
        return new ReindexDatesProgress(checked.get(), total, updated.get(), moved.get(), errors.get(), done);
    }

    public Flux<ReindexDatesProgress> subscribe() {
        return sink.asFlux();
    }

    private void emit(boolean isDone) {
        sink.tryEmitNext(new ReindexDatesProgress(checked.get(), total, updated.get(), moved.get(), errors.get(), isDone));
    }
}
