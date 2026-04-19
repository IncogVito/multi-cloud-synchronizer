import { Injectable, inject } from '@angular/core';
import { BehaviorSubject, Observable } from 'rxjs';
import { tap } from 'rxjs/operators';
import { AuthService } from './auth.service';
import { SyncProgressEvent, SyncStartResponse } from '../models/sync-progress.model';
import { DefaultService } from '../api/generated/default/default.service';
import { ReorganizePreview, ReorganizeResult } from './disk-indexing.service';

@Injectable({ providedIn: 'root' })
export class SyncService {
  private apiService = inject(DefaultService);
  private authService = inject(AuthService);

  private _progress = new BehaviorSubject<SyncProgressEvent | null>(null);
  syncProgress$ = this._progress.asObservable();

  private abortController: AbortController | null = null;

  startSync(accountId: string, provider: 'ICLOUD' | 'IPHONE' = 'ICLOUD'): Observable<SyncStartResponse> {
    return this.apiService.startSync<SyncStartResponse>(accountId, { provider }).pipe(
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
              if (event.phase === 'DONE' || event.phase === 'ERROR' || event.phase === 'CANCELLED') {
                this.closeEvents();
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
    return this.apiService.confirmSync<void>(accountId).pipe(
      tap(() => this.subscribeToEvents(accountId))
    );
  }

  cancelSync(accountId: string): Observable<void> {
    return this.apiService.cancelSync<void>(accountId);
  }

  reorganizePreview(accountId: string): Observable<ReorganizePreview> {
    return this.apiService.reorganizePreview1<ReorganizePreview>(accountId);
  }

  reorganize(accountId: string): Observable<ReorganizeResult> {
    return this.apiService.reorganize2<ReorganizeResult>(accountId);
  }

  reset(): void {
    this.closeEvents();
    this._progress.next(null);
  }
}
