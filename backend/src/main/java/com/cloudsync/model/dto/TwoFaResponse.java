package com.cloudsync.model.dto;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record TwoFaResponse(boolean authenticated, String message) {}
