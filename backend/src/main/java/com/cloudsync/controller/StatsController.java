package com.cloudsync.controller;

import com.cloudsync.model.dto.StatsResponse;
import com.cloudsync.service.StatsService;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Stats")
@Controller("/api/stats")
@Secured(SecurityRule.IS_AUTHENTICATED)
public class StatsController {

    private final StatsService statsService;

    public StatsController(StatsService statsService) {
        this.statsService = statsService;
    }

    @Operation(summary = "Get storage statistics",
               description = "Returns photo counts and sizes for disk, iCloud and iPhone within a storage device context")
    @ApiResponse(responseCode = "200", description = "Stats overview")
    @Get("/overview")
    @Produces(MediaType.APPLICATION_JSON)
    public StatsResponse getOverview(@QueryValue String storageDeviceId) {
        return statsService.getStats(storageDeviceId);
    }
}
