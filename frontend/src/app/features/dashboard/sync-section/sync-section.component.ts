import { Component, OnInit, OnDestroy, inject, signal, computed } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { StatusService } from '../../../core/api/generated/status/status.service';
import { AccountService } from '../../../core/services/account.service';
import { SyncService } from '../../../core/services/sync.service';
import { DiskSetupService, DriveStatus } from '../../../core/services/disk-setup.service';
import { SyncProgressEvent } from '../../../core/models/sync-progress.model';
import { DeviceStatusResponse } from '../../../core/api/generated/model/deviceStatusResponse';
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
            <p class="sync-sub muted">
              Źródło: <strong>{{ sourceLabel() }}</strong>
            </p>
            <button
              class="btn btn-primary"
              [disabled]="starting() || !canStart()"
              (click)="startSync()"
            >
              {{ starting() ? 'Uruchamianie...' : 'Rozpocznij synchronizację' }}
            </button>
            @if (readiness().icloud && !primaryAccount()) {
              <p class="sync-sub muted">iCloud online, ale brak dodanego konta — dodaj je poniżej.</p>
            }
          } @else if (activeProgress()!.phase === 'AWAITING_CONFIRMATION') {
            <p class="sync-title">Gotowe do pobrania</p>
            <p class="sync-sub">Znaleziono zdjęcia w iCloud. Czy chcesz je skopiować na dysk zewnętrzny?</p>
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
            <p class="sync-title" [class.sync-title--error]="activeProgress()!.phase === 'ERROR'">
              Status: {{ phaseLabel(activeProgress()!.phase) }}
            </p>
            <div class="progress-wrap">
              <div class="progress-bar">
                <div class="progress-fill" [style.width.%]="activeProgress()!.percentComplete"></div>
              </div>
              <span class="progress-pct">{{ activeProgress()!.percentComplete | number:'1.0-0' }}%</span>
            </div>
            <ul class="stats">
              @if (activeProgress()!.phase === 'FETCHING_METADATA') {
                <li>Pobrano metadane: <strong>{{ activeProgress()!.metadataFetched }}</strong>{{ activeProgress()!.totalOnCloud > 0 ? ' / ' + activeProgress()!.totalOnCloud : '' }}</li>
              } @else {
                <li>Zsynchronizowane: <strong>{{ activeProgress()!.synced }}</strong> / {{ activeProgress()!.totalOnCloud || '—' }}</li>
              }
              <li>Oczekujące: <strong>{{ activeProgress()!.pending }}</strong></li>
              @if (activeProgress()!.failed > 0) {
                <li class="err">Błędy: <strong>{{ activeProgress()!.failed }}</strong></li>
              }
              @if (etaText()) {
                <li>Pozostały czas: <strong>{{ etaText() }}</strong></li>
              }
            </ul>
            @if (activeProgress()!.currentFile) {
              <p class="sync-sub muted">Aktualny plik: {{ activeProgress()!.currentFile }}</p>
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
    .sync-card:has(.sync-title--error) { border-color: #fca5a5; background: #fff5f5; }
  `]
})
export class SyncSectionComponent implements OnInit, OnDestroy {
  private statusService = inject(StatusService);
  private accountService = inject(AccountService);
  private syncService = inject(SyncService);
  private diskSetupService = inject(DiskSetupService);

  loading = signal(false);
  starting = signal(false);
  confirming = signal(false);
  statuses = signal<DeviceStatusResponse[]>([]);
  accounts = signal<AccountResponse[]>([]);
  driveStatus = signal<DriveStatus | null>(null);
  activeProgress = signal<SyncProgressEvent | null>(null);
  private syncStartTime: number | null = null;
  private progressSub?: Subscription;

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

  /** Can we actually start a sync right now? iPhone-only is OK; iCloud requires an account. */
  canStart = computed<boolean>(() => {
    const r = this.readiness();
    if (!r.ready) return false;
    if (r.icloud && this.primaryAccount()) return true;
    return r.iphone;
  });

  driveLabel = computed<string>(() => {
    const d = this.driveStatus();
    if (!d || !d.mounted) return '—';
    return (d.label || 'dysk zewnętrzny') + (d.drivePath ? ` (${d.drivePath})` : '');
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
    if (!p || !this.syncStartTime || p.percentComplete <= 0 || p.percentComplete >= 100) return null;
    const elapsedMs = Date.now() - this.syncStartTime;
    const totalEstMs = elapsedMs / (p.percentComplete / 100);
    const remainingMs = totalEstMs - elapsedMs;
    return this.formatDuration(remainingMs);
  });

  ngOnInit(): void {
    this.refresh();
    this.progressSub = this.syncService.syncProgress$.subscribe(evt => {
      if (evt) {
        if (!this.syncStartTime) {
          this.syncStartTime = Date.now();
        }
        this.activeProgress.set(evt);
        if (evt.phase === 'DONE' || evt.phase === 'ERROR') {
          this.syncStartTime = null;
        }
      }
    });
  }

  ngOnDestroy(): void {
    this.progressSub?.unsubscribe();
  }

  refresh(): void {
    this.loading.set(true);
    this.statusService.getDeviceStatuses().subscribe({
      next: (s) => this.statuses.set(s),
      error: () => this.statuses.set([]),
      complete: () => this.loading.set(false)
    });
    this.accountService.listAccounts().subscribe({
      next: (a) => this.accounts.set(a),
      error: () => this.accounts.set([])
    });
    this.diskSetupService.getStatus().subscribe({
      next: (d) => this.driveStatus.set(d),
      error: () => this.driveStatus.set(null)
    });
  }

  startSync(): void {
    const acc = this.primaryAccount();
    if (!acc) {
      // iPhone-only sync has no backend endpoint yet; placeholder until implemented.
      return;
    }
    this.starting.set(true);
    this.syncStartTime = Date.now();
    this.syncService.startSync(acc.id).subscribe({
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
    this.syncService.reset();
    this.activeProgress.set(null);
    this.syncStartTime = null;
  }

  phaseLabel(phase: string): string {
    switch (phase) {
      case 'FETCHING_METADATA': return 'Pobieranie metadanych';
      case 'COMPARING': return 'Porównywanie';
      case 'AWAITING_CONFIRMATION': return 'Oczekiwanie na potwierdzenie';
      case 'DOWNLOADING': return 'Pobieranie zdjęć';
      case 'DONE': return 'Zakończone';
      case 'ERROR': return 'Błąd';
      default: return phase;
    }
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
