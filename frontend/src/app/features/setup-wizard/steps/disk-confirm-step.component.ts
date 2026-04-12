import { Component, inject, OnInit, output, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { DiskSetupService, DriveStatus } from '../../../core/services/disk-setup.service';

@Component({
  selector: 'app-disk-confirm-step',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="step-body">
      @if (loading()) {
        <div class="spinner"></div>
        <p>Sprawdzanie dysku...</p>
      } @else if (status() && status()!.mounted) {
        <div class="disk-card">
          <div class="disk-icon">&#128190;</div>
          <div class="disk-info">
            <p class="disk-label">"{{ status()!.label || 'Dysk zewnętrzny' }}"</p>
            <p class="disk-path">{{ status()!.drivePathHost ?? status()!.drivePath }}</p>
            @if (status()!.freeBytes !== null) {
              <p class="disk-free">Wolne miejsce: {{ formatBytes(status()!.freeBytes!) }}</p>
            }
          </div>
        </div>
        <p class="confirm-hint">Upewnij się, że to właściwy dysk do synchronizacji.</p>
        <div class="actions">
          <button class="btn btn-primary" (click)="confirm()">
            Potwierdź: używam dysku "{{ status()!.label || 'zewnętrzny' }}" &rarr;
          </button>
        </div>
      } @else {
        <div class="warn-block">
          <div class="warn-icon">!</div>
          <p class="warn-title">Brak zamontowanego dysku</p>
          <p class="warn-sub">Zamontuj dysk na stronie konfiguracji, a następnie wróć tutaj.</p>
        </div>
        <div class="actions">
          <button class="btn btn-ghost" (click)="refresh()">Odśwież</button>
          <a class="btn btn-primary" href="/setup">Przejdź do konfiguracji dysku</a>
        </div>
      }
      @if (errorMsg()) {
        <p class="error-msg">{{ errorMsg() }}</p>
      }
    </div>
  `,
  styles: [`
    .step-body { display: flex; flex-direction: column; gap: 1rem; }

    .disk-card {
      display: flex;
      align-items: center;
      gap: 1rem;
      padding: 1rem;
      border: 1px solid #d1fae5;
      border-radius: 10px;
      background: #f0fdf4;
    }

    .disk-icon { font-size: 2rem; }

    .disk-info {
      display: flex;
      flex-direction: column;
      gap: 0.2rem;
    }

    .disk-label { font-weight: 600; font-size: 1rem; margin: 0; }
    .disk-path { color: #666; font-size: 0.85rem; margin: 0; }
    .disk-free { color: #16a34a; font-size: 0.85rem; margin: 0; }

    .confirm-hint { color: #6b7280; font-size: 0.875rem; margin: 0; }

    .warn-block {
      display: flex;
      flex-direction: column;
      gap: 0.5rem;
    }

    .warn-icon {
      width: 40px;
      height: 40px;
      border-radius: 50%;
      background: #f59e0b;
      color: #fff;
      display: flex;
      align-items: center;
      justify-content: center;
      font-size: 1.2rem;
      font-weight: bold;
    }

    .warn-title { font-weight: 600; margin: 0; }
    .warn-sub { color: #6b7280; font-size: 0.875rem; margin: 0; }

    .actions { display: flex; gap: 0.75rem; flex-wrap: wrap; margin-top: 0.5rem; }

    .btn {
      padding: 0.5rem 1.25rem;
      border-radius: 8px;
      font-size: 0.875rem;
      font-weight: 500;
      cursor: pointer;
      border: none;
      text-decoration: none;
      display: inline-flex;
      align-items: center;
    }

    .btn-primary { background: #3b82f6; color: #fff; }
    .btn-primary:hover { background: #2563eb; }
    .btn-ghost { background: transparent; color: #374151; border: 1px solid #d1d5db; }
    .btn-ghost:hover { background: #f9fafb; }

    .spinner {
      width: 32px;
      height: 32px;
      border: 3px solid #e5e7eb;
      border-top-color: #3b82f6;
      border-radius: 50%;
      animation: spin 0.8s linear infinite;
    }

    @keyframes spin { to { transform: rotate(360deg); } }

    .error-msg { color: #dc2626; font-size: 0.875rem; }
  `]
})
export class DiskConfirmStepComponent implements OnInit {
  confirmed = output<{ deviceId: string; label: string }>();

  private diskSetupService = inject(DiskSetupService);

  loading = signal(true);
  status = signal<DriveStatus | null>(null);
  errorMsg = signal<string | null>(null);

  ngOnInit(): void {
    this.refresh();
  }

  refresh(): void {
    this.loading.set(true);
    this.diskSetupService.getStatus().subscribe({
      next: (s) => { this.status.set(s); this.loading.set(false); },
      error: () => { this.status.set(null); this.loading.set(false); }
    });
  }

  confirm(): void {
    const s = this.status();
    if (!s?.mounted) return;
    this.confirmed.emit({
      deviceId: s.deviceId ?? '',
      label: s.label ?? 'Dysk zewnętrzny',
    });
  }

  formatBytes(bytes: number): string {
    if (bytes >= 1e12) return (bytes / 1e12).toFixed(1) + ' TB';
    if (bytes >= 1e9) return (bytes / 1e9).toFixed(1) + ' GB';
    if (bytes >= 1e6) return (bytes / 1e6).toFixed(0) + ' MB';
    return bytes + ' B';
  }
}
