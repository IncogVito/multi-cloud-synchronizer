import { Injectable, OnDestroy, signal } from '@angular/core';
import { AuthService } from './auth.service';

export interface RepairProgress {
  checked: number;
  fixed: number;
  total: number;
  done: boolean;
}

@Injectable({ providedIn: 'root' })
export class RepairThumbnailsService implements OnDestroy {
  private abortController: AbortController | null = null;

  readonly running = signal(false);
  readonly progress = signal<RepairProgress | null>(null);
  readonly jobId = signal<string | null>(null);

  constructor(private authService: AuthService) {}

  async startJob(): Promise<void> {
    if (this.running()) return;
    this.running.set(true);
    this.progress.set(null);

    const headers = this.buildHeaders({ 'Content-Type': 'application/json' });

    try {
      const response = await fetch('/api/photos/repair-thumbnails', { method: 'POST', headers });
      if (!response.ok) {
        this.running.set(false);
        return;
      }
      const data: { jobId: string; progress: RepairProgress } = await response.json();
      this.jobId.set(data.jobId);
      this.connectToJob(data.jobId);
    } catch {
      this.running.set(false);
    }
  }

  private connectToJob(id: string): void {
    this.abortController?.abort();
    this.abortController = new AbortController();

    fetch(`/api/photos/repair-thumbnails/${id}/progress`, {
      headers: this.buildHeaders({ Accept: 'text/event-stream' }),
      signal: this.abortController.signal,
    })
      .then(r => this.readStream(r))
      .catch(err => {
        if (err?.name !== 'AbortError') this.running.set(false);
      });
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
    this.running.set(false);
  }

  private handleLine(line: string): void {
    if (!line.startsWith('data:')) return;
    try {
      const event: RepairProgress = JSON.parse(line.slice(5).trim());
      this.progress.set(event);
      if (event.done) {
        this.running.set(false);
        this.jobId.set(null);
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
}
