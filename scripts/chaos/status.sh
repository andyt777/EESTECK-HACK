#!/usr/bin/env bash
# Lists the Toxiproxy proxies, whether they are enabled, and their active toxics.
#
#   bash scripts/chaos/status.sh
set -euo pipefail
. "$(dirname "$0")/../lib/common.sh"
toxi GET /proxies | node -e "let s='';process.stdin.on('data',c=>s+=c).on('end',()=>{const d=JSON.parse(s);
for (const p of Object.values(d)) {
  const t = p.toxics.map(x => x.name + '(' + x.type + ' ' + x.stream + ' tox=' + x.toxicity + ' ' + JSON.stringify(x.attributes) + ')').join(', ');
  console.log((p.enabled ? 'UP  ' : 'DOWN') + '  ' + p.name.padEnd(14) + p.listen.padEnd(16) + ' -> ' + p.upstream.padEnd(16) + ' ' + (t || 'no toxics'));
}})"
