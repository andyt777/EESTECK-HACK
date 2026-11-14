import { signal } from '@angular/core';

/** Web Speech API wrapper (ro-RO), feature-detected; degrades to "not supported". */
export class Dictation {
  readonly supported: boolean;
  readonly listening = signal(false);
  readonly interim = signal('');
  private rec: any = null;

  constructor(private onFinal: (text: string) => void, continuous = false) {
    const SR = (globalThis as any).SpeechRecognition || (globalThis as any).webkitSpeechRecognition;
    this.supported = !!SR;
    if (!SR) return;
    this.rec = new SR();
    this.rec.lang = 'ro-RO';
    this.rec.interimResults = true;
    this.rec.continuous = continuous;
    this.rec.onresult = (ev: any) => {
      let interim = '';
      for (let i = ev.resultIndex; i < ev.results.length; i++) {
        const r = ev.results[i];
        if (r.isFinal) this.onFinal(r[0].transcript.trim());
        else interim += r[0].transcript;
      }
      this.interim.set(interim);
    };
    this.rec.onend = () => { this.listening.set(false); this.interim.set(''); };
    this.rec.onerror = () => { this.listening.set(false); };
  }

  toggle(): void {
    if (!this.rec) return;
    if (this.listening()) { this.rec.stop(); return; }
    try { this.rec.start(); this.listening.set(true); } catch { /* already started */ }
  }

  stop(): void { if (this.listening()) this.rec?.stop(); }
}

export const canSpeak = typeof window !== 'undefined' && 'speechSynthesis' in window;

export function speak(text: string): void {
  if (!canSpeak) return;
  window.speechSynthesis.cancel();
  const clean = text.replace(/[#*_`>\[\]]/g, '').replace(/\(\/[^)]*\)/g, '');
  const u = new SpeechSynthesisUtterance(clean);
  u.lang = 'ro-RO';
  const ro = window.speechSynthesis.getVoices().find((v) => v.lang?.toLowerCase().startsWith('ro'));
  if (ro) u.voice = ro;
  window.speechSynthesis.speak(u);
}

export function stopSpeaking(): void { if (canSpeak) window.speechSynthesis.cancel(); }
