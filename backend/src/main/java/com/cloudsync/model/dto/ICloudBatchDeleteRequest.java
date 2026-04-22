package com.cloudsync.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.micronaut.serde.annotation.Serdeable;

import java.util.List;

@Serdeable
public record ICloudBatchDeleteRequest(@JsonProperty("photo_ids") List<String> photoIds) {}
