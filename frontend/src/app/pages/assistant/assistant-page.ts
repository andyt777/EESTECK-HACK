import { Component, inject, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { ChatContext } from '../../core/models';
import { T } from '../../core/texts';
import { AssistantChat } from '../../features/assistant-chat';

@Component({
  selector: 'fg-assistant-page',
  imports: [AssistantChat],
  template: `
  <div class="page">
    <div class="page-head"><div><div class="kicker">Agentic RAG</div><h1>{{ t.assistant.title }}</h1><div class="lead">{{ t.assistant.lead }}</div></div></div>
    <div class="card flush wrap"><fg-assistant-chat [context]="ctx()" (clearContext)="ctx.set(null)" /></div>
  </div>`,
  styles: [`.wrap { height: calc(100vh - 190px); min-height: 480px; display: flex; flex-direction: column; }`],
})
export class AssistantPage {
  readonly t = T;
  readonly ctx = signal<ChatContext | null>(null);
  constructor() {
    const q = inject(ActivatedRoute).snapshot.queryParamMap;
    const c: ChatContext = {};
    for (const k of ['fileId', 'incidentId', 'findingId'] as const) { const v = q.get(k); if (v) c[k] = v; }
    if (Object.keys(c).length) this.ctx.set(c);
  }
}
