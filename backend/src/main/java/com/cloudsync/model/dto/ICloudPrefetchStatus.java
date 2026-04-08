package com.cloudsync.model.dto;

public record ICloudPrefetchStatus(
    String status,
    int fetched,
    Integer total
) {}
