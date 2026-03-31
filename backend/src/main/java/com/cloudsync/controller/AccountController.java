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

import java.util.List;

@Controller("/api/accounts")
@Secured(SecurityRule.IS_AUTHENTICATED)
@ExecuteOn(TaskExecutors.BLOCKING)
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @Post("/login")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public LoginResponse login(@Body LoginRequest request) {
        return accountService.login(request);
    }

    @Post("/2fa")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public TwoFaResponse twoFa(@Body TwoFaRequest request) {
        return accountService.twoFa(request);
    }

    @Get
    @Produces(MediaType.APPLICATION_JSON)
    public List<AccountResponse> listAccounts() {
        return accountService.listAccounts();
    }

    @Get("/{id}/status")
    @Produces(MediaType.APPLICATION_JSON)
    public HttpResponse<AccountResponse> getAccountStatus(@PathVariable String id) {
        return accountService.getAccountStatus(id)
                .map(HttpResponse::ok)
                .orElse(HttpResponse.notFound());
    }

    @Delete("/{id}")
    public HttpResponse<Void> deleteAccount(@PathVariable String id) {
        accountService.deleteAccount(id);
        return HttpResponse.noContent();
    }
}
