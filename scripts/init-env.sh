#!/usr/bin/env bash
# Creates .env from .env.example, replacing every __GENERATE_*__ placeholder with
# a strong random value. Never overwrites an existing value: if .env already
# exists, only variables that are missing from it are appended.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEMPLATE="$ROOT/.env.example"
TARGET="$ROOT/.env"

rand_hex() { head -c "$1" /dev/urandom | od -An -tx1 | tr -d ' \n'; }
rand_b64() { head -c "$1" /dev/urandom | base64 | tr -d '\n'; }

# Prints the template line with its placeholder (if any) replaced.
render() {
  local line="$1"
  case "$line" in
    *__GENERATE_HEX_24__*)    printf '%s%s\n' "${line%%__GENERATE_HEX_24__*}" "$(rand_hex 24)" ;;
    *__GENERATE_BASE64_32__*) printf '%s%s\n' "${line%%__GENERATE_BASE64_32__*}" "$(rand_b64 32)" ;;
    *)                        printf '%s\n' "$line" ;;
  esac
}

if [ ! -f "$TARGET" ]; then
  umask 077
  while IFS= read -r line || [ -n "$line" ]; do
    line="${line%$'\r'}"
    render "$line"
  done < "$TEMPLATE" > "$TARGET"
  echo "init-env: created .env with freshly generated secrets"
  exit 0
fi

added=0
while IFS= read -r line || [ -n "$line" ]; do
  line="${line%$'\r'}"
  case "$line" in
    ''|'#'*) continue ;;
  esac
  key="${line%%=*}"
  if ! grep -q "^${key}=" "$TARGET"; then
    render "$line" >> "$TARGET"
    echo "init-env: added missing variable $key"
    added=$((added + 1))
  fi
done < "$TEMPLATE"

if [ "$added" -eq 0 ]; then
  echo "init-env: .env already exists and is complete (left untouched)"
fi
