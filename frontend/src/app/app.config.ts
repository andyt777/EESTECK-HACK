import { ApplicationConfig, inject, provideAppInitializer, provideBrowserGlobalErrorListeners, provideZonelessChangeDetection } from '@angular/core';
import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';
import { OidcSecurityService, authInterceptor, provideAuth } from 'angular-auth-oidc-client';
import { AuthzService, authenticate, oidcConfig } from './core/auth';
import { provideRouter, withComponentInputBinding, withInMemoryScrolling } from '@angular/router';
import { provideEchartsCore } from 'ngx-echarts';
import { routes } from './app.routes';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideZonelessChangeDetection(),
    provideHttpClient(withFetch(), withInterceptors([authInterceptor()])),
    provideAuth(oidcConfig()),
    provideAppInitializer(() => authenticate(inject(OidcSecurityService), inject(AuthzService))),
    provideRouter(routes, withComponentInputBinding(), withInMemoryScrolling({ scrollPositionRestoration: 'top' })),
    provideEchartsCore({ echarts: () => import('echarts') }),
  ],
};
