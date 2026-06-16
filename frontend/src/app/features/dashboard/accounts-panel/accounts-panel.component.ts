import { Component, OnInit, computed, inject, output } from '@angular/core';
import { DatePipe } from '@angular/common';
import { Router } from '@angular/router';
import { Store } from '@ngxs/store';
import { AccountResponse } from '../../../core/api/generated/model/accountResponse';
import { AccountsService } from '../../../core/api/generated/accounts/accounts.service';
import { AccountsState } from '../../../state/accounts/accounts.state';
import { LoadAccounts, AccountRemoved } from '../../../state/accounts/accounts.actions';
import { AccountSessionService } from '../../../core/services/account-session.service';

@Component({
  selector: 'app-accounts-panel',
  standalone: true,
  imports: [DatePipe],
  template: `
    <section class="section accounts-panel-section">
      <div class="section-header">
        <h2>iCloud Accounts</h2>
      </div>

      @if (loadingAccounts()) {
        <div class="loading-state">
          <span class="spinner"></span> Loading accounts...
        </div>
      }

      @if (accountsError()) {
        <div class="error-state">{{ accountsError() }}</div>
      }

      @if (!loadingAccounts()) {
        <div class="accounts-list">
          @if (accounts().length === 0 && !accountsError()) {
            <div class="empty-state">
              No iCloud accounts configured. Add one to get started.
            </div>
          }

          @for (account of accounts(); track account.id) {
            <div class="card account-card" [class.account-card--active]="isActive(account)">
              <div class="account-card-header">
                <div class="account-info">
                  <span class="account-name">{{ account.displayName || account.appleId }}</span>
                  <span class="account-id">{{ account.appleId }}</span>
                </div>
                <div class="account-actions">
                  @if (isActive(account)) {
                    <span class="status-badge active-badge">Active account</span>
                  }
                  <span class="status-badge" [class]="account.hasActiveSession ? 'connected' : 'disconnected'">
                    {{ account.hasActiveSession ? 'Connected' : 'Inactive' }}
                  </span>
                  @if (!account.hasActiveSession) {
                    <button
                      class="btn btn-primary btn-sm reconnect-btn"
                      (click)="reconnectAccount(account)"
                      title="Reconnect account"
                    >
                      Połącz
                    </button>
                  }
                  @if (isActive(account)) {
                    <button
                      class="btn btn-secondary btn-sm logout-btn"
                      (click)="logout(account)"
                      title="Log out of this account"
                    >
                      Log out
                    </button>
                  }
                  <button
                    class="btn btn-danger btn-sm delete-btn"
                    (click)="deleteAccount(account)"
                    title="Delete this account (files on disk are kept)"
                  >
                    Delete
                  </button>
                </div>
              </div>
              @if (account.lastSyncAt) {
                <div class="account-meta">
                  <span class="meta-label">Last sync:</span>
                  <span class="meta-value">{{ account.lastSyncAt | date:'medium' }}</span>
                </div>
              }
            </div>
          }
        </div>
      }
    </section>
  `,
  styles: [`
    .section { margin-bottom: var(--spacing-10); }
    .accounts-panel-section { margin-top: 1rem; }
    .section-header {
      display: flex; align-items: center; justify-content: space-between;
      margin-bottom: var(--spacing-4);
    }
    .section-header h2 {
      font-size: var(--font-size-xl);
      font-weight: var(--font-weight-semibold);
      color: var(--color-text-primary);
    }
    .accounts-list { display: flex; flex-direction: column; gap: var(--spacing-3); }
    .account-card { padding: var(--spacing-4) var(--spacing-5); }
    .account-card--active {
      border: 2px solid var(--color-primary);
      box-shadow: 0 0 0 1px var(--color-primary);
    }
    .account-card-header {
      display: flex; align-items: center; justify-content: space-between; gap: var(--spacing-4);
    }
    .account-info { display: flex; flex-direction: column; gap: var(--spacing-1); }
    .account-name {
      font-size: var(--font-size-base); font-weight: var(--font-weight-semibold);
      color: var(--color-text-primary);
    }
    .account-id { font-size: var(--font-size-sm); color: var(--color-text-secondary); }
    .account-actions { display: flex; align-items: center; gap: var(--spacing-3); flex-wrap: wrap; }
    .active-badge {
      background: var(--color-primary); color: #fff;
      font-weight: var(--font-weight-semibold);
    }
    .account-meta {
      display: flex; gap: var(--spacing-2); font-size: var(--font-size-xs);
      color: var(--color-text-secondary); margin-top: var(--spacing-2);
    }
    .meta-label { font-weight: var(--font-weight-medium); }
    .btn-sm { padding: var(--spacing-1) var(--spacing-2); font-size: var(--font-size-xs); }
    .loading-state {
      display: flex; align-items: center; gap: var(--spacing-2);
      color: var(--color-text-secondary); font-size: var(--font-size-sm); padding: var(--spacing-6) 0;
    }
    .error-state {
      color: var(--color-danger); font-size: var(--font-size-sm); padding: var(--spacing-4);
      background: var(--color-danger-bg); border-radius: var(--radius-md);
      border: 1px solid var(--color-danger-light);
    }
    .empty-state {
      color: var(--color-text-muted); font-size: var(--font-size-sm);
      padding: var(--spacing-8) var(--spacing-4); text-align: center;
      background: var(--color-bg-secondary); border-radius: var(--radius-xl);
      border: 1px dashed var(--color-border);
    }
    .spinner {
      display: inline-block; width: 14px; height: 14px;
      border: 2px solid var(--color-border); border-top-color: var(--color-primary);
      border-radius: 50%; animation: spin 0.6s linear infinite;
    }
    @keyframes spin { to { transform: rotate(360deg); } }
  `],
})
export class AccountsPanelComponent implements OnInit {
  private store = inject(Store);
  private session = inject(AccountSessionService);
  private accountsApi = inject(AccountsService);
  private router = inject(Router);

  addAccountRequested = output<void>();
  reconnectRequested = output<AccountResponse>();

  /** Accounts are loaded and cached in AccountsState — no local duplication. */
  accounts = this.store.selectSignal(AccountsState.accounts);
  loadingAccounts = this.store.selectSignal(AccountsState.loading);
  accountsError = this.store.selectSignal(AccountsState.error);

  /** Id of the account whose session is currently active on this device. */
  activeAccountId = this.session.activeAccountId;

  ngOnInit(): void {
    // Only load if not already loaded — avoids redundant HTTP calls when
    // DashboardComponent already dispatched LoadAccounts.
    if (this.accounts().length === 0) {
      this.store.dispatch(new LoadAccounts());
    }
  }

  /**
   * Exposed so DashboardComponent can trigger a refresh after an account is added.
   * Prefer dispatching AccountAdded action when possible.
   */
  loadAccounts(): void {
    this.store.dispatch(new LoadAccounts());
  }

  reconnectAccount(account: AccountResponse): void {
    this.reconnectRequested.emit(account);
  }

  isActive(account: AccountResponse): boolean {
    return account.id === this.activeAccountId();
  }

  /**
   * Logs out of the active account: invalidates the iCloud session on the backend,
   * clears local session state and redirects to /login. The icloud_accounts record
   * is preserved. Files on disk are never touched.
   */
  logout(account: AccountResponse): void {
    this.accountsApi.logout(account.id).subscribe({
      next: () => this.completeLogout(),
      // Even if the backend call fails, drop local session so the user is not stuck.
      error: () => this.completeLogout(),
    });
  }

  /**
   * Deletes the icloud_accounts record. Photos on disk are kept; orphaned photo rows
   * remain and surface via the orphan-assignment task. Deleting the active account
   * logs out first, then redirects to /login. Deleting any other account just refreshes
   * the list and leaves the current session untouched.
   */
  deleteAccount(account: AccountResponse): void {
    const label = account.displayName || account.appleId;
    if (!confirm(`Delete account "${label}"? Photos on disk are kept.`)) {
      return;
    }
    const wasActive = this.isActive(account);
    this.accountsApi.deleteAccount(account.id).subscribe({
      next: () => {
        if (wasActive) {
          this.completeLogout();
        } else {
          this.store.dispatch(new AccountRemoved());
        }
      },
    });
  }

  private completeLogout(): void {
    this.session.clearSession();
    this.router.navigate(['/login']);
  }
}
