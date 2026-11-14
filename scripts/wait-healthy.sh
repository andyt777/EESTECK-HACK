#!/usr/bin/env bash
# Waits until every FileGrid container is healthy (one-shot containers must
# have exited with code 0). Exits 1 on timeout or on a failed container.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

TIMEOUT="${1:-300}"
deadline=$(( $(date +%s) + TIMEOUT ))

while true; do
  pending=""
  failed=""
  while IFS='|' read -r service state health exit_code; do
    [ -z "$service" ] && continue
    if [ "$state" = "exited" ]; then
      [ "$exit_code" = "0" ] || failed="$failed $service(exit=$exit_code)"
    elif [ "$state" = "running" ] && [ "$health" = "healthy" ]; then
      :
    elif [ "$health" = "unhealthy" ]; then
      failed="$failed $service(unhealthy)"
    else
      pending="$pending $service"
    fi
  done < <(docker compose ps -a --format '{{.Service}}|{{.State}}|{{.Health}}|{{.ExitCode}}')

  if [ -n "$failed" ]; then
    echo "FAILED:$failed" >&2
    echo "Inspect with: docker compose logs <service>" >&2
    exit 1
  fi
  if [ -z "$pending" ]; then
    echo "All services are healthy."
    exit 0
  fi
  if [ "$(date +%s)" -ge "$deadline" ]; then
    echo "TIMEOUT after ${TIMEOUT}s, still waiting for:$pending" >&2
    exit 1
  fi
  echo "waiting for:$pending"
  sleep 5
done
