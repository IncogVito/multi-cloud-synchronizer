import { Component, OnInit, inject, signal } from '@angular/core';
import { DiskIndexingService, ReorganizePreview, ReorganizeResult } from '../../../core/services/disk-indexing.service';
import { SyncService } from '../../../core/services/sync.service';
import { AccountService } from '../../../core/services/account.service';
import { AccountResponse } from '../../../core/api/generated/model/accountResponse';

@Component({
  selector: 'app-reorganize-section',
  standalone: true,
  template: `
    @if (preview()) {
      <section class="section reorganize-section">
        <div class="reorg-card">
          <div class="reorg-header">
            <h3 class="reorg-title">Nieposegregowane zdjęcia</h3>
          </div>
          <p class="reorg-sub">
            Znaleziono <strong>{{ preview()!.unorganizedCount }}</strong> zdjęć poza strukturą
            <code>rok/miesiąc</code>. Segregacja przeniesie je do folderów:
            <strong>{{ folderSample() }}</strong>.
          </p>
          @if (preview()!.samples.length > 0) {
            <p class="reorg-samples">
              Przykłady: {{ preview()!.samples.join(', ') }}
            </p>
          }
          <div class="reorg-actions">
            <button class="btn btn-primary btn-sm" [disabled]="reorganizing()" (click)="startReorganize()">
              {{ reorganizing() ? 'Segregowanie...' : 'Segreguj teraz' }}
            </button>
            <button class="btn btn-ghost btn-sm" [disabled]="reorganizing()" (click)="dismiss()">
              Nie teraz
            </button>
          </div>
        </div>
      </section>
    }

    @if (result()) {
      <section class="section reorganize-section">
        <div class="reorg-card reorg-card--done">
          <p class="reorg-title">Segregowanie zakończone</p>
          <p class="reorg-sub">
            Przeniesiono <strong>{{ result()!.moved }}</strong> plików do struktury rok/miesiąc.
            @if (result()!.errors > 0) {
              <span class="err"> Błędów: {{ result()!.errors }}</span>
            }
          </p>
        </div>
      </section>
    }
  `,
  styles: [`
    .reorganize-section { margin-top: 0.75rem; }
    .reorg-card {
      padding: 1rem 1.25rem;
      border: 1px solid #fde68a;
      border-radius: 10px;
      background: #fffbeb;
    }
    .reorg-card--done { border-color: #86efac; background: #f0fdf4; }
    .reorg-header { display: flex; align-items: center; gap: 0.5rem; }
    .reorg-title { font-weight: 600; font-size: 0.95rem; margin: 0 0 0.4rem; }
    .reorg-sub { color: #6b7280; font-size: 0.875rem; margin: 0 0 0.5rem; }
    .reorg-samples { color: #9ca3af; font-size: 0.78rem; margin: 0 0 0.75rem; }
    .reorg-actions { display: flex; gap: 0.5rem; }
    .btn { padding: 0.5rem 1.25rem; border-radius: 8px; font-size: 0.875rem; font-weight: 500; border: none; cursor: pointer; }
    .btn-sm { padding: 0.375rem 0.875rem; font-size: 0.8rem; }
    .btn-primary { background: #3b82f6; color: #fff; }
    .btn-primary:disabled { opacity: 0.6; cursor: not-allowed; }
    .btn-ghost { background: transparent; border: 1px solid #d1d5db; color: #374151; }
    .btn-ghost:disabled { opacity: 0.6; cursor: not-allowed; }
    .err { color: #dc2626; }
    code { background: #f3f4f6; border-radius: 4px; padding: 0 0.3rem; font-size: 0.82rem; }
  `]
})
export class ReorganizeSectionComponent implements OnInit {
  private diskIndexingService = inject(DiskIndexingService);
  private syncService = inject(SyncService);
  private accountService = inject(AccountService);

  preview = signal<ReorganizePreview | null>(null);
  result = signal<ReorganizeResult | null>(null);
  reorganizing = signal(false);

  folderSample = () => {
    const p = this.preview();
    if (!p) return '';
    const shown = p.estimatedFolders.slice(0, 3).join(', ');
    return p.estimatedFolders.length > 3 ? shown + '...' : shown;
  };

  ngOnInit(): void {
    this.loadPreview();
  }

  loadPreview(): void {
    // Try device-level reorganize preview (covers both LOCAL and cloud-synced photos)
    this.diskIndexingService.reorganizePreview().subscribe({
      next: (p) => {
        if (p.unorganizedCount > 0) this.preview.set(p);
      },
      error: () => {
        // Fall back to account-based preview if no active context
        this.loadAccountPreview();
      }
    });
  }

  private loadAccountPreview(): void {
    this.accountService.listAccounts().subscribe({
      next: (accounts: AccountResponse[]) => {
        const primary = accounts.find(a => a.hasActiveSession) ?? accounts[0] ?? null;
        if (primary) {
          this.syncService.reorganizePreview(primary.id).subscribe({
            next: (p) => {
              if (p.unorganizedCount > 0) this.preview.set(p);
            },
            error: () => {}
          });
        }
      }
    });
  }

  startReorganize(): void {
    this.reorganizing.set(true);
    this.preview.set(null);
    this.diskIndexingService.reorganize().subscribe({
      next: (r) => {
        this.reorganizing.set(false);
        this.result.set(r);
      },
      error: () => {
        this.reorganizing.set(false);
        // Fall back to account-based reorganize
        this.accountService.listAccounts().subscribe({
          next: (accounts: AccountResponse[]) => {
            const primary = accounts.find(a => a.hasActiveSession) ?? accounts[0] ?? null;
            if (primary) {
              this.syncService.reorganize(primary.id).subscribe({
                next: (r) => { this.reorganizing.set(false); this.result.set(r); },
                error: () => this.reorganizing.set(false)
              });
            }
          }
        });
      }
    });
  }

  dismiss(): void {
    this.preview.set(null);
  }
}
