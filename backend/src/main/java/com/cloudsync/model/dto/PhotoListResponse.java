package com.cloudsync.model.dto;

import io.micronaut.serde.annotation.Serdeable;

import java.util.List;

@Serdeable
public record PhotoListResponse(List<PhotoResponse> photos, long total, int page, int size) {}
