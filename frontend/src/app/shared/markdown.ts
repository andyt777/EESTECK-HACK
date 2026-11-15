/**
 * The small Markdown subset LLM answers use (bold, inline code, bullet/numbered lists, headings,
 * horizontal rules), parsed into plain data so the template renders it with text bindings only:
 * no innerHTML, so nothing in an answer can inject markup.
 */
export interface MdSpan { kind: 'text' | 'bold' | 'code'; text: string; }
export type MdBlock =
  | { kind: 'p'; spans: MdSpan[] }
  | { kind: 'h'; spans: MdSpan[] }
  | { kind: 'ul' | 'ol'; items: MdSpan[][] }
  | { kind: 'hr' };

export function inline(text: string): MdSpan[] {
  const spans: MdSpan[] = [];
  const re = /\*\*(.+?)\*\*|`([^`]+)`/g;
  let last = 0;
  let m: RegExpExecArray | null;
  while ((m = re.exec(text))) {
    if (m.index > last) spans.push({ kind: 'text', text: text.slice(last, m.index) });
    if (m[1] !== undefined) {
      // Code inside bold ("**Citiri (`METER_READING`)**") keeps its code look; the rest is bold.
      for (const s of inline(m[1])) spans.push(s.kind === 'text' ? { kind: 'bold', text: s.text } : s);
    } else {
      spans.push({ kind: 'code', text: m[2] });
    }
    last = re.lastIndex;
  }
  if (last < text.length) spans.push({ kind: 'text', text: text.slice(last) });
  return spans;
}

export function markdown(source: string): MdBlock[] {
  const blocks: MdBlock[] = [];
  let para: string[] = [];
  const flush = () => {
    if (para.length) blocks.push({ kind: 'p', spans: inline(para.join('\n')) });
    para = [];
  };
  for (const raw of source.replace(/\r\n?/g, '\n').split('\n')) {
    const line = raw.trimEnd();
    const bullet = /^\s*[-*•]\s+(.*)$/.exec(line);
    const numbered = /^\s*\d+[.)]\s+(.*)$/.exec(line);
    const heading = /^#{1,6}\s+(.*)$/.exec(line);
    if (!line.trim()) { flush(); continue; }
    if (/^\s*(-{3,}|\*{3,}|_{3,})\s*$/.test(line)) { flush(); blocks.push({ kind: 'hr' }); continue; }
    if (heading) { flush(); blocks.push({ kind: 'h', spans: inline(heading[1]) }); continue; }
    if (bullet || numbered) {
      flush();
      const kind = bullet ? 'ul' : 'ol';
      const item = inline((bullet ?? numbered)![1]);
      const prev = blocks[blocks.length - 1];
      if (prev && prev.kind === kind) prev.items.push(item);
      else blocks.push({ kind, items: [item] });
      continue;
    }
    // A whole-line bold label ("**Surse:**") reads as a small heading.
    const label = /^\*\*([^*]+)\*\*:?$/.exec(line.trim());
    if (label) { flush(); blocks.push({ kind: 'h', spans: [{ kind: 'text', text: label[1] }] }); continue; }
    para.push(line);
  }
  flush();
  return blocks;
}
