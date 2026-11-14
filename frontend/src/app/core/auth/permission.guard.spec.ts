import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { EnvironmentInjector, runInInjectionContext } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, GuardResult, MaybeAsync, Router, RouterStateSnapshot, UrlTree, provideRouter } from '@angular/router';
import { Observable, firstValueFrom, isObservable } from 'rxjs';
import { Permission } from './auth.config';
import { permissionGuard } from './permission.guard';

describe('permissionGuard', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  function run(...perms: Permission[]): Promise<GuardResult> {
    const result: MaybeAsync<GuardResult> = runInInjectionContext(TestBed.inject(EnvironmentInjector), () =>
      permissionGuard(...perms)({} as ActivatedRouteSnapshot, {} as RouterStateSnapshot));
    expect(isObservable(result)).toBe(true);
    return firstValueFrom(result as Observable<GuardResult>);
  }

  it('allows navigation when the user has the permission', async () => {
    const p = run('intelligence:read');
    http.expectOne('/api/me').flush({ actor: 'analyst.demo', roles: ['analyst'], permissions: ['intelligence:read'] });
    expect(await p).toBe(true);
  });

  it('allows navigation when the user has any of several permissions', async () => {
    const p = run('policy:write', 'policy:read');
    http.expectOne('/api/me').flush({ actor: 'd', roles: ['dispatcher'], permissions: ['policy:read'] });
    expect(await p).toBe(true);
  });

  it('redirects to /forbidden without the permission', async () => {
    const p = run('policy:write');
    http.expectOne('/api/me').flush({ actor: 'field.demo', roles: ['field'], permissions: ['file:read'] });
    const r = await p;
    expect(r instanceof UrlTree).toBe(true);
    expect(TestBed.inject(Router).serializeUrl(r as UrlTree)).toBe('/forbidden');
  });

  it('redirects to /forbidden when the profile cannot be loaded', async () => {
    const p = run('file:read');
    http.expectOne('/api/me').flush({}, { status: 401, statusText: 'Unauthorized' });
    const r = await p;
    expect(TestBed.inject(Router).serializeUrl(r as UrlTree)).toBe('/forbidden');
  });
});
