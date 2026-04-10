package com.cloudsync.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.micronaut.serde.annotation.Serdeable;
import java.time.Instant;

@Serdeable
@JsonIgnoreProperties(ignoreUnknown = true)
public record ICloudPhotoAsset(
    String id,
    String filename,
    Long size,
    @JsonProperty("created_date") Instant createdDate,
    Integer width,
    Integer height,
    @JsonProperty("asset_token") String assetToken
) {}
