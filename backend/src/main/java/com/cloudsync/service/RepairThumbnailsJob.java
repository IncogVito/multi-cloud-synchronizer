package com.cloudsync.service;

import com.cloudsync.model.dto.RepairProgress;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

public class RepairThumbnailsJob {

    private final String id;
    private final int total;
    private final Instant createdAt = Instant.now();
    private final AtomicInteger checked = new AtomicInteger(0);
    private final AtomicInteger fixed = new AtomicInteger(0);
    private volatile boolean done = false;
    private volatile boolean cancelled = false;

    private final Sinks.Many<RepairProgress> sink = Sinks.many().replay().limit(1);

    RepairThumbnailsJob(String id, int total) {
        this.id = id;
        this.total = total;
    }

    void onPhotoChecked(boolean wasBroken) {
        if (wasBroken) fixed.incrementAndGet();
        int n = checked.incrementAndGet();
        sink.tryEmitNext(new RepairProgress(n, fixed.get(), total, false));
    }

    void markDone() {
        done = true;
        sink.tryEmitNext(new RepairProgress(checked.get(), fixed.get(), total, true));
        sink.tryEmitComplete();
    }

    void cancel() { cancelled = true; }

    public boolean isCancelled() { return cancelled; }
    public boolean isDone() { return done; }
    public String getId() { return id; }
    public Instant getCreatedAt() { return createdAt; }

    public RepairProgress currentProgress() {
        return new RepairProgress(checked.get(), fixed.get(), total, done);
    }

    public Flux<RepairProgress> subscribe() {
        return sink.asFlux();
    }
}
