import { signal } from '@angular/core';

/** One Server-Sent Event (https://html.spec.whatwg.org/multipage/server-sent-events.html). */
export interface SseMessage {
  event: string;
  data: string;
  id: string | null;
}

/**
 * Incremental text/event-stream parser. Feed it decoded text in arbitrary chunks
 * (chunk boundaries may fall anywhere, including between `\r` and `\n`); it returns
 * the events completed by each chunk.
 */
export class SseParser {
  private buffer = '';
  private pendingCR = false;
  private event = '';
  private data: string[] = [];
  private lastId: string | null = null;
  /** Server-requested reconnection delay (`retry:` field), if any. */
  retryMs: number | null = null;

  push(chunk: string): SseMessage[] {
    const out: SseMessage[] = [];
    let text = chunk;
    // A `\r` at the end of the previous chunk already ended a line: drop the matching `\n`.
    if (this.pendingCR && text.startsWith('\n')) text = text.slice(1);
    this.pendingCR = false;
    this.buffer += text;

    let start = 0;
    for (let i = 0; i < this.buffer.length; i++) {
      const c = this.buffer[i];
      if (c !== '\n' && c !== '\r') continue;
      const line = this.buffer.slice(start, i);
      if (c === '\r') {
        if (i + 1 < this.buffer.length) {
          if (this.buffer[i + 1] === '\n') i++;
        } else {
          this.pendingCR = true;
        }
      }
      start = i + 1;
      const msg = this.line(line);
      if (msg) out.push(msg);
    }
    this.buffer = this.buffer.slice(start);
    return out;
  }

  /** Forgets any partially received event (call when a connection drops). */
  reset(): void {
    this.buffer = '';
    this.pendingCR = false;
    this.event = '';
    this.data = [];
  }

  private line(line: string): SseMessage | null {
    if (line === '') return this.dispatch();
    if (line.startsWith(':')) return null; // comment / heartbeat
    const colon = line.indexOf(':');
    const field = colon === -1 ? line : line.slice(0, colon);
    let value = colon === -1 ? '' : line.slice(colon + 1);
    if (value.startsWith(' ')) value = value.slice(1);
    switch (field) {
      case 'event': this.event = value; break;
      case 'data': this.data.push(value); break;
      case 'id': if (!value.includes('\0')) this.lastId = value; break;
      case 'retry': if (/^\d+$/.test(value)) this.retryMs = Number(value); break;
      default: break; // unknown fields are ignored
    }
    return null;
  }

  private dispatch(): SseMessage | null {
    const hasData = this.data.length > 0;
    const msg: SseMessage = { event: this.event || 'message', data: this.data.join('\n'), id: this.lastId };
    this.event = '';
    this.data = [];
    return hasData ? msg : null;
  }
}

export type TokenSource = (forceRefresh: boolean) => Promise<string | null>;

export interface SseOptions {
  url: string;
  /** Only events with this name are delivered (others are ignored, like addEventListener). */
  eventName: string;
  onData: (data: unknown) => void;
  token: TokenSource;
  fetchFn?: typeof fetch;
  initialBackoffMs?: number;
  maxBackoffMs?: number;
}

/**
 * SSE over `fetch` so that it can send `Authorization: Bearer` (EventSource cannot).
 * Reconnects with exponential backoff (1 s → 30 s, reset after a successful open), always with a
 * fresh token; the server closes the stream when the token expires, which triggers a reconnect.
 * A 401 forces a token refresh before the next attempt.
 */
export class SseConnection {
  readonly connected = signal(false);
  private backoff: number;
  private readonly initialBackoff: number;
  private readonly maxBackoff: number;
  private timer?: ReturnType<typeof setTimeout>;
  private abort?: AbortController;
  private running = false;
  private forceRefresh = false;

  constructor(private readonly opts: SseOptions) {
    this.initialBackoff = opts.initialBackoffMs ?? 1000;
    this.maxBackoff = opts.maxBackoffMs ?? 30000;
    this.backoff = this.initialBackoff;
  }

  start(): void {
    if (this.running) return;
    this.running = true;
    void this.open();
  }

  stop(): void {
    this.running = false;
    clearTimeout(this.timer);
    this.abort?.abort();
    this.abort = undefined;
    this.connected.set(false);
  }

  /** Current backoff delay (exposed for tests and diagnostics). */
  get nextDelayMs(): number { return this.backoff; }

  private async open(): Promise<void> {
    if (!this.running) return;
    const abort = new AbortController();
    this.abort = abort;
    const parser = new SseParser();
    try {
      const token = await this.opts.token(this.forceRefresh);
      this.forceRefresh = false;
      if (!this.running || abort.signal.aborted) return;
      const headers: Record<string, string> = { Accept: 'text/event-stream', 'Cache-Control': 'no-cache' };
      if (token) headers['Authorization'] = `Bearer ${token}`;
      const fetchFn = this.opts.fetchFn ?? fetch.bind(globalThis);
      const res = await fetchFn(this.opts.url, { headers, signal: abort.signal, cache: 'no-store' });
      if (res.status === 401) this.forceRefresh = true;
      if (!res.ok || !res.body) throw new Error(`SSE ${res.status}`);

      this.connected.set(true);
      this.backoff = this.initialBackoff;
      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      for (;;) {
        const { value, done } = await reader.read();
        if (done) break;
        for (const m of parser.push(decoder.decode(value, { stream: true }))) this.deliver(m);
      }
    } catch {
      /* network error, HTTP error or abort: handled by the reconnect below */
    }
    if (this.abort === abort) this.abort = undefined;
    this.connected.set(false);
    if (this.running && !abort.signal.aborted) this.retry(parser.retryMs);
  }

  private deliver(m: SseMessage): void {
    if (m.event !== this.opts.eventName) return;
    let data: unknown;
    try {
      data = JSON.parse(m.data);
    } catch {
      return; // ignore malformed
    }
    try {
      this.opts.onData(data);
    } catch (e) {
      console.error('SSE handler failed', e); // a consumer bug must not drop the stream
    }
  }

  private retry(serverRetryMs: number | null): void {
    clearTimeout(this.timer);
    const delay = Math.max(this.backoff, serverRetryMs ?? 0);
    this.timer = setTimeout(() => void this.open(), delay);
    this.backoff = Math.min(this.backoff * 2, this.maxBackoff);
  }
}
