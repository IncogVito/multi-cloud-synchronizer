/**
 * Capture-time date helpers.
 *
 * `createdDate` is a true-UTC instant; `createdDateTimezone` is the ISO-8601 offset the shot was
 * taken at (e.g. "+12:00", "Z"), or null/empty when unknown. To group, sort and label photos by the
 * wall-clock time they were actually captured — independent of the viewer's browser timezone — we
 * build a Date whose *local* components equal that capture wall-clock. When the offset is unknown we
 * fall back to the browser-local interpretation (the previous behaviour).
 */

type DatedPhoto = { createdDate: string; createdDateTimezone?: string | null };

/** Parses "+12:00" / "+1200" / "+05:30" / "-08:00" / "Z" into minutes east of UTC; null if invalid. */
export function parseOffsetMinutes(timezone: string | null | undefined): number | null {
  if (!timezone) return null;
  const s = timezone.trim();
  if (s === '') return null;
  if (s === 'Z') return 0;
  const m = /^([+-])(\d{2}):?(\d{2})?$/.exec(s);
  if (!m) return null;
  const sign = m[1] === '-' ? -1 : 1;
  const hours = parseInt(m[2], 10);
  const minutes = m[3] ? parseInt(m[3], 10) : 0;
  return sign * (hours * 60 + minutes);
}

/**
 * Returns a Date whose local getters (getFullYear/getMonth/getDate/getHours/getDay/...) yield the
 * capture wall-clock time. Safe to feed into `toLocaleString` for month/year labels.
 */
export function captureLocalDate(photo: DatedPhoto): Date {
  const utc = new Date(photo.createdDate);
  const offsetMin = parseOffsetMinutes(photo.createdDateTimezone);
  if (offsetMin === null) return utc;
  // Shift the instant so its UTC fields equal the capture wall-clock, then re-home those fields
  // into a browser-local Date so the existing local getters read the wall-clock back out.
  const shifted = new Date(utc.getTime() + offsetMin * 60000);
  return new Date(
    shifted.getUTCFullYear(),
    shifted.getUTCMonth(),
    shifted.getUTCDate(),
    shifted.getUTCHours(),
    shifted.getUTCMinutes(),
    shifted.getUTCSeconds(),
  );
}
