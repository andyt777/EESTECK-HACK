import { Component, DestroyRef, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';
import { Api, Loader, errorText } from '../../core/api.service';
import { EventStream } from '../../core/events.service';
import { DecisionView, VerifyResult } from '../../core/models';
import { T } from '../../core/texts';
import { UiState } from '../../core/ui-state.service';
import { DecisionPanel } from '../../features/decision-panel';
import { SHARED } from '../../shared/ui';

@Component({
  selector: 'fg-passport',
  imports: [RouterLink, MatSnackBarModule, DecisionPanel, ...SHARED],
  templateUrl: './passport-page.html',
  styles: [`
    .ident { display: flex; gap: 16px; align-items: flex-start; }
    .ident .ico { width: 56px; height: 56px; border-radius: 14px; display: grid; place-items: center; background: rgba(255,119,188,.1); border: 1px solid rgba(255,119,188,.35); }
    .ident .ico .ms { font-size: 30px; color: var(--cyan); }
    .journey { display: grid; grid-template-columns: repeat(7, 1fr); gap: 0; position: relative; }
    .step { display: flex; flex-direction: column; align-items: center; text-align: center; gap: 6px; position: relative; padding: 0 4px; }
    .step::before { content: ''; position: absolute; top: 17px; left: -50%; width: 100%; height: 2px; background: var(--line2); z-index: 0; }
    .step:first-child::before { display: none; }
    .step.done::before { background: linear-gradient(90deg, var(--cyan), var(--green)); }
    .step .n { width: 36px; height: 36px; border-radius: 50%; display: grid; place-items: center; z-index: 1; background: var(--panel); border: 2px solid var(--line2); color: var(--muted); font-weight: 700; }
    .step.done .n { border-color: var(--green); color: #ffffff; background: var(--green); box-shadow: none; }
    .step .lb { font-size: 12px; font-weight: 600; } .step .at { font-size: 10.5px; font-family: var(--mono); color: var(--muted); }
    .sha { display: flex; gap: 6px; align-items: center; word-break: break-all; }
    .audit-row { display: grid; grid-template-columns: 150px 200px 1fr 120px; gap: 10px; padding: 7px 0; border-bottom: 1px solid var(--line); font-size: 12.5px; align-items: center; }
    .preview { width: 100%; max-height: 520px; border: 1px solid var(--line); border-radius: 8px; background: #000; }
    iframe.preview { height: 520px; background: #fff; }
    @media (max-width: 900px) { .journey { grid-template-columns: repeat(4, 1fr); row-gap: 14px; } .audit-row { grid-template-columns: 1fr 1fr; } }
  `],
})
export class PassportPage {
  readonly t = T;
  readonly id = input.required<string>();
  readonly api = inject(Api);
  private readonly snack = inject(MatSnackBar);
  private readonly ui = inject(UiState);
  private readonly events = inject(EventStream);
  private readonly sanitizer = inject(DomSanitizer);

  readonly auditType = signal<string>('');
  readonly p = new Loader(() => this.api.passport(this.id(), { auditSize: 200, auditType: this.auditType() || null }));
  readonly decision = signal<DecisionView | null>(null);
  readonly verifying = signal(false);
  readonly verifyResult = signal<VerifyResult | null>(null);
  readonly verifyError = signal<string | null>(null);
  readonly showPreview = signal(false);
  readonly jsonPreview = signal<string | null>(null);

  readonly ctx = computed(() => Object.entries(this.p.data()?.context ?? {}).filter(([, v]) => v !== null && v !== ''));
  readonly can = (a: string) => (this.p.data()?.actions ?? []).includes(a);
  readonly suspect = computed(() => this.p.data()?.integrity.status === 'MISMATCH');
  readonly kind = computed(() => {
    const ct = (this.p.data()?.identity.contentType ?? '').toLowerCase();
    if (ct.startsWith('image/')) return 'image';
    if (ct.startsWith('video/')) return 'video';
    if (ct === 'application/pdf') return 'pdf';
    if (ct.includes('json') || ct.startsWith('text/')) return 'text';
    return 'none';
  });
  /** Signed inline link, requested when the preview opens (an img/video/iframe cannot send the token). */
  readonly inlineUrl = signal<string | null>(null);
  readonly previewError = signal<string | null>(null);
  readonly safeInline = computed<SafeResourceUrl | null>(() => {
    const url = this.inlineUrl();
    return url ? this.sanitizer.bypassSecurityTrustResourceUrl(url) : null;
  });
  readonly auditTypes = computed(() => Array.from(new Set((this.p.data()?.audit.items ?? []).map((a) => a.type))));

  constructor() {
    effect(() => {
      const id = this.id();
      untracked(() => {
        this.p.load();
        this.decision.set(null);
        this.verifyResult.set(null);
        this.showPreview.set(false);
        this.inlineUrl.set(null);
        this.api.decision(id).subscribe({ next: (d) => this.decision.set(d), error: () => this.decision.set(null) });
      });
    });
    effect(() => this.ui.simulatedOnPage.set(this.p.data()?.identity.dataOrigin === 'SIMULATED'));
    const sub = this.events.fileEvents$.subscribe((e) => {
      if (e.fileId === this.id()) {
        this.p.load(true);
        if (['DESTINATION_SELECTED', 'FALLBACK', 'STORED_VERIFIED'].includes(e.status)) {
          this.api.decision(this.id()).subscribe({ next: (d) => this.decision.set(d), error: () => {} });
        }
      }
    });
    inject(DestroyRef).onDestroy(() => sub.unsubscribe());
  }

  setAudit(t: string): void { this.auditType.set(t); this.p.load(true); }

  copy(text: string | null | undefined): void {
    if (!text) return;
    navigator.clipboard?.writeText(text).then(() => this.snack.open(T.app.copied, undefined, { duration: 1500 }));
  }

  verify(): void {
    this.verifying.set(true);
    this.verifyError.set(null);
    this.api.verify(this.id()).subscribe({
      next: (r) => { this.verifyResult.set(r); this.verifying.set(false); this.p.load(true); },
      error: (e) => { this.verifyError.set(errorText(e)); this.verifying.set(false); this.p.load(true); },
    });
  }

  download(suspect = false): void {
    this.api.contentLink(this.id(), false, suspect).subscribe({
      next: (link) => { window.location.href = link.url; },
      error: (e) => this.snack.open(errorText(e), T.app.close, { duration: 6000 }),
    });
  }

  preview(): void {
    this.showPreview.set(!this.showPreview());
    if (!this.showPreview()) return;
    this.previewError.set(null);
    this.api.contentLink(this.id(), true).subscribe({
      next: (link) => {
        this.inlineUrl.set(link.url);
        if (this.kind() === 'text') {
          fetch(link.url).then((r) => r.text()).then((txt) => {
            let out = txt.slice(0, 200_000);
            try { out = JSON.stringify(JSON.parse(out), null, 2); } catch { /* not json */ }
            this.jsonPreview.set(out);
          }).catch((e) => this.jsonPreview.set(String(e)));
        }
      },
      error: (e) => this.previewError.set(errorText(e)),
    });
  }

  askAssistant(): void {
    this.ui.openAssistant({ fileId: this.id() }, `Explică-mi pașaportul fișierului ${this.p.data()?.identity.name ?? this.id()}: traseul, decizia de rutare și integritatea.`);
  }

  ctxLabel(k: string): string { return T.ctx[k] ?? k; }
  ctxValue(k: string, v: any): string {
    if (k === 'eventTime') { const d = new Date(v); return isNaN(d.getTime()) ? String(v) : d.toLocaleString('ro-RO', { timeZone: 'Europe/Bucharest' }); }
    return typeof v === 'object' ? JSON.stringify(v) : String(v);
  }
  json(v: any): string {
    if (!v || (typeof v === 'object' && !Object.keys(v).length)) return '';
    return typeof v === 'string' ? v : Object.entries(v).map(([k, x]) => `${k}: ${typeof x === 'object' ? JSON.stringify(x) : x}`).join(' · ');
  }
}
