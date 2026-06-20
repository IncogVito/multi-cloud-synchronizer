package com.cloudsync.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Regression tests for {@link ExifDateUtil} MP4 handling. Before the fix, MP4 creation time was
 * not read by the disk-indexing path at all, so videos were dated from the filesystem instead of
 * their real capture date.
 */
class ExifDateUtilTest {

    /** Offset in seconds between the MP4/QuickTime epoch (1904-01-01) and the Unix epoch (1970-01-01). */
    private static final long MP4_EPOCH_OFFSET = 2_082_844_800L;

    @Test
    void readsCreationTimeFromMp4MovieHeader(@TempDir Path dir) throws Exception {
        Instant captured = Instant.parse("2021-06-15T12:00:00Z");
        Path file = dir.resolve("VID_0001.mp4");
        Files.write(file, minimalMp4(captured.getEpochSecond() + MP4_EPOCH_OFFSET));

        Instant result = ExifDateUtil.readCaptureDate(file, null);

        assertEquals(captured, result, "MP4 creation_time should be read from the mvhd box");
    }

    @Test
    void rejectsZeroMp4CreationTimeAsEpochGarbage(@TempDir Path dir) throws Exception {
        // creation_time == 0 decodes to 1904-01-01, which is meaningless and must be ignored.
        Path file = dir.resolve("VID_0002.mp4");
        Files.write(file, minimalMp4(0L));

        assertNull(ExifDateUtil.readCaptureDate(file, null),
                "Zeroed MP4 creation_time must fall through, not return the 1904 epoch");
    }

    @Test
    void mp4MovieHeaderHasNoZoneOffset(@TempDir Path dir) throws Exception {
        // The mvhd creation_time is a UTC instant with no zone information attached.
        Instant captured = Instant.parse("2021-06-15T12:00:00Z");
        Path file = dir.resolve("VID_0003.mp4");
        Files.write(file, minimalMp4(captured.getEpochSecond() + MP4_EPOCH_OFFSET));

        ExifDateUtil.CaptureDate cd = ExifDateUtil.readCaptureDateWithZone(file, null);

        assertEquals(captured, cd.instant());
        assertNull(cd.offset(), "MP4 mvhd carries no timezone offset");
    }

    // ── EXIF offset handling ─────────────────────────────────────────────────────

    @Test
    void exifWallClockMinusOffsetGivesUtc() {
        // JPG stores wall-clock "12:31:36" (no zone in the value) plus a separate "+12:00" tag.
        // True UTC = 12:31:36 − 12h = 00:31:36Z; the offset is kept so the UI can show local time.
        java.util.Date wall = java.util.Date.from(Instant.parse("2026-06-07T12:31:36Z"));
        ExifDateUtil.CaptureDate cd = ExifDateUtil.applyOffset(wall, "+12:00");

        assertEquals(Instant.parse("2026-06-07T00:31:36Z"), cd.instant());
        assertEquals(ZoneOffset.ofHours(12), cd.offset());
    }

    @Test
    void exifWithoutOffsetKeepsWallClockAndNullZone() {
        java.util.Date wall = java.util.Date.from(Instant.parse("2026-06-07T12:31:36Z"));
        ExifDateUtil.CaptureDate cd = ExifDateUtil.applyOffset(wall, null);

        assertEquals(Instant.parse("2026-06-07T12:31:36Z"), cd.instant());
        assertNull(cd.offset());
    }

    @Test
    void parsesOffsetInVariousFormats() {
        assertEquals(ZoneOffset.ofHours(12), ExifDateUtil.parseOffset("+12:00"));
        assertEquals(ZoneOffset.ofHours(12), ExifDateUtil.parseOffset("+1200"));
        assertEquals(ZoneOffset.ofHoursMinutes(5, 30), ExifDateUtil.parseOffset("+05:30"));
        assertEquals(ZoneOffset.ofHours(-8), ExifDateUtil.parseOffset("-08:00"));
        assertEquals(ZoneOffset.UTC, ExifDateUtil.parseOffset("Z"));
        assertNull(ExifDateUtil.parseOffset(null));
        assertNull(ExifDateUtil.parseOffset(""));
        assertNull(ExifDateUtil.parseOffset("garbage"));
    }

    // ── QuickTime metadata (local date with embedded offset) ─────────────────────

    @Test
    void parsesQuickTimeMetadataLocalDateWithOffset() {
        // "[QuickTime Metadata] Creation Date - 2026-06-07T12:31:47+1200"
        ExifDateUtil.CaptureDate cd = ExifDateUtil.parseOffsetDateTime("2026-06-07T12:31:47+1200");

        assertEquals(Instant.parse("2026-06-07T00:31:47Z"), cd.instant());
        assertEquals(ZoneOffset.ofHours(12), cd.offset());
    }

    @Test
    void parsesQuickTimeMetadataWithColonOffset() {
        ExifDateUtil.CaptureDate cd = ExifDateUtil.parseOffsetDateTime("2026-06-07T12:31:47+12:00");

        assertEquals(Instant.parse("2026-06-07T00:31:47Z"), cd.instant());
        assertEquals(ZoneOffset.ofHours(12), cd.offset());
    }

    // ── filename-derived date (last resort) ──────────────────────────────────────

    @Test
    void filenameScreenshotIsoWithDash() {
        // "Screenshot_20190615-133747_SmartThings.jpg" → yyyyMMdd-HHmmss
        ExifDateUtil.CaptureDate cd = ExifDateUtil.fromFileName("Screenshot_20190615-133747_SmartThings.jpg");

        assertEquals(Instant.parse("2019-06-15T13:37:47Z"), cd.instant());
        assertNull(cd.offset(), "filename dates carry no timezone");
    }

    @Test
    void filenameImgIsoWithUnderscore() {
        // "IMG_20201107_001553_211.jpg" → yyyyMMdd_HHmmss (trailing _211 ignored)
        ExifDateUtil.CaptureDate cd = ExifDateUtil.fromFileName("IMG_20201107_001553_211.jpg");

        assertEquals(Instant.parse("2020-11-07T00:15:53Z"), cd.instant());
        assertNull(cd.offset());
    }

    @Test
    void filenameImgIsoWithUnderscoreSecond() {
        // "IMG_20200906_121132_779.jpg"
        ExifDateUtil.CaptureDate cd = ExifDateUtil.fromFileName("IMG_20200906_121132_779.jpg");

        assertEquals(Instant.parse("2020-09-06T12:11:32Z"), cd.instant());
        assertNull(cd.offset());
    }

    @Test
    void filenameZomboMemeDayMonthYear() {
        // "ZomboMeme 30052021144801.jpg" → bare 14 digits ddMMyyyyHHmmss
        ExifDateUtil.CaptureDate cd = ExifDateUtil.fromFileName("ZomboMeme 30052021144801.jpg");

        assertEquals(Instant.parse("2021-05-30T14:48:01Z"), cd.instant());
        assertNull(cd.offset());
    }

    @Test
    void filenameZomboMemeDayMonthYearSecond() {
        // "ZomboMeme 15122019233608.jpg"
        ExifDateUtil.CaptureDate cd = ExifDateUtil.fromFileName("ZomboMeme 15122019233608.jpg");

        assertEquals(Instant.parse("2019-12-15T23:36:08Z"), cd.instant());
        assertNull(cd.offset());
    }

    @Test
    void filenameThreeHeadedDragonDayMonthYear() {
        // "Three-Headed Dragon 31012020223142.jpg" — dash in name must not confuse parsing
        ExifDateUtil.CaptureDate cd = ExifDateUtil.fromFileName("Three-Headed Dragon 31012020223142.jpg");

        assertEquals(Instant.parse("2020-01-31T22:31:42Z"), cd.instant());
        assertNull(cd.offset());
    }

    @Test
    void filenameCombinedMemeDayMonthYear() {
        // "CombinedMeme 11082019153615.jpg"
        ExifDateUtil.CaptureDate cd = ExifDateUtil.fromFileName("CombinedMeme 11082019153615.jpg");

        assertEquals(Instant.parse("2019-08-11T15:36:15Z"), cd.instant());
        assertNull(cd.offset());
    }

    @Test
    void filenameEpochMillis() {
        // "1565317981366.jpg" → 13-digit Unix epoch milliseconds
        ExifDateUtil.CaptureDate cd = ExifDateUtil.fromFileName("1565317981366.jpg");

        assertEquals(Instant.ofEpochMilli(1565317981366L), cd.instant());
        assertNull(cd.offset());
    }

    @Test
    void filenameWithNoDateReturnsNull() {
        assertNull(ExifDateUtil.fromFileName("vacation.jpg"));
        assertNull(ExifDateUtil.fromFileName("DSC_0001.jpg"));
        assertNull(ExifDateUtil.fromFileName(null));
    }

    @Test
    void filenameDateUsedAsLastResortWhenNoMetadata(@TempDir Path dir) throws Exception {
        // A bare JPEG (no EXIF) named with a date must fall back to the filename, not the fallback arg.
        Path file = dir.resolve("ZomboMeme 30052021144801.jpg");
        Files.write(file, minimalJpeg());

        ExifDateUtil.CaptureDate cd = ExifDateUtil.readCaptureDateWithZone(file, Instant.parse("2000-01-01T00:00:00Z"));

        assertEquals(Instant.parse("2021-05-30T14:48:01Z"), cd.instant());
    }

    /** Smallest valid JPEG (SOI + EOI) carrying no EXIF metadata. */
    private static byte[] minimalJpeg() {
        return new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9 };
    }

    // ── minimal MP4 builder ─────────────────────────────────────────────────────

    /** Builds a tiny but valid MP4 (ftyp + moov>mvhd) carrying the given creation_time. */
    private static byte[] minimalMp4(long creationTimeSecs) throws Exception {
        byte[] ftyp = box("ftyp", new byte[] {
                'i', 's', 'o', 'm',              // major_brand
                0, 0, 2, 0,                      // minor_version
                'i', 's', 'o', 'm', 'm', 'p', '4', '2' // compatible_brands
        });

        ByteBuffer mvhd = ByteBuffer.allocate(100);
        mvhd.put((byte) 0).put(new byte[3]);     // version=0, flags
        mvhd.putInt((int) creationTimeSecs);     // creation_time
        mvhd.putInt((int) creationTimeSecs);     // modification_time
        mvhd.putInt(600);                        // timescale
        mvhd.putInt(600);                        // duration
        mvhd.putInt(0x00010000);                 // rate 1.0
        mvhd.putShort((short) 0x0100);           // volume 1.0
        mvhd.position(mvhd.position() + 10);     // reserved (2 + 8)
        mvhd.position(mvhd.position() + 36);     // matrix
        mvhd.position(mvhd.position() + 24);     // pre_defined
        mvhd.putInt(2);                          // next_track_id

        byte[] moov = box("moov", box("mvhd", mvhd.array()));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(ftyp);
        out.write(moov);
        return out.toByteArray();
    }

    private static byte[] box(String type, byte[] payload) {
        ByteBuffer buf = ByteBuffer.allocate(8 + payload.length);
        buf.putInt(8 + payload.length);
        buf.put(type.getBytes());
        buf.put(payload);
        return buf.array();
    }
}
