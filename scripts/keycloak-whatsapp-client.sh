#!/usr/bin/env bash
# Adds the PPCastor WhatsApp identity (realm role svc-whatsapp + client filegrid-whatsapp with its
# service account) to an already running Keycloak. Needed once on installations whose realm was imported
# before ADR-0008 (the realm file is imported only on the first start). Idempotent; prints no secret.
set -euo pipefail
# Git Bash on Windows would rewrite /opt/... container paths into Windows paths.
export MSYS_NO_PATHCONV=1

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
bash scripts/init-env.sh >/dev/null  # appends KC_WHATSAPP_SECRET if it is missing
set -a
# shellcheck disable=SC1091
. "$ROOT/.env"
set +a

REALM=filegrid
CLIENT=filegrid-whatsapp
ROLE=svc-whatsapp
CFG=/tmp/kcadm-whatsapp.config

kc() { docker compose exec -T keycloak /opt/keycloak/bin/kcadm.sh "$@" --config "$CFG"; }
# The kcadm config holds an admin token: never leave it in the container.
trap 'docker compose exec -T keycloak rm -f "$CFG" >/dev/null 2>&1 || true' EXIT

kc config credentials --server http://localhost:8080/auth --realm master \
  --user "$KC_BOOTSTRAP_ADMIN_USERNAME" --password "$KC_BOOTSTRAP_ADMIN_PASSWORD" >/dev/null

if ! kc get "roles/$ROLE" -r "$REALM" >/dev/null 2>&1; then
  kc create roles -r "$REALM" -s name="$ROLE" \
    -s 'description=Serviciu: PPCastor pe WhatsApp (asistent, rapoarte vocale)' >/dev/null
  echo "keycloak: role $ROLE created"
fi

ID="$(kc get clients -r "$REALM" -q clientId="$CLIENT" --fields id --format csv --noquotes | tr -d '\r')"
if [ -z "$ID" ]; then
  kc create clients -r "$REALM" -s clientId="$CLIENT" -s enabled=true -s publicClient=false \
    -s clientAuthenticatorType=client-secret -s secret="$KC_WHATSAPP_SECRET" \
    -s standardFlowEnabled=false -s directAccessGrantsEnabled=false -s serviceAccountsEnabled=true \
    -s 'protocolMappers=[{"name":"audience-filegrid-api","protocol":"openid-connect","protocolMapper":"oidc-audience-mapper","config":{"included.custom.audience":"filegrid-api","access.token.claim":"true","id.token.claim":"false"}}]' \
    >/dev/null
  echo "keycloak: client $CLIENT created"
else
  kc update "clients/$ID" -r "$REALM" -s secret="$KC_WHATSAPP_SECRET" >/dev/null
  echo "keycloak: client $CLIENT exists, secret synchronised with the env file"
fi

kc add-roles -r "$REALM" --uusername "service-account-$CLIENT" --rolename "$ROLE" >/dev/null
echo "keycloak: service-account-$CLIENT has role $ROLE"
