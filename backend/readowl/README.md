# Readowl — Backend

API REST da plataforma Readowl, em Java 25 e Spring Boot 4.1.2, com PostgreSQL 18.

> **Estado atual: fundação.** Existem o modelo de dados, as migrations, a segurança base, o tratamento de erros e os testes de infraestrutura. Ainda **não há endpoints de negócio**: cadastro, login e os demais casos de uso entram nas próximas etapas. O contrato HTTP vigente está em [docs/backend-api-contract.md](../../docs/backend-api-contract.md).

## Pré-requisitos

- JDK 25. O build falha de propósito com qualquer outra versão.
- PostgreSQL 18.
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
| `AnonymousSessionIntegrationTest` | Visitantes não criam sessão (servidor HTTP real) |
| `GlobalExceptionHandlerIntegrationTest` | Mapeamento de falhas e ausência de dados internos nas respostas |

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

As sessões serão persistidas em PostgreSQL com Spring Session JDBC, cujas tabelas vêm da migration `V1`. O login ainda não existe: toda rota além de `/actuator/health` responde 401 hoje.

A configuração de OAuth2 com Google e de e-mail será documentada aqui quando esses fluxos forem implementados.
