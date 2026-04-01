import { Routes } from '@angular/router';
import { authGuard } from './core/guards/auth.guard';

export const routes: Routes = [
  {
    path: '',
    redirectTo: '/dashboard',
    pathMatch: 'full'
  },
  {
    path: 'setup',
    loadComponent: () =>
      import('./features/disk-setup/disk-setup.component').then(m => m.DiskSetupComponent)
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
    canActivate: [authGuard]
  },
  {
    path: 'photos',
    loadComponent: () =>
      import('./features/photos/photos.component').then(m => m.PhotosComponent),
    canActivate: [authGuard]
  }
];
