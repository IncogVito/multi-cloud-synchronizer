import { Component, computed, inject, signal } from '@angular/core';
import { Store } from '@ngxs/store';
import { JobsState, Job } from '../../../state/jobs/jobs.state';
import { CancelJob } from '../../../state/jobs/jobs.actions';

@Component({
  selector: 'app-global-task-bar',
  standalone: true,
  template: `
    @if (jobs().length > 0) {
      <div class="task-bar" [class.expanded]="expanded()">
        <button class="task-bar-header" (click)="expanded.set(!expanded())">
          <span class="task-bar-title">
            @if (activeCount() > 0) {
              <span class="spinner"></span>
              {{ activeCount() }} task{{ activeCount() !== 1 ? 's' : '' }} running
            } @else {
              All tasks done
            }
          </span>
          <span class="chevron">{{ expanded() ? '▼' : '▲' }}</span>
        </button>

        @if (expanded()) {
          <div class="task-list">
            @for (job of jobs(); track job.jobId) {
              <div class="task-item" [class.done]="job.status === 'COMPLETED'">
                <div class="task-header">
                  <span class="task-icon">{{ job.type === 'DELETION' ? '🗑' : '🖼' }}</span>
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
export class GlobalTaskBarComponent {
  private store = inject(Store);

  jobs = this.store.selectSignal(JobsState.jobs);
  activeCount = computed(() => this.jobs().filter(j => j.status === 'RUNNING').length);
  expanded = signal(true);

  cancel(job: Job): void {
    this.store.dispatch(new CancelJob(job.jobId));
  }
}
