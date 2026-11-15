import { Component, computed, input, output } from '@angular/core';
import { T } from '../core/texts';

export type Health = 'ok' | 'warn' | 'bad' | 'unknown';
export interface GNode {
  id: string; name: string; kind: 'region' | 'station' | 'post'; x: number; y: number; parent: string | null;
  region: string | null; health: Health; anomalies: number; faults: number; readings: number | null; raw: any;
}
export interface GridModel { width: number; height: number; nodes: GNode[]; links: { a: GNode; b: GNode }[]; dataOrigin: string | null; }

function healthOf(n: any): Health {
  const v = String(n?.health ?? n?.status ?? n?.state ?? n?.level ?? '').toUpperCase();
  if (['OK', 'NORMAL', 'HEALTHY', 'GREEN', 'UP'].includes(v)) return 'ok';
  if (['WARN', 'WARNING', 'ATTENTION', 'YELLOW', 'DEGRADED', 'MEDIUM'].includes(v)) return 'warn';
  if (['BAD', 'ANOMALY', 'CRITICAL', 'ALERT', 'RED', 'DOWN', 'FAULT', 'HIGH'].includes(v)) return 'bad';
  const a = Number(n?.anomalies ?? n?.anomalyCount ?? n?.openFindings ?? n?.findings ?? NaN);
  const f = Number(n?.faults ?? n?.faultCount ?? n?.incidents ?? NaN);
  if (!isNaN(a) || !isNaN(f)) {
    const s = (isNaN(a) ? 0 : a) * 2 + (isNaN(f) ? 0 : f);
    return s >= 4 ? 'bad' : s > 0 ? 'warn' : 'ok';
  }
  return 'unknown';
}

function num(v: any): number { const n = Number(v); return isNaN(n) ? 0 : n; }

/** Tolerant normaliser: accepts {regions, stations, transformers|posts} or {nodes, links}. */
export function normalizeGrid(raw: any): GridModel | null {
  if (!raw || typeof raw !== 'object') return null;
  const width = num(raw.canvas?.width) || 1000;
  const height = num(raw.canvas?.height) || 600;
  const nodes: GNode[] = [];
  const mk = (n: any, kind: GNode['kind'], parent: string | null, region: string | null): GNode => ({
    id: String(n.id ?? n.code ?? n.name), name: String(n.name ?? n.label ?? n.id), kind,
    x: num(n.x), y: num(n.y), parent, region, health: healthOf(n),
    anomalies: num(n.anomalies ?? n.anomalyCount ?? n.openFindings ?? n.findings),
    faults: num(n.faults ?? n.faultCount ?? n.incidents), readings: n.readings ?? n.readingCount ?? null, raw: n,
  });
  if (Array.isArray(raw.nodes)) {
    for (const n of raw.nodes) {
      const t = String(n.type ?? n.kind ?? '').toLowerCase();
      const kind: GNode['kind'] = t.startsWith('reg') ? 'region' : t.startsWith('st') ? 'station' : 'post';
      nodes.push(mk(n, kind, n.parent ?? n.station ?? n.region ?? null, n.region ?? null));
    }
  } else {
    for (const r of raw.regions ?? []) {
      nodes.push(mk(r, 'region', null, String(r.id)));
      for (const s of r.stations ?? []) {
        nodes.push(mk(s, 'station', String(r.id), String(r.id)));
        for (const p of s.posts ?? s.transformers ?? []) nodes.push(mk(p, 'post', String(s.id), String(r.id)));
      }
    }
    for (const s of raw.stations ?? []) nodes.push(mk(s, 'station', s.region ?? null, s.region ?? null));
    for (const p of raw.transformers ?? raw.posts ?? []) nodes.push(mk(p, 'post', p.station ?? null, p.region ?? null));
  }
  if (!nodes.length) return null;
  // Auto-layout nodes without coordinates
  const noPos = nodes.filter((n) => !n.x && !n.y);
  noPos.forEach((n, i) => { n.x = 80 + ((i * 137) % (width - 160)); n.y = 80 + ((i * 89) % (height - 160)); });
  const byId = new Map(nodes.map((n) => [n.id, n]));
  // Propagate worst health upwards for regions/stations without own status
  const rank: Record<Health, number> = { unknown: 0, ok: 1, warn: 2, bad: 3 };
  for (const n of nodes.filter((x) => x.kind === 'post')) {
    const st = n.parent ? byId.get(n.parent) : undefined;
    if (st && rank[n.health] > rank[st.health] && n.health !== 'ok') st.health = n.health;
    if (st && st.health === 'unknown' && n.health === 'ok') st.health = 'ok';
  }
  for (const n of nodes.filter((x) => x.kind === 'station')) {
    const rg = n.parent ? byId.get(n.parent) : undefined;
    if (rg && rank[n.health] > rank[rg.health]) rg.health = n.health === 'ok' && rg.health !== 'unknown' ? rg.health : n.health;
  }
  const links: { a: GNode; b: GNode }[] = [];
  if (Array.isArray(raw.links)) {
    for (const l of raw.links) {
      const a = byId.get(String(l.from ?? l.source ?? l.a)); const b = byId.get(String(l.to ?? l.target ?? l.b));
      if (a && b) links.push({ a, b });
    }
  }
  for (const n of nodes) {
    if (n.parent && n.kind !== 'region') { const p = byId.get(n.parent); if (p) links.push({ a: p, b: n }); }
  }
  // Ring between stations of a region (schematic lines)
  if (!Array.isArray(raw.links)) {
    const groups = new Map<string, GNode[]>();
    for (const s of nodes.filter((n) => n.kind === 'station')) {
      const g = groups.get(s.region ?? '') ?? []; g.push(s); groups.set(s.region ?? '', g);
    }
    for (const g of groups.values()) for (let i = 1; i < g.length; i++) links.push({ a: g[i - 1], b: g[i] });
  }
  return { width, height, nodes, links, dataOrigin: raw.dataOrigin ?? null };
}

@Component({
  selector: 'fg-grid-map',
  template: `
  @if (model(); as m) {
    <svg [attr.viewBox]="'0 0 ' + m.width + ' ' + m.height" class="map" [class.mini]="mini()">
      <defs>
        <pattern id="gm-grid" width="40" height="40" patternUnits="userSpaceOnUse"><path d="M40 0H0V40" fill="none" stroke="#132b74" stroke-width="1"/></pattern>
        <filter id="gm-glow"><feGaussianBlur stdDeviation="4" result="b"/><feMerge><feMergeNode in="b"/><feMergeNode in="SourceGraphic"/></feMerge></filter>
      </defs>
      <rect [attr.width]="m.width" [attr.height]="m.height" fill="url(#gm-grid)" opacity=".6"/>
      @for (r of regions(); track r.id) {
        <g class="region" [class.sel]="selected() === r.id" (click)="pick.emit(selected() === r.id ? null : r.id)">
          <circle [attr.cx]="r.x" [attr.cy]="r.y" [attr.r]="mini() ? 150 : 165" class="halo h-{{ r.health }}"/>
          <text [attr.x]="r.x" [attr.y]="r.y - (mini() ? 125 : 140)" text-anchor="middle" class="rlabel">{{ r.name }}</text>
        </g>
      }
      @for (l of m.links; track $index) {
        <line [attr.x1]="l.a.x" [attr.y1]="l.a.y" [attr.x2]="l.b.x" [attr.y2]="l.b.y" class="ln" [class.hv]="l.a.kind === 'station' && l.b.kind === 'station'" [class.dim]="dimmed(l.b)"/>
      }
      @for (n of posts(); track n.id) {
        <g (click)="pick.emit(n.id)" class="node" [class.dim]="dimmed(n)">
          <rect [attr.x]="n.x - 4" [attr.y]="n.y - 4" width="8" height="8" rx="1.5" class="pt h-{{ n.health }}" [class.sel]="selected() === n.id"><title>{{ n.name }}</title></rect>
        </g>
      }
      @for (n of stations(); track n.id) {
        <g (click)="pick.emit(n.id)" class="node" [class.dim]="dimmed(n)">
          @if (n.health === 'bad') { <circle [attr.cx]="n.x" [attr.cy]="n.y" r="20" class="ping"/> }
          <circle [attr.cx]="n.x" [attr.cy]="n.y" r="11" class="st h-{{ n.health }}" [class.sel]="selected() === n.id" filter="url(#gm-glow)"><title>{{ n.name }}</title></circle>
          <path [attr.d]="bolt(n)" fill="#06123a"/>
          @if (!mini()) { <text [attr.x]="n.x" [attr.y]="n.y + 26" text-anchor="middle" class="slabel">{{ n.name }}</text> }
          @if (n.anomalies > 0) {
            <g><circle [attr.cx]="n.x + 11" [attr.cy]="n.y - 11" r="8" fill="#f43f5e"/><text [attr.x]="n.x + 11" [attr.y]="n.y - 7.5" text-anchor="middle" class="cnt">{{ n.anomalies }}</text></g>
          }
        </g>
      }
    </svg>
    <div class="legend tiny">
      <span><i class="dot ok"></i>{{ t.grid.legendOk }}</span><span><i class="dot warn"></i>{{ t.grid.legendWarn }}</span>
      <span><i class="dot bad"></i>{{ t.grid.legendBad }}</span><span><i class="dot"></i>{{ t.grid.legendUnknown }}</span>
    </div>
  }`,
  styles: [`
    :host { display: block; position: relative; }
    .map { width: 100%; height: auto; display: block; }
    .halo { fill: rgba(255,119,188,.03); stroke: rgba(255,119,188,.18); stroke-dasharray: 4 6; cursor: pointer; }
    .region.sel .halo { fill: rgba(255,119,188,.08); stroke: var(--cyan); }
    .halo.h-bad { stroke: rgba(244,63,94,.45); fill: rgba(244,63,94,.05); } .halo.h-warn { stroke: rgba(251,191,36,.45); fill: rgba(251,191,36,.04); }
    .rlabel { fill: #b4c3ee; font-size: 15px; letter-spacing: .2em; text-transform: uppercase; font-weight: 600; }
    .slabel { fill: #e6ecff; font-size: 11px; pointer-events: none; }
    .ln { stroke: #3355b0; stroke-width: 1; } .ln.hv { stroke: #3b6aa8; stroke-width: 2.4; } .ln.dim, .node.dim { opacity: .25; }
    .node { cursor: pointer; }
    .st { stroke: #06123a; stroke-width: 2; } .st.sel, .pt.sel { stroke: #fff; stroke-width: 3; }
    .h-ok { fill: #34d399; } .h-warn { fill: #fbbf24; } .h-bad { fill: #f43f5e; } .h-unknown { fill: #7f93cf; }
    .halo.h-ok, .halo.h-unknown { fill: rgba(255,119,188,.03); }
    .ping { fill: none; stroke: #f43f5e; stroke-width: 2; animation: ping 1.6s ease-out infinite; transform-box: fill-box; transform-origin: center; }
    @keyframes ping { from { opacity: .9; transform: scale(.6); } to { opacity: 0; transform: scale(1.6); } }
    .cnt { fill: #fff; font-size: 10px; font-weight: 700; }
    .legend { position: absolute; left: 10px; bottom: 8px; display: flex; gap: 12px; color: var(--muted); }
    .legend span { display: inline-flex; gap: 5px; align-items: center; }
  `],
})
export class GridMap {
  readonly t = T;
  readonly model = input<GridModel | null>(null);
  readonly selected = input<string | null>(null);
  readonly mini = input(false);
  readonly pick = output<string | null>();
  readonly regions = computed(() => this.model()?.nodes.filter((n) => n.kind === 'region') ?? []);
  readonly stations = computed(() => this.model()?.nodes.filter((n) => n.kind === 'station') ?? []);
  readonly posts = computed(() => this.model()?.nodes.filter((n) => n.kind === 'post') ?? []);

  dimmed(n: GNode): boolean {
    const s = this.selected();
    if (!s) return false;
    const sel = this.model()?.nodes.find((x) => x.id === s);
    if (!sel) return false;
    if (sel.kind === 'region') return n.region !== sel.id && n.id !== sel.id;
    return n.id !== sel.id && n.parent !== sel.id && sel.parent !== n.id;
  }

  bolt(n: GNode): string {
    const x = n.x, y = n.y;
    return `M${x + 1} ${y - 7} L${x - 4} ${y + 1} H${x} L${x - 1} ${y + 7} L${x + 4} ${y - 1} H${x} Z`;
  }
}
