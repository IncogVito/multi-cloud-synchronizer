package com.cloudsync.model.dto;

import io.micronaut.serde.annotation.Serdeable;

import java.util.Map;

@Serdeable
public record SpriteManifest(
    String spriteId,
    int spriteWidth,
    int spriteHeight,
    Map<String, SpriteSlot> slots
) {}
