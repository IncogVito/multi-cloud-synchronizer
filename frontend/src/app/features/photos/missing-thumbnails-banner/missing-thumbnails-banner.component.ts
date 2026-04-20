import { Component, computed, effect, inject, input, OnDestroy, OnInit, output, signal } from '@angular/core';
import { PhotosService } from '../../../core/api/generated/photos/photos.service';
import { AuthService } from '../../../core/services/auth.service';
import { ThumbnailProgress } from '../../../core/api/generated/model/thumbnailProgress';

const JOB_KEY = 'cloudsync-thumbnail-job-id';

@Component({
  selector: 'app-missing-thumbnails-banner',
  standalone: true,
  imports: [],
  templateUrl: './missing-thumbnails-banner.component.html',
  styleUrl: './missing-thumbnails-banner.component.scss'
})
export class MissingThumbnailsBannerComponent implements OnInit, OnDestroy {
  private photosService = inject(PhotosService);
  private authService = inject(AuthService);

  storageDeviceId = input('');
  selectedPhotoIds = input<string[]>([]);
  deleteStatus = input<string | null>(null);

  thumbnailsGenerated = output<void>();

  missingCount = signal(0);
  generating = signal(false);
  progress = signal<ThumbnailProgress | null>(null);
  jobId = signal<string | null>(null);

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

  ngOnInit(): void {
    const savedJobId = localStorage.getItem(JOB_KEY);
    if (savedJobId) {
      this.jobId.set(savedJobId);
      this.generating.set(true);
      this.connectToJob(savedJobId);
    }
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

  stopGeneration(): void {
    const id = this.jobId();
    if (id) {
      const headers = this.buildHeaders();
      fetch(`/api/photos/thumbnail-jobs/${id}`, { method: 'DELETE', headers });
      localStorage.removeItem(JOB_KEY);
    }
    this.abortController?.abort();
    this.generating.set(false);
    this.jobId.set(null);
    this.progress.set(null);
  }

  private async startGeneration(storageDeviceId: string | null, photoIds: string[] | null): Promise<void> {
    this.generating.set(true);
    this.progress.set(null);

    const headers = this.buildHeaders({ 'Content-Type': 'application/json' });
    const body = JSON.stringify({
      storageDeviceId: storageDeviceId || null,
      photoIds: photoIds?.length ? photoIds : null
    });

    try {
      const response = await fetch('/api/photos/thumbnail-jobs', { method: 'POST', headers, body });
      if (!response.ok) { this.generating.set(false); return; }
      const data = await response.json();
      const id: string = data.jobId;
      this.jobId.set(id);
      localStorage.setItem(JOB_KEY, id);
      this.connectToJob(id);
    } catch {
      this.generating.set(false);
    }
  }

  private connectToJob(id: string): void {
    this.abortController?.abort();
    this.abortController = new AbortController();

    fetch(`/api/photos/thumbnail-jobs/${id}/progress`, {
      headers: this.buildHeaders({ Accept: 'text/event-stream' }),
      signal: this.abortController.signal,
    }).then(r => this.readProgressStream(r))
      .catch(err => {
        if (err?.name !== 'AbortError') {
          console.error('Thumbnail job SSE error', err);
          this.generating.set(false);
        }
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
        this.jobId.set(null);
        localStorage.removeItem(JOB_KEY);
        this.thumbnailsGenerated.emit();
        this.abortController?.abort();
      }
    } catch { /* ignore parse errors */ }
  }

  private buildHeaders(extra: Record<string, string> = {}): Record<string, string> {
    const creds = this.authService.getCredentials();
    const headers: Record<string, string> = { ...extra };
    if (creds) headers['Authorization'] = `Basic ${creds.encoded}`;
    return headers;
  }

  ngOnDestroy(): void {
    // Disconnect SSE only — job continues running server-side
    this.abortController?.abort();
  }
}
