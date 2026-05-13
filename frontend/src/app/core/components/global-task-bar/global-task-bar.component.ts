import { Component, computed, inject, signal, OnInit, OnDestroy } from '@angular/core';
import { Store } from '@ngxs/store';
import { JobsState, Job } from '../../../state/jobs/jobs.state';
import { CancelJob } from '../../../state/jobs/jobs.actions';
import { SyncService } from '../../services/sync.service';
import { SyncProgressEvent } from '../../models/sync-progress.model';
import { Subscription } from 'rxjs';

@Component({
  selector: 'app-global-task-bar',
  standalone: true,
  template: `
    @if (hasAnything()) {
      <div class="task-bar" [class.expanded]="expanded()">
        <button class="task-bar-header" (click)="expanded.set(!expanded())">
          <span class="task-bar-title">
            @if (totalActiveCount() > 0) {
              <span class="spinner"></span>
              {{ totalActiveCount() }} task{{ totalActiveCount() !== 1 ? 's' : '' }} running
            } @else {
              All tasks done
            }
          </span>
          <span class="chevron">{{ expanded() ? '▼' : '▲' }}</span>
        </button>

        @if (expanded()) {
          <div class="task-list">
            @if (syncProgress(); as sp) {
              @if (sp.phase !== 'DONE' && sp.phase !== 'ERROR' && sp.phase !== 'CANCELLED') {
                <div class="task-item">
                  <div class="task-header">
                    <span class="task-icon">↓</span>
                    <span class="task-label">{{ syncLabel(sp) }}</span>
                  </div>
                  @if (syncPercent(sp) > 0) {
                    <div class="task-progress-bar">
                      <div class="task-progress-fill" [style.width.%]="syncPercent(sp)"></div>
                    </div>
                  }
                  <div class="task-counts">{{ syncCounts(sp) }}</div>
                </div>
              } @else if (sp.phase === 'DONE') {
                <div class="task-item done">
                  <div class="task-header">
                    <span class="task-icon">↓</span>
                    <span class="task-label">Sync zakończony</span>
                  </div>
                  <div class="task-done-row">✓ {{ sp.synced }} pobranych@if (sp.failed > 0) {, {{ sp.failed }} błędów}</div>
                </div>
              }
            }
            @for (job of jobs(); track job.jobId) {
              <div class="task-item" [class.done]="job.status === 'COMPLETED'">
                <div class="task-header">
                  <span class="task-icon">{{ job.type === 'DELETION' ? '🗑' : '▣' }}</span>
                  <span class="task-label">{{ job.label }}</span>
                  @if (job.status === 'RUNNING' && job.type === 'DELETION') {
                    <button class="cancel-btn" (click)="cancel(job)" title="Cancel">✕</button>
                  }
                </div>
                @if (job.status === 'COMPLETED') {
                  <div class="task-done-row">
                    ✓ Done — {{ job.done }} ok / {{ job.failed }} failed
                  </div>
                } @else {
                  <div class="task-progress-bar">
                    <div class="task-progress-fill"
                         [style.width.%]="job.total ? (job.done / job.total) * 100 : 0">
                    </div>
                  </div>
                  <div class="task-counts">{{ job.done }} / {{ job.total }}</div>
                }
              </div>
            }
          </div>
        }
      </div>
    }
  `,
  styles: [`
    .task-bar {
      position: fixed;
      bottom: 16px;
      right: 16px;
      z-index: 1000;
      background: var(--color-bg-primary, #fff);
      border: 1px solid var(--color-border, #e2e8f0);
      border-radius: var(--radius-lg, 8px);
      box-shadow: var(--shadow-lg, 0 4px 16px rgba(0,0,0,0.12));
      min-width: 280px;
      max-width: 360px;
      overflow: hidden;
    }

    .task-bar-header {
      width: 100%;
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 10px 14px;
      background: none;
      border: none;
      cursor: pointer;
      font-size: 13px;
      font-weight: 600;
      color: var(--color-text-primary, #1a202c);
    }

    .task-bar-title {
      display: flex;
      align-items: center;
      gap: 8px;
    }

    .spinner {
      display: inline-block;
      width: 12px;
      height: 12px;
      border: 2px solid var(--color-border, #e2e8f0);
      border-top-color: var(--color-primary, #3b82f6);
      border-radius: 50%;
      animation: spin 0.8s linear infinite;
    }

    @keyframes spin { to { transform: rotate(360deg); } }

    .chevron { font-size: 10px; color: var(--color-text-muted, #718096); }

    .task-list {
      border-top: 1px solid var(--color-border, #e2e8f0);
      max-height: 320px;
      overflow-y: auto;
    }

    .task-item {
      padding: 10px 14px;
      border-bottom: 1px solid var(--color-border, #e2e8f0);
      &:last-child { border-bottom: none; }
      &.done { opacity: 0.7; }
    }

    .task-header {
      display: flex;
      align-items: center;
      gap: 6px;
      margin-bottom: 6px;
      font-size: 12px;
      font-weight: 500;
    }

    .task-icon { font-size: 14px; }

    .task-label {
      flex: 1;
      color: var(--color-text-primary, #1a202c);
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }

    .cancel-btn {
      background: none;
      border: none;
      cursor: pointer;
      color: var(--color-text-muted, #718096);
      font-size: 11px;
      padding: 2px 4px;
      border-radius: 3px;
      &:hover { background: var(--color-danger-bg, #fff5f5); color: var(--color-danger, #e53e3e); }
    }

    .task-progress-bar {
      height: 4px;
      background: var(--color-bg-secondary, #f7fafc);
      border-radius: 2px;
      overflow: hidden;
      margin-bottom: 4px;
    }

    .task-progress-fill {
      height: 100%;
      background: var(--color-primary, #3b82f6);
      border-radius: 2px;
      transition: width 0.3s ease;
    }

    .task-counts {
      font-size: 11px;
      color: var(--color-text-muted, #718096);
    }

    .task-done-row {
      font-size: 11px;
      color: var(--color-success, #38a169);
    }
  `],
})
export class GlobalTaskBarComponent implements OnInit, OnDestroy {
  private store = inject(Store);
  private syncService = inject(SyncService);
  private syncSub: Subscription | null = null;

  jobs = this.store.selectSignal(JobsState.jobs);
  syncProgress = signal<SyncProgressEvent | null>(null);

  activeCount = computed(() => this.jobs().filter(j => j.status === 'RUNNING').length);
  syncActive = computed(() => {
    const sp = this.syncProgress();
    return !!sp && sp.phase !== 'DONE' && sp.phase !== 'ERROR' && sp.phase !== 'CANCELLED';
  });
  totalActiveCount = computed(() => this.activeCount() + (this.syncActive() ? 1 : 0));
  hasAnything = computed(() => this.jobs().length > 0 || !!this.syncProgress());
  expanded = signal(true);

  ngOnInit(): void {
    this.syncSub = this.syncService.syncProgress$.subscribe(ev => this.syncProgress.set(ev));
  }

  ngOnDestroy(): void {
    this.syncSub?.unsubscribe();
  }

  cancel(job: Job): void {
    this.store.dispatch(new CancelJob(job.jobId));
  }

  syncLabel(sp: SyncProgressEvent): string {
    switch (sp.phase) {
      case 'FETCHING_METADATA': return 'Pobieranie listy z iCloud';
      case 'PERSISTING_METADATA': return 'Zapisywanie metadanych';
      case 'COMPARING': return 'Porównywanie zdjęć';
      case 'DOWNLOADING': return 'Pobieranie zdjęć';
      case 'REORGANIZING': return 'Reorganizacja plików';
      default: return 'Synchronizacja';
    }
  }

  syncPercent(sp: SyncProgressEvent): number {
    if (sp.phase === 'FETCHING_METADATA' && sp.totalOnCloud > 0)
      return Math.round((sp.metadataFetched / sp.totalOnCloud) * 100);
    if (sp.phase === 'DOWNLOADING' && sp.totalOnCloud > 0)
      return Math.round((sp.synced / sp.totalOnCloud) * 100);
    return 0;
  }

  syncCounts(sp: SyncProgressEvent): string {
    if (sp.phase === 'FETCHING_METADATA') {
      return sp.totalOnCloud > 0 ? `${sp.metadataFetched} / ${sp.totalOnCloud}` : `${sp.metadataFetched}`;
    }
    if (sp.phase === 'DOWNLOADING') {
      return `${sp.synced} / ${sp.totalOnCloud}`;
    }
    if (sp.phase === 'COMPARING') {
      return `${sp.pending} nowych, ${sp.synced} zsync.`;
    }
    return '';
  }
}
