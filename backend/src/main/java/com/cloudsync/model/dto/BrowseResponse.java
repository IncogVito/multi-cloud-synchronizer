package com.cloudsync.model.dto;

import io.micronaut.serde.annotation.Serdeable;

import java.util.List;

@Serdeable
public record BrowseResponse(String path, List<BrowseEntry> entries) {}
