import { Component, computed, effect, ElementRef, HostListener, input, OnDestroy, OnInit, output, signal, ViewChild } from '@angular/core';
import { PhotoResponse } from '../../../core/api/generated/model/photoResponse';

export interface PhotoGroup {
  key: string;
  label: string;
  photos: PhotoResponse[];
}

@Component({
  selector: 'app-photo-timeline',
  standalone: true,
  imports: [],
  templateUrl: './photo-timeline.component.html',
  styleUrl: './photo-timeline.component.scss'
})
export class PhotoTimelineComponent implements OnInit, OnDestroy {
  groups = input<PhotoGroup[]>([]);
  selectedIds = input(new Set<string>());
  thumbnailUrls = input(new Map<string, string>());
  loading = input(false);
  loadingMore = input(false);
  hasMore = input(false);
  loadError = input('');

  @ViewChild('contentArea', { static: true }) contentAreaRef!: ElementRef<HTMLDivElement>;

  photoClicked = output<PhotoResponse>();
  selectionChanged = output<Set<string>>();
  loadMoreRequested = output<void>();

  private scrollListener!: () => void;

  private anchorPhotoId: string | null = null;
  private isShiftKeyHeld = signal(false);
  private shiftHoverPhotoId = signal<string | null>(null);

  private allPhotosFlat = computed(() =>
    this.groups().flatMap(group => group.photos)
  );

  shiftPreviewIds = computed(() => {
    const shiftHeld = this.isShiftKeyHeld();
    const anchorId = this.anchorPhotoId;
    const hoverId = this.shiftHoverPhotoId();

    if (!shiftHeld || anchorId === null || hoverId === null) {
      return new Set<string>();
    }
    return this.computeRangeIds(anchorId, hoverId);
  });

  constructor() {
    effect(() => {
      if (this.selectedIds().size === 0) {
        this.anchorPhotoId = null;
      }
    });
  }

  ngOnInit(): void {
    const el = this.contentAreaRef.nativeElement;

    this.scrollListener = () => {
      if (this.loading() || this.loadingMore() || !this.hasMore()) return;
      if (el.scrollTop + el.clientHeight >= el.scrollHeight - 400) {
        this.loadMoreRequested.emit();
      }
    };

    el.addEventListener('scroll', this.scrollListener, { passive: true });
  }

  ngOnDestroy(): void {
    this.contentAreaRef.nativeElement.removeEventListener('scroll', this.scrollListener);
  }

  @HostListener('document:keydown', ['$event'])
  onKeyDown(event: KeyboardEvent): void {
    if (event.key === 'Shift') {
      this.isShiftKeyHeld.set(true);
    }
  }

  @HostListener('document:keyup', ['$event'])
  onKeyUp(event: KeyboardEvent): void {
    if (event.key === 'Shift') {
      this.isShiftKeyHeld.set(false);
      this.shiftHoverPhotoId.set(null);
    }
  }

  isGroupFullySelected(group: PhotoGroup): boolean {
    return group.photos.length > 0 && group.photos.every(p => this.selectedIds().has(p.id));
  }

  isGroupPartiallySelected(group: PhotoGroup): boolean {
    return group.photos.some(p => this.selectedIds().has(p.id)) && !this.isGroupFullySelected(group);
  }

  toggleGroupSelection(group: PhotoGroup): void {
    const allSelected = group.photos.every(p => this.selectedIds().has(p.id));
    const next = new Set(this.selectedIds());
    if (allSelected) {
      group.photos.forEach(p => next.delete(p.id));
    } else {
      group.photos.forEach(p => next.add(p.id));
    }
    this.selectionChanged.emit(next);
  }

  handlePhotoMouseEnter(photoId: string): void {
    if (this.isShiftKeyHeld() && this.anchorPhotoId !== null) {
      this.shiftHoverPhotoId.set(photoId);
    }
  }

  handleSelectionClick(photo: PhotoResponse, event: MouseEvent): void {
    event.stopPropagation();
    event.preventDefault();

    if (event.shiftKey && this.anchorPhotoId !== null) {
      this.confirmRangeSelectionFromAnchor(photo.id);
    } else {
      this.togglePhotoAndUpdateAnchor(photo.id);
    }

    this.shiftHoverPhotoId.set(null);
  }

  private togglePhotoAndUpdateAnchor(photoId: string): void {
    const next = new Set(this.selectedIds());
    if (next.has(photoId)) {
      next.delete(photoId);
    } else {
      next.add(photoId);
    }
    this.anchorPhotoId = photoId;
    this.selectionChanged.emit(next);
  }

  private confirmRangeSelectionFromAnchor(targetPhotoId: string): void {
    const rangeIds = this.computeRangeIds(this.anchorPhotoId!, targetPhotoId);
    const next = new Set(this.selectedIds());
    rangeIds.forEach(id => next.add(id));
    this.selectionChanged.emit(next);
  }

  private computeRangeIds(anchorPhotoId: string, targetPhotoId: string): Set<string> {
    const allPhotos = this.allPhotosFlat();
    const anchorIndex = allPhotos.findIndex(p => p.id === anchorPhotoId);
    const targetIndex = allPhotos.findIndex(p => p.id === targetPhotoId);

    if (anchorIndex === -1 || targetIndex === -1) {
      return new Set<string>();
    }

    const startIndex = Math.min(anchorIndex, targetIndex);
    const endIndex = Math.max(anchorIndex, targetIndex);
    return new Set(allPhotos.slice(startIndex, endIndex + 1).map(p => p.id));
  }
}
