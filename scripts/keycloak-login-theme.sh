#!/usr/bin/env bash
# Selects the FileGrid login theme (infra/keycloak/themes/filegrid: PPCastor, app palette) on an already
# running Keycloak. Needed once on installations whose realm was imported before the theme existed (the realm
# file is imported only on the first start). Idempotent; prints no secret.
set -euo pipefail
# Git Bash on Windows would rewrite /opt/... container paths into Windows paths.
export MSYS_NO_PATHCONV=1

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
set -a
# shellcheck disable=SC1091
. "$ROOT/.env"
set +a

REALM=filegrid
CFG=/tmp/kcadm-theme.config

kc() { docker compose exec -T keycloak /opt/keycloak/bin/kcadm.sh "$@" --config "$CFG"; }
# The kcadm config holds an admin token: never leave it in the container.
trap 'docker compose exec -T keycloak rm -f "$CFG" >/dev/null 2>&1 || true' EXIT

if ! docker compose exec -T keycloak test -f /opt/keycloak/themes/filegrid/login/theme.properties; then
  echo "keycloak: theme not mounted; run: docker compose up -d keycloak" >&2
  exit 1
fi

kc config credentials --server http://localhost:8080/auth --realm master \
  --user "$KC_BOOTSTRAP_ADMIN_USERNAME" --password "$KC_BOOTSTRAP_ADMIN_PASSWORD" >/dev/null
kc update "realms/$REALM" -s loginTheme=filegrid >/dev/null
echo "keycloak: realm $REALM uses login theme filegrid"

# Keycloak keeps gzip copies of theme resources on disk (data/tmp/kc-gzip-cache) and they survive a
# restart: without this, an edited filegrid.css is still served in its old version to every browser.
docker compose exec -T keycloak sh -c 'rm -rf /opt/keycloak/data/tmp/kc-gzip-cache/*/login/filegrid'
echo "keycloak: gzip cache of the filegrid theme cleared (browsers may need Ctrl+F5: resources are cached 30 days)"
