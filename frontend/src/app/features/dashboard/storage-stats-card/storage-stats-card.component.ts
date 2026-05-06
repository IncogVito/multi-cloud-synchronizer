import { Component, OnInit, OnDestroy, DestroyRef, inject, signal, computed, output } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DecimalPipe } from '@angular/common';
import { filter } from 'rxjs/operators';
import { StatsService } from '../../../core/api/generated/stats/stats.service';
import { AppContextService } from '../../../core/services/app-context.service';
import { SyncService } from '../../../core/services/sync.service';
import { DiskIndexingService } from '../../../core/services/disk-indexing.service';
import { StatsResponse } from '../../../core/api/generated/model/statsResponse';

@Component({
  selector: 'app-storage-stats-card',
  standalone: true,
  imports: [DecimalPipe],
  templateUrl: './storage-stats-card.component.html',
  styleUrl: './storage-stats-card.component.scss'
})
export class StorageStatsCardComponent implements OnInit, OnDestroy {
  private statsApi = inject(StatsService);
  appContext = inject(AppContextService);
  private syncService = inject(SyncService);
  private diskIndexingService = inject(DiskIndexingService);
  private destroyRef = inject(DestroyRef);

  reindexRequested = output<void>();

  stats = signal<StatsResponse | null>(null);
  loading = signal(true);
  reindexing = signal(false);

  diskUsedPercent = computed(() => {
    const s = this.stats();
    if (!s?.diskCapacityBytes || !s.diskSizeBytes) return 0;
    return Math.min(100, Math.round((s.diskSizeBytes / s.diskCapacityBytes) * 100));
  });

  ngOnInit(): void {
    this.load();
    this.syncService.syncProgress$
      .pipe(filter(p => p?.phase === 'DONE'), takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.load());
    this.diskIndexingService.progress$
      .pipe(filter(p => p?.phase === 'DONE'), takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.load());
    this.diskIndexingService.subscribeToEvents();
  }

  ngOnDestroy(): void {
    this.diskIndexingService.closeEvents();
  }

  private load(): void {
    const ctx = this.appContext.context();
    if (!ctx) return;

    this.loading.set(true);
    this.statsApi.getOverview({ storageDeviceId: ctx.storageDeviceId }).subscribe({
      next: s => { this.stats.set(s); this.loading.set(false); },
      error: () => this.loading.set(false)
    });
  }

  startReindex(): void {
    if (this.reindexing()) return;
    this.reindexing.set(true);
    this.diskIndexingService.start().subscribe({
      next: () => {
        this.reindexing.set(false);
        this.reindexRequested.emit();
      },
      error: () => {
        this.reindexing.set(false);
      }
    });
  }

  formatBytes(bytes: number | null | undefined): string {
    if (bytes == null || bytes === 0) return '—';
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    if (bytes < 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
    return `${(bytes / (1024 * 1024 * 1024)).toFixed(2)} GB`;
  }

  formatDate(iso: string | null | undefined): string {
    if (!iso) return 'nigdy';
    const d = new Date(iso);
    const now = new Date();
    const diffMs = now.getTime() - d.getTime();
    const diffDays = Math.floor(diffMs / (1000 * 60 * 60 * 24));
    if (diffDays === 0) return 'dzisiaj';
    if (diffDays === 1) return 'wczoraj';
    if (diffDays < 7) return `${diffDays} dni temu`;
    return d.toLocaleDateString('pl-PL', { day: 'numeric', month: 'short', year: 'numeric' });
  }
}
