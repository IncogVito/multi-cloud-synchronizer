package com.cloudsync.service;

import com.cloudsync.model.dto.IPhoneRepairProgress;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

public class IPhoneRepairJob {

    private final String id;
    private volatile int total = 0;
    private final Instant createdAt = Instant.now();
    private final AtomicInteger checked = new AtomicInteger(0);
    private final AtomicInteger newPending = new AtomicInteger(0);
    private final AtomicInteger missingFixed = new AtomicInteger(0);
    private volatile boolean done = false;
    private volatile boolean cancelled = false;

    private final Sinks.Many<IPhoneRepairProgress> sink = Sinks.many().replay().limit(1);

    IPhoneRepairJob(String id) {
        this.id = id;
    }

    void setTotal(int total) {
        this.total = total;
    }

    void onFileChecked(boolean isNew, boolean wasMissing) {
        if (isNew) newPending.incrementAndGet();
        if (wasMissing) missingFixed.incrementAndGet();
        int n = checked.incrementAndGet();
        sink.tryEmitNext(new IPhoneRepairProgress(n, total, newPending.get(), missingFixed.get(), false));
    }

    void markDone() {
        done = true;
        sink.tryEmitNext(new IPhoneRepairProgress(checked.get(), total, newPending.get(), missingFixed.get(), true));
        sink.tryEmitComplete();
    }

    void cancel() { cancelled = true; }

    public boolean isCancelled() { return cancelled; }
    public boolean isDone() { return done; }
    public String getId() { return id; }
    public Instant getCreatedAt() { return createdAt; }

    public IPhoneRepairProgress currentProgress() {
        return new IPhoneRepairProgress(checked.get(), total, newPending.get(), missingFixed.get(), done);
    }

    public Flux<IPhoneRepairProgress> subscribe() {
        return sink.asFlux();
    }
}
