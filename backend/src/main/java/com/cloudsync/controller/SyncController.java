package com.cloudsync.controller;

import com.cloudsync.model.dto.DiskIndexProgressEvent;
import com.cloudsync.model.dto.IPhoneRepairProgress;
import com.cloudsync.model.dto.IPhoneRepairResult;
import com.cloudsync.model.dto.MergeDuplicatesProgress;
import com.cloudsync.model.dto.MergeDuplicatesResult;
import com.cloudsync.model.dto.OrphanPhotosCountResponse;
import com.cloudsync.model.dto.OrphanPhotosProgress;
import com.cloudsync.model.dto.OrphanPhotosResult;
import com.cloudsync.model.dto.SyncProgressEvent;
import com.cloudsync.model.dto.SyncStartResponse;
import com.cloudsync.model.enums.ProviderType;
import com.cloudsync.service.DiskIndexStateHolder;
import com.cloudsync.service.DiskIndexingService;
import com.cloudsync.service.IPhoneRepairJobService;
import com.cloudsync.service.MergeDuplicatesJobService;
import com.cloudsync.service.OrphanPhotosJobService;
import com.cloudsync.service.SyncService;
import com.cloudsync.service.SyncStateHolder;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.*;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.http.sse.Event;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.Optional;

@Controller("/api/sync")
@Secured(SecurityRule.IS_AUTHENTICATED)
public class SyncController {

    private final SyncService syncService;
    private final SyncStateHolder syncStateHolder;
    private final IPhoneRepairJobService iPhoneRepairJobService;
    private final MergeDuplicatesJobService mergeDuplicatesJobService;
    private final OrphanPhotosJobService orphanPhotosJobService;
    private final DiskIndexingService diskIndexingService;
    private final DiskIndexStateHolder diskIndexStateHolder;

    public SyncController(SyncService syncService,
                          SyncStateHolder syncStateHolder,
                          IPhoneRepairJobService iPhoneRepairJobService,
                          MergeDuplicatesJobService mergeDuplicatesJobService,
                          OrphanPhotosJobService orphanPhotosJobService,
                          DiskIndexingService diskIndexingService,
                          DiskIndexStateHolder diskIndexStateHolder) {
        this.syncService = syncService;
        this.syncStateHolder = syncStateHolder;
        this.iPhoneRepairJobService = iPhoneRepairJobService;
        this.mergeDuplicatesJobService = mergeDuplicatesJobService;
        this.orphanPhotosJobService = orphanPhotosJobService;
        this.diskIndexingService = diskIndexingService;
        this.diskIndexStateHolder = diskIndexStateHolder;
    }

    @Post("/{accountId}")
    @ExecuteOn(TaskExecutors.BLOCKING)
    @Produces(MediaType.APPLICATION_JSON)
    public SyncStartResponse startSync(@PathVariable String accountId,
                                       @QueryValue(defaultValue = "ICLOUD") ProviderType provider) {
        return syncService.startSync(accountId, provider.name());
    }

    @Get(value = "/{accountId}/events", produces = MediaType.TEXT_EVENT_STREAM)
    public Publisher<Event<SyncProgressEvent>> syncEvents(@PathVariable String accountId) {
        return Flux.from(syncStateHolder.subscribe(accountId))
                .map(Event::of);
    }

    @Post("/{accountId}/confirm")
    @ExecuteOn(TaskExecutors.BLOCKING)
    @Produces(MediaType.APPLICATION_JSON)
    public void confirmSync(@PathVariable String accountId) {
        syncService.confirmSync(accountId);
    }

    @Delete("/{accountId}")
    @ExecuteOn(TaskExecutors.BLOCKING)
    @Produces(MediaType.APPLICATION_JSON)
    public void cancelSync(@PathVariable String accountId) {
        syncService.cancelSync(accountId);
    }

    @Get("/{accountId}/status")
    @Produces(MediaType.APPLICATION_JSON)
    public Optional<SyncProgressEvent> getSyncStatus(@PathVariable String accountId) {
        return syncStateHolder.getSnapshot(accountId);
    }

    @Get("/{accountId}/reorganize-preview")
    @ExecuteOn(TaskExecutors.BLOCKING)
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> reorganizePreview(@PathVariable String accountId) {
        return syncService.reorganizePreview(accountId);
    }

    @Post("/{accountId}/reorganize")
    @ExecuteOn(TaskExecutors.BLOCKING)
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> reorganize(@PathVariable String accountId) {
        return syncService.reorganize(accountId);
    }

    /**
     * Start indexing the account's {@code syncFolderPath}. Scans only that folder (not the global
     * device path) and persists discovered media. Returns immediately; progress is streamed via
     * {@code /{accountId}/index/events}.
     */
    @Get("/{accountId}/index")
    @ExecuteOn(TaskExecutors.BLOCKING)
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> indexAccount(@PathVariable String accountId) {
        if (diskIndexingService.isRunning(accountId)) {
            return Map.of("status", "ALREADY_RUNNING");
        }
        diskIndexingService.startIndexing(accountId);
        return Map.of("status", "STARTED");
    }

    @Get(value = "/{accountId}/index/events", produces = MediaType.TEXT_EVENT_STREAM)
    public Publisher<Event<DiskIndexProgressEvent>> indexEvents(@PathVariable String accountId) {
        return Flux.from(diskIndexStateHolder.subscribe(accountId)).map(Event::of);
    }

    @Get("/{accountId}/index/status")
    @Produces(MediaType.APPLICATION_JSON)
    public Optional<DiskIndexProgressEvent> indexStatus(@PathVariable String accountId) {
        return diskIndexStateHolder.getSnapshot(accountId);
    }

    @Post("/{accountId}/iphone-repair")
    @ExecuteOn(TaskExecutors.BLOCKING)
    @Produces(MediaType.APPLICATION_JSON)
    public IPhoneRepairResult startIPhoneRepair(@PathVariable String accountId) {
        return iPhoneRepairJobService.startJob(accountId);
    }

    @Get(value = "/iphone-repair/{jobId}/progress", produces = MediaType.TEXT_EVENT_STREAM)
    public Publisher<Event<IPhoneRepairProgress>> getIPhoneRepairProgress(@PathVariable String jobId) {
        return iPhoneRepairJobService.getJob(jobId)
                .map(job -> (Publisher<Event<IPhoneRepairProgress>>) Flux.from(job.subscribe()).map(Event::of))
                .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, "Repair job not found: " + jobId));
    }

    @Post("/{accountId}/merge-duplicates")
    @ExecuteOn(TaskExecutors.BLOCKING)
    @Produces(MediaType.APPLICATION_JSON)
    public MergeDuplicatesResult startMergeDuplicates(@PathVariable String accountId) {
        return mergeDuplicatesJobService.startJob(accountId);
    }

    @Get(value = "/merge-duplicates/{jobId}/progress", produces = MediaType.TEXT_EVENT_STREAM)
    public Publisher<Event<MergeDuplicatesProgress>> getMergeDuplicatesProgress(@PathVariable String jobId) {
        return mergeDuplicatesJobService.getJob(jobId)
                .map(job -> (Publisher<Event<MergeDuplicatesProgress>>) Flux.from(job.subscribe()).map(Event::of))
                .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, "Merge-duplicates job not found: " + jobId));
    }

    @Get("/{accountId}/orphan-photos/count")
    @ExecuteOn(TaskExecutors.BLOCKING)
    @Produces(MediaType.APPLICATION_JSON)
    public OrphanPhotosCountResponse orphanPhotosCount(@PathVariable String accountId) {
        return new OrphanPhotosCountResponse(orphanPhotosJobService.countOrphans(accountId));
    }

    @Post("/{accountId}/orphan-photos/assign")
    @ExecuteOn(TaskExecutors.BLOCKING)
    @Produces(MediaType.APPLICATION_JSON)
    public OrphanPhotosResult assignOrphanPhotos(@PathVariable String accountId) {
        return orphanPhotosJobService.startJob(accountId);
    }

    @Get(value = "/orphan-photos/{jobId}/progress", produces = MediaType.TEXT_EVENT_STREAM)
    public Publisher<Event<OrphanPhotosProgress>> getOrphanPhotosProgress(@PathVariable String jobId) {
        return orphanPhotosJobService.getJob(jobId)
                .map(job -> (Publisher<Event<OrphanPhotosProgress>>) Flux.from(job.subscribe()).map(Event::of))
                .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, "Orphan-photos job not found: " + jobId));
    }
}
