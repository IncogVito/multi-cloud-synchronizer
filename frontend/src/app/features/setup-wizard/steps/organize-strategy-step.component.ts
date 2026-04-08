import { Component, input, output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { OrganizeBy } from '../../../core/models/sync-config.model';

@Component({
  selector: 'app-organize-strategy-step',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <div class="step-body">
      <p class="step-title">Strategia segregacji zdjęć</p>
      <p class="step-hint">Jak chcesz organizować pobrane zdjęcia na dysku?</p>

      <div class="options">
        <label class="option" [class.selected]="selected === 'YEAR'">
          <input type="radio" name="organize" value="YEAR" [(ngModel)]="selected" />
          <div class="option-content">
            <span class="option-label">Foldery roczne</span>
            <pre class="tree">Zdjecia/
├── 2023/
└── 2024/</pre>
          </div>
        </label>

        <label class="option" [class.selected]="selected === 'MONTH'">
          <input type="radio" name="organize" value="MONTH" [(ngModel)]="selected" />
          <div class="option-content">
            <span class="option-label">Foldery miesięczne <span class="badge">domyślny</span></span>
            <pre class="tree">Zdjecia/
├── 2023/
│   ├── 03/
│   └── 04/
└── 2024/</pre>
          </div>
        </label>
      </div>

      <div class="actions">
        <button class="btn btn-ghost" (click)="back.emit()">&#8592; Wstecz</button>
        <button class="btn btn-primary" (click)="confirm()">
          Dalej &rarr;
        </button>
      </div>
    </div>
  `,
  styles: [`
    .step-body { display: flex; flex-direction: column; gap: 1.25rem; }

    .step-title { font-weight: 600; font-size: 1rem; margin: 0; }
    .step-hint { color: #6b7280; font-size: 0.875rem; margin: 0; }

    .options { display: flex; flex-direction: column; gap: 0.75rem; }

    .option {
      display: flex;
      align-items: flex-start;
      gap: 0.75rem;
      padding: 1rem;
      border: 2px solid #e5e7eb;
      border-radius: 10px;
      cursor: pointer;
      transition: border-color 0.15s, background 0.15s;

      &:hover { border-color: #93c5fd; background: #f8faff; }
      &.selected { border-color: #3b82f6; background: #eff6ff; }

      input[type=radio] { margin-top: 0.2rem; accent-color: #3b82f6; }
    }

    .option-content { display: flex; flex-direction: column; gap: 0.4rem; }

    .option-label { font-weight: 500; font-size: 0.95rem; display: flex; align-items: center; gap: 0.5rem; }

    .badge {
      font-size: 0.7rem;
      background: #dbeafe;
      color: #1d4ed8;
      padding: 0.15rem 0.5rem;
      border-radius: 999px;
      font-weight: 500;
    }

    .tree {
      font-family: monospace;
      font-size: 0.8rem;
      color: #6b7280;
      margin: 0;
      background: transparent;
    }

    .actions { display: flex; gap: 0.75rem; justify-content: space-between; }

    .btn {
      padding: 0.5rem 1.25rem;
      border-radius: 8px;
      font-size: 0.875rem;
      font-weight: 500;
      cursor: pointer;
      border: none;
    }

    .btn-primary { background: #3b82f6; color: #fff; &:hover { background: #2563eb; } }
    .btn-ghost { background: transparent; color: #374151; border: 1px solid #d1d5db; &:hover { background: #f9fafb; } }
  `]
})
export class OrganizeStrategyStepComponent {
  organizeBy = input<OrganizeBy>('MONTH');
  strategySelected = output<OrganizeBy>();
  back = output<void>();

  get selected(): OrganizeBy { return this.organizeBy(); }
  set selected(v: OrganizeBy) { /* handled via ngModel — emitted on confirm */ this._selected = v; }

  private _selected: OrganizeBy | null = null;

  confirm(): void {
    this.strategySelected.emit(this._selected ?? this.organizeBy());
  }
}
