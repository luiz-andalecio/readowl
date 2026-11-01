#!/bin/sh
# Backup físico completo, conferido antes de contar como válido. Junto com o WAL arquivado, permite restaurar o banco em
# qualquer ponto depois dele. Rode uma vez por dia, dentro do contêiner do PostgreSQL:
#
#   docker compose -f infra/compose.yaml --env-file <arquivo> exec -T --user postgres postgres sh /readowl/scripts/backup.sh
#
# A pasta fica no servidor do banco e não protege contra a perda dele: copie-a para fora em seguida (docs/operations-runbook.md).
set -eu

BACKUP_DIR="${BACKUP_DIR:-/var/lib/postgresql/backups}"
BACKUP_KEEP="${BACKUP_KEEP:-7}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
TARGET="$BACKUP_DIR/base/$STAMP"

mkdir -p "$BACKUP_DIR/base" "$BACKUP_DIR/wal"

# O nome com .partial só muda depois da conferência: uma pasta sem o sufixo está completa.
pg_basebackup --pgdata="$TARGET.partial" --format=tar --gzip --wal-method=none --checkpoint=fast \
    --label="readowl-$STAMP" ${PGUSER:+--username="$PGUSER"} --no-password
pg_verifybackup --no-parse-wal "$TARGET.partial"

# O último .backup do arquivo marca onde este backup começa. A limpeza do WAL precisa dele.
ls -1 "$BACKUP_DIR/wal" | grep '\.backup$' | sort | tail -n 1 > "$TARGET.partial/first_wal"
mv "$TARGET.partial" "$TARGET"

# Mantém os BACKUP_KEEP mais recentes, entre eles o que acabou de ficar pronto.
ls -1 "$BACKUP_DIR/base" | grep -v '\.partial$' | sort -r | tail -n +"$((BACKUP_KEEP + 1))" | while read -r expired; do
    rm -rf "${BACKUP_DIR:?}/base/$expired"
done

OLDEST="$(ls -1 "$BACKUP_DIR/base" | grep -v '\.partial$' | sort | head -n 1)"
FIRST_WAL="$(cat "$BACKUP_DIR/base/$OLDEST/first_wal")"
if [ -n "$FIRST_WAL" ]; then
    pg_archivecleanup "$BACKUP_DIR/wal" "$FIRST_WAL"
fi

echo "Backup pronto: $TARGET ($(du -sh "$TARGET" | cut -f1)). Retidos: $(ls -1 "$BACKUP_DIR/base" | wc -l)."
