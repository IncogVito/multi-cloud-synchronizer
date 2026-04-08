package com.cloudsync.model.dto;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record BrowseEntry(String name, String path, boolean dir, int childCount) {}
