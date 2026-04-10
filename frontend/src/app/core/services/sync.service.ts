import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { BehaviorSubject, Observable } from 'rxjs';
import { tap } from 'rxjs/operators';
import { AuthService } from './auth.service';
import { SyncProgressEvent, SyncStartResponse } from '../models/sync-progress.model';

@Injectable({ providedIn: 'root' })
export class SyncService {
  private http = inject(HttpClient);
  private authService = inject(AuthService);

  private _progress = new BehaviorSubject<SyncProgressEvent | null>(null);
  syncProgress$ = this._progress.asObservable();

  private abortController: AbortController | null = null;

  startSync(accountId: string): Observable<SyncStartResponse> {
    return this.http.post<SyncStartResponse>(`/api/sync/${accountId}`, {}).pipe(
      tap(() => this.subscribeToEvents(accountId))
    );
  }

  private subscribeToEvents(accountId: string): void {
    this.closeEvents();
    this.abortController = new AbortController();

    const creds = this.authService.getCredentials();
    const headers: Record<string, string> = { 'Accept': 'text/event-stream' };
    if (creds) headers['Authorization'] = `Basic ${creds.encoded}`;

    fetch(`/api/sync/${accountId}/events`, {
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
              const event: SyncProgressEvent = JSON.parse(line.slice(5).trim());
              this._progress.next(event);
              if (event.phase === 'DONE' || event.phase === 'ERROR') {
                this.closeEvents();
              } else if (event.phase === 'AWAITING_CONFIRMATION') {
                // Keep SSE open — user must confirm before download starts
              }
            } catch { /* ignore parse errors */ }
          }
        }
      }
    }).catch((err) => {
      if (err?.name !== 'AbortError') {
        console.error('SSE sync stream error', err);
      }
    });
  }

  closeEvents(): void {
    this.abortController?.abort();
    this.abortController = null;
  }

  confirmSync(accountId: string): Observable<void> {
    return this.http.post<void>(`/api/sync/${accountId}/confirm`, {}).pipe(
      tap(() => this.subscribeToEvents(accountId))
    );
  }

  reset(): void {
    this.closeEvents();
    this._progress.next(null);
  }
}
