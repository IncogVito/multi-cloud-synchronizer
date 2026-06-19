package com.cloudsync.util;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Shared logic for placing media files on disk by capture date: {@code basePath/yyyy/MM}, or
 * {@code basePath/unknown} when the date is missing. Used by both the sync/reorganize flow and the
 * date-reindex job so they agree on where a file belongs.
 */
public final class MediaPathUtil {

    private MediaPathUtil() {}

    /** Resolves the year/month directory a photo with the given capture date belongs in. */
    public static Path resolveDateDir(String basePath, Instant createdDate) {
        if (createdDate == null) {
            return Path.of(basePath, "unknown");
        }
        LocalDate date = createdDate.atZone(ZoneId.systemDefault()).toLocalDate();
        return Path.of(basePath,
                String.valueOf(date.getYear()),
                String.format("%02d", date.getMonthValue()));
    }
}
