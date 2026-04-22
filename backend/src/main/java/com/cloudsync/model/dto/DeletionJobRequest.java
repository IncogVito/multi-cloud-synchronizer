package com.cloudsync.model.dto;

import io.micronaut.serde.annotation.Serdeable;

import java.util.List;

@Serdeable
public record DeletionJobRequest(String accountId, List<String> photoIds, String provider) {}
