import { LogLevel, OpenIdConfiguration } from 'angular-auth-oidc-client';

/** Keycloak realm path, served behind the gateway under the same origin (docs/AUTH-DESIGN.md §1, §5). */
export const REALM_PATH = '/auth/realms/filegrid';
export const OIDC_CLIENT_ID = 'filegrid-web';

/** Permission strings from the role → permission matrix (docs/AUTH-DESIGN.md §3). */
export type Permission =
  | 'file:read' | 'file:write' | 'file:verify' | 'file:read-suspect' | 'pii:read'
  | 'meters:read'
  | 'incident:read' | 'incident:create' | 'incident:write' | 'incident:confirm'
  | 'policy:read' | 'policy:write'
  | 'intelligence:read' | 'intelligence:write'
  | 'assistant:use' | 'voice:use' | 'storage:read';

/** OIDC configuration: authorization code + PKCE (public client), refresh-token based silent renew. */
export function oidcConfig(origin: string): OpenIdConfiguration {
  return {
    authority: origin + REALM_PATH,
    redirectUrl: origin,
    postLogoutRedirectUri: origin,
    clientId: OIDC_CLIENT_ID,
    responseType: 'code',
    scope: 'openid profile',
    useRefreshToken: true,
    silentRenew: true,
    renewTimeBeforeTokenExpiresInSeconds: 30,
    // Keycloak issues refresh tokens without `offline_access` (that scope would create offline sessions).
    disableRefreshTokenOfflineAccessScopeWarning: true,
    // Only same-origin API calls carry the bearer token (never third-party URLs).
    secureRoutes: ['/api/'],
    startCheckSession: false,
    logLevel: LogLevel.Warn,
  };
}
