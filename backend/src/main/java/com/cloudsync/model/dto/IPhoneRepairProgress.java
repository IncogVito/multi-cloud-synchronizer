package com.cloudsync.model.dto;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record IPhoneRepairProgress(int checked, int total, int newPending, int missingFixed, boolean done) {}
