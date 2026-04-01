import { Component, signal, inject } from '@angular/core';
import { ReactiveFormsModule, FormBuilder, FormGroup, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { AuthService } from '../../core/services/auth.service';
import { AccountService } from '../../core/services/account.service';
import { AccountResponse } from '../../core/api/generated/model';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [ReactiveFormsModule],
  templateUrl: './login.component.html',
  styleUrls: ['./login.component.scss']
})
export class LoginComponent {
  private fb = inject(FormBuilder);
  private authService = inject(AuthService);
  private accountService = inject(AccountService);
  private router = inject(Router);

  accounts = signal<AccountResponse[]>([]);
  selectedAccount = signal<AccountResponse | null>(null);
  isLoadingAccounts = signal(true);
  isLoading = signal(false);
  errorMessage = signal('');
  requires2fa = signal(false);
  sessionId = signal('');
  isAddingNewAccount = signal(false);

  loginForm: FormGroup = this.fb.group({
    password: ['', [Validators.required]]
  });

  twoFaForm: FormGroup = this.fb.group({
    code: ['', [Validators.required, Validators.minLength(4)]]
  });

  newAccountForm: FormGroup = this.fb.group({
    username: ['', [Validators.required]],
    password: ['', [Validators.required]]
  });

  constructor() {
    this.loadAccounts();
  }

  loadAccounts(): void {
    this.isLoadingAccounts.set(true);
    this.accountService.listAccounts().subscribe({
      next: (accounts) => {
        this.accounts.set(accounts || []);
        this.isLoadingAccounts.set(false);
      },
      error: () => {
        this.accounts.set([]);
        this.isLoadingAccounts.set(false);
      }
    });
  }

  selectAccount(account: AccountResponse): void {
    this.selectedAccount.set(account);
    this.isAddingNewAccount.set(false);
    this.loginForm.reset();
    this.loginForm.get('password')?.enable();
    this.errorMessage.set('');
  }

  clearSelection(): void {
    this.selectedAccount.set(null);
    this.isAddingNewAccount.set(false);
    this.loginForm.reset();
    this.twoFaForm.reset();
    this.requires2fa.set(false);
    this.errorMessage.set('');
  }

  showAddNewAccount(): void {
    this.selectedAccount.set(null);
    this.isAddingNewAccount.set(true);
    this.newAccountForm.reset();
    this.loginForm.reset();
    this.errorMessage.set('');
  }

  onLoginSubmit(): void {
    if (!this.selectedAccount() || this.loginForm.invalid) {
      this.errorMessage.set('Proszę wpisać hasło');
      return;
    }

    this.isLoading.set(true);
    this.errorMessage.set('');
    const password = this.loginForm.get('password')?.value;

    this.accountService.login({ appleId: this.selectedAccount()!.appleId, password }).subscribe({
      next: (response) => {
        if (response.requires2fa) {
          this.requires2fa.set(true);
          this.sessionId.set(response.sessionId || '');
          this.isLoading.set(false);
        } else {
          this.authService.setCredentials(this.selectedAccount()!.appleId, password);
          this.isLoading.set(false);
          this.router.navigate(['/dashboard']);
        }
      },
      error: (err) => {
        this.isLoading.set(false);
        this.errorMessage.set(err.error?.message || 'Logowanie nieudane. Sprawdź credentials.');
      }
    });
  }

  onTwoFaSubmit(): void {
    if (this.twoFaForm.invalid || !this.sessionId()) {
      this.errorMessage.set('Proszę wpisać prawidłowy kod 2FA');
      return;
    }

    this.isLoading.set(true);
    this.errorMessage.set('');
    const code = this.twoFaForm.get('code')?.value;
    const password = this.loginForm.get('password')?.value;

    this.accountService.submitTwoFa({ sessionId: this.sessionId(), code }).subscribe({
      next: (response) => {
        if (response.authenticated) {
          this.authService.setCredentials(this.selectedAccount()!.appleId, password);
          this.isLoading.set(false);
          this.router.navigate(['/dashboard']);
        } else {
          this.isLoading.set(false);
          this.errorMessage.set(response.message || 'Weryfikacja 2FA nieudana');
        }
      },
      error: (err) => {
        this.isLoading.set(false);
        this.errorMessage.set(err.error?.message || 'Nieprawidłowy kod 2FA. Spróbuj ponownie.');
      }
    });
  }

  onAddNewAccountSubmit(): void {
    if (this.newAccountForm.invalid) {
      this.errorMessage.set('Proszę wpisać hasło i nową nazwę użytkownika');
      return;
    }

    this.isLoading.set(true);
    this.errorMessage.set('');
    const { username, password } = this.newAccountForm.value;

    this.accountService.login({ appleId: username, password }).subscribe({
      next: (response) => {
        if (response.requires2fa) {
          this.selectedAccount.set({
            id: '', displayName: username, appleId: username,
            hasActiveSession: false, lastSyncAt: '', createdAt: ''
          });
          this.requires2fa.set(true);
          this.sessionId.set(response.sessionId || '');
          this.isLoading.set(false);
        } else {
          this.authService.setCredentials(username, password);
          this.isLoading.set(false);
          this.router.navigate(['/dashboard']);
        }
      },
      error: (err) => {
        this.isLoading.set(false);
        this.errorMessage.set(err.error?.message || 'Dodawanie konta nieudane. Sprawdź dane.');
      }
    });
  }

  goBackToAccountSelection(): void {
    this.selectedAccount.set(null);
    this.isAddingNewAccount.set(false);
    this.requires2fa.set(false);
    this.sessionId.set('');
    this.loginForm.reset();
    this.twoFaForm.reset();
    this.newAccountForm.reset();
    this.errorMessage.set('');
  }
}
