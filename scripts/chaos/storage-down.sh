#!/usr/bin/env bash
# Makes a storage completely unavailable.
#   default (proxy mode): disables its Toxiproxy proxy, so connections are refused. Affects the
#     workers only when they are wired through toxiproxy (opt-in, infra/toxiproxy/README.md).
#   HARD=1: stops the storage container itself (affects every client; what the drills use).
#
#   bash scripts/chaos/storage-down.sh <s3|store-db|ftp>          # until scripts/chaos/reset.sh
#   HARD=1 DURATION=60 bash scripts/chaos/storage-down.sh ftp     # stop ftp for 60 s, then start it
set -euo pipefail
. "$(dirname "$0")/../lib/common.sh"
TARGET="${1:?usage: storage-down.sh <s3|store-db|ftp>}"
case "$TARGET" in s3|store-db|ftp) ;; *) echo "unknown storage '$TARGET'" >&2; exit 2 ;; esac
PROXY="$TARGET"; [ "$TARGET" = ftp ] && PROXY=ftp-control

if [ "${HARD:-0}" = 1 ]; then
  if [ -n "${DURATION:-}" ]; then
    trap 'docker compose start "$TARGET" >/dev/null && echo "$TARGET started again"' EXIT
  fi
  docker compose stop "$TARGET" >/dev/null
  echo "$TARGET container stopped"
  if [ -n "${DURATION:-}" ]; then echo "holding for ${DURATION}s"; sleep "$DURATION"
  else echo "restore with: docker compose start $TARGET"; fi
else
  toxi_populate
  if [ -n "${DURATION:-}" ]; then
    trap 'toxi POST "/proxies/$PROXY" "{\"enabled\":true}" >/dev/null; echo "proxy $PROXY enabled again"' EXIT
  fi
  toxi POST "/proxies/$PROXY" '{"enabled":false}' >/dev/null
  echo "proxy $PROXY disabled (connections refused)"
  if [ -n "${DURATION:-}" ]; then echo "holding for ${DURATION}s"; sleep "$DURATION"
  else echo "active until: bash scripts/chaos/reset.sh"; fi
fi
