package com.cloudsync.model.dto;

import java.util.List;

public record ICloudPhotoListResponse(
    List<ICloudPhotoAsset> photos,
    int total
) {}
