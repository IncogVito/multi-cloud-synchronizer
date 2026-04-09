import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ToastService } from '../services/toast.service';

@Component({
  selector: 'app-toast-host',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="toast-host" role="status" aria-live="polite">
      @for (t of toastService.toasts(); track t.id) {
        <div class="toast" [class]="'toast-' + t.type" (click)="toastService.dismiss(t.id)">
          <span class="icon">
            @switch (t.type) {
              @case ('success') { &#10003; }
              @case ('error') { &#9888; }
              @case ('warning') { &#9888; }
              @default { &#8505; }
            }
          </span>
          <span class="msg">{{ t.message }}</span>
          <button class="close" (click)="toastService.dismiss(t.id); $event.stopPropagation()" aria-label="Zamknij">&times;</button>
        </div>
      }
    </div>
  `,
  styles: [`
    .toast-host {
      position: fixed;
      top: 1rem;
      right: 1rem;
      display: flex;
      flex-direction: column;
      gap: 0.5rem;
      z-index: 9999;
      pointer-events: none;
      max-width: min(420px, calc(100vw - 2rem));
    }
    .toast {
      pointer-events: auto;
      display: flex;
      align-items: flex-start;
      gap: 0.625rem;
      padding: 0.75rem 0.875rem;
      border-radius: 10px;
      box-shadow: 0 6px 24px rgba(15, 23, 42, 0.12), 0 1px 2px rgba(15, 23, 42, 0.08);
      background: #fff;
      border: 1px solid #e5e7eb;
      font-size: 0.875rem;
      line-height: 1.35;
      color: #1f2937;
      cursor: pointer;
      animation: toast-in 0.2s ease-out;
    }
    .toast .icon {
      font-weight: 700;
      font-size: 1rem;
      flex-shrink: 0;
      width: 1.25rem;
      text-align: center;
    }
    .toast .msg { flex: 1; white-space: pre-wrap; word-break: break-word; }
    .toast .close {
      background: transparent;
      border: none;
      font-size: 1.1rem;
      color: #9ca3af;
      cursor: pointer;
      padding: 0 0.25rem;
      line-height: 1;
      &:hover { color: #374151; }
    }
    .toast-success { border-left: 4px solid #22c55e; .icon { color: #22c55e; } }
    .toast-error { border-left: 4px solid #dc2626; .icon { color: #dc2626; } }
    .toast-warning { border-left: 4px solid #f59e0b; .icon { color: #f59e0b; } }
    .toast-info { border-left: 4px solid #3b82f6; .icon { color: #3b82f6; } }

    @keyframes toast-in {
      from { opacity: 0; transform: translateX(12px); }
      to { opacity: 1; transform: translateX(0); }
    }
  `]
})
export class ToastHostComponent {
  toastService = inject(ToastService);
}
