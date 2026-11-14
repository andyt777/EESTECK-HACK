import { ApplicationConfig, provideBrowserGlobalErrorListeners, provideZonelessChangeDetection } from '@angular/core';
import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';
import { provideRouter, withComponentInputBinding, withInMemoryScrolling } from '@angular/router';
import { authInterceptor, provideAuth, withAppInitializerAuthCheck } from 'angular-auth-oidc-client';
import { provideEchartsCore } from 'ngx-echarts';
import { routes } from './app.routes';
import { oidcConfig } from './core/auth/auth.config';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideZonelessChangeDetection(),
    // Keycloak lives under the same origin (/auth); the callback is handled before the first navigation.
    provideAuth({ config: oidcConfig(window.location.origin) }, withAppInitializerAuthCheck()),
    // Adds `Authorization: Bearer` to HttpClient calls matching secureRoutes (/api/).
    provideHttpClient(withFetch(), withInterceptors([authInterceptor()])),
    provideRouter(routes, withComponentInputBinding(), withInMemoryScrolling({ scrollPositionRestoration: 'top' })),
    provideEchartsCore({ echarts: () => import('echarts') }),
  ],
};
