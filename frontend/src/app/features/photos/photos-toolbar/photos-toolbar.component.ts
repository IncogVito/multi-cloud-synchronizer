import { Component, HostListener, inject, input, output, signal } from '@angular/core';
import { Store } from '@ngxs/store';
import { PhotosState } from '../../../state/photos/photos.state';
import { SetShowDetails, SetColumnsPerRow } from '../../../state/photos/photos.actions';

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

  private store = inject(Store);
  showDetails = this.store.selectSignal(PhotosState.showDetails);
  columnsPerRow = this.store.selectSignal(PhotosState.columnsPerRow);
  viewOptionsOpen = signal(false);
  readonly columnOptions = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12];

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

  @HostListener('document:click')
  closeViewOptions(): void {
    this.viewOptionsOpen.set(false);
  }
}
