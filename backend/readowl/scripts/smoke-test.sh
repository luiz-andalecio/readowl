#!/bin/sh
# Sobe o JAR como a imagem faz, com o perfil prod e usuários separados, e confere o que os testes automaticos nao
# cobrem: as migrations com outro usuario, as permissoes, os cookies Secure e os logs em ECS. Usa um banco proprio, criado
# e apagado aqui, e por isso precisa de um superusuario.
#
# Uso, a partir de backend/readowl, depois de mvnw package:
#   DB_ADMIN_USER=postgres DB_ADMIN_PASSWORD=... sh scripts/smoke-test.sh
#
# PSQL e JAVA podem apontar para os executaveis, quando nao estiverem no PATH.
set -u

DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
DB_ADMIN_USER="${DB_ADMIN_USER:?defina DB_ADMIN_USER, um superusuario}"
DB_ADMIN_PASSWORD="${DB_ADMIN_PASSWORD:?defina DB_ADMIN_PASSWORD}"
PSQL="${PSQL:-psql}"
JAVA="${JAVA:-java}"
PORT="${SMOKE_PORT:-18080}"
MANAGEMENT_PORT="${SMOKE_MANAGEMENT_PORT:-18081}"
BASE="http://localhost:$PORT"
INTERNAL="http://localhost:$MANAGEMENT_PORT"
WORK="$(mktemp -d)"
LOG="$WORK/app.log"
EMAIL='smoke@example.com'
PASSWORD='senha-forte-123'
FAILURES=0

export DB_NAME=readowl_smoke_test
export DB_USER=readowl_smoke_app
export DB_PASSWORD="$(od -An -tx1 -N16 /dev/urandom | tr -d ' \n')"
export DB_MIGRATION_USER=readowl_smoke_migrator
export DB_MIGRATION_PASSWORD="$(od -An -tx1 -N16 /dev/urandom | tr -d ' \n')"
export DB_HOST DB_PORT DB_SSL_MODE="${DB_SSL_MODE:-prefer}"

JAR="$(ls target/readowl-*.jar 2>/dev/null | grep -v original | head -1)"
if [ -z "$JAR" ]; then
    echo "JAR nao encontrado. Rode mvnw package em backend/readowl." >&2
    exit 2
fi

admin() {
    PGPASSWORD="$DB_ADMIN_PASSWORD" "$PSQL" -q -At -v ON_ERROR_STOP=1 -h "$DB_HOST" -p "$DB_PORT" -U "$DB_ADMIN_USER" "$@"
}

sql() {
    admin -d "$DB_NAME" -c "$1"
}

drop_database() {
    PGOPTIONS='-c client_min_messages=warning' admin -d postgres -c "DROP DATABASE IF EXISTS $DB_NAME WITH (FORCE)" \
        -c "DROP ROLE IF EXISTS $DB_USER" -c "DROP ROLE IF EXISTS $DB_MIGRATION_USER"
}

check() {
    if [ "$2" = "$3" ]; then
        echo "ok    $1"
    else
        echo "FALHA $1: esperado '$2', veio '$3'"
        FAILURES=$((FAILURES + 1))
    fi
}

contains() {
    case "$3" in
        *"$2"*) echo "ok    $1" ;;
        *) echo "FALHA $1: nao contem '$2'"; FAILURES=$((FAILURES + 1)) ;;
    esac
}

# request METODO CAMINHO [CORPO] [COOKIES] [TOKEN]: deixa o resultado em STATUS e HEADERS.
request() {
    set -- "$1" "$2" "${3:-}" "${4:-}" "${5:-}"
    STATUS="$(curl -s -o "$WORK/body" -D "$WORK/headers" -w '%{http_code}' -X "$1" "$BASE$2" \
        -H 'X-Forwarded-Proto: https' \
        ${4:+-H "Cookie: $4"} \
        ${5:+-H "X-XSRF-TOKEN: $5"} \
        ${3:+-H 'Content-Type: application/json' -d "$3"})"
    HEADERS="$(tr -d '\r' < "$WORK/headers")"
}

cookie_value() {
    printf '%s\n' "$HEADERS" | grep -i "^set-cookie: $1=" | head -1 | sed -E "s/^[^:]*: $1=([^;]*).*/\1/"
}

cookie_line() {
    printf '%s\n' "$HEADERS" | grep -i "^set-cookie: $1=" | head -1
}

# Quem nao pode fazer algo com o usuario da aplicacao precisa receber erro do banco.
refused_to_app() {
    if PGPASSWORD="$DB_PASSWORD" "$PSQL" -q -At -v ON_ERROR_STOP=1 -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -c "$1" > /dev/null 2>&1; then
        echo "FALHA o usuario da aplicacao conseguiu: $1"
        FAILURES=$((FAILURES + 1))
    else
        echo "ok    o usuario da aplicacao nao consegue: $1"
    fi
}

trap 'kill "${APP:-}" 2>/dev/null; drop_database; rm -rf "$WORK"' EXIT

drop_database || exit 2
admin -d postgres -f ../../infra/postgres/roles.sql || exit 2

# Mesmo formato da imagem: o JAR extraido, com as bibliotecas ao lado.
"$JAVA" -Djarmode=tools -jar "$JAR" extract --destination "$WORK/app" > /dev/null || exit 2
EXTRACTED="$WORK/app/$(basename "$JAR")"
MIGRATION=br.com.readowl.readowl.shared.config.DatabaseMigration

if "$JAVA" -cp "$EXTRACTED" "$MIGRATION" > "$WORK/migration.log" 2>&1; then
    echo 'ok    as migrations rodam com o usuario proprio'
else
    echo 'FALHA nas migrations:'
    tail -30 "$WORK/migration.log"
    exit 1
fi
check 'nenhuma migration falhou' '0' "$(sql 'SELECT count(*) FROM flyway_schema_history WHERE NOT success')"
"$JAVA" -cp "$EXTRACTED" "$MIGRATION" > "$WORK/migration2.log" 2>&1
check 'repetir as migrations nao muda nada' '0' "$?"
check 'as tabelas pertencem ao usuario de migration' '0' \
    "$(sql "SELECT count(*) FROM pg_tables WHERE schemaname = 'public' AND tableowner <> '$DB_MIGRATION_USER'")"

export APP_PUBLIC_URL='https://readowl.test' MAIL_FROM='no-reply@readowl.test' SMTP_HOST='localhost' SMTP_PORT='2525'
export TRUSTED_PROXIES='127\.0\.0\.1|0:0:0:0:0:0:0:1' MANAGEMENT_PORT
# A credencial de migration nao chega a aplicacao, como no Compose.
env -u DB_MIGRATION_USER -u DB_MIGRATION_PASSWORD "$JAVA" -jar "$EXTRACTED" --spring.profiles.active=prod --server.port="$PORT" > "$LOG" 2>&1 &
APP=$!

UP=''
for attempt in $(seq 1 60); do
    if [ "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/readyz")" = '200' ]; then
        UP=yes
        break
    fi
    sleep 1
done
if [ -z "$UP" ]; then
    echo 'FALHA a aplicacao nao subiu:'
    tail -30 "$LOG"
    exit 1
fi
echo 'ok    a aplicacao subiu com o usuario sem acesso ao schema'

request GET /api/v1/books
check 'catalogo anonimo' '200' "$STATUS"
check 'visitante nao recebe cookie' '' "$(printf '%s\n' "$HEADERS" | grep -i '^set-cookie:')"
contains 'catalogo pode ficar em cache' 'max-age=30' "$HEADERS"

request GET /api/v1/users/me
check 'rota privada anonima' '401' "$STATUS"
request GET /actuator/health
check 'saude fora da porta publica' '401' "$STATUS"
request GET /actuator/metrics
check 'metricas fora da porta publica' '401' "$STATUS"

request GET /api/v1/auth/csrf
contains 'cookie CSRF com Secure' 'Secure' "$(cookie_line __Host-XSRF-TOKEN)"
contains 'cookie CSRF na raiz' 'Path=/' "$(cookie_line __Host-XSRF-TOKEN)"
TOKEN="$(cookie_value __Host-XSRF-TOKEN)"

REGISTER="{\"name\":\"Smoke\",\"username\":\"smoke-user\",\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}"
request POST /api/v1/auth/register "$REGISTER"
check 'POST sem CSRF' '403' "$STATUS"
request POST /api/v1/auth/register "$REGISTER" "__Host-XSRF-TOKEN=$TOKEN" "$TOKEN"
check 'cadastro' '202' "$STATUS"
sql "UPDATE users SET email_verified_at = now(), role = 'ROOT' WHERE email = '$EMAIL'"

request POST /api/v1/auth/login "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}" "__Host-XSRF-TOKEN=$TOKEN" "$TOKEN"
check 'login' '200' "$STATUS"
SESSION_COOKIE="$(cookie_line __Host-SESSION)"
contains 'cookie de sessao com Secure' 'Secure' "$SESSION_COOKIE"
contains 'cookie de sessao com HttpOnly' 'HttpOnly' "$SESSION_COOKIE"
contains 'cookie de sessao com SameSite' 'SameSite=Lax' "$SESSION_COOKIE"
contains 'cookie do navegador so na rota de login' 'Path=/api/v1/auth/login' "$(cookie_line READOWL_DEVICE)"
SESSION="__Host-SESSION=$(cookie_value __Host-SESSION)"

request GET /api/v1/users/me '' "$SESSION"
check 'perfil' '200' "$STATUS"

request GET /api/v1/auth/csrf '' "$SESSION"
TOKEN="$(cookie_value __Host-XSRF-TOKEN)"
BOTH="$SESSION; __Host-XSRF-TOKEN=$TOKEN"
request POST /api/v1/admin/banners '{"name":"Teste","imageUrl":"https://cdn.example.com/a.png","linkUrl":"https://readowl.test","position":1,"enabled":true}' "$BOTH" "$TOKEN"
check 'banner criado' '201' "$STATUS"
check 'acao na auditoria' 'POST /api/v1/admin/banners|SUCCESS' "$(sql 'SELECT action, outcome FROM audit_log')"

refused_to_app 'DELETE FROM audit_log'
refused_to_app 'UPDATE audit_log SET outcome = outcome'
refused_to_app 'TRUNCATE audit_log'
refused_to_app 'DROP TABLE users'
refused_to_app 'ALTER TABLE users ADD COLUMN x int'
refused_to_app 'CREATE TABLE x (id int)'
refused_to_app 'SELECT count(*) FROM flyway_schema_history'
check 'a auditoria continua la' '1' "$(sql 'SELECT count(*) FROM audit_log')"

request POST /api/v1/auth/logout '{}' "$BOTH" "$TOKEN"
check 'logout' '204' "$STATUS"
request GET /api/v1/users/me '' "$SESSION"
check 'sessao encerrada' '401' "$STATUS"

# Um ciclo do worker, com o SMTP fora do ar: o e-mail da conta precisa continuar na fila.
sleep 17
check 'e-mail continua na fila' '1' "$(sql 'SELECT count(*) FROM account_mail_queue')"

if [ "$DB_SSL_MODE" = verify-full ]; then
    check 'a aplicacao conecta com TLS' 'f' "$(sql "SELECT bool_or(NOT s.ssl) FROM pg_stat_ssl s JOIN pg_stat_activity a USING (pid) WHERE a.usename = '$DB_USER'")"
fi

check 'saude na porta interna' '200' "$(curl -s -o /dev/null -w '%{http_code}' "$INTERNAL/actuator/health/readiness")"
contains 'fila de e-mails nas metricas' '"value":1.0' "$(curl -s "$INTERNAL/actuator/metrics/readowl.account_mail.pending")"
for metric in http.server.requests hikaricp.connections.pending readowl.outbox.failed; do
    check "metrica $metric" '200' "$(curl -s -o /dev/null -w '%{http_code}' "$INTERNAL/actuator/metrics/$metric")"
done
check 'configuracao fora da porta interna' '401' "$(curl -s -o /dev/null -w '%{http_code}' "$INTERNAL/actuator/env")"

contains 'logs em ECS' '"@timestamp"' "$(head -40 "$LOG")"
check 'senha fora dos logs' '0' "$(grep -c -F "$PASSWORD" "$LOG")"
check 'e-mail fora dos logs' '0' "$(grep -c -F "$EMAIL" "$LOG")"
check 'token CSRF fora dos logs' '0' "$(grep -c -F "$TOKEN" "$LOG")"
check 'senha do banco fora dos logs' '0' "$(cat "$LOG" "$WORK/migration.log" | grep -c -F "$DB_MIGRATION_PASSWORD")"
# O unico erro esperado e o aviso de que o SMTP nao esta acessivel.
check 'sem erros inesperados nos logs' '0' "$(grep '"level":"ERROR"' "$LOG" | grep -c -v 'MailConnectionCheck')"

if [ "$FAILURES" -gt 0 ]; then
    echo "$FAILURES verificacao(oes) falharam."
    exit 1
fi
echo 'Tudo certo.'
