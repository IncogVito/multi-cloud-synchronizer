package com.cloudsync.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record ICloudPhotoDeleteItem(
        @JsonProperty("photo_id") String photoId,
        @JsonProperty("asset_record_name") String assetRecordName
) {}
