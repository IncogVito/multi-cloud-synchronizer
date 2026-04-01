package com.cloudsync.controller;

import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Health")
@Controller("/api/health")
@Secured(SecurityRule.IS_ANONYMOUS)
public class HealthController {

    @Operation(summary = "Health check")
    @ApiResponse(responseCode = "200", description = "Service is up")
    @Get
    @Produces(MediaType.APPLICATION_JSON)
    public HealthResponse health() {
        return new HealthResponse("ok", "1.0.0");
    }

    @Serdeable
    public record HealthResponse(String status, String version) {}
}
