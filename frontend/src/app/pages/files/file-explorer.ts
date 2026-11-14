import { Component, DestroyRef, computed, effect, inject, signal } from '@angular/core';
import { CanDirective } from '../../core/auth/can.directive';
import { KeyValuePipe } from '@angular/common';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { Subject, debounceTime } from 'rxjs';
import { Api, Loader, Params } from '../../core/api.service';
import { EventStream } from '../../core/events.service';
import { CATEGORIES, Category, FILE_STATUSES, INTEGRITIES, STORAGES } from '../../core/models';
import { T } from '../../core/texts';
import { UiState } from '../../core/ui-state.service';
import { UploadDialog } from '../../features/upload-form';
import { SHARED } from '../../shared/ui';

const KEYS = ['q', 'category', 'from', 'to', 'sizeMin', 'sizeMax', 'zone', 'station', 'contentType', 'storage', 'status', 'integrity', 'dataOrigin', 'excludeMeters', 'sort', 'dir', 'page'];

@Component({
  selector: 'fg-file-explorer',
  imports: [RouterLink, MatDialogModule, KeyValuePipe, CanDirective, ...SHARED],
  templateUrl: './file-explorer.html',
})
export class FileExplorer {
  readonly t = T;
  readonly cats = CATEGORIES;
  readonly storages = STORAGES;
  readonly statuses = FILE_STATUSES;
  readonly integrities = INTEGRITIES;
  private readonly api = inject(Api);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly dialog = inject(MatDialog);
  private readonly ui = inject(UiState);
  private readonly events = inject(EventStream);

  readonly fixedCat = signal<Category | null>(null);
  readonly f = signal<Record<string, string | undefined>>({});
  readonly list = new Loader(() => this.api.files(this.query()));
  readonly summary = new Loader(() => this.api.categories());
  readonly catSummary = computed(() => (this.summary.data() ?? []).find((s) => s.category === this.fixedCat()) ?? null);
  readonly size = 25;
  readonly pages = computed(() => Math.max(1, Math.ceil((this.list.data()?.total ?? 0) / this.size)));
  private readonly typing$ = new Subject<void>();

  constructor() {
    this.route.paramMap.subscribe((p) => {
      const c = p.get('category') as Category | null;
      this.fixedCat.set(c && CATEGORIES.includes(c) ? c : null);
      if (c) this.summary.load();
    });
    this.route.queryParamMap.subscribe((q) => {
      const v: Record<string, string> = {};
      for (const k of KEYS) { const x = q.get(k); if (x !== null) v[k] = x; }
      this.f.set(v);
      this.list.load();
    });
    this.typing$.pipe(debounceTime(350)).subscribe(() => this.push());
    const sub = this.events.fileEvents$.pipe(debounceTime(1500)).subscribe((e) => {
      if (e.category !== 'METER_READING' && ['STORED_VERIFIED', 'PENDING', 'TRANSFER_FAILED', 'RECEIVED'].includes(e.status)) this.list.load(true);
    });
    inject(DestroyRef).onDestroy(() => sub.unsubscribe());
    effect(() => this.ui.simulatedOnPage.set(this.hasSim()));
  }

  query(): Params {
    const v = this.f();
    const p: Params = { ...v, size: this.size };
    if (this.fixedCat()) p['category'] = this.fixedCat();
    for (const k of ['from', 'to']) if (v[k]) p[k] = new Date(v[k]).toISOString();
    if (v['sizeMin']) p['sizeMin'] = Math.round(Number(v['sizeMin']) * 1024 * 1024);
    if (v['sizeMax']) p['sizeMax'] = Math.round(Number(v['sizeMax']) * 1024 * 1024);
    return p;
  }

  set(k: string, v: string, typing = false): void {
    this.f.update((o) => { const n = { ...o, [k]: v }; if (!v) delete n[k]; if (k !== 'page') delete n['page']; return n; });
    if (typing) this.typing$.next(); else this.push();
  }

  push(): void {
    this.router.navigate([], { relativeTo: this.route, queryParams: { ...Object.fromEntries(KEYS.map((k) => [k, null])), ...this.f() }, queryParamsHandling: 'merge' });
  }

  reset(): void { this.f.set({}); this.push(); }
  page(n: number): void { this.set('page', String(Math.max(0, Math.min(this.pages() - 1, n)))); }
  curPage(): number { return Number(this.f()['page'] ?? 0); }
  open(id: string): void { this.router.navigate(['/files', id]); }
  sortBy(k: string): void {
    const cur = this.f()['sort'] ?? 'receivedAt';
    const dir = cur === k && (this.f()['dir'] ?? 'desc') === 'desc' ? 'asc' : 'desc';
    this.f.update((o) => ({ ...o, sort: k, dir }));
    this.push();
  }
  arrow(k: string): string {
    const cur = this.f()['sort'] ?? 'receivedAt';
    return cur === k ? ((this.f()['dir'] ?? 'desc') === 'desc' ? '↓' : '↑') : '';
  }
  upload(): void { this.dialog.open(UploadDialog, { data: { category: this.fixedCat() }, maxWidth: '96vw' }); }
  readonly hasSim = computed(() => (this.list.data()?.items ?? []).some((i) => i.dataOrigin === 'SIMULATED'));
}
