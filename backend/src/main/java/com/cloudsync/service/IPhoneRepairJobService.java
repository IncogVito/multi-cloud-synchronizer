package com.cloudsync.service;

import com.cloudsync.model.dto.IPhoneRepairProgress;
import com.cloudsync.model.dto.IPhoneRepairResult;
import com.cloudsync.model.dto.PhotoAsset;
import com.cloudsync.model.dto.PrefetchStatus;
import com.cloudsync.model.entity.Photo;
import com.cloudsync.model.enums.MediaType;
import com.cloudsync.model.enums.SyncStatus;
import com.cloudsync.provider.PhotoSyncProvider;
import com.cloudsync.repository.PhotoRepository;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Singleton
public class IPhoneRepairJobService {

    private static final Logger LOG = LoggerFactory.getLogger(IPhoneRepairJobService.class);
    private static final Set<String> VIDEO_EXTENSIONS = Set.of("mp4", "m4v", "mov", "avi", "mkv");

    private final PhotoSyncProvider iPhoneProvider;
    private final PhotoRepository photoRepository;
    private final AppContextService appContextService;
    private final TaskHistoryService taskHistoryService;
    private final ConcurrentHashMap<String, IPhoneRepairJob> jobs = new ConcurrentHashMap<>();

    public IPhoneRepairJobService(@Named("IPHONE") PhotoSyncProvider iPhoneProvider,
                                  PhotoRepository photoRepository,
                                  AppContextService appContextService,
                                  TaskHistoryService taskHistoryService) {
        this.iPhoneProvider = iPhoneProvider;
        this.photoRepository = photoRepository;
        this.appContextService = appContextService;
        this.taskHistoryService = taskHistoryService;
    }

    public IPhoneRepairResult startJob(String accountId) {
        IPhoneRepairJob job = new IPhoneRepairJob(UUID.randomUUID().toString());
        jobs.put(job.getId(), job);
        taskHistoryService.createTask(job.getId(), "IPHONE_REPAIR", accountId, "IPHONE", 0);
        Thread.ofVirtual().name("iphone-repair-" + job.getId()).start(() -> runJob(job, accountId));
        return new IPhoneRepairResult(job.getId(), job.currentProgress());
    }

    public Optional<IPhoneRepairJob> getJob(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    @Scheduled(fixedDelay = "1h", initialDelay = "1h")
    void cleanupOldJobs() {
        Instant cutoff = Instant.now().minus(1, ChronoUnit.HOURS);
        int removed = 0;
        for (var it = jobs.entrySet().iterator(); it.hasNext(); ) {
            IPhoneRepairJob job = it.next().getValue();
            if (job.isDone() && job.getCreatedAt().isBefore(cutoff)) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) LOG.debug("Cleaned up {} completed iPhone repair jobs", removed);
    }

    private void runJob(IPhoneRepairJob job, String accountId) {
        try {
            // 1. Scan iPhone mount via provider
            String sessionId = "repair-" + job.getId();
            iPhoneProvider.prefetch(sessionId);

            List<PhotoAsset> assets = waitForScan(sessionId, job);
            if (assets == null) {
                LOG.error("iPhone scan failed or cancelled for repair job {}", job.getId());
                taskHistoryService.completeTask(job.getId(), "FAILED", 0, 0, "iPhone scan failed");
                job.markDone();
                return;
            }

            // 2. Build lookup structures for all known photos in this account
            List<Photo> allPhotos = photoRepository.findByAccountId(accountId);
            // keyed by iphoneLocation (relPath) — primary match
            Set<String> knownByLocation = allPhotos.stream()
                    .map(Photo::getIphoneLocation)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            // keyed by "lowercase-filename:size" — secondary match (catches cross-provider dupes)
            Set<String> knownByNameAndSize = allPhotos.stream()
                    .filter(p -> p.getFilename() != null && p.getFileSize() != null)
                    .map(p -> p.getFilename().toLowerCase(Locale.ROOT) + ":" + p.getFileSize())
                    .collect(Collectors.toSet());

            String storageDeviceId = appContextService.getActive()
                    .map(ctx -> ctx.storageDeviceId())
                    .orElse(null);

            job.setTotal(assets.size());

            // 3. For each scanned asset: create PENDING record if missing from DB by location OR name+size
            for (PhotoAsset asset : assets) {
                if (job.isCancelled()) break;
                boolean isNew = false;
                boolean knownByLoc = knownByLocation.contains(asset.id());
                boolean knownByKey = asset.filename() != null && asset.size() != null
                        && knownByNameAndSize.contains(
                                sanitizeFilename(asset.filename()).toLowerCase(Locale.ROOT) + ":" + asset.size());
                if (!knownByLoc && !knownByKey) {
                    Photo photo = buildPhoto(asset, accountId, storageDeviceId);
                    photoRepository.save(photo);
                    isNew = true;
                    LOG.debug("iPhone repair: queued missing file {} [account={}]", asset.filename(), accountId);
                }
                job.onFileChecked(isNew, false);
            }

            // 4. Find SYNCED iPhone records whose physical file is gone → reset to PENDING
            List<Photo> syncedWithPath = allPhotos.stream()
                    .filter(p -> "IPHONE".equals(p.getSourceProvider()))
                    .filter(p -> SyncStatus.SYNCED.name().equals(p.getSyncStatus()))
                    .filter(p -> p.getFilePath() != null)
                    .toList();

            for (Photo photo : syncedWithPath) {
                if (job.isCancelled()) break;
                if (!Files.exists(Path.of(photo.getFilePath()))) {
                    photo.setSyncStatus(SyncStatus.PENDING.name());
                    photo.setSyncedToDisk(false);
                    photoRepository.update(photo);
                    job.onFileChecked(false, true);
                    LOG.debug("iPhone repair: reset missing-on-disk {} to PENDING", photo.getId());
                }
            }

        } catch (Exception e) {
            LOG.error("iPhone repair job {} failed", job.getId(), e);
            job.markDone();
            taskHistoryService.completeTask(job.getId(), "FAILED", 0, 0, e.getMessage());
            return;
        }
        job.markDone();
        IPhoneRepairProgress p = job.currentProgress();
        taskHistoryService.completeTask(job.getId(), "COMPLETED",
                p.newPending() + p.missingFixed(), 0, null);
        LOG.info("iPhone repair job {} done: checked={} newPending={} missingFixed={}",
                job.getId(), p.checked(), p.newPending(), p.missingFixed());
    }

    private List<PhotoAsset> waitForScan(String sessionId, IPhoneRepairJob job) throws InterruptedException {
        while (!job.isCancelled()) {
            Thread.sleep(1000);
            PrefetchStatus status = iPhoneProvider.getPrefetchStatus(sessionId);
            if (status == null) continue;
            switch (status.status()) {
                case "ready" -> { return iPhoneProvider.listAllPhotos(sessionId); }
                case "error" -> { return null; }
                default -> { /* scanning or mounting — keep polling */ }
            }
        }
        return null;
    }

    private Photo buildPhoto(PhotoAsset asset, String accountId, String storageDeviceId) {
        Photo p = new Photo();
        p.setId(UUID.randomUUID().toString());
        p.setIcloudPhotoId(asset.id());
        p.setAccountId(accountId);
        p.setFilename(sanitizeFilename(asset.filename()));
        p.setFileSize(asset.size());
        p.setCreatedDate(asset.createdDate());
        p.setImportedDate(Instant.now());
        p.setSyncStatus(SyncStatus.PENDING.name());
        p.setExistsOnIcloud(false);
        p.setExistsOnIphone(Boolean.TRUE);
        p.setSourceProvider("IPHONE");
        p.setIphoneLocation(asset.id());
        p.setMediaType(detectMediaType(asset.filename()));
        if (storageDeviceId != null) p.setStorageDeviceId(storageDeviceId);
        return p;
    }

    private static String sanitizeFilename(String filename) {
        if (filename == null) return null;
        return filename.replace("/", "_");
    }

    private static String detectMediaType(String filename) {
        if (filename == null) return MediaType.PHOTO.name();
        int dot = filename.lastIndexOf('.');
        if (dot < 0) return MediaType.PHOTO.name();
        String ext = filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        return VIDEO_EXTENSIONS.contains(ext) ? MediaType.VIDEO.name() : MediaType.PHOTO.name();
    }
}
