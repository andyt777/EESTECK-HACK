import { Component, ElementRef, effect, inject, input, output, signal, viewChild } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Api, errorText, isUnavailable } from '../core/api.service';
import { ChatContext, ChatResponse } from '../core/models';
import { T } from '../core/texts';
import { Dictation, canSpeak, speak, stopSpeaking } from '../core/voice';
import { OriginBadge } from '../shared/ui';

interface Msg { role: 'user' | 'assistant'; content: string; res?: ChatResponse; error?: string; unavailable?: boolean; open?: boolean; }

@Component({
  selector: 'fg-assistant-chat',
  imports: [RouterLink, OriginBadge],
  template: `
  <div class="chat" [class.compact]="compact()">
    @if (context(); as c) {
      <div class="ctx"><span class="ms">link</span>{{ t.assistant.context }}:
        @if (c.fileId) { <span class="mono">fișier {{ c.fileId.slice(0, 8) }}</span> }
        @if (c.incidentId) { <span class="mono">incident {{ c.incidentId.slice(0, 8) }}</span> }
        @if (c.findingId) { <span class="mono">constatare {{ c.findingId.slice(0, 8) }}</span> }
        <button class="btn ghost sm" (click)="clearContext.emit()">{{ t.assistant.clearContext }}</button>
      </div>
    }
    <div class="msgs" #scroller>
      @if (!msgs().length) {
        <div class="hello">
          <span class="ms big">smart_toy</span>
          <p class="muted">{{ t.assistant.lead }}</p>
          <div class="ex">
            @for (q of t.assistant.examples; track q) { <button class="btn ghost sm" (click)="send(q)">{{ q }}</button> }
          </div>
        </div>
      }
      @for (m of msgs(); track $index) {
        <div class="msg" [class.me]="m.role === 'user'">
          @if (m.role === 'user') {
            <div class="bubble me">{{ m.content }}</div>
          } @else if (m.unavailable) {
            <div class="bubble warn"><span class="ms">power_off</span> {{ t.assistant.unavailable }}</div>
          } @else if (m.error) {
            <div class="bubble bad"><span class="ms">error</span> {{ m.error }}</div>
          } @else {
            <div class="bubble">
              <div class="meta row wrap">
                <span class="badge tone-active"><span class="ms">memory</span>{{ t.assistant.provider }}: {{ m.res?.provider || '—' }}</span>
                @if (m.res?.provider === 'SIMULATED') { <span class="badge sim"><span class="ms">science</span>{{ t.app.simulated }}</span> }
                @if (m.res?.provider !== 'SIMULATED') { <fg-origin [origin]="m.res?.dataOrigin" /> }
                @if (canSpeak) { <button class="btn ghost sm icon" (click)="say(m.content)" [title]="t.assistant.speak"><span class="ms">volume_up</span></button> }
              </div>
              <div class="answer">{{ m.content }}</div>
              @if (m.res?.citations?.length) {
                <div class="cites"><span class="muted tiny">{{ t.assistant.citations }}:</span>
                  @for (c of m.res!.citations; track $index) {
                    @if (c.href.startsWith('/')) { <a class="chip" [routerLink]="path(c.href)" [queryParams]="query(c.href)"><span class="ms">link</span>{{ c.label }}</a> }
                    @else { <a class="chip" [href]="c.href" target="_blank" rel="noopener"><span class="ms">open_in_new</span>{{ c.label }}</a> }
                  }
                </div>
              }
              @if (m.res?.steps?.length) {
                <button class="steps-toggle" (click)="toggle($index)"><span class="ms">{{ m.open ? 'expand_less' : 'expand_more' }}</span>{{ t.assistant.steps }} ({{ m.res!.steps.length }})</button>
                @if (m.open) {
                  <ol class="steps">
                    @for (s of m.res!.steps; track $index) {
                      <li><b class="mono">{{ s.tool }}</b> <span class="mono muted tiny">{{ json(s.input) }}</span><div class="small">{{ s.outputSummary }}</div></li>
                    }
                  </ol>
                }
              }
            </div>
          }
        </div>
      }
      @if (busy()) { <div class="msg"><div class="bubble"><span class="spinner sm"></span> {{ t.assistant.thinking }}</div></div> }
    </div>
    <div class="composer">
      <textarea class="input" rows="2" [placeholder]="t.assistant.placeholder" [value]="draft() + (dict.interim() ? ' ' + dict.interim() : '')"
        (input)="draft.set($any($event.target).value)" (keydown.enter)="enter($event)"></textarea>
      <div class="col">
        <button class="btn icon" [class.danger]="dict.listening()" (click)="mic()" [disabled]="!dict.supported" [title]="dict.supported ? (dict.listening() ? t.assistant.micStop : t.assistant.mic) : t.assistant.noVoice">
          <span class="ms" [class.pulse]="dict.listening()">{{ dict.listening() ? 'stop_circle' : 'mic' }}</span></button>
        <button class="btn icon" (click)="send(draft())" [disabled]="busy() || !draft().trim()" [title]="t.assistant.send"><span class="ms">send</span></button>
      </div>
    </div>
    <div class="foot row between tiny muted">
      <span>{{ dict.supported ? '' : t.assistant.noVoice }}</span>
      @if (msgs().length) { <button class="btn ghost sm" (click)="reset()">{{ t.assistant.clear }}</button> }
    </div>
  </div>`,
  styles: [`
    :host { display: flex; flex-direction: column; min-height: 0; flex: 1; }
    .chat { display: flex; flex-direction: column; flex: 1; min-height: 0; height: 100%; }
    .ctx { display: flex; gap: 8px; align-items: center; padding: 8px 12px; background: rgba(255,119,188,.07); border-bottom: 1px solid var(--line); font-size: 12px; flex-wrap: wrap; }
    .msgs { flex: 1; overflow-y: auto; padding: 14px; display: flex; flex-direction: column; gap: 12px; min-height: 240px; }
    .hello { text-align: center; padding: 20px 10px; display: flex; flex-direction: column; align-items: center; gap: 8px; }
    .ex { display: flex; flex-wrap: wrap; gap: 6px; justify-content: center; } .ex .btn { height: auto; padding: 6px 10px; white-space: normal; text-align: left; }
    .msg { display: flex; } .msg.me { justify-content: flex-end; }
    .bubble { background: var(--panel2); border: 1px solid var(--line); border-radius: 12px; padding: 10px 12px; max-width: 92%; }
    .bubble.me { background: rgba(255,119,188,.14); border-color: rgba(255,119,188,.35); white-space: pre-wrap; }
    .bubble.warn { border-color: rgba(251,191,36,.5); color: #fef3c7; } .bubble.bad { border-color: rgba(244,63,94,.5); color: #ffe4e6; }
    .meta { margin-bottom: 6px; gap: 6px; }
    .answer { white-space: pre-wrap; line-height: 1.55; }
    .cites { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; margin-top: 10px; }
    .steps-toggle { background: none; border: 0; color: var(--cyan); cursor: pointer; font: inherit; font-size: 12px; margin-top: 8px; padding: 0; display: flex; align-items: center; gap: 4px; }
    .steps { margin: 6px 0 0; padding-left: 18px; display: flex; flex-direction: column; gap: 6px; font-size: 12px; }
    .composer { display: flex; gap: 8px; padding: 10px 12px; border-top: 1px solid var(--line); } .composer textarea { min-height: 70px; }
    .foot { padding: 0 12px 8px; min-height: 26px; }
    .spinner.sm { width: 14px; height: 14px; border-width: 2px; display: inline-block; vertical-align: middle; }
  `],
})
export class AssistantChat {
  readonly t = T;
  readonly canSpeak = canSpeak;
  readonly context = input<ChatContext | null>(null);
  readonly seed = input<string | null>(null);
  readonly compact = input(false);
  readonly clearContext = output<void>();
  private readonly api = inject(Api);
  private readonly scroller = viewChild<ElementRef<HTMLElement>>('scroller');

  readonly msgs = signal<Msg[]>([]);
  readonly draft = signal('');
  readonly busy = signal(false);
  readonly dict = new Dictation((text) => this.draft.set((this.draft() + ' ' + text).trim()));
  private lastSeed: string | null = null;
  private autoSpeak = false;

  constructor() {
    effect(() => {
      const s = this.seed();
      if (s && s !== this.lastSeed) { this.lastSeed = s; queueMicrotask(() => this.send(s)); }
    });
  }

  enter(e: Event): void {
    const k = e as KeyboardEvent;
    if (!k.shiftKey) { k.preventDefault(); this.send(this.draft()); }
  }

  mic(): void {
    this.autoSpeak = true;
    this.dict.toggle();
  }

  send(text: string): void {
    const message = text.trim();
    if (!message || this.busy()) return;
    this.dict.stop();
    const history = this.msgs().filter((m) => !m.error && !m.unavailable).map((m) => ({ role: m.role, content: m.content }));
    this.msgs.update((l) => [...l, { role: 'user', content: message }]);
    this.draft.set('');
    this.busy.set(true);
    this.scroll();
    const speakIt = this.autoSpeak;
    this.autoSpeak = false;
    this.api.chat(message, history, this.context() ?? {}).subscribe({
      next: (res) => {
        this.msgs.update((l) => [...l, { role: 'assistant', content: res.answer ?? '', res }]);
        this.busy.set(false);
        this.scroll();
        if (speakIt && res.answer) speak(res.answer);
      },
      error: (e) => {
        this.msgs.update((l) => [...l, { role: 'assistant', content: '', error: errorText(e), unavailable: isUnavailable(e) }]);
        this.busy.set(false);
        this.scroll();
      },
    });
  }

  toggle(i: number): void { this.msgs.update((l) => l.map((m, j) => (j === i ? { ...m, open: !m.open } : m))); }
  say(text: string): void { speak(text); }
  reset(): void { stopSpeaking(); this.msgs.set([]); }
  json(v: any): string { try { return typeof v === 'string' ? v : JSON.stringify(v); } catch { return ''; } }
  path(href: string): string { return href.split('?')[0]; }
  query(href: string): Record<string, string> | null {
    const q = href.split('?')[1];
    return q ? Object.fromEntries(new URLSearchParams(q)) : null;
  }

  private scroll(): void {
    setTimeout(() => { const el = this.scroller()?.nativeElement; if (el) el.scrollTop = el.scrollHeight; }, 30);
  }
}
