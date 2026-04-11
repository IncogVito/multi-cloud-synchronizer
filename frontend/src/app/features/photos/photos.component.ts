import { Component, OnInit, OnDestroy, inject, signal, computed, effect, ViewChild } from '@angular/core';
import { HttpClient } from '@angular/common/http';
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

  private photosService = inject(PhotosService);
  private http = inject(HttpClient);
  private syncService = inject(SyncService);
  private appContextService = inject(AppContextService);

  storageDeviceId = computed(() => this.appContextService.context()?.storageDeviceId ?? '');
  granularity = signal<Granularity>('year');

  allPhotos = signal<PhotoResponse[]>([]);
  groups = computed<PhotoGroup[]>(() => this.buildGroups(this.allPhotos(), this.granularity()));

  loading = signal(false);
  loadingMore = signal(false);
  loadError = signal('');
  hasMore = signal(false);
  currentPage = signal(0);
  pageSize = 50;

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
        this.resetAndLoad();
        this.previousDeviceId = deviceId;
      }
    });
  }

  ngOnInit(): void {
    this.syncSub = this.syncService.syncProgress$.subscribe(event => {
      this.syncProgress.set(event);
      if (event?.phase === 'DONE') {
        this.syncing.set(false);
        this.resetAndLoad();
      } else if (event?.phase === 'ERROR') {
        this.syncing.set(false);
      }
    });


    // log every change on all photos

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

  onSelectionChanged(ids: Set<string>): void {
    this.selectedIds.set(ids);
  }

  onLoadMoreRequested(): void {
    if (this.loadingMore() || !this.hasMore()) return;
    this.loadingMore.set(true);

    this.currentPage.update(p => p + 1);
    this.loadNextPage();
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
    this.photosService.deleteFromICloud(
      { photoIds },
      { accountId: '' }
    ).subscribe({
      next: () => {
        this.allPhotos.update(photos => photos.map(p =>
          photoIds.includes(p.id) ? { ...p, existsOnIcloud: false } : p
        ));
        this.selectedIds.set(new Set());
      },
      error: (err) => alert('Delete from iCloud failed: ' + (err?.message ?? 'Unknown error'))
    });
  }

  onDeleteFromIPhone(): void {
    if (!this.canDeleteSelected()) return;
    if (!confirm(`Delete ${this.selectedIds().size} photo(s) from iPhone? This cannot be undone.`)) return;

    const photoIds = Array.from(this.selectedIds());
    this.photosService.deleteFromIPhone(
      { photoIds },
      { accountId: '' }
    ).subscribe({
      next: () => {
        this.allPhotos.update(photos => photos.map(p =>
          photoIds.includes(p.id) ? { ...p, existsOnIphone: false } : p
        ));
        this.selectedIds.set(new Set());
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
        this.allPhotos.update(photos => photos.map(p =>
          p.id === photo.id ? { ...p, existsOnIcloud: false } : p
        ));
        if (this.detailPhoto()?.id === photo.id) {
          this.detailPhoto.update(p => p ? { ...p, existsOnIcloud: false } : p);
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
        this.allPhotos.update(photos => photos.map(p =>
          p.id === photo.id ? { ...p, existsOnIphone: false } : p
        ));
        if (this.detailPhoto()?.id === photo.id) {
          this.detailPhoto.update(p => p ? { ...p, existsOnIphone: false } : p);
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

    const idx = this.thumbnailQueue.findIndex(p => p.id === photoId);
    if (idx > 0) {
      const [photo] = this.thumbnailQueue.splice(idx, 1);
      this.thumbnailQueue.unshift(photo);
    } else if (idx === -1) {
      const photo = this.allPhotos().find(p => p.id === photoId);
      if (photo) this.thumbnailQueue.unshift(photo);
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

  private resetAndLoad(): void {
    this.thumbnailQueue = [];
    this.thumbnailLoadingActive = false;
    this.allPhotos.set([]);
    this.selectedIds.set(new Set());
    this.currentPage.set(0);
    this.hasMore.set(false);
    this.loadError.set('');
    this.loadPhotos();
  }

  private loadPhotos(): void {
    this.loading.set(true);
    this.loadError.set('');
    this.photosService.listPhotos({
      storageDeviceId: this.storageDeviceId(),
      synced: 'true',
      accountId: '',
      page: this.currentPage(),
      size: this.pageSize
    }).subscribe({
      next: (resp) => {
        const photos = resp.photos ?? [];
        this.allPhotos.update(existing => [...existing, ...photos]);
        this.hasMore.set(this.allPhotos().length < (resp.total ?? 0));
        this.loading.set(false);
      },
      error: (err) => {
        this.loadError.set('Failed to load photos: ' + (err?.message ?? 'Unknown error'));
        this.loading.set(false);
      }
    });
  }

  private loadNextPage(): void {
    this.photosService.listPhotos({
      storageDeviceId: this.storageDeviceId(),
      synced: 'true',
      accountId: '',
      page: this.currentPage(),
      size: this.pageSize
    }).subscribe({
      next: (resp) => {
        const photos = resp.photos ?? [];
        this.allPhotos.update(existing => [...existing, ...photos]);
        this.hasMore.set(this.allPhotos().length < (resp.total ?? 0));
        this.loadingMore.set(false);
      },
      error: () => {
        this.loadingMore.set(false);
      }
    });
  }

  private buildGroups(photos: PhotoResponse[], granularity: Granularity): PhotoGroup[] {
    const groupMap = new Map<string, PhotoResponse[]>();

    for (const photo of photos) {
      const date = new Date(photo.createdDate);
      const key = granularity === 'year'
        ? String(date.getFullYear())
        : `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`;

      if (!groupMap.has(key)) {
        groupMap.set(key, []);
      }
      groupMap.get(key)!.push(photo);
    }

    return Array.from(groupMap.entries())
      .sort((a, b) => b[0].localeCompare(a[0]))
      .map(([key, photos]) => ({
        key,
        label: granularity === 'year' ? key : photos[0]
          ? new Date(photos[0].createdDate).toLocaleString('default', { month: 'long', year: 'numeric' })
          : key,
        photos: photos.sort((a, b) => new Date(b.createdDate).getTime() - new Date(a.createdDate).getTime())
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
