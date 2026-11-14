#!/usr/bin/env bash
# Drill 2: FTP goes down -> fault records (primary FTP) are routed to the S3 fallback,
# while the other categories are not affected (documents still on S3, meter readings
# still stored on Database with an unchanged latency). FTP is restarted on exit.
#
#   bash scripts/drills/ftp-outage.sh          (FAULTS=3 fault records)
set -euo pipefail
. "$(dirname "$0")/../lib/common.sh"
FAULTS="${FAULTS:-3}"
TMPD=$(mktemp -d)
restore() { docker compose start ftp >/dev/null 2>&1 || true; rm -rf "$TMPD"; }
trap restore EXIT

T0=$(now_iso)
BASE=$(store_p95 METER_READING "$(date -u -d '-3 min' +%Y-%m-%dT%H:%M:%SZ)" "$T0")
step "1) Baseline meter p95 (last 3 min): ${BASE%%|*}s over ${BASE##*|} readings"

step "2) Stopping ftp"
docker compose stop ftp >/dev/null
FTP_UP=1
for _ in $(seq 1 30); do
  FTP_UP=$(prom 'min(filegrid_storage_up{storage="FTP"})')
  [ "$FTP_UP" = "0" ] && break
  sleep 2
done
check "Prometheus sees FTP down (filegrid_storage_up{storage=\"FTP\"} = ${FTP_UP:-?})" test "$FTP_UP" = "0"

step "3) Uploading $FAULTS fault records and 1 connection document"
IDS=()
for i in $(seq 1 "$FAULTS"); do
  f="$TMPD/fault-drill-$i.csv"
  { echo "time,phase,current_a"; for k in $(seq 1 2000); do echo "$k,L$((k % 3 + 1)),$((RANDOM % 900))"; done; } > "$f"
  IDS+=("$(upload_file "$f" FAULT_RECORD -F station=ST-DRILL-01 -F zone=Drill -F eventType=SHORT_CIRCUIT)")
done
printf '%%PDF-1.4\n%% ftp drill %s\n%%%%EOF\n' "$(date +%s%N)" > "$TMPD/doc.pdf"
DOC=$(upload_file "$TMPD/doc.pdf" CONNECTION_DOCUMENT)

ON_S3=0
for id in "${IDS[@]}"; do
  st=$(wait_status "$id" '^(STORED_VERIFIED|PENDING|TRANSFER_FAILED)' 45 || true)
  log "   fault $id -> $st"
  [ "$st" = "STORED_VERIFIED|S3" ] && ON_S3=$((ON_S3 + 1))
done
check "all $FAULTS fault records stored and verified on the S3 fallback ($ON_S3/$FAULTS)" test "$ON_S3" -eq "$FAULTS"
FALLBACK_EVENTS=0
for id in "${IDS[@]}"; do
  n=$(api_curl GET "/files/$id/decision" | json "d.fallbackUsed ? 1 : 0")
  FALLBACK_EVENTS=$((FALLBACK_EVENTS + ${n:-0}))
done
log "   decisions with fallbackUsed=true: $FALLBACK_EVENTS/$FAULTS (Agent 01 chose S3 because FTP was down)"

DOC_ST=$(wait_status "$DOC" '^(STORED_VERIFIED|PENDING)' 30 || true)
check "connection document unaffected (stored on its primary S3: $DOC_ST)" test "$DOC_ST" = "STORED_VERIFIED|S3"

sleep 65   # at least one simulator batch (every 60 s) during the outage
DURING=$(store_p95 METER_READING "$T0")
log "   meter p95 during the outage: ${DURING%%|*}s over ${DURING##*|} readings (baseline ${BASE%%|*}s)"
check "meter readings keep flowing during the FTP outage" test "${DURING##*|}" -gt 0
check "meter p95 not degraded (<= 1.5 x baseline + 1 s)" \
  node -e "const b=+process.argv[1], d=+process.argv[2]; process.exit(b<0||d<=b*1.5+1?0:1)" "${BASE%%|*}" "${DURING%%|*}"

step "4) Starting ftp again"
docker compose start ftp >/dev/null
for _ in $(seq 1 30); do
  FTP_UP=$(prom 'min(filegrid_storage_up{storage="FTP"})')
  [ "$FTP_UP" = "1" ] && break
  sleep 2
done
check "FTP back up in Prometheus" test "$FTP_UP" = "1"
verdict "FTP outage: faults went to the S3 fallback, other categories unaffected"
