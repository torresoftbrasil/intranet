#!/usr/bin/env bash
set -euo pipefail
umask 077

TARGET_SHA="${1:?Informe o SHA da versão a publicar}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
test -f .env || { echo '.env não encontrado no servidor.' >&2; exit 1; }
set -a
source .env
set +a
DATA_DIR="${HUB_DATA_DIR:-/srv/torresoft-data/hub}"
BACKUP_KEY=/etc/hub/backup.key
test -s "$BACKUP_KEY" || { echo 'Chave de backup ausente.' >&2; exit 1; }
mkdir -p "$DATA_DIR/postgres" "$DATA_DIR/uploads" "$DATA_DIR/backups"
exec 9>"$DATA_DIR/deploy.lock"
flock -w 1800 9
COMPOSE=(docker compose --env-file .env -f deploy/compose.yaml)
OLD_SHA="$(git rev-parse HEAD)"
BACKUP=""
RELEASE_STARTED=0
MIGRATION_ATTEMPTED=0

restore_previous() {
  local result=$?
  if (( result == 0 )); then return; fi
  if (( RELEASE_STARTED == 0 )); then exit "$result"; fi
  echo "Falha na publicação; restaurando $OLD_SHA" >&2
  "${COMPOSE[@]}" stop web api || true
  if (( MIGRATION_ATTEMPTED == 1 )) && [[ -n "$BACKUP" && -f "$BACKUP" ]]; then
    "${COMPOSE[@]}" up -d postgres
    if ! sha256sum --check "$BACKUP.sha256" >/dev/null ||
       ! openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -pass file:"$BACKUP_KEY" -in "$BACKUP" |
         "${COMPOSE[@]}" exec -T postgres pg_restore --clean --if-exists --no-owner -U "$DB_USER" -d "$DB_NAME"; then
      echo "Restauração do banco falhou; serviços de aplicação permanecem parados. Backup: $BACKUP" >&2
      trap - EXIT
      exit "$result"
    fi
  fi
  git checkout --detach "$OLD_SHA"
  "${COMPOSE[@]}" build api web
  "${COMPOSE[@]}" up -d api web
  echo "Versão anterior restaurada; confira logs e saúde da aplicação." >&2
  exit "$result"
}
trap restore_previous EXIT

"${COMPOSE[@]}" up -d postgres
if "${COMPOSE[@]}" exec -T postgres psql -U "$DB_USER" -d "$DB_NAME" -Atqc 'SELECT 1' >/dev/null 2>&1; then
  BACKUP="$DATA_DIR/backups/pre-${TARGET_SHA:0:12}-$(date -u +%Y%m%d%H%M%S).dump.enc"
  "${COMPOSE[@]}" exec -T postgres pg_dump -Fc -U "$DB_USER" "$DB_NAME" |
    openssl enc -aes-256-cbc -salt -pbkdf2 -iter 200000 -pass file:"$BACKUP_KEY" -out "$BACKUP"
  chmod 600 "$BACKUP"
  test -s "$BACKUP"
  sha256sum "$BACKUP" > "$BACKUP.sha256"
  sha256sum --check "$BACKUP.sha256" >/dev/null
  openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -pass file:"$BACKUP_KEY" -in "$BACKUP" |
    "${COMPOSE[@]}" exec -T postgres pg_restore --list >/dev/null
fi
git fetch origin main
git cat-file -e "$TARGET_SHA^{commit}"
test "$TARGET_SHA" = "$(git rev-parse origin/main)" || { echo 'SHA não é o HEAD atual da main.' >&2; exit 1; }
git checkout --detach "$TARGET_SHA"
RELEASE_STARTED=1
"${COMPOSE[@]}" build api web
MIGRATION_ATTEMPTED=1
"${COMPOSE[@]}" up -d --remove-orphans
for _ in {1..30}; do
  if curl -fsS "http://127.0.0.1:${WEB_PORT:-8083}/healthz" >/dev/null && \
     curl -fsS "http://127.0.0.1:${WEB_PORT:-8083}/api/csrf" >/dev/null; then
    trap - EXIT
    echo "Hub publicado: $TARGET_SHA"
    exit 0
  fi
  sleep 2
done
echo 'A versão nova não respondeu ao health check.' >&2
exit 1
