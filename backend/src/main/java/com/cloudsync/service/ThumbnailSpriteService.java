package com.cloudsync.service;

import com.cloudsync.model.dto.SpriteManifest;
import com.cloudsync.model.dto.SpriteSlot;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Singleton
public class ThumbnailSpriteService {

    private static final Logger LOG = LoggerFactory.getLogger(ThumbnailSpriteService.class);
    private static final int THUMB_SIZE = 300;
    private static final int MAX_COLS = 25;
    private static final float SPRITE_QUALITY = 0.85f;
    private static final int MAX_SPRITE_AGE_DAYS = 7;

    private final String thumbnailDir;
    private final ConcurrentHashMap<String, Object> generationLocks = new ConcurrentHashMap<>();

    public ThumbnailSpriteService(@Named("thumbnailDir") String thumbnailDir) {
        this.thumbnailDir = thumbnailDir;
    }

    public SpriteManifest buildSpriteManifest(List<String> photoIds) throws IOException {
        List<String> available = photoIds.stream()
            .filter(id -> Files.exists(thumbPath(id)))
            .toList();

        if (available.isEmpty()) {
            return new SpriteManifest("empty", 0, 0, Map.of());
        }

        int count = available.size();
        int cols = Math.min(count, MAX_COLS);
        int rows = (count + cols - 1) / cols;
        int spriteWidth = cols * THUMB_SIZE;
        int spriteHeight = rows * THUMB_SIZE;

        String spriteId = computeSpriteId(available);
        Map<String, SpriteSlot> slots = buildSlots(available, cols);

        Path spritePath = spritePath(spriteId);
        if (!Files.exists(spritePath)) {
            generateSprite(spriteId, slots, spritePath, spriteWidth, spriteHeight);
        }

        return new SpriteManifest(spriteId, spriteWidth, spriteHeight, slots);
    }

    public Optional<byte[]> getSpriteBytes(String spriteId) throws IOException {
        if (!spriteId.matches("[a-f0-9]+")) return Optional.empty();
        Path path = spritePath(spriteId);
        if (!Files.exists(path)) return Optional.empty();
        return Optional.of(Files.readAllBytes(path));
    }

    @Scheduled(fixedDelay = "24h", initialDelay = "24h")
    void cleanupOldSprites() {
        Path spritesDir = Path.of(thumbnailDir, "sprites");
        if (!Files.exists(spritesDir)) return;
        Instant cutoff = Instant.now().minus(MAX_SPRITE_AGE_DAYS, ChronoUnit.DAYS);
        int removed = 0;
        try (var stream = Files.list(spritesDir)) {
            for (Path file : stream.toList()) {
                try {
                    BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
                    if (attrs.lastModifiedTime().toInstant().isBefore(cutoff)) {
                        Files.deleteIfExists(file);
                        removed++;
                    }
                } catch (IOException e) {
                    LOG.warn("Failed to check/delete sprite {}: {}", file, e.getMessage());
                }
            }
        } catch (IOException e) {
            LOG.warn("Failed to list sprites dir: {}", e.getMessage());
        }
        if (removed > 0) LOG.debug("Cleaned up {} old sprite files", removed);
    }

    private void generateSprite(String spriteId, Map<String, SpriteSlot> slots,
                                 Path spritePath, int width, int height) throws IOException {
        Object lock = generationLocks.computeIfAbsent(spriteId, k -> new Object());
        synchronized (lock) {
            if (Files.exists(spritePath)) return;
            Files.createDirectories(spritePath.getParent());
            doGenerateSprite(slots, spritePath, width, height);
            LOG.debug("Generated sprite {} ({}x{}, {} slots)", spriteId, width, height, slots.size());
        }
        generationLocks.remove(spriteId);
    }

    private void doGenerateSprite(Map<String, SpriteSlot> slots, Path spritePath,
                                   int width, int height) throws IOException {
        BufferedImage sprite = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sprite.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, width, height);

        for (Map.Entry<String, SpriteSlot> entry : slots.entrySet()) {
            Path thumb = thumbPath(entry.getKey());
            try {
                BufferedImage img = ImageIO.read(thumb.toFile());
                if (img != null) {
                    SpriteSlot slot = entry.getValue();
                    g.drawImage(img, slot.x(), slot.y(), slot.w(), slot.h(), null);
                    img.flush();
                }
            } catch (Exception e) {
                LOG.warn("Could not load thumbnail for sprite slot {}: {}", entry.getKey(), e.getMessage());
            }
        }
        g.dispose();

        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(SPRITE_QUALITY);
        try (FileOutputStream fos = new FileOutputStream(spritePath.toFile());
             ImageOutputStream ios = ImageIO.createImageOutputStream(fos)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(sprite, null, null), param);
        } finally {
            writer.dispose();
            sprite.flush();
        }
    }

    private static Map<String, SpriteSlot> buildSlots(List<String> available, int cols) {
        Map<String, SpriteSlot> slots = new LinkedHashMap<>();
        for (int i = 0; i < available.size(); i++) {
            int col = i % cols;
            int row = i / cols;
            slots.put(available.get(i), new SpriteSlot(col * THUMB_SIZE, row * THUMB_SIZE, THUMB_SIZE, THUMB_SIZE));
        }
        return slots;
    }

    private static String computeSpriteId(List<String> sortedPhotoIds) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String id : sortedPhotoIds) {
                digest.update(id.getBytes());
                digest.update((byte) ',');
            }
            return HexFormat.of().formatHex(digest.digest()).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private Path thumbPath(String photoId) {
        return Path.of(thumbnailDir, photoId + ".jpg");
    }

    private Path spritePath(String spriteId) {
        return Path.of(thumbnailDir, "sprites", spriteId + ".jpg");
    }
}
