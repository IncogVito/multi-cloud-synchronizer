import { HttpInterceptorFn, HttpErrorResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import { AppContextService } from '../services/app-context.service';
import { AuthService } from '../services/auth.service';
import { ToastService } from '../services/toast.service';

export const noContextInterceptor: HttpInterceptorFn = (req, next) => {
  const router = inject(Router);
  const ctx = inject(AppContextService);
  const toast = inject(ToastService);
  const auth = inject(AuthService);

  return next(req).pipe(
    catchError((err: HttpErrorResponse) => {
      if (err.status === 409 && err.error?.error === 'NO_ACTIVE_CONTEXT' && auth.isAuthenticated()) {
        ctx.clearLocal();
        toast.warning('Wybierz dysk i folder docelowy aby kontynuować');
        if (!router.url.startsWith('/setup') && !router.url.startsWith('/login')) {
          router.navigate(['/setup']);
        }
      }
      return throwError(() => err);
    })
  );
};
