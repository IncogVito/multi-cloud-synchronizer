package com.cloudsync.service;

import com.cloudsync.model.dto.MergeDuplicatesProgress;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

public class MergeDuplicatesJob {

    private final String id;
    private volatile int total = 0;
    private final Instant createdAt = Instant.now();
    private final AtomicInteger checked = new AtomicInteger(0);
    private final AtomicInteger merged = new AtomicInteger(0);
    private final AtomicInteger deleted = new AtomicInteger(0);
    private volatile boolean done = false;
    private volatile boolean cancelled = false;

    private final Sinks.Many<MergeDuplicatesProgress> sink = Sinks.many().replay().limit(1);

    MergeDuplicatesJob(String id) {
        this.id = id;
    }

    void setTotal(int total) {
        this.total = total;
    }

    void onGroupProcessed(int deletedCount) {
        merged.incrementAndGet();
        deleted.addAndGet(deletedCount);
        int n = checked.addAndGet(1 + deletedCount);
        sink.tryEmitNext(new MergeDuplicatesProgress(n, total, merged.get(), deleted.get(), false));
    }

    void onGroupSkipped() {
        int n = checked.incrementAndGet();
        sink.tryEmitNext(new MergeDuplicatesProgress(n, total, merged.get(), deleted.get(), false));
    }

    void markDone() {
        done = true;
        sink.tryEmitNext(new MergeDuplicatesProgress(checked.get(), total, merged.get(), deleted.get(), true));
        sink.tryEmitComplete();
    }

    void cancel() { cancelled = true; }

    public boolean isCancelled() { return cancelled; }
    public boolean isDone() { return done; }
    public String getId() { return id; }
    public Instant getCreatedAt() { return createdAt; }

    public MergeDuplicatesProgress currentProgress() {
        return new MergeDuplicatesProgress(checked.get(), total, merged.get(), deleted.get(), done);
    }

    public Flux<MergeDuplicatesProgress> subscribe() {
        return sink.asFlux();
    }
}
