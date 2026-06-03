package com.cloudsync.service;

import com.cloudsync.model.dto.MergeDuplicatesProgress;
import com.cloudsync.model.dto.MergeDuplicatesResult;
import com.cloudsync.model.entity.Photo;
import com.cloudsync.repository.PhotoRepository;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Singleton
public class MergeDuplicatesJobService {

    private static final Logger LOG = LoggerFactory.getLogger(MergeDuplicatesJobService.class);

    private final PhotoRepository photoRepository;
    private final TaskHistoryService taskHistoryService;
    private final ConcurrentHashMap<String, MergeDuplicatesJob> jobs = new ConcurrentHashMap<>();

    public MergeDuplicatesJobService(PhotoRepository photoRepository, TaskHistoryService taskHistoryService) {
        this.photoRepository = photoRepository;
        this.taskHistoryService = taskHistoryService;
    }

    public MergeDuplicatesResult startJob(String accountId) {
        MergeDuplicatesJob job = new MergeDuplicatesJob(UUID.randomUUID().toString());
        jobs.put(job.getId(), job);
        taskHistoryService.createTask(job.getId(), "MERGE_DUPLICATES", accountId, null, 0);
        Thread.ofVirtual().name("merge-duplicates-" + job.getId()).start(() -> runJob(job, accountId));
        return new MergeDuplicatesResult(job.getId(), job.currentProgress());
    }

    public Optional<MergeDuplicatesJob> getJob(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    @Scheduled(fixedDelay = "1h", initialDelay = "1h")
    void cleanupOldJobs() {
        Instant cutoff = Instant.now().minus(1, ChronoUnit.HOURS);
        int removed = 0;
        for (var it = jobs.entrySet().iterator(); it.hasNext(); ) {
            MergeDuplicatesJob job = it.next().getValue();
            if (job.isDone() && job.getCreatedAt().isBefore(cutoff)) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) LOG.debug("Cleaned up {} completed merge-duplicates jobs", removed);
    }

    private void runJob(MergeDuplicatesJob job, String accountId) {
        try {
            List<Photo> all = photoRepository.findByAccountId(accountId);

            Map<String, List<Photo>> groups = all.stream()
                    .filter(p -> p.getFilename() != null && p.getFileSize() != null)
                    .collect(Collectors.groupingBy(
                            p -> p.getFilename().toLowerCase(Locale.ROOT) + ":" + p.getFileSize()));

            List<Map.Entry<String, List<Photo>>> duplicateGroups = groups.entrySet().stream()
                    .filter(e -> e.getValue().size() > 1)
                    .toList();

            job.setTotal(duplicateGroups.stream().mapToInt(e -> e.getValue().size()).sum());

            for (Map.Entry<String, List<Photo>> entry : duplicateGroups) {
                if (job.isCancelled()) break;
                mergeGroup(entry.getValue(), job);
            }

        } catch (Exception e) {
            LOG.error("Merge-duplicates job {} failed: {}", job.getId(), e.getMessage(), e);
            job.markDone();
            taskHistoryService.completeTask(job.getId(), "FAILED", 0, 0, e.getMessage());
            return;
        }
        job.markDone();
        MergeDuplicatesProgress p = job.currentProgress();
        taskHistoryService.completeTask(job.getId(), "COMPLETED", p.merged(), p.deleted(), null);
        LOG.info("Merge-duplicates job {} done: merged={} deleted={}", job.getId(), p.merged(), p.deleted());
    }

    private void mergeGroup(List<Photo> group, MergeDuplicatesJob job) {
        // Winner: prefer synced record; if tie, prefer iCloud-sourced
        Photo winner = group.stream()
                .filter(Photo::isSyncedToDisk)
                .findFirst()
                .orElseGet(() -> group.stream()
                        .filter(p -> "ICLOUD".equals(p.getSourceProvider()))
                        .findFirst()
                        .orElse(group.get(0)));

        List<Photo> losers = group.stream()
                .filter(p -> !p.getId().equals(winner.getId()))
                .toList();

        boolean changed = false;

        for (Photo loser : losers) {
            if (loser.isExistsOnIcloud() || "ICLOUD".equals(loser.getSourceProvider())) {
                if (loser.getIcloudPhotoId() != null && winner.getIcloudPhotoId() == null) {
                    winner.setIcloudPhotoId(loser.getIcloudPhotoId());
                    changed = true;
                }
                if (loser.getIcloudAssetRecordName() != null && winner.getIcloudAssetRecordName() == null) {
                    winner.setIcloudAssetRecordName(loser.getIcloudAssetRecordName());
                    changed = true;
                }
                if (loser.getAssetToken() != null && winner.getAssetToken() == null) {
                    winner.setAssetToken(loser.getAssetToken());
                    changed = true;
                }
                if (!winner.isExistsOnIcloud()) {
                    winner.setExistsOnIcloud(true);
                    changed = true;
                }
            }

            if (Boolean.TRUE.equals(loser.getExistsOnIphone()) || "IPHONE".equals(loser.getSourceProvider())) {
                if (loser.getIphoneLocation() != null && winner.getIphoneLocation() == null) {
                    winner.setIphoneLocation(loser.getIphoneLocation());
                    changed = true;
                }
                if (!Boolean.TRUE.equals(winner.getExistsOnIphone())) {
                    winner.setExistsOnIphone(true);
                    changed = true;
                }
            }

            if (winner.getFilePath() == null && loser.getFilePath() != null) {
                winner.setFilePath(loser.getFilePath());
                changed = true;
            }
            if (winner.getThumbnailPath() == null && loser.getThumbnailPath() != null) {
                winner.setThumbnailPath(loser.getThumbnailPath());
                changed = true;
            }
            if ("SYNCED".equals(loser.getSyncStatus()) && !"SYNCED".equals(winner.getSyncStatus())) {
                winner.setSyncStatus(loser.getSyncStatus());
                winner.setSyncedToDisk(true);
                changed = true;
            }
        }

        if (changed) photoRepository.update(winner);

        for (Photo loser : losers) {
            photoRepository.deleteById(loser.getId());
            LOG.debug("Merge-duplicates: deleted duplicate {} (kept {})", loser.getId(), winner.getId());
        }

        job.onGroupProcessed(losers.size());
    }
}
