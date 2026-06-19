package com.cloudsync.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

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
