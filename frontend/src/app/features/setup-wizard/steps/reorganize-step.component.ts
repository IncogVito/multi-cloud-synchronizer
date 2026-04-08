import { Component, inject, input, OnInit, output, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { SetupWizardService } from '../../../core/services/setup-wizard.service';
import { MovePreview, ReorganizeResult, SyncConfigRequest, WizardState } from '../../../core/models/sync-config.model';

type ReorganizePhase = 'ask' | 'preview' | 'executing' | 'done' | 'saving';

@Component({
  selector: 'app-reorganize-step',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="step-body">

      @if (phase() === 'ask') {
        <p class="step-title">Reorganizacja istniejących zdjęć</p>
        <p class="step-desc">
          Znaleziono <strong>{{ state().scanResult?.totalFiles | number }}</strong> zdjęć w wybranym folderze.
          Czy chcesz przeorganizować je zgodnie z wybraną strukturą
          <strong>{{ state().organizeBy === 'MONTH' ? 'miesięczną' : 'roczną' }}</strong>?
        </p>
        @if (error()) {
          <p class="error-msg">{{ error() }}</p>
        }
        <div class="actions">
          <button class="btn btn-ghost" (click)="back.emit()">&#8592; Wstecz</button>
          <button class="btn btn-ghost" (click)="skipReorganize()">Nie, pomiń</button>
          <button class="btn btn-primary" [disabled]="loading()" (click)="runDryRun()">
            @if (loading()) { Sprawdzam... } @else { Tak, pokaż podgląd &rarr; }
          </button>
        </div>
      }

      @if (phase() === 'preview' && previewResult()) {
        <p class="step-title">Podgląd reorganizacji</p>
        <div class="summary-row">
          <span class="summary-item moved">Do przeniesienia: {{ previewResult()!.moved }}</span>
          <span class="summary-item skipped">Bez zmian: {{ previewResult()!.skipped }}</span>
          @if (previewResult()!.errors > 0) {
            <span class="summary-item errors">Błędy: {{ previewResult()!.errors }}</span>
          }
        </div>
        @if (previewResult()!.sampleMoves.length > 0) {
          <div class="sample-moves">
            <p class="sample-title">Przykładowe przeniesienia:</p>
            @for (move of previewResult()!.sampleMoves; track move.from) {
              <div class="move-row">
                <span class="move-from" [title]="move.from">{{ shortPath(move.from) }}</span>
                <span class="move-arrow">&rarr;</span>
                <span class="move-to" [title]="move.to">{{ shortPath(move.to) }}</span>
              </div>
            }
          </div>
        }
        @if (error()) {
          <p class="error-msg">{{ error() }}</p>
        }
        <div class="actions">
          <button class="btn btn-ghost" (click)="phase.set('ask')">&#8592; Wstecz</button>
          <button class="btn btn-ghost" (click)="skipReorganize()">Pomiń reorganizację</button>
          <button class="btn btn-danger" [disabled]="loading()" (click)="executeReorganize()">
            @if (loading()) { Wykonuję... } @else { Wykonaj reorganizację }
          </button>
        </div>
      }

      @if (phase() === 'executing') {
        <div class="spinner-wrap">
          <div class="spinner"></div>
          <p>Reorganizacja w toku...</p>
        </div>
      }

      @if (phase() === 'saving') {
        <div class="spinner-wrap">
          <div class="spinner"></div>
          <p>Zapisywanie konfiguracji...</p>
        </div>
      }

      @if (phase() === 'done') {
        <div class="done-block">
          <div class="done-icon">&#10003;</div>
          @if (finalResult()) {
            <p class="done-title">Reorganizacja zakończona</p>
            <p class="done-sub">
              Przeniesiono: {{ finalResult()!.moved }},
              Pominięto: {{ finalResult()!.skipped }},
              Błędy: {{ finalResult()!.errors }}
            </p>
          } @else {
            <p class="done-title">Konfiguracja zapisana</p>
          }
          <button class="btn btn-primary" (click)="done.emit()">Przejdź do dashboardu &rarr;</button>
        </div>
      }

    </div>
  `,
  styles: [`
    .step-body { display: flex; flex-direction: column; gap: 1.25rem; }

    .step-title { font-weight: 600; font-size: 1rem; margin: 0; }
    .step-desc { color: #374151; font-size: 0.9rem; line-height: 1.5; margin: 0; }

    .summary-row { display: flex; gap: 1rem; flex-wrap: wrap; }

    .summary-item {
      padding: 0.4rem 0.8rem;
      border-radius: 6px;
      font-size: 0.85rem;
      font-weight: 500;

      &.moved { background: #dbeafe; color: #1d4ed8; }
      &.skipped { background: #f3f4f6; color: #6b7280; }
      &.errors { background: #fee2e2; color: #dc2626; }
    }

    .sample-moves {
      background: #f9fafb;
      border: 1px solid #e5e7eb;
      border-radius: 8px;
      padding: 0.75rem;
      display: flex;
      flex-direction: column;
      gap: 0.4rem;
    }

    .sample-title { font-size: 0.8rem; font-weight: 600; color: #6b7280; margin: 0 0 0.4rem; }

    .move-row {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      font-size: 0.78rem;
      font-family: monospace;
      color: #374151;
    }

    .move-from, .move-to {
      flex: 1;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .move-from { color: #9ca3af; }
    .move-to { color: #16a34a; }
    .move-arrow { color: #d1d5db; flex-shrink: 0; }

    .actions { display: flex; gap: 0.75rem; justify-content: space-between; flex-wrap: wrap; }

    .btn {
      padding: 0.5rem 1.25rem;
      border-radius: 8px;
      font-size: 0.875rem;
      font-weight: 500;
      cursor: pointer;
      border: none;
      &:disabled { opacity: 0.5; cursor: not-allowed; }
    }

    .btn-primary { background: #3b82f6; color: #fff; &:hover:not(:disabled) { background: #2563eb; } }
    .btn-ghost { background: transparent; color: #374151; border: 1px solid #d1d5db; &:hover { background: #f9fafb; } }
    .btn-danger { background: #ef4444; color: #fff; &:hover:not(:disabled) { background: #dc2626; } }

    .spinner-wrap { display: flex; flex-direction: column; align-items: center; gap: 1rem; padding: 3rem 0; }

    .spinner {
      width: 32px;
      height: 32px;
      border: 3px solid #e5e7eb;
      border-top-color: #3b82f6;
      border-radius: 50%;
      animation: spin 0.8s linear infinite;
    }

    @keyframes spin { to { transform: rotate(360deg); } }

    .done-block { display: flex; flex-direction: column; align-items: center; gap: 0.75rem; padding: 2rem 0; text-align: center; }

    .done-icon {
      width: 48px;
      height: 48px;
      border-radius: 50%;
      background: #22c55e;
      color: #fff;
      display: flex;
      align-items: center;
      justify-content: center;
      font-size: 1.4rem;
      font-weight: bold;
    }

    .done-title { font-weight: 600; font-size: 1rem; margin: 0; }
    .done-sub { color: #6b7280; font-size: 0.875rem; margin: 0; }

    .error-msg { color: #dc2626; font-size: 0.875rem; }
  `]
})
export class ReorganizeStepComponent implements OnInit {
  state = input.required<WizardState>();
  done = output<void>();
  back = output<void>();

  private wizardService = inject(SetupWizardService);

  phase = signal<ReorganizePhase>('ask');
  loading = signal(false);
  error = signal<string | null>(null);
  previewResult = signal<ReorganizeResult | null>(null);
  finalResult = signal<ReorganizeResult | null>(null);

  ngOnInit(): void {}

  runDryRun(): void {
    const { accountId } = this.state();
    if (!accountId) return;

    this.loading.set(true);
    this.error.set(null);

    this.wizardService.reorganize(accountId, true).subscribe({
      next: (result) => {
        this.previewResult.set(result);
        this.phase.set('preview');
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set(err?.error?.error || 'Błąd podglądu reorganizacji');
        this.loading.set(false);
      }
    });
  }

  executeReorganize(): void {
    const { accountId } = this.state();
    if (!accountId) return;

    this.loading.set(true);
    this.error.set(null);
    this.phase.set('executing');

    this.wizardService.reorganize(accountId, false).subscribe({
      next: (result) => {
        this.finalResult.set(result);
        this.saveConfigAndFinish();
      },
      error: (err) => {
        this.error.set(err?.error?.error || 'Reorganizacja nie powiodła się');
        this.phase.set('preview');
        this.loading.set(false);
      }
    });
  }

  skipReorganize(): void {
    this.saveConfigAndFinish();
  }

  private saveConfigAndFinish(): void {
    const s = this.state();
    if (!s.accountId || !s.selectedFolder) {
      this.phase.set('done');
      return;
    }

    this.phase.set('saving');
    const config: SyncConfigRequest = {
      syncFolderPath: s.selectedFolder,
      storageDeviceId: s.deviceId,
      organizeBy: s.organizeBy,
    };

    this.wizardService.saveSyncConfig(s.accountId, config).subscribe({
      next: () => { this.phase.set('done'); },
      error: (err) => {
        this.error.set(err?.error?.error || 'Zapis konfiguracji nie powiódł się');
        this.phase.set(this.finalResult() ? 'done' : 'preview');
      }
    });
  }

  shortPath(p: string): string {
    const parts = p.split('/');
    return parts.length > 3 ? '.../' + parts.slice(-2).join('/') : p;
  }
}
