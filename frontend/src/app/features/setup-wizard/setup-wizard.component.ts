import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router } from '@angular/router';
import { AccountService } from '../../core/services/account.service';
import { WizardState } from '../../core/models/sync-config.model';
import { DiskConfirmStepComponent } from './steps/disk-confirm-step.component';
import { FolderPickerStepComponent } from './steps/folder-picker-step.component';
import { OrganizeStrategyStepComponent } from './steps/organize-strategy-step.component';
import { ReorganizeStepComponent } from './steps/reorganize-step.component';

type WizardStep = 1 | 2 | 3 | 4;

@Component({
  selector: 'app-setup-wizard',
  standalone: true,
  imports: [
    CommonModule,
    DiskConfirmStepComponent,
    FolderPickerStepComponent,
    OrganizeStrategyStepComponent,
    ReorganizeStepComponent,
  ],
  template: `
    <div class="wizard-page">
      <div class="wizard-card">
        <div class="wizard-header">
          <img class="logo-icon" src="assets/favicon-32x32.png" alt="CloudSync" width="28" height="28">
          <h1>Konfiguracja synchronizacji</h1>
        </div>

        <div class="stepper">
          @for (s of steps; track s.num) {
            <div class="step" [class.active]="currentStep() === s.num" [class.done]="currentStep() > s.num">
              <div class="step-circle">
                @if (currentStep() > s.num) { <span>&#10003;</span> }
                @else { <span>{{ s.num }}</span> }
              </div>
              <span class="step-label">{{ s.label }}</span>
            </div>
            @if (s.num < steps.length) {
              <div class="step-line" [class.done]="currentStep() > s.num"></div>
            }
          }
        </div>

        <div class="step-content">
          @if (loading()) {
            <div class="spinner-wrap"><div class="spinner"></div><p>Ładowanie...</p></div>
          } @else if (error()) {
            <div class="error-msg">{{ error() }}</div>
          } @else {
            @if (currentStep() === 1) {
              <app-disk-confirm-step
                (confirmed)="onDiskConfirmed($event)"
              />
            }
            @if (currentStep() === 2) {
              <app-folder-picker-step
                (folderSelected)="onFolderSelected($event)"
                (back)="goBack()"
              />
            }
            @if (currentStep() === 3) {
              <app-organize-strategy-step
                [organizeBy]="state().organizeBy"
                (strategySelected)="onStrategySelected($event)"
                (back)="goBack()"
              />
            }
            @if (currentStep() === 4) {
              <app-reorganize-step
                [state]="state()"
                (done)="onWizardDone()"
                (back)="goBack()"
              />
            }
          }
        </div>
      </div>
    </div>
  `,
  styles: [`
    .wizard-page {
      min-height: 100vh;
      display: flex;
      align-items: center;
      justify-content: center;
      background: var(--color-bg-secondary, #f5f5f5);
      padding: 2rem;
    }

    .wizard-card {
      background: #fff;
      border-radius: 12px;
      box-shadow: 0 4px 24px rgba(0,0,0,0.08);
      padding: 2.5rem;
      width: 100%;
      max-width: 640px;
    }

    .wizard-header {
      display: flex;
      align-items: center;
      gap: 0.75rem;
      margin-bottom: 2rem;

      .logo-icon { width: 28px; height: 28px; flex-shrink: 0; }
      h1 { font-size: 1.25rem; font-weight: 600; margin: 0; }
    }

    .stepper {
      display: flex;
      align-items: center;
      margin-bottom: 2rem;
      flex-wrap: nowrap;
      overflow-x: auto;
    }

    .step {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 0.25rem;
      flex-shrink: 0;
    }

    .step-circle {
      width: 32px;
      height: 32px;
      border-radius: 50%;
      border: 2px solid #d1d5db;
      display: flex;
      align-items: center;
      justify-content: center;
      font-size: 0.85rem;
      font-weight: 600;
      color: #9ca3af;
      background: #fff;
      transition: all 0.2s;

      .step.active & {
        border-color: #3b82f6;
        color: #3b82f6;
      }
      .step.done & {
        border-color: #22c55e;
        background: #22c55e;
        color: #fff;
      }
    }

    .step-label {
      font-size: 0.7rem;
      color: #9ca3af;
      white-space: nowrap;

      .step.active & { color: #3b82f6; font-weight: 600; }
      .step.done & { color: #22c55e; }
    }

    .step-line {
      flex: 1;
      height: 2px;
      background: #e5e7eb;
      margin: 0 0.25rem;
      margin-bottom: 1rem;
      transition: background 0.2s;

      &.done { background: #22c55e; }
    }

    .step-content {
      min-height: 200px;
    }

    .spinner-wrap {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 1rem;
      padding: 3rem 0;
    }

    .spinner {
      width: 32px;
      height: 32px;
      border: 3px solid #e5e7eb;
      border-top-color: #3b82f6;
      border-radius: 50%;
      animation: spin 0.8s linear infinite;
    }

    @keyframes spin { to { transform: rotate(360deg); } }

    .error-msg { color: #dc2626; padding: 1rem 0; }
  `]
})
export class SetupWizardComponent implements OnInit {
  private accountService = inject(AccountService);
  private router = inject(Router);

  currentStep = signal<WizardStep>(1);
  loading = signal(true);
  error = signal<string | null>(null);

  state = signal<WizardState>({
    accountId: null,
    deviceId: null,
    deviceLabel: null,
    selectedFolder: null,
    scanResult: null,
    organizeBy: 'MONTH',
  });

  steps = [
    { num: 1, label: 'Dysk' },
    { num: 2, label: 'Folder' },
    { num: 3, label: 'Segregacja' },
    { num: 4, label: 'Reorganizacja' },
  ];

  ngOnInit(): void {
    this.accountService.listAccounts().subscribe({
      next: (accounts) => {
        if (!accounts || accounts.length === 0) {
          this.error.set('Brak kont iCloud. Zaloguj się najpierw.');
        } else {
          this.state.update(s => ({ ...s, accountId: accounts[0].id }));
        }
        this.loading.set(false);
      },
      error: () => {
        this.error.set('Nie można pobrać listy kont.');
        this.loading.set(false);
      }
    });
  }

  onDiskConfirmed(event: { deviceId: string; label: string }): void {
    this.state.update(s => ({ ...s, deviceId: event.deviceId, deviceLabel: event.label }));
    this.currentStep.set(2);
  }

  onFolderSelected(event: { path: string; scanResult: import('../../core/models/sync-config.model').DiskScanResult }): void {
    this.state.update(s => ({ ...s, selectedFolder: event.path, scanResult: event.scanResult }));
    this.currentStep.set(3);
  }

  onStrategySelected(organizeBy: 'YEAR' | 'MONTH'): void {
    this.state.update(s => ({ ...s, organizeBy }));
    const hasPreviousFiles = (this.state().scanResult?.totalFiles ?? 0) > 0;
    if (hasPreviousFiles) {
      this.currentStep.set(4);
    } else {
      this.onWizardDone();
    }
  }

  goBack(): void {
    const step = this.currentStep();
    if (step > 1) {
      this.currentStep.set((step - 1) as WizardStep);
    }
  }

  onWizardDone(): void {
    this.router.navigate(['/dashboard']);
  }
}
