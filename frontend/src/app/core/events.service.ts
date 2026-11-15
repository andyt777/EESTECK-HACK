import { Injectable, NgZone, inject, signal } from '@angular/core';
import { Subject } from 'rxjs';
import { AuthzService } from './auth';
import { IncidentEvent, LiveEvent } from './models';

/** Splits a server-sent event stream into events; returns the dispatched events and the unfinished rest. */
export function parseSse(buffer: string): { events: { event: string; data: string }[]; rest: string } {
  const events: { event: string; data: string }[] = [];
  let text = buffer.replace(/\r\n?/g, '\n');
  let sep: number;
  while ((sep = text.indexOf('\n\n')) >= 0) {
    const block = text.slice(0, sep);
    text = text.slice(sep + 2);
    let event = 'message';
    const data: string[] = [];
    for (const line of block.split('\n')) {
      if (line.startsWith('event:')) event = line.slice(6).trim();
      else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''));
    }
    if (data.length) events.push({ event, data: data.join('\n') });
  }
  return { events, rest: text };
}

/**
 * One server-sent event stream read with fetch, so it can carry the bearer token (EventSource
 * cannot send headers). The server closes the stream when the token expires; it is then reopened
 * with the current (renewed) token. Reconnection uses exponential backoff.
 */
class Stream {
  private abort?: AbortController;
  private backoff = 1000;
  private timer?: ReturnType<typeof setTimeout>;
  readonly connected = signal(false);

  constructor(private url: string, private eventName: string, private onData: (data: any) => void,
              private zone: NgZone, private token: () => Promise<string>) {}

  start(): void {
    if (this.abort) return;
    void this.open();
  }

  private async open(): Promise<void> {
    const abort = new AbortController();
    this.abort = abort;
    try {
      const token = await this.token();
      const headers: Record<string, string> = { Accept: 'text/event-stream' };
      if (token) headers['Authorization'] = 'Bearer ' + token;
      const res = await fetch(this.url, { headers, signal: abort.signal, cache: 'no-store' });
      if (!res.ok || !res.body) throw new Error('SSE ' + res.status);
      this.zone.run(() => { this.connected.set(true); this.backoff = 1000; });
      await this.read(res.body);
      this.backoff = 1000; // closed by the server (token expiry): reconnect promptly with the renewed token
    } catch {
      /* network error or refused: retried below */
    }
    this.zone.run(() => this.connected.set(false));
    if (this.abort === abort) this.retry();
  }

  private async read(body: ReadableStream<Uint8Array>): Promise<void> {
    const reader = body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    for (;;) {
      const { value, done } = await reader.read();
      if (done) return;
      const parsed = parseSse(buffer + decoder.decode(value, { stream: true }));
      buffer = parsed.rest;
      for (const e of parsed.events) {
        if (e.event !== this.eventName) continue;
        try {
          const data = JSON.parse(e.data);
          this.zone.run(() => this.onData(data));
        } catch { /* ignore malformed */ }
      }
    }
  }

  private retry(): void {
    clearTimeout(this.timer);
    this.timer = setTimeout(() => void this.open(), this.backoff);
    this.backoff = Math.min(this.backoff * 2, 30000);
  }
}

@Injectable({ providedIn: 'root' })
export class EventStream {
  private readonly zone = inject(NgZone);
  private readonly authz = inject(AuthzService);
  private readonly token = () => this.authz.token();

  readonly fileEvents$ = new Subject<LiveEvent>();
  readonly incidentEvents$ = new Subject<IncidentEvent>();
  /** Last events (newest first), for live feeds. Meter readings are aggregated, not listed individually. */
  readonly feed = signal<LiveEvent[]>([]);
  /** Latest event per file. */
  readonly latest = signal<Record<string, LiveEvent>>({});
  /** Meter-reading events seen since load (aggregated flow). */
  readonly meterEvents = signal(0);

  private readonly files = new Stream('/api/events/stream', 'file-event', (d) => this.onFile(d), this.zone, this.token);
  private readonly incidents = new Stream('/api/incidents/stream', 'incident-event', (d) => this.incidentEvents$.next(d), this.zone, this.token);

  readonly connected = this.files.connected;
  readonly incidentsConnected = this.incidents.connected;

  start(): void {
    this.files.start();
    this.incidents.start();
  }

  /** Per-file history of non-meter events (bounded), so late subscribers can catch up. */
  readonly history = new Map<string, LiveEvent[]>();

  private onFile(e: LiveEvent): void {
    if (e.category === 'METER_READING') {
      this.meterEvents.update((n) => n + 1);
    } else {
      this.latest.update((m) => ({ ...m, [e.fileId]: e }));
      this.feed.update((f) => [e, ...f].slice(0, 80));
      const h = this.history.get(e.fileId) ?? [];
      h.push(e);
      this.history.set(e.fileId, h);
      if (this.history.size > 300) this.history.delete(this.history.keys().next().value as string);
    }
    this.fileEvents$.next(e);
  }
}
