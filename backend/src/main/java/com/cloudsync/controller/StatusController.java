package com.cloudsync.controller;

import com.cloudsync.model.dto.DeviceCheckEvent;
import com.cloudsync.model.dto.DeviceStatusResponse;
import com.cloudsync.service.DeviceStatusService;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;

import java.util.Map;
import io.micronaut.http.sse.Event;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;

import java.util.List;

@Tag(name = "Status")
@Controller("/api/status")
@Secured(SecurityRule.IS_AUTHENTICATED)
public class StatusController {

    private final DeviceStatusService deviceStatusService;

    public StatusController(DeviceStatusService deviceStatusService) {
        this.deviceStatusService = deviceStatusService;
    }

    @Operation(summary = "Get device statuses")
    @ApiResponse(responseCode = "200", description = "List of device statuses")
    @Get("/devices")
    @Produces(MediaType.APPLICATION_JSON)
    public List<DeviceStatusResponse> getDeviceStatuses() {
        return deviceStatusService.getAllStatuses();
    }

    @Operation(summary = "Check external drive", description = "Streams SSE events with drive detection steps")
    @ApiResponse(responseCode = "200", description = "SSE stream")
    @Post("/check-drive")
    @Produces(MediaType.TEXT_EVENT_STREAM)
    public Publisher<Event<DeviceCheckEvent>> checkDrive() {
        return deviceStatusService.checkDriveStream()
                .map(evt -> Event.of(evt).name(evt.terminal() ? "device-check-final" : "device-check-step"));
    }

    @Operation(summary = "Check iPhone", description = "Streams SSE events with iPhone detection steps")
    @ApiResponse(responseCode = "200", description = "SSE stream")
    @Post("/check-iphone")
    @Produces(MediaType.TEXT_EVENT_STREAM)
    public Publisher<Event<DeviceCheckEvent>> checkIPhone() {
        return deviceStatusService.checkIPhoneStream()
                .map(evt -> Event.of(evt).name(evt.terminal() ? "device-check-final" : "device-check-step"));
    }

    @Operation(summary = "Check iCloud connectivity", description = "Streams SSE events with iCloud check steps")
    @ApiResponse(responseCode = "200", description = "SSE stream")
    @Post("/check-icloud")
    @Produces(MediaType.TEXT_EVENT_STREAM)
    public Publisher<Event<DeviceCheckEvent>> checkICloud() {
        return deviceStatusService.checkICloudStream()
                .map(evt -> Event.of(evt).name(evt.terminal() ? "device-check-final" : "device-check-step"));
    }

    @Operation(summary = "Unmount iPhone", description = "Unmounts the iPhone from the FUSE mount point")
    @ApiResponse(responseCode = "200", description = "Unmount result")
    @Post("/iphone/unmount")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> unmountIPhone() {
        return deviceStatusService.unmountIPhone();
    }
}
