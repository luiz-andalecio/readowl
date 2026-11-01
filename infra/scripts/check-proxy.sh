#!/bin/sh
# Confere, de fora, o que o proxy deve garantir: HTTPS, cabeçalhos forjados sem efeito, limites e erros no formato da API.
#
#   BASE_URL=https://localhost:8443 HTTP_URL=http://localhost:8080 sh infra/scripts/check-proxy.sh
#
# CHECK_SQL, opcional, é um comando que recebe SQL em seu último argumento e permite conferir o efeito no banco.
set -u

BASE_URL="${BASE_URL:?defina BASE_URL}"
HTTP_URL="${HTTP_URL:?defina HTTP_URL}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
FAILURES=0
# Homologação usa um certificado autoassinado.
CURL="curl --silent --insecure --max-time 20"

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
        *) echo "FALHA $1: não contém '$2' em '$3'"; FAILURES=$((FAILURES + 1)) ;;
    esac
}

absent() {
    case "$3" in
        *"$2"*) echo "FALHA $1: contém '$2'"; FAILURES=$((FAILURES + 1)) ;;
        *) echo "ok    $1" ;;
    esac
}

status() {
    $CURL --output /dev/null --write-out '%{http_code}' "$@"
}

headers() {
    $CURL --output /dev/null --dump-header - "$@" | tr -d '\r'
}

check 'HTTP redireciona para HTTPS' '308' "$(status "$HTTP_URL/api/v1/genres")"
contains 'o destino é o endereço público' "$BASE_URL/api/v1/genres" "$(headers "$HTTP_URL/api/v1/genres")"
check 'domínio desconhecido não chega à aplicação' '000' "$(status -H 'Host: outro.example.com' "$BASE_URL/api/v1/genres")"

check 'API acessível' '200' "$(status "$BASE_URL/api/v1/genres")"
PUBLIC="$(headers "$BASE_URL/api/v1/genres")"
contains 'HSTS, porque a aplicação sabe que a conexão é HTTPS' 'trict-transport-security' "$(printf '%s' "$PUBLIC" | tr '[:upper:]' '[:lower:]')"
absent 'sem a versão do NGINX' 'nginx/' "$PUBLIC"
check 'monitor externo' '200' "$(status "$BASE_URL/healthz")"
check 'corpo do monitor sem detalhes' '{"status":"UP"}' "$($CURL "$BASE_URL/healthz")"

for path in actuator/health actuator/metrics actuator/env actuator/heapdump readyz livez; do
    check "/$path fica fora do proxy" '404' "$(status "$BASE_URL/$path")"
done
check 'rota desconhecida' '404' "$(status "$BASE_URL/qualquer-coisa")"

CSRF="$(headers -H 'X-Forwarded-Proto: http' -H 'X-Forwarded-Host: outro.example.com' "$BASE_URL/api/v1/auth/csrf" | grep -i '^set-cookie: __Host-XSRF-TOKEN=')"
contains 'cookie CSRF com o prefixo __Host-' '__Host-XSRF-TOKEN=' "$CSRF"
contains 'protocolo forjado não tira o Secure' 'Secure' "$CSRF"
contains 'cookie CSRF na raiz, exigência do prefixo' 'Path=/' "$CSRF"
absent 'cookie CSRF sem domínio, exigência do prefixo' 'Domain=' "$CSRF"
TOKEN="$(printf '%s' "$CSRF" | sed -E 's/^[^=]*=([^;]*).*/\1/')"

LOGIN="{\"email\":\"forjado-$$@example.com\",\"password\":\"senha-errada-123\"}"
check 'login com IP forjado responde normalmente' '401' "$(status -X POST -H 'Content-Type: application/json' \
    -H 'X-Forwarded-For: 1.2.3.4' -H "X-XSRF-TOKEN: $TOKEN" -H "Cookie: __Host-XSRF-TOKEN=$TOKEN" \
    -d "$LOGIN" "$BASE_URL/api/v1/auth/login")"
if [ -n "${CHECK_SQL:-}" ]; then
    # sha256 de 1.2.3.4, a forma como o limitador guarda o IP.
    FORGED="$(printf '1.2.3.4' | sha256sum | cut -d' ' -f1)"
    check 'o IP forjado não foi contado' '0' "$($CHECK_SQL "SELECT count(*) FROM rate_limit_buckets WHERE bucket_key = 'LOGIN_IP:$FORGED'")"
fi

if [ -n "${MAILPIT_URL:-}" ]; then
    MAIL_TO="proxy-$$@example.com"
    USERNAME="proxy-$$"
    TERMS_VERSION="$($CURL "$BASE_URL/api/v1/auth/terms" | sed -E 's/.*"version":"([^"]+)".*/\1/')"
    check 'cadastro pelo proxy' '202' "$(status -X POST -H 'Content-Type: application/json' \
        -H "X-XSRF-TOKEN: $TOKEN" -H "Cookie: __Host-XSRF-TOKEN=$TOKEN" \
        -d "{\"name\":\"Proxy\",\"username\":\"$USERNAME\",\"email\":\"$MAIL_TO\",\"password\":\"senha-forte-123\",\"termsVersion\":\"$TERMS_VERSION\",\"ageConfirmed\":true}" \
        "$BASE_URL/api/v1/auth/register")"
    # O envio acontece depois da resposta.
    sleep 3
    contains 'e-mail de confirmação entregue' "$MAIL_TO" "$($CURL "$MAILPIT_URL/api/v1/search?query=to:$MAIL_TO")"
fi

# O cache público: a segunda leitura vem dele, e quem tem sessão nunca passa por ele.
$CURL --output /dev/null "$BASE_URL/api/v1/genres"
contains 'leitura pública vem do cache' 'x-cache-status: hit' "$(headers "$BASE_URL/api/v1/genres" | tr '[:upper:]' '[:lower:]')"
absent 'sessão não usa o cache' 'x-cache-status: hit' "$(headers -H 'Cookie: __Host-SESSION=qualquer' "$BASE_URL/api/v1/genres" | tr '[:upper:]' '[:lower:]')"
check 'rota privada nunca vai para o cache' '401' "$(status "$BASE_URL/api/v1/users/me")"
absent 'erro sem cache' 'x-cache-status: hit' "$(headers "$BASE_URL/api/v1/users/me" | tr '[:upper:]' '[:lower:]')"

# Um corpo acima do limite recusado pelo próprio NGINX.
head -c 3200000 /dev/zero | tr '\0' 'a' > "$WORK/big"
check 'corpo grande recusado' '413' "$($CURL --output "$WORK/too-large" --write-out '%{http_code}' -X POST \
    -H 'Content-Type: application/json' --data-binary "@$WORK/big" "$BASE_URL/api/v1/auth/login")"
contains 'corpo grande no formato da API' '"code":"CONTENT_TOO_LARGE"' "$(cat "$WORK/too-large")"

# Mais que os 8 de folga da busca, em paralelo, para não depender da velocidade desta máquina.
for attempt in $(seq 1 40); do
    $CURL --output /dev/null --write-out '%{http_code}\n' "$BASE_URL/api/v1/books?query=teste" >> "$WORK/search" &
done
wait
contains 'busca limitada' '429' "$(cat "$WORK/search")"
contains 'busca atende quem está dentro do limite' '200' "$(cat "$WORK/search")"
LIMITED="$($CURL --dump-header "$WORK/limit-headers" "$BASE_URL/api/v1/books?query=teste")"
contains 'limite no formato da API' '"code":"RATE_LIMITED"' "$LIMITED"
contains 'limite informa a espera' 'retry-after: 1' "$(tr '[:upper:]' '[:lower:]' < "$WORK/limit-headers")"
check 'o catálogo sem busca não sofre o limite da busca' '200' "$(status "$BASE_URL/api/v1/books")"

# Tentativas de login em paralelo, além da folga de 10: o proxy recusa antes de chegarem à aplicação.
for attempt in $(seq 1 30); do
    $CURL --output /dev/null --write-out '%{http_code}\n' -X POST -H 'Content-Type: application/json' -d '{}' \
        "$BASE_URL/api/v1/auth/login" >> "$WORK/logins" &
done
wait
contains 'tentativas de login limitadas no proxy' '429' "$(cat "$WORK/logins")"

if [ "$FAILURES" -gt 0 ]; then
    echo "$FAILURES verificação(ões) falharam."
    exit 1
fi
echo 'Proxy conferido.'
