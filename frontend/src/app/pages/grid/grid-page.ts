import { Component, DestroyRef, computed, effect, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { NgxEchartsDirective } from 'ngx-echarts';
import { Api, Loader, asList, errorText } from '../../core/api.service';
import { Finding } from '../../core/models';
import { T, fmt } from '../../core/texts';
import { UiState } from '../../core/ui-state.service';
import { GridMap, normalizeGrid } from '../../features/grid-map';
import { ReportIncident } from '../../features/report-incident';
import { SHARED } from '../../shared/ui';
import { evidenceFacts, evidenceOption } from './evidence';
import { statsView } from './grid-stats';
import { PpcastorMood } from '../../shared/ppcastor';

type A2 = 'idle' | 'analyzing' | 'found' | 'none' | 'insufficient';

@Component({
  selector: 'fg-grid',
  imports: [RouterLink, MatDialogModule, NgxEchartsDirective, GridMap, ...SHARED],
  templateUrl: './grid-page.html',
  styleUrl: './grid-page.scss',
})
export class GridPage {
  readonly t = T;
  private readonly api = inject(Api);
  private readonly ui = inject(UiState);
  private readonly dialog = inject(MatDialog);

  readonly grid = new Loader(() => this.api.grid());
  readonly summary = new Loader(() => this.api.intelSummary());
  readonly evaluation = new Loader(() => this.api.evaluation());
  readonly f = signal<Record<string, string>>({});
  readonly findings = new Loader(() => this.api.findings({ ...this.f(), limit: 200 }));
  readonly model = computed(() => normalizeGrid(this.grid.data()));
  readonly selNode = signal<string | null>(null);
  readonly sel = signal<Finding | null>(null);
  readonly analyzing = signal(false);
  readonly note = signal('');
  readonly actionMsg = signal<string | null>(null);

  readonly list = computed(() => {
    const all = asList<Finding>(this.findings.data());
    const n = this.selNode();
    const m = this.model();
    if (!n || !m) return all;
    const node = m.nodes.find((x) => x.id === n);
    if (!node) return all;
    return all.filter((x) => node.kind === 'region' ? (x.region === node.id || x.region === node.name) : node.kind === 'station' ? (x.station === node.id || x.station === node.name) : (x.post === node.id));
  });
  readonly selNodeObj = computed(() => this.model()?.nodes.find((x) => x.id === this.selNode()) ?? null);
  readonly types = computed(() => Array.from(new Set(asList<Finding>(this.findings.data()).map((x) => x.type))));
  readonly agent = computed<A2>(() => {
    if (this.analyzing()) return 'analyzing';
    if (!this.findings.data()) return 'idle';
    const l = this.list();
    if (l.length && l.every((x) => x.type === 'INSUFFICIENT_DATA')) return 'insufficient';
    return l.filter((x) => x.type !== 'INSUFFICIENT_DATA').length ? 'found' : 'none';
  });
  readonly agentMsg = computed(() => {
    switch (this.agent()) {
      case 'analyzing': return T.grid.agentAnalyzing;
      case 'found': return fmt(T.grid.agentFound, { n: this.list().filter((x) => x.type !== 'INSUFFICIENT_DATA').length });
      case 'none': return T.grid.agentNone;
      case 'insufficient': return T.grid.agentInsufficient;
      default: return T.grid.agentIdle;
    }
  });
  readonly chart = computed(() => evidenceOption(this.sel()?.evidence));
  readonly facts = computed(() => evidenceFacts(this.sel()?.evidence));
  readonly stats = computed(() => statsView(this.summary.data()));
  /** PPCastor's face follows the real analysis state: scanning, worried when it found problems, pleased when all is clear. */
  readonly agentMood = computed<PpcastorMood>(() => ({ analyzing: 'thinking', found: 'error', none: 'happy' } as Record<string, PpcastorMood>)[this.agent()] ?? 'idle');

  constructor() {
    [this.grid, this.summary, this.findings, this.evaluation].forEach((l) => l.load());
    const i = setInterval(() => { this.grid.load(true); this.summary.load(true); this.findings.load(true); }, 20000);
    inject(DestroyRef).onDestroy(() => clearInterval(i));
    effect(() => this.ui.simulatedOnPage.set(true));
  }

  setF(k: string, v: string): void { this.f.update((o) => { const n = { ...o, [k]: v }; if (!v) delete n[k]; return n; }); this.findings.load(); }
  pickNode(id: string | null): void { this.selNode.set(id); }
  pick(f: Finding): void { this.sel.set(f); this.note.set(''); this.actionMsg.set(null); if (f.station) this.selNode.set(this.selNode()); }

  analyze(): void {
    this.analyzing.set(true);
    this.api.analyze().subscribe({
      next: () => { setTimeout(() => { this.analyzing.set(false); this.findings.load(); this.grid.load(true); this.summary.load(true); }, 1500); },
      error: (e) => { this.analyzing.set(false); this.actionMsg.set(errorText(e)); },
    });
  }

  setStatus(status: string): void {
    const f = this.sel();
    if (!f) return;
    this.api.findingStatus(f.id, status, this.note()).subscribe({
      next: (r) => { this.actionMsg.set(T.grid.findingStatuses[status] ?? status); this.sel.set({ ...f, ...(r && typeof r === 'object' ? r : {}), status }); this.findings.load(true); },
      error: (e) => this.actionMsg.set(errorText(e)),
    });
  }

  promote(): void {
    const f = this.sel();
    if (!f) return;
    this.dialog.open(ReportIncident, { maxWidth: '96vw', data: { preset: {
      type: 'GRID_ANOMALY', severity: 'MEDIUM', certainty: 'SUSPECTED', zone: f.region ?? f.zone ?? '', station: f.station ?? '', asset: f.post ?? f.meterId ?? '',
      title: this.title(f), description: `${this.obs(f)}\n\nConstatare PPCastor (analiza rețelei): ${f.id}. ${T.grid.disclaimer}`, dataOrigin: f.dataOrigin ?? 'SIMULATED',
    } } });
  }

  ask(): void { const f = this.sel(); if (f) this.ui.openAssistant({ findingId: f.id }, `Explică constatarea ${this.title(f)}: ce date o susțin și ce verificare umană este necesară?`); }

  title(f: Finding): string { return f.title || (f as any).typeLabel || T.grid.findingTypes[f.type] || f.type; }
  typeLabel(type: string): string { const f = asList<any>(this.findings.data()).find((x) => x.type === type); return f?.typeLabel || T.grid.findingTypes[type] || type; }
  from(f: Finding): string | null { return f.windowStart ?? (f as any).window?.from ?? null; }
  to(f: Finding): string | null { return f.windowEnd ?? (f as any).window?.to ?? null; }
  loc(f: Finding): (string | null | undefined)[] { return [(f as any).regionName ?? f.region, f.zone, f.station, f.post, f.meterId]; }
  obs(f: Finding): string {
    const o = f.observations ?? f.observation;
    return Array.isArray(o) ? o.join(' ') : (o ?? '');
  }
  obsList(f: Finding): string[] {
    const o = f.observations ?? f.observation;
    return Array.isArray(o) ? o : o ? [o] : [];
  }
  conf(f: Finding): { level: string; score: number | null; how: string | null } {
    const c = f.confidence;
    if (c && typeof c === 'object') return { level: String(c.level ?? c.label ?? ''), score: typeof c.score === 'number' ? c.score : null, how: c.method ?? c.how ?? c.explanation ?? null };
    if (typeof c === 'number') return { level: c >= 0.75 ? 'HIGH' : c >= 0.45 ? 'MEDIUM' : 'LOW', score: c, how: null };
    return { level: String(c ?? ''), score: null, how: null };
  }
  human(f: Finding): string | null {
    if (typeof f.humanVerificationRequired === 'string') return f.humanVerificationRequired;
    if (f.humanCheck) return f.humanCheck;
    return f.humanVerificationRequired ? T.grid.humanCheck : null;
  }
  fileIds(f: Finding): string[] {
    const e = f.evidence ?? {};
    const ids = e.fileIds ?? e.sourceFileIds ?? e.files ?? [];
    return Array.isArray(ids) ? ids.map((x: any) => (typeof x === 'string' ? x : x?.fileId ?? x?.id)).filter(Boolean).slice(0, 10) : [];
  }
  evalRows(): [string, any][] {
    const e = this.evaluation.data();
    if (!e || e.available === false) return [];
    if (e.perType) {
      const out: [string, any][] = [['Total', { precision: e.precision, recall: e.recall }]];
      for (const [k, v] of Object.entries(e.perType)) out.push([k, v]);
      return out;
    }
    const rows = asList(e).length ? asList(e) : Object.entries(e.detectors ?? e).map(([k, v]: [string, any]) => ({ detector: k, ...(typeof v === 'object' ? v : { value: v }) }));
    return rows.map((r: any) => [r.detector ?? r.type ?? r.name ?? '—', r]);
  }
  pct(v: any): string { if (v === null || v === undefined) return '—'; const n = Number(v); return isNaN(n) ? '—' : n <= 1 ? `${(n * 100).toFixed(0)}%` : String(n); }
}
