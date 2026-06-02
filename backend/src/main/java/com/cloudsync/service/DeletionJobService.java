package com.cloudsync.service;

import com.cloudsync.model.dto.DeletionJobStartResponse;
import com.cloudsync.model.dto.ICloudBatchDeleteResult;
import com.cloudsync.model.dto.JobSummary;
import com.cloudsync.model.dto.PhotoDeleteItem;
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
    private static final int DELETE_SUB_CHUNK_SIZE = 5;

    private final PhotoRepository photoRepository;
    private final AccountRepository accountRepository;
    private final TaskHistoryService taskHistoryService;
    private final Map<String, PhotoSyncProvider> providers;
    private final int chunkSize;
    private final ConcurrentHashMap<String, DeletionJob> jobs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DeletionJob> activeJobByProvider = new ConcurrentHashMap<>();

// FIX:  Use constant enums for Named.
    public DeletionJobService(
            PhotoRepository photoRepository,
            AccountRepository accountRepository,
            TaskHistoryService taskHistoryService,
            @Named("ICLOUD") PhotoSyncProvider iCloudProvider,
            @Named("IPHONE") PhotoSyncProvider iPhoneProvider,
            @Value("${deletion.batch-chunk-size:50}") int chunkSize) {
        this.photoRepository = photoRepository;
        this.accountRepository = accountRepository;
        this.taskHistoryService = taskHistoryService;
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

        String providerKey = accountId + ":" + provider;
        DeletionJob existing = activeJobByProvider.get(providerKey);
        if (existing != null && !existing.isDone()) {
            existing.mergePhotos(eligible);
            return new DeletionJobStartResponse(existing.getJobId(), eligible.size(), skipped);
        }

        List<String> eligibleIds = eligible.stream().map(Photo::getId).toList();
        DeletionJob job = new DeletionJob(UUID.randomUUID().toString(), accountId, provider, eligibleIds);
        jobs.put(job.getJobId(), job);
        activeJobByProvider.put(providerKey, job);

        taskHistoryService.createTask(job.getJobId(), "DELETION", accountId, provider, eligible.size());

        job.enqueuePhotos(eligible);
        Thread.ofVirtual().start(() -> runJob(job, provider, providerKey));

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

    private void runJob(DeletionJob job, String provider, String providerKey) {
        PhotoSyncProvider syncProvider = providers.get(provider);
        if (syncProvider == null) {
            LOG.error("No provider registered for: {}", provider);
            job.markFailed();
            activeJobByProvider.remove(providerKey, job);
            taskHistoryService.completeTask(job.getJobId(), "FAILED", job.getDeleted(), job.getFailed(), "No provider");
            return;
        }

        try {
            while (!job.isCancelled()) {
                List<Photo> batch = job.pollBatch(chunkSize, 200);
                if (batch.isEmpty()) break;
                processChunk(job, batch, syncProvider);
            }
        } catch (Exception e) {
            LOG.error("Deletion job {} failed: {}", job.getJobId(), e.getMessage(), e);
            job.markFailed();
            activeJobByProvider.remove(providerKey, job);
            taskHistoryService.completeTask(job.getJobId(), "FAILED", job.getDeleted(), job.getFailed(), e.getMessage());
            return;
        }

        String finalStatus = job.isCancelled() ? "CANCELLED" : "COMPLETED";
        job.markDone();
        activeJobByProvider.remove(providerKey, job);
        taskHistoryService.completeTask(job.getJobId(), finalStatus, job.getDeleted(), job.getFailed(), null);
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

        List<List<Photo>> subChunks = partition(chunk, DELETE_SUB_CHUNK_SIZE);
        for (List<Photo> subChunk : subChunks) {
            if (job.isCancelled()) return;
            processSubChunk(job, subChunk, syncProvider, provider, sessionId);
        }
    }

    private void processSubChunk(DeletionJob job, List<Photo> subChunk, PhotoSyncProvider syncProvider,
                                  String provider, String sessionId) {
        // FIX: Use constant enum for provider
        List<PhotoDeleteItem> items = subChunk.stream()
                .map(p -> "ICLOUD".equals(provider)
                        ? new PhotoDeleteItem(p.getIcloudPhotoId(), p.getIcloudAssetRecordName())
                        : new PhotoDeleteItem(p.getIphoneLocation(), null))
                .toList();

        List<ICloudBatchDeleteResult> results;
        try {
            results = syncProvider.batchDeletePhotos(items, sessionId);
        } catch (Exception e) {
            LOG.warn("Batch delete sub-chunk failed for job {}: {}", job.getJobId(), e.getMessage());
            List<Photo> permanent = job.requeueRetriable(subChunk);
            if (!permanent.isEmpty()) {
                job.recordFailure(permanent.stream().map(Photo::getId).toList());
            }
            return;
        }

        List<String> succeeded = new ArrayList<>();
        List<Photo> failed = new ArrayList<>();

        for (int i = 0; i < subChunk.size(); i++) {
            Photo photo = subChunk.get(i);
            ICloudBatchDeleteResult result = i < results.size() ? results.get(i) : null;
            boolean deleted = result != null && result.deleted();

            if (deleted) {
                updatePhotoAfterDeletion(photo, provider);
                succeeded.add(photo.getId());
            } else {
                String err = result != null ? result.error() : "no result";
                LOG.warn("Failed to delete photo {} from {}: {}", photo.getId(), provider, err);
                failed.add(photo);
            }
        }

        if (!succeeded.isEmpty()) {
            job.recordSuccess(succeeded);
            List<String> succeededNames = subChunk.stream()
                    .filter(p -> succeeded.contains(p.getId()))
                    .map(Photo::getFilename)
                    .toList();
            taskHistoryService.addTaskItems(job.getJobId(), succeeded, succeededNames, "DELETED", null);
        }
        if (!failed.isEmpty()) {
            List<Photo> permanent = job.requeueRetriable(failed);
            if (!permanent.isEmpty()) {
                List<String> permanentIds = permanent.stream().map(Photo::getId).toList();
                job.recordFailure(permanentIds);
                List<String> permanentNames = permanent.stream().map(Photo::getFilename).toList();
                taskHistoryService.addTaskItems(job.getJobId(), permanentIds, permanentNames, "FAILED", "Deletion failed");
            }
        }
    }

    private void updatePhotoAfterDeletion(Photo photo, String provider) {
        try {
            // FIX:  Use constant enum for provider
            if ("ICLOUD".equals(provider)) {
                photo.setExistsOnIcloud(false);
            } else {
                photo.setExistsOnIphone(false);
            }
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
