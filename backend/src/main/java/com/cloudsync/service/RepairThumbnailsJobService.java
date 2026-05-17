package com.cloudsync.service;

import com.cloudsync.model.dto.RepairThumbnailsResult;
import com.cloudsync.model.entity.Photo;
import com.cloudsync.repository.PhotoRepository;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

@Singleton
public class RepairThumbnailsJobService {

    private static final Logger LOG = LoggerFactory.getLogger(RepairThumbnailsJobService.class);

    private final PhotoRepository photoRepository;
    private final TaskHistoryService taskHistoryService;
    private final ExecutorService executor;
    private final ConcurrentHashMap<String, RepairThumbnailsJob> jobs = new ConcurrentHashMap<>();

    public RepairThumbnailsJobService(PhotoRepository photoRepository,
                                      TaskHistoryService taskHistoryService,
                                      @Named("thumbnailExecutor") ExecutorService executor) {
        this.photoRepository = photoRepository;
        this.taskHistoryService = taskHistoryService;
        this.executor = executor;
    }

    public RepairThumbnailsResult startJob() {
        List<Photo> candidates = photoRepository.findAllWithThumbnailPath();

        RepairThumbnailsJob job = new RepairThumbnailsJob(UUID.randomUUID().toString(), candidates.size());
        jobs.put(job.getId(), job);

        taskHistoryService.createTask(job.getId(), "REPAIR_THUMBNAILS", null, null, candidates.size());

        Thread.ofVirtual().start(() -> runJob(job, candidates));

        return new RepairThumbnailsResult(job.getId(), job.currentProgress());
    }

    public Optional<RepairThumbnailsJob> getJob(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    @Scheduled(fixedDelay = "1h", initialDelay = "1h")
    void cleanupOldJobs() {
        Instant cutoff = Instant.now().minus(1, ChronoUnit.HOURS);
        int removed = 0;
        for (var it = jobs.entrySet().iterator(); it.hasNext(); ) {
            RepairThumbnailsJob job = it.next().getValue();
            if (job.isDone() && job.getCreatedAt().isBefore(cutoff)) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) LOG.debug("Cleaned up {} completed repair jobs", removed);
    }

    private void runJob(RepairThumbnailsJob job, List<Photo> candidates) {
        try {
            List<CompletableFuture<Void>> futures = candidates.stream().map(photo ->
                CompletableFuture.runAsync(() -> {
                    if (job.isCancelled()) return;
                    boolean broken = isBroken(photo.getThumbnailPath());
                    if (broken) {
                        tryDeleteFile(photo.getThumbnailPath());
                        photo.setThumbnailPath(null);
                        photoRepository.update(photo);
                    }
                    job.onPhotoChecked(broken);
                }, executor)
            ).toList();
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } finally {
            job.markDone();
            var p = job.currentProgress();
            taskHistoryService.completeTask(job.getId(), "COMPLETED", p.fixed(), p.checked() - p.fixed(), null);
            LOG.info("Repair job {} done: checked={} fixed={}", job.getId(), p.checked(), p.fixed());
        }
    }

    private boolean isBroken(String thumbnailPath) {
        if (thumbnailPath == null || thumbnailPath.isBlank()) return false;
        try {
            Path path = Path.of(thumbnailPath);
            return !Files.exists(path) || Files.size(path) == 0;
        } catch (IOException e) {
            return true;
        }
    }

    private void tryDeleteFile(String thumbnailPath) {
        if (thumbnailPath == null) return;
        try {
            Files.deleteIfExists(Path.of(thumbnailPath));
        } catch (IOException ignored) {}
    }
}
