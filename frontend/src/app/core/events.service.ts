import { Injectable, NgZone, inject, signal } from '@angular/core';
import { Subject } from 'rxjs';
import { IncidentEvent, LiveEvent } from './models';

/** One EventSource with reconnection and exponential backoff. */
class Stream {
  private es?: EventSource;
  private backoff = 1000;
  private timer?: ReturnType<typeof setTimeout>;
  readonly connected = signal(false);

  constructor(private url: string, private eventName: string, private onData: (data: any) => void, private zone: NgZone) {}

  start(): void {
    if (this.es) return;
    this.open();
  }

  private open(): void {
    try {
      this.es = new EventSource(this.url);
    } catch {
      this.retry();
      return;
    }
    this.es.onopen = () => this.zone.run(() => { this.connected.set(true); this.backoff = 1000; });
    this.es.addEventListener(this.eventName, (m: MessageEvent) => {
      try {
        const data = JSON.parse(m.data);
        this.zone.run(() => this.onData(data));
      } catch { /* ignore malformed */ }
    });
    this.es.onerror = () => {
      this.zone.run(() => this.connected.set(false));
      this.es?.close();
      this.es = undefined;
      this.retry();
    };
  }

  private retry(): void {
    clearTimeout(this.timer);
    this.timer = setTimeout(() => this.open(), this.backoff);
    this.backoff = Math.min(this.backoff * 2, 30000);
  }
}

@Injectable({ providedIn: 'root' })
export class EventStream {
  private readonly zone = inject(NgZone);

  readonly fileEvents$ = new Subject<LiveEvent>();
  readonly incidentEvents$ = new Subject<IncidentEvent>();
  /** Last events (newest first), for live feeds. Meter readings are aggregated, not listed individually. */
  readonly feed = signal<LiveEvent[]>([]);
  /** Latest event per file. */
  readonly latest = signal<Record<string, LiveEvent>>({});
  /** Meter-reading events seen since load (aggregated flow). */
  readonly meterEvents = signal(0);

  private readonly files = new Stream('/api/events/stream', 'file-event', (d) => this.onFile(d), this.zone);
  private readonly incidents = new Stream('/api/incidents/stream', 'incident-event', (d) => this.incidentEvents$.next(d), this.zone);

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
