package com.cloudsync.model.dto;

import io.micronaut.serde.annotation.Serdeable;
import java.time.Instant;

/**
 * Summary of photos for a single calendar month.
 * Used by the month-based browsing UI to build the TOC sidebar.
 */
@Serdeable
public record MonthSummaryResponse(
        /** ISO year-month key, e.g. "2024-03". Used as stable identifier. */
        String yearMonth,
        /** Human-readable label, e.g. "March 2024". */
        String label,
        /** Number of photos with createdDate in this month. */
        long photoCount,
        /** Combined file size of all photos in this month, in bytes. */
        long totalSizeBytes,
        /** Earliest createdDate among photos in this month. Null if month has no photos. */
        Instant earliestDate,
        /** Latest createdDate among photos in this month. Null if month has no photos. */
        Instant latestDate,
        /** Photos that exist on iCloud but not on iPhone. */
        long icloudOnlyCount,
        /** Photos that exist on iPhone but not on iCloud. */
        long iphoneOnlyCount
) {
}
