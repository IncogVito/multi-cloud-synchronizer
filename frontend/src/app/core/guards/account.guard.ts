import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { map } from 'rxjs';
import { AccountSessionService } from '../services/account-session.service';

export const accountGuard: CanActivateFn = () => {
  const session = inject(AccountSessionService);
  const router = inject(Router);

  if (!session.activeAccountId()) {
    return router.createUrlTree(['/login']);
  }

  return session.validateSession().pipe(
    map(valid => valid ? true : router.createUrlTree(['/login']))
  );
};
