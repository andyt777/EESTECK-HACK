import { Component, inject, input, output, signal } from '@angular/core';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { CATEGORIES, Category } from '../core/models';
import { T } from '../core/texts';
import { UploadMeta, UploadService } from '../core/upload.service';
import { SizePipe } from '../shared/ui';

/** Drop zone + context form; starts resumable tus uploads through UploadService. */
@Component({
  selector: 'fg-upload-form',
  imports: [SizePipe],
  template: `
  <div class="drop" [class.over]="over()" (dragover)="$event.preventDefault(); over.set(true)" (dragleave)="over.set(false)" (drop)="drop($event)" (click)="picker.click()">
    <span class="ms big">cloud_upload</span>
    <div>{{ t.upload.drop }} <a>{{ t.upload.browse }}</a></div>
    <div class="tiny muted">{{ t.upload.resumeHint }}</div>
    <input #picker type="file" multiple hidden (change)="pick($any($event.target).files); picker.value = ''" />
  </div>
  @if (files().length) {
    <div class="sel">
      <h3>{{ t.upload.selected }}</h3>
      @for (f of files(); track $index) {
        <div class="row small"><span class="ms">description</span><span class="grow ellipsis">{{ f.name }}</span><span class="muted">{{ f.size | size }}</span>
          <button class="btn ghost sm icon" (click)="remove($index)"><span class="ms">close</span></button></div>
      }
    </div>
  }
  <div class="form">
    <label class="field"><span>{{ t.upload.category }}</span>
      <select class="input" [value]="meta().category ?? ''" (change)="set('category', $any($event.target).value)">
        <option value="">{{ t.upload.categoryAuto }}</option>
        @for (c of cats; track c) { <option [value]="c">{{ t.category[c] }}</option> }
      </select></label>
    <label class="field"><span>{{ t.upload.zone }}</span><input class="input" [value]="meta().zone ?? ''" (input)="set('zone', $any($event.target).value)" placeholder="ex. Timiș" /></label>
    <label class="field"><span>{{ t.upload.station }}</span><input class="input" [value]="meta().station ?? ''" (input)="set('station', $any($event.target).value)" placeholder="ex. ST-BAN-01" /></label>
    <label class="field"><span>{{ t.upload.equipment }}</span><input class="input" [value]="meta().equipment ?? ''" (input)="set('equipment', $any($event.target).value)" placeholder="ex. T2 110/20 kV" /></label>
    <label class="field"><span>{{ t.upload.eventType }}</span><input class="input" [value]="meta().eventType ?? ''" (input)="set('eventType', $any($event.target).value)" placeholder="ex. scurtcircuit" /></label>
    <label class="field"><span>{{ t.upload.eventTime }}</span><input class="input" type="datetime-local" [value]="meta().eventTime ?? ''" (input)="set('eventTime', $any($event.target).value)" /></label>
    <label class="field wide"><span>{{ t.upload.description }}</span><textarea class="input" rows="2" [value]="meta().description ?? ''" (input)="set('description', $any($event.target).value)"></textarea></label>
  </div>
  <div class="row between">
    <span class="muted tiny">tus · chunk 8 MB · SHA-256 în flux</span>
    <div class="row">
      @if (dialog) { <button class="btn ghost" (click)="cancel()">{{ t.app.cancel }}</button> }
      <button class="btn" (click)="go()" [disabled]="!files().length"><span class="ms">rocket_launch</span>{{ t.upload.start }}</button>
    </div>
  </div>`,
  styles: [`
    :host { display: flex; flex-direction: column; gap: 14px; }
    .drop { border: 2px dashed var(--line2); border-radius: 12px; padding: 26px; text-align: center; cursor: pointer; display: flex; flex-direction: column; gap: 6px; align-items: center; transition: .15s; background: rgba(5,11,24,.4); }
    .drop:hover, .drop.over { border-color: var(--cyan); background: rgba(34,211,238,.06); box-shadow: inset 0 0 40px rgba(34,211,238,.08); }
    .sel { display: flex; flex-direction: column; gap: 4px; }
    .form { display: grid; grid-template-columns: repeat(3, minmax(0,1fr)); gap: 10px; } .wide { grid-column: 1 / -1; }
    @media (max-width: 700px) { .form { grid-template-columns: 1fr; } }
  `],
})
export class UploadForm {
  readonly t = T;
  readonly cats = CATEGORIES;
  readonly initialFiles = input<File[]>([]);
  readonly fixedCategory = input<Category | null>(null);
  readonly started = output<void>();
  private readonly uploads = inject(UploadService);
  readonly dialog = inject(MatDialogRef, { optional: true });
  private readonly data = inject<{ files?: File[]; category?: Category } | null>(MAT_DIALOG_DATA, { optional: true });

  readonly files = signal<File[]>([]);
  readonly meta = signal<UploadMeta>({});
  readonly over = signal(false);

  ngOnInit(): void {
    const f = this.data?.files ?? this.initialFiles();
    if (f?.length) this.files.set([...f]);
    const c = this.data?.category ?? this.fixedCategory();
    if (c) this.meta.update((m) => ({ ...m, category: c }));
  }

  set(k: keyof UploadMeta, v: string): void { this.meta.update((m) => ({ ...m, [k]: v })); }
  pick(list: FileList | null): void { if (list) this.files.update((f) => [...f, ...Array.from(list)]); }
  drop(e: DragEvent): void { e.preventDefault(); this.over.set(false); this.pick(e.dataTransfer?.files ?? null); }
  remove(i: number): void { this.files.update((f) => f.filter((_, j) => j !== i)); }
  cancel(): void { this.dialog?.close(false); }

  go(): void {
    this.uploads.start(this.files(), this.meta());
    this.files.set([]);
    this.started.emit();
    this.dialog?.close(true);
  }
}

@Component({
  selector: 'fg-upload-dialog',
  imports: [UploadForm],
  template: `<div class="dlg"><h2><span class="ms">cloud_upload</span> {{ t.upload.confirmTitle }}</h2><fg-upload-form /></div>`,
  styles: [`.dlg { padding: 20px; width: 760px; max-width: 92vw; color: var(--text); } h2 { margin-bottom: 14px; display: flex; gap: 8px; align-items: center; }`],
})
export class UploadDialog { readonly t = T; }
