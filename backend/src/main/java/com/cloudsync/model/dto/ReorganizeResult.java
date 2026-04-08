package com.cloudsync.model.dto;

import io.micronaut.serde.annotation.Serdeable;

import java.util.List;

@Serdeable
public record ReorganizeResult(int moved, int skipped, int errors, boolean dryRun, List<MovePreview> sampleMoves) {}
