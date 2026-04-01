import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ReactiveFormsModule, FormBuilder, FormGroup, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { AuthService } from '../../core/services/auth.service';
import { AccountService } from '../../core/services/account.service';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule],
  templateUrl: './login.component.html',
  styleUrls: ['./login.component.scss']
})
export class LoginComponent {
  loginForm: FormGroup;
  twoFaForm: FormGroup;
  isLoading = false;
  errorMessage = '';
  
  // 2FA state
  requires2fa = false;
  sessionId = '';

  constructor(
    private fb: FormBuilder,
    private authService: AuthService,
    private accountService: AccountService,
    private router: Router
  ) {
    this.loginForm = this.fb.group({
      username: ['', [Validators.required]],
      password: ['', [Validators.required]]
    });

    this.twoFaForm = this.fb.group({
      code: ['', [Validators.required, Validators.minLength(4)]]
    });
  }

  onLoginSubmit() {
    if (this.loginForm.invalid) {
      this.errorMessage = 'Please enter valid credentials';
      return;
    }

    this.isLoading = true;
    this.errorMessage = '';
    const { username, password } = this.loginForm.value;

    this.accountService.login({ appleId: username, password }).subscribe({
      next: (response) => {
        if (response.requires2fa) {
          // 2FA required - show 2FA form
          this.requires2fa = true;
          this.sessionId = response.sessionId || '';
          this.isLoading = false;
        } else {
          // Login successful without 2FA
          this.authService.setCredentials(username, password);
          this.isLoading = false;
          this.router.navigate(['/dashboard']);
        }
      },
      error: (err) => {
        this.isLoading = false;
        this.errorMessage = err.error?.message || 'Login failed. Please check your credentials.';
      }
    });
  }

  onTwoFaSubmit() {
    if (this.twoFaForm.invalid || !this.sessionId) {
      this.errorMessage = 'Please enter a valid 2FA code';
      return;
    }

    this.isLoading = true;
    this.errorMessage = '';
    const { code } = this.twoFaForm.value;
    const { username, password } = this.loginForm.value;

    this.accountService.submitTwoFa({ sessionId: this.sessionId, code }).subscribe({
      next: (response) => {
        if (response.authenticated) {
          // 2FA verified - store credentials and redirect
          this.authService.setCredentials(username, password);
          this.isLoading = false;
          this.router.navigate(['/dashboard']);
        } else {
          this.isLoading = false;
          this.errorMessage = response.message || '2FA verification failed';
        }
      },
      error: (err) => {
        this.isLoading = false;
        this.errorMessage = err.error?.message || 'Invalid 2FA code. Please try again.';
      }
    });
  }

  goBackToLogin() {
    this.requires2fa = false;
    this.sessionId = '';
    this.twoFaForm.reset();
    this.errorMessage = '';
  }
}
