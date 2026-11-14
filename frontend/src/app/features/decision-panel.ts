import { Component, computed, input } from '@angular/core';
import { DecisionView } from '../core/models';
import { T } from '../core/texts';
import { CategoryPipe, DatePipe, StatusBadge, StorageChip } from '../shared/ui';

/** Agent 01 decision: category, features, chosen storage, candidates with scores/reasons, fallback, result. */
@Component({
  selector: 'fg-decision',
  imports: [StorageChip, StatusBadge, CategoryPipe, DatePipe],
  template: `
  @if (d(); as d) {
    <div class="dec">
      @if (explanation()) { <blockquote class="expl"><span class="ms">format_quote</span>{{ explanation() }}</blockquote> }
      <div class="facts">
        <div><span class="lbl">{{ t.decision.category }}</span><b>{{ (d.category || d.features?.category) | cat }}</b>
          @if (detectionText()) { <span class="tiny muted">{{ detectionText() }}</span> }</div>
        <div><span class="lbl">{{ t.decision.selected }}</span><fg-storage [storage]="d.selectedStorage" /></div>
        <div><span class="lbl">{{ t.decision.fallback }}</span>
          @if (d.fallbackUsed) { <span class="badge tone-warn"><span class="ms">fork_right</span>{{ t.decision.fallbackUsed }}</span> <span class="small">{{ d.fallbackReason }}</span> }
          @else { <span class="muted small">{{ t.decision.fallbackNone }}</span> }</div>
        @if (d.fileStatus) { <div><span class="lbl">{{ t.decision.result }}</span><fg-status [status]="d.fileStatus" /></div> }
        <div><span class="lbl">{{ t.decision.policyVersion }}</span><span class="mono">{{ d.policyVersion }}</span></div>
        <div><span class="lbl">{{ t.decision.decidedAt }}</span><span class="small">{{ d.decidedAt | dt: true }}</span></div>
      </div>
      @if (features().length) {
        <h3 class="mt">{{ t.decision.features }}</h3>
        <div class="feat">@for (f of features(); track f[0]) { <span class="chip"><span class="muted">{{ f[0] }}:</span>&nbsp;{{ f[1] }}</span> }</div>
      }
      <h3 class="mt">{{ t.decision.candidates }}</h3>
      <div class="cands">
        @for (c of cands(); track c.storage) {
          <div class="cand" [class.chosen]="c.selected" [class.out]="!c.selected">
            <div class="row between">
              <fg-storage [storage]="c.storage" />
              <span class="badge" [class.tone-ok]="c.selected" [class.tone-muted]="!c.selected">{{ c.selected ? t.decision.chosen : t.decision.rejected }}</span>
            </div>
            <div class="row small muted wrap">
              <span>{{ t.decision.role }}: {{ t.decision.roles[c.role] || c.role }}</span>
              <span>· {{ t.decision.eligible }}: {{ c.eligible ? t.app.yes : t.app.no }}</span>
              <span>· {{ t.decision.available }}: {{ c.available ? t.app.yes : t.app.no }}</span>
            </div>
            <div class="score"><div class="bar"><i [style.width.%]="c.score * 100"></i></div><b class="mono">{{ (c.score * 100).toFixed(0) }}</b></div>
            @if (!compact()) {
              <div class="factors">
                @for (f of factorList(c); track f.k) {
                  <div class="tiny row"><span class="grow muted">{{ t.decision.factorNames[f.k] || f.k }} <span class="mono">×{{ f.w }}</span></span><span class="mono">{{ f.v.toFixed(2) }}</span></div>
                }
              </div>
            }
            <ul class="reasons">
              @for (r of c.reasons; track $index) {
                <li class="k-{{ r.kind }}"><span class="ms">{{ r.kind === 'POSITIVE' ? 'check_circle' : r.kind === 'NEGATIVE' ? 'cancel' : 'info' }}</span>{{ r.text }}</li>
              }
            </ul>
          </div>
        }
      </div>
    </div>
  } @else {
    <div class="muted small">{{ t.decision.none }}</div>
  }`,
  styles: [`
    .expl { margin: 0 0 12px; padding: 10px 12px; border-left: 3px solid var(--cyan); background: rgba(34,211,238,.06); border-radius: 0 8px 8px 0; font-size: 13.5px; display: flex; gap: 8px; }
    .expl .ms { color: var(--cyan); }
    .facts { display: grid; grid-template-columns: repeat(auto-fill, minmax(190px, 1fr)); gap: 10px; }
    .facts > div { display: flex; flex-direction: column; gap: 3px; align-items: flex-start; }
    .lbl { font-size: 10.5px; text-transform: uppercase; letter-spacing: .1em; color: var(--muted); }
    h3 { margin-bottom: 8px; }
    .feat { display: flex; flex-wrap: wrap; gap: 6px; }
    .cands { display: grid; grid-template-columns: repeat(auto-fill, minmax(250px, 1fr)); gap: 10px; }
    .cand { border: 1px solid var(--line); border-radius: 10px; padding: 10px; display: flex; flex-direction: column; gap: 6px; background: rgba(5,11,24,.4); }
    .cand.chosen { border-color: rgba(52,211,153,.6); box-shadow: 0 0 20px rgba(52,211,153,.08); }
    .cand.out { opacity: .9; }
    .score { display: flex; gap: 8px; align-items: center; } .bar { flex: 1; height: 6px; background: rgba(255,255,255,.06); border-radius: 4px; overflow: hidden; }
    .bar i { display: block; height: 100%; background: linear-gradient(90deg, #0ea5c6, #22d3ee); }
    .chosen .bar i { background: linear-gradient(90deg, #059669, #34d399); }
    .reasons { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 3px; font-size: 12px; }
    .reasons li { display: flex; gap: 5px; align-items: flex-start; } .reasons .ms { font-size: 14px; margin-top: 1px; }
    .k-POSITIVE .ms { color: var(--green); } .k-NEGATIVE .ms { color: var(--red); } .k-INFO .ms { color: var(--blue); }
    .factors { display: flex; flex-direction: column; gap: 2px; border-top: 1px dashed var(--line); padding-top: 5px; }
  `],
})
export class DecisionPanel {
  readonly t = T;
  readonly d = input<DecisionView | null>(null);
  readonly compact = input(false);
  readonly explanation = computed(() => this.d()?.explanationRo ?? this.d()?.explanation ?? null);
  readonly cands = computed(() => (Array.isArray(this.d()?.candidates) ? this.d()!.candidates! : []));
  readonly detectionText = computed(() => {
    const det = this.d()?.detection;
    if (!det) return '';
    if (typeof det === 'string') return det;
    const parts: string[] = [];
    for (const k of ['method', 'source', 'reason', 'matchedBy', 'rule']) if (det[k]) parts.push(String(det[k]));
    return parts.join(' · ') || Object.entries(det).filter(([, v]) => typeof v !== 'object').map(([k, v]) => `${k}: ${v}`).slice(0, 3).join(' · ');
  });
  readonly features = computed<[string, string][]>(() => {
    const f = this.d()?.features;
    if (!f || typeof f !== 'object') return [];
    return Object.entries(f).filter(([k, v]) => v !== null && typeof v !== 'object' && k !== 'sizeBytes').map(([k, v]) => [k, String(v)]);
  });
  factorList(c: any): { k: string; v: number; w: number }[] {
    return Object.entries(c.factors ?? {}).map(([k, f]: [string, any]) => ({ k, v: Number(f?.value ?? 0), w: Number(f?.weight ?? 0) }));
  }
}
