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
import { SyncProgressComponent } from './sync-progress/sync-progress.component';
import { MissingThumbnailsBannerComponent } from './missing-thumbnails-banner/missing-thumbnails-banner.component';
import { MonthsNavComponent } from './photos-months-nav/months-nav.component';
import { SyncService } from '../../core/services/sync.service';
import { DiskIndexingService } from '../../core/services/disk-indexing.service';
import { AppContextService } from '../../core/services/app-context.service';
import { SyncProgressEvent } from '../../core/models/sync-progress.model';
import { PhotosState } from '../../state/photos/photos.state';
import { LoadPhotos, LoadMorePhotos, LoadMonthsSummary, SetActiveMonth } from '../../state/photos/photos.actions';
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
    SyncProgressComponent,
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

  filteredPhotos = computed(() => {
    const f = this.sourceFilter();
    const photos = this.allPhotos();
    if (f === 'icloud') return photos.filter(p => p.existsOnIcloud && !p.existsOnIphone);
    if (f === 'iphone') return photos.filter(p => p.existsOnIphone && !p.existsOnIcloud);
    return photos;
  });

  groups = computed<PhotoGroup[]>(() => this.buildGroupsFromPhotos(this.filteredPhotos(), this.granularity()));

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

  canDeleteSelected = computed(() => {
    const selectedPhotos = this.allPhotos().filter(p => this.selectedIds().has(p.id));
    return selectedPhotos.length > 0 && selectedPhotos.every(p => p.syncedToDisk);
  });

  syncing = signal(false);
  syncProgress = signal<SyncProgressEvent | null>(null);
  operationStatus = signal<string | null>(null);

  thumbnailUrls = signal(new Map<string, string>());
  detailPhoto = signal<PhotoResponse | null>(null);
  detailImageUrl = signal<string | null>(null);

  private syncSub: Subscription | null = null;
  private thumbnailBlobUrls: string[] = [];
  private thumbnailQueue: PhotoResponse[] = [];
  private thumbnailLoadingActive = false;
  private isScrolling = false;
  private scrollDebounceTimer: ReturnType<typeof setTimeout> | null = null;
  private thumbnailDelayTimer: ReturnType<typeof setTimeout> | null = null;
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
          this.store.dispatch(new LoadPhotos(deviceId));
          this.store.dispatch(new LoadMonthsSummary(deviceId));
        }
        this.previousDeviceId = deviceId;
      }
    });
  }

  ngOnInit(): void {
    this.syncSub = this.syncService.syncProgress$.subscribe(event => {
      this.syncProgress.set(event);
      if (event?.phase === 'DONE') {
        this.syncing.set(false);
        this.reloadCurrent();
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
    for (const url of this.thumbnailBlobUrls) URL.revokeObjectURL(url);
    if (this.detailBlobUrl) URL.revokeObjectURL(this.detailBlobUrl);
    this.syncSub?.unsubscribe();
    this.syncService.closeEvents();
    if (this.scrollDebounceTimer !== null) clearTimeout(this.scrollDebounceTimer);
    if (this.thumbnailDelayTimer !== null) clearTimeout(this.thumbnailDelayTimer);
    if (this.statusTimer !== null) clearTimeout(this.statusTimer);
    this.thumbnailQueue = [];
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
        const thumbUrl = this.thumbnailUrls().get(photo.id);
        if (thumbUrl) this.detailImageUrl.set(thumbUrl);
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

  onDeleteFromICloud(): void {
    if (!this.canDeleteSelected()) return;
    const count = this.selectedIds().size;
    if (!confirm(`Delete ${count} photo(s) from iCloud? This cannot be undone.`)) return;

    const photoIds = Array.from(this.selectedIds());
    const accountId = this.firstAccountId(photoIds);
    this.selectedIds.set(new Set());
    this.setOperationStatus(`Deleting ${count} photo(s) from iCloud...`, 0);

    this.photosService.deleteFromICloud({ photoIds }, { accountId }).subscribe({
      next: () => {
        this.setOperationStatus(`Deleted ${count} photo(s) from iCloud`, 5000);
        this.reloadCurrent();
      },
      error: (err) => this.setOperationStatus(`Delete from iCloud failed: ${err?.message ?? 'Unknown error'}`, 8000)
    });
  }

  onDeleteFromIPhone(): void {
    if (!this.canDeleteSelected()) return;
    const count = this.selectedIds().size;
    if (!confirm(`Delete ${count} photo(s) from iPhone? This cannot be undone.`)) return;

    const photoIds = Array.from(this.selectedIds());
    const accountId = this.firstAccountId(photoIds);
    this.selectedIds.set(new Set());
    this.setOperationStatus(`Deleting ${count} photo(s) from iPhone...`, 0);

    this.photosService.deleteFromIPhone({ photoIds }, { accountId }).subscribe({
      next: () => {
        this.setOperationStatus(`Deleted ${count} photo(s) from iPhone`, 5000);
        this.reloadCurrent();
      },
      error: (err) => this.setOperationStatus(`Delete from iPhone failed: ${err?.message ?? 'Unknown error'}`, 8000)
    });
  }

  onGenerateThumbnailsForSelected(): void {
    this.banner?.generateForSelected();
  }

  onDetailDeleteFromICloud(photo: PhotoResponse): void {
    if (!photo.syncedToDisk) return;
    if (!confirm(`Delete "${photo.filename}" from iCloud?`)) return;
    this.photosService.deleteFromICloud({ photoIds: [photo.id] }, { accountId: photo.accountId }).subscribe({
      next: () => {
        this.detailPhoto.update(p => p ? { ...p, existsOnIcloud: false } : p);
        this.reloadCurrent();
      },
      error: (err) => alert('Failed: ' + (err?.message ?? 'Unknown error'))
    });
  }

  onDetailDeleteFromIPhone(photo: PhotoResponse): void {
    if (!photo.syncedToDisk) return;
    if (!confirm(`Delete "${photo.filename}" from iPhone?`)) return;
    this.photosService.deleteFromIPhone({ photoIds: [photo.id] }, { accountId: photo.accountId }).subscribe({
      next: () => {
        this.detailPhoto.update(p => p ? { ...p, existsOnIphone: false } : p);
        this.reloadCurrent();
      },
      error: (err) => alert('Failed: ' + (err?.message ?? 'Unknown error'))
    });
  }

  onThumbnailsGenerated(): void {
    this.timeline.refreshObserver();
  }

  onThumbnailNeeded(photoId: string): void {
    if (this.thumbnailUrls().has(photoId)) return;
    const photo = this.allPhotos().find(p => p.id === photoId);
    if (!photo?.thumbnailPath) return;

    const idx = this.thumbnailQueue.findIndex(p => p.id === photoId);
    if (idx > 0) {
      const [p] = this.thumbnailQueue.splice(idx, 1);
      this.thumbnailQueue.unshift(p);
    } else if (idx === -1) {
      this.thumbnailQueue.unshift(photo);
    }

    if (!this.thumbnailLoadingActive && !this.isScrolling) {
      this.processNextThumbnail();
    }
  }

  onScrolled(): void {
    this.isScrolling = true;
    if (this.scrollDebounceTimer !== null) clearTimeout(this.scrollDebounceTimer);
    this.scrollDebounceTimer = setTimeout(() => {
      this.isScrolling = false;
      this.scrollDebounceTimer = null;
      if (!this.thumbnailLoadingActive) this.processNextThumbnail();
    }, 250);
  }

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

  private resetThumbnailState(): void {
    this.thumbnailQueue = [];
    this.thumbnailLoadingActive = false;
    for (const url of this.thumbnailBlobUrls) URL.revokeObjectURL(url);
    this.thumbnailBlobUrls = [];
    this.thumbnailUrls.set(new Map());
  }

  private buildGroupsFromPhotos(photos: PhotoResponse[], granularity: Granularity): PhotoGroup[] {
    const groupMap = new Map<string, PhotoResponse[]>();
    for (const photo of photos) {
      const date = new Date(photo.createdDate);
      const key = granularity === 'year'
        ? String(date.getFullYear())
        : `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`;
      if (!groupMap.has(key)) groupMap.set(key, []);
      groupMap.get(key)!.push(photo);
    }
    return Array.from(groupMap.entries())
      .sort((a, b) => b[0].localeCompare(a[0]))
      .map(([key, groupPhotos]) => ({
        key,
        label: granularity === 'year' ? key : groupPhotos[0]
          ? new Date(groupPhotos[0].createdDate).toLocaleString('default', { month: 'long' })
          : key,
        photos: groupPhotos.sort((a, b) => new Date(b.createdDate).getTime() - new Date(a.createdDate).getTime())
      }));
  }

  private processNextThumbnail(): void {
    if (this.thumbnailQueue.length === 0) { this.thumbnailLoadingActive = false; return; }
    if (this.isScrolling) { this.thumbnailLoadingActive = false; return; }

    this.thumbnailLoadingActive = true;
    const photo = this.thumbnailQueue.shift()!;

    if (this.thumbnailUrls().has(photo.id)) { this.processNextThumbnail(); return; }

    this.http.get(`/api/photos/${photo.id}/thumbnail`, { responseType: 'blob' }).subscribe({
      next: (blob) => {
        const url = URL.createObjectURL(blob);
        this.thumbnailBlobUrls.push(url);
        this.thumbnailUrls.update(urls => new Map(urls).set(photo.id, url));
        this.thumbnailDelayTimer = setTimeout(() => this.processNextThumbnail(), 40);
      },
      error: () => { this.thumbnailDelayTimer = setTimeout(() => this.processNextThumbnail(), 40); }
    });
  }
}
