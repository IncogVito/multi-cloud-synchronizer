import { Component, computed, effect, inject, input, OnDestroy, output, signal } from '@angular/core';
import { PhotosService } from '../../../core/api/generated/photos/photos.service';
import { AuthService } from '../../../core/services/auth.service';
import { ThumbnailProgress } from '../../../core/api/generated/model/thumbnailProgress';

@Component({
  selector: 'app-missing-thumbnails-banner',
  standalone: true,
  imports: [],
  templateUrl: './missing-thumbnails-banner.component.html',
  styleUrl: './missing-thumbnails-banner.component.scss'
})
export class MissingThumbnailsBannerComponent implements OnDestroy {
  private photosService = inject(PhotosService);
  private authService = inject(AuthService);

  storageDeviceId = input('');
  selectedPhotoIds = input<string[]>([]);
  deleteStatus = input<string | null>(null);

  thumbnailsGenerated = output<void>();

  missingCount = signal(0);
  generating = signal(false);
  progress = signal<ThumbnailProgress | null>(null);

  progressPercent = computed(() => {
    const p = this.progress();
    if (!p || !p.total) return 0;
    return Math.round((p.processed! / p.total) * 100);
  });

  private abortController: AbortController | null = null;

  constructor() {
    effect(() => {
      const deviceId = this.storageDeviceId();
      if (deviceId) this.fetchMissingCount();
    });
  }

  fetchMissingCount(): void {
    this.photosService.countMissingThumbnails({ storageDeviceId: this.storageDeviceId() }).subscribe({
      next: (result) => this.missingCount.set(result.count),
      error: () => this.missingCount.set(0)
    });
  }

  generateAll(): void {
    if (this.generating()) return;
    this.startGeneration(this.storageDeviceId(), null);
  }

  generateForSelected(): void {
    if (this.generating()) return;
    this.startGeneration(null, this.selectedPhotoIds());
  }

  private startGeneration(storageDeviceId: string | null, photoIds: string[] | null): void {
    this.generating.set(true);
    this.progress.set(null);
    this.streamProgress(storageDeviceId, photoIds);
  }

  private streamProgress(storageDeviceId: string | null, photoIds: string[] | null): void {
    this.abortController?.abort();
    this.abortController = new AbortController();

    const creds = this.authService.getCredentials();
    const headers: Record<string, string> = {
      'Accept': 'text/event-stream',
      'Content-Type': 'application/json'
    };
    if (creds) headers['Authorization'] = `Basic ${creds.encoded}`;

    const body = JSON.stringify({
      storageDeviceId: storageDeviceId || null,
      photoIds: photoIds?.length ? photoIds : null
    });

    fetch('/api/photos/generate-thumbnails', {
      method: 'POST',
      headers,
      body,
      signal: this.abortController.signal,
    }).then(async (response) => {
      await this.readProgressStream(response);
    }).catch((err) => {
      if (err?.name !== 'AbortError') {
        console.error('Thumbnail generation SSE error', err);
      }
      this.generating.set(false);
    });
  }

  private async readProgressStream(response: Response): Promise<void> {
    const reader = response.body!.getReader();
    const decoder = new TextDecoder();
    let buffer = '';

    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split('\n');
      buffer = lines.pop() ?? '';
      for (const line of lines) {
        this.handleProgressLine(line);
      }
    }
    this.generating.set(false);
  }

  private handleProgressLine(line: string): void {
    if (!line.startsWith('data:')) return;
    try {
      const event: ThumbnailProgress = JSON.parse(line.slice(5).trim());
      this.progress.set(event);
      if (event.done) {
        this.generating.set(false);
        this.missingCount.set(0);
        this.thumbnailsGenerated.emit();
        this.abortController?.abort();
      }
    } catch { /* ignore parse errors */ }
  }

  ngOnDestroy(): void {
    this.abortController?.abort();
  }
}
