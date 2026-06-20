package com.cloudsync.util;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MediaPathUtilTest {

    @Test
    void bucketsByLocalDateWhenOffsetGiven() {
        // 23:30 UTC on May 31 is 11:30 on June 1 at +12:00 → must land in June, not May.
        Instant utc = Instant.parse("2026-05-31T23:30:00Z");

        Path dir = MediaPathUtil.resolveDateDir("/base", utc, "+12:00");

        assertEquals(Path.of("/base", "2026", "06"), dir);
    }

    @Test
    void negativeOffsetCanShiftToPreviousMonth() {
        // 01:00 UTC on June 1 is 21:00 on May 31 at -04:00.
        Instant utc = Instant.parse("2026-06-01T01:00:00Z");

        Path dir = MediaPathUtil.resolveDateDir("/base", utc, "-04:00");

        assertEquals(Path.of("/base", "2026", "05"), dir);
    }

    @Test
    void unknownDirWhenDateMissing() {
        assertEquals(Path.of("/base", "unknown"),
                MediaPathUtil.resolveDateDir("/base", null, "+12:00"));
    }

    @Test
    void blankOrBadTimezoneFallsBackToSystemZone() {
        Instant utc = Instant.parse("2026-06-15T12:00:00Z");
        // Should not throw; resolves using the system zone like the legacy single-arg form.
        assertEquals(MediaPathUtil.resolveDateDir("/base", utc),
                MediaPathUtil.resolveDateDir("/base", utc, "garbage"));
    }
}
