package com.cloudsync.service;

import com.cloudsync.model.dto.OrphanPhotosProgress;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

public class OrphanPhotosJob {

    private final String id;
    private volatile int total = 0;
    private final Instant createdAt = Instant.now();
    private final AtomicInteger processed = new AtomicInteger(0);
    private final AtomicInteger assigned = new AtomicInteger(0);
    private volatile boolean done = false;
    private volatile boolean cancelled = false;

    private final Sinks.Many<OrphanPhotosProgress> sink = Sinks.many().replay().limit(1);

    OrphanPhotosJob(String id) {
        this.id = id;
    }

    void setTotal(int total) {
        this.total = total;
    }

    void onAssigned() {
        assigned.incrementAndGet();
        int n = processed.incrementAndGet();
        sink.tryEmitNext(new OrphanPhotosProgress(n, total, assigned.get(), false));
    }

    void markDone() {
        done = true;
        sink.tryEmitNext(new OrphanPhotosProgress(processed.get(), total, assigned.get(), true));
        sink.tryEmitComplete();
    }

    void cancel() { cancelled = true; }

    public boolean isCancelled() { return cancelled; }
    public boolean isDone() { return done; }
    public String getId() { return id; }
    public Instant getCreatedAt() { return createdAt; }

    public OrphanPhotosProgress currentProgress() {
        return new OrphanPhotosProgress(processed.get(), total, assigned.get(), done);
    }

    public Flux<OrphanPhotosProgress> subscribe() {
        return sink.asFlux();
    }
}
