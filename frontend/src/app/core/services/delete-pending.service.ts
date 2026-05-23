import { Injectable, inject, signal } from '@angular/core';
import { PhotosService } from '../api/generated/photos/photos.service';

@Injectable({ providedIn: 'root' })
export class DeletePendingService {
  private photos = inject(PhotosService);

  readonly running = signal(false);
  readonly lastDeleted = signal<number | null>(null);
  readonly error = signal<string | null>(null);

  async run(): Promise<void> {
    if (this.running()) return;
    this.running.set(true);
    this.error.set(null);
    try {
      const result = await this.photos.deletePending().toPromise();
      this.lastDeleted.set(result?.deleted ?? 0);
    } catch (e: any) {
      this.error.set(e?.message ?? 'Delete pending failed');
    } finally {
      this.running.set(false);
    }
  }
}
