import { Component, inject, signal } from '@angular/core';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { Router } from '@angular/router';
import { Subject, debounceTime, switchMap, catchError, of } from 'rxjs';
import { Api, errorText } from '../core/api.service';
import { IncidentConfig } from '../core/models';
import { T } from '../core/texts';
import { WaText } from '../shared/ui';

const MANUAL = ['GRID_FAULT_MAJOR', 'FIRE_REPORTED', 'FIELD_EMERGENCY', 'CYBER_SECURITY', 'GRID_ANOMALY'];
const SEV = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'];

/** "Raportează incident" with live WhatsApp message preview. */
@Component({
  selector: 'fg-report-incident',
  imports: [WaText],
  template: `
  <div class="dlg">
    <h2><span class="ms" aria-hidden="true">campaign</span> {{ t.incidents.report }}</h2>
    <div class="cols">
      <div class="col">
        <div class="frm">
          <label class="field"><span>{{ t.incidents.type }}</span>
            <select class="input" [value]="m().type" (change)="set('type', $any($event.target).value)">
              @for (x of types(); track x.type) { <option [value]="x.type">{{ x.label }}</option> }
            </select></label>
          <label class="field"><span>{{ t.incidents.severity }}</span>
            <select class="input" [value]="m().severity" (change)="set('severity', $any($event.target).value)">
              @for (s of sevs(); track s.value) { <option [value]="s.value">{{ s.label }}</option> }
            </select></label>
          <label class="field"><span>{{ t.incidents.certainty }} @if (isFire()) {*}</span>
            <select class="input" [value]="m().certainty" (change)="set('certainty', $any($event.target).value)">
              @if (isFire()) { <option value="">— alege —</option> }
              @for (c of certs; track c) { <option [value]="c">{{ t.incidents.certainties[c] }}</option> }
            </select></label>
          <label class="field"><span>{{ t.incidents.source }} @if (isFire()) {*}</span>
            <select class="input" [value]="m().source" (change)="set('source', $any($event.target).value)">
              @if (isFire()) { <option value="">— alege —</option> }
              <option value="USER">{{ t.incidents.sources['USER'] }}</option><option value="SENSOR">{{ t.incidents.sources['SENSOR'] }}</option>
            </select></label>
          <label class="field"><span>{{ t.incidents.zone }}</span><input class="input" [value]="m().zone" (input)="set('zone', $any($event.target).value)" /></label>
          <label class="field"><span>{{ t.incidents.station }}</span><input class="input" [value]="m().station" (input)="set('station', $any($event.target).value)" /></label>
          <label class="field"><span>{{ t.incidents.asset }}</span><input class="input" [value]="m().asset" (input)="set('asset', $any($event.target).value)" /></label>
          <label class="field"><span>{{ t.incidents.titleField }}</span><input class="input" [value]="m().title" (input)="set('title', $any($event.target).value)" /></label>
          <label class="field wide"><span>{{ t.incidents.description }}</span><textarea class="input" rows="3" [value]="m().description" (input)="set('description', $any($event.target).value)"></textarea></label>
          <label class="check wide"><input type="checkbox" [checked]="m().dataOrigin === 'SIMULATED'" (change)="set('dataOrigin', $any($event.target).checked ? 'SIMULATED' : 'REAL')" /> Exercițiu / date simulate (dataOrigin=SIMULATED)</label>
        </div>
        @if (isFire()) { <div class="banner warn small"><span class="ms" aria-hidden="true">local_fire_department</span>{{ t.incidents.fireWarning }}</div> }
        @if (error()) { <div class="banner bad small"><span class="ms" aria-hidden="true">error</span>{{ error() }}</div> }
      </div>
      <div class="col prev">
        <h3>{{ t.incidents.preview }} <span class="badge sim">{{ mode() }}</span></h3>
        @for (p of preview(); track $index) {
          <div class="tiny muted">→ {{ p.recipientName }} · {{ p.roleLabel || p.role }}</div>
          <div class="wa-bubble"><fg-wa [text]="p.body" /></div>
        } @empty { <div class="muted small">{{ previewError() || t.incidents.previewEmpty }}</div> }
      </div>
    </div>
    <div class="row between mt">
      <span class="tiny muted">source=USER · {{ t.incidents.mode }}: {{ mode() }}</span>
      <div class="row"><button class="btn ghost" (click)="ref.close()">{{ t.app.cancel }}</button>
        <button class="btn danger" (click)="submit()" [disabled]="!valid() || busy()"><span class="ms" aria-hidden="true">send</span>{{ t.incidents.submit }}</button></div>
    </div>
  </div>`,
  styles: [`
    .dlg { padding: 20px; width: 1000px; max-width: 94vw; color: var(--text); }
    h2 { display: flex; gap: 8px; align-items: center; margin-bottom: 14px; }
    .cols { display: grid; grid-template-columns: 1.1fr 1fr; gap: 18px; }
    .frm { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; } .wide { grid-column: 1 / -1; }
    .prev { max-height: 60vh; overflow-y: auto; background: #0b141a; border-radius: 10px; padding: 12px; border: 1px solid #1f2c34; }
    .banner { margin: 0; padding: 8px 10px; }
    @media (max-width: 800px) { .cols { grid-template-columns: 1fr; } }
  `],
})
export class ReportIncident {
  readonly t = T;
  readonly certs = ['SUSPECTED', 'REPORTED', 'CONFIRMED'];
  readonly ref = inject(MatDialogRef<ReportIncident>);
  private readonly api = inject(Api);
  private readonly router = inject(Router);
  private readonly data = inject<{ preset?: any } | null>(MAT_DIALOG_DATA, { optional: true });

  readonly cfg = signal<IncidentConfig | null>(null);
  readonly m = signal<any>({ type: 'GRID_FAULT_MAJOR', severity: 'CRITICAL', certainty: 'REPORTED', source: 'USER', zone: '', station: '', asset: '', title: '', description: '', dataOrigin: 'REAL', ...(this.data?.preset ?? {}) });
  readonly preview = signal<{ role: string; roleLabel: string; recipientName: string; body: string }[]>([]);
  readonly previewError = signal<string | null>(null);
  readonly error = signal<string | null>(null);
  readonly busy = signal(false);
  private readonly preview$ = new Subject<any>();

  constructor() {
    this.api.incidentConfig().subscribe({ next: (c) => this.cfg.set(c), error: () => {} });
    this.preview$.pipe(debounceTime(300), switchMap((b) => this.api.previewIncident(b).pipe(catchError((e) => { this.previewError.set(errorText(e)); return of(null); })))).subscribe((r) => {
      if (r) { this.preview.set(r); this.previewError.set(null); } else this.preview.set([]);
    });
    this.refresh();
  }

  types() {
    const c = this.cfg();
    return c ? c.types.filter((x) => x.manual) : MANUAL.map((type) => ({ type, label: T.incidents.types[type] ?? type, manual: true }));
  }
  sevs() { return this.cfg()?.severities ?? SEV.map((value) => ({ value, label: T.incidents.severities[value] })); }
  mode() { return this.cfg()?.mode ?? 'SIMULATED'; }
  isFire() { return this.m().type === 'FIRE_REPORTED'; }
  valid() { const m = this.m(); return !!m.description?.trim() && (!this.isFire() || (!!m.certainty && !!m.source)); }

  set(k: string, v: string): void {
    this.m.update((m) => {
      const n = { ...m, [k]: v };
      if (k === 'type' && v === 'FIRE_REPORTED') { n.certainty = ''; n.source = ''; }
      return n;
    });
    this.refresh();
  }

  private body() {
    const m = this.m();
    const b: any = { ...m };
    for (const k of ['zone', 'station', 'asset', 'title']) if (!b[k]) delete b[k];
    if (!b.certainty) delete b.certainty;
    if (!b.source) b.source = 'USER';
    return b;
  }

  private refresh(): void {
    if (this.m().description?.trim() || this.m().zone || this.m().station) this.preview$.next(this.body());
  }

  submit(): void {
    this.busy.set(true);
    this.api.createIncident(this.body()).subscribe({
      next: (d) => { this.busy.set(false); this.ref.close(d); this.router.navigate(['/incidents', d.incident.id]); },
      error: (e) => { this.busy.set(false); this.error.set(errorText(e)); },
    });
  }
}
