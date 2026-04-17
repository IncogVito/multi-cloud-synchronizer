import { Component, OnInit, OnDestroy, inject, signal, computed, effect } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { Store } from '@ngxs/store';
import { SyncService } from '../../../core/services/sync.service';
import { DiskSetupService, DriveStatus } from '../../../core/services/disk-setup.service';
import { SyncProgressEvent } from '../../../core/models/sync-progress.model';
import { DevicesState } from '../../../state/devices/devices.state';
import { AccountsState } from '../../../state/accounts/accounts.state';
import { AccountResponse } from '../../../core/api/generated/model/accountResponse';

interface DeviceReadiness {
  drive: boolean;
  iphone: boolean;
  icloud: boolean;
  /** At least one source (iPhone or iCloud) is available. */
  anySource: boolean;
  /** Drive is ready AND at least one source is ready. */
  ready: boolean;
}

@Component({
  selector: 'app-sync-section',
  standalone: true,
  imports: [DecimalPipe, RouterLink],
  template: `
    <section class="section sync-section">
      <div class="section-header">
        <h2>Synchronizacja</h2>
        <button class="btn btn-ghost" (click)="refresh()" [disabled]="loading()">Odśwież</button>
      </div>

      @if (!readiness().ready) {
        <div class="sync-card sync-card--pending">
          <p class="sync-title">
            @if (!readiness().drive) {
              Brak dysku zewnętrznego
            } @else {
              Brak źródła zdjęć (iPhone lub iCloud)
            }
          </p>
          <p class="sync-sub">Do synchronizacji potrzebny jest dysk docelowy oraz co najmniej jedno źródło: iPhone albo iCloud.</p>
          <ul class="readiness">
            <li [class.ok]="readiness().drive">
              {{ readiness().drive ? '✓' : '○' }} Dysk zewnętrzny (wymagany)
            </li>
            <li [class.ok]="readiness().anySource">
              {{ readiness().anySource ? '✓' : '○' }}
              Źródło: iPhone
              <span class="src-badge" [class.on]="readiness().iphone">{{ readiness().iphone ? 'on' : 'off' }}</span>
              lub iCloud
              <span class="src-badge" [class.on]="readiness().icloud">{{ readiness().icloud ? 'on' : 'off' }}</span>
            </li>
          </ul>
          @if (!readiness().drive) {
            <div class="actions">
              <a class="btn btn-primary btn-sm" routerLink="/setup">Wybierz dysk</a>
            </div>
          }
        </div>
      } @else {
        <div class="sync-card">
          @if (!activeProgress()) {
            <p class="sync-title">Gotowe do synchronizacji</p>
            <p class="sync-sub">{{ syncNeededText() }}</p>
            <p class="sync-sub muted">
              Dysk docelowy: <strong>{{ driveLabel() }}</strong>
              <a class="link" routerLink="/setup">zmień</a>
            </p>

            @if (readiness().icloud && readiness().iphone) {
              <div class="provider-tabs">
                <button class="provider-tab" [class.active]="selectedProvider() === 'ICLOUD'" (click)="selectedProvider.set('ICLOUD')">
                  iCloud
                </button>
                <button class="provider-tab" [class.active]="selectedProvider() === 'IPHONE'" (click)="selectedProvider.set('IPHONE')">
                  iPhone
                </button>
              </div>
            } @else {
              <p class="sync-sub muted">
                Źródło: <strong>{{ sourceLabel() }}</strong>
              </p>
            }

            <button
              class="btn btn-primary"
              [disabled]="starting() || !canStart()"
              (click)="startSync()"
            >
              {{ starting() ? 'Uruchamianie...' : startButtonLabel() }}
            </button>
            @if (selectedProvider() === 'ICLOUD' && readiness().icloud && !primaryAccount()) {
              <p class="sync-sub muted">iCloud online, ale brak dodanego konta — dodaj je poniżej.</p>
            }
            @if (selectedProvider() === 'IPHONE' && readiness().iphone) {
              <p class="sync-sub muted">Synchronizacja bezpośrednia z iPhone (USB) — funkcja w przygotowaniu.</p>
            }
          } @else if (activeProgress()!.phase === 'AWAITING_CONFIRMATION') {
            <p class="sync-title">Gotowe do pobrania</p>
            <p class="sync-sub">Znaleziono zdjęcia ({{ selectedProvider() === 'IPHONE' ? 'iPhone' : 'iCloud' }}). Czy chcesz je skopiować na dysk zewnętrzny?</p>
            <ul class="stats">
              <li>Zdjęcia w iCloud: <strong>{{ activeProgress()!.totalOnCloud }}</strong></li>
              <li>Już zsynchronizowane: <strong>{{ activeProgress()!.synced }}</strong></li>
              <li>Do pobrania: <strong>{{ activeProgress()!.pending }}</strong></li>
            </ul>
            <div class="actions">
              <button class="btn btn-primary" [disabled]="confirming()" (click)="confirmSync()">
                {{ confirming() ? 'Uruchamianie...' : 'Pobierz ' + activeProgress()!.pending + ' zdjęć' }}
              </button>
              <button class="btn btn-ghost" (click)="cancelSync()">Anuluj</button>
            </div>
          } @else {
            <p class="sync-title"
               [class.sync-title--error]="activeProgress()!.phase === 'ERROR'"
               [class.sync-title--cancelled]="activeProgress()!.phase === 'CANCELLED'">
              Status: {{ phaseLabel(activeProgress()!.phase) }}
            </p>
            @if (activeProgress()!.phase !== 'ERROR' && activeProgress()!.phase !== 'CANCELLED') {
              <div class="progress-wrap">
                <div class="progress-bar">
                  <div class="progress-fill" [style.width.%]="activeProgress()!.percentComplete"></div>
                </div>
                <span class="progress-pct">{{ activeProgress()!.percentComplete | number:'1.0-0' }}%</span>
              </div>
            }
            <ul class="stats">
              @if (activeProgress()!.phase === 'FETCHING_METADATA') {
                <li>Pobrano metadane: <strong>{{ activeProgress()!.metadataFetched }}</strong>{{ activeProgress()!.totalOnCloud > 0 ? ' / ' + activeProgress()!.totalOnCloud : '' }}</li>
              } @else if (activeProgress()!.phase !== 'ERROR' && activeProgress()!.phase !== 'CANCELLED' && activeProgress()!.phase !== 'PERSISTING_METADATA') {
                <li>Zsynchronizowane: <strong>{{ activeProgress()!.synced }}</strong> / {{ activeProgress()!.totalOnCloud || '—' }}</li>
              }
              @if (activeProgress()!.pending > 0) {
                <li>Oczekujące: <strong>{{ activeProgress()!.pending }}</strong></li>
              }
              @if (activeProgress()!.failed > 0) {
                <li class="err">Błędy: <strong>{{ activeProgress()!.failed }}</strong></li>
              }
              @if (etaText()) {
                <li>Pozostały czas: <strong>{{ etaText() }}</strong></li>
              }
            </ul>
            @if (activeProgress()!.phase === 'DOWNLOADING' && (activeProgress()!.diskFreeBytes != null || activeProgress()!.diskPhotoCount != null)) {
              <ul class="stats disk-stats">
                @if (activeProgress()!.diskPhotoCount != null) {
                  <li>Zdjęcia na dysku: <strong>{{ activeProgress()!.diskPhotoCount }}</strong></li>
                }
                @if (activeProgress()!.diskFreeBytes != null) {
                  <li>Wolne miejsce: <strong>{{ formatBytes(activeProgress()!.diskFreeBytes!) }}</strong></li>
                }
              </ul>
            }
            @if (isActivelySyncing()) {
              <div class="actions">
                <button class="btn btn-ghost btn-danger" (click)="cancelSync()">Przerwij synchronizację</button>
              </div>
            }
          }
        </div>
      }
    </section>
  `,
  styles: [`
    .sync-section { margin-top: 1rem; }
    .sync-card {
      padding: 1rem 1.25rem;
      border: 1px solid var(--color-border, #e5e7eb);
      border-radius: 10px;
      background: var(--color-bg, #fff);
    }
    .sync-card--pending { opacity: 0.85; }
    .sync-title { font-weight: 600; margin: 0 0 0.5rem; }
    .sync-sub { color: #6b7280; font-size: 0.875rem; margin: 0.25rem 0 0.75rem; }
    .muted { opacity: 0.8; }
    .readiness { list-style: none; padding: 0; margin: 0.5rem 0 0; display: flex; gap: 1rem; flex-wrap: wrap; }
    .readiness li { color: #6b7280; font-size: 0.875rem; }
    .readiness li.ok { color: #16a34a; }
    .src-badge { display: inline-block; padding: 0 0.4rem; margin: 0 0.2rem; border-radius: 999px; background: #e5e7eb; color: #6b7280; font-size: 0.7rem; text-transform: uppercase; }
    .src-badge.on { background: #dcfce7; color: #16a34a; }
    .btn-sm { padding: 0.375rem 0.875rem; font-size: 0.8rem; }
    .actions { margin-top: 0.75rem; display: flex; gap: 0.5rem; }
    .link { margin-left: 0.5rem; color: #3b82f6; text-decoration: none; }
    .link:hover { text-decoration: underline; }
    .btn { padding: 0.5rem 1.25rem; border-radius: 8px; font-size: 0.875rem; font-weight: 500; border: none; cursor: pointer; }
    .btn-primary { background: #3b82f6; color: #fff; }
    .btn-primary:disabled { opacity: 0.6; cursor: not-allowed; }
    .btn-ghost { background: transparent; border: 1px solid #d1d5db; color: #374151; }
    .progress-wrap { display: flex; align-items: center; gap: 0.75rem; margin-top: 0.5rem; }
    .progress-bar { flex: 1; height: 10px; background: #e5e7eb; border-radius: 999px; overflow: hidden; }
    .progress-fill { height: 100%; background: #3b82f6; transition: width 0.3s ease; }
    .progress-pct { font-size: 0.8rem; color: #374151; min-width: 3em; text-align: right; }
    .stats { list-style: none; padding: 0; margin: 0.75rem 0 0; display: flex; flex-wrap: wrap; gap: 1rem; font-size: 0.85rem; color: #374151; }
    .stats .err { color: #dc2626; }
    .sync-title--error { color: #dc2626; }
    .sync-title--cancelled { color: #6b7280; }
    .sync-card:has(.sync-title--error) { border-color: #fca5a5; background: #fff5f5; }
    .btn-danger { border-color: #fca5a5; color: #dc2626; }
    .btn-danger:hover { background: #fff5f5; }
    .disk-stats { border-top: 1px dashed #e5e7eb; padding-top: 0.5rem; margin-top: 0.5rem; color: #6b7280; }
    .provider-tabs { display: flex; gap: 0; margin: 0.5rem 0 0.75rem; border: 1px solid #d1d5db; border-radius: 8px; overflow: hidden; width: fit-content; }
    .provider-tab { padding: 0.375rem 1rem; font-size: 0.8rem; font-weight: 500; border: none; background: transparent; color: #6b7280; cursor: pointer; }
    .provider-tab.active { background: #3b82f6; color: #fff; }
    .provider-tab:not(.active):hover { background: #f3f4f6; }
  `]
})
export class SyncSectionComponent implements OnInit, OnDestroy {
  private store = inject(Store);
  private syncService = inject(SyncService);
  private diskSetupService = inject(DiskSetupService);

  loading = signal(false);
  starting = signal(false);
  confirming = signal(false);
  driveStatus = signal<DriveStatus | null>(null);
  activeProgress = signal<SyncProgressEvent | null>(null);
  selectedProvider = signal<'ICLOUD' | 'IPHONE'>('ICLOUD');

  /** Device statuses and accounts come from global stores — no local fetching. */
  statuses = this.store.selectSignal(DevicesState.devices);
  accounts = this.store.selectSignal(AccountsState.accounts);

  private syncStartTime: number | null = null;
  private progressSub?: Subscription;

  constructor() {
    // When only one source is available, auto-select it
    effect(() => {
      const r = this.readiness();
      if (r.icloud && !r.iphone) this.selectedProvider.set('ICLOUD');
      if (r.iphone && !r.icloud) this.selectedProvider.set('IPHONE');
    });
  }

  readiness = computed<DeviceReadiness>(() => {
    const s = this.statuses();
    const drive = s.some(d => d.deviceType === 'EXTERNAL_DRIVE' && d.connected);
    const iphone = s.some(d => d.deviceType === 'IPHONE' && d.connected);
    const icloud = s.some(d => d.deviceType === 'ICLOUD' && d.connected);
    const anySource = iphone || icloud;
    return { drive, iphone, icloud, anySource, ready: drive && anySource };
  });

  primaryAccount = computed<AccountResponse | null>(() => {
    const list = this.accounts();
    return list.find(a => a.hasActiveSession) ?? list[0] ?? null;
  });

  /** Can we actually start a sync right now for the selected provider? */
  canStart = computed<boolean>(() => {
    const r = this.readiness();
    if (!r.ready) return false;
    const p = this.selectedProvider();
    if (p === 'ICLOUD') return r.icloud && !!this.primaryAccount();
    if (p === 'IPHONE') return r.iphone; // stub — will show error from backend
    return false;
  });

  /** Label shown on the start button based on selected provider. */
  startButtonLabel = computed<string>(() =>
    this.selectedProvider() === 'IPHONE' ? 'Synchronizuj z iPhone' : 'Rozpocznij synchronizację'
  );

  isActivelySyncing = computed<boolean>(() => {
    const phase = this.activeProgress()?.phase;
    return phase === 'FETCHING_METADATA'
      || phase === 'PERSISTING_METADATA'
      || phase === 'COMPARING'
      || phase === 'DOWNLOADING';
  });

  driveLabel = computed<string>(() => {
    const d = this.driveStatus();
    if (!d || !d.mounted) return '—';
    const path = d.drivePathHost ?? d.drivePath;
    return (d.label || 'dysk zewnętrzny') + (path ? ` (${path})` : '');
  });

  sourceLabel = computed<string>(() => {
    const r = this.readiness();
    if (r.iphone && r.icloud) return 'iPhone + iCloud';
    if (r.iphone) return 'iPhone';
    if (r.icloud) return 'iCloud';
    return '—';
  });

  syncNeededText = computed(() => {
    const p = this.activeProgress();
    if (!p) return 'Kliknij aby sprawdzić stan biblioteki iCloud i zsynchronizować nowe zdjęcia.';
    if (p.totalOnCloud > 0 && p.synced >= p.totalOnCloud) {
      return `Wszystkie ${p.totalOnCloud} zdjęć jest już zsynchronizowanych.`;
    }
    const remaining = Math.max(0, (p.totalOnCloud || 0) - p.synced);
    return `Do zsynchronizowania: ${remaining} zdjęć.`;
  });

  etaText = computed(() => {
    const p = this.activeProgress();
    if (!p || p.percentComplete <= 0 || p.percentComplete >= 100) return null;
    if (!this.syncStartTime) return null;

    const elapsedMs = Date.now() - this.syncStartTime;
    const totalEstMs = elapsedMs / (p.percentComplete / 100);
    const remainingMs = totalEstMs - elapsedMs;
    return this.formatDuration(remainingMs);
  });

  ngOnInit(): void {
    this.refresh();
    this.progressSub = this.syncService.syncProgress$.subscribe(evt => {
      if (evt) {
        if (!this.syncStartTime) this.syncStartTime = Date.now();
        this.activeProgress.set(evt);
        if (evt.phase === 'DONE' || evt.phase === 'ERROR' || evt.phase === 'CANCELLED') {
          this.syncStartTime = null;
        }
      }
    });
  }

  ngOnDestroy(): void {
    this.progressSub?.unsubscribe();
  }

  /**
   * Loads drive status. Device statuses and accounts are managed by
   * DevicesState and AccountsState respectively — no direct fetching here.
   *
   * TODO: Test that removing statusService/accountService calls here doesn't
   * regress the sync readiness display when the dashboard first loads.
   */
  refresh(): void {
    this.loading.set(true);
    this.diskSetupService.getStatus().subscribe({
      next: (d) => {
        this.driveStatus.set(d);
        this.loading.set(false);
      },
      error: () => {
        this.driveStatus.set(null);
        this.loading.set(false);
      },
    });
  }

  startSync(): void {
    const acc = this.primaryAccount();
    const provider = this.selectedProvider();
    if (provider === 'ICLOUD' && !acc) return;
    this.starting.set(true);
    this.syncStartTime = Date.now();
    // For iPhone, use any account's session (Apple ID link) or a placeholder empty string
    const accountId = acc?.id ?? '';
    this.syncService.startSync(accountId, provider).subscribe({
      next: () => this.starting.set(false),
      error: () => {
        this.starting.set(false);
        this.syncStartTime = null;
      }
    });
  }

  confirmSync(): void {
    const acc = this.primaryAccount();
    if (!acc) return;
    this.confirming.set(true);
    this.syncService.confirmSync(acc.id).subscribe({
      next: () => this.confirming.set(false),
      error: () => this.confirming.set(false)
    });
  }

  cancelSync(): void {
    const acc = this.primaryAccount();
    if (acc) {
      this.syncService.cancelSync(acc.id).subscribe();
    }
    this.starting.set(false);
    this.syncService.reset();
    this.activeProgress.set(null);
    this.syncStartTime = null;
  }

  phaseLabel(phase: string): string {
    switch (phase) {
      case 'FETCHING_METADATA': return 'Pobieranie metadanych';
      case 'PERSISTING_METADATA': return 'Zapisywanie do bazy';
      case 'COMPARING': return 'Porównywanie';
      case 'AWAITING_CONFIRMATION': return 'Oczekiwanie na potwierdzenie';
      case 'DOWNLOADING': return 'Pobieranie zdjęć';
      case 'DONE': return 'Zakończone';
      case 'ERROR': return 'Błąd';
      case 'CANCELLED': return 'Anulowano';
      default: return phase;
    }
  }

  formatBytes(bytes: number): string {
    if (bytes >= 1_073_741_824) return (bytes / 1_073_741_824).toFixed(1) + ' GB';
    if (bytes >= 1_048_576) return (bytes / 1_048_576).toFixed(1) + ' MB';
    if (bytes >= 1024) return (bytes / 1024).toFixed(0) + ' KB';
    return bytes + ' B';
  }

  private formatDuration(ms: number): string {
    if (ms < 0 || !isFinite(ms)) return '—';
    const s = Math.round(ms / 1000);
    if (s < 60) return s + 's';
    const m = Math.round(s / 60);
    if (m < 60) return m + ' min';
    const h = Math.floor(m / 60);
    const rem = m % 60;
    return h + 'h ' + rem + 'min';
  }
}
