import { Component, OnInit, inject, output } from '@angular/core';
import { DatePipe } from '@angular/common';
import { Store } from '@ngxs/store';
import { AccountResponse } from '../../../core/api/generated/model/accountResponse';
import { AccountsState } from '../../../state/accounts/accounts.state';
import { LoadAccounts } from '../../../state/accounts/accounts.actions';

@Component({
  selector: 'app-accounts-panel',
  standalone: true,
  imports: [DatePipe],
  templateUrl: './accounts-panel.component.html',
  styleUrl: './accounts-panel.component.scss'
})
export class AccountsPanelComponent implements OnInit {
  private store = inject(Store);

  addAccountRequested = output<void>();
  reconnectRequested = output<AccountResponse>();

  /** Accounts are loaded and cached in AccountsState — no local duplication. */
  accounts = this.store.selectSignal(AccountsState.accounts);
  loadingAccounts = this.store.selectSignal(AccountsState.loading);
  accountsError = this.store.selectSignal(AccountsState.error);

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
   *
   * TODO: Test that calling loadAccounts() twice in quick succession doesn't
   * result in a doubled list.
   */
  loadAccounts(): void {
    this.store.dispatch(new LoadAccounts());
  }

  reconnectAccount(account: AccountResponse): void {
    this.reconnectRequested.emit(account);
  }
}
