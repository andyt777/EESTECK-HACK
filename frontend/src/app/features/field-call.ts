import { Component, effect, inject, input, signal, untracked } from '@angular/core';
import { Api, asList, errorText, isUnavailable } from '../core/api.service';
import { T } from '../core/texts';
import { canSpeak, speak } from '../core/voice';
import { DatePipe, OriginBadge } from '../shared/ui';

/** Lines of a call script/transcript, whatever the exact shape the service returns. */
export function callLines(c: any): { who: string; text: string }[] {
  if (!c) return [];
  for (const k of ['transcript', 'lines', 'turns', 'messages', 'script']) {
    const v = c[k];
    if (Array.isArray(v)) return v.map((l: any) => (typeof l === 'string' ? { who: '', text: l } : { who: l.speaker ?? l.role ?? l.who ?? '', text: l.text ?? l.content ?? l.message ?? JSON.stringify(l) }));
    if (typeof v === 'string' && v.trim()) return v.split(/\n+/).map((s) => { const m = s.match(/^([^:]{1,30}):\s*(.*)$/); return m ? { who: m[1], text: m[2] } : { who: '', text: s }; });
  }
  return [];
}

@Component({
  selector: 'fg-field-call',
  imports: [OriginBadge, DatePipe],
  template: `
  <div class="row wrap">
    <label class="field" style="min-width: 200px"><span>{{ t.field.role }}</span>
      <select class="input" [value]="role()" (change)="role.set($any($event.target).value)">
        @for (r of roles; track r) { <option [value]="r">{{ t.field.roles[r] }}</option> }
      </select></label>
    <button class="btn" style="align-self: flex-end" (click)="call()" [disabled]="busy() || !incidentId()"><span class="ms">call</span>{{ t.field.call }}</button>
  </div>
  @if (error()) { <div class="small mt" [style.color]="unavailable() ? 'var(--amber)' : 'var(--red)'">{{ unavailable() ? t.app.unavailable : error() }}</div> }
  @for (c of calls(); track $index) {
    <div class="call mt">
      <div class="row between small">
        <span class="row"><span class="ms">phone_in_talk</span><b>{{ c.roleLabel || t.field.roles[c.role] || c.role }}</b> {{ c.recipientName || c.recipient || '' }}</span>
        <span class="row"><fg-origin [origin]="c.dataOrigin || c.mode || 'SIMULATED'" /><span class="tiny muted">{{ (c.createdAt || c.startedAt) | dt: true }}</span>
          @if (canSpeak) { <button class="btn ghost sm icon" (click)="play(c)" [title]="t.assistant.speak"><span class="ms">volume_up</span></button> }</span>
      </div>
      @if (c.status) { <div class="tiny muted">{{ c.status }}</div> }
      <div class="lines">
        @for (l of lines(c); track $index) { <div class="ln" [class.sys]="isSys(l.who)"><b>{{ l.who }}</b>@if (l.who) {:} {{ l.text }}</div> }
        @empty { <pre class="code">{{ json(c) }}</pre> }
      </div>
    </div>
  } @empty { @if (!error()) { <div class="muted small mt">{{ t.field.callsEmpty }}</div> } }`,
  styles: [`
    .call { border: 1px solid var(--line); border-radius: 10px; padding: 10px 12px; background: rgba(5,11,24,.4); }
    .lines { display: flex; flex-direction: column; gap: 4px; margin-top: 6px; font-size: 12.5px; }
    .ln b { color: var(--cyan); } .ln.sys b { color: var(--amber); }
  `],
})
export class FieldCall {
  readonly t = T;
  readonly canSpeak = canSpeak;
  readonly roles = Object.keys(T.field.roles);
  readonly incidentId = input<string | null>(null);
  private readonly api = inject(Api);
  readonly role = signal('FIELD_TEAM');
  readonly calls = signal<any[]>([]);
  readonly current = signal<any>(null);
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);
  readonly unavailable = signal(false);

  constructor() {
    effect(() => { const id = this.incidentId(); untracked(() => this.load(id)); });
  }

  load(id: string | null): void {
    this.api.voiceCalls(id ?? undefined).subscribe({
      next: (r) => { this.calls.set(asList(r)); this.error.set(null); },
      error: (e) => { this.unavailable.set(isUnavailable(e)); this.error.set(errorText(e)); },
    });
  }

  call(): void {
    const id = this.incidentId();
    if (!id) return;
    this.busy.set(true);
    this.api.voiceCall(id, this.role()).subscribe({
      next: (c) => { this.busy.set(false); this.error.set(null); this.calls.update((l) => [c, ...l.filter((x) => x.id !== c?.id)]); },
      error: (e) => { this.busy.set(false); this.unavailable.set(isUnavailable(e)); this.error.set(errorText(e)); },
    });
  }

  lines(c: any) { return callLines(c); }
  isSys(who: string): boolean { return /filegrid|agent|sistem|ai/i.test(who); }
  play(c: any): void { speak(this.lines(c).map((l) => l.text).join('. ')); }
  json(v: any): string { return JSON.stringify(v, null, 2); }
}
