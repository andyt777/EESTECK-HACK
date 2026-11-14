#!/usr/bin/env bash
# Unreliable connections on a proxy. Toxiproxy is a TCP proxy, so real IP packet loss
# cannot be produced (TCP would retransmit anyway); these modes reproduce the effects
# an application sees on a lossy link:
#   reset_peer - a fraction (toxicity) of connections is reset (RST) after RESET_MS (default 500 ms)
#   timeout    - a fraction of connections stops passing data, closed after TIMEOUT_MS (default 5000 ms)
#   loss       - approximation of packet loss: 10% of connections stall (timeout) + latency
#                200 +/- 400 ms on all of them (retransmission-like stalls)
#
#   bash scripts/chaos/network-faults.sh <reset_peer|timeout|loss> [proxy=degraded-edge] [toxicity=0.3]
#   DURATION=60 bash scripts/chaos/network-faults.sh reset_peer
set -euo pipefail
. "$(dirname "$0")/../lib/common.sh"
MODE="${1:?usage: network-faults.sh <reset_peer|timeout|loss> [proxy] [toxicity]}"
PROXY="${2:-degraded-edge}"; TOX="${3:-0.3}"
toxi_populate
case "$MODE" in
  reset_peer)
    add_toxic "$PROXY" chaos_reset_peer reset_peer downstream "$TOX" "{\"timeout\":${RESET_MS:-500}}" >/dev/null
    NAMES=(chaos_reset_peer) ;;
  timeout)
    add_toxic "$PROXY" chaos_timeout timeout downstream "$TOX" "{\"timeout\":${TIMEOUT_MS:-5000}}" >/dev/null
    NAMES=(chaos_timeout) ;;
  loss)
    add_toxic "$PROXY" chaos_loss_timeout timeout downstream 0.1 "{\"timeout\":${TIMEOUT_MS:-5000}}" >/dev/null
    add_toxic "$PROXY" chaos_loss_jitter latency downstream 1.0 '{"latency":200,"jitter":400}' >/dev/null
    NAMES=(chaos_loss_timeout chaos_loss_jitter) ;;
  *) echo "unknown mode '$MODE' (reset_peer|timeout|loss)" >&2; exit 2 ;;
esac
echo "$MODE on $PROXY (toxicity $TOX; toxics: ${NAMES[*]})"
chaos_hold "$PROXY" "${NAMES[@]}"
