package com.cloudsync.controller;

import com.cloudsync.model.dto.DeviceCheckEvent;
import com.cloudsync.model.dto.DeviceStatusResponse;
import com.cloudsync.service.DeviceStatusService;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.sse.Event;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;

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
    @Produces(MediaType.TEXT_EVENT_STREAM)
    public Publisher<Event<DeviceCheckEvent>> checkDrive() {
        return deviceStatusService.checkDriveStream()
                .map(evt -> Event.of(evt).name(evt.terminal() ? "device-check-final" : "device-check-step"));
    }

    @Post("/check-iphone")
    @Produces(MediaType.TEXT_EVENT_STREAM)
    public Publisher<Event<DeviceCheckEvent>> checkIPhone() {
        return deviceStatusService.checkIPhoneStream()
                .map(evt -> Event.of(evt).name(evt.terminal() ? "device-check-final" : "device-check-step"));
    }

    @Post("/check-icloud")
    @Produces(MediaType.TEXT_EVENT_STREAM)
    public Publisher<Event<DeviceCheckEvent>> checkICloud() {
        return deviceStatusService.checkICloudStream()
                .map(evt -> Event.of(evt).name(evt.terminal() ? "device-check-final" : "device-check-step"));
    }
}
