#!/bin/sh
# One-shot, idempotent: creates (or updates the password of) the `keycloak` role and
# database inside meta-db. initdb.d scripts do not run on an existing volume, so this
# runs on every start and only changes what is missing.
set -eu

export PGPASSWORD="$META_DB_PASSWORD"
PSQL="psql -v ON_ERROR_STOP=1 -h meta-db -U $META_DB_USER -d filegrid_meta -q"

$PSQL -v kc_password="$KC_DB_PASSWORD" <<'SQL'
SELECT format('CREATE ROLE keycloak LOGIN PASSWORD %L', :'kc_password')
 WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'keycloak') \gexec
SELECT format('ALTER ROLE keycloak WITH LOGIN PASSWORD %L', :'kc_password') \gexec
SELECT 'CREATE DATABASE keycloak OWNER keycloak'
 WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'keycloak') \gexec
SQL

echo "keycloak-db-init: role and database 'keycloak' are ready"
