package com.cloudsync.model.dto;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record ReindexDatesProgress(int checked, int total, int updated, int moved, int errors, boolean done) {}
