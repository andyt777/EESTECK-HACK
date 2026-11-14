#!/usr/bin/env bash
# Applies configuration changes made under infra/ while the stack is running,
# without depending on file-change notifications (which do not cross Windows
# bind mounts):
#   - Prometheus: hot reload through its lifecycle endpoint (internal network only);
#   - gateway (Traefik): restart, the only way to be sure the routes were re-read.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
# Keep Git Bash from rewriting the in-container paths below.
export MSYS_NO_PATHCONV=1

echo "==> Prometheus: reloading configuration and rules"
if docker compose exec -T prometheus wget -q -O /dev/null --post-data='' http://127.0.0.1:9090/-/reload; then
  echo "    reloaded"
else
  echo "    reload FAILED: check the syntax with: docker compose logs prometheus" >&2
  exit 1
fi

echo "==> Gateway: restarting to re-read routes and TLS configuration"
docker compose restart gateway
bash scripts/wait-healthy.sh 120
