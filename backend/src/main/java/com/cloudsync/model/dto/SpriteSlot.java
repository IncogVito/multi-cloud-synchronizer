package com.cloudsync.model.dto;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record SpriteSlot(int x, int y, int w, int h) {}
