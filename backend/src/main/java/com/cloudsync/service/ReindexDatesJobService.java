package com.cloudsync.service;

import com.cloudsync.model.dto.JobSummary;
import com.cloudsync.model.dto.ReindexDatesProgress;
import com.cloudsync.model.dto.ReindexDatesResult;
import com.cloudsync.model.entity.ICloudAccount;
import com.cloudsync.model.entity.Photo;
import com.cloudsync.repository.AccountRepository;
import com.cloudsync.repository.PhotoRepository;
import com.cloudsync.util.ExifDateUtil;
import com.cloudsync.util.MediaPathUtil;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "Reindex dates" task. For every photo on disk belonging to an account it re-reads the real
 * capture date from the file's metadata (via {@link ExifDateUtil}). If the stored date matches,
 * the photo is left alone; if it differs, the stored date is corrected and — when the file no
 * longer sits in the {@code yyyy/MM} folder matching its real date — it is moved into place using
 * the same {@link MediaPathUtil} logic the sync/reorganize flow uses.
 */
@Singleton
public class ReindexDatesJobService {

    private static final Logger LOG = LoggerFactory.getLogger(ReindexDatesJobService.class);

    private final PhotoRepository photoRepository;
    private final AccountRepository accountRepository;
    private final TaskHistoryService taskHistoryService;
    private final ConcurrentHashMap<String, ReindexDatesJob> jobs = new ConcurrentHashMap<>();

    public ReindexDatesJobService(PhotoRepository photoRepository,
                                  AccountRepository accountRepository,
                                  TaskHistoryService taskHistoryService) {
        this.photoRepository = photoRepository;
        this.accountRepository = accountRepository;
        this.taskHistoryService = taskHistoryService;
    }

    public ReindexDatesResult startJob(String accountId) {
        ICloudAccount account = requireAccount(accountId);
        ReindexDatesJob job = new ReindexDatesJob(UUID.randomUUID().toString());
        jobs.put(job.getId(), job);
        taskHistoryService.createTask(job.getId(), "REINDEX_DATES", accountId, null, 0);
        Thread.ofVirtual().name("reindex-dates-" + job.getId()).start(() -> runJob(job, account));
        return new ReindexDatesResult(job.getId(), job.currentProgress());
    }

    public Optional<ReindexDatesJob> getJob(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    public List<JobSummary> allJobSummaries() {
        return jobs.values().stream()
                .map(j -> {
                    ReindexDatesProgress p = j.currentProgress();
                    return new JobSummary(
                            j.getId(),
                            "REINDEX_DATES",
                            j.isDone() ? "COMPLETED" : j.isCancelled() ? "CANCELLED" : "RUNNING",
                            p.total(),
                            p.checked(),
                            p.errors(),
                            "Reindexing capture dates");
                })
                .toList();
    }

    @Scheduled(fixedDelay = "1h", initialDelay = "1h")
    void cleanupOldJobs() {
        Instant cutoff = Instant.now().minus(1, ChronoUnit.HOURS);
        int removed = 0;
        for (var it = jobs.entrySet().iterator(); it.hasNext(); ) {
            ReindexDatesJob job = it.next().getValue();
            if (job.isDone() && job.getCreatedAt().isBefore(cutoff)) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) LOG.debug("Cleaned up {} completed reindex-dates jobs", removed);
    }

    private void runJob(ReindexDatesJob job, ICloudAccount account) {
        String basePath = account.getSyncFolderPath();
        try {
            List<Photo> candidates = photoRepository
                    .findByAccountIdAndSyncedToDisk(account.getId(), true).stream()
                    .filter(p -> p.getFilePath() != null)
                    .toList();
            job.setTotal(candidates.size());

            for (Photo photo : candidates) {
                if (job.isCancelled()) break;
                processPhoto(job, photo, basePath);
            }
        } catch (Exception e) {
            LOG.error("Reindex-dates job {} failed: {}", job.getId(), e.getMessage(), e);
            job.markDone();
            ReindexDatesProgress p = job.currentProgress();
            taskHistoryService.completeTask(job.getId(), "FAILED", p.updated(), p.errors(), e.getMessage());
            return;
        }

        job.markDone();
        ReindexDatesProgress p = job.currentProgress();
        String status = job.isCancelled() ? "CANCELLED" : "COMPLETED";
        taskHistoryService.completeTask(job.getId(), status, p.updated(), p.errors(), null);
        LOG.info("Reindex-dates job {} done: checked={} updated={} moved={} errors={}",
                job.getId(), p.checked(), p.updated(), p.moved(), p.errors());
    }

    private void processPhoto(ReindexDatesJob job, Photo photo, String basePath) {
        Path currentPath = Path.of(photo.getFilePath());
        if (!Files.exists(currentPath)) {
            // File not on disk right now — can't verify its real date, skip without touching it.
            job.onPhotoOk();
            return;
        }

        Instant metadataDate = ExifDateUtil.readCaptureDate(currentPath, null);
        if (metadataDate == null) {
            // No reliable capture date in the file — leave the stored date as-is.
            job.onPhotoOk();
            return;
        }

        boolean dateChanged = !metadataDate.equals(photo.getCreatedDate());
        Path targetDir = MediaPathUtil.resolveDateDir(basePath, metadataDate);
        boolean needsMove = currentPath.getParent() == null
                || !currentPath.getParent().equals(targetDir);

        if (!dateChanged && !needsMove) {
            job.onPhotoOk();
            return;
        }

        try {
            boolean movedFile = false;
            if (needsMove) {
                Path destPath = uniqueDestination(targetDir, currentPath.getFileName().toString());
                Files.createDirectories(targetDir);
                Files.move(currentPath, destPath);
                photo.setFilePath(destPath.toString());
                movedFile = true;
            }
            if (dateChanged) {
                photo.setCreatedDate(metadataDate);
            }
            photoRepository.update(photo);
            job.onPhotoUpdated(movedFile);
        } catch (Exception e) {
            LOG.warn("Reindex-dates: failed for photo {} ({}): {}",
                    photo.getId(), photo.getFilePath(), e.getMessage());
            job.onPhotoError();
        }
    }

    /** Avoids clobbering an existing file at the destination (no overwrite, no delete). */
    private Path uniqueDestination(Path dir, String filename) {
        Path candidate = dir.resolve(filename);
        if (!Files.exists(candidate)) return candidate;

        int dot = filename.lastIndexOf('.');
        String base = dot >= 0 ? filename.substring(0, dot) : filename;
        String ext = dot >= 0 ? filename.substring(dot) : "";
        for (int i = 1; ; i++) {
            Path next = dir.resolve(base + "_" + i + ext);
            if (!Files.exists(next)) return next;
        }
    }

    private ICloudAccount requireAccount(String accountId) {
        return accountRepository.findById(accountId)
                .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, "Account not found: " + accountId));
    }
}
