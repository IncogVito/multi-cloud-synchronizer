import { Component, OnInit, OnDestroy, inject, signal, computed, effect, ViewChild } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Store } from '@ngxs/store';
import { PhotosService } from '../../core/api/generated/photos/photos.service';
import { PhotoResponse } from '../../core/api/generated/model/photoResponse';
import { PhotosToolbarComponent } from './photos-toolbar/photos-toolbar.component';
import { PhotoTimelineComponent } from './photo-timeline/photo-timeline.component';
import { PhotoGroup } from './photo-timeline/photo-timeline.component';
import { BatchActionsBarComponent } from './batch-actions-bar/batch-actions-bar.component';
import { PhotoDetailModalComponent } from './photo-detail-modal/photo-detail-modal.component';
import { SyncProgressComponent } from './sync-progress/sync-progress.component';
import { MissingThumbnailsBannerComponent } from './missing-thumbnails-banner/missing-thumbnails-banner.component';
import { SyncService } from '../../core/services/sync.service';
import { AppContextService } from '../../core/services/app-context.service';
import { SyncProgressEvent } from '../../core/models/sync-progress.model';
import { PhotosState } from '../../state/photos/photos.state';
import { LoadPhotos, LoadMorePhotos, LoadMonthsSummary, SetActiveMonth } from '../../state/photos/photos.actions';
import { MonthSummaryResponse } from '../../core/api/generated/model/monthSummaryResponse';
import { Subscription } from 'rxjs';

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
    MissingThumbnailsBannerComponent
  ],
  templateUrl: './photos.component.html',
  styleUrl: './photos.component.scss'
})
export class PhotosComponent implements OnInit, OnDestroy {
  @ViewChild(PhotoTimelineComponent) private timeline!: PhotoTimelineComponent;

  private store = inject(Store);
  private photosService = inject(PhotosService);
  private http = inject(HttpClient);
  private syncService = inject(SyncService);
  private appContextService = inject(AppContextService);

  storageDeviceId = computed(() => this.appContextService.context()?.storageDeviceId ?? '');
  granularity = signal<Granularity>('year');

  // Derived from store — single source of truth for photos and pagination
  allPhotos = this.store.selectSignal(PhotosState.photos);
  loading = this.store.selectSignal(PhotosState.loading);
  loadingMore = this.store.selectSignal(PhotosState.loadingMore);
  hasMore = this.store.selectSignal(PhotosState.hasMore);
  loadError = this.store.selectSignal(PhotosState.error);
  monthsSummary = this.store.selectSignal(PhotosState.monthsSummary);
  activeMonth = this.store.selectSignal(PhotosState.activeMonth);

  groups = computed<PhotoGroup[]>(() => this.buildGroupsFromPhotos(this.allPhotos(), this.granularity()));

  syncing = signal(false);
  syncProgress = signal<SyncProgressEvent | null>(null);

  private syncSub: Subscription | null = null;

  selectedIds = signal(new Set<string>());
  thumbnailUrls = signal(new Map<string, string>());
  private thumbnailBlobUrls: string[] = [];

  private thumbnailQueue: PhotoResponse[] = [];
  private thumbnailLoadingActive = false;
  private isScrolling = false;
  private scrollDebounceTimer: ReturnType<typeof setTimeout> | null = null;
  private thumbnailDelayTimer: ReturnType<typeof setTimeout> | null = null;

  detailPhoto = signal<PhotoResponse | null>(null);
  detailImageUrl = signal<string | null>(null);
  private detailBlobUrl: string | null = null;

  canDeleteSelected = computed(() => {
    const selectedPhotos = this.allPhotos().filter(p => this.selectedIds().has(p.id));
    return selectedPhotos.length > 0 && selectedPhotos.every(p => p.syncedToDisk);
  });

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
        const deviceId = this.storageDeviceId();
        if (deviceId) {
          // Reload photos and summary after sync completes so new photos appear
          this.store.dispatch(new LoadPhotos(deviceId, this.activeMonth() ?? undefined));
          this.store.dispatch(new LoadMonthsSummary(deviceId));
        }
      } else if (event?.phase === 'ERROR') {
        this.syncing.set(false);
      }
    });
  }

  ngOnDestroy(): void {
    for (const url of this.thumbnailBlobUrls) {
      URL.revokeObjectURL(url);
    }
    if (this.detailBlobUrl) {
      URL.revokeObjectURL(this.detailBlobUrl);
    }
    this.syncSub?.unsubscribe();
    this.syncService.closeEvents();
    if (this.scrollDebounceTimer !== null) clearTimeout(this.scrollDebounceTimer);
    if (this.thumbnailDelayTimer !== null) clearTimeout(this.thumbnailDelayTimer);
    this.thumbnailQueue = [];
  }

  onGranularityChanged(g: Granularity): void {
    this.granularity.set(g);
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

  onSelectionChanged(ids: Set<string>): void {
    this.selectedIds.set(ids);
  }

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
    if (!confirm(`Delete ${this.selectedIds().size} photo(s) from iCloud? This cannot be undone.`)) return;

    const photoIds = Array.from(this.selectedIds());
    const firstPhoto = this.allPhotos().find(p => photoIds.includes(p.id));
    const accountId = firstPhoto?.accountId ?? '';
    this.photosService.deleteFromICloud(
      { photoIds },
      { accountId }
    ).subscribe({
      next: () => {
        this.selectedIds.set(new Set());
        // Reload to reflect deletion
        const deviceId = this.storageDeviceId();
        if (deviceId) {
          this.store.dispatch(new LoadPhotos(deviceId, this.activeMonth() ?? undefined));
        }
      },
      error: (err) => alert('Delete from iCloud failed: ' + (err?.message ?? 'Unknown error'))
    });
  }

  onDeleteFromIPhone(): void {
    if (!this.canDeleteSelected()) return;
    if (!confirm(`Delete ${this.selectedIds().size} photo(s) from iPhone? This cannot be undone.`)) return;

    const photoIds = Array.from(this.selectedIds());
    const firstPhoto = this.allPhotos().find(p => photoIds.includes(p.id));
    const accountId = firstPhoto?.accountId ?? '';
    this.photosService.deleteFromIPhone(
      { photoIds },
      { accountId }
    ).subscribe({
      next: () => {
        this.selectedIds.set(new Set());
        const deviceId = this.storageDeviceId();
        if (deviceId) {
          this.store.dispatch(new LoadPhotos(deviceId, this.activeMonth() ?? undefined));
        }
      },
      error: (err) => alert('Delete from iPhone failed: ' + (err?.message ?? 'Unknown error'))
    });
  }

  onDetailDeleteFromICloud(photo: PhotoResponse): void {
    if (!photo.syncedToDisk) return;
    if (!confirm(`Delete "${photo.filename}" from iCloud?`)) return;
    this.photosService.deleteFromICloud(
      { photoIds: [photo.id] },
      { accountId: photo.accountId }
    ).subscribe({
      next: () => {
        if (this.detailPhoto()?.id === photo.id) {
          this.detailPhoto.update(p => p ? { ...p, existsOnIcloud: false } : p);
        }
        const deviceId = this.storageDeviceId();
        if (deviceId) {
          this.store.dispatch(new LoadPhotos(deviceId, this.activeMonth() ?? undefined));
        }
      },
      error: (err) => alert('Failed: ' + (err?.message ?? 'Unknown error'))
    });
  }

  onDetailDeleteFromIPhone(photo: PhotoResponse): void {
    if (!photo.syncedToDisk) return;
    if (!confirm(`Delete "${photo.filename}" from iPhone?`)) return;
    this.photosService.deleteFromIPhone(
      { photoIds: [photo.id] },
      { accountId: photo.accountId }
    ).subscribe({
      next: () => {
        if (this.detailPhoto()?.id === photo.id) {
          this.detailPhoto.update(p => p ? { ...p, existsOnIphone: false } : p);
        }
        const deviceId = this.storageDeviceId();
        if (deviceId) {
          this.store.dispatch(new LoadPhotos(deviceId, this.activeMonth() ?? undefined));
        }
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
    if (!photo) return;

    // Skip photos without a server-side thumbnail — show placeholder instead, no request needed
    if (!photo.thumbnailPath) return;

    const existingQueueIndex = this.thumbnailQueue.findIndex(p => p.id === photoId);
    if (existingQueueIndex > 0) {
      // Promote to front so visible photos load first
      const [queuedPhoto] = this.thumbnailQueue.splice(existingQueueIndex, 1);
      this.thumbnailQueue.unshift(queuedPhoto);
    } else if (existingQueueIndex === -1) {
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
      if (!this.thumbnailLoadingActive) {
        this.processNextThumbnail();
      }
    }, 250);
  }

  clearSelection(): void {
    this.selectedIds.set(new Set());
  }

  private resetThumbnailState(): void {
    this.thumbnailQueue = [];
    this.thumbnailLoadingActive = false;
    for (const url of this.thumbnailBlobUrls) {
      URL.revokeObjectURL(url);
    }
    this.thumbnailBlobUrls = [];
    this.thumbnailUrls.set(new Map());
  }

  private buildGroupsFromPhotos(photos: PhotoResponse[], granularity: Granularity): PhotoGroup[] {
    const groupMap = new Map<string, PhotoResponse[]>();

    for (const photo of photos) {
      const date = new Date(photo.createdDate);
      const groupKey = granularity === 'year'
        ? String(date.getFullYear())
        : `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`;

      if (!groupMap.has(groupKey)) {
        groupMap.set(groupKey, []);
      }
      groupMap.get(groupKey)!.push(photo);
    }

    return Array.from(groupMap.entries())
      .sort((a, b) => b[0].localeCompare(a[0]))
      .map(([key, groupPhotos]) => ({
        key,
        label: granularity === 'year' ? key : groupPhotos[0]
          ? new Date(groupPhotos[0].createdDate).toLocaleString('default', { month: 'long', year: 'numeric' })
          : key,
        photos: groupPhotos.sort((a, b) => new Date(b.createdDate).getTime() - new Date(a.createdDate).getTime())
      }));
  }

  private processNextThumbnail(): void {
    if (this.thumbnailQueue.length === 0) {
      this.thumbnailLoadingActive = false;
      return;
    }
    if (this.isScrolling) {
      this.thumbnailLoadingActive = false;
      return;
    }

    this.thumbnailLoadingActive = true;
    const photo = this.thumbnailQueue.shift()!;

    if (this.thumbnailUrls().has(photo.id)) {
      this.processNextThumbnail();
      return;
    }

    this.http.get(`/api/photos/${photo.id}/thumbnail`, { responseType: 'blob' }).subscribe({
      next: (blob) => {
        const url = URL.createObjectURL(blob);
        this.thumbnailBlobUrls.push(url);
        this.thumbnailUrls.update(urls => new Map(urls).set(photo.id, url));
        this.thumbnailDelayTimer = setTimeout(() => this.processNextThumbnail(), 40);
      },
      error: () => {
        this.thumbnailDelayTimer = setTimeout(() => this.processNextThumbnail(), 40);
      }
    });
  }
}
