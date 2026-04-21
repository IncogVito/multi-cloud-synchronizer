import { Injectable, OnDestroy, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { switchMap, map } from 'rxjs/operators';

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

@Injectable({ providedIn: 'root' })
export class ThumbnailSpriteService implements OnDestroy {
  private readonly pending = new Set<string>();
  private readonly _slots = signal(new Map<string, ResolvedSlot>());
  private readonly blobUrls: string[] = [];
  private flushTimer: ReturnType<typeof setTimeout> | null = null;

  readonly slots = this._slots.asReadonly();

  constructor(private http: HttpClient) {}

  request(photoId: string): void {
    if (this._slots().has(photoId) || this.pending.has(photoId)) return;
    this.pending.add(photoId);
    this.scheduleFlush();
  }

  reset(): void {
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

    this.http
      .post<SpriteManifestDto>('/api/photos/sprite-manifest', { photoIds: batch })
      .pipe(
        switchMap(manifest =>
          this.http
            .get(`/api/photos/sprites/${manifest.spriteId}`, { responseType: 'blob' })
            .pipe(map(blob => ({ manifest, blob })))
        )
      )
      .subscribe({
        next: ({ manifest, blob }) => {
          if (manifest.spriteId === 'empty' || manifest.spriteWidth === 0) return;
          const url = URL.createObjectURL(blob);
          this.blobUrls.push(url);
          const cols = manifest.spriteWidth / 300;
          const rows = manifest.spriteHeight / 300;
          this._slots.update(current => {
            const next = new Map(current);
            for (const [photoId, slot] of Object.entries(manifest.slots)) {
              next.set(photoId, {
                spriteUrl: url,
                cols,
                rows,
                col: slot.x / 300,
                row: slot.y / 300,
              });
            }
            return next;
          });
          if (this.pending.size > 0) this.scheduleFlush();
        },
        error: () => {
          if (this.pending.size > 0) this.scheduleFlush();
        },
      });
  }
}
