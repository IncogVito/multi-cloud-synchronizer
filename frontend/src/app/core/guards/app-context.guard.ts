import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AppContextService } from '../services/app-context.service';

export const appContextGuard: CanActivateFn = () => {
  const ctx = inject(AppContextService);
  const router = inject(Router);
  if (ctx.hasContext()) return true;
  return router.createUrlTree(['/setup']);
};
