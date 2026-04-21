import { Component, ElementRef, HostListener, ViewChild, computed, effect, input, output, signal } from '@angular/core';
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
  hasPrev = input(false);
  hasNext = input(false);

  closed = output<void>();
  prevRequested = output<void>();
  nextRequested = output<void>();
  deleteFromICloud = output<PhotoResponse>();
  deleteFromIPhone = output<PhotoResponse>();

  @ViewChild('imageArea') imageAreaRef!: ElementRef<HTMLDivElement>;

  zoom = signal(1);
  translateX = signal(0);
  translateY = signal(0);

  private dragging = false;
  private dragStartX = 0;
  private dragStartY = 0;
  private dragOriginX = 0;
  private dragOriginY = 0;

  readonly imageTransform = computed(() =>
    `scale(${this.zoom()}) translate(${this.translateX()}px, ${this.translateY()}px)`
  );

  constructor() {
    effect(() => {
      if (this.photo()) this.resetView();
    });
  }

  @HostListener('document:keydown', ['$event'])
  onKeyDown(e: KeyboardEvent): void {
    if (!this.photo()) return;
    switch (e.key) {
      case 'Escape': this.closed.emit(); break;
      case 'ArrowLeft': this.prevRequested.emit(); break;
      case 'ArrowRight': this.nextRequested.emit(); break;
      case '+': case '=': this.zoomIn(); break;
      case '-': this.zoomOut(); break;
      case '0': this.resetView(); break;
    }
  }

  onWheel(e: WheelEvent): void {
    e.preventDefault();
    const delta = e.deltaY < 0 ? 0.15 : -0.15;
    this.setZoom(this.zoom() + delta);
  }

  onMouseDown(e: MouseEvent): void {
    if (this.zoom() <= 1) return;
    this.dragging = true;
    this.dragStartX = e.clientX;
    this.dragStartY = e.clientY;
    this.dragOriginX = this.translateX();
    this.dragOriginY = this.translateY();
    e.preventDefault();
  }

  @HostListener('document:mousemove', ['$event'])
  onMouseMove(e: MouseEvent): void {
    if (!this.dragging) return;
    const scale = this.zoom();
    const dx = (e.clientX - this.dragStartX) / scale;
    const dy = (e.clientY - this.dragStartY) / scale;
    this.translateX.set(this.dragOriginX + dx);
    this.translateY.set(this.dragOriginY + dy);
  }

  @HostListener('document:mouseup')
  onMouseUp(): void {
    this.dragging = false;
  }

  zoomIn(): void { this.setZoom(this.zoom() + 0.25); }
  zoomOut(): void { this.setZoom(this.zoom() - 0.25); }
  resetView(): void { this.zoom.set(1); this.translateX.set(0); this.translateY.set(0); }

  private setZoom(value: number): void {
    const clamped = Math.min(5, Math.max(1, Math.round(value * 100) / 100));
    this.zoom.set(clamped);
    if (clamped === 1) { this.translateX.set(0); this.translateY.set(0); }
  }

  formatFileSize(bytes: number): string {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    if (bytes < 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
    return `${(bytes / 1024 / 1024 / 1024).toFixed(2)} GB`;
  }
}
