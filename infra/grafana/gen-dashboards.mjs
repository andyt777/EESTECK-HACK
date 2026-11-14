// Generates the provisioned Grafana dashboards of Stage 8.
// Usage: node gen-dashboards.mjs <output dir>
import { writeFileSync } from 'node:fs';
import { join } from 'node:path';

const out = process.argv[2];
const PROM = { type: 'prometheus', uid: 'prometheus' };
const LOKI = { type: 'loki', uid: 'loki' };

let id = 0;
function grid(w, h, x, y) { return { w, h, x, y }; }

function ts(title, targets, gridPos, unit = 'short', opts = {}) {
  return {
    id: ++id, type: 'timeseries', title, datasource: PROM, gridPos,
    description: opts.description || '',
    fieldConfig: { defaults: { unit, custom: { lineWidth: 1, fillOpacity: 10 } }, overrides: [] },
    options: { legend: { displayMode: 'table', placement: 'bottom', calcs: ['lastNotNull', 'max'] },
      tooltip: { mode: 'multi', sort: 'desc' } },
    targets: targets.map(([expr, legendFormat], i) => ({ refId: String.fromCharCode(65 + i), datasource: PROM, expr, legendFormat })),
  };
}

function stat(title, expr, gridPos, unit = 'short', thresholds = null, opts = {}) {
  return {
    id: ++id, type: 'stat', title, datasource: PROM, gridPos, description: opts.description || '',
    fieldConfig: { defaults: { unit, mappings: opts.mappings || [], thresholds: thresholds || {
      mode: 'absolute', steps: [{ color: 'green', value: null }] } }, overrides: [] },
    options: { reduceOptions: { calcs: ['lastNotNull'], fields: '', values: false }, colorMode: 'background',
      graphMode: 'none', textMode: 'value_and_name', justifyMode: 'auto' },
    targets: [{ refId: 'A', datasource: PROM, expr, legendFormat: opts.legend || '' }],
  };
}

function table(title, expr, gridPos, unit = 's') {
  return {
    id: ++id, type: 'table', title, datasource: PROM, gridPos,
    fieldConfig: { defaults: { unit, decimals: 3 }, overrides: [] },
    options: { showHeader: true },
    targets: ['0.5', '0.95', '0.99'].map((q, i) => ({ refId: String.fromCharCode(65 + i), datasource: PROM,
      expr: expr.replaceAll('$Q', q), format: 'table', instant: true })),
    transformations: [
      { id: 'merge', options: {} },
      { id: 'organize', options: { excludeByName: { Time: true },
        renameByName: { 'Value #A': 'p50', 'Value #B': 'p95', 'Value #C': 'p99' } } },
    ],
  };
}

function logs(title, expr, gridPos) {
  return { id: ++id, type: 'logs', title, datasource: LOKI, gridPos,
    options: { showTime: true, wrapLogMessage: true, sortOrder: 'Descending', enableLogDetails: true },
    targets: [{ refId: 'A', datasource: LOKI, expr }] };
}

function row(title, y) { return { id: ++id, type: 'row', title, collapsed: false, gridPos: grid(24, 1, 0, y), panels: [] }; }

const UP_DOWN = [{ type: 'value', options: { '0': { text: 'INDISPONIBIL', color: 'red' }, '1': { text: 'UP', color: 'green' } } }];
const ZERO_GOOD = { mode: 'absolute', steps: [{ color: 'green', value: null }, { color: 'red', value: 1 }] };

function variable(name, label, query) {
  return { name, label, type: 'query', datasource: PROM, query: { query, refId: 'v' }, definition: query,
    includeAll: true, multi: true, current: { text: 'All', value: '$__all' }, refresh: 2, sort: 1 };
}

function dashboard(uid, title, panels, vars = [], tags = []) {
  id = 0;
  return { uid, title, tags: ['filegrid', ...tags], timezone: 'browser', schemaVersion: 39, version: 1,
    editable: false, refresh: '10s', time: { from: 'now-30m', to: 'now' },
    templating: { list: vars }, annotations: { list: [] }, links: [
      { title: 'FileGrid', type: 'dashboards', tags: ['filegrid'], asDropdown: true, includeVars: false, keepTime: true }],
    panels };
}

const SEL = 'category=~"$category", storage=~"$storage"';
const q = (metric, extra = '') =>
  `histogram_quantile($Q, sum by (le, category, storage) (rate(${metric}_bucket{${SEL}${extra}}[5m])))`;

// (a) Latency, throughput and errors per category x storage
id = 0;
const latency = dashboard('filegrid-latency', 'FileGrid — Latență, throughput și erori (categorie × storage)', (() => {
  const p = [];
  p.push(row('Stocare (scriere + read-back SHA-256)', 0));
  for (const [i, quant] of ['0.5', '0.95', '0.99'].entries()) {
    p.push(ts(`Stocare p${Math.round(Number(quant) * 100)}`, [[q('filegrid_store_duration_seconds', ', outcome="verified"').replaceAll('$Q', quant), '{{category}} → {{storage}}']],
      grid(8, 8, i * 8, 1), 's'));
  }
  p.push(table('Stocare: p50 / p95 / p99 pe categorie × storage (ultimele 5 min)', q('filegrid_store_duration_seconds', ', outcome="verified"'), grid(12, 8, 0, 9)));
  p.push(table('Recuperare: p50 / p95 / p99 pe categorie × storage (ultimele 5 min)', q('filegrid_retrieve_duration_seconds', ', outcome="success"'), grid(12, 8, 12, 9)));
  p.push(row('Throughput și erori', 17));
  p.push(ts('Operații / s (store + retrieve)', [[`sum by (operation, category, storage) (rate(filegrid_operations_total{${SEL}}[1m]))`, '{{operation}} {{category}} → {{storage}}']], grid(12, 8, 0, 18), 'ops'));
  p.push(ts('Octeți / s', [[`sum by (operation, category, storage) (rate(filegrid_bytes_total{${SEL}}[1m]))`, '{{operation}} {{category}} → {{storage}}']], grid(12, 8, 12, 18), 'Bps'));
  p.push(ts('Rata erorilor (încercări nereușite / total)', [[
    `sum by (category, storage) (rate(filegrid_operations_total{${SEL}, outcome=~"error|mismatch"}[5m])) / clamp_min(sum by (category, storage) (rate(filegrid_operations_total{${SEL}}[5m])), 1e-9)`,
    '{{category}} → {{storage}}']], grid(12, 8, 0, 26), 'percentunit'));
  p.push(ts('Fallback-uri / min', [[`sum by (category, storage, to) (increase(filegrid_fallback_total{category=~"$category"}[1m]))`, '{{category}}: {{storage}} → {{to}}']], grid(12, 8, 12, 26), 'short'));
  p.push(row('Recuperare', 34));
  for (const [i, quant] of ['0.5', '0.95', '0.99'].entries()) {
    p.push(ts(`Recuperare p${Math.round(Number(quant) * 100)}`, [[q('filegrid_retrieve_duration_seconds').replaceAll('$Q', quant), '{{category}} ← {{storage}}']],
      grid(8, 8, i * 8, 35), 's'));
  }
  return p;
})(), [
  variable('category', 'Categorie', 'label_values(filegrid_operations_total, category)'),
  variable('storage', 'Storage', 'label_values(filegrid_operations_total, storage)'),
], ['latency']);

// (b) Meter readings
const meters = dashboard('filegrid-meters', 'FileGrid — Citiri contoare (rată, gap-uri, duplicate)', [
  stat('Duplicate scrise (trebuie 0)', 'sum(filegrid_meter_duplicates_written_total)', grid(6, 5, 0, 0), 'short', ZERO_GOOD),
  stat('Gap-uri de secvență (24 h)', 'sum(filegrid_meter_gaps_total)', grid(6, 5, 6, 0), 'short',
    { mode: 'absolute', steps: [{ color: 'green', value: null }, { color: 'orange', value: 1 }, { color: 'red', value: 50 }] }),
  stat('Duplicate respinse', 'sum(filegrid_meter_duplicates_rejected_total)', grid(6, 5, 12, 0)),
  stat('Contoare active', 'sum(filegrid_meter_meters_active)', grid(6, 5, 18, 0)),
  ts('Citiri stocate / min (reconciliere meta-db)', [['sum(rate(filegrid_meter_readings_stored_total[5m])) * 60', 'stocate']], grid(12, 8, 0, 5), 'short'),
  ts('Simulator: trimise / confirmate / s', [
    ['sum(rate(filegrid_simulator_readings_sent_total[1m]))', 'trimise'],
    ['sum(rate(filegrid_simulator_readings_acked_total[1m]))', 'confirmate (ack 202)'],
    ['sum(rate(filegrid_simulator_duplicates_sent_total[1m]))', 'duplicate trimise intenționat']], grid(12, 8, 12, 5), 'ops'),
  ts('Gap-uri și duplicate în timp', [
    ['sum(filegrid_meter_gaps_total)', 'gap-uri'],
    ['sum(filegrid_meter_duplicates_rejected_total)', 'duplicate respinse'],
    ['sum(filegrid_meter_duplicates_written_total)', 'duplicate scrise']], grid(12, 8, 0, 13)),
  ts('Coada meter și latența loturilor (p95)', [
    ['max(filegrid_queue_depth{category="METER_READING"})', 'mesaje în coadă'],
    ['histogram_quantile(0.95, sum by (le) (rate(filegrid_store_duration_seconds_bucket{category="METER_READING", storage="DATABASE"}[5m])))', 'p95 lot (s)']], grid(12, 8, 12, 13)),
], [], ['meters']);

// (c) Queues & workers per category (bulkhead)
const queues = dashboard('filegrid-queues', 'FileGrid — Cozi și workeri per categorie (bulkhead)', [
  ts('Mesaje în coadă per categorie', [['max by (category) (filegrid_queue_depth)', '{{category}}']], grid(12, 8, 0, 0)),
  ts('Consumatori (workeri) per coadă', [['max by (category) (filegrid_queue_consumers)', '{{category}}']], grid(12, 8, 12, 0)),
  ts('Fișiere PENDING per categorie', [['max by (category) (filegrid_pending_files)', '{{category}}']], grid(12, 8, 0, 8)),
  ts('Stocări / s per serviciu worker', [['sum by (service) (rate(filegrid_operations_total{operation="store"}[1m]))', '{{service}}']], grid(12, 8, 12, 8), 'ops'),
  ts('p95 stocare per categorie (bulkhead: o furtună pe o categorie nu le încetinește pe celelalte)', [[
    'histogram_quantile(0.95, sum by (le, category) (rate(filegrid_store_duration_seconds_bucket{outcome="verified"}[5m])))', '{{category}}']], grid(12, 8, 0, 16), 's'),
  ts('Instanțe backend active', [['count by (service) (up{job="backend"} == 1)', '{{service}}']], grid(12, 8, 12, 16)),
  ts('Heap JVM per instanță', [['sum by (service, instance) (jvm_memory_used_bytes{job="backend", area="heap"})', '{{service}} {{instance}}']], grid(12, 8, 0, 24), 'bytes'),
  ts('CPU per instanță', [['max by (service, instance) (process_cpu_usage{job="backend"})', '{{service}} {{instance}}']], grid(12, 8, 12, 24), 'percentunit'),
], [], ['queues']);

// (d) Storage status
const storage = dashboard('filegrid-storage', 'FileGrid — Starea storage-urilor', [
  stat('FTP', 'max(filegrid_storage_up{storage="FTP"})', grid(8, 5, 0, 0), 'none', null, { mappings: UP_DOWN }),
  stat('S3', 'max(filegrid_storage_up{storage="S3"})', grid(8, 5, 8, 0), 'none', null, { mappings: UP_DOWN }),
  stat('Database', 'max(filegrid_storage_up{storage="DATABASE"})', grid(8, 5, 16, 0), 'none', null, { mappings: UP_DOWN }),
  ts('Disponibilitate în timp (1 = UP)', [['max by (storage) (filegrid_storage_up)', '{{storage}}']], grid(12, 8, 0, 5)),
  ts('Erori de scriere / min per storage', [['sum by (storage) (increase(filegrid_operations_total{operation="store", outcome=~"error|mismatch"}[1m]))', '{{storage}}']], grid(12, 8, 12, 5)),
  ts('p95 stocare per storage', [['histogram_quantile(0.95, sum by (le, storage) (rate(filegrid_store_duration_seconds_bucket{outcome="verified"}[5m])))', '{{storage}}']], grid(12, 8, 0, 13), 's'),
  ts('Fallback-uri / min (de pe storage)', [['sum by (storage, to) (increase(filegrid_fallback_total[1m]))', '{{storage}} → {{to}}']], grid(12, 8, 12, 13)),
  ts('Alerte active', [['count by (alertname) (ALERTS{alertstate="firing"})', '{{alertname}}']], grid(24, 7, 0, 21)),
], [], ['storage']);

// (e) Security & PII
const security = dashboard('filegrid-security', 'FileGrid — Securitate și date personale (PII)', [
  stat('Documente PII stocate necriptat (trebuie 0)', 'sum(filegrid_pii_stored_unencrypted_total) or vector(0)', grid(8, 5, 0, 0), 'short', ZERO_GOOD),
  stat('Neconcordanțe de integritate (24 h)', 'sum(increase(filegrid_integrity_mismatch_total[24h])) or vector(0)', grid(8, 5, 8, 0), 'short', ZERO_GOOD),
  stat('Răspunsuri 401/403 (1 h)', 'sum(increase(http_server_requests_seconds_count{job="backend", status=~"401|403"}[1h])) or vector(0)', grid(8, 5, 16, 0), 'short',
    { mode: 'absolute', steps: [{ color: 'green', value: null }, { color: 'orange', value: 10 }, { color: 'red', value: 100 }] }),
  ts('Documente de racordare stocate (criptate aplicativ) / min', [['sum by (storage) (increase(filegrid_operations_total{operation="store", category="CONNECTION_DOCUMENT", outcome="verified"}[1m]))', '{{storage}}']], grid(12, 8, 0, 5)),
  ts('Refuzuri de acces (401/403) / min', [['sum by (uri, status) (increase(http_server_requests_seconds_count{job="backend", status=~"401|403"}[1m]))', '{{status}} {{uri}}']], grid(12, 8, 12, 5)),
  ts('Alerte de securitate active', [['count by (alertname) (ALERTS{alertstate="firing", alertname=~"PersonalDataAtRisk|IntegrityMismatch"})', '{{alertname}}']], grid(12, 7, 0, 13)),
  ts('Neconcordanțe de integritate în timp', [['sum(increase(filegrid_integrity_mismatch_total[5m]))', 'neconcordanțe / 5 min']], grid(12, 7, 12, 13)),
  logs('Jurnal: integritate, criptare, acces refuzat (Loki)', '{job="docker", service=~"api|worker-.*"} |~ "(?i)integrity|mismatch|encrypt|ciphertext|forbidden|unauthori"', grid(24, 10, 0, 20)),
], [], ['security']);

for (const [file, dash] of [['latency.json', latency], ['meters.json', meters], ['queues.json', queues],
  ['storage.json', storage], ['security.json', security]]) {
  writeFileSync(join(out, file), JSON.stringify(dash, null, 2) + '\n');
}
console.log('written to', out);
