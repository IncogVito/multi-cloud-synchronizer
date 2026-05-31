import { PhotoResponse } from '../../core/api/generated/model/photoResponse';
import {
  buildGroupsFromPhotos,
  SIZE_BUCKETS,
  sizeBucketIndex,
  sortPhotosByDate,
  sortPhotosBySize,
  parentKey,
  dayKey,
} from './photo-groups.utils';

function makePhoto(id: string, createdDate: string, fileSize: number): PhotoResponse {
  return { id, createdDate, fileSize, filename: `${id}.jpg` } as PhotoResponse;
}

// ─── sizeBucketIndex ──────────────────────────────────────────────────────────

describe('sizeBucketIndex', () => {
  it('≥ 200 MB → bucket 0', () => {
    expect(sizeBucketIndex(200 * 1024 * 1024)).toBe(0);
    expect(sizeBucketIndex(300 * 1024 * 1024)).toBe(0);
  });

  it('100–200 MB → bucket 1', () => {
    expect(sizeBucketIndex(100 * 1024 * 1024)).toBe(1);
    expect(sizeBucketIndex(150 * 1024 * 1024)).toBe(1);
  });

  it('50–100 MB → bucket 2', () => {
    expect(sizeBucketIndex(50 * 1024 * 1024)).toBe(2);
    expect(sizeBucketIndex(75 * 1024 * 1024)).toBe(2);
  });

  it('10–50 MB → bucket 3', () => {
    expect(sizeBucketIndex(10 * 1024 * 1024)).toBe(3);
    expect(sizeBucketIndex(25 * 1024 * 1024)).toBe(3);
  });

  it('1–10 MB → bucket 4', () => {
    expect(sizeBucketIndex(1 * 1024 * 1024)).toBe(4);
    expect(sizeBucketIndex(5 * 1024 * 1024)).toBe(4);
  });

  it('< 1 MB → bucket 5', () => {
    expect(sizeBucketIndex(0)).toBe(5);
    expect(sizeBucketIndex(512 * 1024)).toBe(5);
  });

  it('bucket boundaries are exclusive on the lower end', () => {
    expect(sizeBucketIndex(100 * 1024 * 1024 - 1)).toBe(2);
  });
});

// ─── sortPhotosBySize ─────────────────────────────────────────────────────────

describe('sortPhotosBySize', () => {
  it('sorts descending by fileSize', () => {
    const photos = [makePhoto('a', '2024-01-01', 1000), makePhoto('b', '2024-01-01', 5000), makePhoto('c', '2024-01-01', 200)];
    const sorted = sortPhotosBySize(photos);
    expect(sorted.map(p => p.id)).toEqual(['b', 'a', 'c']);
  });

  it('does not mutate original array', () => {
    const photos = [makePhoto('a', '2024-01-01', 100), makePhoto('b', '2024-01-01', 500)];
    const original = [...photos];
    sortPhotosBySize(photos);
    expect(photos[0].id).toBe(original[0].id);
  });

  it('treats undefined fileSize as 0', () => {
    const photos = [
      { id: 'no-size', createdDate: '2024-01-01', filename: 'x.jpg' } as PhotoResponse,
      makePhoto('big', '2024-01-01', 1000),
    ];
    const sorted = sortPhotosBySize(photos);
    expect(sorted[0].id).toBe('big');
  });
});

// ─── sortPhotosByDate ─────────────────────────────────────────────────────────

describe('sortPhotosByDate', () => {
  it('sorts newest first', () => {
    const photos = [
      makePhoto('old', '2023-01-01T00:00:00', 100),
      makePhoto('new', '2024-06-01T00:00:00', 100),
      makePhoto('mid', '2023-12-01T00:00:00', 100),
    ];
    const sorted = sortPhotosByDate(photos);
    expect(sorted.map(p => p.id)).toEqual(['new', 'mid', 'old']);
  });
});

// ─── parentKey ────────────────────────────────────────────────────────────────

describe('parentKey', () => {
  it('year granularity returns 4-digit year string', () => {
    expect(parentKey(new Date('2024-06-15'), 'year')).toBe('2024');
  });

  it('month granularity returns YYYY-MM', () => {
    expect(parentKey(new Date('2024-06-15'), 'month')).toBe('2024-06');
    expect(parentKey(new Date('2024-01-01'), 'month')).toBe('2024-01');
  });
});

// ─── dayKey ───────────────────────────────────────────────────────────────────

describe('dayKey', () => {
  it('returns YYYY-MM-DD with zero-padding', () => {
    expect(dayKey(new Date('2024-03-05'))).toBe('2024-03-05');
    expect(dayKey(new Date('2024-11-20'))).toBe('2024-11-20');
  });
});

// ─── buildGroupsFromPhotos — sortMode size ────────────────────────────────────

describe('buildGroupsFromPhotos — sortMode=size', () => {
  const photos = [
    makePhoto('big',    '2024-06-10T10:00:00', 150 * 1024 * 1024),
    makePhoto('medium', '2024-06-15T12:00:00',  20 * 1024 * 1024),
    makePhoto('small',  '2024-06-20T08:00:00',   500 * 1024),
    makePhoto('tiny',   '2024-06-01T06:00:00',   100 * 1024),
  ];

  it('creates a primary group for the year', () => {
    const groups = buildGroupsFromPhotos(photos, 'year', 'none', 'size');
    const primary = groups.filter(g => g.level === 'primary');
    expect(primary).toHaveLength(1);
    expect(primary[0].key).toBe('2024');
  });

  it('creates secondary groups for each non-empty size bucket', () => {
    const groups = buildGroupsFromPhotos(photos, 'year', 'none', 'size');
    const secondary = groups.filter(g => g.level === 'secondary');
    expect(secondary.length).toBeGreaterThan(0);
    const labels = secondary.map(g => g.label);
    expect(labels).toContain(SIZE_BUCKETS[1].label);  // 100–200 MB (big)
    expect(labels).toContain(SIZE_BUCKETS[3].label);  // 10–50 MB  (medium)
    expect(labels).toContain(SIZE_BUCKETS[5].label);  // < 1 MB    (small, tiny)
  });

  it('does not emit empty buckets', () => {
    const groups = buildGroupsFromPhotos(photos, 'year', 'none', 'size');
    const secondary = groups.filter(g => g.level === 'secondary');
    expect(secondary.every(g => g.photos.length > 0)).toBe(true);
  });

  it('photos within a bucket are sorted by size descending', () => {
    const groups = buildGroupsFromPhotos(photos, 'year', 'none', 'size');
    const smallBucket = groups.find(g => g.label === SIZE_BUCKETS[5].label);
    expect(smallBucket).toBeDefined();
    const sizes = smallBucket!.photos.map(p => p.fileSize ?? 0);
    for (let i = 1; i < sizes.length; i++) {
      expect(sizes[i - 1]).toBeGreaterThanOrEqual(sizes[i]);
    }
  });

  it('primary group selectionPhotos contains all photos in the parent', () => {
    const groups = buildGroupsFromPhotos(photos, 'year', 'none', 'size');
    const primary = groups.find(g => g.level === 'primary')!;
    expect(primary.selectionPhotos).toHaveLength(photos.length);
  });

  it('primary groups are sorted newest-first by key', () => {
    const multiYear = [
      makePhoto('y2023', '2023-06-01T00:00:00', 1000),
      makePhoto('y2024', '2024-06-01T00:00:00', 1000),
    ];
    const groups = buildGroupsFromPhotos(multiYear, 'year', 'none', 'size');
    const primaries = groups.filter(g => g.level === 'primary');
    expect(primaries[0].key).toBe('2024');
    expect(primaries[1].key).toBe('2023');
  });

  it('month granularity creates one primary group per month', () => {
    const multiMonth = [
      makePhoto('jan', '2024-01-10T00:00:00', 5 * 1024 * 1024),
      makePhoto('feb', '2024-02-10T00:00:00', 5 * 1024 * 1024),
    ];
    const groups = buildGroupsFromPhotos(multiMonth, 'month', 'none', 'size');
    const primaries = groups.filter(g => g.level === 'primary');
    expect(primaries).toHaveLength(2);
    expect(primaries.map(g => g.key)).toContain('2024-02');
    expect(primaries.map(g => g.key)).toContain('2024-01');
  });
});

// ─── buildGroupsFromPhotos — sortMode date (regression) ──────────────────────

describe('buildGroupsFromPhotos — sortMode=date', () => {
  const photos = [
    makePhoto('a', '2024-06-01T00:00:00', 1000),
    makePhoto('b', '2024-06-02T00:00:00', 500),
    makePhoto('c', '2023-12-01T00:00:00', 2000),
  ];

  it('groupingMode=none returns flat groups sorted newest first', () => {
    const groups = buildGroupsFromPhotos(photos, 'year', 'none', 'date');
    expect(groups[0].key).toBe('2024');
    expect(groups[1].key).toBe('2023');
  });

  it('groupingMode=none sorts photos within group by date descending', () => {
    const groups = buildGroupsFromPhotos(photos, 'year', 'none', 'date');
    const group2024 = groups.find(g => g.key === '2024')!;
    expect(group2024.photos[0].id).toBe('b');
    expect(group2024.photos[1].id).toBe('a');
  });

  it('groupingMode=day creates primary + secondary groups', () => {
    const groups = buildGroupsFromPhotos(photos, 'year', 'day', 'date');
    expect(groups.some(g => g.level === 'primary')).toBe(true);
    expect(groups.some(g => g.level === 'secondary')).toBe(true);
  });

  it('returns empty array for empty input', () => {
    expect(buildGroupsFromPhotos([], 'year', 'none', 'date')).toEqual([]);
    expect(buildGroupsFromPhotos([], 'year', 'none', 'size')).toEqual([]);
  });
});
