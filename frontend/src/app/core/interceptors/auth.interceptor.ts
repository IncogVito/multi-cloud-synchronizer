import { HttpInterceptorFn } from '@angular/common/http';

export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const credentialsRaw = sessionStorage.getItem('auth_credentials');

  if (credentialsRaw) {
    try {
      const credentials = JSON.parse(credentialsRaw) as { encoded: string };
      const authReq = req.clone({
        setHeaders: {
          Authorization: `Basic ${credentials.encoded}`
        }
      });
      return next(authReq);
    } catch {
      // Invalid credentials in storage - proceed without auth header
    }
  }

  return next(req);
};
