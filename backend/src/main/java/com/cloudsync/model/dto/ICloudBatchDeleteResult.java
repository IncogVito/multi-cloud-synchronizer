package com.cloudsync.model.dto;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record ICloudBatchDeleteResult(String photoId, boolean deleted, String error) {}
