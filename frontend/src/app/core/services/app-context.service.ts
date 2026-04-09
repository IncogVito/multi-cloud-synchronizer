import { Injectable, computed, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, tap, map, of, catchError } from 'rxjs';
import { environment } from '../../../environments/environment';
import { AppContext, BrowseContextResponse } from '../models/app-context.model';

@Injectable({ providedIn: 'root' })
export class AppContextService {
  private http = inject(HttpClient);
  private base = `${environment.apiUrl}/context`;

  private _context = signal<AppContext | null>(null);
  private _loading = signal(true);

  context = this._context.asReadonly();
  loading = this._loading.asReadonly();
  hasContext = computed(() => this._context() !== null && !this._context()!.degraded);
  isDegraded = computed(() => this._context()?.degraded === true);

  load(): Observable<AppContext | null> {
    this._loading.set(true);
    return this.http.get<AppContext>(this.base, { observe: 'response' }).pipe(
      map(res => (res.status === 204 ? null : (res.body ?? null))),
      tap(ctx => {
        this._context.set(ctx);
        this._loading.set(false);
      }),
      catchError(() => {
        this._context.set(null);
        this._loading.set(false);
        return of(null);
      })
    );
  }

  set(storageDeviceId: string, basePath: string, create = false): Observable<AppContext> {
    return this.http
      .post<AppContext>(this.base, { storageDeviceId, basePath, create })
      .pipe(tap(ctx => this._context.set(ctx)));
  }

  clear(): Observable<void> {
    return this.http.delete<void>(this.base).pipe(tap(() => this._context.set(null)));
  }

  clearLocal(): void {
    this._context.set(null);
  }

  browse(path = ''): Observable<BrowseContextResponse> {
    return this.http.get<BrowseContextResponse>(`${this.base}/browse`, { params: { path } });
  }

  mkdir(parentPath: string, name: string): Observable<{ absolutePath: string; relativePath: string }> {
    return this.http.post<{ absolutePath: string; relativePath: string }>(`${this.base}/mkdir`, {
      parentPath,
      name
    });
  }
}
