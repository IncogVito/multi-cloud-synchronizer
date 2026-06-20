package com.cloudsync.util;

import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Shared logic for placing media files on disk by capture date: {@code basePath/yyyy/MM}, or
 * {@code basePath/unknown} when the date is missing. Used by both the sync/reorganize flow and the
 * date-reindex job so they agree on where a file belongs.
 */
public final class MediaPathUtil {

    private MediaPathUtil() {}

    /** Resolves the year/month directory for a capture date, falling back to the system zone. */
    public static Path resolveDateDir(String basePath, Instant createdDate) {
        return resolveDateDir(basePath, createdDate, null);
    }

    /**
     * Resolves the year/month directory a photo belongs in, bucketing by its <em>local</em> capture
     * date. {@code timezone} is the stored ISO offset id (e.g. "+12:00"); when null/unparseable the
     * system default zone is used so behaviour matches the legacy single-arg form.
     */
    public static Path resolveDateDir(String basePath, Instant createdDate, String timezone) {
        if (createdDate == null) {
            return Path.of(basePath, "unknown");
        }
        LocalDate date = createdDate.atZone(zoneFor(timezone)).toLocalDate();
        return Path.of(basePath,
                String.valueOf(date.getYear()),
                String.format("%02d", date.getMonthValue()));
    }

    private static ZoneId zoneFor(String timezone) {
        if (timezone == null || timezone.isBlank()) return ZoneId.systemDefault();
        try {
            return ZoneOffset.of(timezone.trim());
        } catch (DateTimeException e) {
            return ZoneId.systemDefault();
        }
    }
}
