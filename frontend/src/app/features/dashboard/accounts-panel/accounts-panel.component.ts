import { Component, OnInit, Output, EventEmitter, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { AccountsService } from '../../../core/api/generated/accounts/accounts.service';
import { AccountResponse } from '../../../core/api/generated/model/accountResponse';

@Component({
  selector: 'app-accounts-panel',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './accounts-panel.component.html',
  styleUrl: './accounts-panel.component.scss'
})
export class AccountsPanelComponent implements OnInit {
  private accountsService = inject(AccountsService);

  @Output() addAccountRequested = new EventEmitter<void>();

  accounts: AccountResponse[] = [];
  loadingAccounts = false;
  accountsError = '';

  ngOnInit(): void {
    this.loadAccounts();
  }

  loadAccounts(): void {
    this.loadingAccounts = true;
    this.accountsError = '';
    this.accountsService.listAccounts().subscribe({
      next: (accounts) => {
        this.accounts = accounts;
        this.loadingAccounts = false;
      },
      error: (err) => {
        this.accountsError = 'Failed to load accounts. ' + (err?.message ?? '');
        this.loadingAccounts = false;
      }
    });
  }

  deleteAccount(account: AccountResponse): void {
    if (!confirm(`Delete account "${account.appleId}"? This cannot be undone.`)) return;
    this.accountsService.deleteAccount(account.id).subscribe({
      next: () => {
        this.accounts = this.accounts.filter(a => a.id !== account.id);
      },
      error: (err) => {
        alert('Failed to delete account: ' + (err?.message ?? 'Unknown error'));
      }
    });
  }
}
