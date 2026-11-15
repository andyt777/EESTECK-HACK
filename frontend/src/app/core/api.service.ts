import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Injectable, inject, signal, Signal } from '@angular/core';
import { Observable, Subscription, firstValueFrom } from 'rxjs';
import {
  CategorySummary, ChatContext, ChatResponse, DecisionSummary, DecisionView, FileSummary, Finding,
  IncidentConfig, IncidentDetail, IncidentNotification, IncidentSummary, MeterStats, Page, Passport,
  StorageHealth, StorageType, VerifyResult,
} from './models';

export type Params = Record<string, string | number | boolean | null | undefined>;

function params(p?: Params): HttpParams {
  let hp = new HttpParams();
  for (const [k, v] of Object.entries(p ?? {})) {
    if (v !== null && v !== undefined && v !== '') hp = hp.set(k, String(v));
  }
  return hp;
}

/** Human-readable error with the traceId when the backend sends one. */
export function errorText(e: unknown): string {
  if (e instanceof HttpErrorResponse) {
    const body = e.error as any;
    const msg = body?.detail || body?.message || body?.title || (typeof body === 'string' && body.length < 200 ? body : '');
    const trace = body?.traceId || e.headers?.get('traceparent') || '';
    const status = e.status === 0 ? 'rețea' : e.status;
    return `${msg || e.statusText || 'Eroare'} (${status})${trace ? ' · traceId ' + trace : ''}`;
  }
  return String((e as any)?.message ?? e);
}

/** True when the endpoint simply does not exist yet / service is down (show an empty state, not an error). */
export function isUnavailable(e: unknown): boolean {
  return e instanceof HttpErrorResponse && [0, 404, 405, 501, 502, 503, 504].includes(e.status);
}

/** Small signal-based loader: data / error / loading, reloadable, never throws into the template. */
export class Loader<T> {
  readonly data = signal<T | null>(null);
  readonly error = signal<string | null>(null);
  readonly unavailable = signal(false);
  readonly loading = signal(false);
  private sub?: Subscription;

  constructor(private readonly source: () => Observable<T>) {}

  load(silent = false): void {
    this.sub?.unsubscribe();
    if (!silent) this.loading.set(true);
    this.sub = this.source().subscribe({
      next: (v) => {
        this.data.set(v);
        this.error.set(null);
        this.unavailable.set(false);
        this.loading.set(false);
      },
      error: (e) => {
        this.loading.set(false);
        this.unavailable.set(isUnavailable(e));
        this.error.set(errorText(e));
      },
    });
  }

  set(v: T): void { this.data.set(v); }
  destroy(): void { this.sub?.unsubscribe(); }
}

@Injectable({ providedIn: 'root' })
export class Api {
  private readonly http = inject(HttpClient);

  // files & categories
  files(p: Params) { return this.http.get<Page<FileSummary>>('/api/files', { params: params(p) }); }
  categories() { return this.http.get<CategorySummary[]>('/api/categories/summary'); }
  passport(id: string, p?: Params) { return this.http.get<Passport>(`/api/files/${id}/passport`, { params: params(p) }); }
  decision(id: string) { return this.http.get<DecisionView>(`/api/files/${id}/decision`); }
  verify(id: string) { return this.http.post<VerifyResult>(`/api/files/${id}/verify`, {}); }
  /** Short-lived signed URL for places that cannot send the bearer token (img, video, iframe, a download). */
  contentLink(id: string, inline = false, suspect = false) {
    return this.http.post<{ url: string; expiresAt: string }>(`/api/files/${id}/content-link`,
      { disposition: inline ? 'inline' : 'attachment', acknowledgeSuspect: suspect });
  }
  decisions(p: Params) { return this.http.get<Page<DecisionSummary>>('/api/decisions', { params: params(p) }); }
  policies() { return this.http.get<any>('/api/policies'); }
  policyHistory(p?: Params) { return this.http.get<any>('/api/policies/history', { params: params(p) }); }
  putPolicy(yaml: string) {
    return this.http.put<any>('/api/admin/policies', yaml, { headers: { 'Content-Type': 'application/yaml' } });
  }

  // platform
  meterStats() { return this.http.get<MeterStats>('/api/meters/stats'); }
  storageHealth() { return this.http.get<StorageHealth[]>('/api/storage/health'); }
  startMaintenance(storage: StorageType, reason: string) {
    return this.http.put(`/api/admin/storage/${storage}/maintenance`, { reason });
  }
  stopMaintenance(storage: StorageType) { return this.http.delete(`/api/admin/storage/${storage}/maintenance`); }

  // incidents
  incidents(p: Params) { return this.http.get<Page<IncidentSummary>>('/api/incidents', { params: params(p) }); }
  incident(id: string) { return this.http.get<IncidentDetail>(`/api/incidents/${id}`); }
  createIncident(body: any) { return this.http.post<IncidentDetail>('/api/incidents', body); }
  previewIncident(body: any) {
    return this.http.post<{ role: string; roleLabel: string; recipientName: string; body: string }[]>('/api/incidents/preview', body);
  }
  acknowledge(id: string, note: string) { return this.http.post<any>(`/api/incidents/${id}/acknowledge`, { note }); }
  assign(id: string, owner: string) { return this.http.post<any>(`/api/incidents/${id}/assign`, { owner }); }
  setStatus(id: string, status: string, note: string) {
    return this.http.post<any>(`/api/incidents/${id}/status`, { status, note });
  }
  notifications(p: Params) { return this.http.get<Page<IncidentNotification>>('/api/incidents/notifications', { params: params(p) }); }
  confirmNotification(id: string, confirmedBy: string) {
    return this.http.post<any>(`/api/incidents/notifications/${id}/confirm`, { confirmedBy });
  }
  incidentConfig() { return this.http.get<IncidentConfig>('/api/incidents/config'); }

  // intelligence
  findings(p: Params) { return this.http.get<any>('/api/intelligence/findings', { params: params(p) }); }
  intelSummary() { return this.http.get<any>('/api/intelligence/summary'); }
  grid() { return this.http.get<any>('/api/intelligence/grid'); }
  findingStatus(id: string, status: string, note: string) {
    return this.http.post<any>(`/api/intelligence/findings/${id}/status`, { status, note });
  }
  analyze() { return this.http.post<any>('/api/intelligence/analyze', {}); }
  evaluation() { return this.http.get<any>('/api/intelligence/evaluation'); }
  chat(message: string, history: { role: string; content: string }[], context: ChatContext) {
    return this.http.post<ChatResponse>('/api/intelligence/assistant/chat', { message, history, context });
  }
  voiceCall(incidentId: string, role: string) { return this.http.post<any>('/api/intelligence/voice/call', { incidentId, role }); }
  voiceCalls(incidentId?: string) { return this.http.get<any>('/api/intelligence/voice/calls', { params: params({ incidentId }) }); }
  fieldReport(body: any) { return this.http.post<any>('/api/intelligence/voice/field-report', body); }

  promise<T>(o: Observable<T>): Promise<T> { return firstValueFrom(o); }
}

/** Normalises list-ish responses ({items}, {findings}, plain array) into an array. */
export function asList<T = any>(v: any): T[] {
  if (Array.isArray(v)) return v;
  if (!v || typeof v !== 'object') return [];
  for (const k of ['items', 'findings', 'calls', 'content', 'data', 'results']) {
    if (Array.isArray(v[k])) return v[k];
  }
  return [];
}

export type ReadonlySig<T> = Signal<T>;
