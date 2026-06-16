import { Component, effect, inject, input, OnInit, output } from '@angular/core';
import { ThumbnailJobStateService } from '../../../core/services/thumbnail-job-state.service';

@Component({
  selector: 'app-missing-thumbnails-banner',
  standalone: true,
  imports: [],
  templateUrl: './missing-thumbnails-banner.component.html',
  styleUrl: './missing-thumbnails-banner.component.scss'
})
export class MissingThumbnailsBannerComponent implements OnInit {
  private jobState = inject(ThumbnailJobStateService);

  accountId = input('');
  selectedPhotoIds = input<string[]>([]);
  deleteStatus = input<string | null>(null);

  thumbnailsGenerated = output<void>();

  readonly generating = this.jobState.generating;
  readonly progress = this.jobState.progress;
  readonly missingCount = this.jobState.missingCount;
  readonly progressPercent = this.jobState.progressPercent;

  constructor() {
    effect(() => {
      const accountId = this.accountId();
      if (accountId) this.jobState.fetchMissingCount(accountId);
    });

    effect(() => {
      const n = this.jobState.thumbnailsDone();
      if (n > 0) this.thumbnailsGenerated.emit();
    });
  }

  ngOnInit(): void {
    this.jobState.restoreFromStorage();
  }

  generateAll(): void {
    this.jobState.startJob(this.accountId(), null);
  }

  generateForSelected(): void {
    this.jobState.startJob(null, this.selectedPhotoIds());
  }

  stopGeneration(): void {
    this.jobState.stopJob();
  }
}
