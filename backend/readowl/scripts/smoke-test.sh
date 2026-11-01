#!/bin/sh
# Sobe o JAR empacotado com o perfil prod e confere o que os testes automaticos nao cobrem: a inicializacao real,
# os cookies Secure e os logs em ECS. APAGA O SCHEMA DO BANCO, entao so aceita um banco cujo nome termine em _test.
#
# Uso, a partir de backend/readowl, depois de mvnw package:
#   DB_USER=readowl DB_PASSWORD=... sh scripts/smoke-test.sh
#
# PSQL e JAVA podem apontar para os executaveis, quando nao estiverem no PATH.
set -u

DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
DB_NAME="${DB_NAME:-readowl_test}"
PSQL="${PSQL:-psql}"
JAVA="${JAVA:-java}"
PORT="${SMOKE_PORT:-18080}"
BASE="http://localhost:$PORT"
WORK="$(mktemp -d)"
LOG="$WORK/app.log"
EMAIL='smoke@example.com'
PASSWORD='senha-forte-123'
FAILURES=0

case "$DB_NAME" in
    *_test) ;;
    *) echo "Recusado: o schema de '$DB_NAME' seria apagado e o nome nao termina em _test." >&2; exit 2 ;;
esac

JAR="$(ls target/readowl-*.jar 2>/dev/null | grep -v original | head -1)"
if [ -z "$JAR" ]; then
    echo "JAR nao encontrado. Rode mvnw package em backend/readowl." >&2
    exit 2
fi

export PGPASSWORD="$DB_PASSWORD"
sql() {
    "$PSQL" -q -At -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -c "$1"
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

sql 'SET client_min_messages = warning; DROP SCHEMA public CASCADE; CREATE SCHEMA public;' || exit 2

export DB_HOST DB_PORT DB_NAME
export APP_PUBLIC_URL='https://readowl.test' MAIL_FROM='no-reply@readowl.test' SMTP_HOST='localhost' SMTP_PORT='2525'
export TRUSTED_PROXIES='127\.0\.0\.1|0:0:0:0:0:0:0:1'
"$JAVA" -jar "$JAR" --spring.profiles.active=prod --server.port="$PORT" > "$LOG" 2>&1 &
APP=$!
trap 'kill "$APP" 2>/dev/null; rm -rf "$WORK"' EXIT

UP=''
for attempt in $(seq 1 60); do
    if [ "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/actuator/health")" = '200' ]; then
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
echo 'ok    a aplicacao subiu'

check 'nenhuma migration falhou' '0' "$(sql 'SELECT count(*) FROM flyway_schema_history WHERE NOT success')"

request GET /api/v1/books
check 'catalogo anonimo' '200' "$STATUS"
check 'visitante nao recebe cookie' '' "$(printf '%s\n' "$HEADERS" | grep -i '^set-cookie:')"
contains 'catalogo pode ficar em cache' 'max-age=30' "$HEADERS"

request GET /api/v1/users/me
check 'rota privada anonima' '401' "$STATUS"
request GET /actuator/metrics
check 'metricas nao expostas' '401' "$STATUS"

request GET /api/v1/auth/csrf
contains 'cookie CSRF com Secure' 'Secure' "$(cookie_line XSRF-TOKEN)"
TOKEN="$(cookie_value XSRF-TOKEN)"

REGISTER="{\"name\":\"Smoke\",\"username\":\"smoke-user\",\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}"
request POST /api/v1/auth/register "$REGISTER"
check 'POST sem CSRF' '403' "$STATUS"
request POST /api/v1/auth/register "$REGISTER" "XSRF-TOKEN=$TOKEN" "$TOKEN"
check 'cadastro' '202' "$STATUS"
sql "UPDATE users SET email_verified_at = now(), role = 'ROOT' WHERE email = '$EMAIL'"

request POST /api/v1/auth/login "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}" "XSRF-TOKEN=$TOKEN" "$TOKEN"
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
TOKEN="$(cookie_value XSRF-TOKEN)"
BOTH="$SESSION; XSRF-TOKEN=$TOKEN"
request POST /api/v1/admin/banners '{"name":"Teste","imageUrl":"https://cdn.example.com/a.png","linkUrl":"https://readowl.test","position":1,"enabled":true}' "$BOTH" "$TOKEN"
check 'banner criado' '201' "$STATUS"
check 'acao na auditoria' 'POST /api/v1/admin/banners|SUCCESS' "$(sql 'SELECT action, outcome FROM audit_log')"

request POST /api/v1/auth/logout '{}' "$BOTH" "$TOKEN"
check 'logout' '204' "$STATUS"
request GET /api/v1/users/me '' "$SESSION"
check 'sessao encerrada' '401' "$STATUS"

# Um ciclo do worker, com o SMTP fora do ar: o e-mail da conta precisa continuar na fila.
sleep 17
check 'e-mail continua na fila' '1' "$(sql 'SELECT count(*) FROM account_mail_queue')"

contains 'logs em ECS' '"@timestamp"' "$(head -40 "$LOG")"
check 'senha fora dos logs' '0' "$(grep -c -F "$PASSWORD" "$LOG")"
check 'e-mail fora dos logs' '0' "$(grep -c -F "$EMAIL" "$LOG")"
check 'token CSRF fora dos logs' '0' "$(grep -c -F "$TOKEN" "$LOG")"
# O unico erro esperado e o aviso de que o SMTP nao esta acessivel.
check 'sem erros inesperados nos logs' '0' "$(grep '"level":"ERROR"' "$LOG" | grep -c -v 'MailConnectionCheck')"

if [ "$FAILURES" -gt 0 ]; then
    echo "$FAILURES verificacao(oes) falharam."
    exit 1
fi
echo 'Tudo certo.'
