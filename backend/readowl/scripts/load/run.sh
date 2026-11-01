#!/bin/sh
# Teste de carga do JAR de produção, em um banco próprio, criado e apagado aqui. Não passa pelo NGINX. Precisa de um
# superusuário. Uso, a partir de backend/readowl, depois de mvnw package:
#
#   DB_ADMIN_USER=postgres DB_ADMIN_PASSWORD=... sh scripts/load/run.sh prepare   Cria o banco com os dados sintéticos
#   DB_ADMIN_USER=postgres DB_ADMIN_PASSWORD=... sh scripts/load/run.sh test      Sobe o JAR, mede e o encerra
#   DB_ADMIN_USER=postgres DB_ADMIN_PASSWORD=... sh scripts/load/run.sh clean     Apaga o banco
#
# READERS, LOGGED, COMMENTERS, DURATION_SECONDS, THINK_MIN_MS, THINK_MAX_MS, POLL_MS e RELEASE_AT_SECONDS definem a carga.
# POOL_SIZE e TOMCAT_THREADS mudam a aplicação. CPUS e HEAP limitam a JVM ao que o servidor teria. PSQL e JAVA podem
# apontar para os executáveis.
set -u

HERE="$(dirname "$0")"
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
DB_ADMIN_USER="${DB_ADMIN_USER:?defina DB_ADMIN_USER, um superusuário}"
DB_ADMIN_PASSWORD="${DB_ADMIN_PASSWORD:?defina DB_ADMIN_PASSWORD}"
PSQL="${PSQL:-psql}"
JAVA="${JAVA:-java}"
PORT="${LOAD_PORT:-18080}"
MANAGEMENT_PORT="${LOAD_MANAGEMENT_PORT:-18081}"
CPUS="${CPUS:-2}"
HEAP="${HEAP:-700m}"
ACCOUNTS="${ACCOUNTS:-20000}"
AUTHORS="${AUTHORS:-500}"
BOOKS="${BOOKS:-5000}"
CHAPTERS_PER_BOOK="${CHAPTERS_PER_BOOK:-10}"
LOAD_PASSWORD='senha-de-carga-123'
WORK="${LOAD_WORK:-${TMPDIR:-/tmp}/readowl-load}"
LOG="$WORK/app.log"

export DB_HOST DB_PORT LOAD_PASSWORD
export DB_NAME=readowl_load_test
export DB_USER=readowl_load_app
export DB_PASSWORD=carga-app-local
export DB_MIGRATION_USER=readowl_load_migrator
export DB_MIGRATION_PASSWORD=carga-migrator-local
export DB_SSL_MODE="${DB_SSL_MODE:-prefer}"
export APP_PUBLIC_URL='https://readowl.test' MAIL_FROM='no-reply@readowl.test' SMTP_HOST='localhost' SMTP_PORT='2525'
export TRUSTED_PROXIES='127\.0\.0\.1|0:0:0:0:0:0:0:1' MANAGEMENT_PORT PRIVILEGED_GOOGLE_LOGIN_REQUIRED=false

admin() {
    PGPASSWORD="$DB_ADMIN_PASSWORD" "$PSQL" -q -At -v ON_ERROR_STOP=1 -h "$DB_HOST" -p "$DB_PORT" -U "$DB_ADMIN_USER" "$@"
}

sql() {
    admin -d "$DB_NAME" -c "$1"
}

extract() {
    JAR="$(ls target/readowl-*.jar 2>/dev/null | grep -v original | head -1)"
    if [ -z "$JAR" ]; then
        echo 'JAR não encontrado. Rode mvnw package em backend/readowl.' >&2
        exit 2
    fi
    rm -rf "$WORK/app"
    mkdir -p "$WORK"
    "$JAVA" -Djarmode=tools -jar "$JAR" extract --destination "$WORK/app" > /dev/null || exit 2
    EXTRACTED="$WORK/app/$(basename "$JAR")"
}

start_app() {
    # shellcheck disable=SC2086
    "$JAVA" "-XX:ActiveProcessorCount=$CPUS" "-Xmx$HEAP" -XX:+ExitOnOutOfMemoryError -jar "$EXTRACTED" \
        --spring.profiles.active=prod --server.port="$PORT" ${POOL_SIZE:+--spring.datasource.hikari.maximum-pool-size="$POOL_SIZE"} \
        ${TOMCAT_THREADS:+--server.tomcat.threads.max="$TOMCAT_THREADS"} > "$LOG" 2>&1 &
    APP=$!
    trap 'kill "$APP" 2>/dev/null' EXIT
    for attempt in $(seq 1 90); do
        if [ "$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:$PORT/readyz")" = '200' ]; then
            return 0
        fi
        sleep 1
    done
    echo 'A aplicação não subiu:' >&2
    tail -30 "$LOG" >&2
    exit 1
}

drop_database() {
    PGOPTIONS='-c client_min_messages=warning' admin -d postgres -c "DROP DATABASE IF EXISTS $DB_NAME WITH (FORCE)" \
        -c "DROP ROLE IF EXISTS $DB_USER" -c "DROP ROLE IF EXISTS $DB_MIGRATION_USER"
}

case "${1:-}" in
    prepare)
        drop_database || exit 2
        admin -d postgres -f "$HERE/../../../../infra/postgres/roles.sql" || exit 2
        extract
        "$JAVA" -cp "$EXTRACTED" br.com.readowl.readowl.shared.config.DatabaseMigration > "$WORK/migration.log" 2>&1 || { tail -20 "$WORK/migration.log"; exit 1; }
        start_app
        # A conta "modelo", pela API, dá o hash de senha e a versão dos termos às contas sintéticas.

        TERMS="$(curl -s "http://localhost:$PORT/api/v1/auth/terms" | sed -E 's/.*"version":"([^"]+)".*/\1/')"
        TOKEN="$(curl -s -D - -o /dev/null -H 'X-Forwarded-Proto: https' "http://localhost:$PORT/api/v1/auth/csrf" | tr -d '\r' | grep -i '^set-cookie: __Host-XSRF' | sed -E 's/^[^=]*=([^;]*).*/\1/')"
        STATUS="$(curl -s -o /dev/null -w '%{http_code}' -X POST -H 'X-Forwarded-Proto: https' -H 'Content-Type: application/json' \
            -H "X-XSRF-TOKEN: $TOKEN" -H "Cookie: __Host-XSRF-TOKEN=$TOKEN" \
            -d "{\"name\":\"Modelo\",\"username\":\"carga-modelo\",\"email\":\"carga-modelo@example.com\",\"password\":\"$LOAD_PASSWORD\",\"termsVersion\":\"$TERMS\",\"ageConfirmed\":true}" \
            "http://localhost:$PORT/api/v1/auth/register")"
        [ "$STATUS" = 202 ] || { echo "Cadastro da conta modelo: $STATUS"; exit 1; }
        kill "$APP" 2>/dev/null
        wait "$APP" 2>/dev/null
        trap - EXIT
        sql 'SET client_min_messages = warning; DELETE FROM account_mail_queue'
        START="$(date +%s)"
        admin -d "$DB_NAME" -v accounts="$ACCOUNTS" -v authors="$AUTHORS" -v books="$BOOKS" \
            -v chapters_per_book="$CHAPTERS_PER_BOOK" -f "$HERE/seed.sql" || exit 1
        echo "Dados prontos em $(($(date +%s) - START)) s: $(sql "SELECT pg_size_pretty(pg_database_size(current_database()))")"
        ;;
    test)
        extract
        start_app
        before() { sql "SELECT COALESCE(sum(n_tup_upd), 0), COALESCE(sum(n_tup_hot_upd), 0) FROM pg_stat_user_tables WHERE relname = 'spring_session'"; }
        BEFORE="$(before)"
        "$JAVA" "$HERE/LoadTest.java"
        RESULT=$?
        sleep 1
        AFTER="$(before)"
        echo "spring_session: atualizações (total|HOT) antes $BEFORE, depois $AFTER"
        echo "Sessões: $(sql 'SELECT count(*) FROM spring_session'). Erros no log: $(grep -c '"level":"ERROR"' "$LOG"), sem contar o SMTP: $(grep '"level":"ERROR"' "$LOG" | grep -c -v 'MailConnectionCheck')"
        if [ "${RELEASE_AT_SECONDS:-0}" -gt 0 ]; then
            # Um ciclo do worker para alcançar os seguidores.
            sleep 17
            echo "Notificações do lançamento: $(sql "SELECT count(*) FROM notifications n JOIN chapters c ON c.id = n.chapter_id WHERE c.slug LIKE 'lancamento-%'")"
        fi
        exit "$RESULT"
        ;;
    clean)
        drop_database
        rm -rf "$WORK"
        ;;
    *)
        sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'
        exit 2
        ;;
esac
