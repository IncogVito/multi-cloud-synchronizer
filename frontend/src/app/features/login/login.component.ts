import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ReactiveFormsModule, FormBuilder, FormGroup, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { AuthService } from '../../core/services/auth.service';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule],
  template: `
    <div class="login-wrapper">
      <div class="card login-card">
        <h2>Cloud Synchronizer</h2>
        <p class="subtitle">Sign in to continue</p>

        <form [formGroup]="loginForm" (ngSubmit)="onSubmit()">
          <div class="form-group">
            <label for="username">Username</label>
            <input
              id="username"
              type="text"
              formControlName="username"
              placeholder="Enter username"
              autocomplete="username"
            />
          </div>

          <div class="form-group">
            <label for="password">Password</label>
            <input
              id="password"
              type="password"
              formControlName="password"
              placeholder="Enter password"
              autocomplete="current-password"
            />
          </div>

          <div class="error-message" *ngIf="errorMessage">
            {{ errorMessage }}
          </div>

          <button
            type="submit"
            class="btn btn-primary submit-btn"
            [disabled]="loginForm.invalid || isLoading"
          >
            {{ isLoading ? 'Signing in...' : 'Sign In' }}
          </button>
        </form>
      </div>
    </div>
  `,
  styles: [`
    .login-wrapper {
      min-height: 100vh;
      display: flex;
      align-items: center;
      justify-content: center;
      background-color: var(--color-bg-secondary);
      padding: var(--spacing-6);
    }

    .login-card {
      width: 100%;
      max-width: 400px;
    }

    h2 {
      text-align: center;
      margin-bottom: var(--spacing-1);
    }

    .subtitle {
      text-align: center;
      color: var(--color-text-secondary);
      font-size: var(--font-size-sm);
      margin-bottom: var(--spacing-6);
    }

    .form-group {
      margin-bottom: var(--spacing-4);

      label {
        display: block;
        font-size: var(--font-size-sm);
        font-weight: var(--font-weight-medium);
        color: var(--color-text-primary);
        margin-bottom: var(--spacing-1);
      }

      input {
        width: 100%;
        padding: var(--spacing-2) var(--spacing-3);
        font-size: var(--font-size-base);
        border: 1px solid var(--color-border);
        border-radius: var(--radius-md);
        background-color: var(--color-bg-primary);
        color: var(--color-text-primary);
        transition: border-color var(--transition-fast);

        &:focus {
          outline: none;
          border-color: var(--color-primary);
          box-shadow: 0 0 0 3px var(--color-primary-bg);
        }
      }
    }

    .error-message {
      color: var(--color-danger);
      font-size: var(--font-size-sm);
      margin-bottom: var(--spacing-4);
      padding: var(--spacing-2) var(--spacing-3);
      background-color: var(--color-danger-bg);
      border-radius: var(--radius-md);
    }

    .submit-btn {
      width: 100%;
      padding: var(--spacing-3);
    }
  `]
})
export class LoginComponent {
  loginForm: FormGroup;
  isLoading = false;
  errorMessage = '';

  constructor(
    private fb: FormBuilder,
    private authService: AuthService,
    private router: Router
  ) {
    this.loginForm = this.fb.group({
      username: ['', [Validators.required]],
      password: ['', [Validators.required]]
    });
  }

  onSubmit(): void {
    if (this.loginForm.invalid) return;

    this.isLoading = true;
    this.errorMessage = '';

    const { username, password } = this.loginForm.value;

    try {
      this.authService.login(username, password);
      this.router.navigate(['/dashboard']);
    } catch (error) {
      this.errorMessage = 'Login failed. Please check your credentials.';
    } finally {
      this.isLoading = false;
    }
  }
}
