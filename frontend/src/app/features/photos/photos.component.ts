import { Component, OnInit, OnDestroy, inject, signal, computed, effect, ViewChild, DestroyRef } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { HttpClient } from '@angular/common/http';
import { Store } from '@ngxs/store';
import { PhotosService } from '../../core/api/generated/photos/photos.service';
import { PhotoResponse } from '../../core/api/generated/model/photoResponse';
import { PhotosToolbarComponent, SourceFilter } from './photos-toolbar/photos-toolbar.component';
import { PhotoTimelineComponent } from './photo-timeline/photo-timeline.component';
import { PhotoGroup } from './photo-timeline/photo-timeline.component';
import { BatchActionsBarComponent } from './batch-actions-bar/batch-actions-bar.component';
import { PhotoDetailModalComponent } from './photo-detail-modal/photo-detail-modal.component';
import { MissingThumbnailsBannerComponent } from './missing-thumbnails-banner/missing-thumbnails-banner.component';
import { MonthsNavComponent } from './photos-months-nav/months-nav.component';
import { SyncService } from '../../core/services/sync.service';
import { DiskIndexingService } from '../../core/services/disk-indexing.service';
import { AppContextService } from '../../core/services/app-context.service';
import { ThumbnailSpriteService } from '../../core/services/thumbnail-sprite.service';
import { ThumbnailJobStateService } from '../../core/services/thumbnail-job-state.service';
import { SyncProgressEvent } from '../../core/models/sync-progress.model';
import { PhotosState } from '../../state/photos/photos.state';
import { ClearDeletedPhotos, GroupingMode, LoadPhotos, LoadMorePhotos, LoadMonthsSummary, SetActiveMonth } from '../../state/photos/photos.actions';
import { StartDeletionJob } from '../../state/jobs/jobs.actions';
import { Subscription } from 'rxjs';
import { filter } from 'rxjs/operators';

type Granularity = 'year' | 'month';

@Component({
  selector: 'app-photos',
  standalone: true,
  imports: [
    PhotosToolbarComponent,
    PhotoTimelineComponent,
    BatchActionsBarComponent,
    PhotoDetailModalComponent,
    MissingThumbnailsBannerComponent,
    MonthsNavComponent,
  ],
  templateUrl: './photos.component.html',
  styleUrl: './photos.component.scss'
})
export class PhotosComponent implements OnInit, OnDestroy {
  @ViewChild(PhotoTimelineComponent) private timeline!: PhotoTimelineComponent;
  @ViewChild(MissingThumbnailsBannerComponent) private banner?: MissingThumbnailsBannerComponent;

  private store = inject(Store);
  private photosService = inject(PhotosService);
  private http = inject(HttpClient);
  private syncService = inject(SyncService);
  private diskIndexingService = inject(DiskIndexingService);
  private appContextService = inject(AppContextService);
  private thumbnailSpriteService = inject(ThumbnailSpriteService);
  private thumbnailJobState = inject(ThumbnailJobStateService);
  private destroyRef = inject(DestroyRef);

  storageDeviceId = computed(() => this.appContextService.context()?.storageDeviceId ?? '');
  granularity = signal<Granularity>('year');
  sourceFilter = signal<SourceFilter>('all');
  tocCollapsed = signal<boolean>(localStorage.getItem('cloudsync-months-collapsed') === '1');

  toggleToc(): void {
    const next = !this.tocCollapsed();
    this.tocCollapsed.set(next);
    localStorage.setItem('cloudsync-months-collapsed', next ? '1' : '0');
  }

  allPhotos = this.store.selectSignal(PhotosState.photos);
  loading = this.store.selectSignal(PhotosState.loading);
  loadingMore = this.store.selectSignal(PhotosState.loadingMore);
  hasMore = this.store.selectSignal(PhotosState.hasMore);
  loadError = this.store.selectSignal(PhotosState.error);
  monthsSummary = this.store.selectSignal(PhotosState.monthsSummary);
  activeMonth = this.store.selectSignal(PhotosState.activeMonth);
  showDetails = this.store.selectSignal(PhotosState.showDetails);
  columnsPerRow = this.store.selectSignal(PhotosState.columnsPerRow);
  groupingMode = this.store.selectSignal(PhotosState.groupingMode);

  filteredPhotos = computed(() => {
    const f = this.sourceFilter();
    const photos = this.allPhotos();
    if (f === 'icloud') return photos.filter(p => p.existsOnIcloud && !p.existsOnIphone);
    if (f === 'iphone') return photos.filter(p => p.existsOnIphone && !p.existsOnIcloud);
    return photos;
  });

  groups = computed<PhotoGroup[]>(() => this.buildGroupsFromPhotos(this.filteredPhotos(), this.granularity(), this.groupingMode()));

  private orderedPhotos = computed<PhotoResponse[]>(() =>
    this.groups().flatMap(g => g.photos)
  );

  selectedIds = signal(new Set<string>());

  selectedSize = computed(() => {
    const ids = this.selectedIds();
    return this.allPhotos()
      .filter(p => ids.has(p.id))
      .reduce((sum, p) => sum + (p.fileSize ?? 0), 0);
  });

  selectedMissingThumbnailIds = computed(() =>
    this.allPhotos()
      .filter(p => this.selectedIds().has(p.id) && !p.thumbnailPath)
      .map(p => p.id)
  );

  deletedIds = this.store.selectSignal(PhotosState.deletedIds);
  deletedCount = computed(() => this.deletedIds().length);

  clearRemoved(): void {
    this.store.dispatch(new ClearDeletedPhotos());
  }

  private currentDetailIndex = computed(() =>
    this.orderedPhotos().findIndex(p => p.id === this.detailPhoto()?.id)
  );

  hasPrevPhoto = computed(() => this.currentDetailIndex() > 0);
  hasNextPhoto = computed(() => {
    const idx = this.currentDetailIndex();
    return idx >= 0 && idx < this.orderedPhotos().length - 1;
  });

  canDeleteSelected = computed(() => {
    const selectedPhotos = this.allPhotos().filter(p => this.selectedIds().has(p.id));
    return selectedPhotos.length > 0 && selectedPhotos.every(p => p.syncedToDisk);
  });

  syncing = signal(false);
  syncProgress = signal<SyncProgressEvent | null>(null);
  operationStatus = signal<string | null>(null);

  thumbnailSlots = this.thumbnailSpriteService.slots;
  detailPhoto = signal<PhotoResponse | null>(null);
  detailImageUrl = signal<string | null>(null);

  private syncSub: Subscription | null = null;
  private statusTimer: ReturnType<typeof setTimeout> | null = null;
  private detailBlobUrl: string | null = null;
  private previousDeviceId: string | null = null;

  constructor() {
    effect(() => {
      const deviceId = this.storageDeviceId();
      if (this.previousDeviceId !== deviceId) {
        this.resetThumbnailState();
        this.selectedIds.set(new Set());
        if (deviceId) {
          this.store.dispatch(new LoadPhotos(deviceId, this.activeMonth() ?? undefined));
          this.store.dispatch(new LoadMonthsSummary(deviceId));
        }
        this.previousDeviceId = deviceId;
      }
    });

    effect(() => {
      this.schedulePrefetch(this.filteredPhotos(), this.columnsPerRow());
    });

    effect(() => {
      const n = this.thumbnailJobState.thumbnailsDone();
      if (n > 0) {
        this.reloadCurrent();
        this.timeline?.refreshObserver();
      }
    });
  }

  ngOnInit(): void {
    this.syncSub = this.syncService.syncProgress$.subscribe(event => {
      this.syncProgress.set(event);
      if (event?.phase === 'DONE') {
        this.syncing.set(false);
        this.reloadCurrent();
        if (event.thumbnailJobId) {
          this.thumbnailJobState.connectToJob(event.thumbnailJobId);
        }
      } else if (event?.phase === 'ERROR') {
        this.syncing.set(false);
      }
    });

    this.diskIndexingService.progress$
      .pipe(
        filter(p => p?.phase === 'DONE'),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe(() => this.reloadCurrent());
  }

  ngOnDestroy(): void {
    if (this.detailBlobUrl) URL.revokeObjectURL(this.detailBlobUrl);
    this.syncSub?.unsubscribe();
    this.syncService.closeEvents();
    if (this.statusTimer !== null) clearTimeout(this.statusTimer);
  }

  onGranularityChanged(g: Granularity): void { this.granularity.set(g); }

  onSourceFilterChanged(f: SourceFilter): void {
    this.sourceFilter.set(f);
    this.selectedIds.set(new Set());
  }

  onSyncRequested(): void {
    if (this.syncing()) return;
    this.syncing.set(true);
    this.syncService.reset();
    this.syncService.startSync(this.storageDeviceId()).subscribe({
      error: (err) => {
        this.syncing.set(false);
        alert('Sync failed: ' + (err?.message ?? 'Unknown error'));
      }
    });
  }

  onMonthSelected(yearMonth: string | null): void {
    this.store.dispatch(new SetActiveMonth(yearMonth));
    this.resetThumbnailState();
    this.selectedIds.set(new Set());
  }

  onSelectionChanged(ids: Set<string>): void { this.selectedIds.set(ids); }

  onLoadMoreRequested(): void {
    if (this.loadingMore() || !this.hasMore()) return;
    this.store.dispatch(new LoadMorePhotos());
  }

  onPhotoClicked(photo: PhotoResponse): void {
    this.detailPhoto.set(photo);
    this.detailImageUrl.set(null);
    if (this.detailBlobUrl) {
      URL.revokeObjectURL(this.detailBlobUrl);
      this.detailBlobUrl = null;
    }
    this.http.get(`/api/photos/${photo.id}/full`, { responseType: 'blob' }).subscribe({
      next: (blob) => {
        this.detailBlobUrl = URL.createObjectURL(blob);
        this.detailImageUrl.set(this.detailBlobUrl);
      },
      error: () => {
        this.http.get(`/api/photos/${photo.id}/thumbnail`, { responseType: 'blob' }).subscribe({
          next: (blob) => {
            this.detailBlobUrl = URL.createObjectURL(blob);
            this.detailImageUrl.set(this.detailBlobUrl);
          }
        });
      }
    });
  }

  onDetailClosed(): void {
    this.detailPhoto.set(null);
    this.detailImageUrl.set(null);
    if (this.detailBlobUrl) {
      URL.revokeObjectURL(this.detailBlobUrl);
      this.detailBlobUrl = null;
    }
  }

  onPrevPhoto(): void {
    const idx = this.currentDetailIndex();
    if (idx > 0) this.onPhotoClicked(this.orderedPhotos()[idx - 1]);
  }

  onNextPhoto(): void {
    const idx = this.currentDetailIndex();
    if (idx >= 0 && idx < this.orderedPhotos().length - 1) this.onPhotoClicked(this.orderedPhotos()[idx + 1]);
  }

  onDeleteFromICloud(): void {
    if (!this.canDeleteSelected()) return;
    const count = this.selectedIds().size;
    if (!confirm(`Delete ${count} photo(s) from iCloud? This cannot be undone.`)) return;

    const photoIds = Array.from(this.selectedIds());
    const accountId = this.firstAccountId(photoIds);
    this.selectedIds.set(new Set());
    this.store.dispatch(new StartDeletionJob({ accountId, photoIds, provider: 'ICLOUD' }));
  }

  onDeleteFromIPhone(): void {
    if (!this.canDeleteSelected()) return;
    const count = this.selectedIds().size;
    if (!confirm(`Delete ${count} photo(s) from iPhone? This cannot be undone.`)) return;

    const photoIds = Array.from(this.selectedIds());
    const accountId = this.firstAccountId(photoIds);
    this.selectedIds.set(new Set());
    this.store.dispatch(new StartDeletionJob({ accountId, photoIds, provider: 'IPHONE' }));
  }

  onGenerateThumbnailsForSelected(): void {
    this.banner?.generateForSelected();
  }

  onDetailDeleteFromICloud(photo: PhotoResponse): void {
    if (!photo.syncedToDisk) return;
    if (!confirm(`Delete "${photo.filename}" from iCloud?`)) return;
    this.store.dispatch(new StartDeletionJob({ accountId: photo.accountId, photoIds: [photo.id], provider: 'ICLOUD' }));
    this.onDetailClosed();
  }

  onDetailDeleteFromIPhone(photo: PhotoResponse): void {
    if (!photo.syncedToDisk) return;
    if (!confirm(`Delete "${photo.filename}" from iPhone?`)) return;
    this.store.dispatch(new StartDeletionJob({ accountId: photo.accountId, photoIds: [photo.id], provider: 'IPHONE' }));
    this.onDetailClosed();
  }

  onThumbnailsGenerated(): void {
    this.timeline.refreshObserver();
  }

  onThumbnailNeeded(photoId: string): void {
    const photo = this.allPhotos().find(p => p.id === photoId);
    if (!photo?.thumbnailPath) return;
    this.thumbnailSpriteService.request(photoId);
  }

  onScrolled(): void {}

  clearSelection(): void { this.selectedIds.set(new Set()); }

  private reloadCurrent(): void {
    const deviceId = this.storageDeviceId();
    if (!deviceId) return;
    this.store.dispatch(new LoadPhotos(deviceId, this.activeMonth() ?? undefined));
    this.store.dispatch(new LoadMonthsSummary(deviceId));
  }

  private firstAccountId(photoIds: string[]): string {
    return this.allPhotos().find(p => photoIds.includes(p.id))?.accountId ?? '';
  }

  private setOperationStatus(msg: string, autoClearMs: number): void {
    if (this.statusTimer !== null) clearTimeout(this.statusTimer);
    this.operationStatus.set(msg);
    if (autoClearMs > 0) {
      this.statusTimer = setTimeout(() => this.operationStatus.set(null), autoClearMs);
    }
  }

  private schedulePrefetch(photos: PhotoResponse[], cols: number): void {
    if (photos.length === 0) return;
    const ids = photos.filter(p => p.thumbnailPath).map(p => p.id);
    this.thumbnailSpriteService.prefetchPages(ids, cols * 8, 4);
  }

  private resetThumbnailState(): void {
    this.thumbnailSpriteService.reset();
  }

  private readonly DAY_NAMES_PL = ['niedziela', 'poniedziałek', 'wtorek', 'środa', 'czwartek', 'piątek', 'sobota'];
  private readonly HOUR_SLOTS: ReadonlyArray<[number, number]> = [[0, 4], [4, 8], [8, 12], [12, 16], [16, 20], [20, 24]];

  private dayKey(date: Date): string {
    return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
  }

  private dayLabel(date: Date): string {
    const d = String(date.getDate()).padStart(2, '0');
    const m = String(date.getMonth() + 1).padStart(2, '0');
    return `${d}-${m}-${date.getFullYear()} (${this.DAY_NAMES_PL[date.getDay()]})`;
  }

  private hourSlotLabel(slotIdx: number): string {
    const [start, end] = this.HOUR_SLOTS[slotIdx];
    return `${String(start).padStart(2, '0')}:00 – ${String(end).padStart(2, '0')}:00`;
  }

  private parentKey(date: Date, granularity: Granularity): string {
    return granularity === 'year'
      ? String(date.getFullYear())
      : `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`;
  }

  private parentLabel(parentKey: string, granularity: Granularity, sampleDate: Date): string {
    return granularity === 'year'
      ? parentKey
      : sampleDate.toLocaleString('default', { month: 'long', year: 'numeric' });
  }

  private sortPhotosByDate(photos: PhotoResponse[]): PhotoResponse[] {
    return photos.sort((a, b) => new Date(b.createdDate).getTime() - new Date(a.createdDate).getTime());
  }

  private buildGroupsFromPhotos(photos: PhotoResponse[], granularity: Granularity, groupingMode: GroupingMode): PhotoGroup[] {
    if (groupingMode === 'day' || groupingMode === 'hour') {
      const parentMap = new Map<string, PhotoResponse[]>();
      for (const photo of photos) {
        const key = this.parentKey(new Date(photo.createdDate), granularity);
        if (!parentMap.has(key)) parentMap.set(key, []);
        parentMap.get(key)!.push(photo);
      }

      const result: PhotoGroup[] = [];
      for (const [pKey, parentPhotos] of Array.from(parentMap.entries()).sort((a, b) => b[0].localeCompare(a[0]))) {
        result.push({
          key: pKey,
          label: this.parentLabel(pKey, granularity, new Date(parentPhotos[0].createdDate)),
          photos: [],
          level: 'primary',
          selectionPhotos: parentPhotos,
        });

        if (groupingMode === 'day') {
          const dayMap = new Map<string, PhotoResponse[]>();
          for (const photo of parentPhotos) {
            const key = this.dayKey(new Date(photo.createdDate));
            if (!dayMap.has(key)) dayMap.set(key, []);
            dayMap.get(key)!.push(photo);
          }
          for (const [dKey, dayPhotos] of Array.from(dayMap.entries()).sort((a, b) => b[0].localeCompare(a[0]))) {
            result.push({
              key: `${pKey}/${dKey}`,
              label: this.dayLabel(new Date(dayPhotos[0].createdDate)),
              photos: this.sortPhotosByDate(dayPhotos),
              level: 'secondary',
            });
          }
        } else {
          const slotMap = new Map<string, PhotoResponse[]>();
          for (const photo of parentPhotos) {
            const date = new Date(photo.createdDate);
            const key = `${this.dayKey(date)}-${Math.floor(date.getHours() / 4)}`;
            if (!slotMap.has(key)) slotMap.set(key, []);
            slotMap.get(key)!.push(photo);
          }
          for (const [sKey, slotPhotos] of Array.from(slotMap.entries()).sort((a, b) => b[0].localeCompare(a[0]))) {
            const date = new Date(slotPhotos[0].createdDate);
            const slotIdx = Math.floor(date.getHours() / 4);
            result.push({
              key: `${pKey}/${sKey}`,
              label: `${this.dayLabel(date)} · ${this.hourSlotLabel(slotIdx)}`,
              photos: this.sortPhotosByDate(slotPhotos),
              level: 'secondary',
            });
          }
        }
      }
      return result;
    }

    const groupMap = new Map<string, PhotoResponse[]>();
    for (const photo of photos) {
      const key = this.parentKey(new Date(photo.createdDate), granularity);
      if (!groupMap.has(key)) groupMap.set(key, []);
      groupMap.get(key)!.push(photo);
    }
    return Array.from(groupMap.entries())
      .sort((a, b) => b[0].localeCompare(a[0]))
      .map(([key, groupPhotos]) => ({
        key,
        label: this.parentLabel(key, granularity, new Date(groupPhotos[0].createdDate)),
        photos: this.sortPhotosByDate(groupPhotos),
      }));
  }

}
