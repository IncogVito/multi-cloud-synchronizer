import { Injectable, signal } from '@angular/core';
import { Observable, catchError, map, of } from 'rxjs';
import { AccountService } from './account.service';

const STORAGE_KEY = 'active_account_id';

@Injectable({ providedIn: 'root' })
export class AccountSessionService {
  private readonly _activeAccountId = signal<string | null>(null);
  readonly activeAccountId = this._activeAccountId.asReadonly();

  constructor(private accountService: AccountService) {
    this.restoreFromStorage();
  }

  setActiveAccount(id: string): void {
    localStorage.setItem(STORAGE_KEY, id);
    this._activeAccountId.set(id);
  }

  clearSession(): void {
    localStorage.removeItem(STORAGE_KEY);
    this._activeAccountId.set(null);
  }

  validateSession(): Observable<boolean> {
    const id = this._activeAccountId();
    if (!id) return of(false);
    return this.accountService.getAccountStatus(id).pipe(
      map(account => account?.hasActiveSession === true),
      catchError(() => of(false))
    );
  }

  private restoreFromStorage(): void {
    const stored = localStorage.getItem(STORAGE_KEY);
    if (!stored) return;
    this._activeAccountId.set(stored);
    this.validateSession().subscribe(valid => {
      if (!valid) this.clearSession();
    });
  }
}
