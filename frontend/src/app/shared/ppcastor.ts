import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

/** Expression of the assistant PPCastor; each one maps to a real request state (see moodFor). */
export type PpcastorMood = 'idle' | 'thinking' | 'happy' | 'error';

/** The parts of a chat message that decide PPCastor's expression. */
export interface PpcastorMsgLike {
  role: 'user' | 'assistant';
  error?: string;
  unavailable?: boolean;
  res?: { provider?: string | null; refused?: boolean } | null;
}

/**
 * Maps the real chat state to an expression: thinking while /chat is in flight, worried on an error,
 * when the service is unavailable or when the answer came from the no-LLM fallback, happy otherwise.
 */
export function moodFor(m: PpcastorMsgLike | null, busy: boolean): PpcastorMood {
  if (busy) return 'thinking';
  if (!m) return 'idle';
  if (m.error || m.unavailable || m.res?.provider === 'SIMULATED' || m.res?.refused) return 'error';
  return m.res ? 'happy' : 'idle';
}

/**
 * The assistant PPCastor: same beaver as the Village (hard hat, pink hi-vis vest) but a different job,
 * so it wears a headset with a microphone and, in the full-body pose, holds a tablet instead of a log.
 * Decorative only: the name and role are always rendered as text next to it.
 */
@Component({
  selector: 'fg-ppcastor-avatar',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { 'aria-hidden': 'true', '[class]': '"mood-" + mood()' },
  template: `
  <svg [attr.viewBox]="viewBox()" [attr.width]="size()" [attr.height]="full() ? size() * 1.5 : size() * 1.2" focusable="false">
    <g class="bob">
      @if (full()) {
        <!-- tail, feet, body, vest -->
        <ellipse cx="-25" cy="-9" rx="15" ry="8" transform="rotate(-22 -25 -9)" class="fur-dark"/>
        <ellipse cx="-9" cy="-2" rx="8" ry="4" class="fur-dark"/><ellipse cx="9" cy="-2" rx="8" ry="4" class="fur-dark"/>
      }
      <ellipse cy="-25" rx="19" ry="22" class="fur"/>
      <ellipse cy="-21" rx="11" ry="14" class="cream"/>
      <path d="M-4 -44 Q-17 -42 -18.5 -26 Q-18.5 -14 -13 -8 H-4 Z" class="vest"/>
      <path d="M4 -44 Q17 -42 18.5 -26 Q18.5 -14 13 -8 H4 Z" class="vest"/>
      <path d="M-18.4 -24 H-4 M4 -24 H18.4" class="vest-stripe"/>
      @if (full() && kit() === 'magnifier') {
        <!-- grid analyst: magnifying glass held up to the side -->
        <path d="M-16 -36 q-6 8 -3 14" class="arm"/><path d="M16 -34 q8 -2 12 -12" class="arm"/>
        <g class="lens"><path d="M28 -45 L29.5 -54" class="handle"/><circle cx="30" cy="-63" r="9" class="glass"/></g>
      } @else if (full()) {
        <!-- tablet held in front of the vest -->
        <g class="tablet">
          <rect x="-14" y="-35" width="28" height="20" rx="3" class="tab-body"/>
          <rect x="-11" y="-32" width="22" height="14" rx="1.5" class="tab-screen"/>
          <path d="M-8 -21 v-4 M-4 -21 v-7 M0 -21 v-5 M4 -21 v-9 M8 -21 v-6" class="tab-bars"/>
        </g>
        <path d="M-16 -36 q-6 8 -3 14" class="arm"/><path d="M16 -36 q6 8 3 14" class="arm"/>
      }
      <!-- head -->
      <circle cx="-17" cy="-66" r="4.6" class="fur-dark"/><circle cx="17" cy="-66" r="4.6" class="fur-dark"/>
      <circle cy="-58" r="19" class="fur"/>
      <ellipse cy="-51" rx="11" ry="8" class="cream"/>
      <rect x="-4" y="-49" width="8" height="7.5" rx="1.6" class="teeth"/><path d="M0 -49 V-41.5" class="line"/>
      <ellipse cy="-55.5" rx="4.2" ry="3" class="nose"/>
      @switch (mood()) {
        @case ('happy') { <path d="M-12 -63 q3.5 -5 7 0 M5 -63 q3.5 -5 7 0" class="eye-l"/> }
        @case ('thinking') { <circle cx="-6.5" cy="-66" r="2.5" class="eye"/><circle cx="10.5" cy="-66" r="2.5" class="eye"/> }
        @case ('error') {
          <circle cx="-8.5" cy="-63.5" r="3.6" class="eye-w"/><circle cx="8.5" cy="-63.5" r="3.6" class="eye-w"/>
          <circle cx="-8.5" cy="-63" r="1.6" class="eye"/><circle cx="8.5" cy="-63" r="1.6" class="eye"/>
          <path d="M-13 -70 l7 2.2 M13 -70 l-7 2.2" class="brow"/>
        }
        @default { <circle cx="-8.5" cy="-64" r="2.6" class="eye blink"/><circle cx="8.5" cy="-64" r="2.6" class="eye blink"/> }
      }
      <!-- hard hat -->
      <path d="M-19 -69 Q-19 -90 0 -90 Q19 -90 19 -69 Z" class="hat"/>
      <rect x="-3.2" y="-90" width="6.4" height="21" class="hat-stripe"/>
      <rect x="-23" y="-71" width="46" height="5" rx="2.5" class="hat"/>
      @if (kit() === 'headset') {
        <!-- headset: band over the hat, ear cups, microphone boom: the assistant's mark -->
        <path d="M-21 -63 Q-25 -95 0 -95 Q25 -95 21 -63" class="band"/>
        <rect x="-25" y="-66" width="7" height="11" rx="3" class="cup"/><rect x="18" y="-66" width="7" height="11" rx="3" class="cup"/>
        <path d="M-21 -56 Q-20 -45 -9 -45" class="boom"/><circle cx="-8" cy="-45" r="2.4" class="mic"/>
      }
    </g>
  </svg>`,
  styles: [`
    :host { display: inline-block; line-height: 0; vertical-align: middle; flex: none;
      --ink: var(--text, #12305e); --fur: #9b6238; --fur-dark: #6f4424; --cream: #f1d9b5; }
    svg { overflow: hidden; display: block; }
    .fur { fill: var(--fur); stroke: var(--ink); stroke-width: 1.3; }
    .fur-dark { fill: var(--fur-dark); stroke: var(--ink); stroke-width: 1.3; }
    .cream { fill: var(--cream); }
    .vest { fill: var(--pink, #ff77bc); stroke: var(--ink); stroke-width: 1.2; stroke-linejoin: round; }
    .vest-stripe { fill: none; stroke: #ffffff; stroke-width: 3; }
    .arm { fill: none; stroke: var(--fur-dark); stroke-width: 6; stroke-linecap: round; }
    .teeth { fill: #ffffff; stroke: var(--ink); stroke-width: 1; }
    .line { stroke: var(--ink); stroke-width: .9; }
    .nose, .eye { fill: var(--ink); }
    .eye-w { fill: #ffffff; stroke: var(--ink); stroke-width: 1; }
    .eye-l, .brow { fill: none; stroke: var(--ink); stroke-width: 2.2; stroke-linecap: round; }
    .hat { fill: #ffffff; stroke: var(--ink); stroke-width: 1.4; }
    .hat-stripe { fill: var(--pink, #ff77bc); }
    .band { fill: none; stroke: var(--ink); stroke-width: 2.4; stroke-linecap: round; }
    .cup { fill: var(--pink-ink, #c2327e); stroke: var(--ink); stroke-width: 1.2; }
    .boom { fill: none; stroke: var(--ink); stroke-width: 1.8; stroke-linecap: round; }
    .mic { fill: var(--ink); }
    .tab-body { fill: #ffffff; stroke: var(--ink); stroke-width: 1.3; }
    .tab-screen { fill: var(--sky, #bfe2ff); }
    .tab-bars { stroke: var(--pink-ink, #c2327e); stroke-width: 2; stroke-linecap: round; }
    .glass { fill: rgba(191, 226, 255, .55); stroke: var(--ink); stroke-width: 2.4; }
    .handle { stroke: var(--fur-dark); stroke-width: 4.5; stroke-linecap: round; }
    :host(.mood-thinking) .lens { animation: scan 1.4s ease-in-out infinite; transform-box: fill-box; transform-origin: center; }
    @keyframes scan { 50% { transform: translate(-3px, 2px) rotate(-8deg); } }
    :host(.mood-error) .hat-stripe { fill: var(--amber, #a15c07); }
    :host(.mood-happy) .hat-stripe { fill: var(--green, #0f7a56); }
    .eye.blink { animation: blink 4s infinite; transform-box: fill-box; transform-origin: center; }
    :host(.mood-thinking) .bob { animation: bob 1.1s ease-in-out infinite; }
    @keyframes blink { 0%, 94%, 100% { transform: scaleY(1); } 96% { transform: scaleY(.1); } }
    @keyframes bob { 0%, 100% { transform: translateY(0) rotate(0); } 50% { transform: translateY(-2px) rotate(-3deg); } }
    @media (prefers-reduced-motion: reduce) { .eye.blink, :host(.mood-thinking) .bob, :host(.mood-thinking) .lens { animation: none; } }
  `],
})
export class PpcastorAvatar {
  readonly mood = input<PpcastorMood>('idle');
  /** Rendered width in px (height follows the pose). */
  readonly size = input<number>(32);
  /** Full body holding a tablet (empty state); otherwise head and shoulders with the headset. */
  readonly full = input<boolean>(false);
  /** What PPCastor carries: the assistant's headset + tablet, or the grid analyst's magnifying glass. */
  readonly kit = input<'headset' | 'magnifier'>('headset');
  /** Head-and-shoulders crops just below the vest neckline so nothing paints outside the box. */
  readonly viewBox = computed(() => (this.full() ? '-42 -100 84 126' : '-30 -100 60 72'));
}
