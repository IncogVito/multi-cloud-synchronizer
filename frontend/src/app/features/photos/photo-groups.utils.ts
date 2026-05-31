import { PhotoResponse } from '../../core/api/generated/model/photoResponse';
import { GroupingMode, SortMode } from '../../state/photos/photos.actions';
import { PhotoGroup } from './photo-timeline/photo-timeline.component';

export type Granularity = 'year' | 'month';

export const DAY_NAMES_PL = ['niedziela', 'poniedziałek', 'wtorek', 'środa', 'czwartek', 'piątek', 'sobota'];
export const HOUR_SLOTS: ReadonlyArray<[number, number]> = [[0, 4], [4, 8], [8, 12], [12, 16], [16, 20], [20, 24]];
export const SIZE_BUCKETS: ReadonlyArray<{ min: number; label: string }> = [
  { min: 200 * 1024 * 1024, label: '≥ 200 MB' },
  { min: 100 * 1024 * 1024, label: '100 – 200 MB' },
  { min: 50 * 1024 * 1024,  label: '50 – 100 MB' },
  { min: 10 * 1024 * 1024,  label: '10 – 50 MB' },
  { min: 1 * 1024 * 1024,   label: '1 – 10 MB' },
  { min: 0,                  label: '< 1 MB' },
];

export function dayKey(date: Date): string {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
}

export function dayLabel(date: Date): string {
  const d = String(date.getDate()).padStart(2, '0');
  const m = String(date.getMonth() + 1).padStart(2, '0');
  return `${d}-${m}-${date.getFullYear()} (${DAY_NAMES_PL[date.getDay()]})`;
}

export function hourSlotLabel(slotIdx: number): string {
  const [start, end] = HOUR_SLOTS[slotIdx];
  return `${String(start).padStart(2, '0')}:00 – ${String(end).padStart(2, '0')}:00`;
}

export function parentKey(date: Date, granularity: Granularity): string {
  return granularity === 'year'
    ? String(date.getFullYear())
    : `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`;
}

export function parentLabel(pKey: string, granularity: Granularity, sampleDate: Date): string {
  return granularity === 'year'
    ? pKey
    : sampleDate.toLocaleString('default', { month: 'long', year: 'numeric' });
}

export function sortPhotosByDate(photos: PhotoResponse[]): PhotoResponse[] {
  return photos.slice().sort((a, b) => new Date(b.createdDate).getTime() - new Date(a.createdDate).getTime());
}

export function sortPhotosBySize(photos: PhotoResponse[]): PhotoResponse[] {
  return photos.slice().sort((a, b) => (b.fileSize ?? 0) - (a.fileSize ?? 0));
}

export function sizeBucketIndex(bytes: number): number {
  for (let i = 0; i < SIZE_BUCKETS.length; i++) {
    if (bytes >= SIZE_BUCKETS[i].min) return i;
  }
  return SIZE_BUCKETS.length - 1;
}

export function buildGroupsFromPhotos(
  photos: PhotoResponse[],
  granularity: Granularity,
  groupingMode: GroupingMode,
  sortMode: SortMode,
): PhotoGroup[] {
  if (sortMode === 'size') {
    const parentMap = new Map<string, PhotoResponse[]>();
    for (const photo of photos) {
      const key = parentKey(new Date(photo.createdDate), granularity);
      if (!parentMap.has(key)) parentMap.set(key, []);
      parentMap.get(key)!.push(photo);
    }

    const result: PhotoGroup[] = [];
    for (const [pKey, parentPhotos] of Array.from(parentMap.entries()).sort((a, b) => b[0].localeCompare(a[0]))) {
      result.push({
        key: pKey,
        label: parentLabel(pKey, granularity, new Date(parentPhotos[0].createdDate)),
        photos: [],
        level: 'primary',
        selectionPhotos: parentPhotos,
      });

      const bucketMap = new Map<number, PhotoResponse[]>();
      for (const photo of parentPhotos) {
        const idx = sizeBucketIndex(photo.fileSize ?? 0);
        if (!bucketMap.has(idx)) bucketMap.set(idx, []);
        bucketMap.get(idx)!.push(photo);
      }

      for (const [bucketIdx, bucketPhotos] of Array.from(bucketMap.entries()).sort((a, b) => a[0] - b[0])) {
        result.push({
          key: `${pKey}/size-${bucketIdx}`,
          label: SIZE_BUCKETS[bucketIdx].label,
          photos: sortPhotosBySize(bucketPhotos),
          level: 'secondary',
        });
      }
    }
    return result;
  }

  if (groupingMode === 'day' || groupingMode === 'hour') {
    const parentMap = new Map<string, PhotoResponse[]>();
    for (const photo of photos) {
      const key = parentKey(new Date(photo.createdDate), granularity);
      if (!parentMap.has(key)) parentMap.set(key, []);
      parentMap.get(key)!.push(photo);
    }

    const result: PhotoGroup[] = [];
    for (const [pKey, parentPhotos] of Array.from(parentMap.entries()).sort((a, b) => b[0].localeCompare(a[0]))) {
      result.push({
        key: pKey,
        label: parentLabel(pKey, granularity, new Date(parentPhotos[0].createdDate)),
        photos: [],
        level: 'primary',
        selectionPhotos: parentPhotos,
      });

      if (groupingMode === 'day') {
        const dayMap = new Map<string, PhotoResponse[]>();
        for (const photo of parentPhotos) {
          const key = dayKey(new Date(photo.createdDate));
          if (!dayMap.has(key)) dayMap.set(key, []);
          dayMap.get(key)!.push(photo);
        }
        for (const [dKey, dayPhotos] of Array.from(dayMap.entries()).sort((a, b) => b[0].localeCompare(a[0]))) {
          result.push({
            key: `${pKey}/${dKey}`,
            label: dayLabel(new Date(dayPhotos[0].createdDate)),
            photos: sortPhotosByDate(dayPhotos),
            level: 'secondary',
          });
        }
      } else {
        const slotMap = new Map<string, PhotoResponse[]>();
        for (const photo of parentPhotos) {
          const date = new Date(photo.createdDate);
          const key = `${dayKey(date)}-${Math.floor(date.getHours() / 4)}`;
          if (!slotMap.has(key)) slotMap.set(key, []);
          slotMap.get(key)!.push(photo);
        }
        for (const [sKey, slotPhotos] of Array.from(slotMap.entries()).sort((a, b) => b[0].localeCompare(a[0]))) {
          const date = new Date(slotPhotos[0].createdDate);
          const slotIdx = Math.floor(date.getHours() / 4);
          result.push({
            key: `${pKey}/${sKey}`,
            label: `${dayLabel(date)} · ${hourSlotLabel(slotIdx)}`,
            photos: sortPhotosByDate(slotPhotos),
            level: 'secondary',
          });
        }
      }
    }
    return result;
  }

  const groupMap = new Map<string, PhotoResponse[]>();
  for (const photo of photos) {
    const key = parentKey(new Date(photo.createdDate), granularity);
    if (!groupMap.has(key)) groupMap.set(key, []);
    groupMap.get(key)!.push(photo);
  }
  return Array.from(groupMap.entries())
    .sort((a, b) => b[0].localeCompare(a[0]))
    .map(([key, groupPhotos]) => ({
      key,
      label: parentLabel(key, granularity, new Date(groupPhotos[0].createdDate)),
      photos: sortPhotosByDate(groupPhotos),
    }));
}
