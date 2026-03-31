package com.cloudsync.controller;

import com.cloudsync.model.dto.DeviceStatusResponse;
import com.cloudsync.service.DeviceStatusService;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;

import java.util.List;

@Controller("/api/status")
@Secured(SecurityRule.IS_AUTHENTICATED)
public class StatusController {

    private final DeviceStatusService deviceStatusService;

    public StatusController(DeviceStatusService deviceStatusService) {
        this.deviceStatusService = deviceStatusService;
    }

    @Get("/devices")
    @Produces(MediaType.APPLICATION_JSON)
    public List<DeviceStatusResponse> getDeviceStatuses() {
        return deviceStatusService.getAllStatuses();
    }

    @Post("/check-drive")
    @Produces(MediaType.APPLICATION_JSON)
    public HttpResponse<DeviceStatusResponse> checkDrive() {
        return HttpResponse.ok(deviceStatusService.checkDrive());
    }

    @Post("/check-iphone")
    @Produces(MediaType.APPLICATION_JSON)
    public HttpResponse<DeviceStatusResponse> checkIPhone() {
        return HttpResponse.ok(deviceStatusService.checkIPhone());
    }
}
