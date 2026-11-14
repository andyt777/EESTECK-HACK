import { Injectable, inject } from '@angular/core';
import { OidcSecurityService } from 'angular-auth-oidc-client';
import { firstValueFrom } from 'rxjs';

/** Seconds before `exp` at which a token is considered stale and refreshed first. */
export const TOKEN_MIN_VALIDITY_S = 30;

/** Reads `exp` (seconds since epoch) from a JWT without validating it (the server validates). */
export function jwtExpiry(token: string | null | undefined): number | null {
  if (!token) return null;
  const part = token.split('.')[1];
  if (!part) return null;
  try {
    const b64 = part.replace(/-/g, '+').replace(/_/g, '/').padEnd(Math.ceil(part.length / 4) * 4, '=');
    const exp = (JSON.parse(atob(b64)) as { exp?: unknown }).exp;
    return typeof exp === 'number' ? exp : null;
  } catch {
    return null;
  }
}

/**
 * Bearer tokens for transports the HTTP interceptor does not cover (fetch-based SSE, tus uploads).
 * Guarantees a token that is valid for at least TOKEN_MIN_VALIDITY_S, refreshing it (single flight) when needed.
 */
@Injectable({ providedIn: 'root' })
export class AccessTokens {
  private readonly oidc = inject(OidcSecurityService);
  private refreshing: Promise<string | null> | null = null;

  async get(forceRefresh = false): Promise<string | null> {
    const current = await this.current();
    const exp = jwtExpiry(current);
    const stale = !current || (exp !== null && exp - Date.now() / 1000 < TOKEN_MIN_VALIDITY_S);
    if (!forceRefresh && !stale) return current;
    return this.refresh();
  }

  private async current(): Promise<string | null> {
    try {
      return (await firstValueFrom(this.oidc.getAccessToken())) || null;
    } catch {
      return null;
    }
  }

  private refresh(): Promise<string | null> {
    this.refreshing ??= firstValueFrom(this.oidc.forceRefreshSession())
      .then((r) => (r?.isAuthenticated && r.accessToken ? r.accessToken : this.current()))
      .catch(() => this.current())
      .finally(() => { this.refreshing = null; });
    return this.refreshing;
  }
}
