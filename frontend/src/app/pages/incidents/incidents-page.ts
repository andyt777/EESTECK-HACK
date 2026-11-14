import { Component, DestroyRef, computed, effect, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { Api, Loader } from '../../core/api.service';
import { EventStream } from '../../core/events.service';
import { T } from '../../core/texts';
import { UiState } from '../../core/ui-state.service';
import { ReportIncident } from '../../features/report-incident';
import { SHARED } from '../../shared/ui';

@Component({
  selector: 'fg-incidents',
  imports: [RouterLink, MatDialogModule, ...SHARED],
  template: `
  <div class="page">
    <div class="page-head">
      <div><div class="kicker">Security & Incident Center</div><h1>{{ t.incidents.title }}</h1><div class="lead">{{ t.incidents.lead }}</div></div>
      <div class="row">
        <span class="row small muted"><span class="dot" [class.ok]="events.incidentsConnected()" [class.bad]="!events.incidentsConnected()"></span>{{ events.incidentsConnected() ? t.app.live : t.app.offline }}</span>
        <button class="btn ghost" (click)="ui.phoneOpen.set(true)"><span class="ms">smartphone</span>{{ t.incidents.phone }}</button>
        <button class="btn danger" (click)="report()"><span class="ms">campaign</span>{{ t.incidents.report }}</button>
      </div>
    </div>
    <div class="card mb"><div class="filters">
      <label class="field"><span>{{ t.incidents.type }}</span><select class="input" (change)="setF('type', $any($event.target).value)">
        <option value="">{{ t.app.all }}</option>@for (x of types; track x) { <option [value]="x">{{ t.incidents.types[x] }}</option> }</select></label>
      <label class="field"><span>{{ t.incidents.severity }}</span><select class="input" (change)="setF('severity', $any($event.target).value)">
        <option value="">{{ t.app.all }}</option>@for (x of sevs; track x) { <option [value]="x">{{ t.incidents.severities[x] }}</option> }</select></label>
      <label class="field"><span>{{ t.incidents.status }}</span><select class="input" (change)="setF('status', $any($event.target).value)">
        <option value="">{{ t.app.all }}</option>@for (x of statuses; track x) { <option [value]="x">{{ t.incidents.statuses[x] }}</option> }</select></label>
      <label class="field"><span>{{ t.incidents.zone }}</span><input class="input" (change)="setF('zone', $any($event.target).value)" /></label>
      <label class="field"><span>{{ t.files.from }}</span><input class="input" type="datetime-local" (change)="setF('from', $any($event.target).value ? toIso($any($event.target).value) : '')" /></label>
      <label class="field"><span>{{ t.files.to }}</span><input class="input" type="datetime-local" (change)="setF('to', $any($event.target).value ? toIso($any($event.target).value) : '')" /></label>
      <label class="check"><input type="checkbox" [checked]="f()['open'] === 'true'" (change)="setF('open', $any($event.target).checked ? 'true' : '')" />{{ t.incidents.openOnly }}</label>
    </div></div>
    <div class="card flush">
      @if (list.data(); as p) {
        <div class="table-wrap"><table class="tbl">
          <thead><tr><th>ID</th><th>{{ t.incidents.severity }}</th><th>{{ t.incidents.type }}</th><th>{{ t.incidents.description }}</th><th>{{ t.incidents.zone }}</th><th>{{ t.incidents.status }}</th><th>{{ t.incidents.notifications }}</th><th>{{ t.incidents.owner }}</th><th>{{ t.incidents.created }}</th></tr></thead>
          <tbody>
            @for (i of p.items; track i.id) {
              <tr class="click" (click)="open(i.id)" [class.sel]="i.severity === 'CRITICAL' && i.status !== 'RESOLVED' && i.status !== 'CLOSED'">
                <td class="mono small nowrap"><a [routerLink]="['/incidents', i.id]" (click)="$event.stopPropagation()">{{ i.code }}</a></td>
                <td><fg-severity [value]="i.severity" [label]="i.severityLabel" /></td>
                <td class="small">{{ i.typeLabel || t.incidents.types[i.type] }} <fg-origin [origin]="i.dataOrigin" /></td>
                <td class="small"><b>{{ i.title }}</b><div class="muted ellipsis" style="max-width: 340px">{{ i.description }}</div></td>
                <td class="small">{{ [i.zone, i.station, i.asset] | join }}</td>
                <td><span class="badge" [class.tone-ok]="i.status === 'RESOLVED' || i.status === 'CLOSED'" [class.tone-warn]="i.status === 'NEW' || i.status === 'NOTIFIED'" [class.tone-info]="i.status === 'ACKNOWLEDGED' || i.status === 'IN_PROGRESS'">{{ i.statusLabel || t.incidents.statuses[i.status] }}</span></td>
                <td class="small nowrap">{{ i.notificationCount }} @if (i.unconfirmedCount > 0) { <span class="badge tone-warn">{{ i.unconfirmedCount }} {{ t.incidents.unconfirmed }}</span> }</td>
                <td class="small">{{ i.owner || t.incidents.noOwner }}</td>
                <td class="small nowrap">{{ i.createdAt | dt }}</td>
              </tr>
            } @empty { <tr><td colspan="9"><fg-state [empty]="true" /></td></tr> }
          </tbody></table></div>
        <div class="pager"><span>{{ p.total | num }} {{ t.app.results }}</span>
          <button class="btn ghost sm" [disabled]="page() === 0" (click)="go(page() - 1)">{{ t.app.prev }}</button>
          <span>{{ t.app.page }} {{ page() + 1 }} {{ t.app.of }} {{ pages() }}</span>
          <button class="btn ghost sm" [disabled]="page() + 1 >= pages()" (click)="go(page() + 1)">{{ t.app.next }}</button></div>
      } @else { <fg-state [loading]="list.loading()" [unavailable]="list.unavailable()" [error]="list.error()" (retry)="list.load()" /> }
    </div>
  </div>`,
})
export class IncidentsPage {
  readonly t = T;
  readonly types = Object.keys(T.incidents.types);
  readonly sevs = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'];
  readonly statuses = Object.keys(T.incidents.statuses);
  readonly ui = inject(UiState);
  readonly events = inject(EventStream);
  private readonly api = inject(Api);
  private readonly router = inject(Router);
  private readonly dialog = inject(MatDialog);
  readonly f = signal<Record<string, string>>({});
  readonly page = signal(0);
  readonly list = new Loader(() => this.api.incidents({ ...this.f(), page: this.page(), size: 25 }));
  readonly pages = computed(() => Math.max(1, Math.ceil((this.list.data()?.total ?? 0) / 25)));

  constructor() {
    this.list.load();
    const sub = this.events.incidentEvents$.subscribe(() => this.list.load(true));
    inject(DestroyRef).onDestroy(() => sub.unsubscribe());
    effect(() => this.ui.simulatedOnPage.set((this.list.data()?.items ?? []).some((i) => i.dataOrigin === 'SIMULATED')));
  }
  toIso(v: string): string { return new Date(v).toISOString(); }
  setF(k: string, v: string): void { this.f.update((o) => { const n = { ...o, [k]: v }; if (!v) delete n[k]; return n; }); this.page.set(0); this.list.load(); }
  go(n: number): void { this.page.set(n); this.list.load(); }
  open(id: string): void { this.router.navigate(['/incidents', id]); }
  report(): void { this.dialog.open(ReportIncident, { maxWidth: '96vw' }).afterClosed().subscribe(() => this.list.load(true)); }
}
