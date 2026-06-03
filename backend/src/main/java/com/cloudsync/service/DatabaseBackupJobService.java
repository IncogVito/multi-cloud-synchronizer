package com.cloudsync.service;

import com.cloudsync.model.dto.DatabaseBackupResult;
import io.micronaut.context.annotation.Value;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Singleton
public class DatabaseBackupJobService {

    private static final Logger LOG = LoggerFactory.getLogger(DatabaseBackupJobService.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("ddMMyyyy");

    private final DatabaseBackupExecutor backupExecutor;
    private final String externalDrivePath;
    private final TaskHistoryService taskHistoryService;
    private final ConcurrentHashMap<String, DatabaseBackupJob> jobs = new ConcurrentHashMap<>();

    public DatabaseBackupJobService(DatabaseBackupExecutor backupExecutor,
                                    @Value("${app.external-drive-path}") String externalDrivePath,
                                    TaskHistoryService taskHistoryService) {
        this.backupExecutor = backupExecutor;
        this.externalDrivePath = externalDrivePath;
        this.taskHistoryService = taskHistoryService;
    }

    public DatabaseBackupResult startJob() {
        DatabaseBackupJob job = new DatabaseBackupJob(UUID.randomUUID().toString());
        jobs.put(job.getId(), job);
        taskHistoryService.createTask(job.getId(), "DB_BACKUP", null, null, 0);
        Thread.ofVirtual().name("db-backup-" + job.getId()).start(() -> runBackup(job));
        return new DatabaseBackupResult(job.getId(), job.currentProgress());
    }

    public Optional<DatabaseBackupJob> getJob(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    @Scheduled(fixedDelay = "1h", initialDelay = "1h")
    void cleanupOldJobs() {
        Instant cutoff = Instant.now().minus(1, ChronoUnit.HOURS);
        jobs.entrySet().removeIf(e -> e.getValue().isDone() && e.getValue().getCreatedAt().isBefore(cutoff));
    }

    private void runBackup(DatabaseBackupJob job) {
        String date = LocalDate.now().format(DATE_FMT);
        Path backupDir = Path.of(externalDrivePath, "backups");
        Path backupFile = backupDir.resolve("backup_" + date + ".db");

        try {
            Files.createDirectories(backupDir);
            // VACUUM INTO fails if dest exists — remove first
            Files.deleteIfExists(backupFile);

            backupExecutor.vacuum(backupFile);

            job.markDone(backupFile.toString());
            taskHistoryService.completeTask(job.getId(), "COMPLETED", 1, 0, null);
            LOG.info("Database backup completed: {}", backupFile);
        } catch (Exception e) {
            LOG.error("Database backup failed for job {}", job.getId(), e);
            job.markFailed(e.getMessage());
            taskHistoryService.completeTask(job.getId(), "FAILED", 0, 1, e.getMessage());
        }
    }
}
