import { TestBed } from '@angular/core/testing';
import { LoginResponse, OidcSecurityService } from 'angular-auth-oidc-client';
import { Observable, Subject, of } from 'rxjs';
import { AccessTokens, jwtExpiry } from './access-token.service';

function fakeJwt(payload: object): string {
  const b64 = (o: object) => btoa(JSON.stringify(o)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  return `${b64({ alg: 'none' })}.${b64(payload)}.sig`;
}
const nowS = () => Math.floor(Date.now() / 1000);

class FakeOidc {
  token = '';
  refreshes = 0;
  refresh$: Observable<LoginResponse> | null = null;
  getAccessToken() { return of(this.token); }
  forceRefreshSession(): Observable<LoginResponse> {
    this.refreshes++;
    return this.refresh$ ?? of({ isAuthenticated: true, accessToken: 'refreshed', idToken: '', userData: null });
  }
}

describe('jwtExpiry', () => {
  it('reads exp from a base64url JWT payload', () => {
    expect(jwtExpiry(fakeJwt({ exp: 1234, sub: 'x' }))).toBe(1234);
  });
  it('returns null for malformed input', () => {
    expect(jwtExpiry('not-a-jwt')).toBeNull();
    expect(jwtExpiry('a.@@@.c')).toBeNull();
    expect(jwtExpiry(null)).toBeNull();
    expect(jwtExpiry(fakeJwt({ sub: 'x' }))).toBeNull();
  });
});

describe('AccessTokens', () => {
  let oidc: FakeOidc;
  let tokens: AccessTokens;

  beforeEach(() => {
    oidc = new FakeOidc();
    TestBed.configureTestingModule({ providers: [{ provide: OidcSecurityService, useValue: oidc }] });
    tokens = TestBed.inject(AccessTokens);
  });

  it('returns the current token while it is valid for long enough', async () => {
    oidc.token = fakeJwt({ exp: nowS() + 240 });
    expect(await tokens.get()).toBe(oidc.token);
    expect(oidc.refreshes).toBe(0);
  });

  it('refreshes a token that is about to expire', async () => {
    oidc.token = fakeJwt({ exp: nowS() + 5 });
    expect(await tokens.get()).toBe('refreshed');
    expect(oidc.refreshes).toBe(1);
  });

  it('refreshes when forced (after a 401)', async () => {
    oidc.token = fakeJwt({ exp: nowS() + 240 });
    expect(await tokens.get(true)).toBe('refreshed');
  });

  it('runs a single refresh for concurrent callers', async () => {
    const pending = new Subject<LoginResponse>();
    oidc.refresh$ = pending;
    const a = tokens.get();
    const b = tokens.get();
    for (let i = 0; i < 5; i++) await Promise.resolve();
    pending.next({ isAuthenticated: true, accessToken: 'new', idToken: '', userData: null });
    pending.complete();
    expect(await a).toBe('new');
    expect(await b).toBe('new');
    expect(oidc.refreshes).toBe(1);
  });

  it('falls back to the stored token when the refresh fails', async () => {
    oidc.token = fakeJwt({ exp: nowS() + 5 });
    oidc.refresh$ = new Observable<LoginResponse>((s) => s.error(new Error('offline')));
    expect(await tokens.get()).toBe(oidc.token);
  });
});
