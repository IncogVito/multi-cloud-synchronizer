import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { BehaviorSubject, Observable } from 'rxjs';
import { AuthService } from './auth.service';

export interface DiskIndexProgress {
  phase: 'SCANNING' | 'DONE' | 'ERROR';
  scanned: number;
  total: number;
  percentComplete: number;
  error?: string;
}

export interface ReorganizePreview {
  unorganizedCount: number;
  samples: string[];
  estimatedFolders: string[];
}

export interface ReorganizeResult {
  moved: number;
  errors: number;
}

@Injectable({ providedIn: 'root' })
export class DiskIndexingService {
  private http = inject(HttpClient);
  private authService = inject(AuthService);

  private _progress = new BehaviorSubject<DiskIndexProgress | null>(null);
  progress$ = this._progress.asObservable();

  private abortController: AbortController | null = null;

  start(): Observable<{ status: string }> {
    return this.http.post<{ status: string }>('/api/disk-index/start', {});
  }

  subscribeToEvents(): void {
    this.closeEvents();
    this.abortController = new AbortController();

    const creds = this.authService.getCredentials();
    const headers: Record<string, string> = { Accept: 'text/event-stream' };
    if (creds) headers['Authorization'] = `Basic ${creds.encoded}`;

    fetch('/api/disk-index/events', {
      headers,
      signal: this.abortController.signal,
    }).then(async (response) => {
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
          if (line.startsWith('data:')) {
            try {
              const event: DiskIndexProgress = JSON.parse(line.slice(5).trim());
              this._progress.next(event);
              if (event.phase === 'DONE' || event.phase === 'ERROR') {
                this.closeEvents();
              }
            } catch { /* ignore parse errors */ }
          }
        }
      }
    }).catch((err) => {
      if (err?.name !== 'AbortError') {
        console.error('SSE disk-index stream error', err);
      }
    });
  }

  closeEvents(): void {
    this.abortController?.abort();
    this.abortController = null;
  }

  reset(): void {
    this.closeEvents();
    this._progress.next(null);
  }

  reorganizePreview(): Observable<ReorganizePreview> {
    return this.http.get<ReorganizePreview>('/api/disk-index/reorganize-preview');
  }

  reorganize(): Observable<ReorganizeResult> {
    return this.http.post<ReorganizeResult>('/api/disk-index/reorganize', {});
  }
}
