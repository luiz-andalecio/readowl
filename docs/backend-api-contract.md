# Readowl — Contrato da API do backend

Referência de integração para o frontend Angular. **Este documento descreve apenas o que está implementado.** O que ainda é plano está separado na última seção e pode mudar.

## Convenções

| Item | Regra |
|---|---|
| Prefixo | `/api/v1` |
| Formato | JSON |
| Datas | ISO-8601 em UTC (`2026-10-30T19:14:21Z`) |
| Origem | Frontend e API sob a mesma origem. O backend **não habilita CORS** |
| Rastreamento | Toda resposta traz o header `X-Correlation-Id`, gerado pelo servidor |
| Idioma | Mensagens de validação sempre em português |
| Tamanho | Corpos JSON acima de 3 MiB recebem 400 `BAD_REQUEST` |

### Paginação

Listas paginadas aceitam `?page=0&size=20&sort=-updatedAt`. `page` começa em 0 e `size` vai até 50. `sort` usa um campo permitido pelo endpoint, com `-` na frente para ordem decrescente. Valores fora disso recebem 400 com `INVALID_PAGINATION` ou `INVALID_SORT`.

```json
{ "content": [], "page": 0, "size": 20, "totalElements": 0, "totalPages": 0 }
```

### Papéis

| `role` | Acesso |
|---|---|
| `USER` | Gerencia as próprias obras e o próprio perfil |
| `MODERATOR` | Tudo o que o `USER` faz, e atende denúncias e oculta ou restaura conteúdo. Não edita conteúdo de terceiros nem usuários |
| `ADMIN` | Tudo o que o `USER` faz, sobre as obras e os comentários de **qualquer** pessoa, além de gêneros e tags. Vê usuários, mas não os edita |
| `ROOT` | Tudo o que o `ADMIN` faz, e edita usuários |

## Erros

Todas as respostas de erro usam `application/problem+json` (RFC 9457):

```json
{
  "status": 401,
  "title": "Unauthorized",
  "detail": "Autenticação necessária.",
  "code": "UNAUTHENTICATED",
  "correlationId": "cf7b9043-6e9e-40a2-8353-cf9635534d95"
}
```

Use **`code`** para decidir o comportamento da interface: ele é estável. `title` e `detail` podem mudar. Informe o `correlationId` ao reportar um problema.

Erros de validação trazem também `errors`:

```json
{
  "status": 400,
  "title": "Bad Request",
  "detail": "Dados inválidos.",
  "code": "VALIDATION_FAILED",
  "errors": [{ "field": "password", "message": "tamanho deve ser entre 8 e 128" }]
}
```

### Códigos gerais

| Status | `code` | Quando |
|---|---|---|
| 400 | `VALIDATION_FAILED` | Campos inválidos |
| 400 | `BAD_REQUEST` | Corpo malformado ou parâmetro de tipo errado |
| 400 | `INVALID_TEXT` | Texto com o caractere nulo, que não pode ser guardado |
| 401 | `UNAUTHENTICATED` | Requisição sem sessão válida |
| 403 | `CSRF_TOKEN_INVALID` | Token CSRF ausente ou incorreto |
| 403 | `ACCESS_DENIED` | Sem permissão para a operação |
| 404 | `NOT_FOUND` | Rota inexistente |
| 405 | `METHOD_NOT_ALLOWED` | Método HTTP não suportado na rota |
| 409 | `CONCURRENT_MODIFICATION` | O recurso foi alterado por outra operação |
| 409 | `DATA_CONFLICT` | Conflito com dados existentes |
| 429 | `RATE_LIMITED` | Muitas tentativas. O header `Retry-After` informa os segundos de espera |
| 500 | `INTERNAL_ERROR` | Erro inesperado |
| 503 | `AUTHENTICATION_BUSY` | Muitas verificações de senha ao mesmo tempo. Vale para login, cadastro e troca de senha. Tente de novo em instantes |
| 503 | `TEMPORARILY_UNAVAILABLE` | Falha passageira no banco, como bloqueio ou consulta acima do tempo máximo. O header `Retry-After` informa a espera |

Outros erros de protocolo usam o nome do status HTTP como `code`, por exemplo `UNSUPPORTED_MEDIA_TYPE`. Os códigos de cada operação estão junto dos endpoints.

## CSRF

Toda requisição que altera estado (`POST`, `PUT`, `PATCH`, `DELETE`) precisa do token CSRF, **inclusive login e logout**. Sem ele, a resposta é 403 com `CSRF_TOKEN_INVALID`.

| Item | Desenvolvimento | Produção |
|---|---|---|
| Cookie (legível por JavaScript) | `XSRF-TOKEN` | `__Host-XSRF-TOKEN` |
| Header de envio | `X-XSRF-TOKEN` | `X-XSRF-TOKEN` |

O prefixo `__Host-` exige HTTPS, por isso o nome muda entre os ambientes. Ele impede que um subdomínio defina o cookie no lugar do site.

`XSRF-TOKEN` é o padrão do `HttpClient` do Angular, que envia o header automaticamente em requisições com **URL relativa** que alteram estado. **Em produção, é preciso informar o nome**, ou o header deixa de ser enviado e toda escrita responde 403:

```ts
withXsrfConfiguration({ cookieName: environment.production ? '__Host-XSRF-TOKEN' : 'XSRF-TOKEN', headerName: 'X-XSRF-TOKEN' })
```

**O cookie só é criado sob demanda.** Respostas de leitura não o emitem, o que permite cache de páginas públicas. O fluxo é:

1. Chame `GET /api/v1/auth/csrf` antes do primeiro `POST` da página.
2. Login, troca de senha e logout **descartam** o token. Chame `GET /api/v1/auth/csrf` de novo antes da próxima operação que altera estado.

## Sessão

A autenticação é por cookie de sessão `HttpOnly`, inacessível ao JavaScript. **Não armazene tokens de autenticação no navegador.** Requisições anônimas não criam sessão.

| Item | Valor |
|---|---|
| Nome do cookie | `SESSION` em desenvolvimento; `__Host-SESSION` em produção |
| Atributos | `HttpOnly`, `SameSite=Lax`, `Path=/`; `Secure` em produção |
| Sem "manter conectado" | Expira após 12 horas sem uso, e o cookie some ao fechar o navegador |
| Com "manter conectado" | Expira após 14 dias sem uso |
| Moderação e administração | Expira após 1 hora sem uso, e "manter conectado" não vale |
| Duração máxima | 30 dias; 12 horas para moderação e administração. Vale mesmo com uso contínuo |

Escritas e rotas de `/admin` e `/moderation` conferem a conta a cada requisição. Se ela foi suspensa ou mudou de papel, a sessão é encerrada e a resposta é 401 `UNAUTHENTICATED`.

### Cache

Para visitantes, as rotas públicas de leitura enviam `Cache-Control: public, max-age=30` e `Vary: Cookie`. Com sessão, a resposta é `no-store`, porque pode incluir obras adultas. As rotas são: catálogo, obra, índice, capítulo, banners e perfil público. `GET /genres` usa 300 segundos. Conteúdo tirado do ar pode continuar em caches por esse tempo. As demais respostas, inclusive os erros, enviam `no-store`.

## Autenticação

Todos os endpoints abaixo ficam em `/api/v1/auth`. Os públicos não exigem sessão, mas os `POST` exigem CSRF.

Os limites contam por IP e por conta e respondem 429 com `RATE_LIMITED`:

| Operação | Limite |
|---|---|
| Login | 10 por conta em cada IP, 100 por conta e 30 por IP, a cada 15 minutos. Um login correto zera os dois limites por conta |
| Cadastro | 5 por e-mail e 10 por IP, por hora |
| Reenvio de confirmação e recuperação de senha | 3 por e-mail em cada IP, 10 por e-mail e 10 por IP, por hora |
| Uso de token de e-mail | 20 por IP, a cada 15 minutos |
| Confirmação de senha (troca e vínculo com Google) | 10 por conta, a cada 15 minutos |

### `GET /auth/csrf`

Emite o cookie CSRF (`XSRF-TOKEN`; em produção, `__Host-XSRF-TOKEN`). Público.

**204**, sem corpo.

### `GET /auth/session`

Informa quem está autenticado. Público: é a forma de saber se há sessão, sem tratar erro.

**200** para visitantes:
```json
{ "authenticated": false, "user": null }
```

**200** com sessão:
```json
{
  "authenticated": true,
  "user": {
    "id": "01a19372-c79e-795c-a830-081911db21ce",
    "username": "maria-leitora",
    "name": "Maria Leitora",
    "role": "USER",
    "hasPassword": true,
    "googleLinked": false,
    "termsAccepted": true
  }
}
```

Com `termsAccepted: false`, mostre os termos e chame `POST /users/me/terms`. Até lá, escritas respondem 403 `TERMS_NOT_ACCEPTED`, salvo aceitar, cuidar da senha, do e-mail e das sessões, e excluir a conta. Acontece com quem entrou pelo Google e, para todos, quando os termos mudam.

Os valores de `role` estão em [Papéis](#papéis). Use-os só para adaptar a interface: quem decide o acesso é o backend.

### `GET /auth/terms`

Versão vigente dos termos de uso e idade mínima. Público. O texto dos termos fica no frontend e depende de revisão jurídica.

**200**
```json
{ "version": "2026-11", "minimumAge": 18 }
```

### `POST /auth/register`

Cria uma conta e envia o e-mail de confirmação. Público.

```json
{
  "name": "Maria Leitora",
  "username": "maria-leitora",
  "email": "maria@example.com",
  "password": "...",
  "termsVersion": "2026-11",
  "ageConfirmed": true
}
```

| Campo | Regra |
|---|---|
| `name` | Até 100 caracteres, não vazio |
| `username` | De 3 a 30 letras, números, `_` ou `-`, começando com letra ou número. É salvo em minúsculas e não pode ser alterado depois |
| `email` | E-mail válido, até 255 caracteres. Maiúsculas são ignoradas |
| `password` | De 8 a 128 caracteres |
| `termsVersion` | Obrigatório. A versão que a pessoa leu, de `GET /auth/terms` |
| `ageConfirmed` | Obrigatório e `true`: a pessoa declara ter a idade mínima |

**202**, sem corpo. **A resposta é a mesma se o e-mail já tiver conta**, para não revelar quem está cadastrado: nesse caso, o dono do e-mail recebe um aviso em vez da confirmação. Mostre uma mensagem como "Enviamos as instruções para o seu e-mail".

| Status | `code` | Quando |
|---|---|---|
| 409 | `USERNAME_TAKEN` | Nome de usuário ocupado ou reservado |
| 409 | `TERMS_OUTDATED` | Os termos mudaram. A resposta traz `currentVersion` |

### `POST /auth/verify-email`

Confirma o e-mail. Público.

```json
{ "token": "..." }
```

**204**, sem corpo. Não inicia sessão: envie o usuário para o login.

| Status | `code` | Quando |
|---|---|---|
| 400 | `INVALID_TOKEN` | Token inválido, expirado ou já usado |

O token vale por 24 horas. Faça este `POST` por uma ação da página, nunca em um `GET` do servidor.

### `POST /auth/resend-verification`

Reenvia a confirmação e invalida o link anterior. Público.

```json
{ "email": "maria@example.com" }
```

**202**, sem corpo, para qualquer e-mail.

### `POST /auth/login`

Inicia a sessão. Público.

```json
{ "email": "maria@example.com", "password": "...", "rememberMe": true }
```

`rememberMe` é opcional e, ausente, vale `false`. Veja [Sessão](#sessão).

**200**, com o mesmo corpo de `GET /auth/session`. Emite o cookie de sessão e descarta o token CSRF.

No primeiro login em cada navegador, emite também o cookie `READOWL_DEVICE`, `HttpOnly` e restrito a esta rota, válido por 90 dias. O frontend não precisa tratá-lo. Com ele, o login continua funcionando mesmo se um ataque de muitos IPs esgotar o limite da conta. Trocar ou recuperar a senha desfaz isso em todos os navegadores.

| Status | `code` | Quando |
|---|---|---|
| 401 | `INVALID_CREDENTIALS` | E-mail ou senha incorretos, ou conta sem senha. Não distingue os casos |
| 403 | `EMAIL_NOT_VERIFIED` | Senha correta, e-mail ainda não confirmado. Ofereça o reenvio |
| 403 | `ACCOUNT_SUSPENDED` | Senha correta, conta suspensa |
| 403 | `GOOGLE_LOGIN_REQUIRED` | Senha correta, mas em produção contas de moderação e administração só entram pelo Google |

### `POST /auth/logout`

Encerra a sessão. Envie um corpo vazio.

**204**, sem corpo. Também responde 204 se não houver sessão. Descarta o token CSRF.

### `POST /auth/password/forgot`

Envia o link de recuperação e invalida o anterior. Público.

```json
{ "email": "maria@example.com" }
```

**202**, sem corpo, para qualquer e-mail.

### `POST /auth/password/reset`

Define uma nova senha. Público.

```json
{ "token": "...", "newPassword": "..." }
```

**204**, sem corpo. Encerra todas as sessões da conta e confirma o e-mail, se ainda não estava confirmado. Não inicia sessão.

| Status | `code` | Quando |
|---|---|---|
| 400 | `INVALID_TOKEN` | Token inválido, expirado ou já usado |

O token vale por 1 hora. Contas criadas com o Google também podem definir uma senha por aqui.

### Páginas que o frontend precisa ter

Os e-mails apontam para estas rotas. **O token vai no fragmento (`#token=`)**, que o navegador não envia ao servidor: leia-o de `ActivatedRoute.fragment`, não dos parâmetros de consulta.

| Rota | Ação da página |
|---|---|
| `/verificar-email#token=...` | Enviar o token a `POST /auth/verify-email` |
| `/redefinir-senha#token=...` | Pedir a nova senha e enviar a `POST /auth/password/reset` |
| `/recuperar-senha` | Pedir o e-mail e enviar a `POST /auth/password/forgot` |

## Login com Google

**Se o backend estiver sem credenciais do Google,** `GET /oauth2/authorization/google` responde 401 e `POST /auth/google/link` responde 503 com `GOOGLE_UNAVAILABLE`.

### Entrar

Faça uma **navegação completa** para `/oauth2/authorization/google`, não uma chamada pelo `HttpClient`. O usuário volta ao frontend por redirecionamento:

| Resultado | Destino |
|---|---|
| Sucesso | `/`. Chame `GET /auth/session` para obter o usuário |
| Falha | `/login?error=<código>` |

| Código em `error` | Quando |
|---|---|
| `GOOGLE_ACCOUNT_EXISTS` | Já existe conta com este e-mail. O usuário deve entrar com a senha e vincular o Google |
| `GOOGLE_EMAIL_UNVERIFIED` | O Google não confirmou o e-mail |
| `ACCOUNT_SUSPENDED` | Conta suspensa |
| `GOOGLE_LOGIN_FAILED` | Login cancelado ou falha no fluxo |

O primeiro login cria a conta, com um `username` gerado a partir do nome.

Depois do retorno, chame `GET /auth/csrf` antes da próxima operação que altera estado, porque o login descartou o token.

### Vincular a uma conta existente

Requer sessão e nunca acontece automaticamente.

1. `POST /auth/google/link` com a senha atual: `{ "password": "..." }`. Contas sem senha enviam `{}`.
2. Na resposta **204**, faça uma navegação completa para `/oauth2/authorization/google` em até 10 minutos.
3. O usuário volta para `/conta` ou, em caso de falha, para `/conta?error=<código>`, ainda autenticado na própria conta.

| Onde | `code` | Quando |
|---|---|---|
| Resposta do `POST` (400) | `INVALID_CURRENT_PASSWORD` | Senha ausente ou incorreta |
| Resposta do `POST` (409) | `GOOGLE_ALREADY_LINKED` | A conta já tem Google vinculado |
| Parâmetro `error` | `GOOGLE_LINKED_TO_OTHER_ACCOUNT` | Esta conta do Google pertence a outro usuário |
| Parâmetro `error` | `GOOGLE_LOGIN_FAILED` | Vínculo cancelado ou falha no fluxo |

## Usuários

### `GET /users/{username}`

Perfil público. Não exige sessão.

**200**
```json
{
  "username": "maria-leitora",
  "name": "Maria Leitora",
  "description": "Leio de tudo.",
  "createdAt": "2026-10-31T19:32:15.393101Z"
}
```

`description` pode ser `null`.

| Status | `code` | Quando |
|---|---|---|
| 404 | `USER_NOT_FOUND` | Não existe, não confirmou o e-mail ou está suspenso |

### `GET /users/me`

Dados da própria conta. Exige sessão.

**200**
```json
{
  "id": "01a19372-c79e-795c-a830-081911db21ce",
  "username": "maria-leitora",
  "name": "Maria Leitora",
  "email": "maria@example.com",
  "description": null,
  "role": "USER",
  "emailVerified": true,
  "emailNewChapters": false,
  "createdAt": "2026-10-31T19:32:15.393101Z"
}
```

### `POST /users/me/terms`

Aceita os termos vigentes.

```json
{ "termsVersion": "2026-11", "ageConfirmed": true }
```

**204**, sem corpo. **409** `TERMS_OUTDATED` se a versão não for a vigente. **400** sem a declaração de idade.

### `GET /users/me/sessions`

Sessões abertas da conta, da usada mais recentemente para a mais antiga.

**200**
```json
[{
  "id": "7f3a...",
  "createdAt": "2026-11-01T12:00:00Z",
  "lastAccessedAt": "2026-11-01T13:00:00Z",
  "userAgent": "Mozilla/5.0 ...",
  "current": true
}]
```

`id` serve só para encerrar a sessão: não é o valor do cookie.

### `DELETE /users/me/sessions`

Encerra as demais sessões e mantém a atual. **204**, sem corpo.

### `DELETE /users/me/sessions/{id}`

Encerra uma sessão. Encerrar a atual equivale a sair. **204**, sem corpo. **404** `SESSION_NOT_FOUND` se ela não for da conta.

### `POST /users/me/email`

Pede a troca de e-mail. O link vai para o endereço novo e vale por 1 hora.

```json
{ "newEmail": "novo@example.com", "password": "..." }
```

`password` é obrigatória para quem tem senha. Quem só entra pelo Google precisa ter entrado há menos de 15 minutos.

**202**, sem corpo, **também se o endereço novo já tiver conta**, para não revelar quem está cadastrado.

| Status | `code` | Quando |
|---|---|---|
| 400 | `INVALID_CURRENT_PASSWORD` | Senha incorreta ou ausente |
| 403 | `RECENT_LOGIN_REQUIRED` | Conta sem senha, com login antigo. Peça para entrar de novo |

### `POST /auth/email/confirm`

Conclui a troca. Público: o link abre em `/confirmar-email#token=...`.

```json
{ "token": "..." }
```

**204**, sem corpo. **Todas as sessões são encerradas**, e o endereço antigo recebe um aviso.

| Status | `code` | Quando |
|---|---|---|
| 400 | `INVALID_TOKEN` | Link inválido, expirado ou já usado |
| 409 | `EMAIL_TAKEN` | Outra conta passou a usar o endereço |

### `GET /users/me/export`

Baixa os dados da conta em JSON, com `Content-Disposition: attachment`. Limite de 3 por dia. Inclui perfil, biblioteca, notas, reações, curtidas, comentários, notificações, denúncias feitas, registros de acesso e obras, com o rascunho e a revisão publicada de cada capítulo.

### `DELETE /users/me`

Exclui a conta, **sem volta**. Peça uma confirmação clara antes de chamar.

```json
{ "password": "..." }
```

Com `password` e as regras de `POST /users/me/email`. **204**, sem corpo, e a sessão termina. Obras, biblioteca, notas e reações são apagadas. Os comentários continuam, com o autor "Usuário removido" e sem `username`. O que continua guardado está em [personal-data.md](personal-data.md).

| Status | `code` | Quando |
|---|---|---|
| 400 | `INVALID_CURRENT_PASSWORD` | Senha incorreta ou ausente |
| 403 | `RECENT_LOGIN_REQUIRED` | Conta sem senha, com login antigo |
| 409 | `LAST_ROOT` | É a última conta root ativa |

### `PATCH /users/me`

Atualiza o perfil. Exige sessão. Campos ausentes não mudam; outros campos enviados são ignorados.

```json
{ "name": "Maria L.", "description": "Leio de tudo.", "emailNewChapters": true }
```

| Campo | Regra |
|---|---|
| `name` | Até 100 caracteres, não vazio |
| `description` | Até 2000 caracteres. Em branco, remove a descrição |
| `emailNewChapters` | Receber e-mail de novos capítulos das obras seguidas. Começa desligado |

**200**, com o mesmo corpo de `GET /users/me`.

### `PUT /users/me/password`

Troca a senha. Exige sessão.

```json
{ "currentPassword": "...", "newPassword": "..." }
```

**204**, sem corpo. Mantém a sessão atual com um novo ID, encerra as demais e descarta o token CSRF.

| Status | `code` | Quando |
|---|---|---|
| 400 | `INVALID_CURRENT_PASSWORD` | Senha atual incorreta |
| 409 | `PASSWORD_NOT_SET` | Conta sem senha. Use a recuperação de senha para definir uma |

## Gêneros e tags

### `GET /genres`

Lista todos os gêneros em ordem alfabética. Público.

**200**
```json
[{ "id": "019f...", "name": "Fantasia" }]
```

### `GET /tags?query=mag`

Sugere até 20 tags que começam com o texto informado e estão em alguma obra publicada. Uma tag nova só passa a ser sugerida depois disso. Público, para o campo de autocompletar.

**200**, no mesmo formato de `GET /genres`.

## Obras

Exigem sessão e CSRF nas operações que alteram estado.

**Quem pode alterar:** o autor, `ADMIN` e `ROOT`. Para os demais, uma obra fora do ar responde 404 `BOOK_NOT_FOUND`, sem revelar que existe, e uma obra publicada responde 403 `ACCESS_DENIED`.

### Limites de autoria

Protegem o armazenamento contra abuso e valem por conta. Passar de um limite por tempo responde 429 `RATE_LIMITED`. Passar de uma cota responde 409 com o código indicado e o campo `limit`.

| Operação | Limite |
|---|---|
| `POST /books` | 10 por dia |
| `PATCH /books/{id}` com `tags` | 30 por hora, porque pode criar tags públicas |
| Alterar ou excluir obras, volumes e capítulos, reordenar e publicar ou despublicar obras | 120 por minuto |
| `PUT /chapters/{id}/draft` | 120 por minuto |
| `POST /chapters/{id}/publish` | 120 por hora |

| Cota | Valor padrão | `code` |
|---|---|---|
| Obras por conta | 50 | `BOOK_LIMIT_REACHED` |
| Volumes por obra | 200 | `VOLUME_LIMIT_REACHED` |
| Capítulos por obra | 5.000 | `CHAPTER_LIMIT_REACHED` |
| Revisões publicadas por capítulo | 500 | `REVISION_LIMIT_REACHED` |

Além disso, a conta tem um limite de 2 GiB para o texto gravado em rascunhos e revisões, já comprimido. Passar dele responde 409 `STORAGE_LIMIT_REACHED`, com `limit` em bytes, ao publicar ou ao salvar um rascunho grande, a partir de 256 KiB.

Capítulo publicado com a obra fora do ar não gera notificação, nem quando a obra volta ao ar.

### O slug muda com o nome

O slug é `sigla-titulo`, em minúsculas e sem acentos: a obra **Shihais: Remanescente da Aura** com a sigla **SRA** fica em `sra-shihais-remanescente-da-aura`. **Mudar o título ou a sigla muda o slug, e o endereço antigo deixa de valer.** Depois de renomear, use o `slug` da resposta.

Vale o mesmo para capítulos: o slug segue o título. Títulos repetidos dentro da obra recebem um número, como `prologo-2`.

### `POST /books`

```json
{
  "title": "Shihais: Remanescente da Aura",
  "acronym": "SRA",
  "synopsis": "...",
  "releaseFrequency": "Semanal",
  "genreIds": ["019f..."],
  "tags": ["Magia"]
}
```

| Campo | Regra |
|---|---|
| `title` | Até 200 caracteres, não vazio |
| `acronym` | De 2 a 10 letras ou números, sem acentos ou espaços. É salva em maiúsculas |
| `synopsis` | Até 2000 caracteres, não vazia |
| `releaseFrequency` | Opcional, até 50 caracteres |
| `genreIds` | De 1 a 5 gêneros de `GET /genres` |
| `tags` | Opcional, até 10 nomes com letras, números, espaços, `_` ou `-`. Tags novas são criadas; maiúsculas não geram duplicatas |

**201**
```json
{
  "id": "019f...",
  "title": "Shihais: Remanescente da Aura",
  "acronym": "SRA",
  "slug": "sra-shihais-remanescente-da-aura",
  "synopsis": "...",
  "releaseFrequency": "Semanal",
  "status": "ONGOING",
  "publicationState": "DRAFT",
  "moderated": false,
  "viewCount": 0,
  "authorUsername": "maria-leitora",
  "genres": [{ "id": "019f...", "name": "Fantasia" }],
  "tags": [{ "id": "019f...", "name": "Magia" }],
  "createdAt": "2026-10-31T19:32:15.393101Z",
  "updatedAt": "2026-10-31T19:32:15.393101Z"
}
```

`status` é `ONGOING`, `COMPLETED` ou `HIATUS`. `publicationState` é `DRAFT`, `PUBLISHED` ou `UNPUBLISHED`. `moderated` é `true` se a obra foi ocultada pela moderação. Mostre isso ao autor, que não consegue publicá-la nesse estado.

| Status | `code` | Quando |
|---|---|---|
| 400 | `UNKNOWN_GENRE` | Gênero inexistente |
| 409 | `BOOK_SLUG_TAKEN` | Já existe obra com esta sigla e este título. O usuário deve escolher outra sigla |

### `GET /books/{id}/details`

Dados completos da obra para quem pode alterá-la. **200**, com o corpo acima.

### `PATCH /books/{id}`

Aceita os campos de `POST /books` e `status`. Campos ausentes não mudam. `releaseFrequency` em branco é removida. `genreIds` e `tags` substituem a lista inteira.

`version` é opcional e recomendado em telas de edição: envie o valor lido em `GET /books/{id}/details`. Se a obra mudou depois disso, a resposta é 409 `VERSION_CONFLICT`, com `currentVersion`, sem alterar nada. Vale também para `PATCH /volumes/{id}` e `PATCH /chapters/{id}`.

**200**, com o corpo acima. Pode responder 409 `BOOK_SLUG_TAKEN`, sem alterar nada.

### `DELETE /books/{id}`

**Exclui a obra com capítulos, comentários e avaliações, sem volta.** Peça ao usuário para digitar o título e envie-o no corpo. O parâmetro `?confirmTitle=` também é aceito, mas deixa o título em logs de acesso.

```json
{ "confirmTitle": "Título exato da obra" }
```

**204**, sem corpo.

| Status | `code` | Quando |
|---|---|---|
| 400 | `TITLE_CONFIRMATION_MISMATCH` | O título não confere |
| 409 | `BOOK_PUBLISHED` | Despublique a obra antes |
| 409 | `BOOK_UNDER_MODERATION` | A obra ou um de seus capítulos está oculto pela moderação. Vale também para administradores |

### `POST /books/{id}/publish` e `POST /books/{id}/unpublish`

Envie um corpo vazio. **200**, com o corpo de `GET /books/{id}/details`.

| Status | `code` | Quando |
|---|---|---|
| 409 | `BOOK_WITHOUT_PUBLISHED_CHAPTERS` | Publique ao menos um capítulo antes da obra |
| 409 | `BOOK_UNDER_MODERATION` | A obra foi ocultada pela moderação. Vale também para administradores |

### `GET /users/me/books`

Minhas obras, paginadas. `sort`: `title`, `createdAt` ou `updatedAt`. O padrão é `-updatedAt`.

**200**, com `content` neste formato:
```json
{
  "id": "019f...",
  "title": "Shihais: Remanescente da Aura",
  "acronym": "SRA",
  "slug": "sra-shihais-remanescente-da-aura",
  "status": "ONGOING",
  "publicationState": "DRAFT",
  "moderated": false,
  "authorUsername": "maria-leitora",
  "createdAt": "2026-10-31T19:32:15.393101Z",
  "updatedAt": "2026-10-31T19:32:15.393101Z"
}
```

## Leitura pública

Não exigem sessão, não criam sessão e não emitem cookies. Mostram **só** conteúdo público: obra publicada, de autor não suspenso, e capítulos publicados. Vale para todos, inclusive para o autor e administradores, que usam `GET /books/{id}/details` e `GET /chapters/{id}/draft` para ver o restante.

### `GET /books`

Catálogo paginado.

| Parâmetro | Efeito |
|---|---|
| `query` | Busca no título e na sinopse, até 100 caracteres. Ignora acentos e maiúsculas, aceita trechos do título e tolera erros de digitação. Também encontra pela sigla exata |
| `genre` | ID do gênero. Repita o parâmetro para exigir vários |
| `tag` | Nome da tag. Repita o parâmetro para exigir várias |
| `status` | `ONGOING`, `COMPLETED` ou `HIATUS` |
| `author` | Username do autor. Use para listar as obras no perfil público |
| `sort` | `title`, `createdAt`, `lastChapterAt`, `views` ou, em buscas, `relevance`. Com `-` na frente, ordem decrescente |
| `page`, `size` | Paginação |

Sem `sort`, uma busca ordena por relevância, e o catálogo, pelas atualizações mais recentes (`-lastChapterAt`). Use `-lastChapterAt` e `-views` para as seções da página inicial.

**200**, com `content` neste formato:
```json
{
  "id": "019f...",
  "title": "Shihais: Remanescente da Aura",
  "acronym": "SRA",
  "slug": "sra-shihais-remanescente-da-aura",
  "synopsis": "...",
  "status": "ONGOING",
  "viewCount": 0,
  "author": { "username": "maria-leitora", "name": "Maria Leitora" },
  "genres": [{ "id": "019f...", "name": "Fantasia" }],
  "tags": [{ "id": "019f...", "name": "Magia" }],
  "publishedChapters": 12,
  "lastChapterPublishedAt": "2026-10-31T19:32:15.393101Z",
  "createdAt": "2026-10-31T19:32:15.393101Z"
}
```

| Status | `code` | Quando |
|---|---|---|
| 400 | `QUERY_TOO_LONG` | Busca acima de 100 caracteres |
| 400 | `TOO_MANY_FILTERS` | Mais de 10 gêneros ou 10 tags |
| 400 | `BAD_REQUEST` | `status` ou `genre` em formato inválido |

### `GET /books/{slug}`

Página da obra.

**200**
```json
{
  "id": "019f...",
  "title": "Shihais: Remanescente da Aura",
  "acronym": "SRA",
  "slug": "sra-shihais-remanescente-da-aura",
  "synopsis": "...",
  "releaseFrequency": "Semanal",
  "status": "ONGOING",
  "viewCount": 0,
  "author": { "username": "maria-leitora", "name": "Maria Leitora" },
  "genres": [{ "id": "019f...", "name": "Fantasia" }],
  "tags": [],
  "lastChapterPublishedAt": "2026-10-31T19:32:15.393101Z",
  "createdAt": "2026-10-31T19:32:15.393101Z",
  "volumes": [{ "id": "019f...", "title": "Volume 1" }],
  "publishedChapters": 12
}
```

Volumes sem capítulos publicados não aparecem. A lista de capítulos fica em `GET /books/{slug}/chapters`.

Responde 404 `BOOK_NOT_FOUND` se a obra não for pública.

### `GET /books/{slug}/chapters`

Índice da obra, paginado: uma obra longa pode ter milhares de capítulos. Vem na ordem de leitura, primeiro os capítulos sem volume e depois cada volume em ordem. `size` padrão: 50. Não aceita `sort`.

**200**
```json
{
  "content": [{
    "id": "019f...",
    "title": "Prólogo",
    "slug": "prologo",
    "volumeId": "019f...",
    "publishedAt": "2026-10-31T19:32:15.393101Z"
  }],
  "page": 0,
  "size": 50,
  "totalElements": 12,
  "totalPages": 1
}
```

### `GET /books/{slug}/chapters/{chapterSlug}`

Texto do capítulo para leitura.

**200**
```json
{
  "id": "019f...",
  "title": "Prólogo",
  "slug": "prologo",
  "volume": { "id": "019f...", "title": "Volume 1" },
  "html": "<p>Era uma vez.</p>",
  "publishedAt": "2026-10-31T19:32:15.393101Z",
  "book": {
    "id": "019f...",
    "title": "Shihais: Remanescente da Aura",
    "slug": "sra-shihais-remanescente-da-aura",
    "author": { "username": "maria-leitora", "name": "Maria Leitora" }
  },
  "previous": null,
  "next": { "title": "Capítulo 1", "slug": "capitulo-1" }
}
```

`html` é gerado pelo servidor a partir do documento validado, com todo o texto escapado, e pode ser inserido na página com `[innerHTML]`. `volume`, `previous` e `next` podem ser `null`.

Responde 404 com `BOOK_NOT_FOUND` ou `CHAPTER_NOT_FOUND`. Depois de renomear uma obra ou capítulo, **o endereço antigo responde 404**.

## Biblioteca

Exige sessão. Cada usuário só acessa a própria biblioteca.

### `GET /library`

Obras seguidas, das mais recentes para as mais antigas, paginadas. Obras que saíram do ar não aparecem, mas voltam se forem republicadas.

**200**, com `content` neste formato:
```json
{
  "book": {
    "id": "019f...",
    "title": "Shihais: Remanescente da Aura",
    "acronym": "SRA",
    "slug": "sra-shihais-remanescente-da-aura",
    "status": "ONGOING",
    "authorUsername": "maria-leitora",
    "authorName": "Maria Leitora",
    "lastChapterPublishedAt": "2026-10-31T19:32:15.393101Z"
  },
  "followedAt": "2026-10-31T19:32:15.393101Z",
  "progress": {
    "chapterId": "019f...",
    "chapterTitle": "Prólogo",
    "chapterSlug": "prologo",
    "percent": 40,
    "updatedAt": "2026-10-31T19:32:15.393101Z"
  }
}
```

`progress` é `null` se não houver leitura registrada ou se o capítulo saiu do ar.

### `GET /library/{bookId}`

Estado do usuário em uma obra, para a página da obra e o leitor.

**200**
```json
{ "following": true, "progress": null }
```

`progress` tem o formato acima. Responde 404 `BOOK_NOT_FOUND` se a obra não for pública.

### `PUT /library/{bookId}`

Segue a obra. Envie um corpo vazio. Repetir não é erro. **200**, com o corpo de `GET /library/{bookId}`.

### `DELETE /library/{bookId}`

Deixa de seguir, mesmo com a obra fora do ar. Repetir não é erro. **204**, sem corpo. O progresso de leitura é mantido.

### `PUT /library/{bookId}/progress`

Registra onde o usuário parou. Não exige seguir a obra. A última leitura vale, mesmo voltando a um capítulo anterior.

```json
{ "chapterId": "019f...", "percent": 40 }
```

`percent` vai de 0 a 100. **200**, com o corpo de `GET /library/{bookId}`.

Responde 404 `CHAPTER_NOT_FOUND` se o capítulo não pertencer à obra ou não estiver publicado.

## Comunidade

As leituras marcadas como públicas não exigem sessão, não criam sessão e não emitem cookies. As alterações exigem sessão e CSRF. Conteúdo fora do ar responde 404 `BOOK_NOT_FOUND` ou `CHAPTER_NOT_FOUND`, como nas rotas de leitura.

Contas suspensas deixam de aparecer: seus comentários somem, e suas notas e reações saem dos totais. Tudo volta se a conta for reativada.

Limites por minuto, além dos quais a resposta é 429 `RATE_LIMITED`:

| Ação | Por conta | Por IP |
|---|---|---|
| Criar, responder ou editar comentário | 10 | 60 |
| Avaliar, reagir ou curtir | 60 | 300 |
| Registrar visita | 120 | 600 |

### Comentários

**O conteúdo é texto puro e pode conter qualquer coisa, inclusive `<script>`. Exiba-o sempre como texto, nunca com `innerHTML`.** As quebras de linha são preservadas.

`GET /books/{bookId}/comments` e `GET /chapters/{chapterId}/comments` são públicos e paginados, e trazem só comentários principais. A lista da obra não inclui os comentários dos capítulos. `sort`: `createdAt`, com padrão `-createdAt`.

**200**, com `content` neste formato:
```json
{
  "id": "019f...",
  "bookId": "019f...",
  "chapterId": null,
  "parentId": null,
  "content": "Gostei muito!\nQuando sai o próximo?",
  "author": { "username": "maria-leitora", "name": "Maria Leitora" },
  "removed": false,
  "edited": false,
  "replyCount": 2,
  "likeCount": 5,
  "createdAt": "2026-10-31T19:32:15.393101Z",
  "updatedAt": "2026-10-31T19:32:15.393101Z"
}
```

`author.username` é `null` quando a conta foi excluída: mostre `author.name`, sem link para perfil.

**Trate `content` e `author` como opcionais.** Um comentário principal excluído, oculto ou de conta suspensa que ainda tem respostas continua na lista com `removed: true`, `content: null` e `author: null`, para não sumir com a conversa. Mostre algo como "Comentário removido". Sem respostas visíveis, ele não aparece.

`POST /books/{bookId}/comments` e `POST /chapters/{chapterId}/comments` criam um comentário. **201**, com o corpo acima.

```json
{ "content": "Gostei muito!" }
```

`content` tem de 1 a 5000 caracteres, sem contar espaços nas pontas.

`PATCH /comments/{id}`, com o mesmo corpo, altera o texto. **200**, com o corpo acima e `edited: true`. Quem comentou edita enquanto o conteúdo estiver no ar; `ADMIN` e `ROOT` editam sempre. **A resposta não indica quem editou:** isso fica em `GET /admin/audit-log`.

`DELETE /comments/{id}` remove o comentário da página. **204**, sem corpo. Quem comentou pode excluir mesmo com o conteúdo fora do ar; `ADMIN` e `ROOT` também. O autor da obra não exclui comentários de terceiros. **O texto continua guardado no banco para a moderação.**

| Status | `code` | Quando |
|---|---|---|
| 400 | `INVALID_COMMENT` | O texto tem caracteres de controle, exceto quebras de linha e tabulação |
| 403 | `ACCESS_DENIED` | O comentário é de outra pessoa |
| 404 | `COMMENT_NOT_FOUND` | Inexistente, já excluído ou fora do ar |
| 409 | `DUPLICATE_COMMENT` | O usuário enviou o mesmo texto no mesmo lugar há menos de 10 minutos |

### Respostas

Têm um nível só: não há resposta de resposta. A obra e o capítulo vêm do comentário respondido.

`GET /comments/{id}/replies` é público e paginado, das mais antigas para as mais recentes por padrão. `sort`: `createdAt`. Funciona mesmo se o comentário principal foi removido. **200**, no formato acima, com `parentId` preenchido.

`POST /comments/{id}/replies` responde com o mesmo corpo de um comentário. **201**, no formato acima. As regras de `PATCH` e `DELETE` são as mesmas, em `/comments/{id da resposta}`.

| Status | `code` | Quando |
|---|---|---|
| 400 | `NESTED_REPLY` | O comentário informado já é uma resposta |
| 404 | `COMMENT_NOT_FOUND` | Inexistente, removido ou fora do ar. Comentários removidos não aceitam novas respostas |

### Curtidas

Valem para comentários e respostas. Cada pessoa curte uma vez, e ninguém curte o próprio comentário.

`PUT /comments/{id}/like` curte. Envie um corpo vazio. Repetir não é erro. **200**:
```json
{ "liked": true, "likeCount": 6 }
```

`DELETE /comments/{id}/like` remove a curtida, mesmo com o comentário fora do ar. Repetir não é erro. **204**, sem corpo.

`GET /users/me/liked-comments?commentIds=019f...,019f...` diz quais dos comentários informados, até 50, a pessoa curtiu. As listas públicas não trazem isso porque não têm dados pessoais. **200**:
```json
{ "commentIds": ["019f..."] }
```

| Status | `code` | Quando |
|---|---|---|
| 400 | `TOO_MANY_IDS` | Mais de 50 comentários na consulta |
| 403 | `OWN_COMMENT_LIKE` | O comentário é da própria pessoa |
| 404 | `COMMENT_NOT_FOUND` | Inexistente, removido ou fora do ar |

### Avaliações

Cada usuário tem uma nota de 1 a 5 por obra. O autor não avalia a própria obra.

`GET /books/{bookId}/rating` é público. **200**, com `average` igual a `null` enquanto não houver notas:
```json
{ "average": 3.67, "count": 3 }
```

`GET /books/{bookId}/rating/me` traz a nota do usuário, ou `null` em `score`. **200**:
```json
{ "score": 4, "summary": { "average": 3.67, "count": 3 } }
```

`PUT /books/{bookId}/rating/me` grava ou troca a nota. **200**, com o corpo acima.

```json
{ "score": 4 }
```

`DELETE /books/{bookId}/rating/me` remove a nota, mesmo com a obra fora do ar. Repetir não é erro. **204**, sem corpo.

| Status | `code` | Quando |
|---|---|---|
| 403 | `OWN_BOOK_RATING` | O usuário é o autor da obra |

### Reações

Cada usuário tem uma reação por capítulo, e reagir de novo troca a anterior. O autor não reage ao próprio capítulo. Tipos: `LIKE`, `LOVE`, `LAUGH`, `WOW`, `SAD` e `ANGRY`. Tipo desconhecido recebe 400 `BAD_REQUEST`.

`GET /chapters/{chapterId}/reactions` é público. `counts` traz sempre todos os tipos. **200**:
```json
{ "counts": { "LIKE": 2, "LOVE": 1, "LAUGH": 0, "WOW": 0, "SAD": 0, "ANGRY": 0 }, "total": 3 }
```

`GET /chapters/{chapterId}/reactions/me` traz a reação do usuário, ou `null` em `type`. **200**:
```json
{ "type": "LIKE", "summary": { "counts": { "LIKE": 2, "LOVE": 1, "LAUGH": 0, "WOW": 0, "SAD": 0, "ANGRY": 0 }, "total": 3 } }
```

`PUT /chapters/{chapterId}/reactions/me` grava ou troca a reação. **200**, com o corpo acima.

```json
{ "type": "LIKE" }
```

`DELETE /chapters/{chapterId}/reactions/me` remove a reação, mesmo com o capítulo fora do ar. Repetir não é erro. **204**, sem corpo.

| Status | `code` | Quando |
|---|---|---|
| 403 | `OWN_CHAPTER_REACTION` | O usuário é o autor da obra |

### `POST /chapters/{chapterId}/views`

Registra que o usuário abriu o capítulo. Envie um corpo vazio. **204**, sem corpo, tenha a visita contado ou não.

**Chame no navegador, depois de abrir o capítulo e só com o usuário logado.** O SSR não registra visitas, e abrir a rota de leitura não conta nada. Visitantes recebem 401.

O servidor decide o que conta: uma visita por usuário e capítulo a cada 2 minutos, e nunca as do autor da obra. O total da obra é público em `viewCount`. O total de cada capítulo só aparece em `GET /books/{id}/contents`, para o autor e a administração.

## Banners

### `GET /banners`

Banners ativos da página inicial, por `position`. Público, sem paginação. Um banner é ativo se está ligado e dentro do período. **200**:
```json
[{
  "id": "019f...",
  "name": "Novidades de outubro",
  "imageUrl": "https://cdn.example.com/outubro.png",
  "linkUrl": "https://readowl.example.com/obras/sra-shihais",
  "position": 1
}]
```

### Administração

Para `ADMIN` e `ROOT`. Até a etapa de mídia, as imagens são endereços externos.

| Endpoint | O que faz |
|---|---|
| `GET /admin/banners` | Todos os banners, paginados. `sort`: `position`, `name` ou `createdAt`. O padrão é `position` |
| `GET /admin/banners/{id}` | Um banner |
| `POST /admin/banners` | Cria. **201** |
| `PUT /admin/banners/{id}` | Substitui todos os campos, com `version`. **200** |
| `DELETE /admin/banners/{id}` | Exclui. **204** |

```json
{
  "version": 0,
  "name": "Novidades de outubro",
  "imageUrl": "https://cdn.example.com/outubro.png",
  "linkUrl": "https://readowl.example.com/obras/sra-shihais",
  "position": 1,
  "enabled": true,
  "activeFrom": "2026-10-01T00:00:00Z",
  "activeUntil": null
}
```

| Campo | Regra |
|---|---|
| `version` | Só em `PUT`: a versão lida |
| `name` | Até 200 caracteres, não vazio |
| `imageUrl`, `linkUrl` | Até 1000 caracteres, sempre `https://` com o endereço completo |
| `position` | A partir de 0. Pode repetir |
| `enabled` | Obrigatório |
| `activeFrom`, `activeUntil` | Opcionais. Sem um deles, o período fica em aberto naquele lado |

**200** ou **201**:
```json
{
  "id": "019f...",
  "name": "Novidades de outubro",
  "imageUrl": "https://cdn.example.com/outubro.png",
  "linkUrl": "https://readowl.example.com/obras/sra-shihais",
  "position": 1,
  "enabled": true,
  "activeFrom": "2026-10-01T00:00:00Z",
  "activeUntil": null,
  "version": 0,
  "createdAt": "2026-10-31T19:32:15.393101Z",
  "updatedAt": "2026-10-31T19:32:15.393101Z"
}
```

| Status | `code` | Quando |
|---|---|---|
| 400 | `INVALID_BANNER_URL` | O endereço não é `https://` completo |
| 400 | `INVALID_BANNER_PERIOD` | O fim não é depois do início |
| 404 | `BANNER_NOT_FOUND` | Inexistente |
| 409 | `BANNER_CONFLICT` | Outra pessoa alterou o banner. A resposta traz `currentVersion` |

## Notificações

Exige sessão. Cada usuário só acessa as próprias. Quem segue uma obra é avisado quando um capítulo é publicado pela primeira vez com a obra no ar. Correções e republicações não avisam, nem capítulos publicados com a obra fora do ar.

**A notificação não é imediata:** um processo em segundo plano as cria em até cerca de 15 segundos. A publicação não depende dele.

### `GET /notifications`

Das mais recentes para as mais antigas, paginadas. `unreadOnly=true` mostra só as não lidas. Conteúdo que saiu do ar não aparece e volta se for republicado. Os slugs são os atuais.

**200**, com `content` neste formato:
```json
{
  "id": "019f...",
  "type": "NEW_CHAPTER",
  "read": false,
  "book": { "id": "019f...", "title": "Shihais: Remanescente da Aura", "slug": "sra-shihais-remanescente-da-aura" },
  "chapter": { "id": "019f...", "title": "Prólogo", "slug": "prologo" },
  "createdAt": "2026-10-31T19:32:15.393101Z"
}
```

### `GET /notifications/unread-count`

Para consultar periodicamente. Não há WebSocket. **200**:
```json
{ "unread": 3 }
```

### `PATCH /notifications/{id}`

Marca como lida ou não lida. **204**, sem corpo. Responde 404 `NOTIFICATION_NOT_FOUND` se não existir ou for de outra pessoa.

```json
{ "read": true }
```

### `POST /notifications/read-all`

Marca todas como lidas. Envie um corpo vazio. **204**, sem corpo.

### E-mails

Quem liga `emailNewChapters` em `PATCH /users/me` também recebe um e-mail por capítulo novo, com link para `/obras/{slug da obra}/capitulos/{slug do capítulo}` e instruções para desligar em `/conta`. **Por padrão, está desligado.** Se a pessoa desligar ou o capítulo sair do ar antes do envio, o e-mail não é enviado.

## Denúncias

### `POST /reports`

Exige sessão. Só o que está público pode ser denunciado.

```json
{ "targetType": "COMMENT", "targetId": "019f...", "reason": "ABUSE", "details": "Ofensa direta." }
```

| Campo | Regra |
|---|---|
| `targetType` | `BOOK`, `CHAPTER`, `COMMENT` ou `USER` |
| `reason` | `SPAM`, `ABUSE`, `ILLEGAL_CONTENT`, `COPYRIGHT` ou `OTHER` |
| `details` | Opcional, até 1000 caracteres |

**201**
```json
{
  "id": "019f...",
  "targetType": "COMMENT",
  "targetId": "019f...",
  "reason": "ABUSE",
  "status": "OPEN",
  "createdAt": "2026-10-31T19:32:15.393101Z"
}
```

Limite de 10 denúncias por hora por conta e de 30 por IP.

| Status | `code` | Quando |
|---|---|---|
| 403 | `OWN_CONTENT_REPORT` | O conteúdo é da própria pessoa |
| 404 | `BOOK_NOT_FOUND`, `CHAPTER_NOT_FOUND`, `COMMENT_NOT_FOUND` ou `USER_NOT_FOUND` | Inexistente ou fora do ar |
| 409 | `REPORT_ALREADY_OPEN` | A pessoa já tem uma denúncia em aberto sobre este alvo |

## Moderação

Em `/api/v1/moderation`, para `MODERATOR`, `ADMIN` e `ROOT`. Os demais recebem 403 `ACCESS_DENIED`.

### `GET /moderation/reports`

Paginada, das mais antigas para as mais recentes por padrão. `sort`: `createdAt`. Filtros opcionais: `status` (`OPEN`, `RESOLVED` ou `DISMISSED`) e `targetType`.

**200**, com `content` neste formato, também usado em `GET /moderation/reports/{id}`:
```json
{
  "id": "019f...",
  "targetType": "COMMENT",
  "targetId": "019f...",
  "targetSnapshot": "Texto do comentário no momento da denúncia",
  "targetOwner": { "id": "019f...", "username": "joao" },
  "reason": "ABUSE",
  "details": "Ofensa direta.",
  "reporter": { "id": "019f...", "username": "maria-leitora" },
  "status": "OPEN",
  "resolutionNote": null,
  "resolvedBy": null,
  "resolvedAt": null,
  "createdAt": "2026-10-31T19:32:15.393101Z"
}
```

`targetSnapshot` guarda o conteúdo no momento da denúncia, que continua disponível mesmo se o alvo for editado ou excluído. Em obras traz título e sinopse; em capítulos, título, número da revisão e os primeiros 50 mil caracteres do texto; em comentários, o texto; em usuários, nome de usuário, nome e descrição. **Exiba como texto. Quem denunciou não deve ser revelado fora da moderação.**

### `PATCH /moderation/reports/{id}`

Encerra a denúncia. `note` é opcional, até 1000 caracteres. **200**, com o corpo acima. Encerrar não oculta o conteúdo: use as ações abaixo.

```json
{ "status": "RESOLVED", "note": "Comentário ocultado." }
```

| Status | `code` | Quando |
|---|---|---|
| 400 | `INVALID_REPORT_STATUS` | `status` não é `RESOLVED` nem `DISMISSED` |
| 404 | `REPORT_NOT_FOUND` | Denúncia inexistente |
| 409 | `REPORT_CLOSED` | Já encerrada |
| 403 | `OWN_CONTENT_MODERATION` | A denúncia é contra quem está encerrando. `ADMIN` e `ROOT` podem |

### Ocultar e restaurar

| Endpoint | Efeito |
|---|---|
| `POST /moderation/books/{id}/hide` | Tira a obra do ar. O autor não consegue republicar |
| `POST /moderation/chapters/{id}/hide` | Tira o capítulo do ar. O autor não consegue republicar |
| `POST /moderation/comments/{id}/hide` | Oculta o comentário. A pessoa não consegue editar nem excluir |
| `POST /moderation/books/{id}/restore`, `POST /moderation/chapters/{id}/restore` e `POST /moderation/comments/{id}/restore` | Desfaz a ocultação |

Todas exigem o motivo, de até 500 caracteres, que fica na auditoria. **204**, sem corpo.

```json
{ "reason": "Plágio confirmado." }
```

**Restaurar não republica obras e capítulos:** o autor decide. Comentários voltam a aparecer na hora. O que a própria pessoa excluiu não pode ser restaurado.

| Status | `code` | Quando |
|---|---|---|
| 403 | `OWN_CONTENT_MODERATION` | Ao restaurar, o conteúdo é de quem está pedindo. `ADMIN` e `ROOT` podem |
| 404 | `BOOK_NOT_FOUND`, `CHAPTER_NOT_FOUND` ou `COMMENT_NOT_FOUND` | Inexistente |
| 409 | `ALREADY_HIDDEN` | Já está oculto ou foi excluído pela própria pessoa |
| 409 | `NOT_HIDDEN` | Não está oculto pela moderação |
| 409 | `LAST_PUBLISHED_CHAPTER` | É o último capítulo publicado de uma obra no ar. Oculte a obra |

Suspender contas não é uma ação de moderação: é edição de usuário, exclusiva do `ROOT`, em `PATCH /admin/users/{id}`.

### `GET /moderation/comments`

Todos os comentários de uma obra (`?bookId=`, inclusive os dos capítulos) ou de uma pessoa (`?userId=`), inclusive respostas, excluídos, ocultos e de obras fora do ar. Informe exatamente um dos dois, ou recebe 400 `INVALID_FILTER`. Paginada. `sort`: `createdAt`, com padrão `-createdAt`.

**200**, com `content` neste formato:
```json
{
  "id": "019f...",
  "bookId": "019f...",
  "chapterId": null,
  "parentId": null,
  "content": "Texto",
  "author": { "id": "019f...", "username": "joao", "name": "João" },
  "deletedAt": null,
  "moderatedAt": null,
  "createdAt": "2026-10-31T19:32:15.393101Z",
  "updatedAt": "2026-10-31T19:32:15.393101Z"
}
```

## Volumes e capítulos

Mesmas regras de acesso das obras. Volumes são opcionais: um capítulo pode ficar direto na obra.

### `GET /books/{id}/contents`

**200**
```json
{
  "volumes": [{ "id": "019f...", "title": "Volume 1", "displayOrder": 1 }],
  "chapters": [{
    "id": "019f...",
    "title": "Prólogo",
    "slug": "prologo",
    "state": "DRAFT",
    "displayOrder": 1,
    "volumeId": "019f...",
    "moderated": false,
    "viewCount": 0,
    "createdAt": "2026-10-31T19:32:15.393101Z",
    "updatedAt": "2026-10-31T19:32:15.393101Z"
  }]
}
```

`state` é `DRAFT`, `PUBLISHED` ou `UNPUBLISHED`. `volumeId` é `null` em capítulos sem volume. `displayOrder` é sequencial dentro de cada volume e pode ter lacunas depois de exclusões.

### Volumes

| Endpoint | Corpo | Resposta |
|---|---|---|
| `POST /books/{id}/volumes` | `{ "title": "Volume 1" }` | **201**, o volume |
| `PATCH /volumes/{id}` | `{ "title": "Volume 1" }` | **200**, o volume |
| `DELETE /volumes/{id}` | | **204**. Com capítulos, 409 `VOLUME_NOT_EMPTY` |
| `PUT /books/{id}/volumes/order` | `{ "volumeIds": ["...", "..."] }` | **200**, os volumes na nova ordem |

Criar um volume além da cota responde 409 `VOLUME_LIMIT_REACHED`.

A reordenação recebe **todos** os volumes da obra. Lista incompleta, repetida ou com itens de outra obra recebe 400 `INVALID_ORDER`.

### Capítulos

| Endpoint | Corpo | Resposta |
|---|---|---|
| `POST /books/{id}/chapters` | `{ "title": "Prólogo", "volumeId": "..." }` | **201**, o capítulo |
| `PATCH /chapters/{id}` | `{ "title": "Prólogo" }` | **200**, o capítulo, com o novo `slug` |
| `DELETE /chapters/{id}` | | **204**. Publicado, 409 `CHAPTER_PUBLISHED`. Oculto pela moderação, 409 `CHAPTER_UNDER_MODERATION`, inclusive para administradores |
| `PUT /books/{id}/chapters/order` | `{ "volumeId": "...", "chapterIds": ["..."] }` | **200**, os capítulos na nova ordem |

`volumeId` é opcional na criação e na reordenação. Títulos têm até 200 caracteres. Um `volumeId` de outra obra recebe 400 `VOLUME_NOT_IN_BOOK`. Criar um capítulo além da cota responde 409 `CHAPTER_LIMIT_REACHED`.

A reordenação define a ordem de um volume, ou dos capítulos sem volume quando `volumeId` é omitido. A lista precisa incluir todos os capítulos que **já estão ali**; outros capítulos da obra na lista são **movidos** para lá. Fora disso, 400 `INVALID_ORDER`.

## Rascunho e publicação

O rascunho e o conteúdo publicado são separados: salvar nunca altera o que os leitores veem.

### `GET /chapters/{id}/draft`

**200**
```json
{
  "chapterId": "019f...",
  "version": 3,
  "document": { "type": "doc", "content": [] },
  "formatVersion": 1,
  "updatedAt": "2026-10-31T19:32:15.393101Z"
}
```

### `PUT /chapters/{id}/draft`

Salvamento automático. Envie a `version` que você leu.

```json
{ "version": 3, "document": { "type": "doc", "content": [] } }
```

**200**, com o mesmo corpo do `GET` e a nova `version`. Use-a no próximo salvamento.

| Status | `code` | Quando |
|---|---|---|
| 400 | `INVALID_DOCUMENT` | Fora do formato abaixo. `detail` indica o motivo |
| 409 | `DRAFT_CONFLICT` | Outra aba salvou antes. A resposta traz `currentVersion`. Avise o usuário; não sobrescreva sem perguntar |
| 409 | `CONCURRENT_MODIFICATION` | Dois salvamentos no mesmo instante. Trate como `DRAFT_CONFLICT`, mas sem `currentVersion`: leia o rascunho de novo |

### Formato do documento (versão 1)

É o JSON do Tiptap, com uma **lista fechada** do que é permitido. Qualquer outro tipo, marca, atributo ou campo recusa o documento inteiro. Configure o editor só com estas extensões:

| Tipo | Elementos |
|---|---|
| Blocos | `paragraph`, `heading` (`level` 2 ou 3), `blockquote`, `bulletList`, `orderedList` (`start`), `listItem`, `horizontalRule` |
| Em linha | `text`, `hardBreak` |
| Marcas | `bold`, `italic`, `underline`, `strike` |
| Alinhamento | `textAlign` em `paragraph` e `heading`: `left`, `center`, `right` ou `justify` |

**Links e imagens não são aceitos nesta versão.** O limite é de 2 MiB de JSON, 500 mil caracteres de texto e 20 níveis de aninhamento.

O HTML de leitura é gerado pelo servidor, com todo texto escapado. O alinhamento vira as classes `text-left`, `text-center`, `text-right` e `text-justify`, que o frontend precisa estilizar.

### `POST /chapters/{id}/publish`

Publica o rascunho na versão que o usuário viu.

```json
{ "draftVersion": 3 }
```

**200**, com o capítulo. Um rascunho igual à revisão atual não cria revisão nova.

| Status | `code` | Quando |
|---|---|---|
| 400 | `EMPTY_CHAPTER` | Capítulo sem texto |
| 409 | `DRAFT_CONFLICT` | O rascunho mudou depois de `draftVersion` |
| 409 | `CHAPTER_UNDER_MODERATION` | O capítulo foi ocultado pela moderação. Vale também para administradores |
| 409 | `REVISION_LIMIT_REACHED` | O capítulo atingiu a cota de revisões publicadas |

O capítulo só aparece para leitores quando **a obra também está publicada**.

### `POST /chapters/{id}/unpublish`

Envie um corpo vazio. **200**, com o capítulo. O rascunho e as revisões são mantidos.

| Status | `code` | Quando |
|---|---|---|
| 409 | `LAST_PUBLISHED_CHAPTER` | É o último capítulo publicado de uma obra publicada. Despublique a obra antes |

### `GET /chapters/{id}/revisions`

Histórico de publicações, da mais recente para a mais antiga, paginado. Todas são mantidas; o salvamento automático não gera revisão.

**200**, com `content` neste formato:
```json
{
  "revisionNumber": 2,
  "formatVersion": 1,
  "createdByUsername": "maria-leitora",
  "createdAt": "2026-10-31T19:32:15.393101Z",
  "published": true
}
```

`createdByUsername` é quem publicou, que pode ser um administrador.

## Administração

Em `/api/v1/admin`. Responde 403 `ACCESS_DENIED` a quem não tem o papel exigido.

| Endpoint | Quem | O que faz |
|---|---|---|
| `GET /admin/books` | `ADMIN`, `ROOT` | Todas as obras, paginadas, no formato de `GET /users/me/books` |
| `POST /admin/genres`, `POST /admin/tags` | `ADMIN`, `ROOT` | Cria. Corpo: `{ "name": "..." }`. **201** |
| `PATCH /admin/genres/{id}`, `PATCH /admin/tags/{id}` | `ADMIN`, `ROOT` | Renomeia. Corpo: `{ "name": "..." }`. **200** |
| `DELETE /admin/genres/{id}`, `DELETE /admin/tags/{id}` | `ADMIN`, `ROOT` | Exclui. **204** |
| `GET /admin/users`, `GET /admin/users/{id}` | `ADMIN`, `ROOT` | Consulta usuários. `sort`: `username`, `email` ou `createdAt` |
| `PATCH /admin/users/{id}` | `ROOT` | Edita um usuário |
| `GET /admin/audit-log` | `ADMIN`, `ROOT` | Registro das ações administrativas |

Gêneros e tags podem responder 409 com `GENRE_NAME_TAKEN`, `TAG_NAME_TAKEN`, `GENRE_IN_USE` ou `TAG_IN_USE`, e 404 com `GENRE_NOT_FOUND` ou `TAG_NOT_FOUND`. `DELETE /admin/tags/{id}?detach=true` retira a tag das obras antes de excluí-la, para remover uma tag imprópria já em uso.

### `PATCH /admin/users/{id}`

```json
{ "name": "...", "username": "...", "description": "...", "role": "ADMIN", "status": "ACTIVE" }
```

Campos ausentes não mudam. `status` é `ACTIVE` ou `SUSPENDED`. Senha e e-mail não são editáveis por aqui.

**200**
```json
{
  "id": "019f...",
  "username": "maria-leitora",
  "name": "Maria Leitora",
  "email": "maria@example.com",
  "description": null,
  "role": "USER",
  "status": "ACTIVE",
  "emailVerified": true,
  "createdAt": "2026-10-31T19:32:15.393101Z",
  "updatedAt": "2026-10-31T19:32:15.393101Z"
}
```

**Mudar `role` ou `status` encerra todas as sessões do usuário na hora.** Se for a própria conta, o root precisa entrar de novo.

| Status | `code` | Quando |
|---|---|---|
| 404 | `USER_NOT_FOUND` | Usuário inexistente |
| 409 | `USERNAME_TAKEN` | Nome de usuário ocupado ou reservado |
| 409 | `LAST_ROOT` | Removeria a última conta root ativa |

### `GET /admin/access-records`

Registro de acesso de uma conta: logins e escritas, com IP e horário. **Só `ROOT`**, e cada consulta fica na auditoria.

`?userId=...&page=0&size=20`

**200**, paginado:
```json
{ "id": "019f...", "userId": "019f...", "ip": "203.0.113.7", "action": "POST /api/v1/books", "createdAt": "2026-11-01T12:00:00Z" }
```

### `GET /admin/audit-log`

Das entradas mais recentes para as mais antigas, paginada. Filtros opcionais: `actorId` e `resource`, com o valor exato registrado. Nenhuma rota altera ou exclui entradas.

**200**, com `content` neste formato:
```json
{
  "id": "019f...",
  "actorId": "019f...",
  "actorUsername": "root",
  "action": "USER_UPDATED",
  "resource": "users/019f...",
  "outcome": "SUCCESS",
  "correlationId": "cf7b9043-6e9e-40a2-8353-cf9635534d95",
  "details": { "role": { "from": "USER", "to": "MODERATOR" } },
  "createdAt": "2026-10-31T19:32:15.393101Z"
}
```

`outcome` é `SUCCESS`, `FAILED` ou `DENIED`. Corpos de requisição nunca são guardados, então `details` é `null` na maioria das entradas.

| O que é registrado | `action` |
|---|---|
| Edição de usuário, com antes e depois | `USER_UPDATED` |
| Encerramento de denúncia | `REPORT_CLOSED` |
| Ocultar e restaurar, com o motivo | `BOOK_HIDDEN`, `BOOK_RESTORED`, `CHAPTER_HIDDEN`, `CHAPTER_RESTORED`, `COMMENT_HIDDEN`, `COMMENT_RESTORED` |
| Demais alterações em `/admin` e `/moderation`, e tentativas que falham | Método e rota, como `POST /api/v1/admin/tags` |
| Alterações de `ADMIN` e `ROOT` em obras de outras pessoas | Método e rota, como `PATCH /api/v1/books/{id}` |
| Edição e exclusão de comentários de outras pessoas, com o texto | `COMMENT_EDITED`, `COMMENT_DELETED` |
| Exclusão de obras e capítulos de outras pessoas, com título e slug | `BOOK_DELETED`, `CHAPTER_DELETED` |
| Leitura de rascunho de outra pessoa | `GET /api/v1/chapters/{chapterId}/draft` |
| Consulta de usuários, que mostra e-mails | `GET /api/v1/admin/users` e `GET /api/v1/admin/users/{id}` |
| Acesso recusado a `/admin` e `/moderation`, de quem está logado, até 60 por hora para cada conta | Método e caminho, com `outcome` `DENIED` |

As demais leituras não são registradas.

## Operacional

### Verificação de saúde

Pública e sem detalhes internos. Uma falha de SMTP não altera o resultado. O endereço depende do ambiente:

| Ambiente | Endereço |
|---|---|
| Desenvolvimento | `GET /actuator/health` |
| Produção, pelo site | `GET /healthz` |

**200**
```json
{ "status": "UP" }
```

Em desenvolvimento, a resposta também traz `groups`.

**503** quando a aplicação ou o banco estão indisponíveis.

### Respostas do proxy

Em produção, o NGINX responde algumas requisições antes de elas chegarem à aplicação, no mesmo formato de erro e sem `correlationId`:

| Status | `code` | Quando |
|---|---|---|
| 413 | `CONTENT_TOO_LARGE` | Corpo acima de 3 MiB |
| 429 | `RATE_LIMITED` | Acima de 30 requisições por segundo por IP, de 50 conexões abertas, de 2 buscas por segundo em `GET /books?query=` ou de 2 tentativas por segundo em login, cadastro e links de e-mail, com uma pequena folga. `Retry-After` informa a espera |
| 503 | `TEMPORARILY_UNAVAILABLE` | Aplicação fora do ar, como durante uma implantação. `Retry-After` informa a espera |

### Demais rotas

Qualquer outra rota responde **401** `UNAUTHENTICATED` a requisições anônimas.

## O que o frontend precisará configurar

Nada disso foi alterado no projeto Angular.

| Item | Ação |
|---|---|
| Cliente HTTP | Registrar `provideHttpClient` e usar URLs relativas |
| Proxy de desenvolvimento | Encaminhar `/api`, `/oauth2` e `/login/oauth2` para `http://localhost:8080` |
| CSRF em produção | Informar o cookie `__Host-XSRF-TOKEN` em `withXsrfConfiguration`, como na [seção CSRF](#csrf) |
| Busca | Aguardar a pessoa parar de digitar antes de chamar `GET /books?query=`, e tratar 429 esperando o tempo de `Retry-After` |
| SSR | No servidor Node, usar URL absoluta e apenas GETs públicos: os cookies do usuário não são encaminhados. Chamar a aplicação pela rede interna, porque pelo NGINX todas as visitas contariam como um único IP |
| Renderização | `app.routes.server.ts` usa `RenderMode.Prerender` em `**`, que não atende rotas dinâmicas previstas para SSR |
| Rotas novas | `/login`, `/conta`, `/verificar-email`, `/confirmar-email`, `/redefinir-senha` e `/recuperar-senha` |
| Termos e idade | Mostrar os termos no cadastro e enviar `termsVersion` e `ageConfirmed`. Tratar `termsAccepted: false` em `GET /auth/session` |
| Manter conectado | Oferecer a opção no login, com `rememberMe` |
| Conta | Em `/conta`: sessões abertas, troca de e-mail, exportação e exclusão |
| Administração | Tratar 403 `RECENT_LOGIN_REQUIRED` ao trocar papel ou status, pedindo para entrar de novo |
| Obras adultas | Oferecer `contentRating` ao criar e editar a obra. O SSR não envia cookies, então uma obra adulta responde 404 no servidor e só aparece no navegador, com sessão |
| Imagens | Tratar obra e perfil sem capa ou avatar até a etapa de mídia |
| Editor | Adotar o Tiptap com as extensões da [lista do formato](#formato-do-documento-versão-1). Ele ainda não está no `package.json` |
| Leitura | Estilizar as classes `text-left`, `text-center`, `text-right` e `text-justify` |
| Comentários | Exibir como texto, sem `innerHTML`, preservando as quebras de linha. Tratar `removed: true`, sem texto nem autor |
| Curtidas | Depois de carregar uma lista, consultar `GET /users/me/liked-comments`, só com o usuário logado |
| Banners | Os endereços vêm de administradores e são externos: considerar a política de imagens do site |
| Visitas | Chamar `POST /chapters/{id}/views` no navegador ao abrir um capítulo, só com o usuário logado |
| Notificações | Consultar `GET /notifications/unread-count` periodicamente, só com o usuário logado |
| E-mail de capítulo novo | Ter a página `/obras/{slug da obra}/capitulos/{slug do capítulo}` e a opção `emailNewChapters` em `/conta` |

### Pontos a alinhar

- O README cita Angular 22 e Node 24 LTS; o `package.json` usa Angular 21.2 e o Node local é o 26.
- **Se o editor não for o Tiptap, o formato de conteúdo muda.** Avise antes de haver capítulos reais, porque os documentos salvos dependem dele.

## Planejado (ainda não implementado)

Estes grupos ainda não existem e seus caminhos podem mudar:

- **Mídia:** upload de imagens de capa, avatar e banner. Depende de aprovação para adicionar o armazenamento S3.
