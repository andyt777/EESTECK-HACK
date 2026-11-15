import { Component, DestroyRef, computed, effect, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Api, Loader } from '../../core/api.service';
import { EventStream } from '../../core/events.service';
import { DecisionView, TERMINAL } from '../../core/models';
import { T } from '../../core/texts';
import { UiState } from '../../core/ui-state.service';
import { UploadService } from '../../core/upload.service';
import { DecisionPanel } from '../../features/decision-panel';
import { SHARED } from '../../shared/ui';
import { Village } from './village';

/** Statuses after which Agent 01's routing decision has been persisted (it is saved before the transfer). */
const DECIDED = ['DESTINATION_SELECTED', 'TRANSFERRING', 'TRANSFER_RESUMED', 'FALLBACK', 'STORED_VERIFIED', 'PENDING', 'TRANSFER_FAILED'];

/**
 * Control Center: the Village map only. After the user drops a file on Agent 01,
 * the routing decision for that file appears as a message over the map.
 */
@Component({
  selector: 'fg-control-center',
  imports: [RouterLink, Village, DecisionPanel, ...SHARED],
  templateUrl: './control-center.html',
  styleUrl: './control-center.scss',
})
export class ControlCenter {
  readonly t = T;
  private readonly api = inject(Api);
  private readonly events = inject(EventStream);
  private readonly ui = inject(UiState);
  private readonly uploads = inject(UploadService);

  readonly summary = new Loader(() => this.api.categories());
  readonly meter = new Loader(() => this.api.meterStats());
  readonly health = new Loader(() => this.api.storageHealth());

  /** File whose decision message is shown (only files the user uploaded in this session). */
  readonly messageFor = signal<string | null>(null);
  readonly decision = signal<DecisionView | null>(null);
  readonly details = signal(false);
  readonly fileName = computed(() => { const id = this.messageFor(); return id ? (this.uploads.byFileId(id)?.name ?? null) : null; });
  /** Real backend state of the file, from the live event stream. */
  readonly fileStatus = computed(() => { const id = this.messageFor(); return id ? (this.events.latest()[id]?.status ?? this.uploads.byFileId(id)?.status ?? null) : null; });
  readonly verified = computed(() => this.fileStatus() === 'STORED_VERIFIED');

  constructor() {
    const all = [this.summary, this.meter, this.health];
    all.forEach((l) => l.load());
    const timer = setInterval(() => all.forEach((l) => l.load(true)), 5000);
    inject(DestroyRef).onDestroy(() => { clearInterval(timer); all.forEach((l) => l.destroy()); });

    effect(() => this.ui.simulatedOnPage.set(this.meter.data()?.dataOrigin === 'SIMULATED'));

    // Fetch the persisted decision once Agent 01 has made it; refresh on terminal states (fallback may change it).
    let fetched = '';
    effect(() => {
      const id = this.messageFor();
      const st = this.fileStatus();
      if (!id || !st || !DECIDED.includes(st)) return;
      // One fetch when the decision exists, one more on the outcome (a fallback rewrites it).
      const key = `${id}:${TERMINAL.includes(st) || st === 'FALLBACK' ? st : 'decided'}`;
      if (key === fetched) return;
      fetched = key;
      this.api.decision(id).subscribe({ next: (d) => { if (this.messageFor() === id) this.decision.set(d); }, error: () => {} });
    });
  }

  onFocus(id: string | null): void {
    if (!id || !this.uploads.byFileId(id) || id === this.messageFor()) return;
    this.messageFor.set(id);
    this.decision.set(null);
    this.details.set(false);
  }

  dismiss(): void { this.messageFor.set(null); this.decision.set(null); }
}
