import { Component, inject, signal, OnInit, OnDestroy } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { JobsService } from '../../core/api/generated/jobs/jobs.service';
import { TaskHistoryDto } from '../../core/api/generated/model/taskHistoryDto';
import { TaskHistoryDetailDto } from '../../core/api/generated/model/taskHistoryDetailDto';

type FilterType = 'ALL' | 'SYNC' | 'DELETION' | 'THUMBNAIL';
type FilterStatus = 'ALL' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED';

@Component({
  selector: 'app-tasks',
  standalone: true,
  imports: [CommonModule, DatePipe],
  styleUrl: './tasks.component.scss',
  template: `
    <div class="tasks-page">
      <div class="page-header">
        <div class="page-header-row">
          <div>
            <h1>Tasks</h1>
            <p class="subtitle">History of all background operations</p>
          </div>
          <button class="refresh-btn" (click)="refresh()" [disabled]="loading()">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" [class.spinning]="loading()">
              <polyline points="23 4 23 10 17 10"/><polyline points="1 20 1 14 7 14"/>
              <path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"/>
            </svg>
            Refresh
          </button>
        </div>
      </div>

      <div class="filters">
        <div class="filter-group">
          <span class="filter-label">Type</span>
          <div class="custom-select" [class.open]="typeOpen()">
            <button class="cs-trigger" (click)="typeOpen.set(!typeOpen())" type="button">
              <span class="cs-value">{{ labelFor(typeOptions, typeFilter()) }}</span>
              <svg class="cs-chevron" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
                <polyline points="6 9 12 15 18 9"/>
              </svg>
            </button>
            @if (typeOpen()) {
              <div class="cs-panel">
                @for (t of typeOptions; track t.value) {
                  <button class="cs-option" [class.active]="typeFilter() === t.value"
                          (click)="setTypeFilter(t.value); typeOpen.set(false)" type="button">
                    @if (typeFilter() === t.value) {
                      <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="20 6 9 17 4 12"/></svg>
                    } @else {
                      <span style="width:12px;display:inline-block"></span>
                    }
                    {{ t.label }}
                  </button>
                }
              </div>
            }
          </div>
        </div>
        <div class="filter-group">
          <span class="filter-label">Status</span>
          <div class="custom-select" [class.open]="statusOpen()">
            <button class="cs-trigger" (click)="statusOpen.set(!statusOpen())" type="button">
              <span class="cs-value">{{ labelFor(statusOptions, statusFilter()) }}</span>
              <svg class="cs-chevron" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
                <polyline points="6 9 12 15 18 9"/>
              </svg>
            </button>
            @if (statusOpen()) {
              <div class="cs-panel">
                @for (s of statusOptions; track s.value) {
                  <button class="cs-option" [class.active]="statusFilter() === s.value"
                          (click)="setStatusFilter(s.value); statusOpen.set(false)" type="button">
                    @if (statusFilter() === s.value) {
                      <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="20 6 9 17 4 12"/></svg>
                    } @else {
                      <span style="width:12px;display:inline-block"></span>
                    }
                    {{ s.label }}
                  </button>
                }
              </div>
            }
          </div>
        </div>
      </div>

      <div class="tasks-content">
        <div class="tasks-list">
          @if (loading()) {
            <div class="state-msg">Loading...</div>
          } @else if (tasks().length === 0) {
            <div class="state-msg empty">No tasks found.</div>
          } @else {
            @for (task of tasks(); track task.id) {
              <div class="task-row" [class.selected]="selectedId() === task.id" (click)="selectTask(task)">
                <div class="task-type-icon" [attr.data-type]="task.type">
                  {{ typeIcon(task.type) }}
                </div>
                <div class="task-info">
                  <div class="task-title">{{ taskLabel(task) }}</div>
                  <div class="task-meta">
                    {{ task.createdAt | date:'dd MMM yyyy, HH:mm' }}
                    @if (task.durationMs) {
                      &nbsp;·&nbsp;{{ formatDuration(task.durationMs) }}
                    }
                  </div>
                </div>
                <div class="task-stats">
                  @if (task.status !== 'RUNNING' && task.totalItems > 0) {
                    <span class="stat ok">{{ task.succeededItems }} ok</span>
                    @if (task.failedItems > 0) {
                      <span class="stat fail">{{ task.failedItems }} failed</span>
                    }
                  }
                </div>
                <div class="task-status" [attr.data-status]="task.status">
                  {{ task.status }}
                </div>
              </div>
            }

            @if (hasMore()) {
              <button class="load-more-btn" (click)="loadMore()" [disabled]="loading()">
                Load more
              </button>
            }
          }
        </div>

        @if (selectedId()) {
          <div class="task-detail">
            @if (detailLoading()) {
              <div class="state-msg">Loading details...</div>
            } @else if (detail()) {
              <div class="detail-header">
                <h2>{{ taskLabel(detail()!) }}</h2>
                <div class="detail-status" [attr.data-status]="detail()!.status">{{ detail()!.status }}</div>
              </div>

              <div class="detail-meta">
                <div class="meta-row">
                  <span class="meta-key">Started</span>
                  <span>{{ detail()!.createdAt | date:'dd MMM yyyy, HH:mm:ss' }}</span>
                </div>
                @if (detail()!.completedAt) {
                  <div class="meta-row">
                    <span class="meta-key">Finished</span>
                    <span>{{ detail()!.completedAt | date:'dd MMM yyyy, HH:mm:ss' }}</span>
                  </div>
                }
                @if (detail()!.durationMs) {
                  <div class="meta-row">
                    <span class="meta-key">Duration</span>
                    <span>{{ formatDuration(detail()!.durationMs!) }}</span>
                  </div>
                }
                <div class="meta-row">
                  <span class="meta-key">Result</span>
                  <span>{{ detail()!.succeededItems }} ok / {{ detail()!.failedItems }} failed / {{ detail()!.totalItems }} total</span>
                </div>
                @if (detail()!.errorMessage) {
                  <div class="meta-row error-row">
                    <span class="meta-key">Error</span>
                    <span class="error-text">{{ detail()!.errorMessage }}</span>
                  </div>
                }
              </div>

              @if (detail()!.phases && detail()!.phases.length > 0) {
                <div class="detail-section">
                  <h3>Phases</h3>
                  <div class="phases-list">
                    @for (phase of detail()!.phases; track $index) {
                      <div class="phase-row">
                        <div class="phase-name">{{ phase.phase }}</div>
                        <div class="phase-times">
                          @if (phase.startedAt) {
                            <span>{{ phase.startedAt | date:'HH:mm:ss' }}</span>
                          }
                          @if (phase.completedAt) {
                            <span class="phase-arrow">→</span>
                            <span>{{ phase.completedAt | date:'HH:mm:ss' }}</span>
                          }
                          @if (phase.durationMs) {
                            <span class="phase-duration">({{ formatDuration(phase.durationMs) }})</span>
                          }
                        </div>
                        @if (phase.errorMessage) {
                          <div class="phase-error">{{ phase.errorMessage }}</div>
                        }
                      </div>
                    }
                  </div>
                </div>
              }

              @if (detail()!.items && detail()!.items.length > 0) {
                <div class="detail-section">
                  <h3>Items ({{ detail()!.items.length }})</h3>
                  <div class="items-list">
                    <div class="items-header">
                      <span>File</span>
                      <span>Status</span>
                    </div>
                    @for (item of detail()!.items; track $index) {
                      <div class="item-row" [class.item-failed]="item.itemStatus === 'FAILED'">
                        <span class="item-name">{{ item.photoName || item.photoId || '—' }}</span>
                        <span class="item-status" [attr.data-status]="item.itemStatus">{{ item.itemStatus }}</span>
                        @if (item.errorMessage) {
                          <span class="item-error">{{ item.errorMessage }}</span>
                        }
                      </div>
                    }
                  </div>
                </div>
              }
            }
          </div>
        }
      </div>
    </div>
  `,
})
export class TasksComponent implements OnInit, OnDestroy {
  private jobsService = inject(JobsService);
  private pollInterval: ReturnType<typeof setInterval> | null = null;

  tasks = signal<TaskHistoryDto[]>([]);
  loading = signal(false);
  detail = signal<TaskHistoryDetailDto | null>(null);
  detailLoading = signal(false);
  selectedId = signal<string | null>(null);

  typeFilter = signal<FilterType>('ALL');
  statusFilter = signal<FilterStatus>('ALL');
  typeOpen = signal(false);
  statusOpen = signal(false);

  currentPage = signal(0);
  hasMore = signal(false);

  typeOptions: { value: FilterType; label: string }[] = [
    { value: 'ALL', label: 'All' },
    { value: 'SYNC', label: 'Sync' },
    { value: 'DELETION', label: 'Deletion' },
    { value: 'THUMBNAIL', label: 'Thumbnails' },
  ];

  statusOptions: { value: FilterStatus; label: string }[] = [
    { value: 'ALL', label: 'All' },
    { value: 'RUNNING', label: 'Running' },
    { value: 'COMPLETED', label: 'Completed' },
    { value: 'FAILED', label: 'Failed' },
    { value: 'CANCELLED', label: 'Cancelled' },
  ];

  ngOnInit(): void {
    this.load(0);
    this.pollInterval = setInterval(() => this.refresh(), 10000);
  }

  ngOnDestroy(): void {
    if (this.pollInterval !== null) clearInterval(this.pollInterval);
  }

  refresh(): void {
    this.load(0, true);
    const sid = this.selectedId();
    if (sid) this.loadDetail(sid);
  }

  setTypeFilter(value: FilterType): void {
    this.typeFilter.set(value);
    this.typeOpen.set(false);
    this.load(0);
  }

  setStatusFilter(value: FilterStatus): void {
    this.statusFilter.set(value);
    this.statusOpen.set(false);
    this.load(0);
  }

  labelFor(options: { value: string; label: string }[], value: string): string {
    return options.find(o => o.value === value)?.label ?? value;
  }

  loadMore(): void {
    this.load(this.currentPage() + 1);
  }

  selectTask(task: TaskHistoryDto): void {
    if (this.selectedId() === task.id) {
      this.selectedId.set(null);
      this.detail.set(null);
      return;
    }
    this.selectedId.set(task.id);
    this.loadDetail(task.id);
  }

  private loadDetail(taskId: string): void {
    this.detailLoading.set(true);
    this.jobsService.getTaskDetail(taskId).subscribe({
      next: (d) => {
        this.detail.set(d);
        this.detailLoading.set(false);
      },
      error: () => this.detailLoading.set(false),
    });
  }

  typeIcon(type: string): string {
    switch (type) {
      case 'SYNC': return '↓';
      case 'DELETION': return '✕';
      case 'THUMBNAIL': return '▣';
      default: return '·';
    }
  }

  taskLabel(task: TaskHistoryDto | TaskHistoryDetailDto): string {
    switch (task.type) {
      case 'SYNC': return `Sync (${task.provider ?? 'unknown'})`;
      case 'DELETION': return `Delete photos (${task.provider ?? 'unknown'})`;
      case 'THUMBNAIL': return 'Generate thumbnails';
      default: return task.type;
    }
  }

  formatDuration(ms: number): string {
    if (ms < 1000) return `${ms}ms`;
    if (ms < 60000) return `${(ms / 1000).toFixed(1)}s`;
    const m = Math.floor(ms / 60000);
    const s = Math.floor((ms % 60000) / 1000);
    return `${m}m ${s}s`;
  }

  private load(page: number, silent = false): void {
    if (!silent) this.loading.set(true);
    const typeVal = this.typeFilter();
    const statusVal = this.statusFilter();
    const params: Record<string, string | number> = { page, size: 25 };
    if (typeVal !== 'ALL') params['type'] = typeVal;
    if (statusVal !== 'ALL') params['status'] = statusVal;

    this.jobsService.listHistory(params as any).subscribe({
      next: (result) => {
        const incoming = result.tasks ?? [];
        this.tasks.set(page === 0 ? incoming : [...this.tasks(), ...incoming]);
        this.currentPage.set(page);
        this.hasMore.set(page + 1 < (result.totalPages ?? 0));
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }
}
