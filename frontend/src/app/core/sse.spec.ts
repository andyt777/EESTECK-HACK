import { SseConnection, SseMessage, SseParser } from './sse';

describe('SseParser', () => {
  const feed = (chunks: string[]) => {
    const p = new SseParser();
    const out: SseMessage[] = [];
    for (const c of chunks) out.push(...p.push(c));
    return { p, out };
  };

  it('parses a named event with JSON data', () => {
    const { out } = feed(['event: file-event\ndata: {"a":1}\n\n']);
    expect(out).toEqual([{ event: 'file-event', data: '{"a":1}', id: null }]);
  });

  it('handles chunk boundaries anywhere (mid field, mid value, between events)', () => {
    const text = 'id: 7\nevent: file-event\ndata: {"fileId":"x","status":"STORED_VERIFIED"}\n\nevent: incident-event\ndata: {"b":2}\n\n';
    for (let size = 1; size <= 9; size++) {
      const chunks: string[] = [];
      for (let i = 0; i < text.length; i += size) chunks.push(text.slice(i, i + size));
      const { out } = feed(chunks);
      expect(out.map((m) => m.event)).toEqual(['file-event', 'incident-event']);
      expect(JSON.parse(out[0].data).status).toBe('STORED_VERIFIED');
      expect(out[0].id).toBe('7');
    }
  });

  it('supports CRLF and CR line endings, including a CRLF split across chunks', () => {
    expect(feed(['event: e\r\ndata: 1\r', '\n\r', '\n']).out).toEqual([{ event: 'e', data: '1', id: null }]);
    expect(feed(['data: 2\r\r']).out).toEqual([{ event: 'message', data: '2', id: null }]);
  });

  it('joins multi-line data, ignores comments/heartbeats and unknown fields', () => {
    const { out } = feed([': heartbeat\n\nfoo: bar\ndata: line1\ndata:line2\n\n']);
    expect(out).toEqual([{ event: 'message', data: 'line1\nline2', id: null }]);
  });

  it('does not dispatch an event without data and keeps an unfinished event buffered', () => {
    const { p, out } = feed(['event: ping\n\n', 'event: file-event\ndata: {"x"']);
    expect(out).toEqual([]);
    expect(p.push(':1}\n\n')).toEqual([{ event: 'file-event', data: '{"x":1}', id: null }]);
  });

  it('reads the retry field', () => {
    expect(feed(['retry: 5000\n\n']).p.retryMs).toBe(5000);
    expect(feed(['retry: soon\n\n']).p.retryMs).toBeNull();
  });
});

/** A fake fetch Response whose body is driven by the test. */
function streamResponse(status = 200) {
  let ctrl!: ReadableStreamDefaultController<Uint8Array>;
  const body = new ReadableStream<Uint8Array>({ start: (c) => { ctrl = c; } });
  const enc = new TextEncoder();
  return {
    response: { ok: status >= 200 && status < 300, status, body } as unknown as Response,
    send: (s: string) => ctrl.enqueue(enc.encode(s)),
    sendBytes: (b: Uint8Array) => ctrl.enqueue(b),
    close: () => ctrl.close(),
  };
}

const flush = async () => { for (let i = 0; i < 10; i++) await Promise.resolve(); };

describe('SseConnection', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('sends the bearer token and delivers only the subscribed event name', async () => {
    const s = streamResponse();
    const fetchFn = vi.fn().mockResolvedValue(s.response);
    const got: unknown[] = [];
    const c = new SseConnection({ url: '/api/events/stream', eventName: 'file-event', onData: (d) => got.push(d), token: async () => 'tok', fetchFn });
    c.start();
    await flush();

    expect(fetchFn).toHaveBeenCalledTimes(1);
    const [url, init] = fetchFn.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('/api/events/stream');
    expect((init.headers as Record<string, string>)['Authorization']).toBe('Bearer tok');
    expect(c.connected()).toBe(true);

    // Multi-byte UTF-8 split across chunks must decode correctly.
    const bytes = new TextEncoder().encode('event: file-event\ndata: {"name":"Racordare Ștefănești"}\n\n');
    s.send('event: other\ndata: {"no":1}\n\n');
    const cut = bytes.indexOf(0xc8) + 1; // inside the 2-byte "Ș"
    s.sendBytes(bytes.slice(0, cut));
    s.sendBytes(bytes.slice(cut));
    s.send('data: not json\nevent: file-event\n\n');
    await flush();
    expect(got).toEqual([{ name: 'Racordare Ștefănești' }]);
    c.stop();
  });

  it('reconnects with a fresh token when the server closes the stream (token expiry)', async () => {
    const first = streamResponse();
    const second = streamResponse();
    const fetchFn = vi.fn().mockResolvedValueOnce(first.response).mockResolvedValueOnce(second.response);
    const token = vi.fn().mockResolvedValueOnce('t1').mockResolvedValueOnce('t2');
    const c = new SseConnection({ url: '/s', eventName: 'e', onData: () => {}, token, fetchFn });
    c.start();
    await flush();
    expect(c.connected()).toBe(true);

    first.close();
    await flush();
    expect(c.connected()).toBe(false);
    expect(fetchFn).toHaveBeenCalledTimes(1);

    await vi.advanceTimersByTimeAsync(1000);
    await flush();
    expect(fetchFn).toHaveBeenCalledTimes(2);
    expect(((fetchFn.mock.calls[1][1] as RequestInit).headers as Record<string, string>)['Authorization']).toBe('Bearer t2');
    expect(c.connected()).toBe(true);
    c.stop();
  });

  it('backs off exponentially on errors (1s, 2s, 4s… max 30s) and resets after a successful open', async () => {
    const fetchFn = vi.fn().mockRejectedValue(new TypeError('network'));
    const c = new SseConnection({ url: '/s', eventName: 'e', onData: () => {}, token: async () => 't', fetchFn });
    c.start();
    await flush();
    expect(fetchFn).toHaveBeenCalledTimes(1);

    await vi.advanceTimersByTimeAsync(999);
    expect(fetchFn).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(1);
    await flush();
    expect(fetchFn).toHaveBeenCalledTimes(2);

    await vi.advanceTimersByTimeAsync(2000);
    await flush();
    expect(fetchFn).toHaveBeenCalledTimes(3);
    expect(c.nextDelayMs).toBe(8000);

    for (let i = 0; i < 6; i++) { await vi.advanceTimersByTimeAsync(30000); await flush(); }
    expect(c.nextDelayMs).toBe(30000);

    const ok = streamResponse();
    fetchFn.mockResolvedValue(ok.response);
    await vi.advanceTimersByTimeAsync(30000);
    await flush();
    expect(c.connected()).toBe(true);
    expect(c.nextDelayMs).toBe(1000);
    c.stop();
  });

  it('forces a token refresh after a 401', async () => {
    const ok = streamResponse();
    const fetchFn = vi.fn()
      .mockResolvedValueOnce({ ok: false, status: 401, body: null } as unknown as Response)
      .mockResolvedValueOnce(ok.response);
    const token = vi.fn(async (force: boolean) => (force ? 'fresh' : 'stale'));
    const c = new SseConnection({ url: '/s', eventName: 'e', onData: () => {}, token, fetchFn });
    c.start();
    await flush();
    await vi.advanceTimersByTimeAsync(1000);
    await flush();
    expect(token.mock.calls.map((x) => x[0])).toEqual([false, true]);
    expect(((fetchFn.mock.calls[1][1] as RequestInit).headers as Record<string, string>)['Authorization']).toBe('Bearer fresh');
    c.stop();
  });

  it('stop() aborts the stream and prevents reconnects', async () => {
    const s = streamResponse();
    const fetchFn = vi.fn().mockResolvedValue(s.response);
    const c = new SseConnection({ url: '/s', eventName: 'e', onData: () => {}, token: async () => 't', fetchFn });
    c.start();
    await flush();
    const signal = (fetchFn.mock.calls[0][1] as RequestInit).signal as AbortSignal;
    c.stop();
    expect(signal.aborted).toBe(true);
    await vi.advanceTimersByTimeAsync(60000);
    expect(fetchFn).toHaveBeenCalledTimes(1);
    expect(c.connected()).toBe(false);
  });
});
