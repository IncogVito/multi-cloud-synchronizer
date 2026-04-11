import { ChangeDetectorRef, Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router } from '@angular/router';
import { DiskSetupService, DiskInfo, DriveStatus } from '../../core/services/disk-setup.service';
import { AppContextService } from '../../core/services/app-context.service';
import { BrowseContextResponse, BrowseEntry } from '../../core/models/app-context.model';
import { ToastService } from '../../core/services/toast.service';

type SetupState = 'loading' | 'no-disk' | 'disks-available' | 'mounted' | 'path-picker' | 'confirm';

@Component({
  selector: 'app-disk-setup',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="setup-page">
      <div class="setup-card">
        <div class="setup-header">
          <img class="logo-icon" src="assets/favicon-32x32.png" alt="CloudSync" width="28" height="28">
          <h1>CloudSync — konfiguracja dysku</h1>
        </div>

        @if (state() === 'loading') {
          <div class="state-block">
            <div class="spinner"></div>
            <p>Sprawdzanie dysku...</p>
          </div>
        }

        @if (state() === 'mounted' && driveStatus()) {
          <div class="state-block success">
            <div class="status-icon">&#10003;</div>
            <p class="status-title">Dysk zamontowany: "{{ driveStatus()!.label || 'dysk zewnętrzny' }}"</p>
            <p class="status-sub">{{ driveStatus()!.drivePath }}</p>
            @if (driveStatus()!.freeBytes !== null) {
              <p class="status-sub">Wolne miejsce: {{ formatBytes(driveStatus()!.freeBytes!) }}</p>
            }
            <p class="status-sub muted">Wybierz folder docelowy na dysku, aby aktywować aplikację.</p>
            <div class="actions">
              <button class="btn btn-primary" (click)="startPathPicker()">Wybierz folder &rarr;</button>
              <button class="btn btn-ghost" (click)="changeDisk()">Zmień dysk</button>
            </div>
          </div>
        }

        @if (state() === 'path-picker' && browseData()) {
          <div class="state-block">
            <p class="status-title">Wybierz folder docelowy</p>
            <p class="status-sub">Aktualna ścieżka: <code>/{{ browseData()!.currentRelative }}</code></p>
            <div class="path-picker">
              @if (browseData()!.currentRelative) {
                <div class="path-item nav" (click)="navigateUp()">&larr; W górę</div>
              }
              @for (entry of browseData()!.entries; track entry.absolutePath) {
                <div class="path-item" (click)="navigateInto(entry)">&#128193; {{ entry.name }}</div>
              }
              @if (!browseData()!.entries.length) {
                <p class="status-sub muted">Brak podfolderów</p>
              }
            </div>
            @if (errorMessage()) { <p class="error-msg">{{ errorMessage() }}</p> }
            <div class="actions">
              <button class="btn btn-primary" (click)="selectCurrentPath()">Wybierz ten folder</button>
              <button class="btn btn-ghost" (click)="promptCreateFolder()">Utwórz nowy folder</button>
              <button class="btn btn-ghost" (click)="state.set('mounted')">Wstecz</button>
            </div>
          </div>
        }

        @if (state() === 'confirm') {
          <div class="state-block">
            <p class="status-title">Potwierdź konfigurację</p>
            <p class="status-sub">Dysk: <strong>{{ driveStatus()?.label || 'dysk zewnętrzny' }}</strong></p>
            <p class="status-sub">Ścieżka: <code>{{ selectedAbsolutePath() }}</code></p>
            @if (driveStatus()?.freeBytes !== null && driveStatus()?.freeBytes !== undefined) {
              <p class="status-sub">Wolne miejsce: {{ formatBytes(driveStatus()!.freeBytes!) }}</p>
            }
            @if (errorMessage()) { <p class="error-msg">{{ errorMessage() }}</p> }
            <div class="actions">
              <button class="btn btn-primary" [disabled]="saving()" (click)="confirmContext()">
                {{ saving() ? 'Zapisywanie...' : 'Zatwierdź i przejdź dalej' }}
              </button>
              <button class="btn btn-ghost" (click)="state.set('path-picker')">Wstecz</button>
            </div>
          </div>
        }

        @if (state() === 'no-disk') {
          <div class="state-block warning">
            <div class="status-icon warn">!</div>
            <p class="status-title">Brak dysku zewnętrznego</p>
            <p class="status-sub">Podłącz dysk USB/SATA i odśwież</p>
            @if (errorMessage()) {
              <p class="error-msg">{{ errorMessage() }}</p>
            }
            <div class="actions">
              <button class="btn btn-primary" (click)="refresh()">Odśwież</button>
              <button class="btn btn-ghost" (click)="goToDashboard()">Powrót do dashboard &rarr;</button>
            </div>
          </div>
        }

        @if (state() === 'disks-available') {
          <div class="state-block">
            <p class="status-title">Wybierz dysk do synchronizacji:</p>
            <div class="disk-list">
              @for (disk of availableDisks(); track disk.path) {
                <div class="disk-item" [class.selected]="selectedDisk() === disk.path">
                  <div class="disk-info">
                    <span class="disk-name">{{ disk.path }}</span>
                    <span class="disk-meta">
                      {{ disk.vendor || disk.model || 'Dysk zewnętrzny' }}
                      @if (disk.size) { &mdash; {{ disk.size }} }
                      @if (disk.label) { &mdash; "{{ disk.label }}" }
                    </span>
                  </div>
                  <button class="btn btn-primary btn-sm"
                          [disabled]="mounting()"
                          (click)="mountDisk(disk.path)">
                    {{ mounting() && selectedDisk() === disk.path ? 'Montowanie...' : 'Wybierz' }}
                  </button>
                </div>
              }
            </div>
            @if (errorMessage()) {
              <p class="error-msg">{{ errorMessage() }}</p>
            }
            <div class="actions">
              <button class="btn btn-ghost" (click)="reloadDiskList()">Odśwież listę</button>
              <button class="btn btn-ghost" (click)="goToDashboard()">Powrót do dashboard &rarr;</button>
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

      .logo-icon { width: 28px; height: 28px; flex-shrink: 0; }
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

    .path-picker {
      margin: 0.75rem 0;
      max-height: 280px;
      overflow-y: auto;
      border: 1px solid #e5e7eb;
      border-radius: 8px;
    }
    .path-item {
      padding: 0.6rem 0.875rem;
      cursor: pointer;
      border-bottom: 1px solid #f3f4f6;
      font-size: 0.9rem;
      &:hover { background: #f9fafb; }
      &:last-child { border-bottom: none; }
      &.nav { color: #2563eb; font-weight: 500; }
    }
    code {
      background: #f3f4f6;
      padding: 0.1rem 0.4rem;
      border-radius: 4px;
      font-size: 0.85rem;
    }
  `]
})
export class DiskSetupComponent implements OnInit {
  private diskSetupService = inject(DiskSetupService);
  private appContextService = inject(AppContextService);
  private toast = inject(ToastService);
  private router = inject(Router);
  private route = inject(ActivatedRoute);
  private cdr = inject(ChangeDetectorRef);

  state = signal<SetupState>('loading');
  driveStatus = signal<DriveStatus | null>(null);
  availableDisks = signal<DiskInfo[]>([]);
  selectedDisk = signal<string | null>(null);
  mounting = signal(false);
  errorMessage = signal<string | null>(null);

  browseData = signal<BrowseContextResponse | null>(null);
  selectedAbsolutePath = signal<string>('');
  saving = signal(false);

  /** Safety net: if a request hangs without next/error, force the page out of loading. */
  private static readonly REQUEST_TIMEOUT_MS = 15000;

  ngOnInit(): void {
    const action = this.route.snapshot.queryParamMap.get('action');
    if (action === 'change-disk') {
      this.forceDiskChange();
    } else if (action === 'change-folder') {
      this.refresh(() => {
        if (this.state() === 'mounted') this.startPathPicker();
      });
    } else {
      this.refresh();
    }
  }

  /** Clear context, unmount, and immediately show the disk picker. */
  private forceDiskChange(): void {
    this.state.set('loading');
    this.errorMessage.set(null);
    this.cdr.markForCheck();
    this.appContextService.clear().subscribe({
      next: () => this.unmountAndList(),
      error: () => this.unmountAndList()
    });
  }

  private unmountAndList(): void {
    this.diskSetupService.unmount().subscribe({
      next: () => this.loadDisks(),
      error: (err) => {
        console.warn('[disk-setup] unmount failed (continuing to list)', err);
        this.loadDisks();
      }
    });
  }

  refresh(onReady?: () => void): void {
    this.state.set('loading');
    this.errorMessage.set(null);
    this.cdr.markForCheck();

    let settled = false;
    const safety = setTimeout(() => {
      if (!settled) {
        console.warn('[disk-setup] /api/setup/status did not respond in time, falling back');
        this.errorMessage.set('Backend nie odpowiedział w zadanym czasie. Sprawdź logi backendu.');
        this.state.set('no-disk');
        this.cdr.markForCheck();
      }
    }, DiskSetupComponent.REQUEST_TIMEOUT_MS);

    this.diskSetupService.getStatus().subscribe({
      next: (status) => {
        settled = true;
        clearTimeout(safety);
        this.driveStatus.set(status);
        if (status.mounted) {
          this.state.set('mounted');
        } else {
          this.loadDisks();
        }
        this.cdr.markForCheck();
        onReady?.();
      },
      error: (err) => {
        settled = true;
        clearTimeout(safety);
        console.error('[disk-setup] getStatus failed', err);
        this.errorMessage.set('Nie udało się pobrać statusu dysku.');
        this.state.set('no-disk');
        this.cdr.markForCheck();
      }
    });
  }

  /** Reload the disk list without re-checking mount status (avoids bouncing back to 'mounted'). */
  reloadDiskList(): void {
    this.state.set('loading');
    this.errorMessage.set(null);
    this.cdr.markForCheck();
    this.loadDisks();
  }

  private loadDisks(): void {
    // Reset previous selection so no disk appears pre-selected when re-listing.
    this.selectedDisk.set(null);
    this.diskSetupService.listDisks().subscribe({
      next: (disks) => {
        this.availableDisks.set(disks);
        this.state.set(disks.length > 0 ? 'disks-available' : 'no-disk');
        this.cdr.markForCheck();
      },
      error: (err) => {
        console.error('[disk-setup] listDisks failed', err);
        this.state.set('no-disk');
        this.cdr.markForCheck();
      }
    });
  }

  mountDisk(device: string): void {
    this.selectedDisk.set(device);
    this.mounting.set(true);
    this.errorMessage.set(null);
    this.diskSetupService.mount(device).subscribe({
      next: (status) => {
        this.mounting.set(false);
        this.driveStatus.set(status);
        this.state.set('mounted');
        this.toast.success(`Zamontowano ${status.label || device}`);
        this.cdr.markForCheck();
      },
      error: (err) => {
        this.mounting.set(false);
        const backendMsg = err?.error?.message || err?.error?.error;
        const httpInfo = err?.status ? ` (HTTP ${err.status})` : '';
        const full = backendMsg
          ? `Montowanie nie powiodło się${httpInfo}: ${backendMsg}`
          : `Montowanie nie powiodło się${httpInfo}. Sprawdź logi backendu.`;
        this.errorMessage.set(full);
        this.toast.error(full);
        console.error('[disk-setup] mount failed', err);
        this.cdr.markForCheck();
      }
    });
  }

  /** Unmount and re-list disks so user can pick a different one. */
  changeDisk(): void {
    this.forceDiskChange();
  }

  goToDashboard(): void {
    this.router.navigate(['/dashboard']);
  }

  startPathPicker(): void {
    this.errorMessage.set(null);
    this.loadBrowse('');
    this.state.set('path-picker');
  }

  private loadBrowse(path: string): void {
    this.appContextService.browse(path).subscribe({
      next: (data) => {
        this.browseData.set(data);
        this.cdr.markForCheck();
      },
      error: (err) => {
        this.errorMessage.set(err?.error?.message || 'Nie udało się odczytać katalogów');
        this.cdr.markForCheck();
      }
    });
  }

  navigateInto(entry: BrowseEntry): void {
    this.loadBrowse(entry.relativePath);
  }

  navigateUp(): void {
    const current = this.browseData()?.currentRelative || '';
    const parts = current.split('/').filter(Boolean);
    parts.pop();
    this.loadBrowse(parts.join('/'));
  }

  selectCurrentPath(): void {
    const data = this.browseData();
    if (!data) return;
    this.selectedAbsolutePath.set(data.currentAbsolute);
    this.errorMessage.set(null);
    this.state.set('confirm');
  }

  promptCreateFolder(): void {
    const data = this.browseData();
    if (!data) return;
    const name = window.prompt('Nazwa nowego folderu:');
    if (!name) return;
    this.appContextService.mkdir(data.currentAbsolute, name).subscribe({
      next: (created) => {
        this.loadBrowse(created.relativePath);
      },
      error: (err) => {
        this.errorMessage.set(err?.error?.message || 'Nie udało się utworzyć folderu');
        this.cdr.markForCheck();
      }
    });
  }

  confirmContext(): void {
    const status = this.driveStatus();
    if (!status?.deviceId) {
      this.errorMessage.set('Brak identyfikatora dysku');
      return;
    }
    this.saving.set(true);
    this.errorMessage.set(null);
    this.appContextService.set(status.deviceId, this.selectedAbsolutePath(), true).subscribe({
      next: () => {
        this.saving.set(false);
        this.toast.success('Kontekst zapisany — aplikacja aktywna');
        this.router.navigate(['/dashboard']);
      },
      error: (err) => {
        this.saving.set(false);
        const msg = err?.error?.message || 'Nie udało się zapisać kontekstu';
        this.errorMessage.set(msg);
        this.toast.error(msg);
        this.cdr.markForCheck();
      }
    });
  }

  formatBytes(bytes: number): string {
    if (bytes >= 1e12) return (bytes / 1e12).toFixed(1) + ' TB';
    if (bytes >= 1e9) return (bytes / 1e9).toFixed(1) + ' GB';
    if (bytes >= 1e6) return (bytes / 1e6).toFixed(0) + ' MB';
    return bytes + ' B';
  }
}
