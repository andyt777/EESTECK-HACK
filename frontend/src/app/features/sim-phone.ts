import { Component, DestroyRef, inject, output, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Api, asList, errorText, isUnavailable } from '../core/api.service';
import { EventStream } from '../core/events.service';
import { IncidentNotification } from '../core/models';
import { T } from '../core/texts';
import { TimePipe, WaText } from '../shared/ui';

/** Simulated phone: shows WhatsApp messages sent in SIMULATED mode, each with an "OK" confirmation. */
@Component({
  selector: 'fg-sim-phone',
  imports: [RouterLink, TimePipe, WaText],
  template: `
  <div class="phone">
    <div class="notch"></div>
    <div class="ph-head">
      <span class="ms">chat</span>
      <div class="grow"><b>WhatsApp</b><div class="tiny">{{ t.incidents.phone }} · <span class="badge sim">{{ t.app.simulated }}</span></div></div>
      <button class="x" (click)="close.emit()" [title]="t.app.close"><span class="ms">close</span></button>
    </div>
    <div class="ph-body">
      @if (unavailable()) { <div class="empty">{{ t.app.unavailable }}</div> }
      @else if (error()) { <div class="empty">{{ error() }}</div> }
      @for (n of items(); track n.id) {
        <div class="m">
          <div class="to tiny">→ {{ n.recipientName }} · {{ n.roleLabel || n.role }} @if (n.phoneMasked) { · {{ n.phoneMasked }} }</div>
          <div class="wa-bubble"><fg-wa [text]="n.body" />
            <div class="ts tiny">{{ n.createdAt | tm }} · {{ n.statusLabel || n.status }}</div>
          </div>
          <div class="acts">
            <a class="tiny" [routerLink]="['/incidents', n.incidentId]">{{ n.incidentCode }}</a>
            @if (n.confirmedAt) {
              <span class="ok tiny"><span class="ms">done_all</span> {{ t.incidents.confirmedBy }} {{ n.confirmedBy }}</span>
            } @else if (n.kind !== 'UPDATE') {
              <button class="okbtn" (click)="confirm(n)" [disabled]="busy() === n.id">{{ t.incidents.confirmOk }}</button>
            }
          </div>
        </div>
      } @empty {
        @if (!unavailable() && !error()) { <div class="empty">{{ t.incidents.phoneEmpty }}</div> }
      }
    </div>
  </div>`,
  styles: [`
    .phone { width: 340px; height: 600px; max-height: 80vh; background: #0b141a; border: 10px solid #111827; border-radius: 34px; box-shadow: 0 30px 80px rgba(0,0,0,.7), 0 0 0 1px #374151; display: flex; flex-direction: column; overflow: hidden; position: relative; }
    .notch { position: absolute; top: 0; left: 50%; transform: translateX(-50%); width: 110px; height: 18px; background: #111827; border-radius: 0 0 12px 12px; z-index: 2; }
    .ph-head { display: flex; gap: 8px; align-items: center; padding: 24px 12px 10px; background: #1f2c34; color: #e9edef; }
    .ph-head .badge { padding: 0 6px; font-size: 10px; }
    .x { background: none; border: 0; color: #aebac1; cursor: pointer; }
    .ph-body { flex: 1; overflow-y: auto; padding: 10px; display: flex; flex-direction: column; gap: 12px;
      background: #0b141a radial-gradient(circle at 20% 20%, rgba(255,255,255,.02) 0 2px, transparent 3px) 0 0/22px 22px; }
    .to { color: #8696a0; margin-bottom: 3px; }
    .ts { color: #8fbfa6; text-align: right; margin-top: 6px; }
    .acts { display: flex; justify-content: space-between; align-items: center; margin-top: 4px; }
    .okbtn { background: #00a884; color: #fff; border: 0; border-radius: 16px; padding: 4px 14px; font-weight: 700; cursor: pointer; font: inherit; font-size: 12px; }
    .okbtn:disabled { opacity: .5; }
    .ok { color: #53bdeb; display: inline-flex; gap: 3px; align-items: center; } .ok .ms { font-size: 14px; }
    .empty { color: #8696a0; text-align: center; padding: 30px 10px; font-size: 13px; }
  `],
})
export class SimPhone {
  readonly t = T;
  readonly close = output<void>();
  private readonly api = inject(Api);
  private readonly events = inject(EventStream);
  readonly items = signal<IncidentNotification[]>([]);
  readonly error = signal<string | null>(null);
  readonly unavailable = signal(false);
  readonly busy = signal<string | null>(null);

  constructor() {
    this.load();
    const sub = this.events.incidentEvents$.subscribe((e) => { if (e.notification) this.load(); });
    const timer = setInterval(() => this.load(), 10000);
    inject(DestroyRef).onDestroy(() => { sub.unsubscribe(); clearInterval(timer); });
  }

  load(): void {
    this.api.notifications({ mode: 'SIMULATED', size: 50 }).subscribe({
      next: (p) => { this.items.set(asList<IncidentNotification>(p)); this.error.set(null); this.unavailable.set(false); },
      error: (e) => { this.unavailable.set(isUnavailable(e)); this.error.set(errorText(e)); },
    });
  }

  confirm(n: IncidentNotification): void {
    this.busy.set(n.id);
    this.api.confirmNotification(n.id, n.recipientName).subscribe({
      next: () => { this.busy.set(null); this.load(); },
      error: (e) => { this.busy.set(null); this.error.set(errorText(e)); },
    });
  }
}
