#!/usr/bin/env bash
# Drill 1: S3 goes down -> a connection document (primary S3) falls back to
# Database (or waits in PENDING), an incident STORAGE_UNAVAILABLE is opened with a
# (simulated) WhatsApp alert, S3 comes back -> the incident is resolved. PASS/FAIL.
#
#   bash scripts/drills/storage-outage.sh            (STORAGE=s3 by default)
set -euo pipefail
. "$(dirname "$0")/../lib/common.sh"
STORAGE="${STORAGE:-s3}"
UPPER=$(echo "$STORAGE" | tr a-z A-Z)
restore() { docker compose start "$STORAGE" >/dev/null 2>&1 || true; }
trap restore EXIT
fail() { echo "FAIL: $*"; exit 1; }

echo "1) Stopping $STORAGE"
docker compose stop "$STORAGE" >/dev/null

echo "2) Waiting for FileGrid to see $UPPER down and open an incident"
OPEN=0
for i in $(seq 1 30); do
  OPEN=$(api_curl GET "/incidents?type=STORAGE_UNAVAILABLE&open=true" | json "d.total")
  [ "${OPEN:-0}" -ge 1 ] && break
  sleep 3
done
[ "${OPEN:-0}" -ge 1 ] || fail "no STORAGE_UNAVAILABLE incident"
INC=$(api_curl GET "/incidents?type=STORAGE_UNAVAILABLE&open=true" | json "d.items[0].code")
echo "   incident $INC opened (WhatsApp alert simulated)"

echo "3) Uploading a connection document while $UPPER is down"
TMP=$(mktemp --suffix=.pdf)
printf '%%PDF-1.4\n%% FileGrid drill %s\n%%%%EOF\n' "$(date +%s%N)" > "$TMP"
ID=$(upload_file "$TMP" CONNECTION_DOCUMENT)
rm -f "$TMP"
[ -n "$ID" ] || fail "upload rejected"
echo "   file $ID"
ST=$(wait_status "$ID" '^(STORED_VERIFIED|PENDING)' 40) || fail "file neither stored on fallback nor pending ($ST)"
echo "   status: $ST"

echo "4) Starting $STORAGE again"
docker compose start "$STORAGE" >/dev/null
for i in $(seq 1 40); do
  OPEN=$(api_curl GET "/incidents?type=STORAGE_UNAVAILABLE&open=true" | json "d.total")
  [ "${OPEN:-1}" -eq 0 ] && break
  sleep 3
done
[ "${OPEN:-1}" -eq 0 ] || fail "incident not resolved after $UPPER came back"
echo "   incident resolved, final update sent to the alerted people"
if [[ "$ST" == PENDING* ]]; then
  echo "5) The file was PENDING: waiting for the automatic retry"
  ST=$(wait_status "$ID" '^STORED_VERIFIED' 60) || fail "PENDING file not stored after $UPPER came back ($ST)"
  echo "   status: $ST (no manual intervention)"
fi
echo "PASS: outage detected, file kept safe ($ST), incident opened and resolved"
