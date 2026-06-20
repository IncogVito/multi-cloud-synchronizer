import { describe, expect, it } from 'vitest';
import { captureLocalDate, parseOffsetMinutes } from './capture-date.util';

describe('parseOffsetMinutes', () => {
  it('parses colon and compact forms', () => {
    expect(parseOffsetMinutes('+12:00')).toBe(720);
    expect(parseOffsetMinutes('+1200')).toBe(720);
    expect(parseOffsetMinutes('+05:30')).toBe(330);
    expect(parseOffsetMinutes('-08:00')).toBe(-480);
    expect(parseOffsetMinutes('Z')).toBe(0);
  });

  it('returns null for missing/invalid input', () => {
    expect(parseOffsetMinutes(null)).toBeNull();
    expect(parseOffsetMinutes(undefined)).toBeNull();
    expect(parseOffsetMinutes('')).toBeNull();
    expect(parseOffsetMinutes('garbage')).toBeNull();
  });
});

describe('captureLocalDate', () => {
  it('reads back the capture wall-clock from UTC + offset', () => {
    // 00:31Z taken at +12:00 → local wall-clock is 12:31 the same day.
    const d = captureLocalDate({ createdDate: '2026-06-07T00:31:36Z', createdDateTimezone: '+12:00' });
    expect(d.getFullYear()).toBe(2026);
    expect(d.getMonth()).toBe(5); // June (0-based)
    expect(d.getDate()).toBe(7);
    expect(d.getHours()).toBe(12);
    expect(d.getMinutes()).toBe(31);
  });

  it('rolls the wall-clock date forward across midnight', () => {
    // 23:30Z on May 31 at +12:00 → 11:30 on June 1 local.
    const d = captureLocalDate({ createdDate: '2026-05-31T23:30:00Z', createdDateTimezone: '+12:00' });
    expect(d.getMonth()).toBe(5); // June
    expect(d.getDate()).toBe(1);
    expect(d.getHours()).toBe(11);
  });

  it('falls back to browser-local Date when offset is unknown', () => {
    const iso = '2026-06-07T00:31:36Z';
    const d = captureLocalDate({ createdDate: iso, createdDateTimezone: null });
    expect(d.getTime()).toBe(new Date(iso).getTime());
  });
});
