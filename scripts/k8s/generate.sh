#!/usr/bin/env bash
# Generates the Secrets and ConfigMaps the Kubernetes manifests expect, into
# infra/k8s/overlays/kind/generated/ (git-ignored, files readable only by you):
#
#   secrets.yaml    from .env           (credentials, KEK, LLM keys, TLS)
#   configmaps.yaml from repo files     (config/, docs/*.md, simulator/topology.yaml,
#                                        infra/rabbitmq/, infra/minio/) + LLM choice from .env
#
# Same sources as docker compose, so there is ONE place to change a value.
# .env is parsed line by line, never `source`d (it is data, not a script).
# Nothing is printed except file names. Only `kubectl create --dry-run=client`
# is used: no cluster is needed to run this script.
#
# Usage: bash scripts/k8s/generate.sh            (reads ./.env)
#        ENV_FILE=/path/to/env bash scripts/k8s/generate.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="${ENV_FILE:-$ROOT/.env}"
OUT="$ROOT/infra/k8s/overlays/kind/generated"
NS="filegrid"
KUBECTL="${KUBECTL:-kubectl}"

die() { echo "k8s/generate: $*" >&2; exit 1; }

command -v "$KUBECTL" >/dev/null 2>&1 || die "kubectl not found"
[ -f "$ENV_FILE" ] || die "$ENV_FILE not found - run scripts/init-env.sh first"

# Value of KEY in the env file: last definition wins, CR and one pair of
# surrounding quotes removed. Empty string when the key is absent.
env_get() {
  local line
  line="$(grep -E "^$1=" "$ENV_FILE" | tail -n 1 || true)"
  line="${line#*=}"
  line="${line%$'\r'}"
  case "$line" in
    \"*\") line="${line#\"}"; line="${line%\"}" ;;
    \'*\') line="${line#\'}"; line="${line%\'}" ;;
  esac
  printf '%s' "$line"
}

# Value of KEY or a default when it is empty/absent.
env_or() {
  local v
  v="$(env_get "$1")"
  printf '%s' "${v:-$2}"
}

REQUIRED=(
  META_DB_USER META_DB_PASSWORD STORE_DB_USER STORE_DB_PASSWORD
  MINIO_ROOT_USER MINIO_ROOT_PASSWORD S3_ACCESS_KEY S3_SECRET_KEY
  FTP_USER FTP_PASSWORD RABBITMQ_USER RABBITMQ_PASSWORD FILEGRID_KEK
)
for key in "${REQUIRED[@]}"; do
  [ -n "$(env_get "$key")" ] || die "$key is empty or missing in $ENV_FILE - run scripts/init-env.sh"
done

# TLS certificate: reuse the one docker compose uses (infra/certs, git-ignored).
if [ ! -s "$ROOT/infra/certs/localhost.pem" ] || [ ! -s "$ROOT/infra/certs/localhost-key.pem" ]; then
  bash "$ROOT/scripts/gen-certs.sh"
fi

umask 077
mkdir -p "$OUT"
TMP="$(mktemp -d "$OUT/.tmp.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT

# secret NAME TARGET_KEY=ENV_KEY ...  -> one Secret built from an env file in $TMP.
# Paths are relative (cd) so a Windows kubectl works from Git Bash too.
secret() {
  local name="$1" pair
  shift
  : > "$TMP/$name.env"
  for pair in "$@"; do
    printf '%s=%s\n' "${pair%%=*}" "$(env_get "${pair#*=}")" >> "$TMP/$name.env"
  done
  (cd "$TMP" && "$KUBECTL" create secret generic "$name" --namespace "$NS" \
     --from-env-file="$name.env" --dry-run=client -o yaml)
  echo "---"
}

{
  secret backend-secrets \
    META_DB_USER=META_DB_USER META_DB_PASSWORD=META_DB_PASSWORD \
    STORE_DB_USER=STORE_DB_USER STORE_DB_PASSWORD=STORE_DB_PASSWORD \
    S3_ACCESS_KEY=S3_ACCESS_KEY S3_SECRET_KEY=S3_SECRET_KEY \
    FTP_USER=FTP_USER FTP_PASSWORD=FTP_PASSWORD \
    RABBITMQ_USER=RABBITMQ_USER RABBITMQ_PASSWORD=RABBITMQ_PASSWORD \
    FILEGRID_KEK=FILEGRID_KEK FILEGRID_KEK_ID=FILEGRID_KEK_ID \
    FILEGRID_KEK_PREVIOUS=FILEGRID_KEK_PREVIOUS

  # WhatsApp (Twilio) + contact numbers: only the api needs them.
  secret api-notify-secrets \
    TWILIO_ACCOUNT_SID=TWILIO_ACCOUNT_SID TWILIO_AUTH_TOKEN=TWILIO_AUTH_TOKEN \
    TWILIO_WHATSAPP_FROM=TWILIO_WHATSAPP_FROM \
    NOTIFY_ESCALATION_OVERRIDE=NOTIFY_ESCALATION_OVERRIDE \
    NOTIFY_CONTACT_DISPATCH=NOTIFY_CONTACT_DISPATCH \
    NOTIFY_CONTACT_TECH_MANAGER=NOTIFY_CONTACT_TECH_MANAGER \
    NOTIFY_CONTACT_FIELD_TEAM=NOTIFY_CONTACT_FIELD_TEAM \
    NOTIFY_CONTACT_IT_SECURITY=NOTIFY_CONTACT_IT_SECURITY \
    NOTIFY_CONTACT_PLATFORM_ADMIN=NOTIFY_CONTACT_PLATFORM_ADMIN

  secret intelligence-secrets \
    META_DB_USER=META_DB_USER META_DB_PASSWORD=META_DB_PASSWORD \
    STORE_DB_USER=STORE_DB_USER STORE_DB_PASSWORD=STORE_DB_PASSWORD \
    ANTHROPIC_API_KEY=ANTHROPIC_API_KEY LLM_API_KEY=LLM_API_KEY \
    TWILIO_ACCOUNT_SID=TWILIO_ACCOUNT_SID TWILIO_AUTH_TOKEN=TWILIO_AUTH_TOKEN \
    TWILIO_FROM_NUMBER=TWILIO_FROM_NUMBER

  secret meta-db-secret POSTGRES_USER=META_DB_USER POSTGRES_PASSWORD=META_DB_PASSWORD
  secret store-db-secret POSTGRES_USER=STORE_DB_USER POSTGRES_PASSWORD=STORE_DB_PASSWORD
  # Root credentials: only the s3 server and the s3-init Job see them.
  secret s3-secret \
    MINIO_ROOT_USER=MINIO_ROOT_USER MINIO_ROOT_PASSWORD=MINIO_ROOT_PASSWORD \
    S3_ACCESS_KEY=S3_ACCESS_KEY S3_SECRET_KEY=S3_SECRET_KEY
  secret ftp-secret FTP_USER_NAME=FTP_USER FTP_USER_PASS=FTP_PASSWORD
  secret rabbitmq-secret RABBITMQ_DEFAULT_USER=RABBITMQ_USER RABBITMQ_DEFAULT_PASS=RABBITMQ_PASSWORD

  # KEDA connection string. Generated passwords are hex, so they need no URL
  # encoding; a hand-written password with @ : / % must be percent-encoded.
  printf 'host=amqp://%s:%s@rabbitmq.%s.svc.cluster.local:5672/\n' \
    "$(env_get RABBITMQ_USER)" "$(env_get RABBITMQ_PASSWORD)" "$NS" > "$TMP/keda-rabbitmq.env"
  (cd "$TMP" && "$KUBECTL" create secret generic keda-rabbitmq --namespace "$NS" \
     --from-env-file=keda-rabbitmq.env --dry-run=client -o yaml)
  echo "---"

  (cd "$ROOT" && "$KUBECTL" create secret tls filegrid-tls --namespace "$NS" \
     --cert=infra/certs/localhost.pem --key=infra/certs/localhost-key.pem \
     --dry-run=client -o yaml)
} > "$OUT/secrets.yaml"

# Non-secret LLM choice for intelligence (same defaults as docker-compose).
{
  printf 'ASSISTANT_MODEL=%s\n' "$(env_or ASSISTANT_MODEL claude-sonnet-5-5)"
  printf 'LLM_PROVIDER=%s\n'    "$(env_get LLM_PROVIDER)"
  printf 'LLM_BASE_URL=%s\n'    "$(env_get LLM_BASE_URL)"
  printf 'LLM_MODEL=%s\n'       "$(env_or LLM_MODEL gemini-3.5-flash)"
  printf 'VOICE_PROVIDER=%s\n'  "$(env_or VOICE_PROVIDER simulated)"
} > "$TMP/intelligence-llm.env"

DOC_ARGS=()
for doc in "$ROOT"/docs/*.md; do
  DOC_ARGS+=("--from-file=docs/$(basename "$doc")")
done

(
  cd "$ROOT"
  cm() { "$KUBECTL" create configmap "$@" --namespace "$NS" --dry-run=client -o yaml; echo "---"; }
  cm filegrid-config --from-file=config/policies.yaml --from-file=config/notification-routing.yaml
  cm filegrid-templates --from-file=config/templates
  cm filegrid-docs "${DOC_ARGS[@]}"
  cm simulator-topology --from-file=simulator/topology.yaml
  cm rabbitmq-config --from-file=infra/rabbitmq/enabled_plugins --from-file=infra/rabbitmq/20-filegrid.conf
  cm s3-init --from-file=infra/minio/init.sh --from-file=infra/minio/filegrid-policy.json
) > "$OUT/configmaps.yaml"
(cd "$TMP" && "$KUBECTL" create configmap intelligence-llm --namespace "$NS" \
   --from-env-file=intelligence-llm.env --dry-run=client -o yaml) >> "$OUT/configmaps.yaml"

echo "k8s/generate: wrote $OUT/secrets.yaml and $OUT/configmaps.yaml (git-ignored)"
