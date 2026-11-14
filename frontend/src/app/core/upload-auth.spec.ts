import { DetailedError, HttpRequest, HttpResponse } from 'tus-js-client';
import { tusAuthOptions, tusStatus } from './upload.service';

function fakeReq() {
  const headers: Record<string, string> = {};
  return { headers, req: { setHeader: (k: string, v: string) => { headers[k] = v; } } as unknown as HttpRequest };
}

function errWithStatus(status: number | null): DetailedError {
  const e = new Error('x') as DetailedError & { originalResponse: HttpResponse | null };
  e.originalResponse = status === null ? null : ({ getStatus: () => status } as unknown as HttpResponse);
  return e;
}

describe('tus auth hooks', () => {
  it('sets a fresh bearer token on every request (awaited by tus-js-client)', async () => {
    const token = vi.fn().mockResolvedValueOnce('t1').mockResolvedValueOnce('t2');
    const o = tusAuthOptions(token);
    const a = fakeReq();
    const b = fakeReq();
    await o.onBeforeRequest!(a.req);
    await o.onBeforeRequest!(b.req);
    expect(a.headers['Authorization']).toBe('Bearer t1');
    expect(b.headers['Authorization']).toBe('Bearer t2');
    expect(token.mock.calls.map((c) => c[0])).toEqual([false, false]);
  });

  it('on 401 retries and forces a token refresh for the next request', async () => {
    const token = vi.fn(async (force: boolean) => (force ? 'fresh' : 'stale'));
    const o = tusAuthOptions(token);
    expect(o.onShouldRetry!(errWithStatus(401), 0, {})).toBe(true);
    const r = fakeReq();
    await o.onBeforeRequest!(r.req);
    expect(r.headers['Authorization']).toBe('Bearer fresh');
    // the flag is consumed
    const r2 = fakeReq();
    await o.onBeforeRequest!(r2.req);
    expect(r2.headers['Authorization']).toBe('Bearer stale');
  });

  it('gives up on repeated 401s', () => {
    const o = tusAuthOptions(async () => 't');
    expect(o.onShouldRetry!(errWithStatus(401), 2, {})).toBe(false);
  });

  it('keeps the default policy for other statuses', () => {
    const o = tusAuthOptions(async () => 't');
    expect(o.onShouldRetry!(errWithStatus(403), 0, {})).toBe(false);
    expect(o.onShouldRetry!(errWithStatus(404), 0, {})).toBe(false);
    expect(o.onShouldRetry!(errWithStatus(409), 0, {})).toBe(true);
    expect(o.onShouldRetry!(errWithStatus(423), 0, {})).toBe(true);
    expect(o.onShouldRetry!(errWithStatus(503), 0, {})).toBe(true);
    expect(o.onShouldRetry!(errWithStatus(null), 0, {})).toBe(true); // network error
  });

  it('omits the header when no token is available', async () => {
    const o = tusAuthOptions(async () => null);
    const r = fakeReq();
    await o.onBeforeRequest!(r.req);
    expect(r.headers['Authorization']).toBeUndefined();
  });

  it('tusStatus reads the response status or 0', () => {
    expect(tusStatus(errWithStatus(401))).toBe(401);
    expect(tusStatus(errWithStatus(null))).toBe(0);
    expect(tusStatus(null)).toBe(0);
  });
});
