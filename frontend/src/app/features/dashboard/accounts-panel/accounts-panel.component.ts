import { Component, OnInit, inject, signal, output } from '@angular/core';
import { DatePipe } from '@angular/common';
import { AccountsService } from '../../../core/api/generated/accounts/accounts.service';
import { AccountResponse } from '../../../core/api/generated/model/accountResponse';

@Component({
  selector: 'app-accounts-panel',
  standalone: true,
  imports: [DatePipe],
  templateUrl: './accounts-panel.component.html',
  styleUrl: './accounts-panel.component.scss'
})
export class AccountsPanelComponent implements OnInit {
  private accountsService = inject(AccountsService);

  addAccountRequested = output<void>();
  reconnectRequested = output<AccountResponse>();

  accounts = signal<AccountResponse[]>([]);
  loadingAccounts = signal(false);
  accountsError = signal('');

  ngOnInit(): void {
    this.loadAccounts();
  }

  loadAccounts(): void {
    this.loadingAccounts.set(true);
    this.accountsError.set('');
    this.accountsService.listAccounts().subscribe({
      next: (accounts) => {
        this.accounts.set(accounts);
        this.loadingAccounts.set(false);
      },
      error: (err) => {
        this.accountsError.set('Failed to load accounts. ' + (err?.message ?? ''));
        this.loadingAccounts.set(false);
      }
    });
  }

  reconnectAccount(account: AccountResponse): void {
    this.reconnectRequested.emit(account);
  }

  deleteAccount(account: AccountResponse): void {
    if (!confirm(`Delete account "${account.appleId}"? This cannot be undone.`)) return;
    this.accountsService.deleteAccount(account.id).subscribe({
      next: () => {
        this.accounts.update(accs => accs.filter(a => a.id !== account.id));
      },
      error: (err) => {
        alert('Failed to delete account: ' + (err?.message ?? 'Unknown error'));
      }
    });
  }
}
