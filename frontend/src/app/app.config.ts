import { ApplicationConfig, provideAppInitializer, inject } from '@angular/core';
import { provideRouter } from '@angular/router';
import { routes } from './app.routes';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideAnimations } from '@angular/platform-browser/animations';
import { authInterceptor } from './core/interceptors/auth.interceptor';
import { noContextInterceptor } from './core/interceptors/no-context.interceptor';
import { AppContextService } from './core/services/app-context.service';
import { firstValueFrom } from 'rxjs';

export const appConfig: ApplicationConfig = {
  providers: [
    provideRouter(routes),
    provideHttpClient(withInterceptors([authInterceptor, noContextInterceptor])),
    provideAnimations(),
    provideAppInitializer(() => {
      const ctx = inject(AppContextService);
      return firstValueFrom(ctx.load()).catch(() => null);
    })
  ]
};
