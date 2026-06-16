package com.cloudsync.service;

import com.cloudsync.model.dto.OrphanPhotosResult;
import com.cloudsync.model.entity.ICloudAccount;
import com.cloudsync.model.entity.Photo;
import com.cloudsync.repository.AccountRepository;
import com.cloudsync.repository.PhotoRepository;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Assigns photos with no owner ({@code account_id IS NULL}) on the active account's storage device
 * to that account. Device is derived from {@code account.storageDeviceId}. Photos already owned by
 * another account are never touched. Tracked in {@code task_history} as {@code ASSIGN_ORPHAN_PHOTOS}.
 */
@Singleton
public class OrphanPhotosJobService {

    public static final String TASK_TYPE = "ASSIGN_ORPHAN_PHOTOS";

    private static final Logger LOG = LoggerFactory.getLogger(OrphanPhotosJobService.class);

    private final PhotoRepository photoRepository;
    private final AccountRepository accountRepository;
    private final TaskHistoryService taskHistoryService;
    private final ConcurrentHashMap<String, OrphanPhotosJob> jobs = new ConcurrentHashMap<>();

    public OrphanPhotosJobService(PhotoRepository photoRepository,
                                  AccountRepository accountRepository,
                                  TaskHistoryService taskHistoryService) {
        this.photoRepository = photoRepository;
        this.accountRepository = accountRepository;
        this.taskHistoryService = taskHistoryService;
    }

    /** Number of orphan photos on the account's device, or 0 if the account is unknown. */
    public long countOrphans(String accountId) {
        return accountRepository.findById(accountId)
                .map(acc -> photoRepository.countByAccountIdIsNullAndStorageDeviceId(acc.getStorageDeviceId()))
                .orElse(0L);
    }

    public OrphanPhotosResult startJob(String accountId) {
        OrphanPhotosJob job = new OrphanPhotosJob(UUID.randomUUID().toString());
        jobs.put(job.getId(), job);
        int total = (int) countOrphans(accountId);
        taskHistoryService.createTask(job.getId(), TASK_TYPE, accountId, null, total);
        Thread.ofVirtual().name("orphan-photos-" + job.getId()).start(() -> runJob(job, accountId));
        return new OrphanPhotosResult(job.getId(), job.currentProgress());
    }

    public Optional<OrphanPhotosJob> getJob(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    @Scheduled(fixedDelay = "1h", initialDelay = "1h")
    void cleanupOldJobs() {
        Instant cutoff = Instant.now().minus(1, ChronoUnit.HOURS);
        int removed = 0;
        for (var it = jobs.entrySet().iterator(); it.hasNext(); ) {
            OrphanPhotosJob job = it.next().getValue();
            if (job.isDone() && job.getCreatedAt().isBefore(cutoff)) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) LOG.debug("Cleaned up {} completed orphan-photos jobs", removed);
    }

    void runJob(OrphanPhotosJob job, String accountId) {
        try {
            Optional<ICloudAccount> account = accountRepository.findById(accountId);
            if (account.isEmpty()) {
                job.markDone();
                taskHistoryService.completeTask(job.getId(), "FAILED", 0, 0, "Account not found: " + accountId);
                return;
            }

            String storageDeviceId = account.get().getStorageDeviceId();
            List<Photo> orphans = photoRepository.findByAccountIdIsNullAndStorageDeviceId(storageDeviceId);
            job.setTotal(orphans.size());

            for (Photo photo : orphans) {
                if (job.isCancelled()) break;
                photo.setAccountId(accountId);
                photoRepository.update(photo);
                job.onAssigned();
            }
        } catch (Exception e) {
            LOG.error("Orphan-photos job {} failed: {}", job.getId(), e.getMessage(), e);
            job.markDone();
            taskHistoryService.completeTask(job.getId(), "FAILED",
                    job.currentProgress().assigned(), 0, e.getMessage());
            return;
        }

        job.markDone();
        int assigned = job.currentProgress().assigned();
        taskHistoryService.completeTask(job.getId(), "COMPLETED", assigned, 0, null);
        LOG.info("Orphan-photos job {} done: assigned={}", job.getId(), assigned);
    }
}
