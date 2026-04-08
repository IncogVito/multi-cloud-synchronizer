import { Component, input, computed } from '@angular/core';
import { SyncProgressEvent } from '../../../core/models/sync-progress.model';

@Component({
  selector: 'app-sync-progress',
  standalone: true,
  templateUrl: './sync-progress.component.html',
  styleUrl: './sync-progress.component.scss'
})
export class SyncProgressComponent {
  event = input<SyncProgressEvent | null>(null);

  progressPercent = computed(() => {
    const e = this.event();
    if (!e) return 0;
    if (e.phase === 'FETCHING_METADATA' && e.totalOnCloud > 0)
      return Math.round((e.metadataFetched / e.totalOnCloud) * 100);
    if (e.totalOnCloud > 0)
      return Math.round((e.synced / e.totalOnCloud) * 100);
    return 0;
  });

  isVisible = computed(() => !!this.event());
}
