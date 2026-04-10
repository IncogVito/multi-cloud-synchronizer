package com.cloudsync.model.dto;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import java.util.List;

@Serdeable
public record ICloudPhotoListResponse(
    List<ICloudPhotoAsset> photos,
    @Nullable Integer total
) {}
