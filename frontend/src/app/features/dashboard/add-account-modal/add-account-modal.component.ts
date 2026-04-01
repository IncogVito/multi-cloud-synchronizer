import { Component, Input, Output, EventEmitter, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
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
  imports: [CommonModule, FormsModule],
  templateUrl: './add-account-modal.component.html',
  styleUrl: './add-account-modal.component.scss'
})
export class AddAccountModalComponent {
  private accountsService = inject(AccountsService);

  @Input() visible = false;
  @Output() closed = new EventEmitter<void>();
  @Output() accountAdded = new EventEmitter<void>();

  modalLoading = false;
  modalError = '';
  pendingAccountId: string | null = null;

  addForm: AddAccountForm = { appleId: '', password: '', twoFaCode: '' };

  close(): void {
    this.pendingAccountId = null;
    this.addForm = { appleId: '', password: '', twoFaCode: '' };
    this.modalError = '';
    this.closed.emit();
  }

  submit(): void {
    if (this.pendingAccountId) {
      this.submit2FA();
    } else {
      this.submitLogin();
    }
  }

  private submitLogin(): void {
    if (!this.addForm.appleId || !this.addForm.password) {
      this.modalError = 'Please fill in all fields.';
      return;
    }
    this.modalLoading = true;
    this.modalError = '';

    this.accountsService.login({ appleId: this.addForm.appleId, password: this.addForm.password }).subscribe({
      next: (resp: LoginResponse) => {
        this.modalLoading = false;
        if (resp.requires2fa) {
          this.pendingAccountId = resp.accountId;
        } else {
          this.close();
          this.accountAdded.emit();
        }
      },
      error: (err) => {
        this.modalLoading = false;
        this.modalError = err?.error?.message ?? err?.message ?? 'Login failed. Please check your credentials.';
      }
    });
  }

  private submit2FA(): void {
    if (!this.addForm.twoFaCode) {
      this.modalError = 'Please enter the verification code.';
      return;
    }
    this.modalLoading = true;
    this.modalError = '';

    this.accountsService.twoFa({ accountId: this.pendingAccountId!, code: this.addForm.twoFaCode }).subscribe({
      next: () => {
        this.modalLoading = false;
        this.close();
        this.accountAdded.emit();
      },
      error: (err) => {
        this.modalLoading = false;
        this.modalError = err?.error?.message ?? err?.message ?? '2FA verification failed.';
      }
    });
  }
}
