package com.cloudsync.model.dto;

import io.micronaut.serde.annotation.Serdeable;

import java.util.List;

@Serdeable
public record GenerateThumbnailsRequest(String storageDeviceId, List<String> photoIds) {}
