#!/usr/bin/env bash
# Demo only: flips one byte of a stored file DIRECTLY in its storage (bypassing
# FileGrid), so that the next retrieval or verification detects the mismatch.
# Runs inside the storage containers of the internal network; no port is published.
# Credentials are read inside each container from its own environment.
#
#   scripts/corrupt-file.sh <fileId>
set -euo pipefail

FILE_ID="${1:?usage: scripts/corrupt-file.sh <fileId>}"
if ! [[ "$FILE_ID" =~ ^[0-9a-fA-F-]{36}$ ]]; then
  echo "fileId must be a UUID" >&2
  exit 2
fi
cd "$(dirname "$0")/.."
export MSYS_NO_PATHCONV=1   # Git Bash: keep container paths as they are

ROW=$(docker compose exec -T meta-db sh -c \
  "psql -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\" -At -F '|' -c \"SELECT storage_type, storage_key, coalesce(idempotency_key,''), category, status FROM file_record WHERE id = '$FILE_ID'\"")
if [ -z "$ROW" ]; then
  echo "File $FILE_ID is not known to FileGrid." >&2
  exit 1
fi
IFS='|' read -r STORAGE KEY IDEM CATEGORY STATUS <<<"$ROW"
if [ "$STATUS" != "STORED_VERIFIED" ]; then
  echo "File $FILE_ID is not stored yet (status $STATUS)." >&2
  exit 1
fi
echo "File $FILE_ID: category $CATEGORY, storage $STORAGE, key $KEY"

# Shell snippet run in the storage container: flips the first byte of file "$f".
FLIP='first=$(od -An -tu1 -N1 "$f" | tr -d " "); printf "\\$(printf "%03o" $(( first ^ 255 )))" | dd of="$f" bs=1 count=1 conv=notrunc 2>/dev/null'

case "$STORAGE" in
  DATABASE)
    if [ "$CATEGORY" = "METER_READING" ] && [ -n "$IDEM" ]; then
      SQL="UPDATE meter_reading_blob SET content = set_byte(content, 0, get_byte(content, 0) # 255) WHERE reading_id = '$IDEM'"
    else
      SQL="UPDATE stored_blob SET content = set_byte(content, 0, get_byte(content, 0) # 255) WHERE key = '$KEY'"
    fi
    docker compose exec -T store-db sh -c "psql -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\" -c \"$SQL\""
    ;;
  FTP)
    docker compose exec -T -e "FG_KEY=$KEY" -e "FG_FLIP=$FLIP" ftp sh -c '
      f="$FTP_USER_HOME/$FG_KEY"
      [ -f "$f" ] || { echo "not found: $f" >&2; exit 1; }
      eval "$FG_FLIP"
      echo "flipped the first byte of $f"'
    ;;
  S3)
    docker compose exec -T -e "FG_BUCKET=filegrid-${KEY%%/*}" -e "FG_OBJECT=${KEY#*/}" -e "FG_FLIP=$FLIP" s3 sh -c '
      set -e
      mc alias set local http://127.0.0.1:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null
      f=/tmp/filegrid-corrupt
      mc cp --quiet "local/$FG_BUCKET/$FG_OBJECT" "$f" >/dev/null
      eval "$FG_FLIP"
      mc cp --quiet "$f" "local/$FG_BUCKET/$FG_OBJECT" >/dev/null
      rm -f "$f"
      echo "flipped the first byte of s3://$FG_BUCKET/$FG_OBJECT"'
    ;;
  *)
    echo "Unknown storage '$STORAGE'." >&2
    exit 1
    ;;
esac
echo "Done. Retrieve or verify the file in FileGrid: the mismatch is detected there."
