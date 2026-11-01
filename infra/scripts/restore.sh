#!/bin/sh
# Prepara uma pasta de dados a partir de um backup, sem iniciar o banco nem tocar no banco em uso.
#
#   RESTORE_DIR=<pasta nova> sh restore.sh [backup]
#
# Sem o nome do backup, usa o mais recente. RECOVERY_TARGET_TIME restaura até um instante, como
# '2026-11-01 12:00:00+00'; sem ele, até o último WAL arquivado.
set -eu

BACKUP_DIR="${BACKUP_DIR:-/var/lib/postgresql/backups}"
RESTORE_DIR="${RESTORE_DIR:?defina RESTORE_DIR, uma pasta nova para os dados restaurados}"
# Executado pelo servidor durante a recuperação. Precisa de outro valor quando o servidor roda no Windows.
RESTORE_COMMAND="${RESTORE_COMMAND:-cp $BACKUP_DIR/wal/%f %p}"
BACKUP="${1:-$(ls -1 "$BACKUP_DIR/base" | grep -v '\.partial$' | sort | tail -n 1)}"

if [ -z "$BACKUP" ] || [ ! -f "$BACKUP_DIR/base/$BACKUP/base.tar.gz" ]; then
    echo "Backup não encontrado em $BACKUP_DIR/base: '$BACKUP'." >&2
    exit 2
fi
if [ -e "$RESTORE_DIR" ] && [ -n "$(ls -A "$RESTORE_DIR" 2>/dev/null)" ]; then
    echo "Recusado: $RESTORE_DIR não está vazia. A restauração nunca sobrescreve dados." >&2
    exit 2
fi

mkdir -p "$RESTORE_DIR"
chmod 700 "$RESTORE_DIR"
tar -xzf "$BACKUP_DIR/base/$BACKUP/base.tar.gz" -C "$RESTORE_DIR"

{
    echo "restore_command = '$RESTORE_COMMAND'"
    if [ -n "${RECOVERY_TARGET_TIME:-}" ]; then
        echo "recovery_target_time = '$RECOVERY_TARGET_TIME'"
    fi
    echo "recovery_target_action = 'promote'"
    # Esta cópia não pode arquivar WAL na pasta do banco original.
    echo "archive_mode = off"
} >> "$RESTORE_DIR/postgresql.auto.conf"
touch "$RESTORE_DIR/recovery.signal"

echo "Dados de $BACKUP prontos em $RESTORE_DIR."
