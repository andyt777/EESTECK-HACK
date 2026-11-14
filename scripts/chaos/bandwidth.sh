#!/usr/bin/env bash
# Limits bandwidth (default 256 KB/s) on a proxy: upstream (client -> server, uploads)
# and downstream (server -> client, downloads).
#
#   bash scripts/chaos/bandwidth.sh [proxy=degraded-edge] [rateKBps=256]
#   DURATION=120 bash scripts/chaos/bandwidth.sh
set -euo pipefail
. "$(dirname "$0")/../lib/common.sh"
PROXY="${1:-degraded-edge}"; RATE="${2:-256}"
toxi_populate
add_toxic "$PROXY" chaos_bandwidth_up bandwidth upstream 1.0 "{\"rate\":$RATE}" >/dev/null
add_toxic "$PROXY" chaos_bandwidth_down bandwidth downstream 1.0 "{\"rate\":$RATE}" >/dev/null
echo "bandwidth limited to ${RATE} KB/s on $PROXY (both directions)"
chaos_hold "$PROXY" chaos_bandwidth_up chaos_bandwidth_down
