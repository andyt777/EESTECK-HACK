import { Directive, Injectable, TemplateRef, ViewContainerRef, computed, effect, inject, input, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { CanActivateFn, Router } from '@angular/router';
import { OidcSecurityService, PassedInitialConfig } from 'angular-auth-oidc-client';
import { firstValueFrom } from 'rxjs';

/** Keycloak realm `filegrid` behind the gateway, on the same origin as the application (docs/AUTH-DESIGN.md §5). */
export function oidcConfig(origin = window.location.origin): PassedInitialConfig {
  return {
    config: {
      authority: `${origin}/auth/realms/filegrid`,
      redirectUrl: origin,
      postLogoutRedirectUri: origin,
      clientId: 'filegrid-web',
      responseType: 'code',
      scope: 'openid profile',
      useRefreshToken: true,
      silentRenew: true,
      renewTimeBeforeTokenExpiresInSeconds: 30,
      ignoreNonceAfterRefresh: true,
      triggerRefreshWhenIdTokenExpired: false,
      secureRoutes: ['/api/'],
      startCheckSession: false,
      autoUserInfo: false,
      historyCleanupOff: false,
    },
  };
}

const RETURN_URL = 'fg.returnUrl';

/**
 * App initializer: completes the code + PKCE redirect when there is one, otherwise sends the
 * browser to the Keycloak login page. The rest of the application starts only with a token, so
 * every request, the event streams and the uploads are authenticated from the first call.
 */
export async function authenticate(oidc: OidcSecurityService, authz: AuthzService): Promise<void> {
  const result = await firstValueFrom(oidc.checkAuth());
  if (!result.isAuthenticated) {
    try { sessionStorage.setItem(RETURN_URL, window.location.pathname + window.location.search); } catch { /* private mode */ }
    oidc.authorize();
    await new Promise<never>(() => {}); // the page is leaving for the login form
  }
  let back: string | null = null;
  try { back = sessionStorage.getItem(RETURN_URL); sessionStorage.removeItem(RETURN_URL); } catch { /* private mode */ }
  if (back && back !== '/' && back.startsWith('/')) window.history.replaceState(null, '', back);
  await authz.load();
}

export interface Me {
  actor: string;
  name: string | null;
  roles: string[];
  permissions: string[];
  expiresAt: string | null;
}

const ROLE_LABELS: Record<string, string> = {
  field: 'Teren',
  dispatcher: 'Dispecer',
  connections: 'Racordări',
  analyst: 'Analist',
  'incident-manager': 'Manager incidente',
  admin: 'Administrator',
};

/** Who is signed in and what they may do, from {@code GET /api/me}: hides actions the backend would refuse. */
@Injectable({ providedIn: 'root' })
export class AuthzService {
  private readonly http = inject(HttpClient);
  private readonly oidc = inject(OidcSecurityService);

  readonly me = signal<Me | null>(null);
  readonly permissions = computed(() => new Set(this.me()?.permissions ?? []));
  readonly roleLabel = computed(() => {
    const roles = (this.me()?.roles ?? []).filter((r) => ROLE_LABELS[r]);
    return roles.map((r) => ROLE_LABELS[r]).join(', ');
  });

  async load(): Promise<void> {
    try {
      this.me.set(await firstValueFrom(this.http.get<Me>('/api/me')));
    } catch {
      this.me.set(null);
    }
  }

  can(permission: string): boolean {
    return this.permissions().has(permission);
  }

  /** The current access token (silent renew with the refresh token keeps it valid). */
  token(): Promise<string> {
    return firstValueFrom(this.oidc.getAccessToken());
  }

  logout(): void {
    this.oidc.logoff().subscribe();
  }
}

/** Route guard: pages that need a permission send the others back to the village. */
export function permissionGuard(permission: string): CanActivateFn {
  return () => inject(AuthzService).can(permission) || inject(Router).parseUrl('/');
}

/** `<button *fgCan="'policy:write'">` renders only when the signed-in user has the permission. */
@Directive({ selector: '[fgCan]' })
export class CanDirective {
  readonly fgCan = input.required<string>();
  private readonly authz = inject(AuthzService);
  private readonly tpl = inject(TemplateRef<unknown>);
  private readonly vcr = inject(ViewContainerRef);
  private shown = false;

  constructor() {
    effect(() => {
      const allowed = this.authz.permissions().has(this.fgCan());
      if (allowed && !this.shown) this.vcr.createEmbeddedView(this.tpl);
      if (!allowed && this.shown) this.vcr.clear();
      this.shown = allowed;
    });
  }
}
