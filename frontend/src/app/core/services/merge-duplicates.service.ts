import { Injectable, OnDestroy, signal } from '@angular/core';
import { AuthService } from './auth.service';

export interface MergeDuplicatesProgress {
  checked: number;
  total: number;
  merged: number;
  deleted: number;
  done: boolean;
}

@Injectable({ providedIn: 'root' })
export class MergeDuplicatesService implements OnDestroy {
  private abortController: AbortController | null = null;

  readonly running = signal(false);
  readonly progress = signal<MergeDuplicatesProgress | null>(null);

  constructor(private authService: AuthService) {}

  async startJob(accountId: string): Promise<void> {
    if (this.running()) return;
    this.running.set(true);
    this.progress.set(null);

    const headers = this.buildHeaders({ 'Content-Type': 'application/json' });

    try {
      const response = await fetch(`/api/sync/${accountId}/merge-duplicates`, { method: 'POST', headers });
      if (!response.ok) {
        this.running.set(false);
        return;
      }
      const data: { jobId: string } = await response.json();
      this.connectToJob(data.jobId);
    } catch {
      this.running.set(false);
    }
  }

  private connectToJob(id: string): void {
    this.abortController?.abort();
    this.abortController = new AbortController();

    fetch(`/api/jobs/${id}/progress`, {
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
      const event: MergeDuplicatesProgress = JSON.parse(line.slice(5).trim());
      this.progress.set(event);
      if (event.done) {
        this.running.set(false);
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
