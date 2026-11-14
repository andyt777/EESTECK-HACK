import { HttpErrorResponse, HttpHeaders, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Api, errorText, isUnavailable, sameOriginUrl } from './api.service';

describe('Api.contentLink', () => {
  let api: Api;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    api = TestBed.inject(Api);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  it('POSTs the disposition and returns the signed link', () => {
    let url = '';
    api.contentLink('f-1', 'inline').subscribe((r) => (url = r.url));
    const req = http.expectOne('/api/files/f-1/content-link');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ disposition: 'inline' });
    req.flush({ url: '/api/files/f-1/content?disposition=inline&exp=1&act=a&sig=s', expiresAt: '2026-11-14T10:00:00Z' });
    expect(url).toContain('sig=s');
  });

  it('only sends acknowledgeSuspect for the explicit suspect download', () => {
    api.contentLink('f-2', 'attachment', true).subscribe();
    expect(http.expectOne('/api/files/f-2/content-link').request.body).toEqual({ disposition: 'attachment', acknowledgeSuspect: true });
  });

  it('never builds direct /content URLs any more', () => {
    expect((api as unknown as Record<string, unknown>)['contentUrl']).toBeUndefined();
  });
});

describe('sameOriginUrl', () => {
  const origin = 'https://localhost';
  it('accepts relative and same-origin URLs', () => {
    expect(sameOriginUrl('/api/files/x/content?sig=1', origin)).toBe('/api/files/x/content?sig=1');
    expect(sameOriginUrl('https://localhost/api/a?b=1', origin)).toBe('/api/a?b=1');
  });
  it('rejects other origins and empty values', () => {
    expect(sameOriginUrl('https://evil.example/api', origin)).toBeNull();
    expect(sameOriginUrl('//evil.example/x', origin)).toBeNull();
    expect(sameOriginUrl('', origin)).toBeNull();
    expect(sameOriginUrl(null, origin)).toBeNull();
  });
});

describe('errorText', () => {
  it('shows a clear Romanian message with the traceId for 403', () => {
    const e = new HttpErrorResponse({ status: 403, error: { title: 'Forbidden', traceId: 'abc123' } });
    const t = errorText(e);
    expect(t).toContain('Nu aveți drepturi');
    expect(t).toContain('(403)');
    expect(t).toContain('traceId abc123');
    expect(isUnavailable(e)).toBe(false); // shown as an error, not as "service unavailable"
  });
  it('explains an expired session for 401', () => {
    expect(errorText(new HttpErrorResponse({ status: 401, headers: new HttpHeaders() }))).toContain('Sesiunea a expirat');
  });
  it('keeps the server message for other errors', () => {
    expect(errorText(new HttpErrorResponse({ status: 400, error: { detail: 'YAML invalid' } }))).toContain('YAML invalid (400)');
  });
});
