#!/usr/bin/env sh
set -eu

env_file=/etc/vibeali/backup.env
if [ ! -r "$env_file" ]; then
  logger -t vibeali-backup "skipped reason=missing_configuration"
  exit 0
fi
. "$env_file"
: "${SUPABASE_DB_URL:?SUPABASE_DB_URL is required}"
: "${BACKUP_ENCRYPTION_PASSPHRASE_FILE:?BACKUP_ENCRYPTION_PASSPHRASE_FILE is required}"

backup_dir=/var/backups/vibeali
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$backup_dir"
umask 077

docker run --rm postgres:17-alpine pg_dump \
  --dbname "$SUPABASE_DB_URL" \
  --format=custom \
  --no-owner \
  --no-privileges \
  | openssl enc -aes-256-cbc -pbkdf2 -salt \
      -pass "file:$BACKUP_ENCRYPTION_PASSPHRASE_FILE" \
      -out "$backup_dir/database-$stamp.dump.enc"

find "$backup_dir" -type f -name 'database-*.dump.enc' -mtime +14 -delete
logger -t vibeali-backup "completed artifact=database-$stamp.dump.enc"
