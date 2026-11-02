# Dados pessoais no Readowl

Para quem opera o Readowl e para a revisão jurídica. Descreve o que o sistema guarda hoje, por quanto tempo e o que acontece quando alguém exclui a conta.

> **Ainda não passou por revisão jurídica.** Os prazos, a idade mínima, o registro de acesso e os termos precisam da confirmação de um advogado antes da abertura ao público. Este documento não é a política de privacidade: é a base técnica para escrevê-la. Os textos dos termos e da política ainda não existem.

## Pontos para o advogado

1. **Registro de acesso (Marco Civil, art. 15).** Guardamos data, hora, IP e rota de logins e escritas por 6 meses. Confirmar se o Readowl se enquadra como provedor com fins econômicos e se isso basta. **A porta de origem não é guardada**, e pode ser necessária para identificar uma conexão em redes com CGNAT.
2. **Idade mínima.** O cadastro exige declarar 18 anos ou mais. É só uma declaração, sem verificação. Confirmar se é suficiente, sobretudo com conteúdo adulto.
3. **Classificação de obras.** O próprio autor marca a obra como `ADULT`, e essas obras ficam ocultas para visitantes. Confirmar se essa separação atende, e o que fazer com uma obra adulta marcada como livre.
4. **Texto de comentários após a exclusão da conta.** O texto continua publicado sem o autor. Confirmar se a anonimização basta, porque o texto pode identificar a pessoa.
5. **Auditoria por 2 anos e cópias em denúncias.** Confirmar os prazos e a base legal para mantê-los depois da exclusão da conta.
6. **Direito autoral.** As obras saem com a conta. Conferir se os termos precisam tratar disso.
7. **Imagens em outros sites.** Capas e banners abrem direto de sites como Imgur e ImgBB, que recebem o IP de quem lÃª. Confirmar se a polÃ­tica de privacidade precisa informar isso.
8. **Bases legais, encarregado, transferência internacional** (e-mails, Google, provedor) e o canal de solicitações da LGPD ainda não foram definidos.

## O que é guardado e por quanto tempo

Os prazos ficam em `readowl.retention.*`. A tarefa diária `RetentionTasks` aplica os que têm prazo próprio.

| Dado | Tabela | Prazo | Motivo |
|---|---|---|---|
| Conta não confirmada | `users` | 7 dias | Libera o nome de usuário e evita acumular dados de quem não concluiu o cadastro |
| Conta ativa | `users` | Até a exclusão | Uso do serviço |
| Notificações | `notifications` | 1 ano | Histórico recente |
| Texto de comentário excluído | `comments` | 30 dias, ou até encerrar uma denúncia aberta | Moderação |
| Denúncia encerrada e cópia do conteúdo | `reports` | 1 ano após o encerramento | Defesa e reincidência |
| Auditoria administrativa | `audit_log` | 2 anos. O banco recusa menos de 1 ano | Responsabilização |
| Registro de acesso | `access_records` | 183 dias. O banco recusa menos de 180 | Marco Civil |
| Eventos processados | `outbox_events` | 30 dias | Reprocessamento |
| E-mail com falha definitiva | `account_mail_queue` | 7 dias | Investigação |
| Navegador conhecido | `known_devices` | 90 dias | Proteção do login |
| Tokens vencidos | `*_tokens` | 1 dia | Limpeza |
| Limites de tentativas | `rate_limit_buckets` | Até vencer a janela. Só hashes | Segurança |
| Visualização recente | `chapter_views` | 5 minutos | Evitar contagem repetida |
| Sessão | `spring_session` | 12 horas sem uso, ou 14 dias com "manter conectado". Máximo de 30 dias | Autenticação |
| Sessão de moderação ou administração | `spring_session` | 1 hora sem uso, máximo de 12 horas | Autenticação |
| Backups | Fora do banco | 7 dias | Recuperação. Contêm tudo o que estava no banco, inclusive contas já excluídas |
| Obras, revisões publicadas e comentários | Várias | Enquanto a conta existir | Serviço |
| Foto de perfil | Armazenamento S3 | Até a pessoa trocar ou excluir a conta | Perfil público. Refeita sem metadados |
| Links externos | `user_links` | Até a pessoa trocar ou excluir a conta | Perfil público |

## Exclusão da conta

`DELETE /users/me` é irreversível e pede a senha ou, sem senha, um login recente. A última conta root não pode ser excluída.

| Dado | O que acontece |
|---|---|
| Nome, nome de usuário, e-mail, senha, descrição | Apagados. A linha em `users` fica com valores aleatórios, porque outras tabelas apontam para ela |
| Vínculo com Google, tokens, navegadores, sessões | Apagados |
| Foto de perfil e links externos | Apagados. A foto sai do armazenamento depois do commit. Se isso falhar, o log registra a chave, e o arquivo precisa ser apagado à mão |
| Biblioteca, progresso, notas, reações, curtidas, notificações | Apagados |
| Obras, capítulos, rascunhos e revisões da pessoa | Apagados, junto com os comentários de terceiros nessas obras |
| Obra sob denúncia aberta | Fica fora do ar até a denúncia ser encerrada, e então é apagada |
| Comentários da pessoa | Continuam, com o texto e com o autor "Usuário removido" |
| Denúncias feitas ou recebidas | Continuam até o prazo, ligadas à linha anonimizada |
| Auditoria e registro de acesso | Continuam até o prazo, com o ID da linha anonimizada e, no registro de acesso, o IP |
| Backups | Saem conforme o prazo: uma restauração feita nesse intervalo traz a conta de volta. **Depois de restaurar um backup, é preciso repetir as exclusões feitas depois dele. Ainda não há procedimento automatizado para isso** |

O `AccountDeletionIntegrationTest` procura os dados da conta em toda coluna de texto de toda tabela e também exige uma decisão para cada tabela nova que aponte para `users`.

## Exportação

`GET /users/me/export` entrega um JSON com perfil, endereço da foto, links externos, aceite dos termos, vínculos, biblioteca, notas, reações, curtidas, comentários, notificações, denúncias feitas, registros de acesso e obras, com o rascunho e a revisão publicada de cada capítulo. Ficam de fora hashes e tokens, o que outras pessoas escreveram e as revisões antigas.

## Quem acessa o quê

| Dado | Quem |
|---|---|
| Registro de acesso | Só `ROOT`, e cada consulta fica na auditoria |
| E-mails de outras contas | `ADMIN` e `ROOT`, e a leitura fica na auditoria |
| Auditoria | `ADMIN` e `ROOT` |
| Denúncias | `MODERATOR`, `ADMIN` e `ROOT` |
