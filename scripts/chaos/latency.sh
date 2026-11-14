#!/usr/bin/env bash
# Adds latency with jitter (default 800 ms +/- 300 ms) on a proxy, in both directions.
#
#   bash scripts/chaos/latency.sh [proxy=degraded-edge] [latencyMs=800] [jitterMs=300]
#   DURATION=60 bash scripts/chaos/latency.sh          # removed automatically after 60 s
set -euo pipefail
. "$(dirname "$0")/../lib/common.sh"
PROXY="${1:-degraded-edge}"; LAT="${2:-800}"; JIT="${3:-300}"
toxi_populate
add_toxic "$PROXY" chaos_latency_down latency downstream 1.0 "{\"latency\":$LAT,\"jitter\":$JIT}" >/dev/null
add_toxic "$PROXY" chaos_latency_up latency upstream 1.0 "{\"latency\":$LAT,\"jitter\":$JIT}" >/dev/null
echo "latency ${LAT}ms +/- ${JIT}ms on $PROXY (upstream + downstream)"
chaos_hold "$PROXY" chaos_latency_down chaos_latency_up
