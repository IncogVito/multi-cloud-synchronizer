package com.cloudsync.service;

import com.cloudsync.model.dto.DeletionJobStartResponse;
import com.cloudsync.model.dto.ICloudBatchDeleteResult;
import com.cloudsync.model.dto.JobSummary;
import com.cloudsync.model.entity.ICloudAccount;
import com.cloudsync.model.entity.Photo;
import com.cloudsync.provider.PhotoSyncProvider;
import com.cloudsync.repository.AccountRepository;
import com.cloudsync.repository.PhotoRepository;
import io.micronaut.context.annotation.Value;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Singleton
public class DeletionJobService {

    private static final Logger LOG = LoggerFactory.getLogger(DeletionJobService.class);

    private final PhotoRepository photoRepository;
    private final AccountRepository accountRepository;
    private final Map<String, PhotoSyncProvider> providers;
    private final int chunkSize;
    private final ConcurrentHashMap<String, DeletionJob> jobs = new ConcurrentHashMap<>();

// FIX:  Use constant enums for Named.
    public DeletionJobService(
            PhotoRepository photoRepository,
            AccountRepository accountRepository,
            @Named("ICLOUD") PhotoSyncProvider iCloudProvider,
            @Named("IPHONE") PhotoSyncProvider iPhoneProvider,
            @Value("${deletion.batch-chunk-size:50}") int chunkSize) {
        this.photoRepository = photoRepository;
        this.accountRepository = accountRepository;
        this.providers = Map.of("ICLOUD", iCloudProvider, "IPHONE", iPhoneProvider);
        this.chunkSize = chunkSize;
    }

// FIX: add logs 
    public DeletionJobStartResponse startJob(String accountId, List<String> photoIds, String provider) {
        List<Photo> candidates = photoRepository.findByIdIn(photoIds);

        List<Photo> eligible = candidates.stream()
                .filter(p -> isEligible(p, provider))
                .toList();

        int skipped = photoIds.size() - eligible.size();
        List<String> eligibleIds = eligible.stream().map(Photo::getId).toList();

        DeletionJob job = new DeletionJob(UUID.randomUUID().toString(), accountId, provider, eligibleIds);
        jobs.put(job.getJobId(), job);

        Thread.ofVirtual().start(() -> runJob(job, eligible, provider));

        return new DeletionJobStartResponse(job.getJobId(), eligible.size(), skipped);
    }

    public Optional<DeletionJob> getJob(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    public boolean cancelJob(String jobId) {
        DeletionJob job = jobs.get(jobId);
        if (job == null) return false;
        job.cancel();
        return true;
    }

    public List<JobSummary> allJobSummaries() {
        return jobs.values().stream()
                .map(j -> new JobSummary(
                        j.getJobId(),
                        "DELETION", // FIX: Use constants from different file
                        j.getStatus(),
                        j.getTotal(),
                        j.getDeleted(),
                        j.getFailed(),
                        "Deleting photos (" + j.getProvider() + ")"))
                .toList();
    }

    @Scheduled(fixedDelay = "2h", initialDelay = "2h")
    void cleanupOldJobs() {
        Instant cutoff = Instant.now().minus(2, ChronoUnit.HOURS);
        int removed = 0;
        for (var it = jobs.entrySet().iterator(); it.hasNext(); ) {
            DeletionJob job = it.next().getValue();
            if (job.isDone() && job.getCreatedAt().isBefore(cutoff)) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) LOG.debug("Cleaned up {} completed deletion jobs", removed);
    }

    private void runJob(DeletionJob job, List<Photo> photos, String provider) {
        PhotoSyncProvider syncProvider = providers.get(provider);
        if (syncProvider == null) {
            LOG.error("No provider registered for: {}", provider);
            job.markFailed();
            return;
        }

        try {
            List<List<Photo>> chunks = partition(photos, chunkSize);
            for (List<Photo> chunk : chunks) {
                if (job.isCancelled()) break;
                processChunk(job, chunk, syncProvider);
            }
        } catch (Exception e) {
            LOG.error("Deletion job {} failed: {}", job.getJobId(), e.getMessage(), e);
            job.markFailed();
            return;
        }

        job.markDone();
    }

    private void processChunk(DeletionJob job, List<Photo> chunk, PhotoSyncProvider syncProvider) {
        String accountId = job.getAccountId();
        String provider = job.getProvider();

        ICloudAccount account = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalStateException("Account not found: " + accountId));
        String sessionId = account.getSessionId();
        if (sessionId == null) {
            LOG.warn("No session for account {} in job {}", accountId, job.getJobId());
            job.recordFailure(chunk.stream().map(Photo::getId).toList());
            return;
        }

        // FIX: Use constant enum for provider
        List<String> remoteIds = chunk.stream()
                .map(p -> "ICLOUD".equals(provider) ? p.getIcloudPhotoId() : p.getIphoneLocation())
                .toList();

        List<ICloudBatchDeleteResult> results;
        try {
            results = syncProvider.batchDeletePhotos(remoteIds, sessionId);
        } catch (Exception e) {
            LOG.warn("Batch delete chunk failed for job {}: {}", job.getJobId(), e.getMessage());
            List<String> failedIds = chunk.stream().map(Photo::getId).toList();
            job.recordFailure(failedIds);
            return;
        }

        List<String> succeeded = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        for (int i = 0; i < chunk.size(); i++) {
            Photo photo = chunk.get(i);
            ICloudBatchDeleteResult result = i < results.size() ? results.get(i) : null;
            boolean deleted = result != null && result.deleted();

            if (deleted) {
                updatePhotoAfterDeletion(photo, provider);
                succeeded.add(photo.getId());
            } else {
                String err = result != null ? result.error() : "no result";
                LOG.warn("Failed to delete photo {} from {}: {}", photo.getId(), provider, err);
                failed.add(photo.getId());
            }
        }

        if (!succeeded.isEmpty()) job.recordSuccess(succeeded);
        if (!failed.isEmpty()) job.recordFailure(failed);
    }

    private void updatePhotoAfterDeletion(Photo photo, String provider) {
        try {
            // FIX:  Use constant enum for provider
            if ("ICLOUD".equals(provider)) {
                photo.setExistsOnIcloud(false);
            } else {
                photo.setExistsOnIphone(false);
            }
            photo.setDeleted(true);
            photo.setDeletedDate(Instant.now());
            photoRepository.update(photo);
        } catch (Exception e) {
            LOG.warn("Failed to update DB for photo {} after deletion: {}", photo.getId(), e.getMessage());
        }
    }

    private boolean isEligible(Photo photo, String provider) {
        // FIX: Use constant enum for provider
        return switch (provider) {
            case "ICLOUD" -> Boolean.TRUE.equals(photo.isExistsOnIcloud());
            case "IPHONE" -> Boolean.TRUE.equals(photo.getExistsOnIphone());
            default -> false;
        };
    }

    private static <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> chunks = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            chunks.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return chunks;
    }
}
