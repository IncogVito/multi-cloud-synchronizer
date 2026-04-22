package com.cloudsync.model.dto;

import io.micronaut.serde.annotation.Serdeable;

import java.util.List;

@Serdeable
public record ICloudBatchDeleteResponse(List<ICloudBatchDeleteResult> results) {}
