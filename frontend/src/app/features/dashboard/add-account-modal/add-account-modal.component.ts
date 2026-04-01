import { Component, inject, signal, input, output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AccountsService } from '../../../core/api/generated/accounts/accounts.service';
import { LoginResponse } from '../../../core/api/generated/model/loginResponse';

interface AddAccountForm {
  appleId: string;
  password: string;
  twoFaCode: string;
}

@Component({
  selector: 'app-add-account-modal',
  standalone: true,
  imports: [FormsModule],
  templateUrl: './add-account-modal.component.html',
  styleUrl: './add-account-modal.component.scss'
})
export class AddAccountModalComponent {
  private accountsService = inject(AccountsService);

  visible = input(false);
  closed = output<void>();
  accountAdded = output<void>();

  modalLoading = signal(false);
  modalError = signal('');
  pendingAccountId = signal<string | null>(null);

  addForm: AddAccountForm = { appleId: '', password: '', twoFaCode: '' };

  close(): void {
    this.pendingAccountId.set(null);
    this.addForm = { appleId: '', password: '', twoFaCode: '' };
    this.modalError.set('');
    this.closed.emit();
  }

  submit(): void {
    if (this.pendingAccountId()) {
      this.submit2FA();
    } else {
      this.submitLogin();
    }
  }

  private submitLogin(): void {
    if (!this.addForm.appleId || !this.addForm.password) {
      this.modalError.set('Please fill in all fields.');
      return;
    }
    this.modalLoading.set(true);
    this.modalError.set('');

    this.accountsService.login({ appleId: this.addForm.appleId, password: this.addForm.password }).subscribe({
      next: (resp: LoginResponse) => {
        this.modalLoading.set(false);
        if (resp.requires2fa) {
          this.pendingAccountId.set(resp.accountId ?? null);
        } else {
          this.close();
          this.accountAdded.emit();
        }
      },
      error: (err) => {
        this.modalLoading.set(false);
        this.modalError.set(err?.error?.message ?? err?.message ?? 'Login failed. Please check your credentials.');
      }
    });
  }

  private submit2FA(): void {
    if (!this.addForm.twoFaCode) {
      this.modalError.set('Please enter the verification code.');
      return;
    }
    this.modalLoading.set(true);
    this.modalError.set('');

    this.accountsService.twoFa({ accountId: this.pendingAccountId()!, code: this.addForm.twoFaCode }).subscribe({
      next: () => {
        this.modalLoading.set(false);
        this.close();
        this.accountAdded.emit();
      },
      error: (err) => {
        this.modalLoading.set(false);
        this.modalError.set(err?.error?.message ?? err?.message ?? '2FA verification failed.');
      }
    });
  }
}
