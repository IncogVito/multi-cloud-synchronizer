import { Component, OnInit, OnDestroy, inject, signal, computed, effect } from '@angular/core';
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
    this.reloadThumbnailsForPhotosWithoutPreview();
  }

  clearSelection(): void {
    this.selectedIds.set(new Set());
  }

  private resetAndLoad(): void {
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
        this.loadThumbnails(photos);
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
        this.loadThumbnails(photos);
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

  private loadThumbnails(photos: PhotoResponse[]): void {
    for (const photo of photos) {
      if (this.thumbnailUrls().has(photo.id)) continue;

      this.http.get(`/api/photos/${photo.id}/thumbnail`, { responseType: 'blob' }).subscribe({
        next: (blob) => {
          const url = URL.createObjectURL(blob);
          this.thumbnailBlobUrls.push(url);
          this.thumbnailUrls.update(urls => new Map(urls).set(photo.id, url));
        },
        error: () => { /* thumbnail failed, leave placeholder */ }
      });
    }
  }

  private reloadThumbnailsForPhotosWithoutPreview(): void {
    const photosWithoutPreview = this.allPhotos().filter(p => !this.thumbnailUrls().has(p.id));
    this.loadThumbnails(photosWithoutPreview);
  }
}
