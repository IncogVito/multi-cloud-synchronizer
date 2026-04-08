package com.cloudsync.controller;

import com.cloudsync.model.dto.*;
import com.cloudsync.service.SetupService;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.*;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

@Controller("/api")
@ExecuteOn(TaskExecutors.BLOCKING)
public class SetupController {

    private static final Logger LOG = LoggerFactory.getLogger(SetupController.class);

    private final SetupService setupService;

    public SetupController(SetupService setupService) {
        this.setupService = setupService;
    }

    @Get("/setup/browse")
    @Produces(MediaType.APPLICATION_JSON)
    @Secured(SecurityRule.IS_ANONYMOUS)
    public HttpResponse<?> browse(@QueryValue(defaultValue = "") String path) {
        try {
            BrowseResponse result = setupService.browse(path.isBlank() ? null : path);
            return HttpResponse.ok(result);
        } catch (IllegalStateException e) {
            return HttpResponse.badRequest(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return HttpResponse.badRequest(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            LOG.error("browse error for path={}: {}", path, e.getMessage());
            return HttpResponse.serverError(Map.of("error", e.getMessage()));
        }
    }

    @Get("/setup/scan")
    @Produces(MediaType.APPLICATION_JSON)
    @Secured(SecurityRule.IS_ANONYMOUS)
    public HttpResponse<?> scan(@QueryValue String path) {
        try {
            DiskScanResult result = setupService.deepScanFolder(path);
            return HttpResponse.ok(result);
        } catch (IllegalArgumentException e) {
            return HttpResponse.badRequest(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            LOG.error("scan error for path={}: {}", path, e.getMessage());
            return HttpResponse.serverError(Map.of("error", e.getMessage()));
        }
    }

    @Put("/accounts/{id}/sync-config")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Secured(SecurityRule.IS_AUTHENTICATED)
    public HttpResponse<?> saveSyncConfig(@PathVariable String id, @Body SyncConfigRequest request) {
        LOG.info("PUT /api/accounts/{}/sync-config", id);
        try {
            setupService.saveSyncConfig(id, request);
            return HttpResponse.ok(Map.of("success", true));
        } catch (IllegalArgumentException e) {
            return HttpResponse.badRequest(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            LOG.error("saveSyncConfig error for account={}: {}", id, e.getMessage());
            return HttpResponse.serverError(Map.of("error", e.getMessage()));
        }
    }

    @Post("/accounts/{id}/reorganize")
    @Produces(MediaType.APPLICATION_JSON)
    @Secured(SecurityRule.IS_AUTHENTICATED)
    public HttpResponse<?> reorganize(@PathVariable String id,
                                      @QueryValue(defaultValue = "true") boolean dryRun) {
        LOG.info("POST /api/accounts/{}/reorganize?dryRun={}", id, dryRun);
        try {
            ReorganizeResult result = setupService.reorganize(id, dryRun);
            return HttpResponse.ok(result);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return HttpResponse.badRequest(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            LOG.error("reorganize error for account={}: {}", id, e.getMessage());
            return HttpResponse.serverError(Map.of("error", e.getMessage()));
        }
    }
}
