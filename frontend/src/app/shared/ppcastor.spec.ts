import { describe, expect, it } from 'vitest';
import { moodFor } from './ppcastor';

describe('moodFor', () => {
  it('thinks while a request is in flight', () => {
    expect(moodFor(null, true)).toBe('thinking');
  });

  it('waits when idle and there is no message', () => {
    expect(moodFor(null, false)).toBe('idle');
  });

  it('is worried on an error answer', () => {
    expect(moodFor({ role: 'assistant', error: 'x' }, false)).toBe('error');
  });

  it('is worried when the assistant is unavailable', () => {
    expect(moodFor({ role: 'assistant', unavailable: true }, false)).toBe('error');
  });

  it('is worried on a fallback (no-LLM) answer', () => {
    expect(moodFor({ role: 'assistant', res: { provider: 'SIMULATED' } }, false)).toBe('error');
  });

  it('is worried when the request was refused (PII or write request)', () => {
    expect(moodFor({ role: 'assistant', res: { provider: 'gemini', refused: true } }, false)).toBe('error');
  });

  it('is happy on a real LLM answer', () => {
    expect(moodFor({ role: 'assistant', res: { provider: 'GEMINI' } }, false)).toBe('happy');
  });
});
