import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { map } from 'rxjs';
import { Permission } from './auth.config';
import { AuthzService } from './authz.service';

/**
 * Route guard: lets the navigation through when the user has at least one of the permissions,
 * otherwise redirects to `/forbidden`. Runs after the OIDC auto-login guard (parent route).
 */
export function permissionGuard(...anyOf: Permission[]): CanActivateFn {
  return () => {
    const authz = inject(AuthzService);
    const router = inject(Router);
    return authz.load().pipe(
      map((ok) => (ok && authz.can(anyOf) ? true : router.createUrlTree(['/forbidden']))),
    );
  };
}
