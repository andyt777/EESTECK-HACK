import { Component, computed, inject, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { T } from '../core/texts';
import { UploadItem, UploadService } from '../core/upload.service';
import { CategoryPipe, SizePipe, StatusBadge, StorageChip, TimePipe, STATUS_META } from '../shared/ui';

/** Live status timeline per uploaded file (driven by SSE events of that file). */
@Component({
  selector: 'fg-upload-tracker',
  imports: [RouterLink, SizePipe, TimePipe, CategoryPipe, StatusBadge, StorageChip],
  template: `
  @for (it of shown(); track it.key) {
    <div class="item" [class.done]="it.status === 'STORED_VERIFIED'" [class.bad]="it.status === 'TRANSFER_FAILED' || it.status === 'UPLOAD_ERROR'">
      <div class="row">
        <span class="ms">description</span>
        <b class="grow ellipsis" [title]="it.name">{{ it.name }}</b>
        <fg-status [status]="it.status" />
      </div>
      <div class="row small muted wrap">
        <span>{{ it.size | size }}</span>
        @if (it.category) { <span>· {{ it.category | cat }}</span> }
        @if (it.storage) { <fg-storage [storage]="it.storage" /> }
        @if (it.message) { <span class="msg">„{{ it.message }}”</span> }
      </div>
      @if (it.status === 'UPLOADING') {
        <div class="progress"><i [style.width.%]="pct(it.uploadedBytes, it.size)"></i></div>
        <div class="row tiny muted between"><span>{{ it.uploadedBytes | size }} / {{ it.size | size }} {{ t.upload.uploaded }}</span>
          @if (it.paused) { <button class="btn ghost sm" (click)="up.resume(it.key)">{{ t.upload.resume }}</button> }
          @else { <button class="btn ghost sm" (click)="up.pause(it.key)">{{ t.upload.pause }}</button> }</div>
      }
      @if (it.status === 'TRANSFERRING' || it.status === 'TRANSFER_RESUMED') {
        <div class="progress"><i [style.width.%]="pct(it.bytesTransferred ?? 0, it.totalBytes ?? it.size)"></i></div>
        <div class="tiny muted">{{ it.bytesTransferred ?? 0 | size }} / {{ it.totalBytes ?? it.size | size }} → {{ it.storage }}</div>
      }
      @if (!compact()) {
        <ol class="steps">
          @for (s of it.timeline; track $index) {
            <li class="tone-{{ tone(s.status) }}"><span class="ms">{{ icon(s.status) }}</span>{{ label(s.status) }} <span class="tiny mono muted">{{ s.at | tm }}</span></li>
          }
        </ol>
      }
      @if (it.fileId) {
        <div class="row">
          <a class="btn sm" [class.ghost]="it.status !== 'STORED_VERIFIED'" [routerLink]="['/files', it.fileId]"><span class="ms">badge</span>{{ it.status === 'STORED_VERIFIED' ? t.control.openPassport : t.upload.passport }}</a>
          <span class="mono tiny muted">{{ it.fileId }}</span>
        </div>
      }
      @if (it.error) { <div class="small" style="color: var(--red)">{{ it.error }}</div> }
    </div>
  } @empty {
    <div class="muted small empty">{{ t.upload.queueEmpty }}</div>
  }`,
  styles: [`
    :host { display: flex; flex-direction: column; gap: 10px; }
    .item { border: 1px solid var(--line); border-radius: 10px; padding: 10px 12px; background: rgba(5,11,24,.45); display: flex; flex-direction: column; gap: 7px; }
    .item.done { border-color: rgba(52,211,153,.45); } .item.bad { border-color: rgba(244,63,94,.5); }
    .msg { color: #b7c6e4; font-style: italic; }
    .steps { list-style: none; margin: 0; padding: 0; display: flex; flex-wrap: wrap; gap: 4px; }
    .steps li { display: inline-flex; align-items: center; gap: 3px; font-size: 11px; padding: 1px 7px 1px 4px; border-radius: 999px; border: 1px solid; }
    .steps .ms { font-size: 13px; }
    .empty { padding: 10px; text-align: center; }
  `],
})
export class UploadTracker {
  readonly t = T;
  readonly up = inject(UploadService);
  readonly compact = input(false);
  readonly limit = input(50);
  readonly shown = computed<UploadItem[]>(() => this.up.items().slice(0, this.limit()));
  pct(a: number, b: number): number { return b ? Math.min(100, (a / b) * 100) : 0; }
  label(s: string): string { return T.status[s] ?? s; }
  tone(s: string): string { return STATUS_META[s]?.tone ?? 'muted'; }
  icon(s: string): string { return STATUS_META[s]?.icon ?? 'help'; }
}
