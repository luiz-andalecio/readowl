# Operação do backend

Para quem implanta e opera o Readowl. Os comandos usam `<env>` para o arquivo de ambiente, que fica fora do repositório. Modelo em [`infra/.env.example`](../infra/.env.example).

```sh
COMPOSE="docker compose -f infra/compose.yaml --env-file <env>"
```

## Como o ambiente é montado

| Serviço | Papel | Exposição |
|---|---|---|
| `nginx` | Única entrada. TLS, limites por IP, tamanho de corpo | Portas 80 e 443 |
| `app` | API, com o usuário `DB_USER` | Rede interna. Porta 8081 só para métricas e saúde, não publicada |
| `migrate` | Aplica as migrations e termina, com `DB_MIGRATION_USER` | Só a rede do banco |
| `postgres` | Banco e arquivamento do WAL | Só a rede do banco |

O serviço `app` nunca recebe a credencial de migration. O frontend ainda não tem imagem: `location /` em [`readowl.conf.template`](../infra/nginx/readowl.conf.template) responde 404 até ele entrar.

### Homologação

```sh
sh infra/homolog.sh up      # Constrói a imagem e sobe tudo, gerando senhas e certificado na primeira vez
sh infra/homolog.sh check   # Confere usuários, permissões, proxy e e-mail
```

O site fica em `https://localhost:8443`, com certificado autoassinado, e os e-mails em `http://localhost:8025`. O pipeline repete esses passos a cada Pull Request.

### Primeira instalação em produção

1. Preencha o arquivo de ambiente e aponte `TLS_CERT_DIR` para o certificado. A chave deve ser legível pelo usuário 101, o do NGINX.
2. Se o banco for gerenciado ou estiver em outro host, rode [`roles.sql`](../infra/postgres/roles.sql) com um superusuário e defina `DB_SSL_MODE=verify-full` e `DB_SSL_ROOT_CERT`. Monte o certificado da autoridade no serviço `app` e no `migrate`.
3. Rode `$COMPOSE up --detach --wait`.
4. Promova a primeira conta root, como descrito no [README do backend](../backend/readowl/README.md#primeira-conta-root). **Antes, entre com ela e vincule o Google**: em produção, moderação e administração só entram pelo Google.

> **Contas privilegiadas devem ter a verificação em duas etapas ativa no Google.** O Readowl não consegue conferir isso, então cabe a quem administra. Se o Google ficar indisponível, ninguém com esses papéis entra. Em uma emergência, defina `PRIVILEGED_GOOGLE_LOGIN_REQUIRED=false`, reinicie, resolva e volte o valor.

### Banco já existente com um único usuário

As tabelas continuam pertencendo ao usuário antigo, e `afterMigrate.sql` recusa a migration enquanto o usuário da aplicação for dono delas, porque o dono ignora as permissões. Com a aplicação parada e depois de um backup:

```sql
CREATE ROLE readowl_app LOGIN PASSWORD '...';
GRANT CONNECT ON DATABASE readowl TO readowl_app;
ALTER DATABASE readowl OWNER TO readowl_migrator;
REASSIGN OWNED BY usuario_antigo TO readowl_migrator;
```

## Implantar

1. Confira que o pipeline passou no commit a implantar.
2. Faça um backup: `$COMPOSE exec -T --user postgres postgres sh /readowl/scripts/backup.sh`.
3. Anote a imagem em uso, para o rollback, e defina `READOWL_IMAGE` com a tag do novo commit. Nunca use `latest`.
4. Rode `$COMPOSE up --detach --wait`. O `migrate` roda primeiro, e a aplicação só sobe se ele terminar sem erro.
5. Confira `https://<site>/healthz` e os logs de erro nos minutos seguintes.

Com uma instância, a API fica fora do ar enquanto a aplicação reinicia. O NGINX responde 503 `TEMPORARILY_UNAVAILABLE` nesse intervalo.

## Voltar à versão anterior

```sh
# No arquivo de ambiente: READOWL_IMAGE=<imagem anterior>
$COMPOSE up --detach --wait
```

As migrations não são desfeitas: a versão anterior continua sobre o schema novo, o que só funciona com a regra da seção seguinte. O `migrate` da versão anterior ignora migrations mais novas que ele e termina sem erro.

**Ensaio em 01/11/2026**, com o JAR extraído como na imagem e banco local, sem Docker:

| Verificação | Resultado |
|---|---|
| Versão atual atendendo sobre um schema com uma migration a mais | 200 |
| `DatabaseMigration` com uma migration futura no histórico | Saída 0, com aviso |
| Parar, migrar e subir de novo | 21,6 s |
| Sessão aberta antes do rollback | Continua válida |
| Escrita depois do rollback | 200 |
| Coluna renomeada, contra-exemplo | A versão anterior não sobe: `missing column [description]` |

O contra-exemplo é o que uma migration incompatível causa: **sem rollback possível sem restaurar o backup**.

Ao atualizar o Spring Security de versão principal, sessões antigas podem não carregar. Quem estiver nessa situação precisa entrar de novo. Se isso causar erros, limpe a tabela `spring_session` com o usuário de migration.

## Escrever migrations

- **Compatíveis com a versão anterior.** Em cada implantação, só acrescente: coluna anulável ou com padrão, tabela, índice.
- **Remover ou renomear leva duas implantações.** Primeiro, o código para de usar a coluna. Só na implantação seguinte a migration a remove.
- **Índice em tabela grande:** use `CREATE INDEX CONCURRENTLY`, que não bloqueia escritas. Ele não roda em transação, então precisa de `executeInTransaction=false` em um `.conf` ao lado da migration e de tempos definidos por ela mesma, porque `beforeEachMigrate.sql` só vale dentro de transação.
- **`NOT NULL` em tabela grande:** primeiro `ADD CONSTRAINT ... NOT VALID`, depois `VALIDATE CONSTRAINT`.
- **Migration aplicada não muda:** a soma de verificação falharia. Crie outra.
- **Tabela nova:** as permissões vêm sozinhas de `afterMigrate.sql`. Se ela só receber linhas, como `audit_log`, retire `UPDATE` e `DELETE` lá.

## Backup e restauração

O backup físico diário, somado ao WAL arquivado, permite restaurar o banco em qualquer ponto depois dele.

```sh
$COMPOSE exec -T --user postgres postgres sh /readowl/scripts/backup.sh          # Diário, por cron no servidor
$COMPOSE exec -T --user postgres postgres sh /readowl/scripts/restore-drill.sh   # A cada três meses
```

São mantidos 7 backups (`BACKUP_KEEP`) em `/var/lib/postgresql/backups`.

> **A cópia para fora do servidor ainda não existe.** A pasta fica no mesmo volume do banco, então a perda do disco leva os dois. Depende da escolha do provedor. A cópia precisa ser cifrada, porque contém e-mails, hashes de senha e rascunhos, e deve ser feita antes de abrir ao público.

### Restaurar de verdade

1. Pare a aplicação: `$COMPOSE stop nginx app`.
2. Copie a pasta de backups para fora do volume do banco.
3. Num volume novo, prepare os dados:
   ```sh
   RESTORE_DIR=<pasta nova> RECOVERY_TARGET_TIME='2026-11-01 12:00:00+00' sh infra/scripts/restore.sh
   ```
   Sem `RECOVERY_TARGET_TIME`, restaura até o último WAL arquivado. O script recusa uma pasta que não esteja vazia.
4. Aponte o serviço `postgres` para esse volume e suba o serviço. Quando `SELECT pg_is_in_recovery()` responder `f`, a recuperação terminou.
5. Confira os dados e suba o restante com `$COMPOSE up --detach --wait`.
6. Faça um backup novo: o histórico mudou de linha do tempo.

### Prazos

| Medida | Proposta | Situação |
|---|---|---|
| RPO, com o disco intacto | 5 minutos (`archive_timeout`) | Mecanismo ensaiado |
| RPO, com perda do servidor | 1 hora: intervalo da cópia externa | **Depende do provedor** |
| RTO | 1 hora | Restauração ensaiada, não o processo completo em servidor |

**Ensaio em 01/11/2026:** PostgreSQL 18.6 local, sem Docker, com banco de 332 MB de dados sintéticos e 1 milhão de linhas.

| Etapa | Resultado |
|---|---|
| Backup e conferência (123 MiB comprimidos) | 34 s |
| Restauração até um instante, com a marca anterior presente e a posterior ausente | 30 s |
| Restauração até o último WAL | 25 s |
| Banco original depois das restaurações | Intacto |
| Restauração depois da limpeza de backups antigos | 10 s |

O tempo cresce com o tamanho do banco e com o WAL acumulado desde o último backup. Repita o ensaio no servidor real antes de abrir, com um volume próximo do esperado.

## Monitorar

A porta interna 8081 expõe `/actuator/health`, `/actuator/health/readiness`, `/actuator/health/liveness` e `/actuator/metrics`. Monitores externos usam `https://<site>/healthz`, que responde 200 apenas com a aplicação e o banco no ar.

> **Nenhum alerta dispara ainda.** As métricas existem, mas falta escolher a ferramenta que as coleta e avisa alguém.

| Alerta | Métrica | Condição sugerida | O que olhar |
|---|---|---|---|
| Fora do ar | `/healthz` | Falha por 2 minutos | Logs da aplicação e do banco |
| Erros 5xx | `http.server.requests`, `outcome=SERVER_ERROR` | Acima de 1% por 5 minutos | Logs, pelo `correlationId` |
| Pool esgotado | `hikaricp.connections.pending` | Acima de 0 por 2 minutos | Consultas lentas e tamanho do pool |
| E-mails de conta parados | `readowl.account_mail.delay.seconds` | Acima de 300 | SMTP: ninguém confirma cadastro ou recupera senha |
| E-mails de conta perdidos | `readowl.account_mail.failed` | Aumentou | `last_error` em `account_mail_queue`; a linha fica 7 dias |
| Notificações paradas | `readowl.outbox.delay.seconds` | Acima de 300 | Logs do `NotificationWorker` |
| Eventos com falha | `readowl.outbox.failed` | Aumentou | `last_error` em `outbox_events` |
| Memória | `jvm.memory.used`, `area=heap` | Acima de 85% por 10 minutos | Contagem de reinícios do contêiner |
| Ataque de senhas | `readowl.security.events`, `event=LOGIN_FAILED` | Acima de 100 em 5 minutos | Logs, pelo hash da tentativa |
| Backup atrasado | Data do último backup | Mais de 26 horas | Cron e saída de `backup.sh` |
| Certificado | Validade do TLS | Menos de 14 dias | Renovação |

Limites atingidos no NGINX aparecem no log de acesso com status 429.

## Logs

| Origem | Formato | O que não entra |
|---|---|---|
| `app` | ECS (JSON) | Senhas, tokens, cookies e e-mails |
| `nginx`, acesso | JSON, com `correlationId` | Query string, `Referer` e cookies |
| `nginx`, erros | Texto | Em nível `error`, pode trazer a URL e o `Referer` de um pedido que falhou no proxy |

Os links enviados por e-mail levam o token no fragmento da URL (`#token=`), que o navegador não envia ao servidor. Por isso, ele não aparece nesses logs nem no `Referer`.

## Capacidade

[`scripts/load/run.sh`](../backend/readowl/scripts/load/run.sh) mede o JAR de produção com um banco de dados sintéticos: 20 mil contas, 5 mil obras, 50 mil capítulos e 150 mil comentários, 100 mil deles em uma única obra, seguida por 20 mil contas. Simula leitores anônimos, leitores logados que consultam as notificações a cada 30 segundos, pessoas comentando e o lançamento de um capítulo.

**Medido em 01/11/2026**, com 60 segundos de aquecimento e 40 de medida, e cada usuário fazendo um pedido a cada 5 a 15 segundos. A aplicação foi limitada a 2 CPUs e 700 MiB, com pool de 10 conexões.

| Usuários | Pedidos/s | p95 do catálogo | p95 do capítulo | p95 da busca | Fila do pool | Erros 5xx |
|---|---:|---:|---:|---:|---:|---:|
| 1.220 | 130 | 54 ms | 33 ms | 1.127 ms | 2 | 0 |
| 1.830 | 196 | 81 ms | 75 ms | 1.055 ms | 44 | 0 |
| 2.440 | 253 | 1.974 ms | 2.029 ms | 3.164 ms | 190 | 5 em 10.117 |

Com 1.830 usuários, o lançamento de um capítulo levou 76 ms para publicar e avisou os 20 mil seguidores, sem erros. A aplicação usou no máximo 334 MiB de heap.

> **Limites destes números.** Foram medidos em uma máquina de desenvolvimento, com o banco e o gerador de carga dividindo os mesmos 8 núcleos, e variam bastante entre rodadas. Não passam pelo NGINX, então não incluem o cache de leituras públicas, que deve aliviar bastante os visitantes, nem os limites por IP. O texto dos capítulos comprime melhor que um real. A busca usa de propósito uma palavra presente em todos os títulos, o pior caso. **Repita em um servidor igual ao de produção antes de usar para dimensionar.**

### O que as medidas decidiram

| Item | Decisão | Motivo |
|---|---|---|
| Consultas do catálogo e da busca | Reescritas | Eram o gargalo. Com 1.220 usuários, o p95 do catálogo caiu de 1.862 ms para 54 ms, e com 2.440 os erros caíram de 160 para 5, com o dobro de vazão |
| Tamanho do pool | Mantido em 10 | Com 20 conexões não houve ganho consistente: o limite era o trabalho do banco, não a falta de conexões |
| Custo da sessão | Sem mudança | Cerca de 100 atualizações por segundo com 330 pessoas logadas, sem aparecer entre as consultas mais vistas. Nenhuma é HOT, porque o `EXPIRY_TIME` tem índice, mas a tabela ficou em menos de 1 MiB. Se pesar com muito mais gente logada, aumente o intervalo das consultas de notificações antes de qualquer outra mudança |
| Contagem de comentários | Sem mudança | Na obra com 100 mil comentários, a lista levou de 110 ms a 290 ms, um valor aceitável para esse volume |

Acima da capacidade, a aplicação responde 503 `TEMPORARILY_UNAVAILABLE` depois de 5 segundos sem conexão livre, em vez de acumular pedidos: é o comportamento esperado.

## Limites do NGINX

| Rota | Limite por IP | Folga |
|---|---|---|
| `/api/`, `/oauth2/`, `/login/oauth2/` | 30 por segundo | 60 |
| `/api/v1/books` com `query` | 2 por segundo, além do anterior | 8 |
| Login, cadastro, links de e-mail | 2 por segundo, além do primeiro | 10 |
| Conexões abertas ao mesmo tempo | 50 | - |
| Corpo da requisição | 3 MiB | - |

Respostas públicas ficam no cache do NGINX pelo tempo que a aplicação indicar, de 30 segundos na maioria das rotas, e `X-Cache-Status` mostra o resultado. Quem tem sessão nunca usa esse cache. Conteúdo tirado do ar pode continuar visível por esse tempo, ou por mais alguns segundos se a aplicação estiver fora do ar, porque o cache serve a cópia vencida nesse caso.

Um bloco IPv6 `/64` conta como um cliente. **Se o SSR do frontend chamar a API por este NGINX, todas as visitas contarão como um único IP.** Nesse caso, ele deve chamar a aplicação pela rede interna, e não pelo endereço público.
