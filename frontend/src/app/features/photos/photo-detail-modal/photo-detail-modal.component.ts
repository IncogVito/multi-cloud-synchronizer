import { Component, Input, Output, EventEmitter } from '@angular/core';
import { CommonModule } from '@angular/common';
import { PhotoResponse } from '../../../core/api/generated/model/photoResponse';

@Component({
  selector: 'app-photo-detail-modal',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './photo-detail-modal.component.html',
  styleUrl: './photo-detail-modal.component.scss'
})
export class PhotoDetailModalComponent {
  @Input() photo: PhotoResponse | null = null;
  @Input() imageUrl: string | null = null;
  @Input() loading = false;

  @Output() closed = new EventEmitter<void>();
  @Output() deleteFromICloud = new EventEmitter<PhotoResponse>();
  @Output() deleteFromIPhone = new EventEmitter<PhotoResponse>();

  formatFileSize(bytes: number): string {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    if (bytes < 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
    return `${(bytes / 1024 / 1024 / 1024).toFixed(2)} GB`;
  }
}
