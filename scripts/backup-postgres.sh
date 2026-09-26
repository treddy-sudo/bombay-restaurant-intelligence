#!/usr/bin/env bash
set -euo pipefail

: "${DATABASE_HOST:?DATABASE_HOST is required}"
: "${DATABASE_PORT:=5432}"
: "${DATABASE_NAME:?DATABASE_NAME is required}"
: "${DATABASE_USERNAME:?DATABASE_USERNAME is required}"
: "${DATABASE_PASSWORD:?DATABASE_PASSWORD is required}"

BACKUP_DIR="${BACKUP_DIR:-/tmp/bombay-restaurant-backups}"
BACKUP_S3_PREFIX="${BACKUP_S3_PREFIX:-bombay-restaurant-intelligence/postgres}"
TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"
FILENAME="${DATABASE_NAME}-${TIMESTAMP}.dump"
mkdir -p "$BACKUP_DIR"
OUTPUT="$BACKUP_DIR/$FILENAME"

export PGPASSWORD="$DATABASE_PASSWORD"
pg_dump \
  --host="$DATABASE_HOST" \
  --port="$DATABASE_PORT" \
  --username="$DATABASE_USERNAME" \
  --dbname="$DATABASE_NAME" \
  --format=custom \
  --no-owner \
  --no-privileges \
  --file="$OUTPUT"

pg_restore --list "$OUTPUT" >/dev/null
sha256sum "$OUTPUT" >"$OUTPUT.sha256"

if [[ -n "${BACKUP_S3_BUCKET:-}" ]]; then
  command -v aws >/dev/null 2>&1 || { echo "aws CLI is required when BACKUP_S3_BUCKET is set" >&2; exit 1; }
  AWS_ARGS=()
  if [[ -n "${BACKUP_S3_ENDPOINT:-}" ]]; then
    AWS_ARGS+=(--endpoint-url "$BACKUP_S3_ENDPOINT")
  fi
  DEST="s3://${BACKUP_S3_BUCKET}/${BACKUP_S3_PREFIX}/${FILENAME}"
  aws "${AWS_ARGS[@]}" s3 cp "$OUTPUT" "$DEST"
  aws "${AWS_ARGS[@]}" s3 cp "$OUTPUT.sha256" "$DEST.sha256"
  echo "Backup uploaded: $DEST"
else
  echo "Backup created locally: $OUTPUT"
  echo "WARNING: local files are not durable on an ephemeral service; copy this backup to durable storage."
fi
