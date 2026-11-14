#!/bin/sh
# One-shot MinIO bootstrap: versioned buckets + dedicated FileGrid service user.
# Idempotent: safe to run on every `docker compose up`.
set -eu

mc alias set s3 http://s3:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null

for bucket in filegrid-faults filegrid-documents filegrid-videos filegrid-meters filegrid-benchmark; do
  mc mb --ignore-existing "s3/$bucket"
  mc version enable "s3/$bucket"
done

# Service user (FileGrid never uses the root credentials).
mc admin user add s3 "$S3_ACCESS_KEY" "$S3_SECRET_KEY"

# Least privilege: only the filegrid-* buckets.
mc admin policy create s3 filegrid-rw /init/filegrid-policy.json
mc admin policy attach s3 filegrid-rw --user "$S3_ACCESS_KEY" 2>/dev/null \
  || echo "policy filegrid-rw already attached"

echo "minio-init: done"
