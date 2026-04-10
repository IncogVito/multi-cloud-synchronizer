import { Component, ElementRef, input, OnDestroy, OnInit, output, ViewChild } from '@angular/core';
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

  ngOnInit(): void {
    const el = this.contentAreaRef.nativeElement;

    this.scrollListener = () => {
      if (this.loading() || this.loadingMore() || !this.hasMore()) return;
      if (el.scrollTop + el.clientHeight >= el.scrollHeight - 300) {
        console.log("Loading more");
        this.loadMoreRequested.emit();
      }
    };

    el.addEventListener('scroll', this.scrollListener, { passive: true });
  }

  ngOnDestroy(): void {
    this.contentAreaRef.nativeElement.removeEventListener('scroll', this.scrollListener);
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

  togglePhotoSelection(id: string): void {
    const next = new Set(this.selectedIds());
    if (next.has(id)) {
      next.delete(id);
    } else {
      next.add(id);
    }
    this.selectionChanged.emit(next);
  }
}
