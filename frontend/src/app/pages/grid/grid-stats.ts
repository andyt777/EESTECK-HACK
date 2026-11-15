import { T } from '../../core/texts';

export interface StatFact { label: string; value: number; }
export interface StatRow { label: string; value: number; pct: number; }
export interface StatGroup { title: string; rows: StatRow[]; }
export interface StatsView { facts: StatFact[]; groups: StatGroup[]; lastRun: string | null; }

const FACT_KEYS = ['readingsAnalysed', 'metersAnalysed', 'prosumers', 'total', 'incidentsOpened'] as const;
const STATUS_ORDER = ['NEW', 'CONFIRMED', 'DISMISSED'];

function counts(v: unknown): [string, number][] {
  if (!v || typeof v !== 'object' || Array.isArray(v)) return [];
  return Object.entries(v as Record<string, unknown>).filter((e): e is [string, number] => typeof e[1] === 'number');
}

function group(title: string, entries: [string, number][], sort = true): StatGroup | null {
  if (!entries.length) return null;
  const rows = sort ? [...entries].sort((a, b) => b[1] - a[1]) : entries;
  const max = Math.max(...rows.map((r) => r[1]), 1);
  return { title, rows: rows.map(([label, value]) => ({ label, value, pct: Math.round((value / max) * 100) })) };
}

/**
 * Turns Agent 02's raw /summary payload into what the Grid Intelligence page shows: plain Romanian titles,
 * headline numbers in reading order and breakdowns sorted largest first (raw keys never reach the UI).
 */
export function statsView(s: unknown): StatsView {
  if (!s || typeof s !== 'object') return { facts: [], groups: [], lastRun: null };
  const o = s as Record<string, unknown>;
  const L = T.grid.statsText;
  const facts = FACT_KEYS.filter((k) => typeof o[k] === 'number').map((k) => ({ label: L[k], value: o[k] as number }));
  const types = counts(o['byTypeLabel']).length
    ? counts(o['byTypeLabel'])
    : counts(o['byType']).map(([k, v]) => [T.grid.findingTypes[k] ?? k, v] as [string, number]);
  const statuses = counts(o['byStatus'])
    .sort((a, b) => (STATUS_ORDER.indexOf(a[0]) + 99) % 99 - (STATUS_ORDER.indexOf(b[0]) + 99) % 99)
    .map(([k, v]) => [L.status[k] ?? T.grid.findingStatuses[k] ?? k, v] as [string, number]);
  const groups = [group(L.byType, types), group(L.byRegion, counts(o['byRegion'])), group(L.byStatus, statuses, false)]
    .filter((g): g is StatGroup => g !== null);
  const run = o['lastRun'];
  const at = run && typeof run === 'object' ? (run as Record<string, unknown>)['at'] : run;
  return { facts, groups, lastRun: typeof at === 'string' ? at : null };
}
