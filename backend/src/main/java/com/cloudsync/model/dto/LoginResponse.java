package com.cloudsync.model.dto;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record LoginResponse(String sessionId, boolean requires2fa, String accountId) {}
