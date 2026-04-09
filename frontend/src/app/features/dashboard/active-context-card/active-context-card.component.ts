import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router } from '@angular/router';
import { AppContextService } from '../../../core/services/app-context.service';

@Component({
  selector: 'app-active-context-card',
  standalone: true,
  imports: [CommonModule],
  template: `
    @if (ctx.context(); as c) {
      <div class="ctx-card" [class.degraded]="c.degraded">
        <div class="ctx-header">
          <span class="icon">&#128190;</span>
          <div class="ctx-title">
            <strong>{{ c.storageDeviceLabel || 'dysk zewnętrzny' }}</strong>
            <span class="path">/{{ c.relativePath }}</span>
          </div>
          @if (c.degraded) {
            <span class="badge warn">Dysk niedostępny</span>
          }
        </div>
        <div class="ctx-meta">
          @if (c.freeBytes !== null) {
            <span>Wolne: {{ formatBytes(c.freeBytes) }}</span>
          }
          <span>Mount: <code>{{ c.mountPoint }}</code></span>
        </div>
        <div class="ctx-actions">
          <button class="btn" (click)="changeDisk()">Zmień dysk</button>
          <button class="btn" (click)="changeFolder()">Zmień folder</button>
          @if (c.degraded) {
            <button class="btn primary" (click)="changeDisk()">Podłącz ponownie</button>
          }
        </div>
      </div>
    }
  `,
  styles: [`
    .ctx-card {
      background: #fff;
      border: 1px solid #e5e7eb;
      border-radius: 12px;
      padding: 1rem 1.25rem;
      margin-bottom: 1rem;
      box-shadow: 0 1px 3px rgba(0,0,0,0.05);
      &.degraded { border-color: #dc2626; background: #fef2f2; }
    }
    .ctx-header {
      display: flex; align-items: center; gap: 0.75rem;
      .icon { font-size: 1.4rem; }
      .ctx-title { display: flex; flex-direction: column; flex: 1; }
      .path { color: #6b7280; font-size: 0.85rem; font-family: monospace; }
    }
    .ctx-meta {
      display: flex; gap: 1rem; margin-top: 0.5rem;
      color: #6b7280; font-size: 0.825rem;
      code { background: #f3f4f6; padding: 0 0.3rem; border-radius: 3px; }
    }
    .ctx-actions { margin-top: 0.75rem; display: flex; gap: 0.5rem; }
    .btn {
      padding: 0.4rem 0.9rem;
      border: 1px solid #d1d5db;
      background: #fff;
      border-radius: 6px;
      font-size: 0.825rem;
      cursor: pointer;
      &.primary { background: #3b82f6; color: #fff; border-color: #3b82f6; }
    }
    .badge { padding: 0.2rem 0.5rem; border-radius: 4px; font-size: 0.75rem; font-weight: 600; }
    .badge.warn { background: #dc2626; color: #fff; }
  `]
})
export class ActiveContextCardComponent {
  ctx = inject(AppContextService);
  private router = inject(Router);

  changeDisk(): void {
    this.router.navigate(['/setup'], { queryParams: { action: 'change-disk' } });
  }

  changeFolder(): void {
    this.router.navigate(['/setup'], { queryParams: { action: 'change-folder' } });
  }

  formatBytes(bytes: number): string {
    if (bytes >= 1e12) return (bytes / 1e12).toFixed(1) + ' TB';
    if (bytes >= 1e9) return (bytes / 1e9).toFixed(1) + ' GB';
    if (bytes >= 1e6) return (bytes / 1e6).toFixed(0) + ' MB';
    return bytes + ' B';
  }
}
