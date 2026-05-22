import { Component, HostListener, computed, inject, input, output, signal } from '@angular/core';
import { Store } from '@ngxs/store';
import { PhotosState } from '../../../state/photos/photos.state';
import { GroupingMode, SetColumnsPerRow, SetGroupingMode, SetShowDetails } from '../../../state/photos/photos.actions';

export type SourceFilter = 'all' | 'icloud' | 'iphone';

@Component({
  selector: 'app-photos-toolbar',
  standalone: true,
  imports: [],
  templateUrl: './photos-toolbar.component.html',
  styleUrl: './photos-toolbar.component.scss'
})
export class PhotosToolbarComponent {
  granularity = input<'year' | 'month'>('year');
  syncing = input(false);
  canSync = input(false);
  sourceFilter = input<SourceFilter>('all');

  granularityChanged = output<'year' | 'month'>();
  syncRequested = output<void>();
  sourceFilterChanged = output<SourceFilter>();
  selectVideoPreviewsRequested = output<void>();

  private store = inject(Store);
  showDetails = this.store.selectSignal(PhotosState.showDetails);
  columnsPerRow = this.store.selectSignal(PhotosState.columnsPerRow);
  groupingMode = this.store.selectSignal(PhotosState.groupingMode);
  viewOptionsOpen = signal(false);
  readonly columnOptions = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12];

  granularityIdx = computed(() => this.granularity() === 'year' ? 0 : 1);
  sourceIdx = computed(() => {
    const f = this.sourceFilter();
    return f === 'all' ? 0 : f === 'icloud' ? 1 : 2;
  });

  setGranularity(g: 'year' | 'month'): void {
    this.granularityChanged.emit(g);
  }

  setSourceFilter(f: SourceFilter): void {
    this.sourceFilterChanged.emit(f);
  }

  toggleViewOptions(event: MouseEvent): void {
    event.stopPropagation();
    this.viewOptionsOpen.update(v => !v);
  }

  toggleShowDetails(event: MouseEvent): void {
    event.stopPropagation();
    this.store.dispatch(new SetShowDetails(!this.showDetails()));
  }

  setColumnsPerRow(n: number, event: MouseEvent): void {
    event.stopPropagation();
    this.store.dispatch(new SetColumnsPerRow(n));
  }

  toggleGroupByDays(event: MouseEvent): void {
    event.stopPropagation();
    const current = this.groupingMode();
    const next: GroupingMode = current === 'none' ? 'day' : 'none';
    this.store.dispatch(new SetGroupingMode(next));
  }

  toggleGroupByHours(event: MouseEvent): void {
    event.stopPropagation();
    const current = this.groupingMode();
    const next: GroupingMode = current === 'hour' ? 'day' : 'hour';
    this.store.dispatch(new SetGroupingMode(next));
  }

  onSelectVideoPreviews(event: MouseEvent): void {
    event.stopPropagation();
    this.selectVideoPreviewsRequested.emit();
  }

  @HostListener('document:click')
  closeViewOptions(): void {
    this.viewOptionsOpen.set(false);
  }
}
