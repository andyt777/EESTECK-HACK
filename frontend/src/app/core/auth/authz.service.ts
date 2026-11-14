import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, catchError, map, of, shareReplay, tap } from 'rxjs';
import { T } from '../texts';
import { Permission } from './auth.config';

/** Response of `GET /api/me` (docs/AUTH-DESIGN.md §3). */
export interface Me {
  actor: string;
  roles: string[];
  permissions: string[];
}

/** Display order of the business roles (most privileged first). */
const ROLE_ORDER = ['admin', 'incident-manager', 'dispatcher', 'connections', 'analyst', 'field'];

/**
 * The signed-in user's actor, roles and permissions as returned by the API.
 * UI-only: it hides/disables actions; the server stays authoritative for every request.
 */
@Injectable({ providedIn: 'root' })
export class AuthzService {
  private readonly http = inject(HttpClient);
  private request$: Observable<boolean> | null = null;

  readonly actor = signal<string | null>(null);
  readonly roles = signal<string[]>([]);
  readonly permissions = signal<ReadonlySet<string>>(new Set());
  readonly loaded = signal(false);
  readonly error = signal<string | null>(null);

  /** Known business roles, translated, most privileged first. */
  readonly roleLabels = computed(() =>
    ROLE_ORDER.filter((r) => this.roles().includes(r)).map((r) => T.auth.roles[r] ?? r));

  /** Loads `/api/me` once (shared by concurrent callers); emits true when the profile is available. */
  load(force = false): Observable<boolean> {
    if (force || !this.request$) {
      this.request$ = this.http.get<Me>('/api/me').pipe(
        tap((me) => this.set(me)),
        map(() => true),
        catchError(() => {
          this.error.set(T.auth.profileError);
          this.request$ = null; // allow a later retry
          return of(false);
        }),
        shareReplay(1),
      );
    }
    return this.request$;
  }

  /** True when the user has the permission (or any of them, for an array). */
  can(p: Permission | string | readonly (Permission | string)[]): boolean {
    const set = this.permissions();
    return Array.isArray(p) ? p.some((x) => set.has(x)) : set.has(p as string);
  }

  set(me: Me): void {
    this.actor.set(me.actor ?? null);
    this.roles.set(Array.isArray(me.roles) ? me.roles : []);
    this.permissions.set(new Set(Array.isArray(me.permissions) ? me.permissions : []));
    this.error.set(null);
    this.loaded.set(true);
  }

  clear(): void {
    this.actor.set(null);
    this.roles.set([]);
    this.permissions.set(new Set());
    this.loaded.set(false);
    this.request$ = null;
  }
}
