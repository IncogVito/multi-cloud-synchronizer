import { Component, input, output } from '@angular/core';

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

  granularityChanged = output<'year' | 'month'>();
  syncRequested = output<void>();

  setGranularity(g: 'year' | 'month'): void {
    this.granularityChanged.emit(g);
  }
}
