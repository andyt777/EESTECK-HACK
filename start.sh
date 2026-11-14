#!/usr/bin/env bash
# FileGrid PPC — one-command start (Linux, macOS, Git Bash, WSL).
#   init-env -> gen-certs -> docker compose up -d --build -> wait healthy -> URLs
#
#   ./start.sh          full mode: one worker per category (bulkhead demo)
#   ./start.sh --lite   lite mode: a single worker-all (machines with ~6 GB for Docker)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

mode="full"
for arg in "$@"; do
  case "$arg" in
    --lite) mode="lite"; export COMPOSE_PROFILES=lite ;;
    *) echo "Unknown option: $arg (supported: --lite)" >&2; exit 1 ;;
  esac
done

command -v docker >/dev/null 2>&1 || { echo "Docker is not installed or not on PATH." >&2; exit 1; }
docker info >/dev/null 2>&1 || { echo "Docker is not running. Start Docker Desktop first." >&2; exit 1; }

echo "==> 1/4 Environment"
bash scripts/init-env.sh

echo "==> 2/4 TLS certificate"
bash scripts/gen-certs.sh

echo "==> 3/4 Starting services ($mode mode)"
docker compose up -d --build

echo "==> 4/4 Waiting for services to become healthy"
bash scripts/wait-healthy.sh 300

https_port="$(docker compose port gateway 443 | sed 's/.*://')"
suffix=""
[ "$https_port" = "443" ] || suffix=":$https_port"

cat <<EOF

FileGrid is up ($mode mode).

  Grafana      https://localhost${suffix}/grafana   (user and password: GRAFANA_ADMIN_* in the env file)
  Status       docker compose ps
  Memory       bash scripts/mem-report.sh
  Logs         docker compose logs -f <service>
  Stop         docker compose down

Storages (s3, ftp, store-db) are reachable only inside the internal Docker network.
EOF
