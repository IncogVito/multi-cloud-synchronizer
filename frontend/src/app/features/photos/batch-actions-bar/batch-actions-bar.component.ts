import { Component, input, output } from '@angular/core';

@Component({
  selector: 'app-batch-actions-bar',
  standalone: true,
  imports: [],
  templateUrl: './batch-actions-bar.component.html',
  styleUrl: './batch-actions-bar.component.scss'
})
export class BatchActionsBarComponent {
  selectedCount = input(0);
  selectedSize = input(0);
  canDelete = input(false);
  missingThumbnailCount = input(0);

  deleteFromICloud = output<void>();
  deleteFromIPhone = output<void>();
  generateThumbnails = output<void>();
  clearSelection = output<void>();

  formatBytes(bytes: number): string {
    if (bytes === 0) return '0 B';
    const units = ['B', 'KB', 'MB', 'GB', 'TB'];
    const i = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1);
    return `${(bytes / Math.pow(1024, i)).toFixed(1)} ${units[i]}`;
  }
}
