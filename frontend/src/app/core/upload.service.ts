import { Injectable, inject, signal } from '@angular/core';
import * as tus from 'tus-js-client';
import { Api } from './api.service';
import { EventStream } from './events.service';
import { Category, LiveEvent, StorageType, TERMINAL } from './models';

export interface UploadMeta {
  category?: Category | '';
  zone?: string; station?: string; equipment?: string; eventType?: string; eventTime?: string; description?: string;
}

export interface TimelineStep { status: string; at: string; message?: string | null; storage?: StorageType | null; }

export interface UploadItem {
  key: string;
  name: string;
  size: number;
  uploadedBytes: number;
  fileId: string | null;
  status: string;
  category: Category | null;
  storage: StorageType | null;
  bytesTransferred: number | null;
  totalBytes: number | null;
  message: string | null;
  error: string | null;
  paused: boolean;
  timeline: TimelineStep[];
}

const CHUNK = 8 * 1024 * 1024; // ≤ 16 MB (CLAUDE.md)

@Injectable({ providedIn: 'root' })
export class UploadService {
  private readonly events = inject(EventStream);
  private readonly api = inject(Api);
  private readonly uploads = new Map<string, tus.Upload>();

  readonly items = signal<UploadItem[]>([]);
  /** Last file the user uploaded — Agent 01 follows it in the Village. */
  readonly focus = signal<string | null>(null);

  constructor() {
    this.events.fileEvents$.subscribe((e) => this.apply(e));
  }

  start(files: File[], meta: UploadMeta): void {
    for (const file of files) this.startOne(file, meta);
  }

  private startOne(file: File, meta: UploadMeta): void {
    const key = `${Date.now()}-${Math.random().toString(36).slice(2)}`;
    const item: UploadItem = {
      key, name: file.name, size: file.size, uploadedBytes: 0, fileId: null, status: 'UPLOADING',
      category: (meta.category || null) as Category | null, storage: null, bytesTransferred: null, totalBytes: file.size,
      message: null, error: null, paused: false, timeline: [{ status: 'UPLOADING', at: new Date().toISOString() }],
    };
    this.items.update((l) => [item, ...l]);
    this.focus.set(key);

    const metadata: Record<string, string> = {
      filename: file.name,
      filetype: file.type || 'application/octet-stream',
      contentType: file.type || 'application/octet-stream',
      dataOrigin: 'REAL',
    };
    for (const k of ['category', 'zone', 'station', 'equipment', 'eventType', 'description'] as const) {
      const v = meta[k];
      if (v) metadata[k] = String(v);
    }
    if (meta.eventTime) {
      const d = new Date(meta.eventTime);
      if (!isNaN(d.getTime())) metadata['eventTime'] = d.toISOString();
    }

    const upload = new tus.Upload(file, {
      endpoint: '/api/uploads',
      chunkSize: CHUNK,
      retryDelays: [0, 1000, 3000, 5000, 10000, 20000],
      metadata,
      removeFingerprintOnSuccess: true,
      onProgress: (sent) => {
        this.patch(key, { uploadedBytes: sent, fileId: this.idOf(upload) ?? this.get(key)?.fileId ?? null });
        this.catchUp(key);
      },
      onError: (err) => {
        this.patch(key, { status: 'UPLOAD_ERROR', error: String((err as any)?.message ?? err) });
        this.push(key, { status: 'UPLOAD_ERROR', at: new Date().toISOString(), message: String((err as any)?.message ?? err) });
      },
      onSuccess: () => {
        this.patch(key, { uploadedBytes: file.size, fileId: this.idOf(upload) });
        this.catchUp(key);
        this.poll(key);
      },
    });
    this.uploads.set(key, upload);
    upload.findPreviousUploads().then((prev) => {
      if (prev.length) upload.resumeFromPreviousUpload(prev[0]);
      upload.start();
    });
  }

  pause(key: string): void {
    this.uploads.get(key)?.abort();
    this.patch(key, { paused: true });
  }

  resume(key: string): void {
    this.uploads.get(key)?.start();
    this.patch(key, { paused: false });
  }

  get(key: string): UploadItem | undefined {
    return this.items().find((i) => i.key === key);
  }

  byFileId(fileId: string): UploadItem | undefined {
    return this.items().find((i) => i.fileId === fileId);
  }

  private idOf(u: tus.Upload): string | null {
    const url = u.url;
    if (!url) return null;
    const seg = url.split('?')[0].split('/').filter(Boolean).pop() ?? null;
    return seg && /^[0-9a-f-]{36}$/i.test(seg) ? seg : null;
  }

  /** Replays events that arrived before we knew the fileId. */
  private catchUp(key: string): void {
    const it = this.get(key);
    if (!it?.fileId) return;
    const h = this.events.history.get(it.fileId) ?? [];
    for (const e of h) {
      if (!it.timeline.some((s) => s.status === e.status && s.at === e.timestamp)) this.apply(e);
    }
  }

  private apply(e: LiveEvent): void {
    const it = this.items().find((i) => i.fileId === e.fileId);
    if (!it) return;
    const patch: Partial<UploadItem> = { message: e.message, category: e.category ?? it.category };
    if (e.storage) patch.storage = e.storage;
    if (e.bytesTransferred !== null && e.bytesTransferred !== undefined) patch.bytesTransferred = e.bytesTransferred;
    if (e.totalBytes) patch.totalBytes = e.totalBytes;
    // FALLBACK / MISMATCH are transient markers: shown in the timeline, the state stays the real one.
    if (e.status !== 'FALLBACK' && e.status !== 'MISMATCH') patch.status = e.status;
    this.patch(it.key, patch);
    const last = it.timeline[it.timeline.length - 1];
    if (!(last && last.status === e.status && (e.status === 'TRANSFERRING'))) {
      this.push(it.key, { status: e.status, at: e.timestamp, message: e.message, storage: e.storage });
    }
  }

  /** Safety net in case SSE events were missed: reads the real state from the passport. */
  private poll(key: string, attempt = 0): void {
    const it = this.get(key);
    if (!it?.fileId || TERMINAL.includes(it.status) || attempt > 40) return;
    setTimeout(() => {
      const cur = this.get(key);
      if (!cur?.fileId || TERMINAL.includes(cur.status)) return;
      this.api.passport(cur.fileId).subscribe({
        next: (p) => {
          const now = this.get(key);
          if (!now) return;
          if (p.status !== now.status && TERMINAL.includes(p.status)) {
            this.patch(key, { status: p.status, storage: p.storage?.type ?? now.storage, category: p.identity.category });
            this.push(key, { status: p.status, at: new Date().toISOString(), storage: p.storage?.type ?? null });
          } else {
            this.poll(key, attempt + 1);
          }
        },
        error: () => this.poll(key, attempt + 1),
      });
    }, attempt < 5 ? 1500 : 4000);
  }

  private patch(key: string, p: Partial<UploadItem>): void {
    this.items.update((l) => l.map((i) => (i.key === key ? { ...i, ...p } : i)));
  }

  private push(key: string, s: TimelineStep): void {
    this.items.update((l) => l.map((i) => (i.key === key ? { ...i, timeline: [...i.timeline, s] } : i)));
  }
}
