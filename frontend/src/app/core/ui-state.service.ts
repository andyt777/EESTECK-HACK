import { Injectable, signal } from '@angular/core';
import { ChatContext } from './models';

function readPref(): boolean {
  try {
    const v = localStorage.getItem('fg.animations');
    if (v !== null) return v === '1';
  } catch { /* storage blocked */ }
  return !(typeof matchMedia !== 'undefined' && matchMedia('(prefers-reduced-motion: reduce)').matches);
}

/** Global UI state: animations switch, simulated-data badge, assistant side panel. */
@Injectable({ providedIn: 'root' })
export class UiState {
  readonly animations = signal(readPref());
  readonly simulatedOnPage = signal(false);
  readonly assistantOpen = signal(false);
  readonly assistantContext = signal<ChatContext | null>(null);
  readonly assistantSeed = signal<string | null>(null);
  readonly phoneOpen = signal(false);

  toggleAnimations(): void {
    const v = !this.animations();
    this.animations.set(v);
    try { localStorage.setItem('fg.animations', v ? '1' : '0'); } catch { /* ignore */ }
  }

  openAssistant(context: ChatContext | null, seed?: string): void {
    this.assistantContext.set(context);
    this.assistantSeed.set(seed ?? null);
    this.assistantOpen.set(true);
  }
}
