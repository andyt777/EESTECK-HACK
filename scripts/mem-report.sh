#!/usr/bin/env bash
# Memory report: current usage of every FileGrid container against its limit,
# plus an OOM-kill check. Usage: scripts/mem-report.sh
set -euo pipefail

ids="$(docker ps -a -q --filter label=com.docker.compose.project=filegrid)"
if [ -z "$ids" ]; then
  echo "No FileGrid containers found. Start the stack first." >&2
  exit 1
fi

# Converts docker's "123.4MiB" / "1.2GiB" / "512KiB" to MiB.
to_mib() {
  awk -v v="$1" 'BEGIN {
    n = v; sub(/[A-Za-z]+$/, "", n)
    u = v; sub(/^[0-9.]+/, "", u)
    if (u == "GiB") n *= 1024; else if (u == "KiB") n /= 1024; else if (u == "B") n /= 1048576
    printf "%.0f", n
  }'
}

printf '%-16s %10s %10s %7s  %s\n' SERVICE "USED(MiB)" "LIMIT(MiB)" "USED%" STATE
total_used=0
total_limit=0
oom=""

while IFS='|' read -r id service state oom_killed limit_bytes; do
  [ -z "$id" ] && continue
  [ "$oom_killed" = "true" ] && oom="$oom $service"
  limit_mib=$(( limit_bytes / 1048576 ))
  if [ "$state" != "running" ]; then
    printf '%-16s %10s %10s %7s  %s\n' "$service" "-" "$limit_mib" "-" "$state"
    continue
  fi
  usage="$(docker stats --no-stream --format '{{.MemUsage}}' "$id" | awk '{print $1}')"
  used_mib="$(to_mib "$usage")"
  if [ "$limit_mib" -gt 0 ]; then
    pct="$(awk -v u="$used_mib" -v l="$limit_mib" 'BEGIN { printf "%.0f%%", u * 100 / l }')"
    limit_text="$limit_mib"
  else
    pct="-"
    limit_text="none"
  fi
  printf '%-16s %10s %10s %7s  %s\n' "$service" "$used_mib" "$limit_text" "$pct" "$state"
  total_used=$(( total_used + used_mib ))
  total_limit=$(( total_limit + limit_mib ))
done < <(docker inspect --format '{{.Id}}|{{index .Config.Labels "com.docker.compose.service"}}|{{.State.Status}}|{{.State.OOMKilled}}|{{.HostConfig.Memory}}' $ids | sort -t'|' -k2)

printf '%-16s %10s %10s\n' "TOTAL (running)" "$total_used" "$total_limit"

if [ -n "$oom" ]; then
  echo "OOMKilled:$oom" >&2
  exit 1
fi
echo "No container was OOMKilled."
