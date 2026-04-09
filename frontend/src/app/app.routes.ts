import { Routes } from '@angular/router';
import { authGuard } from './core/guards/auth.guard';
import { appContextGuard } from './core/guards/app-context.guard';

export const routes: Routes = [
  {
    path: '',
    redirectTo: '/dashboard',
    pathMatch: 'full'
  },
  {
    path: 'setup',
    loadComponent: () =>
      import('./features/disk-setup/disk-setup.component').then(m => m.DiskSetupComponent),
    canActivate: [authGuard]
  },
  {
    path: 'setup/wizard',
    loadComponent: () =>
      import('./features/setup-wizard/setup-wizard.component').then(m => m.SetupWizardComponent),
    canActivate: [authGuard, appContextGuard]
  },
  {
    path: 'login',
    loadComponent: () =>
      import('./features/login/login.component').then(m => m.LoginComponent)
  },
  {
    path: 'dashboard',
    loadComponent: () =>
      import('./features/dashboard/dashboard.component').then(m => m.DashboardComponent),
    canActivate: [authGuard, appContextGuard]
  },
  {
    path: 'photos',
    loadComponent: () =>
      import('./features/photos/photos.component').then(m => m.PhotosComponent),
    canActivate: [authGuard, appContextGuard]
  }
];
