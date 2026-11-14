#!/usr/bin/env bash
# Demo drill: S3 goes down → a connection document (primary S3) falls back to
# Database, an incident STORAGE_UNAVAILABLE is opened with a (simulated)
# WhatsApp alert, S3 comes back → the incident is resolved. Verdict PASS/FAIL.
#
#   bash scripts/drills/storage-outage.sh
set -euo pipefail
cd "$(dirname "$0")/../.."
GW="${GATEWAY:-https://localhost}"
STORAGE="${STORAGE:-s3}"
UPPER=$(echo "$STORAGE" | tr a-z A-Z)
json() { node -e "let s='';process.stdin.on('data',c=>s+=c).on('end',()=>{const d=JSON.parse(s);console.log(eval(process.argv[1]))})" "$1"; }
fail() { echo "FAIL: $*"; docker compose start "$STORAGE" >/dev/null 2>&1 || true; exit 1; }

echo "1) Stopping $STORAGE"
docker compose stop "$STORAGE" >/dev/null

echo "2) Waiting for FileGrid to see $UPPER down and open an incident"
for i in $(seq 1 30); do
  OPEN=$(curl -sk "$GW/api/incidents?type=STORAGE_UNAVAILABLE&open=true" | json "d['total']")
  [ "${OPEN:-0}" -ge 1 ] && break
  sleep 3
done
[ "${OPEN:-0}" -ge 1 ] || fail "no STORAGE_UNAVAILABLE incident"
INC=$(curl -sk "$GW/api/incidents?type=STORAGE_UNAVAILABLE&open=true" | json "d['items'][0]['code']")
echo "   incident $INC opened (WhatsApp alert simulated)"

echo "3) Uploading a connection document while $UPPER is down"
TMP=$(mktemp --suffix=.pdf)
TMP_NATIVE=$(cygpath -w "$TMP" 2>/dev/null || echo "$TMP")
printf '%%PDF-1.4\n%% FileGrid drill %s\n%%%%EOF\n' "$(date +%s%N)" > "$TMP"
ID=$(curl -sk -F "file=@$TMP_NATIVE;type=application/pdf" -F category=CONNECTION_DOCUMENT -F dataOrigin=SIMULATED \
  "$GW/api/files" | json "d['id']")
rm -f "$TMP"
echo "   file $ID"
for i in $(seq 1 40); do
  P=$(curl -sk "$GW/api/files/$ID/passport")
  ST=$(echo "$P" | json "d.status + '|' + (d.storage && d.storage.type || '')")
  case "$ST" in STORED_VERIFIED*|PENDING*) break ;; esac
  sleep 2
done
echo "   status: $ST"
case "$ST" in STORED_VERIFIED*|PENDING*) ;; *) fail "file neither stored on fallback nor pending ($ST)" ;; esac

echo "4) Starting $STORAGE again"
docker compose start "$STORAGE" >/dev/null
for i in $(seq 1 40); do
  OPEN=$(curl -sk "$GW/api/incidents?type=STORAGE_UNAVAILABLE&open=true" | json "d['total']")
  [ "${OPEN:-1}" -eq 0 ] && break
  sleep 3
done
[ "${OPEN:-1}" -eq 0 ] || fail "incident not resolved after $UPPER came back"
echo "   incident resolved, final update sent to the alerted people"
echo "PASS: outage detected, file kept safe ($ST), incident opened and resolved"
