package com.cloudsync.service;

import com.cloudsync.model.dto.ThumbnailProgress;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

public class ThumbnailJob {

    private final String id;
    private final int total;
    private final Instant createdAt = Instant.now();
    private final AtomicInteger processed = new AtomicInteger(0);
    private final AtomicInteger errors = new AtomicInteger(0);
    private volatile boolean done = false;
    private volatile boolean cancelled = false;

    // replay().all() so reconnecting clients receive full history and catch up instantly
    private final Sinks.Many<ThumbnailProgress> sink = Sinks.many().replay().all();

    ThumbnailJob(String id, int total) {
        this.id = id;
        this.total = total;
    }

    void onPhotoProcessed(boolean error) {
        if (error) errors.incrementAndGet();
        int n = processed.incrementAndGet();
        sink.tryEmitNext(new ThumbnailProgress(n, total, false, errors.get()));
    }

    void markDone() {
        done = true;
        sink.tryEmitNext(new ThumbnailProgress(processed.get(), total, true, errors.get()));
        sink.tryEmitComplete();
    }

    void cancel() { cancelled = true; }

    public boolean isCancelled() { return cancelled; }
    public boolean isDone() { return done; }
    public String getId() { return id; }
    public Instant getCreatedAt() { return createdAt; }

    public ThumbnailProgress currentProgress() {
        return new ThumbnailProgress(processed.get(), total, done, errors.get());
    }

    public Flux<ThumbnailProgress> subscribe() {
        return sink.asFlux();
    }
}
