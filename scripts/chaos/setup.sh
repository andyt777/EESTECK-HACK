#!/usr/bin/env bash
# Creates/updates the Toxiproxy proxies from infra/toxiproxy/toxiproxy.json (idempotent)
# and lists them. Useful when toxiproxy runs without -config (stack started before Stage 18).
#
#   bash scripts/chaos/setup.sh
set -euo pipefail
. "$(dirname "$0")/../lib/common.sh"
toxi_populate
bash "$FG_LIB_DIR/../chaos/status.sh"
