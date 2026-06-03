package com.cloudsync.model.dto;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record MergeDuplicatesProgress(int checked, int total, int merged, int deleted, boolean done) {}
