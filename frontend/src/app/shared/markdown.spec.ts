import { describe, expect, it } from 'vitest';
import { inline, markdown } from './markdown';

describe('markdown', () => {
  it('renders bold and inline code as spans, keeping plain text', () => {
    expect(inline('Contoare **2.000** din `meter_stats`.')).toEqual([
      { kind: 'text', text: 'Contoare ' },
      { kind: 'bold', text: '2.000' },
      { kind: 'text', text: ' din ' },
      { kind: 'code', text: 'meter_stats' },
      { kind: 'text', text: '.' },
    ]);
  });

  it('keeps inline code inside bold as code', () => {
    expect(inline('**Citiri (`METER_READING`)**')).toEqual([
      { kind: 'bold', text: 'Citiri (' },
      { kind: 'code', text: 'METER_READING' },
      { kind: 'bold', text: ')' },
    ]);
  });

  it('groups list items, turns rules and bold labels into blocks', () => {
    const blocks = markdown('**Din datele FileGrid:**\n- **Contoare:** 2.000\n- Citiri: 1.998\n\n---\n1. unu\n2. doi');
    expect(blocks.map((b) => b.kind)).toEqual(['h', 'ul', 'hr', 'ol']);
    expect((blocks[1] as { items: unknown[] }).items.length).toBe(2);
    expect((blocks[3] as { items: unknown[] }).items.length).toBe(2);
  });

  it('never produces markup from the source: tags stay text', () => {
    const [p] = markdown('<img src=x onerror=alert(1)>');
    expect(p).toEqual({ kind: 'p', spans: [{ kind: 'text', text: '<img src=x onerror=alert(1)>' }] });
  });
});
