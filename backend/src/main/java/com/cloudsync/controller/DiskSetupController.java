package com.cloudsync.controller;

import com.cloudsync.service.DiskSetupService;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.*;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

@Controller("/api/setup")
@Secured(SecurityRule.IS_ANONYMOUS)
public class DiskSetupController {

    private static final Logger LOG = LoggerFactory.getLogger(DiskSetupController.class);

    private final DiskSetupService diskSetupService;

    public DiskSetupController(DiskSetupService diskSetupService) {
        this.diskSetupService = diskSetupService;
    }

    @Get("/status")
    @Produces(MediaType.APPLICATION_JSON)
    public HttpResponse<DiskSetupService.DriveStatus> getStatus() {
        return HttpResponse.ok(diskSetupService.getDriveStatus());
    }

    @Get("/disks")
    @Produces(MediaType.APPLICATION_JSON)
    public HttpResponse<List<DiskSetupService.DiskInfo>> listDisks() {
        return HttpResponse.ok(diskSetupService.listDisks());
    }

    @Post("/mount")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public HttpResponse<?> mount(@Body Map<String, String> body) {
        String device = body.get("device");
        if (device == null || device.isBlank()) {
            return HttpResponse.badRequest(Map.of("error", "Missing 'device' field"));
        }
        try {
            DiskSetupService.DriveStatus status = diskSetupService.mountAndRegister(device);
            return HttpResponse.ok(status);
        } catch (IllegalStateException e) {
            LOG.warn("Mount failed: {}", e.getMessage());
            return HttpResponse.serverError(Map.of(
                    "error", "MOUNT_FAILED",
                    "message", e.getMessage() == null ? "Unknown error" : e.getMessage()
            ));
        } catch (Exception e) {
            LOG.error("Mount unexpected error", e);
            return HttpResponse.serverError(Map.of(
                    "error", "MOUNT_FAILED",
                    "message", e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "no message" : e.getMessage())
            ));
        }
    }

    @Post("/unmount")
    public HttpResponse<?> unmount() {
        try {
            diskSetupService.unmount();
            return HttpResponse.ok(Map.of("success", true));
        } catch (IllegalStateException e) {
            LOG.warn("Unmount failed: {}", e.getMessage());
            return HttpResponse.serverError(Map.of("error", e.getMessage()));
        }
    }
}
