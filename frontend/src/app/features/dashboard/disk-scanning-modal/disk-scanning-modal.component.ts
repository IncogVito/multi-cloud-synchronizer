import { Component, OnDestroy, OnInit, inject, output, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { DiskIndexingService, DiskIndexProgress } from '../../../core/services/disk-indexing.service';
import { Subscription } from 'rxjs';

@Component({
  selector: 'app-disk-scanning-modal',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="modal-backdrop">
      <div class="modal-card">
        @if (progress()?.phase === 'DONE') {
          <div class="done-block">
            <div class="done-icon">&#10003;</div>
            <p class="done-title">Skanowanie zakończone</p>
            <p class="done-sub">
              Zindeksowano <strong>{{ progress()!.scanned }}</strong> plików.
            </p>
            @if ((progress()!.newlyDeleted ?? 0) > 0) {
              <p class="done-sub deleted-notice">
                Oznaczono jako usunięte: <strong>{{ progress()!.newlyDeleted }}</strong> zdjęć (pliki zniknęły z dysku od ostatniego skanowania).
              </p>
            }
            <button class="btn btn-primary" (click)="close()">Przejdź do dashboardu</button>
          </div>
        } @else if (progress()?.phase === 'ERROR') {
          <div class="done-block">
            <div class="error-icon">!</div>
            <p class="done-title">Błąd skanowania</p>
            <p class="done-sub error-text">{{ progress()!.error || 'Nieznany błąd' }}</p>
            <button class="btn btn-primary" (click)="close()">Zamknij</button>
          </div>
        } @else {
          <div class="scanning-block">
            <div class="spinner"></div>
            <p class="scan-title">Trwa skanowanie dysku i zapisywanie danych</p>
            <p class="scan-sub">
              @if (progress() && progress()!.total > 0) {
                Przeskanowano {{ progress()!.scanned }} z {{ progress()!.total }} plików
                ({{ progress()!.percentComplete | number: '1.0-0' }}%)
              } @else if (progress() && progress()!.scanned > 0) {
                Znaleziono {{ progress()!.scanned }} plików...
              } @else {
                Wyszukiwanie plików multimedialnych...
              }
            </p>
            @if (progress() && progress()!.total > 0) {
              <div class="progress-bar-track">
                <div
                  class="progress-bar-fill"
                  [style.width.%]="progress()!.percentComplete"
                ></div>
              </div>
            }
          </div>
        }
      </div>
    </div>
  `,
  styles: [`
    .modal-backdrop {
      position: fixed;
      inset: 0;
      background: rgba(0, 0, 0, 0.55);
      display: flex;
      align-items: center;
      justify-content: center;
      z-index: 1000;
    }

    .modal-card {
      background: #fff;
      border-radius: 16px;
      box-shadow: 0 8px 40px rgba(0, 0, 0, 0.18);
      padding: 2.5rem;
      width: 100%;
      max-width: 440px;
      text-align: center;
    }

    .scanning-block {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 1.25rem;
    }

    .spinner {
      width: 56px;
      height: 56px;
      border: 5px solid #e5e7eb;
      border-top-color: #3b82f6;
      border-radius: 50%;
      animation: spin 0.9s linear infinite;
    }

    @keyframes spin { to { transform: rotate(360deg); } }

    .scan-title {
      font-size: 1.05rem;
      font-weight: 600;
      color: #111827;
      margin: 0;
    }

    .scan-sub {
      font-size: 0.875rem;
      color: #6b7280;
      margin: 0;
    }

    .progress-bar-track {
      width: 100%;
      height: 8px;
      background: #e5e7eb;
      border-radius: 4px;
      overflow: hidden;
    }

    .progress-bar-fill {
      height: 100%;
      background: #3b82f6;
      border-radius: 4px;
      transition: width 0.3s ease;
    }

    .done-block {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 1rem;
    }

    .done-icon {
      width: 56px;
      height: 56px;
      border-radius: 50%;
      background: #22c55e;
      color: #fff;
      display: flex;
      align-items: center;
      justify-content: center;
      font-size: 1.6rem;
      font-weight: bold;
    }

    .error-icon {
      width: 56px;
      height: 56px;
      border-radius: 50%;
      background: #ef4444;
      color: #fff;
      display: flex;
      align-items: center;
      justify-content: center;
      font-size: 1.6rem;
      font-weight: bold;
    }

    .done-title {
      font-size: 1.05rem;
      font-weight: 600;
      margin: 0;
    }

    .done-sub {
      color: #6b7280;
      font-size: 0.875rem;
      margin: 0;
    }

    .error-text { color: #dc2626; }
    .deleted-notice { color: #d97706; }

    .btn {
      padding: 0.6rem 1.5rem;
      border-radius: 8px;
      font-size: 0.875rem;
      font-weight: 500;
      border: none;
      cursor: pointer;
    }

    .btn-primary {
      background: #3b82f6;
      color: #fff;
    }

    .btn-primary:hover { background: #2563eb; }
  `]
})
export class DiskScanningModalComponent implements OnInit, OnDestroy {
  closed = output<void>();

  private diskIndexingService = inject(DiskIndexingService);
  private sub?: Subscription;

  progress = signal<DiskIndexProgress | null>(null);

  ngOnInit(): void {
    this.sub = this.diskIndexingService.progress$.subscribe(p => {
      this.progress.set(p);
    });
    this.diskIndexingService.subscribeToEvents();
  }

  ngOnDestroy(): void {
    this.sub?.unsubscribe();
  }

  close(): void {
    this.diskIndexingService.reset();
    this.closed.emit();
  }
}
