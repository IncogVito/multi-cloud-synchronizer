import { Injectable, OnDestroy, signal, computed } from '@angular/core';
import { AuthService } from './auth.service';

export interface ThumbnailJobProgress {
  processed: number;
  total: number;
  done: boolean;
  errors: number;
}

const JOB_KEY = 'cloudsync-thumbnail-job-id';

@Injectable({ providedIn: 'root' })
export class ThumbnailJobStateService implements OnDestroy {
  private abortController: AbortController | null = null;

  readonly generating = signal(false);
  readonly progress = signal<ThumbnailJobProgress | null>(null);
  readonly jobId = signal<string | null>(null);
  readonly missingCount = signal(0);

  readonly progressPercent = computed(() => {
    const p = this.progress();
    if (!p?.total) return 0;
    return Math.round((p.processed / p.total) * 100);
  });

  readonly thumbnailsDone = signal(0);

  constructor(private authService: AuthService) {}

  fetchMissingCount(storageDeviceId: string): void {
    const headers = this.buildHeaders({ Accept: 'application/json' });
    const qs = storageDeviceId ? `?storageDeviceId=${encodeURIComponent(storageDeviceId)}` : '';
    fetch(`/api/photos/missing-thumbnails-count${qs}`, { headers })
      .then(r => r.json())
      .then((data: { count: number }) => this.missingCount.set(data.count ?? 0))
      .catch(() => this.missingCount.set(0));
  }

  async startJob(storageDeviceId: string | null, photoIds: string[] | null): Promise<void> {
    if (this.generating()) return;
    this.generating.set(true);
    this.progress.set(null);

    const headers = this.buildHeaders({ 'Content-Type': 'application/json' });
    const body = JSON.stringify({
      storageDeviceId: storageDeviceId || null,
      photoIds: photoIds?.length ? photoIds : null,
    });

    try {
      const response = await fetch('/api/photos/thumbnail-jobs', { method: 'POST', headers, body });
      if (!response.ok) { this.generating.set(false); return; }
      const data = await response.json();
      this.jobId.set(data.jobId);
      localStorage.setItem(JOB_KEY, data.jobId);
      this.connectToJob(data.jobId);
    } catch {
      this.generating.set(false);
    }
  }

  connectToJob(id: string): void {
    this.abortController?.abort();
    this.abortController = new AbortController();
    this.jobId.set(id);
    this.generating.set(true);
    localStorage.setItem(JOB_KEY, id);

    fetch(`/api/photos/thumbnail-jobs/${id}/progress`, {
      headers: this.buildHeaders({ Accept: 'text/event-stream' }),
      signal: this.abortController.signal,
    })
      .then(r => this.readStream(r))
      .catch(err => {
        if (err?.name !== 'AbortError') this.generating.set(false);
      });
  }

  stopJob(): void {
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

  restoreFromStorage(): void {
    const savedId = localStorage.getItem(JOB_KEY);
    if (savedId) this.connectToJob(savedId);
  }

  ngOnDestroy(): void {
    this.abortController?.abort();
  }

  private async readStream(response: Response): Promise<void> {
    const reader = response.body!.getReader();
    const decoder = new TextDecoder();
    let buffer = '';

    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split('\n');
      buffer = lines.pop() ?? '';
      for (const line of lines) this.handleLine(line);
    }
    this.generating.set(false);
  }

  private handleLine(line: string): void {
    if (!line.startsWith('data:')) return;
    try {
      const event: ThumbnailJobProgress = JSON.parse(line.slice(5).trim());
      this.progress.set(event);
      if (event.done) {
        this.generating.set(false);
        this.missingCount.set(0);
        this.jobId.set(null);
        localStorage.removeItem(JOB_KEY);
        this.abortController?.abort();
        this.thumbnailsDone.update(n => n + 1);
      }
    } catch { /* ignore parse errors */ }
  }

  private buildHeaders(extra: Record<string, string> = {}): Record<string, string> {
    const creds = this.authService.getCredentials();
    const headers: Record<string, string> = { ...extra };
    if (creds) headers['Authorization'] = `Basic ${creds.encoded}`;
    return headers;
  }
}
