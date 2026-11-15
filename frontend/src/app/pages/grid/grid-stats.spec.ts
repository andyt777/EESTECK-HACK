import { describe, expect, it } from 'vitest';
import { statsView } from './grid-stats';

const summary = {
  total: 229, incidentsOpened: 10, metersAnalysed: 2000, readingsAnalysed: 193576, prosumers: 192, dataOrigin: 'SIMULATED',
  byType: { REPEATED_VALUES: 63, ZERO_CONSUMPTION: 116 },
  byTypeLabel: { 'Valori repetate': 63, 'Consum zero': 116 },
  byRegion: { Banat: 70, Muntenia: 97 },
  byStatus: { NEW: 200, CONFIRMED: 20, DISMISSED: 9 },
  lastRun: '2026-11-15T10:00:00Z',
};

describe('statsView', () => {
  it('turns raw keys into plain Romanian headline facts, in reading order', () => {
    expect(statsView(summary).facts).toEqual([
      { label: 'Citiri verificate', value: 193576 },
      { label: 'Contoare urmărite', value: 2000 },
      { label: 'Prosumatori (produc și energie)', value: 192 },
      { label: 'Probleme găsite', value: 229 },
      { label: 'Devenite incidente', value: 10 },
    ]);
  });

  it('never shows raw keys such as dataOrigin, byTypeLabel or NEW', () => {
    const text = JSON.stringify(statsView(summary));
    for (const raw of ['dataOrigin', 'byTypeLabel', 'byRegion', 'byStatus', 'incidentsOpened', '"NEW"']) {
      expect(text).not.toContain(raw);
    }
  });

  it('groups breakdowns under understandable titles, largest first, with bar widths', () => {
    const [types, regions, statuses] = statsView(summary).groups;
    expect(types.title).toBe('Ce fel de probleme');
    expect(types.rows.map((r) => r.label)).toEqual(['Consum zero', 'Valori repetate']);
    expect(types.rows[0].pct).toBe(100);
    expect(types.rows[1].pct).toBe(Math.round((63 / 116) * 100));
    expect(regions.title).toBe('Unde apar');
    expect(statuses.title).toBe('Cât s-a verificat');
    expect(statuses.rows.map((r) => r.label)).toEqual(['De verificat', 'Confirmate', 'Respinse']);
  });

  it('falls back to type codes translated by the UI when labels are missing', () => {
    const { groups } = statsView({ byType: { ZERO_CONSUMPTION: 3 } });
    expect(groups[0].rows[0].label).toBe('Consum zero');
  });

  it('reads the last run time from the {at} object Agent 02 returns', () => {
    expect(statsView({ lastRun: { at: '2026-11-15T10:00:00Z', reason: 'scheduled' } }).lastRun).toBe('2026-11-15T10:00:00Z');
  });

  it('is empty for missing or malformed data', () => {
    expect(statsView(null)).toEqual({ facts: [], groups: [], lastRun: null });
  });
});
