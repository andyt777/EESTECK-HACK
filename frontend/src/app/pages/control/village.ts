import { Component, DestroyRef, ElementRef, afterNextRender, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { Router } from '@angular/router';
import { gsap } from 'gsap';
import { AuthzService } from '../../core/auth';
import { EventStream } from '../../core/events.service';
import { CATEGORIES, Category, CategorySummary, LiveEvent, MeterStats, STORAGES, StorageHealth, StorageType, TERMINAL } from '../../core/models';
import { T, fmt } from '../../core/texts';
import { UiState } from '../../core/ui-state.service';
import { UploadService } from '../../core/upload.service';
import { formatNum, formatSize } from '../../shared/ui';

type AgentState = 'idle' | 'scanning' | 'walking' | 'carrying' | 'success' | 'warning' | 'error' | 'drop';

/** Scene coordinates (viewBox 1200 x 680). Houses are drawn around (x, y) = middle of the facade base. */
export const HOUSE: Record<Category, { x: number; y: number; icon: string }> = {
  METER_READING: { x: 650, y: 150, icon: 'electric_meter' },
  FAULT_RECORD: { x: 650, y: 300, icon: 'bolt' },
  CONNECTION_DOCUMENT: { x: 650, y: 450, icon: 'folder_shared' },
  DRONE_VIDEO: { x: 650, y: 600, icon: 'videocam' },
};
export const SERVER: Record<StorageType, { x: number; y: number; icon: string }> = {
  DATABASE: { x: 1060, y: 190, icon: 'database' },
  S3: { x: 1060, y: 375, icon: 'cloud' },
  FTP: { x: 1060, y: 560, icon: 'folder_shared' },
};
const AGENT_HOME = { x: 330, y: 400 };
const ENTRY = { x: 96, y: 400 };
/** Where Agent 01 stands when it delivers a file to a house: in front of the door, on the path. */
const doorstep = (c: Category) => ({ x: HOUSE[c].x - 152, y: HOUSE[c].y + 6 });
/** Wire from a house (right wall, plug) to a storage server (left side). */
export function wirePath(c: Category, s: StorageType): string {
  const h = HOUSE[c], v = SERVER[s];
  const x1 = h.x + 62, y1 = h.y - 22, x2 = v.x - 48, y2 = v.y - 6;
  const mx = (x1 + x2) / 2;
  return `M${x1} ${y1} C${mx} ${y1} ${mx} ${y2} ${x2} ${y2}`;
}

interface Focus { fileId: string; category: Category | null; storage: StorageType | null; status: string; at: number; }

@Component({
  selector: 'fg-village',
  templateUrl: './village.html',
  styleUrl: './village.scss',
})
export class Village {
  readonly t = T;
  readonly cats = CATEGORIES;
  readonly storages = STORAGES;
  readonly H = HOUSE;
  readonly S = SERVER;
  readonly entry = ENTRY;
  readonly home = AGENT_HOME;

  readonly summary = input<CategorySummary[] | null>(null);
  readonly health = input<StorageHealth[] | null>(null);
  readonly meter = input<MeterStats | null>(null);
  readonly focusFile = output<string | null>();

  private readonly host = inject(ElementRef<HTMLElement>);
  private readonly events = inject(EventStream);
  private readonly uploads = inject(UploadService);
  private readonly authz = inject(AuthzService);
  private readonly ui = inject(UiState);
  private readonly router = inject(Router);

  readonly agentState = signal<AgentState>('idle');
  readonly agentMsg = signal<string>(T.agent01.idle);
  /** Speech bubble: above PPCastor on the plaza, to its left when it stands at a house (keeps house labels visible). */
  readonly bubbleSide = signal<'above' | 'left'>('above');
  readonly hotHouse = signal<Category | null>(null);
  readonly wire = signal<{ cat: Category; st: StorageType } | null>(null);
  readonly pulseServer = signal<StorageType | null>(null);
  readonly focus = signal<Focus | null>(null);
  readonly entryFile = signal(false);
  readonly dragging = signal(false);
  readonly overAgent = signal(false);
  readonly packetLabel = signal('');
  /** True only while PPCastor is actually walking (drives the waddle). */
  readonly moving = signal(false);

  private agentEl?: SVGGElement;
  private packetEl?: SVGGElement;
  private packetT = { t: 0 };
  private resetTimer?: ReturnType<typeof setTimeout>;
  private hintTimer?: ReturnType<typeof setTimeout>;

  readonly byCat = computed(() => {
    const m: Partial<Record<Category, CategorySummary>> = {};
    for (const s of this.summary() ?? []) m[s.category] = s;
    return m;
  });
  readonly healthBy = computed(() => {
    const m: Partial<Record<StorageType, StorageHealth>> = {};
    for (const h of this.health() ?? []) m[h.storage] = h;
    return m;
  });
  /** Where the meter readings actually go (largest count); default the policy primary. */
  readonly meterTarget = computed<StorageType>(() => {
    const by = this.byCat().METER_READING?.byStorage ?? {};
    let best: StorageType = 'DATABASE';
    let n = -1;
    for (const [k, v] of Object.entries(by)) if (v > n) { n = v; best = k as StorageType; }
    return best;
  });
  readonly meterFlowing = computed(() => (this.meter()?.readingsLastMinute ?? 0) > 0 && this.status(this.meterTarget()) !== 'DOWN');
  readonly meterWire = computed(() => wirePath('METER_READING', this.meterTarget()));
  readonly meterLabel = computed(() => fmt(T.control.meterRate, { n: formatNum(this.meter()?.readingsLastMinute ?? 0) }));
  readonly meterLabelAt = computed(() => {
    const h = HOUSE.METER_READING, v = SERVER[this.meterTarget()];
    return { x: (h.x + 62 + v.x - 48) / 2, y: Math.min(h.y - 22, v.y - 6) - 14 };
  });
  readonly wireD = computed(() => { const w = this.wire(); return w ? wirePath(w.cat, w.st) : null; });

  /** Most recent upload still streaming from the browser (tus), shown in Agent 01's bubble. */
  private readonly activeUpload = computed(() => this.uploads.items().find((i) => i.status === 'UPLOADING' || i.status === 'UPLOAD_ERROR') ?? null);

  constructor() {
    afterNextRender(() => {
      const root = this.host.nativeElement as HTMLElement;
      this.agentEl = root.querySelector('#agent01') as SVGGElement;
      this.packetEl = root.querySelector('#packet') as SVGGElement;
      gsap.set(this.agentEl, { x: AGENT_HOME.x, y: AGENT_HOME.y });
      gsap.set(this.packetEl, { opacity: 0 });
      this.idleBob();
    });
    effect(() => {
      const up = this.activeUpload();
      untracked(() => {
        const cur = this.focus();
        if (!up || (cur && !TERMINAL.includes(cur.status))) return;
        if (up.status === 'UPLOAD_ERROR') { this.say('warning', 'UPLOAD_ERROR'); return; }
        const pct = up.size > 0 ? `${Math.floor((up.uploadedBytes / up.size) * 100)}%` : '';
        this.say('carrying', 'UPLOADING', { pct });
      });
    });
    const sub = this.events.fileEvents$.subscribe((e) => this.onEvent(e));
    inject(DestroyRef).onDestroy(() => {
      sub.unsubscribe();
      gsap.killTweensOf([this.agentEl, this.packetEl, this.packetT].filter(Boolean));
      clearTimeout(this.resetTimer); clearTimeout(this.hintTimer);
    });
  }

  // ----------------------------------------------------------------- helpers
  houseCount(c: Category): string {
    const s = this.byCat()[c];
    return s ? `${formatNum(s.fileCount)} ${T.control.files} · ${formatSize(s.totalBytes)}` : '—';
  }
  warnings(c: Category): number { const s = this.byCat()[c]; return s ? s.pending + s.mismatch + s.transferFailed : 0; }
  inProgress(c: Category): number { return this.byCat()[c]?.inProgress ?? 0; }
  status(s: StorageType): 'UP' | 'DOWN' | 'UNKNOWN' { return this.healthBy()[s]?.status ?? 'UNKNOWN'; }
  latency(s: StorageType): string { const l = this.healthBy()[s]?.latencyMs; return l === null || l === undefined ? '' : `· ${l} ms`; }
  openHouse(c: Category): void { this.router.navigate(['/categories', c]); }
  houseKey(e: KeyboardEvent, c: Category): void { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); this.openHouse(c); } }

  private dur(d: number): number { return this.ui.animations() ? d : 0; }

  private idleBob(): void {
    if (!this.agentEl || !this.ui.animations()) return;
    const body = this.agentEl.querySelector('.bot') as SVGGElement | null;
    if (body) gsap.to(body, { y: -3, duration: 1.2, yoyo: true, repeat: -1, ease: 'sine.inOut' });
  }

  private moveAgent(x: number, y: number, d = 0.8): void {
    if (!this.agentEl) return;
    this.bubbleSide.set(x > AGENT_HOME.x + 60 ? 'left' : 'above');
    const walks = this.dur(d) > 0 && (Math.abs(Number(gsap.getProperty(this.agentEl, 'x')) - x) > 2 || Math.abs(Number(gsap.getProperty(this.agentEl, 'y')) - y) > 2);
    this.moving.set(walks);
    gsap.to(this.agentEl, { x, y, duration: this.dur(d * 1.5), ease: 'power1.inOut', overwrite: 'auto', onComplete: () => this.moving.set(false) });
  }

  private say(state: AgentState, key: string, vals: Record<string, string> = {}, msg?: string | null): void {
    this.agentState.set(state);
    this.agentMsg.set(fmt(T.agent01[key] ?? msg ?? key, vals));
  }

  // ------------------------------------------------------------------- drop
  /** Files are accepted only when they are released on Agent 01 itself. */
  private onAgent(e: DragEvent): boolean {
    const hit = (this.host.nativeElement as HTMLElement).querySelector('#agent-hit') as SVGCircleElement | null;
    if (!hit) return false;
    const r = hit.getBoundingClientRect();
    const pad = 24;
    return e.clientX >= r.left - pad && e.clientX <= r.right + pad && e.clientY >= r.top - pad && e.clientY <= r.bottom + pad;
  }
  private hasFiles(e: DragEvent): boolean { return Array.from(e.dataTransfer?.types ?? []).includes('Files'); }

  onDragOver(e: DragEvent): void {
    if (!this.hasFiles(e)) return;
    e.preventDefault();
    const over = this.onAgent(e);
    if (e.dataTransfer) e.dataTransfer.dropEffect = over ? 'copy' : 'none';
    if (!this.dragging()) this.dragging.set(true);
    if (over !== this.overAgent()) {
      this.overAgent.set(over);
      if (over) this.say('drop', 'dropHere');
      else if (!this.focus()) this.say('idle', 'idle');
    }
  }
  onDragLeave(e: DragEvent): void {
    if ((e.currentTarget as HTMLElement).contains(e.relatedTarget as Node)) return;
    this.endDrag();
  }
  onDrop(e: DragEvent): void {
    e.preventDefault();
    const over = this.onAgent(e);
    const list = Array.from(e.dataTransfer?.files ?? []);
    this.endDrag();
    if (!list.length) return;
    if (!over) {
      this.say('warning', 'dropOnAgent');
      clearTimeout(this.hintTimer);
      this.hintTimer = setTimeout(() => { if (!this.focus() && !this.activeUpload()) this.say('idle', 'idle'); }, 2600);
      return;
    }
    if (!this.authz.can('file:write')) {
      this.say('warning', 'noUploadPermission');
      clearTimeout(this.hintTimer);
      this.hintTimer = setTimeout(() => { if (!this.focus() && !this.activeUpload()) this.say('idle', 'idle'); }, 3200);
      return;
    }
    // No confirmation dialog: the drop itself starts the resumable upload; Agent 01 detects the category.
    this.uploads.start(list, {});
  }
  private endDrag(): void {
    this.dragging.set(false);
    this.overAgent.set(false);
    if (!this.focus() && !this.activeUpload() && this.agentState() === 'drop') this.say('idle', 'idle');
  }

  // --------------------------------------------------------- state machine
  /** Animation driven exclusively by real SSE events. Success only on STORED_VERIFIED. */
  private onEvent(e: LiveEvent): void {
    if (e.category === 'METER_READING') return; // aggregated flow, not animated per file
    const cur = this.focus();
    const userFile = !!this.uploads.byFileId(e.fileId);
    const curUser = cur ? !!this.uploads.byFileId(cur.fileId) : false;
    const curDone = !cur || (TERMINAL.includes(cur.status) && Date.now() - cur.at > 2500);
    // A file the user just dropped always takes priority over background traffic.
    if (cur && cur.fileId !== e.fileId && !curDone && !(userFile && !curUser)) return;

    if (!cur || cur.fileId !== e.fileId) {
      this.hotHouse.set(null); this.wire.set(null);
      if (this.packetEl) gsap.set(this.packetEl, { opacity: 0 });
      this.packetT.t = 0;
      this.packetLabel.set('');
      this.focusFile.emit(e.fileId);
    }
    const f: Focus = {
      fileId: e.fileId, category: e.category ?? (cur?.fileId === e.fileId ? cur.category : null),
      storage: e.storage ?? (cur?.fileId === e.fileId ? cur.storage : null),
      status: e.status === 'FALLBACK' || e.status === 'MISMATCH' ? (cur?.status ?? e.status) : e.status, at: Date.now(),
    };
    this.focus.set(f);
    clearTimeout(this.resetTimer);
    const cat = f.category ? T.categoryHouse[f.category] : '';
    const st = f.storage ? T.storage[f.storage] : '';

    switch (e.status) {
      case 'RECEIVED':
        if (userFile) { this.say('carrying', 'RECEIVED'); this.moveAgent(AGENT_HOME.x, AGENT_HOME.y, 0.5); break; }
        // Background files arrive at the gate: Agent 01 fetches them.
        this.entryFile.set(true);
        this.say('walking', 'RECEIVED');
        this.moveAgent(ENTRY.x + 50, ENTRY.y, 0.8);
        setTimeout(() => {
          this.entryFile.set(false);
          if (this.agentState() === 'walking') this.agentState.set('carrying');
          this.moveAgent(AGENT_HOME.x, AGENT_HOME.y, 0.8);
        }, this.dur(0.9) * 1000);
        break;
      case 'ANALYZING':
        this.entryFile.set(false);
        this.moveAgent(AGENT_HOME.x, AGENT_HOME.y, 0.5);
        this.say('scanning', 'ANALYZING');
        break;
      case 'CATEGORIZED':
        if (f.category) { this.hotHouse.set(f.category); const d = doorstep(f.category); this.moveAgent(d.x, d.y, 1.1); }
        this.say('walking', 'CATEGORIZED', { cat });
        break;
      case 'DESTINATION_SELECTED':
        this.goToHouse(f);
        this.say('carrying', 'DESTINATION_SELECTED', { storage: st });
        break;
      case 'TRANSFERRING':
      case 'TRANSFER_RESUMED': {
        this.goToHouse(f);
        const ratio = e.totalBytes && e.bytesTransferred !== null && e.bytesTransferred !== undefined ? e.bytesTransferred / e.totalBytes : null;
        this.packet(ratio ?? Math.max(this.packetT.t, 0.15), ratio === null);
        this.packetLabel.set(ratio === null ? '' : `${Math.round(ratio * 100)}%`);
        this.say(e.status === 'TRANSFER_RESUMED' ? 'warning' : 'carrying', e.status);
        break;
      }
      case 'FALLBACK':
        this.goToHouse(f);
        this.packetT.t = 0;
        this.say('warning', 'FALLBACK', { storage: st });
        break;
      case 'MISMATCH':
        this.say('warning', 'MISMATCH');
        break;
      case 'STORED_VERIFIED':
        this.goToHouse(f);
        this.packet(1, false, () => {
          this.pulseServer.set(f.storage);
          if (this.packetEl) gsap.to(this.packetEl, { opacity: 0, duration: this.dur(0.4), delay: this.dur(0.2) });
          setTimeout(() => this.pulseServer.set(null), 1600);
        });
        this.packetLabel.set('');
        this.say('success', 'STORED_VERIFIED');
        this.scheduleReset(7000);
        break;
      case 'PENDING':
        if (this.packetEl) gsap.to(this.packetEl, { opacity: 0.35, duration: this.dur(0.3) });
        this.say('warning', 'PENDING');
        this.scheduleReset(12000);
        break;
      case 'TRANSFER_FAILED':
        if (this.packetEl) gsap.to(this.packetEl, { opacity: 0, duration: this.dur(0.3) });
        this.say('error', 'TRANSFER_FAILED', {}, e.message);
        this.scheduleReset(12000);
        break;
      default:
        if (e.message) this.agentMsg.set(e.message);
    }
  }

  /** Agent 01 stands at the house and the wire to the chosen storage is plugged in. */
  private goToHouse(f: Focus): void {
    if (!f.category) return;
    this.hotHouse.set(f.category);
    const d = doorstep(f.category);
    this.moveAgent(d.x, d.y, 0.9);
    if (f.storage) {
      const w = this.wire();
      if (!w || w.cat !== f.category || w.st !== f.storage) { this.wire.set({ cat: f.category, st: f.storage }); this.packetT.t = 0; }
    }
  }

  private scheduleReset(ms: number): void {
    this.resetTimer = setTimeout(() => {
      this.focus.set(null);
      this.hotHouse.set(null); this.wire.set(null);
      this.moveAgent(AGENT_HOME.x, AGENT_HOME.y, 1);
      if (this.activeUpload()) return; // the upload effect owns the bubble
      this.say('idle', 'idle');
    }, ms);
  }

  private packet(to: number, creep: boolean, done?: () => void): void {
    if (!this.packetEl) return;
    // The wire path is rendered on the next change detection; wait for it.
    requestAnimationFrame(() => {
      const path = (this.host.nativeElement as HTMLElement).querySelector('#wire-hot') as SVGPathElement | null;
      if (!path || !this.packetEl) return;
      const len = path.getTotalLength();
      const place = () => {
        const p = path.getPointAtLength(len * this.packetT.t);
        this.packetEl!.setAttribute('transform', `translate(${p.x} ${p.y})`);
      };
      place();
      gsap.set(this.packetEl, { opacity: 1 });
      // Without byte progress, creep towards 90% but never show arrival before STORED_VERIFIED.
      const target = creep ? Math.min(0.9, this.packetT.t + 0.35) : to;
      gsap.to(this.packetT, { t: target, duration: this.dur(creep ? 2.2 : 0.7), ease: creep ? 'power1.out' : 'power2.out', onUpdate: place, onComplete: done, overwrite: true });
    });
  }
}
