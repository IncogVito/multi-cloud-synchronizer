package com.cloudsync.util;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Directory;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.mov.QuickTimeDirectory;
import com.drew.metadata.mov.metadata.QuickTimeMetadataDirectory;
import com.drew.metadata.mp4.Mp4Directory;
import com.drew.metadata.mp4.media.Mp4MediaDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.Year;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ExifDateUtil {

    private static final Logger LOG = LoggerFactory.getLogger(ExifDateUtil.class);

    /**
     * MP4/QuickTime epoch is 1904-01-01. Files with an unset creation_time (0) decode to that
     * date, which is meaningless. Anything at or before this cutoff is treated as "no date".
     */
    private static final Instant EPOCH_CUTOFF = Instant.parse("1970-01-02T00:00:00Z");

    private ExifDateUtil() {}

    /**
     * Capture date as a true-UTC instant plus the local UTC offset it was recorded at, if known.
     * The offset lets the UI render and sort by the wall-clock time the shot was actually taken
     * (e.g. 00:31Z with {@code +12:00} → 12:31 local), independent of the viewer's timezone.
     *
     * @param offset the recording-location UTC offset, or {@code null} when the source carried no
     *               timezone information (e.g. plain EXIF without an OffsetTime tag, or an MP4 mvhd).
     */
    public record CaptureDate(Instant instant, ZoneOffset offset) {}

    /** ISO-8601 id for a (possibly null) offset, suitable for {@code created_date_timezone}. */
    public static String offsetId(ZoneOffset offset) {
        return offset != null ? offset.getId() : null;
    }

    /**
     * Reads the earliest reliable capture date from file metadata as a true-UTC {@link Instant}.
     * Kept for callers that don't need the timezone; see {@link #readCaptureDateWithZone}.
     */
    public static Instant readCaptureDate(Path file, Instant fallback) {
        return readCaptureDateWithZone(file, fallback).instant();
    }

    /**
     * Reads the earliest reliable capture date together with its UTC offset.
     * Priority: EXIF DateTimeOriginal → EXIF DateTimeDigitized → EXIF DateTime → QuickTime metadata
     * creation date (carries the local offset) → QuickTime creation time → MP4 movie creation time
     * → MP4 media (video track) creation time → fallback.
     */
    public static CaptureDate readCaptureDateWithZone(Path file, Instant fallback) {
        if (file == null) return new CaptureDate(fallback, null);
        try {
            com.drew.metadata.Metadata metadata = ImageMetadataReader.readMetadata(file.toFile());

            for (Extractor extractor : EXTRACTORS) {
                CaptureDate cd = extractor.extract(metadata);
                if (cd != null && cd.instant() != null && cd.instant().isAfter(EPOCH_CUTOFF)) return cd;
            }
        } catch (Exception e) {
            Path name = file.getFileName();
            LOG.debug("Could not read metadata from {}: {}", name != null ? name : file, e.getMessage());
        }
        // Last resort: many screenshots/memes carry no EXIF but encode the date in the file name.
        Path name = file.getFileName();
        if (name != null) {
            CaptureDate cd = fromFileName(name.toString());
            if (cd != null) return cd;
        }
        return new CaptureDate(fallback, null);
    }

    // ── extractors ────────────────────────────────────────────────────────────

    private interface Extractor {
        CaptureDate extract(com.drew.metadata.Metadata metadata);
    }

    private static final List<Extractor> EXTRACTORS = List.of(
        m -> exifDate(m, ExifSubIFDDirectory.class, ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL, ExifSubIFDDirectory.TAG_TIME_ZONE_ORIGINAL),
        m -> exifDate(m, ExifSubIFDDirectory.class, ExifSubIFDDirectory.TAG_DATETIME_DIGITIZED, ExifSubIFDDirectory.TAG_TIME_ZONE_DIGITIZED),
        m -> exifDate(m, ExifIFD0Directory.class, ExifIFD0Directory.TAG_DATETIME, ExifIFD0Directory.TAG_TIME_ZONE),
        // QuickTime metadata creation date is a local timestamp with the offset baked in
        // (e.g. "2026-06-07T12:31:47+1200") — the only MOV source that yields the local offset.
        ExifDateUtil::quickTimeMetadataDate,
            m -> utcDate(m, QuickTimeDirectory.class, QuickTimeDirectory.TAG_CREATION_TIME),
        m -> utcDate(m, Mp4Directory.class, Mp4Directory.TAG_CREATION_TIME),
        // "MP4 Video Directory" — Mp4VideoDirectory extends Mp4MediaDirectory; the mdhd box often
        // carries a valid creation_time even when the movie-level mvhd box is zeroed out.
        m -> utcDate(m, Mp4MediaDirectory.class, Mp4MediaDirectory.TAG_CREATION_TIME)
    );

    /**
     * EXIF date strings ("2026:06:07 12:31:36") carry no zone, so metadata-extractor decodes them
     * as if they were GMT. We treat that as the local wall-clock and convert to true UTC using the
     * separate OffsetTime tag ("+12:00"). With no offset tag we keep the wall-clock as-is.
     */
    private static CaptureDate exifDate(com.drew.metadata.Metadata metadata, Class<? extends Directory> type,
                                        int dateTag, int offsetTag) {
        Directory dir = metadata.getFirstDirectoryOfType(type);
        if (dir == null) return null;
        Date date = dir.getDate(dateTag);
        if (date == null) return null;
        return applyOffset(date, dir.getString(offsetTag));
    }

    private static CaptureDate quickTimeMetadataDate(com.drew.metadata.Metadata metadata) {
        var dateOptional = metadata.getDirectoriesOfType(QuickTimeMetadataDirectory.class)
                .stream().map(
                        directoryType -> directoryType.getString(QuickTimeMetadataDirectory.TAG_CREATION_DATE)
                )
                .filter(Objects::nonNull)
                .map(ExifDateUtil::parseOffsetDateTime)
                .findFirst();
        return dateOptional.orElse(null);
    }

    /** A creation_time that already represents a true UTC instant, with no usable offset. */
    private static CaptureDate utcDate(com.drew.metadata.Metadata metadata, Class<? extends Directory> type, int tag) {
        var dateOptional = metadata.getDirectoriesOfType(type)
                .stream().map(
                        directoryType -> directoryType.getDate(tag)
                ).filter(Objects::nonNull)
                .findFirst();
        return dateOptional.map(date -> new CaptureDate(date.toInstant(), null)).orElse(null);
    }

    // ── parsing helpers (package-private for testing) ───────────────────────────

    /**
     * Converts a wall-clock {@link Date} (decoded as GMT by metadata-extractor) plus a raw offset
     * string into a true-UTC {@link CaptureDate}. A null/blank/unparseable offset leaves the
     * wall-clock instant untouched and reports a null offset.
     */
    static CaptureDate applyOffset(Date wallClock, String rawOffset) {
        Instant wall = wallClock.toInstant();
        ZoneOffset offset = parseOffset(rawOffset);
        if (offset == null) return new CaptureDate(wall, null);
        return new CaptureDate(wall.minusSeconds(offset.getTotalSeconds()), offset);
    }

    /** Parses EXIF OffsetTime forms: "+12:00", "+1200", "+05:30", "-08:00", "Z". Null on failure. */
    static ZoneOffset parseOffset(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        try {
            return ZoneOffset.of(s);
        } catch (DateTimeException e) {
            return null;
        }
    }

    private static final List<DateTimeFormatter> OFFSET_FORMATS = List.of(
        DateTimeFormatter.ISO_OFFSET_DATE_TIME,                       // 2026-06-07T12:31:47+12:00 / ...Z
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ")         // 2026-06-07T12:31:47+1200
    );

    /**
     * Parses a QuickTime-metadata local timestamp with an embedded offset into a true-UTC
     * {@link CaptureDate}. Falls back to treating an offset-less string as UTC wall-clock.
     */
    static CaptureDate parseOffsetDateTime(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        for (DateTimeFormatter f : OFFSET_FORMATS) {
            try {
                OffsetDateTime odt = OffsetDateTime.parse(s, f);
                return new CaptureDate(odt.toInstant(), odt.getOffset());
            } catch (DateTimeException ignored) {
                // try next format
            }
        }
        try {
            LocalDateTime ldt = LocalDateTime.parse(s);
            return new CaptureDate(ldt.toInstant(ZoneOffset.UTC), null);
        } catch (DateTimeException ignored) {
            return null;
        }
    }

    // ── filename-derived date (last resort) ─────────────────────────────────────

    /** Plausible capture-year window; anything outside is treated as a coincidental number. */
    private static final int MIN_YEAR = 1990;
    private static final int MAX_YEAR = Year.now().getValue() + 1;

    // yyyyMMdd separated from HHmmss by a single '-' or '_' (Screenshot_/IMG_ style).
    private static final Pattern ISO_SEPARATED = Pattern.compile("(?<!\\d)(\\d{8})[-_](\\d{6})(?!\\d)");
    // 14 consecutive digits — either yyyyMMddHHmmss or ddMMyyyyHHmmss (ZomboMeme/CombinedMeme style).
    private static final Pattern FOURTEEN_DIGITS = Pattern.compile("(?<!\\d)(\\d{14})(?!\\d)");
    // 13-digit Unix epoch milliseconds.
    private static final Pattern EPOCH_MILLIS = Pattern.compile("(?<!\\d)(\\d{13})(?!\\d)");

    // 'uuuu' (not 'yyyy') for a proleptic year, required by ResolverStyle.STRICT without an era.
    private static final DateTimeFormatter YMD_HMS =
        DateTimeFormatter.ofPattern("uuuuMMddHHmmss").withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter DMY_HMS =
        DateTimeFormatter.ofPattern("ddMMuuuuHHmmss").withResolverStyle(ResolverStyle.STRICT);

    /**
     * Best-effort capture date parsed from a file name when no metadata is available. Recognises
     * {@code yyyyMMdd[-_]HHmmss}, a bare {@code ddMMyyyyHHmmss}/{@code yyyyMMddHHmmss} run of 14
     * digits, and a 13-digit Unix epoch (ms). The wall-clock is taken as UTC with no offset.
     * Returns {@code null} when nothing plausible is found.
     */
    static CaptureDate fromFileName(String fileName) {
        if (fileName == null) return null;

        Matcher sep = ISO_SEPARATED.matcher(fileName);
        if (sep.find()) {
            CaptureDate cd = parseLocal(sep.group(1) + sep.group(2), YMD_HMS);
            if (cd != null) return cd;
        }

        Matcher fourteen = FOURTEEN_DIGITS.matcher(fileName);
        if (fourteen.find()) {
            String digits = fourteen.group(1);
            CaptureDate cd = parseLocal(digits, YMD_HMS);   // 20190615133747
            if (cd == null) cd = parseLocal(digits, DMY_HMS); // 30052021144801
            if (cd != null) return cd;
        }

        Matcher epoch = EPOCH_MILLIS.matcher(fileName);
        if (epoch.find()) {
            Instant i = Instant.ofEpochMilli(Long.parseLong(epoch.group(1)));
            if (plausible(i)) return new CaptureDate(i, null);
        }
        return null;
    }

    private static CaptureDate parseLocal(String digits, DateTimeFormatter format) {
        try {
            LocalDateTime ldt = LocalDateTime.parse(digits, format);
            Instant instant = ldt.toInstant(ZoneOffset.UTC);
            return plausible(instant) ? new CaptureDate(instant, null) : null;
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static boolean plausible(Instant instant) {
        int year = instant.atZone(ZoneOffset.UTC).getYear();
        return year >= MIN_YEAR && year <= MAX_YEAR;
    }
}
