import { Component, input, output } from '@angular/core';

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

  setGranularity(g: 'year' | 'month'): void {
    this.granularityChanged.emit(g);
  }

  setSourceFilter(f: SourceFilter): void {
    this.sourceFilterChanged.emit(f);
  }
}
