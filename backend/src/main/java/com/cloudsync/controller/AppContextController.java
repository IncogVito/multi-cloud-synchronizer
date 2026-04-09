package com.cloudsync.controller;

import com.cloudsync.model.dto.AppContext;
import com.cloudsync.service.AppContextService;
import com.cloudsync.service.DiskSetupService;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.*;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Controller("/api/context")
@ExecuteOn(TaskExecutors.BLOCKING)
public class AppContextController {

    private static final Logger LOG = LoggerFactory.getLogger(AppContextController.class);

    private final AppContextService appContextService;
    private final DiskSetupService diskSetupService;

    public AppContextController(AppContextService appContextService, DiskSetupService diskSetupService) {
        this.appContextService = appContextService;
        this.diskSetupService = diskSetupService;
    }

    @Get
    @Produces(MediaType.APPLICATION_JSON)
    @Secured(SecurityRule.IS_ANONYMOUS)
    public HttpResponse<?> getActive() {
        try {
            Optional<AppContext> ctx = appContextService.getActive();
            return ctx.<HttpResponse<?>>map(HttpResponse::ok).orElseGet(HttpResponse::noContent);
        } catch (Exception e) {
            LOG.error("GET /api/context failed", e);
            return HttpResponse.serverError(Map.of(
                    "error", "CONTEXT_LOAD_FAILED",
                    "message", e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "no message" : e.getMessage())
            ));
        }
    }

    @Post
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Secured(SecurityRule.IS_AUTHENTICATED)
    public HttpResponse<?> setContext(@Body Map<String, Object> body) {
        String storageDeviceId = (String) body.get("storageDeviceId");
        String basePath = (String) body.get("basePath");
        boolean create = Boolean.TRUE.equals(body.get("create"));
        try {
            AppContext ctx = appContextService.setContext(storageDeviceId, basePath, create);
            return HttpResponse.ok(ctx);
        } catch (IllegalArgumentException e) {
            return HttpResponse.badRequest(Map.of("error", "INVALID_BASE_PATH", "message", e.getMessage()));
        } catch (IllegalStateException e) {
            return HttpResponse.badRequest(Map.of("error", "CONTEXT_DRIVE_UNAVAILABLE", "message", e.getMessage()));
        }
    }

    @Delete
    @Secured(SecurityRule.IS_AUTHENTICATED)
    public HttpResponse<?> clear() {
        try {
            appContextService.clear();
            return HttpResponse.noContent();
        } catch (Exception e) {
            LOG.error("DELETE /api/context failed", e);
            return HttpResponse.serverError(Map.of(
                    "error", "CONTEXT_CLEAR_FAILED",
                    "message", e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "no message" : e.getMessage())
            ));
        }
    }

    @Get("/browse")
    @Produces(MediaType.APPLICATION_JSON)
    @Secured(SecurityRule.IS_ANONYMOUS)
    public HttpResponse<?> browse(@QueryValue(defaultValue = "") String path) {
        Optional<com.cloudsync.model.entity.StorageDevice> mountedDevice = diskSetupService.findMountedDevice();
        if (mountedDevice.isEmpty()) {
            return HttpResponse.badRequest(Map.of("error", "NO_DRIVE_MOUNTED"));
        }
        String mountPoint = mountedDevice.get().getMountPoint();
        Path mountPath = Paths.get(mountPoint).toAbsolutePath().normalize();

        Path target;
        try {
            String relative = path == null ? "" : path.trim();
            if (relative.startsWith("/")) relative = relative.substring(1);
            target = mountPath.resolve(relative).toAbsolutePath().normalize();
            if (!target.startsWith(mountPath)) {
                return HttpResponse.badRequest(Map.of("error", "INVALID_PATH"));
            }
            if (!Files.isDirectory(target)) {
                return HttpResponse.badRequest(Map.of("error", "NOT_A_DIRECTORY"));
            }
        } catch (Exception e) {
            return HttpResponse.badRequest(Map.of("error", "INVALID_PATH", "message", e.getMessage()));
        }

        List<Map<String, Object>> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(target)) {
            for (Path child : stream) {
                if (!Files.isDirectory(child)) continue;
                if (Files.isSymbolicLink(child)) continue;
                String childAbs = child.toAbsolutePath().normalize().toString();
                String childRel = mountPath.relativize(child.toAbsolutePath().normalize()).toString();
                entries.add(Map.of(
                        "name", child.getFileName().toString(),
                        "absolutePath", childAbs,
                        "relativePath", childRel
                ));
            }
        } catch (IOException e) {
            LOG.warn("browse failed at {}: {}", target, e.getMessage());
            return HttpResponse.serverError(Map.of("error", "IO_ERROR", "message", e.getMessage()));
        }

        entries.sort((a, b) -> ((String) a.get("name")).compareToIgnoreCase((String) b.get("name")));

        String currentRel = mountPath.relativize(target).toString();
        return HttpResponse.ok(Map.of(
                "mountPoint", mountPoint,
                "currentAbsolute", target.toString(),
                "currentRelative", currentRel,
                "entries", entries
        ));
    }

    @Post("/mkdir")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Secured(SecurityRule.IS_AUTHENTICATED)
    public HttpResponse<?> mkdir(@Body Map<String, String> body) {
        String parent = body.get("parentPath");
        String name = body.get("name");
        if (parent == null || name == null || name.isBlank() || name.contains("/") || name.contains("..")) {
            return HttpResponse.badRequest(Map.of("error", "INVALID_NAME"));
        }
        Optional<com.cloudsync.model.entity.StorageDevice> mountedDevice = diskSetupService.findMountedDevice();
        if (mountedDevice.isEmpty()) {
            return HttpResponse.badRequest(Map.of("error", "NO_DRIVE_MOUNTED"));
        }
        Path mountPath = Paths.get(mountedDevice.get().getMountPoint()).toAbsolutePath().normalize();
        Path parentPath = Paths.get(parent).toAbsolutePath().normalize();
        if (!parentPath.startsWith(mountPath)) {
            return HttpResponse.badRequest(Map.of("error", "INVALID_PATH"));
        }
        Path created = parentPath.resolve(name).normalize();
        if (!created.startsWith(mountPath)) {
            return HttpResponse.badRequest(Map.of("error", "INVALID_PATH"));
        }
        try {
            Files.createDirectories(created);
        } catch (IOException e) {
            return HttpResponse.serverError(Map.of("error", "IO_ERROR", "message", e.getMessage()));
        }
        return HttpResponse.ok(Map.of(
                "absolutePath", created.toString(),
                "relativePath", mountPath.relativize(created).toString()
        ));
    }
}
