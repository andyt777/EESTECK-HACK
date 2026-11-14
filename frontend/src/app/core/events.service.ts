import { Injectable, inject, signal } from '@angular/core';
import { Subject } from 'rxjs';
import { AccessTokens } from './auth/access-token.service';
import { IncidentEvent, LiveEvent } from './models';
import { SseConnection } from './sse';

@Injectable({ providedIn: 'root' })
export class EventStream {
  private readonly tokens = inject(AccessTokens);
  private readonly token = (force: boolean) => this.tokens.get(force);

  readonly fileEvents$ = new Subject<LiveEvent>();
  readonly incidentEvents$ = new Subject<IncidentEvent>();
  /** Last events (newest first), for live feeds. Meter readings are aggregated, not listed individually. */
  readonly feed = signal<LiveEvent[]>([]);
  /** Latest event per file. */
  readonly latest = signal<Record<string, LiveEvent>>({});
  /** Meter-reading events seen since load (aggregated flow). */
  readonly meterEvents = signal(0);

  /** Bearer-authenticated SSE (fetch-based); the server closes it at token expiry and we reconnect. */
  private readonly files = new SseConnection({
    url: '/api/events/stream', eventName: 'file-event', token: this.token, onData: (d) => this.onFile(d as LiveEvent),
  });
  private readonly incidents = new SseConnection({
    url: '/api/incidents/stream', eventName: 'incident-event', token: this.token,
    onData: (d) => this.incidentEvents$.next(d as IncidentEvent),
  });

  readonly connected = this.files.connected;
  readonly incidentsConnected = this.incidents.connected;

  start(): void {
    this.files.start();
    this.incidents.start();
  }

  stop(): void {
    this.files.stop();
    this.incidents.stop();
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
