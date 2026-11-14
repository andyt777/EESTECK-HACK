import { Component, inject, signal } from '@angular/core';
import { CanDirective } from '../../core/auth/can.directive';
import { Router } from '@angular/router';
import { Api, asList, errorText, isUnavailable } from '../../core/api.service';
import { IncidentSummary } from '../../core/models';
import { T } from '../../core/texts';
import { Dictation } from '../../core/voice';
import { FieldCall } from '../../features/field-call';
import { SHARED } from '../../shared/ui';

@Component({
  selector: 'fg-field',
  imports: [FieldCall, CanDirective, ...SHARED],
  template: `
  <div class="page">
    <div class="page-head"><div><div class="kicker">Voce · teren</div><h1>{{ t.field.title }}</h1><div class="lead">{{ t.field.lead }}</div></div>
      <span class="badge sim"><span class="ms">science</span>{{ t.app.simulated }}</span></div>
    <div class="grid g2">
      <div class="card">
        <div class="card-head"><h2><span class="ms">record_voice_over</span> {{ t.field.report }}</h2></div>
        <p class="muted small">{{ t.field.reportLead }}</p>
        <div class="row wrap">
          <label class="field grow"><span>{{ t.incidents.zone }}</span><input class="input" [value]="zone()" (input)="zone.set($any($event.target).value)" /></label>
          <label class="field grow"><span>{{ t.incidents.station }}</span><input class="input" [value]="station()" (input)="station.set($any($event.target).value)" /></label>
        </div>
        <label class="field mt"><span>{{ t.field.transcriptField }}</span>
          <textarea class="input" rows="5" [value]="transcript() + (dict.interim() ? ' ' + dict.interim() : '')" (input)="transcript.set($any($event.target).value)"
            placeholder="ex. „Sunt la stația Timișoara Nord, văd fum la transformatorul T2, nu sunt răniți…”"></textarea></label>
        <div class="row mt">
          <button class="btn" [class.danger]="dict.listening()" (click)="dict.toggle()" [disabled]="!dict.supported"><span class="ms" [class.pulse]="dict.listening()">{{ dict.listening() ? 'stop_circle' : 'mic' }}</span>{{ dict.listening() ? t.field.stop : t.field.dictate }}</button>
          <button class="btn ghost" (click)="propose()" [disabled]="!transcript().trim() || busy()"><span class="ms">auto_awesome</span>{{ t.field.analyze }}</button>
          @if (!dict.supported) { <span class="tiny muted">{{ t.assistant.noVoice }}</span> }
        </div>
        @if (error()) { <div class="banner mt" [class.warn]="unavailable()" [class.bad]="!unavailable()"><span class="ms">error</span>{{ unavailable() ? t.app.unavailable : error() }}</div> }
        @if (proposal(); as p) {
          <div class="card mt glow">
            <div class="card-head"><h3>{{ t.field.proposal }}</h3><fg-origin [origin]="p.dataOrigin || 'SIMULATED'" /></div>
            @let inc = p.proposal || p.incident || p;
            <dl class="kv">
              @for (e of entries(inc); track e[0]) { <dt>{{ e[0] }}</dt><dd>{{ e[1] }}</dd> }
            </dl>
            @if (p.warnings?.length) { <div class="small mt" style="color: var(--amber)">{{ p.warnings.join(' · ') }}</div> }
            <div class="row mt">
              @if (created()) { <button class="btn ok" (click)="openCreated()"><span class="ms">open_in_new</span>{{ t.app.open }} incident</button> }
              @else { <button *fgCan="'incident:create'" class="btn danger" (click)="create()" [disabled]="busy()"><span class="ms">campaign</span>{{ t.field.create }}</button> }
            </div>
          </div>
        }
      </div>
      <div class="card">
        <div class="card-head"><h2><span class="ms">call</span> {{ t.field.call }}</h2></div>
        <label class="field"><span>{{ t.field.incident }}</span>
          <select class="input" [value]="incidentId() ?? ''" (change)="incidentId.set($any($event.target).value || null)">
            <option value="">—</option>
            @for (i of incidents(); track i.id) { <option [value]="i.id">{{ i.code }} · {{ i.title || i.typeLabel }}</option> }
          </select></label>
        <div class="mt"><fg-field-call [incidentId]="incidentId()" /></div>
      </div>
    </div>
  </div>`,
})
export class FieldPage {
  readonly t = T;
  private readonly api = inject(Api);
  private readonly router = inject(Router);
  readonly zone = signal('');
  readonly station = signal('');
  readonly transcript = signal('');
  readonly proposal = signal<any>(null);
  readonly created = signal<string | null>(null);
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);
  readonly unavailable = signal(false);
  readonly incidents = signal<IncidentSummary[]>([]);
  readonly incidentId = signal<string | null>(null);
  readonly dict = new Dictation((txt) => this.transcript.set((this.transcript() + ' ' + txt).trim()), true);

  constructor() {
    this.api.incidents({ open: true, size: 50 }).subscribe({ next: (p) => { this.incidents.set(asList(p)); if (!this.incidentId() && p.items?.length) this.incidentId.set(p.items[0].id); }, error: () => {} });
  }

  private body(create: boolean) { return { transcript: this.transcript(), zone: this.zone() || undefined, station: this.station() || undefined, create }; }

  propose(): void {
    this.dict.stop();
    this.busy.set(true); this.error.set(null); this.created.set(null);
    this.api.fieldReport(this.body(false)).subscribe({
      next: (r) => { this.busy.set(false); this.proposal.set(r); },
      error: (e) => { this.busy.set(false); this.unavailable.set(isUnavailable(e)); this.error.set(errorText(e)); },
    });
  }

  create(): void {
    this.busy.set(true);
    this.api.fieldReport(this.body(true)).subscribe({
      next: (r) => {
        this.busy.set(false);
        const id = r?.incident?.incident?.id ?? r?.incident?.id ?? r?.incidentId ?? r?.created?.id ?? null;
        this.created.set(id);
        this.proposal.set({ ...this.proposal(), ...r });
        if (id) this.router.navigate(['/incidents', id]);
      },
      error: (e) => { this.busy.set(false); this.error.set(errorText(e)); },
    });
  }

  openCreated(): void { const id = this.created(); if (id) this.router.navigate(['/incidents', id]); }

  entries(o: any): [string, string][] {
    if (!o || typeof o !== 'object') return [];
    const labels: Record<string, string> = { type: T.incidents.type, severity: T.incidents.severity, certainty: T.incidents.certainty, source: T.incidents.source, zone: T.incidents.zone, station: T.incidents.station, asset: T.incidents.asset, title: 'Titlu', description: T.incidents.description };
    return Object.entries(o).filter(([k, v]) => v !== null && v !== undefined && typeof v !== 'object' && k !== 'dataOrigin')
      .map(([k, v]) => [labels[k] ?? k, k === 'type' ? (T.incidents.types[String(v)] ?? String(v)) : k === 'severity' ? (T.incidents.severities[String(v)] ?? String(v)) : k === 'certainty' ? (T.incidents.certainties[String(v)] ?? String(v)) : String(v)]);
  }
}
