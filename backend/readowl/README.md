# Readowl — Backend

API REST da plataforma Readowl, em Java 25 e Spring Boot 4.1.2, com PostgreSQL 18.

> **Estado atual: contas e autenticação.** Além da fundação (modelo de dados, migrations, erros e segurança base), já funcionam cadastro, confirmação de e-mail, login e logout, recuperação de senha, login com Google, perfil e limites de tentativas. Obras, leitura e comunidade entram nas próximas etapas. O contrato HTTP vigente está em [docs/backend-api-contract.md](../../docs/backend-api-contract.md).

## Pré-requisitos

- JDK 25. O build falha de propósito com qualquer outra versão.
- PostgreSQL 18.
- [Mailpit](https://github.com/axllent/mailpit/releases), opcional, para ler os e-mails em desenvolvimento (ver [E-mail em desenvolvimento](#e-mail-em-desenvolvimento)).
- Docker apenas se os testes forem usar Testcontainers (ver [Testes](#testes)).

O Maven Wrapper usa a variável `JAVA_HOME`. Se ela apontar para outro JDK, defina-a na sessão do terminal:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25.0.5.0.2'
```

No IntelliJ, abra `backend/readowl` como projeto Maven e selecione o SDK 25.

## Configuração local

### 1. Criar os bancos

Execute como superusuário do PostgreSQL, trocando a senha:

```sql
CREATE ROLE readowl LOGIN PASSWORD 'troque-esta-senha';
CREATE DATABASE readowl_dev OWNER readowl ENCODING 'UTF8' TEMPLATE template0;
CREATE DATABASE readowl_test OWNER readowl ENCODING 'UTF8' TEMPLATE template0;
```

Com o `psql` do Windows:

```powershell
& 'C:\Program Files\PostgreSQL\18\bin\psql.exe' -U postgres -h localhost
```

No Query Tool do pgAdmin, execute um comando por vez: `CREATE DATABASE` falha quando enviado junto com outros comandos.

> **Mantenha `ENCODING 'UTF8'`.** No Windows, o padrão herdado pode ser `WIN1252`, que recusa emojis e outros caracteres em capítulos e comentários.

### 2. Criar o arquivo `.env`

```powershell
Copy-Item .env.example .env
```

Preencha `DB_PASSWORD` e `READOWL_TEST_DB_PASSWORD`. O `.env` não é versionado. Os perfis `dev` e `test` o carregam do diretório de execução, que deve ser `backend/readowl`.

| Variável | Uso | Padrão |
|---|---|---|
| `DB_HOST`, `DB_PORT`, `DB_NAME` | Conexão com o banco | `localhost`, `5432`, `readowl_dev` (só no perfil `dev`) |
| `DB_USER`, `DB_PASSWORD` | Credenciais do banco | Nenhum |
| `READOWL_TEST_DB_URL`, `READOWL_TEST_DB_USER`, `READOWL_TEST_DB_PASSWORD` | Banco dos testes | Sem URL, usa Testcontainers |
| `APP_PUBLIC_URL` | Endereço público do site, usado nos links dos e-mails e no retorno do Google | `http://localhost:4200` (só no perfil `dev`) |
| `SMTP_HOST`, `SMTP_PORT` | Servidor de e-mail | `localhost`, `1025` (só no perfil `dev`) |
| `SMTP_USER`, `SMTP_PASSWORD` | Credenciais do servidor de e-mail | Vazias |
| `MAIL_FROM` | Remetente dos e-mails | `no-reply@readowl.local` (só no perfil `dev`) |
| `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` | Login com Google | Sem o ID, o login com Google fica desativado |

### E-mail em desenvolvimento

Baixe o binário do [Mailpit](https://github.com/axllent/mailpit/releases) para o seu sistema e execute-o. Ele recebe e-mails na porta 1025 e mostra a caixa de entrada em `http://localhost:8025`, sem enviar nada para fora.

Sem o Mailpit a aplicação funciona normalmente, mas os e-mails não chegam: a falha aparece no log e você não recebe o link de confirmação.

### Login com Google

É opcional em desenvolvimento. Para ativá-lo, crie uma credencial **OAuth 2.0 para aplicativo da Web** no Google Cloud Console e preencha `GOOGLE_CLIENT_ID` e `GOOGLE_CLIENT_SECRET` no `.env`. O URI de redirecionamento autorizado é `APP_PUBLIC_URL` seguido de `/login/oauth2/code/google`; em desenvolvimento, `http://localhost:4200/login/oauth2/code/google`.

## Executar

```powershell
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=dev'
```

No PowerShell, as aspas são obrigatórias: sem elas, o argumento é cortado no primeiro ponto e o Maven falha com `Unknown lifecycle phase ".run.profiles=dev"`.

A migração do schema roda ao iniciar. Para conferir: `GET http://localhost:8080/actuator/health` retorna `{"status":"UP",...}`.

Sem perfil, a aplicação **não inicia**: as variáveis do banco não têm valor padrão fora do `dev`.

| Perfil | Finalidade |
|---|---|
| `dev` | Desenvolvimento local: carrega `.env`, cookie de sessão sem `Secure` |
| `prod` | Produção atrás de proxy HTTPS: cookie `__Host-SESSION`, logs estruturados ECS |
| `test` | Testes: fica em `src/test/resources` e não entra no JAR |

## Build e testes

```powershell
.\mvnw.cmd clean verify
```

O artefato é gerado em `target/readowl-0.0.1-SNAPSHOT.jar`.

### Testes

Os testes de integração usam PostgreSQL real, de duas formas:

- **Com `READOWL_TEST_DB_URL` definida:** usam esse banco.
- **Sem ela:** sobem `postgres:18` com Testcontainers, o que exige Docker.

> **O schema do banco de testes é apagado a cada execução.** Por isso os testes se recusam a rodar se o nome do banco não terminar em `_test`.

| Suíte | O que cobre |
|---|---|
| `MigrationIntegrationTest` | Migrations em banco vazio, tabelas criadas, datas com fuso |
| `SchemaConstraintsIntegrationTest` | Unicidade, FKs compostas, `CHECK`, cascatas e reordenação |
| `EntityMappingIntegrationTest` | Mapeamentos JPA, carga LAZY, JSONB, lock otimista, rascunho separado do publicado |
| `SecurityBaselineIntegrationTest` | Negação por padrão, CSRF, Actuator, formato dos erros |
| `AnonymousSessionIntegrationTest` | Visitantes não criam sessão nem recebem cookies |
| `GlobalExceptionHandlerIntegrationTest` | Mapeamento de falhas e ausência de dados internos nas respostas |
| `RegistrationIntegrationTest` | Cadastro, duplicidade, concorrência, confirmação de e-mail, envio após o commit |
| `LoginSessionIntegrationTest` | Login, cookies, CSRF, logout, rotação e revogação de sessões, limites, ausência de segredos nos logs |
| `PasswordRecoveryIntegrationTest` | Recuperação sem revelar contas, uso único do token, troca de senha |
| `GoogleLoginIntegrationTest` | Fluxo OIDC completo contra um provedor local, vínculo explícito, nenhum token guardado |
| `UserProfileIntegrationTest` | Perfil público e privado, proteção contra alteração de papel e identidade |

Os testes de API usam servidor HTTP real, cookies reais e sessões no PostgreSQL. Só o `MailSender` é substituído. O Google é simulado por um servidor local com ID tokens assinados, então **o fluxo com o Google real depende de credenciais e ainda precisa ser conferido manualmente**.

> **Pendência:** o caminho com Testcontainers ainda não foi executado, pois a máquina de desenvolvimento não tem Docker. Só o caminho com banco local foi validado.

## Arquitetura

Monólito modular por domínio, em `br.com.readowl.readowl`. Cada módulo tem as camadas `api`, `application`, `domain` e `infrastructure` apenas quando há conteúdo para elas; hoje, só `domain` existe.

| Módulo | Responsabilidade |
|---|---|
| `shared` | Entidade base, erros, segurança e logging |
| `user` | Usuários, papéis e status |
| `auth` | Identidades externas e tokens de verificação e recuperação |
| `genre` | Gêneros e tags |
| `book` | Obras |
| `publishing` | Volumes, capítulos, rascunhos e revisões publicadas |
| `library` | Obras seguidas |
| `community` | Comentários, curtidas, avaliações e visualizações |
| `banner` | Banners |

As dependências seguem uma direção, sem ciclos: `publishing` e `library` conhecem `book`; `book` conhece `user` e `genre`; `community` conhece `publishing`, `book` e `user`.

### Persistência

- O schema pertence ao **Flyway** (`src/main/resources/db/migration`). O Hibernate roda com `ddl-auto=validate` em todos os ambientes.
- Todas as associações são `LAZY`, e não há coleções inversas nas entidades.
- Entidades não têm setters públicos; mudanças de estado passam por métodos de domínio.
- Contadores de visualização só mudam por `UPDATE` atômico: a coluna é `updatable = false` no mapeamento.
- O rascunho de um capítulo (`chapter_drafts`) é separado das revisões publicadas (`chapter_revisions`), que são imutáveis.

### Autenticação e sessões

- **Sessões:** ficam no PostgreSQL com Spring Session JDBC, cujas tabelas vêm da migration `V1`. Guardam apenas `SessionUser` (ID e papel), nunca a entidade nem tokens do Google.
- **Senhas:** Argon2id com 19 MiB e 2 iterações, o mínimo recomendado pela OWASP. O prefixo `{argon2id}` no hash permite trocar de algoritmo depois.
- **Tokens de e-mail:** 256 bits aleatórios, de uso único. Só o hash SHA-256 é guardado.
- **Limites de tentativas:** janela fixa no PostgreSQL (`RateLimiter`), compartilhada entre instâncias. E-mails e IPs são guardados como hash.
- **E-mails:** enviados de forma assíncrona depois do commit. Se o SMTP falhar, a operação continua válida, a falha fica no log e o usuário pode pedir o reenvio. **Não há nova tentativa automática.**

### Produção

- **Proxy:** o perfil `prod` confia nos cabeçalhos `X-Forwarded-*`, então a aplicação não pode ser acessível diretamente. O NGINX precisa **sobrescrever** o IP do cliente com `proxy_set_header X-Forwarded-For $remote_addr;`. Com `$proxy_add_x_forwarded_for`, o cliente poderia forjar o IP e escapar dos limites por IP.
- **Atualizações:** as sessões usam serialização Java. Ao atualizar o Spring Security para outra versão principal ou renomear `SessionUser` ou `GoogleLinkIntent`, sessões antigas podem falhar ao carregar. Nesse caso, limpe a tabela `spring_session`, o que desconecta todos os usuários.
