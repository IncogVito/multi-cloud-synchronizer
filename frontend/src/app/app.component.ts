import { Component, inject } from '@angular/core';
import { RouterOutlet, RouterLink, RouterLinkActive } from '@angular/router';
import { AuthService } from './core/services/auth.service';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  template: `
    <div class="app-shell">
      @if (authService.isAuthenticated()) {
        <aside class="sidebar">
          <div class="sidebar-logo">
            <span class="logo-icon">&#9729;</span>
            <span class="logo-text">CloudSync</span>
          </div>

          <nav class="sidebar-nav">
            <a
              routerLink="/dashboard"
              routerLinkActive="active"
              class="nav-link"
            >
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <rect x="3" y="3" width="7" height="7"/>
                <rect x="14" y="3" width="7" height="7"/>
                <rect x="3" y="14" width="7" height="7"/>
                <rect x="14" y="14" width="7" height="7"/>
              </svg>
              Dashboard
            </a>
            <a
              routerLink="/photos"
              routerLinkActive="active"
              class="nav-link"
            >
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <rect x="3" y="3" width="18" height="18" rx="2"/>
                <circle cx="8.5" cy="8.5" r="1.5"/>
                <polyline points="21 15 16 10 5 21"/>
              </svg>
              Photos
            </a>
          </nav>

          <div class="sidebar-footer">
            <button class="nav-link logout-btn" (click)="logout()">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/>
                <polyline points="16 17 21 12 16 7"/>
                <line x1="21" y1="12" x2="9" y2="12"/>
              </svg>
              Logout
            </button>
          </div>
        </aside>
      }

      <main class="main-content" [class.with-sidebar]="authService.isAuthenticated()">
        <router-outlet></router-outlet>
      </main>
    </div>
  `,
  styles: [`
    .app-shell {
      display: flex;
      min-height: 100vh;
    }

    .sidebar {
      width: 220px;
      min-width: 220px;
      background: #ffffff;
      border-right: 1px solid var(--color-border);
      display: flex;
      flex-direction: column;
      position: fixed;
      top: 0;
      left: 0;
      height: 100vh;
      z-index: 100;
      box-shadow: var(--shadow-sm);
    }

    .sidebar-logo {
      display: flex;
      align-items: center;
      gap: var(--spacing-2);
      padding: var(--spacing-6) var(--spacing-5);
      border-bottom: 1px solid var(--color-border);
    }

    .logo-icon {
      font-size: 1.5rem;
      line-height: 1;
    }

    .logo-text {
      font-size: var(--font-size-lg);
      font-weight: var(--font-weight-semibold);
      color: var(--color-text-primary);
      letter-spacing: -0.01em;
    }

    .sidebar-nav {
      flex: 1;
      display: flex;
      flex-direction: column;
      padding: var(--spacing-4) var(--spacing-3);
      gap: var(--spacing-1);
    }

    .nav-link {
      display: flex;
      align-items: center;
      gap: var(--spacing-3);
      padding: var(--spacing-2) var(--spacing-3);
      border-radius: var(--radius-md);
      font-size: var(--font-size-sm);
      font-weight: var(--font-weight-medium);
      color: var(--color-text-secondary);
      text-decoration: none;
      transition: background-color var(--transition-fast), color var(--transition-fast);
      cursor: pointer;
      border: none;
      background: none;
      width: 100%;
      font-family: var(--font-family-base);

      &:hover {
        background-color: var(--color-bg-secondary);
        color: var(--color-text-primary);
      }

      &.active {
        background-color: var(--color-primary-bg);
        color: var(--color-primary);
      }
    }

    .sidebar-footer {
      padding: var(--spacing-4) var(--spacing-3);
      border-top: 1px solid var(--color-border);
    }

    .logout-btn {
      color: var(--color-danger);

      &:hover {
        background-color: var(--color-danger-bg);
        color: var(--color-danger-dark);
      }
    }

    .main-content {
      flex: 1;
      min-height: 100vh;

      &.with-sidebar {
        margin-left: 220px;
      }
    }
  `]
})
export class AppComponent {
  authService = inject(AuthService);

  logout(): void {
    this.authService.logout();
    window.location.href = '/login';
  }
}
