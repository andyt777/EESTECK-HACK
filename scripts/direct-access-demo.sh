#!/usr/bin/env bash
# Demo (Stage 7): reads a connection document DIRECTLY from its storage, from
# inside the internal Docker network (no storage port is published), and shows
# that only ciphertext is there. Then downloads it through FileGrid, which
# decrypts it and verifies the original SHA-256.
#
#   scripts/direct-access-demo.sh [fileId]     (default: newest stored connection document)
set -euo pipefail
cd "$(dirname "$0")/.."
export MSYS_NO_PATHCONV=1   # Git Bash: keep container paths as they are
GATEWAY="${GATEWAY:-https://localhost}"

psql_meta() {
  docker compose exec -T meta-db sh -c "psql -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\" -At -F '|' -c \"$1\""
}

FILE_ID="${1:-$(psql_meta "SELECT id FROM file_record WHERE category = 'CONNECTION_DOCUMENT' AND status = 'STORED_VERIFIED' ORDER BY stored_at DESC LIMIT 1")}"
if [ -z "$FILE_ID" ]; then
  echo "No stored connection document. Upload a PDF as CONNECTION_DOCUMENT first." >&2
  exit 1
fi
ROW=$(psql_meta "SELECT f.storage_type, f.storage_key, f.encrypted, f.sha256_plain, f.sha256_stored, f.original_name, coalesce(k.key_id,'-') FROM file_record f LEFT JOIN file_key k ON k.file_id = f.id WHERE f.id = '$FILE_ID'")
IFS='|' read -r STORAGE KEY ENCRYPTED SHA_PLAIN SHA_STORED NAME KEY_ID <<<"$ROW"
echo "File      : $NAME ($FILE_ID)"
echo "Storage   : $STORAGE  key=$KEY   (the key is a UUID path: no client name in it)"
echo "Encrypted : $ENCRYPTED  (data key wrapped with KEK '$KEY_ID', kept in meta-db only)"
echo "SHA-256 original : $SHA_PLAIN"
echo "SHA-256 stored   : $SHA_STORED"
echo
echo "== First 256 bytes read directly from $STORAGE (bypassing FileGrid) =="
case "$STORAGE" in
  S3)
    docker compose exec -T -e "FG_BUCKET=filegrid-${KEY%%/*}" -e "FG_OBJECT=${KEY#*/}" s3 sh -c '
      mc alias set local http://127.0.0.1:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null
      mc cat "local/$FG_BUCKET/$FG_OBJECT" | head -c 256 | od -A x -t x1z | head -16' ;;
  FTP)
    docker compose exec -T -e "FG_KEY=$KEY" ftp sh -c 'head -c 256 "$FTP_USER_HOME/$FG_KEY" | od -A x -t x1z | head -16' ;;
  DATABASE)
    docker compose exec -T store-db sh -c "psql -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\" -At -c \"SELECT encode(substring(content from 1 for 256), 'escape') FROM stored_blob WHERE key = '$KEY'\"" | head -c 400; echo ;;
esac
echo
echo "== Downloaded through FileGrid ($GATEWAY) =="
OUT=$(mktemp)
OUT_NATIVE=$(cygpath -w "$OUT" 2>/dev/null || echo "$OUT")   # Windows curl needs a native path
curl -sk -D - -o "$OUT_NATIVE" "$GATEWAY/api/files/$FILE_ID/content" | grep -iE "^(HTTP|x-integrity|x-content-sha256)" || true
echo "first bytes: $(head -c 8 "$OUT" | od -An -c | tr -s ' ')"
echo "SHA-256 of the download: $(sha256sum "$OUT" | cut -d' ' -f1)"
rm -f "$OUT"
