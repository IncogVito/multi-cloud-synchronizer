package com.cloudsync.util;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.mov.QuickTimeDirectory;
import com.drew.metadata.mp4.Mp4Directory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;
import java.util.List;

public final class ExifDateUtil {

    private static final Logger LOG = LoggerFactory.getLogger(ExifDateUtil.class);

    private ExifDateUtil() {}

    /**
     * Reads the earliest reliable capture date from file metadata.
     * Priority: EXIF DateTimeOriginal → QuickTime creation time → MP4 creation time → fallback.
     */
    public static Instant readCaptureDate(Path file, Instant fallback) {
        if (file == null) return fallback;
        try {
            com.drew.metadata.Metadata metadata = ImageMetadataReader.readMetadata(file.toFile());

            for (Extractor extractor : EXTRACTORS) {
                Instant date = extractor.extract(metadata);
                if (date != null) return date;
            }
        } catch (Exception e) {
            Path name = file.getFileName();
            LOG.debug("Could not read metadata from {}: {}", name != null ? name : file, e.getMessage());
        }
        return fallback;
    }

    // ── extractors ────────────────────────────────────────────────────────────

    private interface Extractor {
        Instant extract(com.drew.metadata.Metadata metadata);
    }

    private static final List<Extractor> EXTRACTORS = List.of(
        metadata -> dateToInstant(getDate(metadata, ExifSubIFDDirectory.class, ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL)),
        metadata -> dateToInstant(getDate(metadata, QuickTimeDirectory.class, QuickTimeDirectory.TAG_CREATION_TIME)),
        metadata -> dateToInstant(getDate(metadata, Mp4Directory.class, Mp4Directory.TAG_CREATION_TIME))
    );

    private static <T extends Directory> Date getDate(com.drew.metadata.Metadata metadata, Class<T> type, int tag) {
        T dir = metadata.getFirstDirectoryOfType(type);
        return dir != null ? dir.getDate(tag) : null;
    }

    private static Instant dateToInstant(Date date) {
        return date != null ? date.toInstant() : null;
    }
}
