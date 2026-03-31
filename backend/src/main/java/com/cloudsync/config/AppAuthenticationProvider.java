package com.cloudsync.config;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpRequest;
import io.micronaut.security.authentication.AuthenticationProvider;
import io.micronaut.security.authentication.AuthenticationRequest;
import io.micronaut.security.authentication.AuthenticationResponse;
import jakarta.inject.Singleton;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;

@Singleton
public class AppAuthenticationProvider implements AuthenticationProvider<HttpRequest<?>> {

    private final String username;
    private final String password;

    public AppAuthenticationProvider(
            @jakarta.inject.Named("appUsername") String username,
            @jakarta.inject.Named("appPassword") String password) {
        this.username = username;
        this.password = password;
    }

    @Override
    public Publisher<AuthenticationResponse> authenticate(
            @Nullable HttpRequest<?> httpRequest,
            AuthenticationRequest<?, ?> authenticationRequest) {

        String identity = authenticationRequest.getIdentity().toString();
        String secret = authenticationRequest.getSecret().toString();

        if (username.equals(identity) && password.equals(secret)) {
            return Flux.just(AuthenticationResponse.success(identity));
        }
        return Flux.just(AuthenticationResponse.failure("Invalid credentials"));
    }
}
