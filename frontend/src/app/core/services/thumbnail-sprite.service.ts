import { Injectable, OnDestroy, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, Subscription, of, from } from 'rxjs';
import { switchMap, map, tap, catchError, concatMap } from 'rxjs/operators';

export interface ResolvedSlot {
  spriteUrl: string;
  cols: number;
  rows: number;
  col: number;
  row: number;
}

interface SpriteSlotDto {
  x: number;
  y: number;
  w: number;
  h: number;
}

interface SpriteManifestDto {
  spriteId: string;
  spriteWidth: number;
  spriteHeight: number;
  slots: Record<string, SpriteSlotDto>;
}

/** Splits `items` into pages of `pageSize`, capped at `maxPages` pages. */
export function buildPages<T>(items: T[], pageSize: number, maxPages: number): T[][] {
  const pages: T[][] = [];
  const limit = Math.min(items.length, pageSize * maxPages);
  for (let i = 0; i < limit; i += pageSize) {
    pages.push(items.slice(i, i + pageSize));
  }
  return pages;
}

@Injectable({ providedIn: 'root' })
export class ThumbnailSpriteService implements OnDestroy {
  private readonly pending = new Set<string>();
  private readonly _slots = signal(new Map<string, ResolvedSlot>());
  private readonly blobUrls: string[] = [];
  private flushTimer: ReturnType<typeof setTimeout> | null = null;
  private prefetchSub: Subscription | null = null;

  readonly slots = this._slots.asReadonly();

  constructor(private http: HttpClient) {}

  /** Queues a single photo for the next debounced sprite batch fetch. */
  request(photoId: string): void {
    if (this._slots().has(photoId) || this.pending.has(photoId)) return;
    this.pending.add(photoId);
    this.scheduleFlush();
  }

  /**
   * Sequentially prefetches thumbnails in pages of `pageSize`.
   * Each page starts only after the previous sprite response is received.
   * Cancels any in-flight prefetch from a prior call.
   */
  prefetchPages(photoIds: string[], pageSize: number, maxPages = 4): void {
    this.prefetchSub?.unsubscribe();
    this.prefetchSub = from(buildPages(photoIds, pageSize, maxPages)).pipe(
      concatMap(page => this.fetchBatch(page)),
    ).subscribe();
  }

  reset(): void {
    this.prefetchSub?.unsubscribe();
    this.prefetchSub = null;
    if (this.flushTimer !== null) clearTimeout(this.flushTimer);
    this.flushTimer = null;
    this.pending.clear();
    for (const url of this.blobUrls) URL.revokeObjectURL(url);
    this.blobUrls.length = 0;
    this._slots.set(new Map());
  }

  ngOnDestroy(): void {
    this.reset();
  }

  private scheduleFlush(): void {
    if (this.flushTimer !== null) clearTimeout(this.flushTimer);
    this.flushTimer = setTimeout(() => this.flush(), 150);
  }

  private flush(): void {
    this.flushTimer = null;
    if (this.pending.size === 0) return;

    const batch = Array.from(this.pending).slice(0, 500);
    batch.forEach(id => this.pending.delete(id));

    this.fetchBatch(batch).subscribe({
      next: () => { if (this.pending.size > 0) this.scheduleFlush(); },
      error: () => { if (this.pending.size > 0) this.scheduleFlush(); },
    });
  }

  /**
   * Fetches a sprite sheet for the given photo IDs, skipping any already loaded
   * or currently queued in the debounce-flush path.
   */
  private fetchBatch(photoIds: string[]): Observable<void> {
    const toFetch = photoIds.filter(id => !this._slots().has(id) && !this.pending.has(id));
    if (toFetch.length === 0) return of(undefined);

    return this.http
      .post<SpriteManifestDto>('/api/photos/sprite-manifest', { photoIds: toFetch })
      .pipe(
        switchMap(manifest =>
          this.http
            .get(`/api/photos/sprites/${manifest.spriteId}`, { responseType: 'blob' })
            .pipe(map(blob => ({ manifest, blob })))
        ),
        tap(({ manifest, blob }) => this.registerSprite(manifest, blob)),
        map(() => undefined),
        catchError(() => of(undefined)),
      );
  }

  private registerSprite(manifest: SpriteManifestDto, blob: Blob): void {
    if (manifest.spriteId === 'empty' || manifest.spriteWidth === 0) return;
    const url = URL.createObjectURL(blob);
    this.blobUrls.push(url);
    const cols = manifest.spriteWidth / 300;
    const rows = manifest.spriteHeight / 300;
    this._slots.update(current => {
      const next = new Map(current);
      for (const [photoId, slot] of Object.entries(manifest.slots)) {
        next.set(photoId, { spriteUrl: url, cols, rows, col: slot.x / 300, row: slot.y / 300 });
      }
      return next;
    });
  }
}
