#!/usr/bin/env bash
# Removes every toxic and re-enables every proxy (Toxiproxy POST /reset).
#
#   bash scripts/chaos/reset.sh
set -euo pipefail
. "$(dirname "$0")/../lib/common.sh"
toxi POST /reset >/dev/null
echo "Toxiproxy reset: all toxics removed, all proxies enabled."
