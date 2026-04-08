package com.cloudsync.model.dto;

import io.micronaut.serde.annotation.Serdeable;

import java.util.Map;

@Serdeable
public record DiskScanResult(long totalFiles, Map<String, Long> byExtension, int deepestLevel) {}
