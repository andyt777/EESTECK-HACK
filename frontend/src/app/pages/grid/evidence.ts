/** Builds an ECharts option from evidence JSON of whatever reasonable shape Agent 02 returns. */
export function evidenceSeries(ev: any): { name: string; data: [any, number][] }[] {
  if (!ev || typeof ev !== 'object') return [];
  const out: { name: string; data: [any, number][] }[] = [];
  const toPoints = (arr: any[]): [any, number][] => {
    const pts: [any, number][] = [];
    for (const p of arr) {
      if (Array.isArray(p) && p.length >= 2 && !isNaN(Number(p[1]))) pts.push([p[0], Number(p[1])]);
      else if (p && typeof p === 'object') {
        const x = p.t ?? p.ts ?? p.time ?? p.timestamp ?? p.at ?? p.periodStart ?? p.x ?? p.hour ?? p.date ?? p.label;
        const yk = ['value', 'v', 'y', 'kwh', 'consumptionKwh', 'consumption', 'voltage', 'voltageV', 'count', 'exportKwh', 'importKwh'].find((k) => typeof p[k] === 'number');
        if (x !== undefined && yk) pts.push([x, p[yk]]);
      } else if (typeof p === 'number') pts.push([pts.length, p]);
    }
    return pts;
  };
  const visit = (v: any, name: string, depth: number) => {
    if (depth > 3 || !v) return;
    if (Array.isArray(v)) {
      if (v.length && v.every((s) => s && typeof s === 'object' && !Array.isArray(s) && (Array.isArray(s.points) || Array.isArray(s.data) || Array.isArray(s.values)))) {
        for (const s of v) { const pts = toPoints(s.points ?? s.data ?? s.values); if (pts.length) out.push({ name: s.name ?? s.label ?? s.meterId ?? name, data: pts }); }
        return;
      }
      // Array of rows with several numeric columns (e.g. postVoltageAvg + gridMedianVoltage): one series per column.
      const rows = v.filter((r) => r && typeof r === 'object' && !Array.isArray(r));
      if (rows.length >= 2 && rows.length === v.length) {
        const xk = ['periodStart', 't', 'ts', 'time', 'timestamp', 'at', 'hour', 'date', 'label', 'seq'].find((k) => rows[0][k] !== undefined);
        const skip = new Set(['seq', 'meters', 'metersCount', 'count', xk ?? '']);
        const yks = Object.keys(rows[0]).filter((k) => !skip.has(k) && typeof rows[0][k] === 'number');
        if (xk && yks.length) {
          for (const yk of yks) out.push({ name: yk, data: rows.filter((r) => typeof r[yk] === 'number').map((r) => [r[xk], r[yk]] as [any, number]) });
          return;
        }
      }
      const pts = toPoints(v);
      if (pts.length >= 2) out.push({ name, data: pts });
      return;
    }
    if (typeof v === 'object') for (const [k, x] of Object.entries(v)) if (typeof x === 'object') visit(x, k, depth + 1);
  };
  visit(ev, 'serie', 0);
  return out.slice(0, 6);
}

export function evidenceOption(ev: any): any | null {
  const series = evidenceSeries(ev);
  if (!series.length) return null;
  const timeAxis = series.some((s) => s.data.some((p) => typeof p[0] === 'string' && !isNaN(Date.parse(p[0]))));
  const baseline = typeof ev?.baseline === 'number' ? ev.baseline : typeof ev?.median === 'number' ? ev.median : null;
  // Series of very different magnitude (e.g. kWh vs volts) go on a second axis.
  const maxOf = (d: [any, number][]) => Math.max(...d.map((p) => Math.abs(p[1])), 0);
  const big = Math.max(...series.map((s) => maxOf(s.data)));
  const secondary = new Set(series.map((s, i) => (big > 0 && maxOf(s.data) < big / 20 ? i : -1)).filter((i) => i >= 0));
  const colors = ['#22d3ee', '#fbbf24', '#a78bfa', '#34d399', '#f43f5e', '#60a5fa'];
  return {
    backgroundColor: 'transparent',
    color: colors,
    grid: { left: 48, right: 18, top: 30, bottom: 36 },
    tooltip: { trigger: 'axis', backgroundColor: '#0b1730', borderColor: '#27426f', textStyle: { color: '#dbe7ff' } },
    legend: { textStyle: { color: '#7f93b8' }, top: 0 },
    xAxis: { type: timeAxis ? 'time' : 'category', axisLine: { lineStyle: { color: '#27426f' } }, axisLabel: { color: '#7f93b8' }, splitLine: { show: false } },
    yAxis: [{ type: 'value', axisLine: { lineStyle: { color: '#27426f' } }, axisLabel: { color: '#7f93b8' }, splitLine: { lineStyle: { color: '#12244a' } } },
      { type: 'value', axisLine: { lineStyle: { color: '#27426f' } }, axisLabel: { color: '#7f93b8' }, splitLine: { show: false } }],
    series: series.map((s, i) => ({
      yAxisIndex: secondary.has(i) ? 1 : 0,
      name: s.name, type: 'line', showSymbol: s.data.length < 40, smooth: true, data: s.data, lineStyle: { width: 2 },
      areaStyle: i === 0 ? { opacity: 0.08 } : undefined,
      markLine: i === 0 && baseline !== null ? { symbol: 'none', lineStyle: { color: '#7f93b8', type: 'dashed' }, label: { color: '#7f93b8', formatter: 'referință' }, data: [{ yAxis: baseline }] } : undefined,
    })),
  };
}

/** Flat key/values of the evidence for display next to the chart. */
export function evidenceFacts(ev: any): [string, string][] {
  if (!ev || typeof ev !== 'object') return [];
  return Object.entries(ev).filter(([k, v]) => v !== null && !/ids$/i.test(k) && (typeof v !== 'object' || (Array.isArray(v) && v.every((x) => typeof x !== 'object') && v.length <= 6)))
    .map(([k, v]) => [k, Array.isArray(v) ? v.join(', ') : typeof v === 'number' ? (Number.isInteger(v) ? String(v) : v.toFixed(3)) : String(v)]);
}
