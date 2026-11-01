#!/bin/sh
# Homologação a partir do repositório. Exige Docker com Compose e openssl.
#
#   sh infra/homolog.sh up        Gera senhas e certificado na primeira vez, constrói a imagem e sobe tudo
#   sh infra/homolog.sh check     Confere o ambiente em execução
#   sh infra/homolog.sh backup    Faz um backup
#   sh infra/homolog.sh drill     Faz um backup e o restaura em uma cópia temporária, cronometrando
#   sh infra/homolog.sh logs      Mostra os logs
#   sh infra/homolog.sh down      Para tudo e mantém os dados
#   sh infra/homolog.sh destroy   Para tudo e APAGA os dados, os backups e as senhas de homologação
set -eu

# Caminhos relativos a esta pasta: o repositório pode estar em uma pasta com espaços no nome.
cd "$(dirname "$0")"
ENV_FILE=.homolog/env
COMPOSE='docker compose --project-name readowl-homolog --env-file .homolog/env -f compose.yaml -f compose.homolog.yaml'
HTTP_PORT="${HTTP_PORT:-8080}"
HTTPS_PORT="${HTTPS_PORT:-8443}"

prepare() {
    mkdir -p .homolog/certs
    if [ ! -f "$ENV_FILE" ]; then
        umask 077
        cat > "$ENV_FILE" <<EOF
READOWL_IMAGE=readowl-backend:homolog
SERVER_NAME=localhost
APP_PUBLIC_URL=https://localhost:$HTTPS_PORT
TLS_CERT_DIR=./.homolog/certs
HTTP_PORT=$HTTP_PORT
HTTPS_PORT=$HTTPS_PORT
POSTGRES_PASSWORD=$(openssl rand -hex 24)
DB_NAME=readowl
DB_SSL_MODE=disable
DB_USER=readowl_app
DB_PASSWORD=$(openssl rand -hex 24)
DB_MIGRATION_USER=readowl_migrator
DB_MIGRATION_PASSWORD=$(openssl rand -hex 24)
MAIL_FROM=no-reply@readowl.test
SMTP_HOST=mailpit
SMTP_PORT=1025
EOF
        umask 022
    fi
    if [ ! -f .homolog/certs/fullchain.pem ]; then
        # No Git Bash do Windows, a variável impede que /CN=localhost seja tratado como caminho de arquivo.
        MSYS_NO_PATHCONV=1 openssl req -x509 -newkey rsa:2048 -nodes -days 365 -subj '/CN=localhost' -addext 'subjectAltName=DNS:localhost' \
            -keyout .homolog/certs/privkey.pem -out .homolog/certs/fullchain.pem 2>/dev/null
        # Certificado descartável, lido pelo usuário do NGINX. Em produção, a chave pertence só ao usuário 101.
        chmod 644 .homolog/certs/privkey.pem
    fi
}

setting() {
    grep "^$1=" "$ENV_FILE" | cut -d= -f2-
}

check() {
    FAILURES=0
    for service in app nginx; do
        if [ "$($COMPOSE exec -T "$service" id -u | tr -d '\r')" = 0 ]; then
            echo "FALHA $service roda como root"
            FAILURES=$((FAILURES + 1))
        else
            echo "ok    $service sem root"
        fi
    done

    if [ -n "$($COMPOSE port app 8081 2>/dev/null)" ]; then
        echo 'FALHA a porta interna da aplicação está publicada'
        FAILURES=$((FAILURES + 1))
    else
        echo 'ok    porta interna não publicada'
    fi

    for forbidden in 'DELETE FROM audit_log' 'UPDATE audit_log SET outcome = outcome' 'TRUNCATE audit_log' \
        'DROP TABLE users' 'CREATE TABLE x (id int)' 'SELECT count(*) FROM flyway_schema_history'; do
        if $COMPOSE exec -T -e PGPASSWORD="$(setting DB_PASSWORD)" postgres \
            psql -At -v ON_ERROR_STOP=1 -h localhost -U "$(setting DB_USER)" -d "$(setting DB_NAME)" -c "$forbidden" >/dev/null 2>&1; then
            echo "FALHA o usuário da aplicação conseguiu: $forbidden"
            FAILURES=$((FAILURES + 1))
        else
            echo "ok    o usuário da aplicação não consegue: $forbidden"
        fi
    done

    # Com o SMTP de teste, a confirmação de cadastro chega à caixa de entrada.
    CHECK_SQL="$COMPOSE exec -T postgres psql -At -U postgres -d $(setting DB_NAME) -c"
    export CHECK_SQL
    BASE_URL="https://localhost:$(setting HTTPS_PORT)" HTTP_URL="http://localhost:$(setting HTTP_PORT)" \
        MAILPIT_URL="http://localhost:${MAILPIT_PORT:-8025}" sh scripts/check-proxy.sh || FAILURES=$((FAILURES + 1))

    if [ "$FAILURES" -gt 0 ]; then
        exit 1
    fi
    echo 'Homologação conferida.'
}

case "${1:-}" in
    up)
        prepare
        $COMPOSE up --detach --build --wait
        echo "No ar: $(setting APP_PUBLIC_URL) (certificado autoassinado). E-mails: http://localhost:${MAILPIT_PORT:-8025}"
        ;;
    check) check ;;
    backup) $COMPOSE exec -T --user postgres postgres sh /readowl/scripts/backup.sh ;;
    drill)
        $COMPOSE exec -T --user postgres postgres sh /readowl/scripts/backup.sh
        $COMPOSE exec -T --user postgres postgres sh /readowl/scripts/restore-drill.sh
        ;;
    logs) $COMPOSE logs --tail=200 ;;
    down) $COMPOSE down ;;
    destroy)
        $COMPOSE down --volumes
        rm -rf .homolog
        ;;
    *)
        sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'
        exit 2
        ;;
esac
