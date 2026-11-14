# Shared helpers for scripts/chaos/*.sh and scripts/drills/*.sh (Stage 18).
# Source it:  . "$(dirname "$0")/../lib/common.sh"
#
# Environment (all optional):
#   FILEGRID_ROOT      repository root holding compose.yaml + .env (default: this checkout)
#   FILEGRID_API_URL   API base URL as seen from the host (default: $GATEWAY/api)
#   GATEWAY            gateway URL (default: https://localhost) - kept for older scripts
#   FILEGRID_TOKEN     bearer token; when set, every API call sends `Authorization: Bearer <token>`
#   FILEGRID_HELPER    compose service with curl used to reach internal-only endpoints
#                      (toxiproxy API, Prometheus); default: api
# Requires: docker compose, curl, node (JSON parsing; python3 is unreliable on Windows).

FG_LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FILEGRID_ROOT="${FILEGRID_ROOT:-$(cd "$FG_LIB_DIR/../.." && pwd)}"
cd "$FILEGRID_ROOT"
export MSYS_NO_PATHCONV=1   # Git Bash: keep container paths as they are

GATEWAY="${GATEWAY:-https://localhost}"
API="${FILEGRID_API_URL:-$GATEWAY/api}"
FILEGRID_HELPER="${FILEGRID_HELPER:-api}"
TOXIPROXY_API="${TOXIPROXY_API:-http://toxiproxy:8474}"
PROMETHEUS_URL="${PROMETHEUS_URL:-http://prometheus:9090}"

AUTH_ARGS=()
if [ -n "${FILEGRID_TOKEN:-}" ]; then
  AUTH_ARGS=(-H "Authorization: Bearer $FILEGRID_TOKEN")
fi

log()  { printf '%s\n' "$*"; }
step() { printf '\n== %s\n' "$*"; }

# JSON from stdin, evaluated with `d` bound to the parsed document:  echo "$J" | json "d.total"
json() {
  node -e "let s='';process.stdin.on('data',c=>s+=c).on('end',()=>{let d;try{d=JSON.parse(s)}catch(e){console.log('');return}const v=eval(process.argv[1]);console.log(v===undefined||v===null?'':(typeof v==='object'?JSON.stringify(v):v))})" "$1"
}

# curl against the FileGrid API (host side, through the gateway). Usage: api_curl GET /files?size=1
api_curl() {
  local method="$1" path="$2"; shift 2
  curl -sk -X "$method" "${AUTH_ARGS[@]}" "$@" "$API$path"
}

# Native path for Windows curl (Git Bash /tmp is not visible to it).
native_path() { cygpath -w "$1" 2>/dev/null || echo "$1"; }

# curl from inside the internal Docker network (no internal port is published).
incluster_curl() { docker compose exec -T "$FILEGRID_HELPER" curl -s "$@"; }

# Toxiproxy HTTP API:  toxi GET /proxies   |   toxi POST /reset   |   toxi POST /proxies/x '{"enabled":false}'
toxi() {
  local method="$1" path="$2" body="${3:-}"
  if [ -n "$body" ]; then
    incluster_curl -X "$method" -H 'Content-Type: application/json' --data-binary "$body" "$TOXIPROXY_API$path"
  else
    incluster_curl -X "$method" "$TOXIPROXY_API$path"
  fi
}

# Creates/updates the proxies from infra/toxiproxy/toxiproxy.json (idempotent; same file the server loads at start).
toxi_populate() {
  docker compose exec -T "$FILEGRID_HELPER" curl -s -X POST -H 'Content-Type: application/json' \
    --data-binary @- "$TOXIPROXY_API/populate" < "$FG_LIB_DIR/../../infra/toxiproxy/toxiproxy.json" >/dev/null
}

# add_toxic <proxy> <name> <type> <stream> <toxicity> <attributes-json>
add_toxic() {
  toxi DELETE "/proxies/$1/toxics/$2" >/dev/null 2>&1 || true
  toxi POST "/proxies/$1/toxics" \
    "{\"name\":\"$2\",\"type\":\"$3\",\"stream\":\"$4\",\"toxicity\":$5,\"attributes\":$6}"
}

# Chaos scripts: DURATION=<seconds> keeps the toxics for that long, then removes them
# (also on Ctrl-C / errors); without DURATION they stay until scripts/chaos/reset.sh.
#   chaos_hold <proxy> <toxic-name>...
chaos_hold() {
  CHAOS_PROXY="$1"; shift
  CHAOS_TOXICS=("$@")
  if [ -n "${DURATION:-}" ]; then
    trap 'for n in "${CHAOS_TOXICS[@]}"; do toxi DELETE "/proxies/$CHAOS_PROXY/toxics/$n" >/dev/null 2>&1 || true; done; echo "removed ${CHAOS_TOXICS[*]} from $CHAOS_PROXY"' EXIT
    echo "holding for ${DURATION}s (Ctrl-C to stop early)"
    sleep "$DURATION"
  else
    echo "active until: bash scripts/chaos/reset.sh"
  fi
}

# Instant Prometheus query -> first sample value (empty when no data).  prom 'sum(filegrid_storage_up)'
prom() {
  docker compose exec -T "$FILEGRID_HELPER" curl -s --get --data-urlencode "query=$1" "$PROMETHEUS_URL/api/v1/query" \
    | json "(d.data && d.data.result && d.data.result[0]) ? d.data.result[0].value[1] : ''"
}

psql_meta() {
  docker compose exec -T meta-db sh -c "psql -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\" -At -F '|' -c \"$1\""
}
psql_store() {
  docker compose exec -T store-db sh -c "psql -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\" -At -F '|' -c \"$1\""
}

# p95 of (stored_at - received_at) in seconds for a category since an ISO timestamp (meta-db).
store_p95() {
  local category="$1" since="$2" until="${3:-}"
  local until_sql=""
  [ -n "$until" ] && until_sql="AND received_at <= '$until'"
  psql_meta "SELECT coalesce(round(percentile_cont(0.95) WITHIN GROUP (ORDER BY extract(epoch FROM stored_at - received_at))::numeric, 3), -1) || '|' || count(*) FROM file_record WHERE category = '$category' AND stored_at IS NOT NULL AND received_at >= '$since' $until_sql"
}

now_iso() { date -u +%Y-%m-%dT%H:%M:%SZ; }

# Waits until a file reaches one of the given statuses (regex). Prints "<status>|<storage>".
wait_status() {
  local id="$1" pattern="$2" tries="${3:-60}" st=""
  for _ in $(seq 1 "$tries"); do
    st=$(api_curl GET "/files/$id/passport" | json "d.status + '|' + ((d.storage && d.storage.type) || '')")
    if [[ "$st" =~ $pattern ]]; then echo "$st"; return 0; fi
    sleep 2
  done
  echo "$st"; return 1
}

# Small multipart upload. upload_file <path> <category> [extra curl -F args...]  -> prints the file id
upload_file() {
  local path="$1" category="$2"; shift 2
  api_curl POST /files -F "file=@$(native_path "$path")" -F "category=$category" -F dataOrigin=SIMULATED "$@" \
    | json "d.id"
}

DRILL_FAILED=0
check() {  # check <description> <command...>
  local desc="$1"; shift
  if "$@"; then log "   [PASS] $desc"; else log "   [FAIL] $desc"; DRILL_FAILED=1; fi
}
verdict() {
  if [ "$DRILL_FAILED" -eq 0 ]; then log "PASS: $1"; exit 0; fi
  log "FAIL: $1"; exit 1
}
