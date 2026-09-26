#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 /path/to/backup.dump" >&2
  exit 2
fi

BACKUP_FILE="$1"
: "${RESTORE_DATABASE_HOST:?RESTORE_DATABASE_HOST is required}"
: "${RESTORE_DATABASE_PORT:=5432}"
: "${RESTORE_DATABASE_NAME:?RESTORE_DATABASE_NAME is required}"
: "${RESTORE_DATABASE_USERNAME:?RESTORE_DATABASE_USERNAME is required}"
: "${RESTORE_DATABASE_PASSWORD:?RESTORE_DATABASE_PASSWORD is required}"

[[ -f "$BACKUP_FILE" ]] || { echo "Backup file not found: $BACKUP_FILE" >&2; exit 1; }
pg_restore --list "$BACKUP_FILE" >/dev/null

if [[ "${CONFIRM_RESTORE:-}" != "YES" ]]; then
  echo "Refusing restore. Set CONFIRM_RESTORE=YES only for the intended restore database." >&2
  exit 1
fi

export PGPASSWORD="$RESTORE_DATABASE_PASSWORD"
pg_restore \
  --host="$RESTORE_DATABASE_HOST" \
  --port="$RESTORE_DATABASE_PORT" \
  --username="$RESTORE_DATABASE_USERNAME" \
  --dbname="$RESTORE_DATABASE_NAME" \
  --clean \
  --if-exists \
  --no-owner \
  --no-privileges \
  "$BACKUP_FILE"

echo "Restore completed into $RESTORE_DATABASE_NAME. Run application health and accounting smoke tests before cutover."
