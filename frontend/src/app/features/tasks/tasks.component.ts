import { Component, inject, signal, computed, OnInit, OnDestroy } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { JobsService } from '../../core/api/generated/jobs/jobs.service';
import { RepairThumbnailsService } from '../../core/services/repair-thumbnails.service';
import { RepairIPhoneService } from '../../core/services/repair-iphone.service';
import { BackupDatabaseService } from '../../core/services/backup-database.service';
import { DeletePendingService } from '../../core/services/delete-pending.service';
import { MergeDuplicatesService } from '../../core/services/merge-duplicates.service';
import { OrphanPhotosService } from '../../core/services/orphan-photos.service';
import { TaskHistoryDto } from '../../core/api/generated/model/taskHistoryDto';
import { TaskHistoryDetailDto } from '../../core/api/generated/model/taskHistoryDetailDto';
import { Store } from '@ngxs/store';
import { AccountsState } from '../../state/accounts/accounts.state';
import { AccountSessionService } from '../../core/services/account-session.service';

type FilterType = 'ALL' | 'SYNC' | 'DELETION' | 'THUMBNAIL' | 'IPHONE_REPAIR' | 'DB_BACKUP' | 'MERGE_DUPLICATES' | 'ASSIGN_ORPHAN_PHOTOS';
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
          <div class="header-actions">
            <button class="refresh-btn" (click)="refresh()" [disabled]="loading()">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" [class.spinning]="loading()">
                <polyline points="23 4 23 10 17 10"/><polyline points="1 20 1 14 7 14"/>
                <path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"/>
              </svg>
              Refresh
            </button>

            <div class="custom-select" [class.open]="actionsOpen()">
              <button class="cs-trigger" (click)="actionsOpen.set(!actionsOpen())" type="button" [disabled]="repairService.running()">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                  <circle cx="12" cy="12" r="1"/><circle cx="19" cy="12" r="1"/><circle cx="5" cy="12" r="1"/>
                </svg>
                <span class="cs-value">Actions</span>
                <svg class="cs-chevron" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
                  <polyline points="6 9 12 15 18 9"/>
                </svg>
              </button>
              @if (actionsOpen()) {
                <div class="cs-panel actions-panel">
                  <button class="cs-option" type="button" (click)="repairBrokenThumbnails()">
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                      <path d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z"/>
                    </svg>
                    Fix broken thumbnails
                  </button>
                  <button class="cs-option" type="button" (click)="repairIPhonePhotos()" [disabled]="repairIPhoneService.running() || !primaryAccountId()">
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                      <rect x="5" y="2" width="14" height="20" rx="2" ry="2"/><line x1="12" y1="18" x2="12.01" y2="18"/>
                    </svg>
                    Find missing iPhone photos
                  </button>
                  <button class="cs-option" type="button" (click)="backupDatabase()" [disabled]="backupService.running()">
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                      <ellipse cx="12" cy="5" rx="9" ry="3"/><path d="M21 12c0 1.66-4 3-9 3s-9-1.34-9-3"/><path d="M3 5v14c0 1.66 4 3 9 3s9-1.34 9-3V5"/>
                    </svg>
                    Backup bazy danych
                  </button>
                  <button class="cs-option" type="button" (click)="removePending()" [disabled]="deletePendingService.running()">
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                      <polyline points="3 6 5 6 21 6"/><path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/><path d="M10 11v6"/><path d="M14 11v6"/>
                    </svg>
                    Usuń pending (stuck)
                  </button>
                  <button class="cs-option" type="button" (click)="mergeDuplicates()" [disabled]="mergeDuplicatesService.running() || !primaryAccountId()">
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                      <line x1="8" y1="6" x2="21" y2="6"/><line x1="8" y1="12" x2="21" y2="12"/><line x1="8" y1="18" x2="21" y2="18"/>
                      <line x1="3" y1="6" x2="3.01" y2="6"/><line x1="3" y1="12" x2="3.01" y2="12"/><line x1="3" y1="18" x2="3.01" y2="18"/>
                    </svg>
                    Scal duplikaty
                  </button>
                </div>
              }
            </div>

            @if (repairService.running()) {
              @if (repairService.progress(); as p) {
                <span class="action-msg">Repairing... {{ p.checked }}/{{ p.total }}</span>
              } @else {
                <span class="action-msg">Starting repair...</span>
              }
            } @else if (repairDoneMessage()) {
              <span class="action-msg">{{ repairDoneMessage() }}</span>
            }
            @if (repairIPhoneService.running()) {
              @if (repairIPhoneService.progress(); as p) {
                <span class="action-msg">Scanning iPhone... {{ p.checked }}/{{ p.total }}</span>
              } @else {
                <span class="action-msg">Connecting to iPhone...</span>
              }
            } @else if (iphoneRepairDoneMessage()) {
              <span class="action-msg">{{ iphoneRepairDoneMessage() }}</span>
            }
            @if (backupService.running()) {
              <span class="action-msg">Tworzenie backupu...</span>
            } @else if (backupDoneMessage()) {
              <span class="action-msg">{{ backupDoneMessage() }}</span>
            }
            @if (deletePendingService.running()) {
              <span class="action-msg">Usuwanie pending...</span>
            } @else if (deletePendingMessage()) {
              <span class="action-msg">{{ deletePendingMessage() }}</span>
            }
            @if (mergeDuplicatesService.running()) {
              @if (mergeDuplicatesService.progress(); as p) {
                <span class="action-msg">Scalanie... {{ p.checked }}/{{ p.total }}</span>
              } @else {
                <span class="action-msg">Scalanie duplikatów...</span>
              }
            } @else if (mergeDuplicatesDoneMessage()) {
              <span class="action-msg">{{ mergeDuplicatesDoneMessage() }}</span>
            }
          </div>
        </div>
      </div>

      @if (orphanPhotosService.orphanCount() > 0 || orphanPhotosService.running() || orphanDoneMessage()) {
        <div class="available-actions">
          <h2 class="available-actions-title">Dostępne akcje</h2>
          <div class="action-card">
            <div class="action-card-icon">⇲</div>
            <div class="action-card-body">
              <div class="action-card-title">Przypisz zdjęcia bez konta</div>
              <div class="action-card-desc">
                @if (orphanPhotosService.running()) {
                  @if (orphanPhotosService.progress(); as p) {
                    Przypisywanie... {{ p.processed }}/{{ p.total }}
                  } @else {
                    Uruchamianie...
                  }
                } @else if (orphanDoneMessage()) {
                  {{ orphanDoneMessage() }}
                } @else {
                  {{ orphanPhotosService.orphanCount() }} zdjęć na tym dysku nie ma przypisanego konta.
                }
              </div>
            </div>
            <button class="action-run-btn" type="button"
                    (click)="assignOrphanPhotos()"
                    [disabled]="orphanPhotosService.running() || !activeAccountId()">
              {{ orphanPhotosService.running() ? 'Trwa...' : 'Uruchom' }}
            </button>
          </div>
        </div>
      }

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
  private store = inject(Store);
  private accountSession = inject(AccountSessionService);
  readonly repairService = inject(RepairThumbnailsService);
  readonly repairIPhoneService = inject(RepairIPhoneService);
  readonly backupService = inject(BackupDatabaseService);
  readonly deletePendingService = inject(DeletePendingService);
  readonly mergeDuplicatesService = inject(MergeDuplicatesService);
  readonly orphanPhotosService = inject(OrphanPhotosService);
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

  actionsOpen = signal(false);

  readonly repairDoneMessage = computed(() => {
    const p = this.repairService.progress();
    if (!p?.done) return null;
    return `Fixed ${p.fixed} broken thumbnail(s) (checked ${p.total}).`;
  });

  readonly iphoneRepairDoneMessage = computed(() => {
    const p = this.repairIPhoneService.progress();
    if (!p?.done) return null;
    if (p.newPending === 0 && p.missingFixed === 0) return `iPhone scan done — no missing files found (${p.total} checked).`;
    return `Found ${p.newPending} new + ${p.missingFixed} missing files — run iPhone sync to download.`;
  });

  readonly backupDoneMessage = computed(() => {
    const p = this.backupService.progress();
    if (!p?.done) return null;
    if (p.error) return `Backup nie powiódł się: ${p.error}`;
    return `Backup zapisany: ${p.backupPath}`;
  });

  readonly mergeDuplicatesDoneMessage = computed(() => {
    const p = this.mergeDuplicatesService.progress();
    if (!p?.done) return null;
    if (p.merged === 0) return 'Brak duplikatów do scalenia.';
    return `Scalono ${p.merged} grup, usunięto ${p.deleted} rekordów.`;
  });

  readonly deletePendingMessage = computed(() => {
    const err = this.deletePendingService.error();
    if (err) return `Błąd: ${err}`;
    const n = this.deletePendingService.lastDeleted();
    if (n === null) return null;
    return n === 0 ? 'Brak pending do usunięcia.' : `Usunięto ${n} pending rekordów.`;
  });

  readonly primaryAccountId = computed<string | null>(() => {
    const accounts = this.store.selectSnapshot(AccountsState.accounts);
    return accounts.find(a => a.hasActiveSession)?.id ?? accounts[0]?.id ?? null;
  });

  readonly activeAccountId = computed<string | null>(() =>
    this.accountSession.activeAccountId() ?? this.primaryAccountId());

  readonly orphanDoneMessage = computed(() => {
    const p = this.orphanPhotosService.progress();
    if (!p?.done) return null;
    if (p.assigned === 0) return 'Brak zdjęć bez konta do przypisania.';
    return `Przypisano ${p.assigned} zdjęć do aktywnego konta.`;
  });

  typeOptions: { value: FilterType; label: string }[] = [
    { value: 'ALL', label: 'All' },
    { value: 'SYNC', label: 'Sync' },
    { value: 'DELETION', label: 'Deletion' },
    { value: 'THUMBNAIL', label: 'Thumbnails' },
    { value: 'IPHONE_REPAIR', label: 'iPhone Repair' },
    { value: 'DB_BACKUP', label: 'Backup bazy' },
    { value: 'MERGE_DUPLICATES', label: 'Scalanie duplikatów' },
    { value: 'ASSIGN_ORPHAN_PHOTOS', label: 'Przypisanie zdjęć bez konta' },
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
    this.refreshOrphanCount();
    this.pollInterval = setInterval(() => this.refresh(), 10000);
  }

  private refreshOrphanCount(): void {
    const accountId = this.activeAccountId();
    if (accountId) this.orphanPhotosService.refreshCount(accountId);
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
      case 'IPHONE_REPAIR': return '⚙';
      case 'DB_BACKUP': return '▦';
      case 'MERGE_DUPLICATES': return '⊕';
      case 'ASSIGN_ORPHAN_PHOTOS': return '⇲';
      default: return '·';
    }
  }

  taskLabel(task: TaskHistoryDto | TaskHistoryDetailDto): string {
    switch (task.type) {
      case 'SYNC': return `Sync (${task.provider ?? 'unknown'})`;
      case 'DELETION': return `Delete photos (${task.provider ?? 'unknown'})`;
      case 'THUMBNAIL': return 'Generate thumbnails';
      case 'IPHONE_REPAIR': return 'Find missing iPhone photos';
      case 'DB_BACKUP': return 'Backup bazy danych';
      case 'MERGE_DUPLICATES': return 'Scalanie duplikatów';
      case 'ASSIGN_ORPHAN_PHOTOS': return 'Przypisanie zdjęć bez konta';
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

  repairBrokenThumbnails(): void {
    this.actionsOpen.set(false);
    this.repairService.startJob().then(() => this.refresh());
  }

  repairIPhonePhotos(): void {
    const accountId = this.primaryAccountId();
    if (!accountId) return;
    this.actionsOpen.set(false);
    this.repairIPhoneService.startJob(accountId).then(() => this.refresh());
  }

  backupDatabase(): void {
    this.actionsOpen.set(false);
    this.backupService.startJob().then(() => this.refresh());
  }

  removePending(): void {
    this.actionsOpen.set(false);
    this.deletePendingService.run().then(() => this.refresh());
  }

  mergeDuplicates(): void {
    const accountId = this.primaryAccountId();
    if (!accountId) return;
    this.actionsOpen.set(false);
    this.mergeDuplicatesService.startJob(accountId).then(() => this.refresh());
  }

  assignOrphanPhotos(): void {
    const accountId = this.activeAccountId();
    if (!accountId) return;
    this.orphanPhotosService.startJob(accountId).then(() => this.refresh());
  }

  private load(page: number, silent = false): void {
    if (!silent) this.loading.set(true);
    const typeVal = this.typeFilter();
    const statusVal = this.statusFilter();
    const params: Record<string, string | number> = { page, size: 25 };
    if (typeVal !== 'ALL') params['type'] = typeVal;
    if (statusVal !== 'ALL') params['status'] = statusVal;
    const accountId = this.accountSession.activeAccountId();
    if (accountId) params['accountId'] = accountId;

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
