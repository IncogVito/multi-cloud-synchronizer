package com.cloudsync.controller;

import com.cloudsync.model.dto.AccountResponse;
import com.cloudsync.model.dto.LoginRequest;
import com.cloudsync.model.dto.LoginResponse;
import com.cloudsync.model.dto.TwoFaRequest;
import com.cloudsync.model.dto.TwoFaResponse;
import com.cloudsync.service.AccountService;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.*;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.List;

@Tag(name = "Accounts")
@Controller("/api/accounts")
@Secured(SecurityRule.IS_AUTHENTICATED)
@ExecuteOn(TaskExecutors.BLOCKING)
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @Operation(summary = "Login", description = "Authenticate with iCloud credentials")
    @ApiResponse(responseCode = "200", description = "Login successful or 2FA required")
    @Post("/login")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public LoginResponse login(@Body LoginRequest request) {
        return accountService.login(request);
    }

    @Operation(summary = "Submit 2FA code")
    @ApiResponse(responseCode = "200", description = "2FA verified")
    @Post("/2fa")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public TwoFaResponse twoFa(@Body TwoFaRequest request) {
        return accountService.twoFa(request);
    }

    @Operation(summary = "List accounts")
    @ApiResponse(responseCode = "200", description = "List of iCloud accounts")
    @Get
    @Produces(MediaType.APPLICATION_JSON)
    public List<AccountResponse> listAccounts() {
        return accountService.listAccounts();
    }

    @Operation(summary = "Get account session status")
    @ApiResponse(responseCode = "200", description = "Account found")
    @ApiResponse(responseCode = "404", description = "Account not found")
    @Get("/{id}/status")
    @Produces(MediaType.APPLICATION_JSON)
    public HttpResponse<AccountResponse> getAccountStatus(@PathVariable String id) {
        return accountService.getAccountStatus(id)
                .map(HttpResponse::ok)
                .orElse(HttpResponse.notFound());
    }

    @Operation(summary = "Delete account")
    @ApiResponse(responseCode = "204", description = "Account deleted")
    @Delete("/{id}")
    public HttpResponse<Void> deleteAccount(@PathVariable String id) {
        accountService.deleteAccount(id);
        return HttpResponse.noContent();
    }
}
