package com.cloudsync.config;

import com.cloudsync.repository.AccountRepository;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpRequest;
import io.micronaut.security.authentication.AuthenticationProvider;
import io.micronaut.security.authentication.AuthenticationRequest;
import io.micronaut.security.authentication.AuthenticationResponse;
import jakarta.inject.Singleton;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

@Singleton
public class AppAuthenticationProvider implements AuthenticationProvider<HttpRequest<?>> {

    private static final Logger LOG = LoggerFactory.getLogger(AppAuthenticationProvider.class);

    private final String appUsername;
    private final String appPassword;
    private final AccountRepository accountRepository;

    public AppAuthenticationProvider(
            @jakarta.inject.Named("appUsername") String appUsername,
            @jakarta.inject.Named("appPassword") String appPassword,
            AccountRepository accountRepository) {
        this.appUsername = appUsername;
        this.appPassword = appPassword;
        this.accountRepository = accountRepository;
    }

    @Override
    public Publisher<AuthenticationResponse> authenticate(
            @Nullable HttpRequest<?> httpRequest,
            AuthenticationRequest<?, ?> authenticationRequest) {

        String identity = authenticationRequest.getIdentity().toString();
        String secret = authenticationRequest.getSecret().toString();

        LOG.debug("[AUTH] Attempting authentication for identity: {}", identity);

        // First, check if credentials match the app admin credentials
        if (appUsername.equals(identity) && appPassword.equals(secret)) {
            LOG.debug("[AUTH] Admin authentication successful");
            return Flux.just(AuthenticationResponse.success(identity));
        }

        // Second, check if identity is a registered iCloud account (appleId)
        try {
            var account = accountRepository.findByAppleId(identity);
            if (account.isPresent()) {
                LOG.debug("[AUTH] iCloud account authentication successful for appleId: {}", identity);
                return Flux.just(AuthenticationResponse.success(identity));
            }
        } catch (Exception e) {
            LOG.warn("[AUTH] Error checking account repository: {}", e.getMessage());
        }

        LOG.debug("[AUTH] Authentication failed for identity: {}", identity);
        return Flux.just(AuthenticationResponse.failure("Invalid credentials"));
    }
}
