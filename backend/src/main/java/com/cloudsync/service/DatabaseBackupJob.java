package com.cloudsync.service;

import com.cloudsync.model.dto.DatabaseBackupProgress;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Instant;

public class DatabaseBackupJob {

    private final String id;
    private final Instant createdAt = Instant.now();
    private volatile boolean done = false;
    private volatile DatabaseBackupProgress lastProgress = new DatabaseBackupProgress(false, null, null);

    private final Sinks.Many<DatabaseBackupProgress> sink = Sinks.many().replay().limit(1);

    DatabaseBackupJob(String id) {
        this.id = id;
    }

    void markDone(String backupPath) {
        done = true;
        DatabaseBackupProgress p = new DatabaseBackupProgress(true, backupPath, null);
        lastProgress = p;
        sink.tryEmitNext(p);
        sink.tryEmitComplete();
    }

    void markFailed(String error) {
        done = true;
        DatabaseBackupProgress p = new DatabaseBackupProgress(true, null, error);
        lastProgress = p;
        sink.tryEmitNext(p);
        sink.tryEmitComplete();
    }

    public boolean isDone() { return done; }
    public String getId() { return id; }
    public Instant getCreatedAt() { return createdAt; }
    public DatabaseBackupProgress currentProgress() { return lastProgress; }

    public Flux<DatabaseBackupProgress> subscribe() {
        return sink.asFlux();
    }
}
