import { Component, inject, input, OnInit, output, signal, computed } from '@angular/core';
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
export class MissingThumbnailsBannerComponent implements OnInit {
  private photosService = inject(PhotosService);
  private authService = inject(AuthService);

  storageDeviceId = input('');

  thumbnailsGenerated = output<void>();

  missingCount = signal(0);
  generating = signal(false);
  progress = signal<ThumbnailProgress | null>(null);

  progressPercent = computed(() => {
    const p = this.progress();
    if (!p || p.total === 0) return 0;
    return Math.round((p.processed / p.total) * 100);
  });

  private abortController: AbortController | null = null;

  ngOnInit(): void {
    this.fetchMissingCount();
  }

  private fetchMissingCount(): void {
    this.photosService.countMissingThumbnails({ storageDeviceId: this.storageDeviceId() }).subscribe({
      next: (result) => this.missingCount.set(result.count),
      error: () => this.missingCount.set(0)
    });
  }

  generateThumbnails(): void {
    if (this.generating()) return;
    this.generating.set(true);
    this.progress.set(null);
    this.streamGenerationProgress();
  }

  private streamGenerationProgress(): void {
    this.abortController?.abort();
    this.abortController = new AbortController();

    const creds = this.authService.getCredentials();
    const headers: Record<string, string> = {
      'Accept': 'text/event-stream',
      'Content-Type': 'application/json'
    };
    if (creds) headers['Authorization'] = `Basic ${creds.encoded}`;

    const body = JSON.stringify({ storageDeviceId: this.storageDeviceId() || null });

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
