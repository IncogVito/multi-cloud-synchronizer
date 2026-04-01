import { Component, input, output } from '@angular/core';
import { DatePipe } from '@angular/common';
import { PhotoResponse } from '../../../core/api/generated/model/photoResponse';

@Component({
  selector: 'app-photo-detail-modal',
  standalone: true,
  imports: [DatePipe],
  templateUrl: './photo-detail-modal.component.html',
  styleUrl: './photo-detail-modal.component.scss'
})
export class PhotoDetailModalComponent {
  photo = input<PhotoResponse | null>(null);
  imageUrl = input<string | null>(null);
  loading = input(false);

  closed = output<void>();
  deleteFromICloud = output<PhotoResponse>();
  deleteFromIPhone = output<PhotoResponse>();

  formatFileSize(bytes: number): string {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    if (bytes < 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
    return `${(bytes / 1024 / 1024 / 1024).toFixed(2)} GB`;
  }
}
