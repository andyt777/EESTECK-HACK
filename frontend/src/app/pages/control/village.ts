import { Component, DestroyRef, ElementRef, afterNextRender, computed, inject, input, output, signal } from '@angular/core';
import { Router } from '@angular/router';
import { gsap } from 'gsap';
import { EventStream } from '../../core/events.service';
import { CATEGORIES, Category, CategorySummary, LiveEvent, MeterStats, STORAGES, StorageHealth, StorageType, TERMINAL } from '../../core/models';
import { T, fmt } from '../../core/texts';
import { UiState } from '../../core/ui-state.service';
import { UploadService } from '../../core/upload.service';
import { formatNum, formatSize } from '../../shared/ui';

type AgentState = 'idle' | 'scanning' | 'walking' | 'carrying' | 'success' | 'warning' | 'error' | 'drop';

const HOUSE: Record<Category, { x: number; y: number; icon: string; color: string }> = {
  METER_READING: { x: 560, y: 92, icon: 'electric_meter', color: '#22d3ee' },
  FAULT_RECORD: { x: 560, y: 232, icon: 'bolt', color: '#fbbf24' },
  CONNECTION_DOCUMENT: { x: 560, y: 372, icon: 'folder_shared', color: '#a78bfa' },
  DRONE_VIDEO: { x: 560, y: 512, icon: 'videocam', color: '#34d399' },
};
const SERVER: Record<StorageType, { x: number; y: number; color: string; icon: string }> = {
  DATABASE: { x: 1060, y: 140, color: '#60a5fa', icon: 'database' },
  S3: { x: 1060, y: 315, color: '#f59e0b', icon: 'cloud' },
  FTP: { x: 1060, y: 490, color: '#2dd4bf', icon: 'folder_shared' },
};
const AGENT_HOME = { x: 300, y: 340 };
const ENTRY = { x: 112, y: 340 };

interface Focus { fileId: string; category: Category | null; storage: StorageType | null; status: string; name: string | null; at: number; }

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

  readonly summary = input<CategorySummary[] | null>(null);
  readonly health = input<StorageHealth[] | null>(null);
  readonly meter = input<MeterStats | null>(null);
  readonly files = output<File[]>();
  readonly focusFile = output<string | null>();

  private readonly host = inject(ElementRef<HTMLElement>);
  private readonly events = inject(EventStream);
  private readonly uploads = inject(UploadService);
  private readonly ui = inject(UiState);
  private readonly router = inject(Router);

  readonly agentState = signal<AgentState>('idle');
  readonly agentMsg = signal<string>(T.agent01.idle);
  readonly hotHouse = signal<Category | null>(null);
  readonly hotServer = signal<StorageType | null>(null);
  readonly hotCable = signal<string | null>(null);
  readonly pulseServer = signal<StorageType | null>(null);
  readonly focus = signal<Focus | null>(null);
  readonly entryFile = signal(false);
  readonly dragOver = signal(false);
  readonly success = signal<{ fileId: string; name: string | null } | null>(null);
  readonly packetLabel = signal('');

  private agentEl?: SVGGElement;
  private packetEl?: SVGGElement;
  private packetT = { t: 0 };
  private packetPath: string | null = null;
  private resetTimer?: ReturnType<typeof setTimeout>;

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
  /** Where the meter readings actually go (largest count), default the policy primary. */
  readonly meterTarget = computed<StorageType>(() => {
    const by = this.byCat().METER_READING?.byStorage ?? {};
    let best: StorageType = 'DATABASE';
    let n = -1;
    for (const [k, v] of Object.entries(by)) if (v > n) { n = v; best = k as StorageType; }
    return best;
  });
  readonly meterFlowing = computed(() => (this.meter()?.readingsLastMinute ?? 0) > 0);

  readonly cables = computed(() => {
    const out: { key: string; cat: Category; st: StorageType; d: string; used: boolean }[] = [];
    for (const c of CATEGORIES) for (const s of STORAGES) {
      const h = HOUSE[c], v = SERVER[s];
      const x1 = h.x + 58, y1 = h.y + 16, x2 = v.x - 44, y2 = v.y + 14;
      const mx = (x1 + x2) / 2;
      out.push({ key: `${c}-${s}`, cat: c, st: s, d: `M${x1} ${y1} C${mx} ${y1} ${mx} ${y2} ${x2} ${y2}`, used: (this.byCat()[c]?.byStorage?.[s] ?? 0) > 0 });
    }
    return out;
  });

  constructor() {
    afterNextRender(() => {
      const root = this.host.nativeElement as HTMLElement;
      this.agentEl = root.querySelector('#agent01') as SVGGElement;
      this.packetEl = root.querySelector('#packet') as SVGGElement;
      gsap.set(this.agentEl, { x: AGENT_HOME.x, y: AGENT_HOME.y });
      gsap.set(this.packetEl, { opacity: 0 });
      this.idleBob();
    });
    const sub = this.events.fileEvents$.subscribe((e) => this.onEvent(e));
    inject(DestroyRef).onDestroy(() => { sub.unsubscribe(); gsap.killTweensOf('*'); clearTimeout(this.resetTimer); });
  }

  // ----------------------------------------------------------------- helpers
  houseCount(c: Category): string {
    const s = this.byCat()[c];
    return s ? `${formatNum(s.fileCount)} ${T.control.files} · ${formatSize(s.totalBytes)}` : '—';
  }
  warnings(c: Category): number { const s = this.byCat()[c]; return s ? s.pending + s.mismatch + s.transferFailed : 0; }
  inProgress(c: Category): number { return this.byCat()[c]?.inProgress ?? 0; }
  status(s: StorageType): 'UP' | 'DOWN' | 'UNKNOWN' { return this.healthBy()[s]?.status ?? 'UNKNOWN'; }
  latency(s: StorageType): string { const l = this.healthBy()[s]?.latencyMs; return l === null || l === undefined ? '' : `${l} ms`; }
  meterRate(): string { return `${formatNum(this.meter()?.readingsLastMinute ?? 0)}/min`; }
  meterMid(): { x: number; y: number } {
    const h = HOUSE.METER_READING, v = SERVER[this.meterTarget()];
    return { x: (h.x + 58 + v.x - 44) / 2, y: (h.y + 16 + v.y + 14) / 2 - 12 };
  }
  openHouse(c: Category): void { this.router.navigate(['/categories', c]); }
  openServer(s: StorageType): void { this.router.navigate(['/files'], { queryParams: { storage: s } }); }
  openPassport(): void { const s = this.success(); if (s) this.router.navigate(['/files', s.fileId]); }

  private dur(d: number): number { return this.ui.animations() ? d : 0; }

  private idleBob(): void {
    if (!this.agentEl || !this.ui.animations()) return;
    const body = this.agentEl.querySelector('.bot') as SVGGElement | null;
    if (body) gsap.to(body, { y: -3, duration: 1.2, yoyo: true, repeat: -1, ease: 'sine.inOut' });
  }

  private moveAgent(x: number, y: number, d = 0.7): void {
    if (!this.agentEl) return;
    gsap.to(this.agentEl, { x, y, duration: this.dur(d), ease: 'power2.inOut', overwrite: 'auto' });
  }

  private say(state: AgentState, key: string, vals: Record<string, string> = {}, msg?: string | null): void {
    this.agentState.set(state);
    this.agentMsg.set(fmt(T.agent01[key] ?? msg ?? key, vals));
  }

  // ------------------------------------------------------------------- drop
  onDragOver(e: DragEvent): void {
    e.preventDefault();
    if (!this.dragOver()) { this.dragOver.set(true); this.agentState.set('drop'); this.agentMsg.set(T.agent01.dropHere); }
  }
  onDragLeave(e: DragEvent): void {
    if ((e.currentTarget as HTMLElement).contains(e.relatedTarget as Node)) return;
    this.dragOver.set(false);
    if (!this.focus()) this.say('idle', 'idle');
  }
  onDrop(e: DragEvent): void {
    e.preventDefault();
    this.dragOver.set(false);
    const list = Array.from(e.dataTransfer?.files ?? []);
    if (list.length) this.files.emit(list);
    if (!this.focus()) this.say('idle', 'idle');
  }

  // --------------------------------------------------------- state machine
  /** Animation driven exclusively by real SSE events. Success only on STORED_VERIFIED. */
  private onEvent(e: LiveEvent): void {
    if (e.category === 'METER_READING') return; // aggregated flow, not animated per file
    const cur = this.focus();
    const userFile = !!this.uploads.byFileId(e.fileId);
    const curUser = cur ? !!this.uploads.byFileId(cur.fileId) : false;
    const curDone = !cur || (TERMINAL.includes(cur.status) && Date.now() - cur.at > 2500);
    if (cur && cur.fileId !== e.fileId && !curDone && !(userFile && !curUser)) return;

    if (!cur || cur.fileId !== e.fileId) {
      this.success.set(null);
      this.hotHouse.set(null); this.hotServer.set(null); this.hotCable.set(null);
      gsap.set(this.packetEl!, { opacity: 0 });
      this.packetT.t = 0;
      this.focusFile.emit(e.fileId);
    }
    const name = this.uploads.byFileId(e.fileId)?.name ?? cur?.name ?? null;
    const f: Focus = {
      fileId: e.fileId, category: e.category ?? (cur?.fileId === e.fileId ? cur.category : null),
      storage: e.storage ?? (cur?.fileId === e.fileId ? cur.storage : null),
      status: e.status === 'FALLBACK' || e.status === 'MISMATCH' ? (cur?.status ?? e.status) : e.status, name, at: Date.now(),
    };
    this.focus.set(f);
    clearTimeout(this.resetTimer);
    const cat = f.category ? T.category[f.category] : '';
    const st = f.storage ? T.storage[f.storage] : '';

    switch (e.status) {
      case 'RECEIVED':
        this.entryFile.set(true);
        this.say('walking', 'RECEIVED');
        this.moveAgent(ENTRY.x + 40, ENTRY.y, 0.8);
        setTimeout(() => { this.entryFile.set(false); if (this.agentState() === 'walking') this.agentState.set('carrying'); this.moveAgent(AGENT_HOME.x, AGENT_HOME.y, 0.8); }, this.dur(0.9) * 1000);
        break;
      case 'ANALYZING':
        this.entryFile.set(false);
        this.moveAgent(AGENT_HOME.x, AGENT_HOME.y, 0.5);
        this.say('scanning', 'ANALYZING');
        break;
      case 'CATEGORIZED':
        if (f.category) { this.hotHouse.set(f.category); this.moveAgent(HOUSE[f.category].x - 120, HOUSE[f.category].y + 40, 0.9); }
        this.say('carrying', 'CATEGORIZED', { cat });
        break;
      case 'DESTINATION_SELECTED':
        if (f.category) this.hotHouse.set(f.category);
        if (f.storage) { this.hotServer.set(f.storage); if (f.category) this.hotCable.set(`${f.category}-${f.storage}`); }
        this.say('carrying', 'DESTINATION_SELECTED', { storage: st });
        break;
      case 'TRANSFERRING':
      case 'TRANSFER_RESUMED': {
        if (f.category && f.storage) { this.hotHouse.set(f.category); this.hotServer.set(f.storage); this.hotCable.set(`${f.category}-${f.storage}`); }
        const ratio = e.totalBytes && e.bytesTransferred !== null && e.bytesTransferred !== undefined ? e.bytesTransferred / e.totalBytes : null;
        this.packet(ratio ?? Math.max(this.packetT.t, 0.15), ratio === null);
        this.packetLabel.set(ratio === null ? '' : `${Math.round(ratio * 100)}%`);
        this.say(e.status === 'TRANSFER_RESUMED' ? 'warning' : 'walking', e.status);
        break;
      }
      case 'FALLBACK':
        if (f.storage) { this.hotServer.set(f.storage); if (f.category) this.hotCable.set(`${f.category}-${f.storage}`); }
        this.packetT.t = 0;
        this.say('warning', 'FALLBACK', { storage: st });
        break;
      case 'MISMATCH':
        this.say('warning', 'MISMATCH');
        break;
      case 'STORED_VERIFIED':
        if (f.category && f.storage) this.hotCable.set(`${f.category}-${f.storage}`);
        this.packet(1, false, () => {
          this.pulseServer.set(f.storage);
          gsap.to(this.packetEl!, { opacity: 0, duration: this.dur(0.4), delay: this.dur(0.2) });
          setTimeout(() => this.pulseServer.set(null), 1600);
        });
        this.say('success', 'STORED_VERIFIED');
        this.success.set({ fileId: f.fileId, name: f.name });
        this.scheduleReset(9000);
        break;
      case 'PENDING':
        gsap.to(this.packetEl!, { opacity: 0.35, duration: this.dur(0.3) });
        this.say('warning', 'PENDING');
        this.scheduleReset(12000);
        break;
      case 'TRANSFER_FAILED':
        gsap.to(this.packetEl!, { opacity: 0, duration: this.dur(0.3) });
        this.say('error', 'TRANSFER_FAILED', {}, e.message);
        this.scheduleReset(12000);
        break;
      default:
        if (e.message) this.agentMsg.set(e.message);
    }
  }

  private scheduleReset(ms: number): void {
    this.resetTimer = setTimeout(() => {
      this.focus.set(null);
      this.hotHouse.set(null); this.hotServer.set(null); this.hotCable.set(null);
      this.moveAgent(AGENT_HOME.x, AGENT_HOME.y, 0.8);
      this.say('idle', 'idle');
    }, ms);
  }

  private packet(to: number, creep: boolean, done?: () => void): void {
    const key = this.hotCable();
    if (!key || !this.packetEl) return;
    const path = (this.host.nativeElement as HTMLElement).querySelector(`#cab-${key}`) as SVGPathElement | null;
    if (!path) return;
    if (this.packetPath !== key) { this.packetPath = key; this.packetT.t = 0; }
    const len = path.getTotalLength();
    const place = () => {
      const p = path.getPointAtLength(len * this.packetT.t);
      this.packetEl!.setAttribute('transform', `translate(${p.x} ${p.y})`);
    };
    place();
    gsap.set(this.packetEl, { opacity: 1 });
    // Without byte progress, creep towards 90% but never show arrival before STORED_VERIFIED.
    const target = creep ? Math.min(0.9, this.packetT.t + 0.35) : to;
    gsap.to(this.packetT, { t: target, duration: this.dur(creep ? 2.2 : 0.6), ease: creep ? 'power1.out' : 'power2.out', onUpdate: place, onComplete: done, overwrite: true });
  }
}
