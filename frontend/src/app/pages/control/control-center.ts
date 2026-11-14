import { Component, DestroyRef, computed, effect, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { Api, Loader, asList } from '../../core/api.service';
import { EventStream } from '../../core/events.service';
import { CATEGORIES, DecisionView } from '../../core/models';
import { T } from '../../core/texts';
import { UiState } from '../../core/ui-state.service';
import { DecisionPanel } from '../../features/decision-panel';
import { GridMap, normalizeGrid } from '../../features/grid-map';
import { UploadDialog } from '../../features/upload-form';
import { UploadTracker } from '../../features/upload-tracker';
import { SHARED } from '../../shared/ui';
import { Village } from './village';

function pick(o: any, keys: string[]): number | null {
  if (!o || typeof o !== 'object') return null;
  for (const k of keys) {
    const v = k.split('.').reduce((a: any, p) => (a == null ? a : a[p]), o);
    if (typeof v === 'number') return v;
    if (Array.isArray(v)) return v.length;
  }
  return null;
}

@Component({
  selector: 'fg-control-center',
  imports: [RouterLink, MatDialogModule, Village, DecisionPanel, GridMap, UploadTracker, ...SHARED],
  templateUrl: './control-center.html',
  styleUrl: './control-center.scss',
})
export class ControlCenter {
  readonly t = T;
  readonly cats = CATEGORIES;
  readonly api = inject(Api);
  readonly events = inject(EventStream);
  private readonly ui = inject(UiState);
  private readonly dialog = inject(MatDialog);

  readonly summary = new Loader(() => this.api.categories());
  readonly meter = new Loader(() => this.api.meterStats());
  readonly health = new Loader(() => this.api.storageHealth());
  readonly incidents = new Loader(() => this.api.incidents({ open: true, size: 5 }));
  readonly intel = new Loader(() => this.api.intelSummary());
  readonly grid = new Loader(() => this.api.grid());

  readonly gridModel = computed(() => normalizeGrid(this.grid.data()));
  readonly focusId = signal<string | null>(null);
  readonly decisionOpen = signal(true);
  readonly decision = signal<DecisionView | null>(null);

  readonly stored = computed(() => (this.summary.data() ?? []).reduce((a, s) => a + s.fileCount - s.pending - s.transferFailed - s.inProgress, 0));
  readonly pending = computed(() => (this.summary.data() ?? []).reduce((a, s) => a + s.pending, 0));
  readonly anomalies = computed(() => pick(this.intel.data(), ['openFindings', 'byStatus.NEW', 'anomalies', 'findings.open', 'total', 'totalFindings', 'findings']));
  readonly openIncidents = computed(() => this.incidents.data()?.total ?? null);
  readonly criticalIncidents = computed(() => (this.incidents.data()?.items ?? []).filter((i) => i.severity === 'CRITICAL').length);

  constructor() {
    const all = [this.summary, this.meter, this.health, this.incidents, this.intel, this.grid];
    all.forEach((l) => l.load());
    const fast = setInterval(() => { this.summary.load(true); this.meter.load(true); this.health.load(true); }, 5000);
    const slow = setInterval(() => { this.incidents.load(true); this.intel.load(true); this.grid.load(true); }, 15000);
    const sub = this.events.incidentEvents$.subscribe(() => this.incidents.load(true));
    inject(DestroyRef).onDestroy(() => { clearInterval(fast); clearInterval(slow); sub.unsubscribe(); all.forEach((l) => l.destroy()); });

    effect(() => {
      const sim = this.meter.data()?.dataOrigin === 'SIMULATED' || this.gridModel()?.dataOrigin === 'SIMULATED';
      this.ui.simulatedOnPage.set(sim);
    });
    // Decision Explorer drop-down: the real decision of the file Agent 01 is carrying.
    effect(() => {
      const id = this.focusId();
      const ev = id ? this.events.latest()[id] : null;
      if (!id || !ev) return;
      if (['DESTINATION_SELECTED', 'FALLBACK', 'STORED_VERIFIED', 'PENDING', 'TRANSFER_FAILED'].includes(ev.status) || (ev.status === 'TRANSFERRING' && !this.decision())) {
        this.api.decision(id).subscribe({ next: (d) => this.decision.set(d), error: () => {} });
      }
    });
  }

  onFocus(id: string | null): void {
    if (id !== this.focusId()) this.decision.set(null);
    this.focusId.set(id);
  }

  openUpload(files: File[] = []): void {
    this.dialog.open(UploadDialog, { data: { files }, panelClass: 'fg-dialog', maxWidth: '96vw' });
  }

  feedTone(s: string): string {
    return ({ STORED_VERIFIED: 'ok', PENDING: 'warn', TRANSFER_FAILED: 'bad', FALLBACK: 'warn', MISMATCH: 'bad', TRANSFER_RESUMED: 'warn' } as Record<string, string>)[s] ?? 'info';
  }

  incidentsList() { return asList(this.incidents.data()); }
}
