import { Component, DestroyRef, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Api, Loader, errorText } from '../../core/api.service';
import { EventStream } from '../../core/events.service';
import { IncidentNotification } from '../../core/models';
import { T } from '../../core/texts';
import { UiState } from '../../core/ui-state.service';
import { FieldCall } from '../../features/field-call';
import { SHARED } from '../../shared/ui';

@Component({
  selector: 'fg-incident-detail',
  imports: [RouterLink, FieldCall, ...SHARED],
  template: `
  <div class="page">
    @if (d.data(); as x) {
      @let i = x.incident;
      <div class="page-head">
        <div>
          <div class="kicker">{{ t.incidents.detail }} · <span class="mono">{{ i.code }}</span></div>
          <h1>{{ i.title || i.typeLabel }}</h1>
          <div class="row wrap mt">
            <fg-severity [value]="i.severity" [label]="i.severityLabel" />
            <span class="badge tone-active">{{ i.typeLabel || t.incidents.types[i.type] }}</span>
            <span class="badge" [class.tone-ok]="i.status === 'RESOLVED' || i.status === 'CLOSED'" [class.tone-warn]="i.status === 'NEW' || i.status === 'NOTIFIED'" [class.tone-info]="i.status === 'ACKNOWLEDGED' || i.status === 'IN_PROGRESS'">{{ i.statusLabel || t.incidents.statuses[i.status] }}</span>
            @if (i.certainty) { <span class="badge tone-muted">{{ t.incidents.certainty }}: {{ t.incidents.certainties[i.certainty] || i.certainty }}</span> }
            @if (i.source) { <span class="badge tone-muted">{{ t.incidents.source }}: {{ t.incidents.sources[i.source] || i.source }}</span> }
            <fg-origin [origin]="i.dataOrigin" [showReal]="true" />
          </div>
        </div>
        <div class="row">
          <button class="btn ghost" (click)="ui.phoneOpen.set(true)"><span class="ms">smartphone</span>{{ t.incidents.phone }}</button>
          <button class="btn ghost" (click)="ask()"><span class="ms">smart_toy</span>{{ t.incidents.askAssistant }}</button>
        </div>
      </div>

      <div class="grid side">
        <div class="col">
          <div class="card">
            <div class="card-head"><h3>{{ t.incidents.detail }}</h3></div>
            <dl class="kv">
              <dt>ID</dt><dd class="mono">{{ i.code }} <span class="muted tiny">{{ i.id }}</span></dd>
              <dt>{{ t.incidents.type }}</dt><dd>{{ i.typeLabel }}</dd>
              <dt>{{ t.incidents.created }}</dt><dd>{{ i.createdAt | dt: true }}</dd>
              @if (i.zone || i.station || i.asset) { <dt>{{ t.incidents.zone }} / {{ t.incidents.station }} / {{ t.incidents.asset }}</dt><dd>{{ [i.zone, i.station, i.asset] | join }}</dd> }
              <dt>{{ t.incidents.description }}</dt><dd style="white-space: pre-wrap">{{ i.description || '—' }}</dd>
              <dt>{{ t.incidents.owner }}</dt><dd>{{ i.owner || t.incidents.noOwner }}</dd>
              <dt>{{ t.incidents.recipients }}</dt><dd>{{ recipients() }}</dd>
              @if (i.resolution) { <dt>{{ t.incidents.resolution }}</dt><dd>{{ i.resolution }} <span class="muted small">· {{ i.resolvedAt | dt }}</span></dd> }
            </dl>
          </div>

          <div class="card">
            <div class="card-head"><h3>{{ t.incidents.notifications }} ({{ x.notifications.length }})</h3><span class="badge sim">{{ t.incidents.mode }}: {{ modeLabel() }}</span></div>
            <div class="notifs">
              @for (n of x.notifications; track n.id) {
                <div class="nt" [class.esc]="n.status === 'ESCALATED'" [class.conf]="!!n.confirmedAt">
                  <div class="row between wrap">
                    <span class="row"><span class="ms">person</span><b>{{ n.recipientName }}</b><span class="muted small">{{ n.roleLabel || n.role }} · {{ n.phoneMasked }}</span>@if (n.level) { <span class="badge tone-muted">nivel {{ n.level }}</span> }</span>
                    <span class="row">
                      <span class="badge sim">{{ modeOf(n) }}</span>
                      <span class="badge" [class.tone-ok]="n.status === 'CONFIRMED'" [class.tone-bad]="n.status === 'ESCALATED'" [class.tone-info]="n.status !== 'CONFIRMED' && n.status !== 'ESCALATED'">{{ n.statusLabel || t.incidents.notifStatuses[n.status] || n.status }}</span>
                      <span class="badge tone-muted">{{ n.kind }}</span>
                    </span>
                  </div>
                  <div class="wa-bubble mt"><fg-wa [text]="n.body" /></div>
                  <div class="row between wrap tiny muted mt">
                    <span>creat {{ n.createdAt | tm }} @if (n.sentAt) { · trimis {{ n.sentAt | tm }} } @if (n.deliveredAt) { · livrat {{ n.deliveredAt | tm }} } @if (n.escalatedAt) { · escaladat {{ n.escalatedAt | tm }} }</span>
                    @if (n.confirmedAt) { <span class="ok"><span class="ms">done_all</span> {{ t.incidents.confirmedBy }} {{ n.confirmedBy }} · {{ n.confirmedAt | tm }}</span> }
                    @else if (n.kind === 'ALERT' && n.escalationDueAt && n.status !== 'ESCALATED' && n.status !== 'CLOSED') {
                      <span class="countdown" [class.urgent]="left(n) < 30">{{ t.incidents.escalatesIn }} {{ fmtLeft(n) }}</span>
                    }
                    @if (!n.confirmedAt && n.kind === 'ALERT' && n.status !== 'CLOSED') { <button class="btn ok sm" (click)="confirm(n)">{{ t.incidents.confirmOk }}</button> }
                  </div>
                </div>
              } @empty { <fg-state [empty]="true" /> }
            </div>
          </div>

          <div class="card">
            <div class="card-head"><h3>{{ t.field.call }}</h3><span class="badge sim">{{ t.app.simulated }}</span></div>
            <fg-field-call [incidentId]="i.id" />
          </div>
        </div>

        <div class="col">
          <div class="card glow">
            <div class="card-head"><h3>{{ t.incidents.allowed }}</h3></div>
            <div class="col">
              <label class="field"><span>{{ t.incidents.note }}</span><textarea class="input" rows="3" [value]="note()" (input)="note.set($any($event.target).value)"></textarea></label>
              <div class="row wrap">
                @if (i.status === 'NEW' || i.status === 'NOTIFIED') { <button class="btn ok sm" (click)="ack()"><span class="ms">task_alt</span>{{ t.incidents.acknowledge }}</button> }
                @for (s of x.allowedTransitions; track s) {
                  <button class="btn sm" [class.ghost]="s !== 'RESOLVED'" [class.ok]="s === 'RESOLVED'" (click)="status(s)" [disabled]="s === 'RESOLVED' && !note().trim()">{{ t.incidents.statuses[s] || s }}</button>
                }
              </div>
              @if (x.allowedTransitions.includes('RESOLVED')) { <div class="tiny muted">{{ t.incidents.resolutionRequired }}</div> }
              <div class="row"><input class="input" [placeholder]="t.incidents.owner" [value]="owner()" (input)="owner.set($any($event.target).value)" />
                <button class="btn ghost sm" (click)="assign()" [disabled]="!owner().trim()">{{ t.incidents.assign }}</button></div>
              @if (actionError()) { <div class="small" style="color: var(--red)">{{ actionError() }}</div> }
            </div>
          </div>

          <div class="card">
            <div class="card-head"><h3>{{ t.incidents.files }} ({{ x.files.length }})</h3></div>
            @for (f of x.files; track f.fileId) {
              <a class="row fl" [routerLink]="['/files', f.fileId]"><span class="ms">description</span><span class="grow ellipsis small">{{ f.name }}</span><span class="tiny muted">{{ f.category | cat }}</span><fg-integrity [status]="f.integrityStatus" /></a>
            } @empty { <div class="muted small">—</div> }
          </div>

          <div class="card">
            <div class="card-head"><h3>{{ t.incidents.timeline }}</h3></div>
            <ul class="timeline">
              @for (e of x.timeline; track e.id) {
                <li [class.ok]="e.kind.includes('RESOLV') || e.kind.includes('CONFIRM')" [class.warn]="e.kind.includes('ESCAL')"><div class="when">{{ e.at | dt: true }} · {{ e.actor || '—' }}</div><div class="small">{{ e.message }}</div></li>
              } @empty { <fg-state [empty]="true" /> }
            </ul>
          </div>
        </div>
      </div>
    } @else {
      <fg-state [loading]="d.loading()" [unavailable]="d.unavailable()" [error]="d.error()" (retry)="d.load()" />
    }
  </div>`,
  styles: [`
    .notifs { display: flex; flex-direction: column; gap: 12px; }
    .nt { border: 1px solid var(--line); border-radius: 10px; padding: 10px 12px; background: rgba(6,18,58,.4); }
    .nt.esc { border-color: rgba(244,63,94,.6); } .nt.conf { border-color: rgba(52,211,153,.45); }
    .ok { color: var(--green); display: inline-flex; gap: 4px; align-items: center; } .ok .ms { font-size: 14px; }
    .countdown { font-family: var(--mono); color: var(--amber); font-weight: 600; } .countdown.urgent { color: var(--red); animation: pulse 1s infinite; }
    .fl { padding: 7px 4px; border-bottom: 1px solid rgba(34,64,143,.6); color: inherit; text-decoration: none !important; } .fl:hover { background: rgba(255,119,188,.04); }
  `],
})
export class IncidentDetailPage {
  readonly t = T;
  readonly id = input.required<string>();
  readonly ui = inject(UiState);
  private readonly api = inject(Api);
  private readonly events = inject(EventStream);
  readonly d = new Loader(() => this.api.incident(this.id()));
  readonly note = signal('');
  readonly owner = signal('');
  readonly actionError = signal<string | null>(null);
  readonly mode = signal<string>('SIMULATED');
  readonly now = signal(Date.now());
  readonly recipients = computed(() => Array.from(new Set((this.d.data()?.notifications ?? []).map((n) => `${n.recipientName} (${n.roleLabel || n.role})`))).join(', ') || '—');

  constructor() {
    effect(() => { this.id(); untracked(() => this.d.load()); });
    effect(() => this.ui.simulatedOnPage.set(this.d.data()?.incident.dataOrigin === 'SIMULATED' || this.mode() === 'SIMULATED'));
    this.api.incidentConfig().subscribe({ next: (c) => this.mode.set(c.mode), error: () => {} });
    const sub = this.events.incidentEvents$.subscribe((e) => {
      if (e.incident?.id === this.id() || e.notification?.incidentId === this.id()) this.d.load(true);
    });
    const tick = setInterval(() => this.now.set(Date.now()), 1000);
    inject(DestroyRef).onDestroy(() => { sub.unsubscribe(); clearInterval(tick); });
  }

  modeLabel(): string { return this.mode() === 'REAL' ? T.incidents.modeSent : this.mode() === 'PREPARED_ONLY' ? T.incidents.modePrepared : T.incidents.modeSimulated; }
  modeOf(n: IncidentNotification): string {
    return n.mode === 'REAL' || n.status === 'SENT' || n.status === 'DELIVERED' ? T.incidents.modeSent : n.mode === 'PREPARED_ONLY' || n.status === 'PREPARED' ? T.incidents.modePrepared : T.incidents.modeSimulated;
  }
  left(n: IncidentNotification): number { return n.escalationDueAt ? Math.max(0, Math.round((new Date(n.escalationDueAt).getTime() - this.now()) / 1000)) : 0; }
  fmtLeft(n: IncidentNotification): string { const s = this.left(n); return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`; }

  private done = { next: () => { this.actionError.set(null); this.note.set(''); this.d.load(true); }, error: (e: unknown) => this.actionError.set(errorText(e)) };
  ack(): void { this.api.acknowledge(this.id(), this.note()).subscribe(this.done); }
  status(s: string): void { this.api.setStatus(this.id(), s, this.note()).subscribe(this.done); }
  assign(): void { this.api.assign(this.id(), this.owner()).subscribe({ ...this.done, next: () => { this.owner.set(''); this.d.load(true); } }); }
  confirm(n: IncidentNotification): void { this.api.confirmNotification(n.id, n.recipientName).subscribe(this.done); }
  ask(): void {
    const i = this.d.data()?.incident;
    this.ui.openAssistant({ incidentId: this.id() }, `Rezumă incidentul ${i?.code ?? ''}: ce știm, ce fișiere sunt asociate și cine a confirmat notificările.`);
  }
}
