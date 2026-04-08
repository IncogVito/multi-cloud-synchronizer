import { Component, inject, OnInit, output, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { SetupWizardService } from '../../../core/services/setup-wizard.service';
import { BrowseEntry, DiskScanResult } from '../../../core/models/sync-config.model';

interface BreadcrumbSegment {
  label: string;
  path: string;
}

@Component({
  selector: 'app-folder-picker-step',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="step-body">
      <p class="step-title">Wybierz folder synchronizacji</p>

      <div class="breadcrumb">
        @for (seg of breadcrumbs(); track seg.path) {
          <span class="bc-item" (click)="navigateTo(seg.path)">{{ seg.label }}</span>
          <span class="bc-sep">/</span>
        }
      </div>

      @if (browseLoading()) {
        <div class="spinner"></div>
      } @else if (browseError()) {
        <p class="error-msg">{{ browseError() }}</p>
      } @else {
        <div class="folder-list">
          @for (entry of entries(); track entry.path) {
            <div
              class="folder-item"
              [class.selected]="selectedPath() === entry.path"
              (click)="selectFolder(entry)"
              (dblclick)="openFolder(entry)"
            >
              <span class="folder-icon">&#128193;</span>
              <div class="folder-info">
                <span class="folder-name">{{ entry.name }}</span>
                @if (entry.childCount > 0) {
                  <span class="folder-meta">{{ entry.childCount }} podfolderów</span>
                }
              </div>
              <button class="btn-open" (click)="$event.stopPropagation(); openFolder(entry)" title="Otwórz">
                &#8594;
              </button>
            </div>
          }
          @if (entries().length === 0) {
            <p class="empty-hint">Brak podfolderów w tym katalogu.</p>
          }
        </div>
      }

      @if (scanLoading()) {
        <div class="scan-info scanning">
          <div class="spinner-sm"></div>
          <span>Skanowanie folderu...</span>
        </div>
      } @else if (scanResult() !== null) {
        <div class="scan-info">
          <span class="scan-icon">&#128247;</span>
          <span>Znaleziono ~{{ scanResult()!.totalFiles | number }} zdjęć
            @if (scanExtSummary()) { ({{ scanExtSummary() }}) }
          </span>
        </div>
      }

      <div class="actions">
        <button class="btn btn-ghost" (click)="back.emit()">&#8592; Wstecz</button>
        <button
          class="btn btn-primary"
          [disabled]="!selectedPath() || scanLoading()"
          (click)="confirmFolder()"
        >
          Wybierz ten folder &rarr;
        </button>
      </div>
    </div>
  `,
  styles: [`
    .step-body { display: flex; flex-direction: column; gap: 1rem; }

    .step-title { font-weight: 600; font-size: 1rem; margin: 0; }

    .breadcrumb {
      display: flex;
      align-items: center;
      flex-wrap: wrap;
      gap: 0.25rem;
      font-size: 0.85rem;
      color: #6b7280;
    }

    .bc-item {
      cursor: pointer;
      color: #3b82f6;
      &:hover { text-decoration: underline; }
    }

    .bc-sep { color: #d1d5db; }

    .folder-list {
      display: flex;
      flex-direction: column;
      gap: 0.35rem;
      max-height: 300px;
      overflow-y: auto;
      border: 1px solid #e5e7eb;
      border-radius: 8px;
      padding: 0.5rem;
    }

    .folder-item {
      display: flex;
      align-items: center;
      gap: 0.75rem;
      padding: 0.6rem 0.75rem;
      border-radius: 6px;
      cursor: pointer;
      transition: background 0.1s;

      &:hover { background: #f3f4f6; }
      &.selected { background: #eff6ff; border: 1px solid #bfdbfe; }
    }

    .folder-icon { font-size: 1.1rem; }

    .folder-info {
      flex: 1;
      display: flex;
      flex-direction: column;
      gap: 0.1rem;
    }

    .folder-name { font-size: 0.9rem; font-weight: 500; }
    .folder-meta { font-size: 0.75rem; color: #9ca3af; }

    .btn-open {
      background: none;
      border: none;
      cursor: pointer;
      color: #9ca3af;
      font-size: 1rem;
      padding: 0.25rem;
      &:hover { color: #3b82f6; }
    }

    .empty-hint { color: #9ca3af; font-size: 0.85rem; text-align: center; padding: 1rem 0; }

    .scan-info {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      padding: 0.75rem;
      background: #f0fdf4;
      border: 1px solid #d1fae5;
      border-radius: 8px;
      font-size: 0.875rem;
      color: #16a34a;

      &.scanning { background: #f8fafc; border-color: #e2e8f0; color: #64748b; }
    }

    .scan-icon { font-size: 1.1rem; }

    .actions { display: flex; gap: 0.75rem; justify-content: space-between; flex-wrap: wrap; }

    .btn {
      padding: 0.5rem 1.25rem;
      border-radius: 8px;
      font-size: 0.875rem;
      font-weight: 500;
      cursor: pointer;
      border: none;
      &:disabled { opacity: 0.5; cursor: not-allowed; }
    }

    .btn-primary { background: #3b82f6; color: #fff; &:hover:not(:disabled) { background: #2563eb; } }
    .btn-ghost { background: transparent; color: #374151; border: 1px solid #d1d5db; &:hover { background: #f9fafb; } }

    .spinner {
      width: 28px;
      height: 28px;
      border: 3px solid #e5e7eb;
      border-top-color: #3b82f6;
      border-radius: 50%;
      animation: spin 0.8s linear infinite;
      margin: 1rem auto;
    }

    .spinner-sm {
      width: 16px;
      height: 16px;
      border: 2px solid #e2e8f0;
      border-top-color: #64748b;
      border-radius: 50%;
      animation: spin 0.8s linear infinite;
    }

    @keyframes spin { to { transform: rotate(360deg); } }

    .error-msg { color: #dc2626; font-size: 0.875rem; }
  `]
})
export class FolderPickerStepComponent implements OnInit {
  folderSelected = output<{ path: string; scanResult: DiskScanResult }>();
  back = output<void>();

  private wizardService = inject(SetupWizardService);

  entries = signal<BrowseEntry[]>([]);
  breadcrumbs = signal<BreadcrumbSegment[]>([]);
  selectedPath = signal<string | null>(null);
  browseLoading = signal(true);
  browseError = signal<string | null>(null);
  scanLoading = signal(false);
  scanResult = signal<DiskScanResult | null>(null);
  scanExtSummary = signal<string | null>(null);

  ngOnInit(): void {
    this.load(null);
  }

  load(path: string | null): void {
    this.browseLoading.set(true);
    this.browseError.set(null);
    this.selectedPath.set(null);
    this.scanResult.set(null);
    this.scanExtSummary.set(null);

    this.wizardService.browse(path ?? undefined).subscribe({
      next: (resp) => {
        this.entries.set(resp.entries);
        this.updateBreadcrumbs(resp.path);
        this.browseLoading.set(false);
      },
      error: (err) => {
        this.browseError.set(err?.error?.error || 'Błąd ładowania folderów');
        this.browseLoading.set(false);
      }
    });
  }

  selectFolder(entry: BrowseEntry): void {
    this.selectedPath.set(entry.path);
    this.runScan(entry.path);
  }

  openFolder(entry: BrowseEntry): void {
    this.load(entry.path);
  }

  navigateTo(path: string): void {
    this.load(path);
  }

  confirmFolder(): void {
    const path = this.selectedPath();
    const scan = this.scanResult();
    if (!path) return;
    this.folderSelected.emit({
      path,
      scanResult: scan ?? { totalFiles: 0, byExtension: {}, deepestLevel: 0 },
    });
  }

  private runScan(path: string): void {
    this.scanLoading.set(true);
    this.scanResult.set(null);
    this.scanExtSummary.set(null);

    this.wizardService.scan(path).subscribe({
      next: (result) => {
        this.scanResult.set(result);
        this.scanExtSummary.set(this.buildExtSummary(result));
        this.scanLoading.set(false);
      },
      error: () => {
        this.scanLoading.set(false);
      }
    });
  }

  private buildExtSummary(r: DiskScanResult): string {
    return Object.entries(r.byExtension)
      .sort(([, a], [, b]) => b - a)
      .slice(0, 4)
      .map(([ext, count]) => `${ext}: ${count}`)
      .join(', ');
  }

  private updateBreadcrumbs(fullPath: string): void {
    const parts = fullPath.split('/').filter(Boolean);
    const segments: BreadcrumbSegment[] = [];
    let cumPath = '';
    for (const part of parts) {
      cumPath += '/' + part;
      segments.push({ label: part, path: cumPath });
    }
    this.breadcrumbs.set(segments);
  }
}
