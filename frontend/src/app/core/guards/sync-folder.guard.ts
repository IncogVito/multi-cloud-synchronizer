import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { catchError, map, of } from 'rxjs';
import { AccountSessionService } from '../services/account-session.service';
import { AccountService } from '../services/account.service';

/**
 * Guards routes that operate on an account's files (dashboard, photos, tasks).
 *
 * The account's {@code syncFolderPath} is the single source of truth for where its
 * media lives on disk. If the active account has no sync folder configured yet, the
 * user is redirected to the setup wizard to pick one (per-account, reusing the wizard).
 * Once set, it is never asked again unless the user explicitly changes it.
 */
export const syncFolderGuard: CanActivateFn = () => {
  const session = inject(AccountSessionService);
  const accountService = inject(AccountService);
  const router = inject(Router);

  const accountId = session.activeAccountId();
  if (!accountId) {
    return router.createUrlTree(['/login']);
  }

  return accountService.getAccountStatus(accountId).pipe(
    map(account => {
      if (account?.syncFolderPath) {
        return true;
      }
      return router.createUrlTree(['/setup/wizard'], { queryParams: { accountId } });
    }),
    catchError(() => of(router.createUrlTree(['/login'])))
  );
};
