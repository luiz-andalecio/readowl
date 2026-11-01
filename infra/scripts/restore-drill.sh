#!/bin/sh
# Ensaio: restaura o backup em uma cópia temporária, em outra porta, confere os dados e cronometra. Não toca no banco em
# uso. Backup que nunca foi restaurado não conta como backup, então rode antes de abrir e a cada três meses:
#
#   docker compose -f infra/compose.yaml --env-file <arquivo> exec -T --user postgres postgres sh /readowl/scripts/restore-drill.sh
set -eu

HERE="$(dirname "$0")"
DRILL_DIR="${DRILL_DIR:-/tmp/readowl-restore-drill}"
DRILL_PORT="${DRILL_PORT:-55439}"
DRILL_DB="${DRILL_DB:-${DB_NAME:?defina DB_NAME}}"
DRILL_USER="${DRILL_USER:-postgres}"
SERVER_OPTIONS="-c port=$DRILL_PORT -c listen_addresses=localhost"

cleanup() {
    pg_ctl --pgdata="$DRILL_DIR" --mode=immediate --wait stop >/dev/null 2>&1 || true
    rm -rf "$DRILL_DIR" "$DRILL_DIR.log"
}
trap cleanup EXIT

START="$(date +%s)"
RESTORE_DIR="$DRILL_DIR" sh "$HERE/restore.sh" "$@"
pg_ctl --pgdata="$DRILL_DIR" --options="$SERVER_OPTIONS" --log="$DRILL_DIR.log" --wait --timeout=600 start

sql() {
    psql --no-psqlrc --quiet --tuples-only --no-align --host=localhost --port="$DRILL_PORT" --username="$DRILL_USER" \
        --dbname="$DRILL_DB" --command="$1"
}

# Com recovery_target_action = promote, a cópia aceita escrita quando termina de aplicar o WAL.
ATTEMPTS=0
while [ "$(sql 'SELECT pg_is_in_recovery()')" = t ]; do
    ATTEMPTS=$((ATTEMPTS + 1))
    if [ "$ATTEMPTS" -gt 600 ]; then
        echo 'FALHA a recuperação não terminou em 10 minutos.' >&2
        exit 1
    fi
    sleep 1
done
ELAPSED=$(($(date +%s) - START))

FAILED_MIGRATIONS="$(sql 'SELECT count(*) FROM flyway_schema_history WHERE NOT success')"
if [ "$FAILED_MIGRATIONS" != 0 ]; then
    echo "FALHA $FAILED_MIGRATIONS migration(ões) com erro na cópia restaurada." >&2
    exit 1
fi

echo "Versão do schema: $(sql 'SELECT max(version::int) FROM flyway_schema_history WHERE version IS NOT NULL')"
for table in users books chapters chapter_revisions chapter_drafts comments audit_log; do
    echo "$table: $(sql "SELECT count(*) FROM $table")"
done
echo "Última auditoria: $(sql 'SELECT max(created_at) FROM audit_log')"
echo "Restauração concluída em ${ELAPSED}s."
