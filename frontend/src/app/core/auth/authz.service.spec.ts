import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { AuthzService } from './authz.service';

describe('AuthzService', () => {
  let svc: AuthzService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    svc = TestBed.inject(AuthzService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('loads /api/me into signals', () => {
    let result: boolean | undefined;
    svc.load().subscribe((v) => (result = v));
    http.expectOne('/api/me').flush({ actor: 'connections.demo', roles: ['connections'], permissions: ['file:read', 'pii:read'] });

    expect(result).toBe(true);
    expect(svc.loaded()).toBe(true);
    expect(svc.actor()).toBe('connections.demo');
    expect(svc.roles()).toEqual(['connections']);
    expect(svc.can('pii:read')).toBe(true);
    expect(svc.can('policy:write')).toBe(false);
  });

  it('shares one request between concurrent callers and caches the result', () => {
    const seen: boolean[] = [];
    svc.load().subscribe((v) => seen.push(v));
    svc.load().subscribe((v) => seen.push(v));
    http.expectOne('/api/me').flush({ actor: 'a', roles: [], permissions: [] });
    svc.load().subscribe((v) => seen.push(v));
    http.expectNone('/api/me');
    expect(seen).toEqual([true, true, true]);
  });

  it('reports failure, sets a Romanian error and allows a retry', () => {
    let result: boolean | undefined;
    svc.load().subscribe((v) => (result = v));
    http.expectOne('/api/me').flush({}, { status: 503, statusText: 'Unavailable' });
    expect(result).toBe(false);
    expect(svc.loaded()).toBe(false);
    expect(svc.error()).toContain('Profilul');

    svc.load().subscribe((v) => (result = v));
    http.expectOne('/api/me').flush({ actor: 'a', roles: [], permissions: ['file:read'] });
    expect(result).toBe(true);
    expect(svc.error()).toBeNull();
  });

  it('can() with a list means any-of', () => {
    svc.set({ actor: 'x', roles: [], permissions: ['incident:read'] });
    expect(svc.can(['policy:write', 'incident:read'])).toBe(true);
    expect(svc.can(['policy:write', 'pii:read'])).toBe(false);
    expect(svc.can([])).toBe(false);
  });

  it('translates known roles to Romanian labels, most privileged first, ignoring service roles', () => {
    svc.set({ actor: 'x', roles: ['field', 'offline_access', 'admin', 'incident-manager'], permissions: [] });
    expect(svc.roleLabels()).toEqual(['Administrator', 'Manager incident', 'Teren']);
    svc.set({ actor: 'x', roles: ['dispatcher', 'connections', 'analyst'], permissions: [] });
    expect(svc.roleLabels()).toEqual(['Dispecer', 'Racordări', 'Analist']);
  });

  it('clear() forgets the user and forces a new request', () => {
    svc.set({ actor: 'x', roles: ['admin'], permissions: ['policy:write'] });
    svc.clear();
    expect(svc.can('policy:write')).toBe(false);
    expect(svc.actor()).toBeNull();
    svc.load().subscribe();
    http.expectOne('/api/me').flush({ actor: 'y', roles: [], permissions: [] });
  });
});
