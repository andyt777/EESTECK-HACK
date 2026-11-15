import { PpcastorAvatar } from './ppcastor';
import { Component, Pipe, PipeTransform, computed, input, output } from '@angular/core';
import { T } from '../core/texts';

const TZ = 'Europe/Bucharest';

export function formatSize(b: number | null | undefined): string {
  if (b === null || b === undefined || isNaN(b)) return '—';
  const u = ['B', 'KB', 'MB', 'GB', 'TB'];
  let i = 0;
  let v = b;
  while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
  return `${v.toFixed(i === 0 ? 0 : v < 10 ? 1 : 0)} ${u[i]}`;
}

export function formatDate(v: string | null | undefined, withSeconds = false): string {
  if (!v) return '—';
  const d = new Date(v);
  if (isNaN(d.getTime())) return String(v);
  return d.toLocaleString('ro-RO', {
    timeZone: TZ, day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit',
    ...(withSeconds ? { second: '2-digit' } : {}),
  });
}

export function formatTime(v: string | null | undefined): string {
  if (!v) return '—';
  const d = new Date(v);
  if (isNaN(d.getTime())) return String(v);
  return d.toLocaleTimeString('ro-RO', { timeZone: TZ, hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

export function formatNum(v: number | null | undefined, digits = 0): string {
  if (v === null || v === undefined || isNaN(v)) return '—';
  return v.toLocaleString('ro-RO', { maximumFractionDigits: digits });
}

@Pipe({ name: 'size' })
export class SizePipe implements PipeTransform { transform(v: number | null | undefined) { return formatSize(v); } }

@Pipe({ name: 'dt' })
export class DatePipe implements PipeTransform { transform(v: string | null | undefined, s = false) { return formatDate(v, s); } }

@Pipe({ name: 'tm' })
export class TimePipe implements PipeTransform { transform(v: string | null | undefined) { return formatTime(v); } }

@Pipe({ name: 'num' })
export class NumPipe implements PipeTransform { transform(v: number | null | undefined, d = 0) { return formatNum(v, d); } }

@Pipe({ name: 'join' })
export class JoinPipe implements PipeTransform {
  transform(v: (string | null | undefined)[] | null | undefined, sep = ' · ') { const r = (v ?? []).filter((x) => !!x).join(sep); return r || '—'; }
}

@Pipe({ name: 'cat' })
export class CategoryPipe implements PipeTransform { transform(v: string | null | undefined) { return v ? (T.category[v] ?? v) : '—'; } }

/** Every file state: text + icon + colour (never colour alone). */
export const STATUS_META: Record<string, { icon: string; tone: string }> = {
  UPLOADING: { icon: 'upload', tone: 'info' },
  UPLOAD_ERROR: { icon: 'error', tone: 'bad' },
  RECEIVED: { icon: 'inbox', tone: 'info' },
  ANALYZING: { icon: 'manage_search', tone: 'info' },
  CATEGORIZED: { icon: 'category', tone: 'info' },
  DESTINATION_SELECTED: { icon: 'alt_route', tone: 'info' },
  TRANSFERRING: { icon: 'sync_alt', tone: 'active' },
  TRANSFER_RESUMED: { icon: 'replay', tone: 'warn' },
  STORED_VERIFIED: { icon: 'verified', tone: 'ok' },
  PENDING: { icon: 'hourglass_top', tone: 'warn' },
  TRANSFER_FAILED: { icon: 'report', tone: 'bad' },
  FALLBACK: { icon: 'fork_right', tone: 'warn' },
  MISMATCH: { icon: 'gpp_bad', tone: 'bad' },
};

@Component({
  selector: 'fg-status',
  template: `<span class="badge tone-{{ meta().tone }}" [attr.title]="label()">
    <span class="ms" aria-hidden="true">{{ meta().icon }}</span>{{ label() }}</span>`,
})
export class StatusBadge {
  readonly status = input.required<string | null | undefined>();
  readonly label = computed(() => T.status[this.status() ?? ''] ?? this.status() ?? '—');
  readonly meta = computed(() => STATUS_META[this.status() ?? ''] ?? { icon: 'help', tone: 'muted' });
}

@Component({
  selector: 'fg-integrity',
  template: `<span class="badge tone-{{ tone() }}"><span class="ms" aria-hidden="true">{{ icon() }}</span>{{ label() }}</span>`,
})
export class IntegrityBadge {
  readonly status = input.required<string | null | undefined>();
  readonly label = computed(() => T.integrity[this.status() ?? ''] ?? '—');
  readonly tone = computed(() => ({ VERIFIED_INTACT: 'ok', MISMATCH: 'bad' } as Record<string, string>)[this.status() ?? ''] ?? 'muted');
  readonly icon = computed(() => ({ VERIFIED_INTACT: 'verified_user', MISMATCH: 'gpp_bad' } as Record<string, string>)[this.status() ?? ''] ?? 'shield');
}

@Component({
  selector: 'fg-storage',
  template: `@if (storage()) {<span class="chip storage-{{ storage() }}"><span class="ms" aria-hidden="true">{{ icon() }}</span>{{ label() }}</span>} @else {<span class="muted">—</span>}`,
})
export class StorageChip {
  readonly storage = input<string | null | undefined>();
  readonly label = computed(() => T.storage[this.storage() ?? ''] ?? this.storage());
  readonly icon = computed(() => ({ DATABASE: 'database', S3: 'cloud', FTP: 'folder_shared' } as Record<string, string>)[this.storage() ?? ''] ?? 'storage');
}

@Component({
  selector: 'fg-origin',
  template: `@if (origin() === 'SIMULATED') {<span class="badge sim" title="dataOrigin=SIMULATED"><span class="ms" aria-hidden="true">science</span>{{ t.app.simulated }}</span>}
    @else if (showReal() && origin() === 'REAL') {<span class="badge tone-muted">{{ t.app.real }}</span>}`,
})
export class OriginBadge {
  readonly origin = input<string | null | undefined>();
  readonly showReal = input(false);
  readonly t = T;
}

@Component({
  selector: 'fg-severity',
  template: `<span class="badge sev-{{ value() }}"><span class="ms" aria-hidden="true">{{ icon() }}</span>{{ label() || t.incidents.severities[value() ?? ''] || value() }}</span>`,
})
export class SeverityBadge {
  readonly value = input<string | null | undefined>();
  readonly label = input<string | null | undefined>();
  readonly t = T;
  readonly icon = computed(() => ({ CRITICAL: 'crisis_alert', HIGH: 'warning', MEDIUM: 'error_outline', LOW: 'info' } as Record<string, string>)[this.value() ?? ''] ?? 'info');
}

@Component({
  selector: 'fg-state',
  template: `
    @if (loading()) {
      <div class="state"><div class="spinner"></div><span>{{ t.app.loading }}</span></div>
    } @else if (unavailable()) {
      <div class="state"><span class="ms big" aria-hidden="true">power_off</span><b>{{ t.app.unavailable }}</b><span class="muted">{{ t.app.unavailableHint }}</span>
        <button class="btn ghost sm" (click)="retry.emit()">{{ t.app.retry }}</button></div>
    } @else if (error()) {
      <div class="state bad"><span class="ms big" aria-hidden="true">error</span><b>{{ t.app.error }}</b><span class="muted mono small">{{ error() }}</span>
        <button class="btn ghost sm" (click)="retry.emit()">{{ t.app.retry }}</button></div>
    } @else if (empty()) {
      <div class="state"><span class="ms big" aria-hidden="true">inbox</span><span>{{ emptyText() || t.app.empty }}</span></div>
    }`,
})
export class StateView {
  readonly loading = input(false);
  readonly error = input<string | null>(null);
  readonly unavailable = input(false);
  readonly empty = input(false);
  readonly emptyText = input<string | null>(null);
  readonly retry = output<void>();
  readonly t = T;
}

/** WhatsApp-style text: *bold* rendered as bold, everything else as plain text (no HTML injection). */
@Component({
  selector: 'fg-wa',
  template: `@for (p of parts(); track $index) { @if (p.b) { <b>{{ p.t }}</b> } @else { {{ p.t }} } }`,
  styles: [':host { white-space: pre-wrap; }'],
})
export class WaText {
  readonly text = input<string | null | undefined>('');
  readonly parts = computed(() => (this.text() ?? '').split(/(\*[^*\n]+\*)/g).filter((x) => x).map((x) => (/^\*[^*]+\*$/.test(x) ? { b: true, t: x.slice(1, -1) } : { b: false, t: x })));
}

export const SHARED = [WaText, JoinPipe, SizePipe, DatePipe, TimePipe, NumPipe, CategoryPipe, StatusBadge, IntegrityBadge, StorageChip, OriginBadge, SeverityBadge, StateView, PpcastorAvatar];
