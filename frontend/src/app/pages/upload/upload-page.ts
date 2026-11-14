import { Component } from '@angular/core';
import { T } from '../../core/texts';
import { UploadForm } from '../../features/upload-form';
import { UploadTracker } from '../../features/upload-tracker';

@Component({
  selector: 'fg-upload-page',
  imports: [UploadForm, UploadTracker],
  template: `
  <div class="page">
    <div class="page-head"><div><div class="kicker">Agent 01</div><h1>{{ t.upload.title }}</h1><div class="lead">{{ t.upload.lead }}</div></div></div>
    <div class="grid side">
      <div class="card glow"><fg-upload-form /></div>
      <div class="card"><div class="card-head"><h3>{{ t.upload.queue }}</h3></div><fg-upload-tracker /></div>
    </div>
  </div>`,
})
export class UploadPage { readonly t = T; }
