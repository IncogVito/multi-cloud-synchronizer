import { Injectable, signal } from '@angular/core';

export type ToastType = 'info' | 'success' | 'warning' | 'error';

export interface Toast {
  id: number;
  message: string;
  type: ToastType;
}

@Injectable({ providedIn: 'root' })
export class ToastService {
  private nextId = 1;
  private _toasts = signal<Toast[]>([]);
  toasts = this._toasts.asReadonly();

  show(message: string, type: ToastType = 'info', durationMs = 4000): void {
    const id = this.nextId++;
    this._toasts.update(arr => [...arr, { id, message, type }]);
    if (durationMs > 0) {
      setTimeout(() => this.dismiss(id), durationMs);
    }
  }

  info(msg: string, d?: number) { this.show(msg, 'info', d); }
  success(msg: string, d?: number) { this.show(msg, 'success', d); }
  warning(msg: string, d?: number) { this.show(msg, 'warning', d); }
  error(msg: string, d?: number) { this.show(msg, 'error', d ?? 6000); }

  dismiss(id: number): void {
    this._toasts.update(arr => arr.filter(t => t.id !== id));
  }
}
