package com.cloudsync.service;

import com.cloudsync.model.dto.ThumbnailJobResponse;
import com.cloudsync.model.entity.Photo;
import com.cloudsync.repository.PhotoRepository;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

@Singleton
public class ThumbnailJobService {

    private static final Logger LOG = LoggerFactory.getLogger(ThumbnailJobService.class);

    private final ThumbnailService thumbnailService;
    private final PhotoRepository photoRepository;
    private final TaskHistoryService taskHistoryService;
    private final ExecutorService thumbnailExecutor;
    private final ConcurrentHashMap<String, ThumbnailJob> jobs = new ConcurrentHashMap<>();

    public ThumbnailJobService(ThumbnailService thumbnailService,
                               PhotoRepository photoRepository,
                               TaskHistoryService taskHistoryService,
                               @Named("thumbnailExecutor") ExecutorService thumbnailExecutor) {
        this.thumbnailService = thumbnailService;
        this.photoRepository = photoRepository;
        this.taskHistoryService = taskHistoryService;
        this.thumbnailExecutor = thumbnailExecutor;
    }

    public ThumbnailJobResponse startJob(String storageDeviceId, List<String> photoIds) {
        List<Photo> candidates;
        if (photoIds != null && !photoIds.isEmpty()) {
            candidates = photoRepository.findByIdIn(photoIds);
        } else {
            candidates = thumbnailService.findCandidates(storageDeviceId);
        }

        ThumbnailJob job = new ThumbnailJob(UUID.randomUUID().toString(), candidates.size());
        jobs.put(job.getId(), job);

        taskHistoryService.createTask(job.getId(), "THUMBNAIL", null, null, candidates.size());

        Thread.ofVirtual().start(() -> runJob(job, candidates));

        return new ThumbnailJobResponse(job.getId(), job.currentProgress());
    }

    public Optional<ThumbnailJob> getJob(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    public boolean cancelJob(String jobId) {
        ThumbnailJob job = jobs.remove(jobId);
        if (job == null) return false;
        job.cancel();
        return true;
    }
// FIX: use constants
    public List<com.cloudsync.model.dto.JobSummary> allJobSummaries() {
        return jobs.values().stream()
                .map(j -> new com.cloudsync.model.dto.JobSummary(
                        j.getId(),
                        "THUMBNAIL",
                        j.isDone() ? "COMPLETED" : j.isCancelled() ? "CANCELLED" : "RUNNING",
                        j.currentProgress().total(),
                        j.currentProgress().processed(),
                        j.currentProgress().errors(),
                        "Generating thumbnails"))
                .toList();
    }

    @Scheduled(fixedDelay = "1h", initialDelay = "1h")
    void cleanupOldJobs() {
        Instant cutoff = Instant.now().minus(1, ChronoUnit.HOURS);
        int removed = 0;
        for (var it = jobs.entrySet().iterator(); it.hasNext(); ) {
            ThumbnailJob job = it.next().getValue();
            if (job.isDone() && job.getCreatedAt().isBefore(cutoff)) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) LOG.debug("Cleaned up {} completed thumbnail jobs", removed);
    }

    private void runJob(ThumbnailJob job, List<Photo> candidates) {
        try {
            List<CompletableFuture<Void>> futures = candidates.stream().map(photo ->
                CompletableFuture.runAsync(() -> {
                    if (job.isCancelled()) return;
                    boolean error = false;
                    try {
                        thumbnailService.generateThumbnail(photo);
                    } catch (Exception e) {
                        LOG.warn("Thumbnail failed for photo {}: {}", photo.getId(), e.getMessage());
                        error = true;
                    }
                    job.onPhotoProcessed(error);
                }, thumbnailExecutor)
            ).toList();
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } finally {
            job.markDone();
            String status = job.isCancelled() ? "CANCELLED" : "COMPLETED";
            taskHistoryService.completeTask(job.getId(), status,
                    job.currentProgress().processed() - job.currentProgress().errors(),
                    job.currentProgress().errors(), null);
        }
    }
}
