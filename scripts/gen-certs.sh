#!/usr/bin/env bash
# Generates the local TLS certificate used by the gateway into infra/certs/
# (git-ignored). Prefers mkcert (trusted by the browser); falls back to an
# openssl self-signed certificate (browser shows a warning once).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CERT_DIR="$ROOT/infra/certs"
CERT="$CERT_DIR/localhost.pem"
KEY="$CERT_DIR/localhost-key.pem"

mkdir -p "$CERT_DIR"

if [ -s "$CERT" ] && [ -s "$KEY" ]; then
  echo "gen-certs: certificate already exists (left untouched)"
  exit 0
fi

if command -v mkcert >/dev/null 2>&1; then
  mkcert -cert-file "$CERT" -key-file "$KEY" localhost 127.0.0.1 ::1
  echo "gen-certs: created a locally trusted certificate with mkcert"
elif command -v openssl >/dev/null 2>&1; then
  # MSYS_NO_PATHCONV stops Git Bash from rewriting "/CN=..." into a Windows
  # path; relative file names keep the output paths valid with it enabled.
  (
    cd "$CERT_DIR"
    MSYS_NO_PATHCONV=1 openssl req -x509 -newkey rsa:2048 -nodes -days 825 \
      -keyout localhost-key.pem -out localhost.pem \
      -subj "/CN=localhost" \
      -addext "subjectAltName=DNS:localhost,IP:127.0.0.1" 2>/dev/null
  )
  echo "gen-certs: created a SELF-SIGNED certificate with openssl"
  echo "           (the browser will warn once; install mkcert for a trusted one)"
else
  echo "gen-certs: neither mkcert nor openssl found. Install one of them." >&2
  exit 1
fi
