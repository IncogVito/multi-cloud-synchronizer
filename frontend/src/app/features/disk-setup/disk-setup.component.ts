import { Component, inject, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router } from '@angular/router';
import { DiskSetupService, DiskInfo, DriveStatus } from '../../core/services/disk-setup.service';

type SetupState = 'loading' | 'no-disk' | 'disks-available' | 'mounted';

@Component({
  selector: 'app-disk-setup',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="setup-page">
      <div class="setup-card">
        <div class="setup-header">
          <span class="logo-icon">&#9729;</span>
          <h1>CloudSync — konfiguracja dysku</h1>
        </div>

        @if (state === 'loading') {
          <div class="state-block">
            <div class="spinner"></div>
            <p>Sprawdzanie dysku...</p>
          </div>
        }

        @if (state === 'mounted' && driveStatus) {
          <div class="state-block success">
            <div class="status-icon">&#10003;</div>
            <p class="status-title">Dysk zamontowany: "{{ driveStatus.label || 'dysk zewnętrzny' }}"</p>
            <p class="status-sub">{{ driveStatus.drivePath }}</p>
            @if (driveStatus.freeBytes !== null) {
              <p class="status-sub">Wolne miejsce: {{ formatBytes(driveStatus.freeBytes) }}</p>
            }
            <div class="actions">
              <button class="btn btn-primary" (click)="goToLogin()">Przejdź do logowania &rarr;</button>
              <button class="btn btn-ghost" (click)="unmount()">Odmontuj</button>
            </div>
          </div>
        }

        @if (state === 'no-disk') {
          <div class="state-block warning">
            <div class="status-icon warn">!</div>
            <p class="status-title">Brak dysku zewnętrznego</p>
            <p class="status-sub">Podłącz dysk USB/SATA i odśwież</p>
            <div class="actions">
              <button class="btn btn-primary" (click)="refresh()">Odśwież</button>
              <button class="btn btn-ghost" (click)="goToLogin()">Kontynuuj bez dysku &rarr;</button>
            </div>
          </div>
        }

        @if (state === 'disks-available') {
          <div class="state-block">
            <p class="status-title">Dostępne dyski:</p>
            <div class="disk-list">
              @for (disk of availableDisks; track disk.path) {
                <div class="disk-item" [class.selected]="selectedDisk === disk.path">
                  <div class="disk-info">
                    <span class="disk-name">{{ disk.path }}</span>
                    <span class="disk-meta">
                      {{ disk.vendor || disk.model || 'Dysk zewnętrzny' }}
                      @if (disk.size) { &mdash; {{ disk.size }} }
                      @if (disk.label) { &mdash; "{{ disk.label }}" }
                    </span>
                  </div>
                  <button class="btn btn-primary btn-sm"
                          [disabled]="mounting"
                          (click)="mountDisk(disk.path)">
                    {{ mounting && selectedDisk === disk.path ? 'Montowanie...' : 'Zamontuj' }}
                  </button>
                </div>
              }
            </div>
            @if (errorMessage) {
              <p class="error-msg">{{ errorMessage }}</p>
            }
            <div class="actions">
              <button class="btn btn-ghost" (click)="refresh()">Odśwież listę</button>
              <button class="btn btn-ghost" (click)="goToLogin()">Kontynuuj bez dysku &rarr;</button>
            </div>
          </div>
        }
      </div>
    </div>
  `,
  styles: [`
    .setup-page {
      min-height: 100vh;
      display: flex;
      align-items: center;
      justify-content: center;
      background: var(--color-bg-secondary, #f5f5f5);
      padding: 2rem;
    }

    .setup-card {
      background: #fff;
      border-radius: 12px;
      box-shadow: 0 4px 24px rgba(0,0,0,0.08);
      padding: 2.5rem;
      width: 100%;
      max-width: 520px;
    }

    .setup-header {
      display: flex;
      align-items: center;
      gap: 0.75rem;
      margin-bottom: 2rem;

      .logo-icon { font-size: 1.8rem; }
      h1 { font-size: 1.25rem; font-weight: 600; margin: 0; }
    }

    .state-block {
      display: flex;
      flex-direction: column;
      gap: 0.5rem;
    }

    .status-icon {
      width: 40px;
      height: 40px;
      border-radius: 50%;
      background: #22c55e;
      color: #fff;
      display: flex;
      align-items: center;
      justify-content: center;
      font-size: 1.2rem;
      font-weight: bold;

      &.warn {
        background: #f59e0b;
      }
    }

    .status-title {
      font-size: 1rem;
      font-weight: 600;
      margin: 0.5rem 0 0;
    }

    .status-sub {
      color: #666;
      font-size: 0.875rem;
      margin: 0;
    }

    .disk-list {
      display: flex;
      flex-direction: column;
      gap: 0.5rem;
      margin: 0.75rem 0;
    }

    .disk-item {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 0.75rem 1rem;
      border: 1px solid #e5e7eb;
      border-radius: 8px;
      gap: 1rem;

      &.selected {
        border-color: #3b82f6;
        background: #eff6ff;
      }
    }

    .disk-info {
      display: flex;
      flex-direction: column;
      gap: 0.25rem;
    }

    .disk-name {
      font-weight: 500;
      font-size: 0.9rem;
    }

    .disk-meta {
      color: #6b7280;
      font-size: 0.8rem;
    }

    .actions {
      display: flex;
      gap: 0.75rem;
      flex-wrap: wrap;
      margin-top: 1.25rem;
    }

    .btn {
      padding: 0.5rem 1.25rem;
      border-radius: 8px;
      font-size: 0.875rem;
      font-weight: 500;
      cursor: pointer;
      border: none;
      transition: background 0.15s;

      &:disabled { opacity: 0.6; cursor: not-allowed; }
    }

    .btn-primary {
      background: #3b82f6;
      color: #fff;
      &:hover:not(:disabled) { background: #2563eb; }
    }

    .btn-ghost {
      background: transparent;
      color: #374151;
      border: 1px solid #d1d5db;
      &:hover { background: #f9fafb; }
    }

    .btn-sm {
      padding: 0.375rem 0.875rem;
      font-size: 0.8rem;
    }

    .spinner {
      width: 32px;
      height: 32px;
      border: 3px solid #e5e7eb;
      border-top-color: #3b82f6;
      border-radius: 50%;
      animation: spin 0.8s linear infinite;
    }

    @keyframes spin {
      to { transform: rotate(360deg); }
    }

    .error-msg {
      color: #dc2626;
      font-size: 0.85rem;
      margin: 0.25rem 0;
    }
  `]
})
export class DiskSetupComponent implements OnInit {
  private diskSetupService = inject(DiskSetupService);
  private router = inject(Router);

  state: SetupState = 'loading';
  driveStatus: DriveStatus | null = null;
  availableDisks: DiskInfo[] = [];
  selectedDisk: string | null = null;
  mounting = false;
  errorMessage: string | null = null;

  ngOnInit(): void {
    this.refresh();
  }

  refresh(): void {
    this.state = 'loading';
    this.errorMessage = null;
    this.diskSetupService.getStatus().subscribe({
      next: (status) => {
        this.driveStatus = status;
        if (status.mounted) {
          this.state = 'mounted';
        } else {
          this.loadDisks();
        }
      },
      error: () => {
        this.state = 'no-disk';
      }
    });
  }

  private loadDisks(): void {
    this.diskSetupService.listDisks().subscribe({
      next: (disks) => {
        this.availableDisks = disks;
        this.state = disks.length > 0 ? 'disks-available' : 'no-disk';
      },
      error: () => {
        this.state = 'no-disk';
      }
    });
  }

  mountDisk(device: string): void {
    this.selectedDisk = device;
    this.mounting = true;
    this.errorMessage = null;
    this.diskSetupService.mount(device).subscribe({
      next: (status) => {
        this.mounting = false;
        this.driveStatus = status;
        this.state = 'mounted';
      },
      error: (err) => {
        this.mounting = false;
        this.errorMessage = err?.error?.error || 'Montowanie nie powiodło się';
      }
    });
  }

  unmount(): void {
    this.diskSetupService.unmount().subscribe({
      next: () => this.refresh(),
      error: () => this.refresh()
    });
  }

  goToLogin(): void {
    this.router.navigate(['/login']);
  }

  formatBytes(bytes: number): string {
    if (bytes >= 1e12) return (bytes / 1e12).toFixed(1) + ' TB';
    if (bytes >= 1e9) return (bytes / 1e9).toFixed(1) + ' GB';
    if (bytes >= 1e6) return (bytes / 1e6).toFixed(0) + ' MB';
    return bytes + ' B';
  }
}
