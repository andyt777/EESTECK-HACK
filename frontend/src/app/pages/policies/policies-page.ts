import { Component, computed, inject, signal } from '@angular/core';
import { Api, Loader, asList, errorText } from '../../core/api.service';
import { CATEGORIES } from '../../core/models';
import { T } from '../../core/texts';
import { SHARED } from '../../shared/ui';

@Component({
  selector: 'fg-policies',
  imports: [...SHARED],
  template: `
  <div class="page">
    <div class="page-head"><div><div class="kicker">Agent 01</div><h1>{{ t.policies.title }}</h1><div class="lead">{{ t.policies.lead }}</div></div></div>
    @if (active.data(); as a) {
      <div class="grid g4 mb">
        <div class="card kpi info"><span class="label">{{ t.policies.version }}</span><span class="value">{{ a.label }}</span><span class="sub mono">{{ a.contentSha256?.slice(0, 16) }}…</span></div>
        <div class="card kpi"><span class="label">{{ t.policies.source }}</span><span class="value" style="font-size:20px">{{ a.source }}</span><span class="sub">{{ a.author }}</span></div>
        <div class="card kpi"><span class="label">{{ t.policies.createdAt }}</span><span class="value" style="font-size:18px">{{ a.createdAt | dt }}</span><span class="sub">{{ a.file?.status || a.file?.state || '' }}</span></div>
        <div class="card kpi"><span class="label">{{ t.policies.history }}</span><span class="value">{{ history.data()?.total ?? '—' }}</span><span class="sub">versiuni</span></div>
      </div>
      <div class="card mb">
        <div class="card-head"><h3>{{ t.policies.active }}</h3></div>
        <table class="tbl"><thead><tr><th>{{ t.files.category }}</th><th>{{ t.policies.primary }}</th><th>{{ t.policies.fallbacks }}</th><th>Retry</th><th>SLA</th><th>Ponderi</th></tr></thead>
          <tbody>@for (c of cats; track c) { @if (a.policy?.categories?.[c]; as p) {
            <tr><td><b>{{ t.category[c] }}</b></td><td><fg-storage [storage]="p.primary" /></td>
              <td><span class="row">@for (f of p.fallbacks; track f) { <fg-storage [storage]="f" /> }</span></td>
              <td class="mono tiny">{{ p.retry?.maxAttempts }}× · {{ p.retry?.initialBackoff }}→{{ p.retry?.maxBackoff }}</td>
              <td class="mono tiny">pending ≤ {{ p.sla?.maxPendingSeconds }}s · p95 ≤ {{ p.sla?.maxStoreP95Seconds }}s</td>
              <td class="tiny">{{ weights(p.weights) }}</td></tr> } }
          </tbody></table>
      </div>
      <div class="grid g2">
        <div class="card">
          <div class="card-head"><h3>{{ t.policies.yaml }}</h3>
            <div class="row">@if (editing()) { <button class="btn ghost sm" (click)="editing.set(false)">{{ t.app.cancel }}</button><button class="btn sm" (click)="apply()">{{ t.policies.apply }}</button> }
              @else { <button class="btn ghost sm" (click)="startEdit(a.yaml)"><span class="ms">edit</span>{{ t.policies.edit }}</button> }</div></div>
          @if (editing()) { <textarea class="input mono" style="min-height: 460px" [value]="draft()" (input)="draft.set($any($event.target).value)"></textarea> }
          @else { <pre class="code">{{ a.yaml }}</pre> }
          @if (msg()) { <div class="banner mt" [class.info]="!msgBad()" [class.bad]="msgBad()" style="margin-bottom:0"><span class="ms">{{ msgBad() ? 'error' : 'check_circle' }}</span><div class="small" style="white-space: pre-wrap">{{ msg() }}</div></div> }
        </div>
        <div class="card">
          <div class="card-head"><h3>{{ t.policies.history }}</h3></div>
          <ul class="timeline">
            @for (v of historyItems(); track v.version) {
              <li><div class="row between"><b>{{ v.label }}</b><span class="when">{{ v.createdAt | dt: true }}</span></div>
                <div class="small muted">{{ v.source }} · {{ v.author }}</div>
                @if (v.diff) { <pre class="code mt" style="max-height: 180px">{{ diffText(v.diff) }}</pre> }</li>
            } @empty { <fg-state [empty]="true" /> }
          </ul>
        </div>
      </div>
    } @else { <fg-state [loading]="active.loading()" [unavailable]="active.unavailable()" [error]="active.error()" (retry)="active.load()" /> }
  </div>`,
})
export class PoliciesPage {
  readonly t = T;
  readonly cats = CATEGORIES;
  private readonly api = inject(Api);
  readonly active = new Loader(() => this.api.policies());
  readonly history = new Loader(() => this.api.policyHistory({ size: 50 }));
  readonly historyItems = computed(() => asList<any>(this.history.data()));
  readonly editing = signal(false);
  readonly draft = signal('');
  readonly msg = signal<string | null>(null);
  readonly msgBad = signal(false);

  constructor() { this.active.load(); this.history.load(); }
  weights(w: any): string { return Object.entries(w ?? {}).map(([k, v]) => `${T.decision.factorNames[k] ?? k} ${v}`).join(' · '); }
  diffText(d: any): string { return typeof d === 'string' ? d : JSON.stringify(d, null, 2); }
  startEdit(y: string): void { this.draft.set(y); this.editing.set(true); this.msg.set(null); }
  apply(): void {
    this.api.putPolicy(this.draft()).subscribe({
      next: () => { this.msg.set(T.policies.applied); this.msgBad.set(false); this.editing.set(false); this.active.load(); this.history.load(); },
      error: (e) => {
        const errs = (e?.error?.errors ?? []) as any[];
        this.msg.set(`${T.policies.refused}: ${errorText(e)}${errs.length ? '\n' + errs.map((x) => (typeof x === 'string' ? x : JSON.stringify(x))).join('\n') : ''}`);
        this.msgBad.set(true);
      },
    });
  }
}
