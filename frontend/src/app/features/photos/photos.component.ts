import { Component, OnInit, OnDestroy, inject, ChangeDetectorRef } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { PhotosService } from '../../core/api/generated/photos/photos.service';
import { AccountsService } from '../../core/api/generated/accounts/accounts.service';
import { PhotoResponse } from '../../core/api/generated/model/photoResponse';
import { AccountResponse } from '../../core/api/generated/model/accountResponse';
import { PhotosToolbarComponent } from './photos-toolbar/photos-toolbar.component';
import { PhotoTimelineComponent } from './photo-timeline/photo-timeline.component';
import { PhotoGroup } from './photo-timeline/photo-timeline.component';
import { BatchActionsBarComponent } from './batch-actions-bar/batch-actions-bar.component';
import { PhotoDetailModalComponent } from './photo-detail-modal/photo-detail-modal.component';

type Granularity = 'year' | 'month';

@Component({
  selector: 'app-photos',
  standalone: true,
  imports: [
    PhotosToolbarComponent,
    PhotoTimelineComponent,
    BatchActionsBarComponent,
    PhotoDetailModalComponent
  ],
  templateUrl: './photos.component.html',
  styleUrl: './photos.component.scss'
})
export class PhotosComponent implements OnInit, OnDestroy {
  private photosService = inject(PhotosService);
  private accountsService = inject(AccountsService);
  private http = inject(HttpClient);
  private cdr = inject(ChangeDetectorRef);

  accounts: AccountResponse[] = [];
  selectedAccountId = '';
  granularity: Granularity = 'year';

  allPhotos: PhotoResponse[] = [];
  groups: PhotoGroup[] = [];

  loading = false;
  loadingMore = false;
  loadError = '';
  hasMore = false;
  currentPage = 0;
  pageSize = 100;

  syncing = false;

  selectedIds = new Set<string>();
  thumbnailUrls = new Map<string, string>();
  thumbnailBlobUrls: string[] = [];

  detailPhoto: PhotoResponse | null = null;
  detailImageUrl: string | null = null;
  private detailBlobUrl: string | null = null;

  ngOnInit(): void {
    this.accountsService.listAccounts().subscribe({
      next: (accounts) => {
        this.accounts = accounts;
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
  }

  onAccountChanged(accountId: string): void {
    this.selectedAccountId = accountId;
    this.allPhotos = [];
    this.groups = [];
    this.selectedIds = new Set();
    this.currentPage = 0;
    this.hasMore = false;
    this.loadError = '';

    if (this.selectedAccountId) {
      this.loadPhotos();
    }
  }

  onGranularityChanged(g: Granularity): void {
    this.granularity = g;
    this.buildGroups();
  }

  onSyncRequested(): void {
    if (!this.selectedAccountId || this.syncing) return;
    this.syncing = true;
    this.photosService.syncPhotos({ accountId: this.selectedAccountId }).subscribe({
      next: () => {
        this.syncing = false;
        this.allPhotos = [];
        this.currentPage = 0;
        this.loadPhotos();
      },
      error: (err) => {
        this.syncing = false;
        alert('Sync failed: ' + (err?.message ?? 'Unknown error'));
      }
    });
  }

  onSelectionChanged(ids: Set<string>): void {
    this.selectedIds = ids;
  }

  onLoadMoreRequested(): void {
    if (this.loadingMore || !this.hasMore) return;
    this.loadingMore = true;
    this.currentPage++;

    this.photosService.listPhotos({
      accountId: this.selectedAccountId,
      synced: 'all',
      page: this.currentPage,
      size: this.pageSize
    }).subscribe({
      next: (resp) => {
        const photos = resp.photos ?? [];
        this.allPhotos = [...this.allPhotos, ...photos];
        this.hasMore = this.allPhotos.length < (resp.total ?? 0);
        this.buildGroups();
        this.loadingMore = false;
        this.loadThumbnails(photos);
      },
      error: () => {
        this.loadingMore = false;
      }
    });
  }

  onPhotoClicked(photo: PhotoResponse): void {
    this.detailPhoto = photo;
    this.detailImageUrl = null;

    if (this.detailBlobUrl) {
      URL.revokeObjectURL(this.detailBlobUrl);
      this.detailBlobUrl = null;
    }

    this.http.get(`/api/photos/${photo.id}/full`, { responseType: 'blob' }).subscribe({
      next: (blob) => {
        this.detailBlobUrl = URL.createObjectURL(blob);
        this.detailImageUrl = this.detailBlobUrl;
        this.cdr.detectChanges();
      },
      error: () => {
        const thumbUrl = this.thumbnailUrls.get(photo.id);
        if (thumbUrl) this.detailImageUrl = thumbUrl;
        this.cdr.detectChanges();
      }
    });
  }

  onDetailClosed(): void {
    this.detailPhoto = null;
    this.detailImageUrl = null;
    if (this.detailBlobUrl) {
      URL.revokeObjectURL(this.detailBlobUrl);
      this.detailBlobUrl = null;
    }
  }

  onDeleteFromICloud(): void {
    if (!this.canDeleteSelected) return;
    if (!confirm(`Delete ${this.selectedIds.size} photo(s) from iCloud? This cannot be undone.`)) return;

    const photoIds = Array.from(this.selectedIds);
    this.photosService.deleteFromICloud(
      { photoIds },
      { accountId: this.selectedAccountId }
    ).subscribe({
      next: () => {
        this.allPhotos = this.allPhotos.map(p =>
          photoIds.includes(p.id) ? { ...p, existsOnIcloud: false } : p
        );
        this.buildGroups();
        this.selectedIds = new Set();
      },
      error: (err) => alert('Delete from iCloud failed: ' + (err?.message ?? 'Unknown error'))
    });
  }

  onDeleteFromIPhone(): void {
    if (!this.canDeleteSelected) return;
    if (!confirm(`Delete ${this.selectedIds.size} photo(s) from iPhone? This cannot be undone.`)) return;

    const photoIds = Array.from(this.selectedIds);
    this.photosService.deleteFromIPhone(
      { photoIds },
      { accountId: this.selectedAccountId }
    ).subscribe({
      next: () => {
        this.allPhotos = this.allPhotos.map(p =>
          photoIds.includes(p.id) ? { ...p, existsOnIphone: false } : p
        );
        this.buildGroups();
        this.selectedIds = new Set();
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
        this.allPhotos = this.allPhotos.map(p =>
          p.id === photo.id ? { ...p, existsOnIcloud: false } : p
        );
        this.buildGroups();
        if (this.detailPhoto?.id === photo.id) {
          this.detailPhoto = { ...this.detailPhoto, existsOnIcloud: false };
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
        this.allPhotos = this.allPhotos.map(p =>
          p.id === photo.id ? { ...p, existsOnIphone: false } : p
        );
        this.buildGroups();
        if (this.detailPhoto?.id === photo.id) {
          this.detailPhoto = { ...this.detailPhoto, existsOnIphone: false };
        }
      },
      error: (err) => alert('Failed: ' + (err?.message ?? 'Unknown error'))
    });
  }

  clearSelection() {
    this.selectedIds = new Set()
  }

  get canDeleteSelected(): boolean {
    const selectedPhotos = this.allPhotos.filter(p => this.selectedIds.has(p.id));
    return selectedPhotos.length > 0 && selectedPhotos.every(p => p.syncedToDisk);
  }

  private loadPhotos(): void {
    this.loading = true;
    this.loadError = '';
    this.photosService.listPhotos({
      accountId: this.selectedAccountId,
      synced: 'all',
      page: this.currentPage,
      size: this.pageSize
    }).subscribe({
      next: (resp) => {
        const photos = resp.photos ?? [];
        this.allPhotos = [...this.allPhotos, ...photos];
        this.hasMore = this.allPhotos.length < (resp.total ?? 0);
        this.buildGroups();
        this.loading = false;
        this.loadThumbnails(photos);
      },
      error: (err) => {
        this.loadError = 'Failed to load photos: ' + (err?.message ?? 'Unknown error');
        this.loading = false;
      }
    });
  }

  private buildGroups(): void {
    const groupMap = new Map<string, PhotoResponse[]>();

    for (const photo of this.allPhotos) {
      const date = new Date(photo.createdDate);
      let key: string;

      if (this.granularity === 'year') {
        key = String(date.getFullYear());
      } else {
        const year = date.getFullYear();
        const month = date.getMonth();
        key = `${year}-${String(month + 1).padStart(2, '0')}`;
      }

      if (!groupMap.has(key)) {
        groupMap.set(key, []);
      }
      groupMap.get(key)!.push(photo);
    }

    this.groups = Array.from(groupMap.entries())
      .sort((a, b) => b[0].localeCompare(a[0]))
      .map(([key, photos]) => ({
        key,
        label: this.granularity === 'year' ? key : photos[0]
          ? new Date(photos[0].createdDate).toLocaleString('default', { month: 'long', year: 'numeric' })
          : key,
        photos: photos.sort((a, b) => new Date(b.createdDate).getTime() - new Date(a.createdDate).getTime())
      }));
  }

  private loadThumbnails(photos: PhotoResponse[]): void {
    for (const photo of photos) {
      if (this.thumbnailUrls.has(photo.id)) continue;

      this.http.get(`/api/photos/${photo.id}/thumbnail`, { responseType: 'blob' }).subscribe({
        next: (blob) => {
          const url = URL.createObjectURL(blob);
          this.thumbnailUrls.set(photo.id, url);
          this.thumbnailBlobUrls.push(url);
          this.cdr.detectChanges();
        },
        error: () => { /* thumbnail failed, leave placeholder */ }
      });
    }
  }
}
