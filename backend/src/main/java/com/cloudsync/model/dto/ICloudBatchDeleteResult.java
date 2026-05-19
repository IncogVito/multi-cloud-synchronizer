package com.cloudsync.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record ICloudBatchDeleteResult(@JsonProperty("photo_id") String photoId, boolean deleted, String error) {}
