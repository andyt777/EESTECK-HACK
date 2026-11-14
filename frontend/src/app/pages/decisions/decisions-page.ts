import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Api, Loader } from '../../core/api.service';
import { CATEGORIES, DecisionView, STORAGES } from '../../core/models';
import { T } from '../../core/texts';
import { DecisionPanel } from '../../features/decision-panel';
import { SHARED } from '../../shared/ui';

@Component({
  selector: 'fg-decisions',
  imports: [RouterLink, DecisionPanel, ...SHARED],
  template: `
  <div class="page">
    <div class="page-head"><div><div class="kicker">Agent 01</div><h1>{{ t.decision.title }}</h1><div class="lead">{{ t.decision.lead }}</div></div>
      <a class="btn ghost" routerLink="/policies"><span class="ms">rule</span>{{ t.nav.policies }}</a></div>
    <div class="card mb"><div class="filters">
      <label class="field"><span>{{ t.files.category }}</span><select class="input" (change)="setF('category', $any($event.target).value)">
        <option value="">{{ t.app.all }}</option>@for (c of cats; track c) { <option [value]="c">{{ t.category[c] }}</option> }</select></label>
      <label class="field"><span>{{ t.files.storage }}</span><select class="input" (change)="setF('storage', $any($event.target).value)">
        <option value="">{{ t.app.all }}</option>@for (s of storages; track s) { <option [value]="s">{{ t.storage[s] }}</option> }</select></label>
      <label class="field"><span>{{ t.decision.fallback }}</span><select class="input" (change)="setF('fallback', $any($event.target).value)">
        <option value="">{{ t.app.all }}</option><option value="true">{{ t.app.yes }}</option><option value="false">{{ t.app.no }}</option></select></label>
    </div></div>
    <div class="grid" [class.g2]="!!sel()">
      <div class="card flush">
        @if (list.data(); as p) {
          <div class="table-wrap"><table class="tbl">
            <thead><tr><th>{{ t.decision.file }}</th><th>{{ t.files.category }}</th><th>{{ t.decision.selected }}</th><th>{{ t.decision.fallback }}</th><th>{{ t.decision.result }}</th><th>{{ t.decision.policyVersion }}</th><th>{{ t.decision.decidedAt }}</th></tr></thead>
            <tbody>
              @for (d of p.items; track d.id) {
                <tr class="click" [class.sel]="selId() === d.fileId" (click)="open(d.fileId)">
                  <td><a [routerLink]="['/files', d.fileId]" (click)="$event.stopPropagation()">{{ d.fileName }}</a><div class="tiny muted ellipsis" style="max-width: 360px">{{ d.explanation }}</div></td>
                  <td class="small">{{ d.category | cat }}</td>
                  <td><fg-storage [storage]="d.selectedStorage" /></td>
                  <td>@if (d.fallbackUsed) { <span class="badge tone-warn">{{ t.decision.fallbackUsed }}</span> } @else { <span class="muted small">—</span> }</td>
                  <td><fg-status [status]="d.fileStatus" /></td>
                  <td class="mono small">{{ d.policyVersion }}</td>
                  <td class="small nowrap">{{ d.decidedAt | dt }}</td>
                </tr>
              } @empty { <tr><td colspan="7"><fg-state [empty]="true" /></td></tr> }
            </tbody></table></div>
          <div class="pager"><span>{{ p.total | num }} {{ t.app.results }}</span>
            <button class="btn ghost sm" [disabled]="page() === 0" (click)="go(page() - 1)">{{ t.app.prev }}</button>
            <span>{{ t.app.page }} {{ page() + 1 }} {{ t.app.of }} {{ pages() }}</span>
            <button class="btn ghost sm" [disabled]="page() + 1 >= pages()" (click)="go(page() + 1)">{{ t.app.next }}</button></div>
        } @else { <fg-state [loading]="list.loading()" [unavailable]="list.unavailable()" [error]="list.error()" (retry)="list.load()" /> }
      </div>
      @if (sel(); as d) {
        <div class="card glow">
          <div class="card-head"><h3>{{ t.decision.open }}</h3>
            <div class="row"><a class="btn ghost sm" [routerLink]="['/files', d.fileId]">{{ t.control.openPassport }}</a>
              <button class="btn ghost sm icon" (click)="sel.set(null); selId.set(null)"><span class="ms">close</span></button></div></div>
          <fg-decision [d]="d" />
        </div>
      }
    </div>
  </div>`,
})
export class DecisionsPage {
  readonly t = T;
  readonly cats = CATEGORIES;
  readonly storages = STORAGES;
  private readonly api = inject(Api);
  readonly f = signal<Record<string, string>>({});
  readonly page = signal(0);
  readonly list = new Loader(() => this.api.decisions({ ...this.f(), page: this.page(), size: 25 }));
  readonly pages = computed(() => Math.max(1, Math.ceil((this.list.data()?.total ?? 0) / 25)));
  readonly sel = signal<DecisionView | null>(null);
  readonly selId = signal<string | null>(null);

  constructor() { this.list.load(); }
  setF(k: string, v: string): void { this.f.update((o) => { const n = { ...o, [k]: v }; if (!v) delete n[k]; return n; }); this.page.set(0); this.list.load(); }
  go(n: number): void { this.page.set(n); this.list.load(); }
  open(fileId: string): void {
    this.selId.set(fileId);
    this.api.decision(fileId).subscribe({ next: (d) => this.sel.set(d), error: () => this.sel.set(null) });
  }
}
