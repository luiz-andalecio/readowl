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
| `MODERATOR` | Igual a `USER` por enquanto: as ações de moderação ainda não existem |
| `ADMIN` | Tudo o que o `USER` faz, sobre as obras de **qualquer** autor, além de gêneros e tags. Vê usuários, mas não os edita |
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
| 401 | `UNAUTHENTICATED` | Requisição sem sessão válida |
| 403 | `CSRF_TOKEN_INVALID` | Token CSRF ausente ou incorreto |
| 403 | `ACCESS_DENIED` | Sem permissão para a operação |
| 404 | `NOT_FOUND` | Rota inexistente |
| 405 | `METHOD_NOT_ALLOWED` | Método HTTP não suportado na rota |
| 409 | `CONCURRENT_MODIFICATION` | O recurso foi alterado por outra operação |
| 409 | `DATA_CONFLICT` | Conflito com dados existentes |
| 429 | `RATE_LIMITED` | Muitas tentativas. O header `Retry-After` informa os segundos de espera |
| 500 | `INTERNAL_ERROR` | Erro inesperado |

Outros erros de protocolo usam o nome do status HTTP como `code`, por exemplo `UNSUPPORTED_MEDIA_TYPE`. Os códigos de cada operação estão junto dos endpoints.

## CSRF

Toda requisição que altera estado (`POST`, `PUT`, `PATCH`, `DELETE`) precisa do token CSRF, **inclusive login e logout**. Sem ele, a resposta é 403 com `CSRF_TOKEN_INVALID`.

| Item | Valor |
|---|---|
| Cookie (legível por JavaScript) | `XSRF-TOKEN` |
| Header de envio | `X-XSRF-TOKEN` |

Esses são os nomes padrão do `HttpClient` do Angular, que envia o header automaticamente em requisições com **URL relativa** que alteram estado.

**O cookie só é criado sob demanda.** Respostas de leitura não o emitem, o que permite cache de páginas públicas. O fluxo é:

1. Chame `GET /api/v1/auth/csrf` antes do primeiro `POST` da página.
2. Login, troca de senha e logout **descartam** o token. Chame `GET /api/v1/auth/csrf` de novo antes da próxima operação que altera estado.

## Sessão

A autenticação é por cookie de sessão `HttpOnly`, inacessível ao JavaScript. **Não armazene tokens de autenticação no navegador.** Requisições anônimas não criam sessão.

| Item | Valor |
|---|---|
| Nome do cookie | `SESSION` em desenvolvimento; `__Host-SESSION` em produção |
| Atributos | `HttpOnly`, `SameSite=Lax`, `Path=/`; `Secure` em produção |
| Duração | Expira após 14 dias sem uso, e o cookie dura 14 dias a partir do login |

## Autenticação

Todos os endpoints abaixo ficam em `/api/v1/auth`. Os públicos não exigem sessão, mas os `POST` exigem CSRF.

Os limites contam por IP e por conta e respondem 429 com `RATE_LIMITED`:

| Operação | Limite |
|---|---|
| Login | 10 por conta e 30 por IP, a cada 15 minutos |
| Cadastro | 5 por e-mail e 10 por IP, por hora |
| Reenvio de confirmação e recuperação de senha | 3 por e-mail e 10 por IP, por hora |
| Uso de token de e-mail | 20 por IP, a cada 15 minutos |
| Confirmação de senha (troca e vínculo com Google) | 10 por conta, a cada 15 minutos |

### `GET /auth/csrf`

Emite o cookie `XSRF-TOKEN`. Público.

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
    "googleLinked": false
  }
}
```

Os valores de `role` estão em [Papéis](#papéis). Use-os só para adaptar a interface: quem decide o acesso é o backend.

### `POST /auth/register`

Cria uma conta e envia o e-mail de confirmação. Público.

```json
{ "name": "Maria Leitora", "username": "maria-leitora", "email": "maria@example.com", "password": "..." }
```

| Campo | Regra |
|---|---|
| `name` | Até 100 caracteres, não vazio |
| `username` | De 3 a 30 letras, números, `_` ou `-`, começando com letra ou número. É salvo em minúsculas e não pode ser alterado depois |
| `email` | E-mail válido, até 255 caracteres. Maiúsculas são ignoradas |
| `password` | De 8 a 128 caracteres |

**202**, sem corpo. **A resposta é a mesma se o e-mail já tiver conta**, para não revelar quem está cadastrado: nesse caso, o dono do e-mail recebe um aviso em vez da confirmação. Mostre uma mensagem como "Enviamos as instruções para o seu e-mail".

| Status | `code` | Quando |
|---|---|---|
| 409 | `USERNAME_TAKEN` | Nome de usuário ocupado ou reservado |

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
{ "email": "maria@example.com", "password": "..." }
```

**200**, com o mesmo corpo de `GET /auth/session`. Emite o cookie de sessão e descarta o token CSRF.

| Status | `code` | Quando |
|---|---|---|
| 401 | `INVALID_CREDENTIALS` | E-mail ou senha incorretos, ou conta sem senha. Não distingue os casos |
| 403 | `EMAIL_NOT_VERIFIED` | Senha correta, e-mail ainda não confirmado. Ofereça o reenvio |
| 403 | `ACCOUNT_SUSPENDED` | Senha correta, conta suspensa |

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
  "createdAt": "2026-10-31T19:32:15.393101Z"
}
```

### `PATCH /users/me`

Atualiza o perfil. Exige sessão. Campos ausentes não mudam; outros campos enviados são ignorados.

```json
{ "name": "Maria L.", "description": "Leio de tudo." }
```

| Campo | Regra |
|---|---|
| `name` | Até 100 caracteres, não vazio |
| `description` | Até 2000 caracteres. Em branco, remove a descrição |

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

Sugere até 20 tags que começam com o texto informado. Público, para o campo de autocompletar.

**200**, no mesmo formato de `GET /genres`.

## Obras

Exigem sessão e CSRF nas operações que alteram estado.

**Quem pode alterar:** o autor, `ADMIN` e `ROOT`. Para os demais, uma obra fora do ar responde 404 `BOOK_NOT_FOUND`, sem revelar que existe, e uma obra publicada responde 403 `ACCESS_DENIED`.

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
  "authorUsername": "maria-leitora",
  "genres": [{ "id": "019f...", "name": "Fantasia" }],
  "tags": [{ "id": "019f...", "name": "Magia" }],
  "createdAt": "2026-10-31T19:32:15.393101Z",
  "updatedAt": "2026-10-31T19:32:15.393101Z"
}
```

`status` é `ONGOING`, `COMPLETED` ou `HIATUS`. `publicationState` é `DRAFT`, `PUBLISHED` ou `UNPUBLISHED`.

| Status | `code` | Quando |
|---|---|---|
| 400 | `UNKNOWN_GENRE` | Gênero inexistente |
| 409 | `BOOK_SLUG_TAKEN` | Já existe obra com esta sigla e este título. O usuário deve escolher outra sigla |

### `GET /books/{id}/details`

Dados completos da obra para quem pode alterá-la. **200**, com o corpo acima.

### `PATCH /books/{id}`

Aceita os campos de `POST /books` e `status`. Campos ausentes não mudam. `releaseFrequency` em branco é removida. `genreIds` e `tags` substituem a lista inteira.

**200**, com o corpo acima. Pode responder 409 `BOOK_SLUG_TAKEN`, sem alterar nada.

### `DELETE /books/{id}?confirmTitle=<título exato>`

**Exclui a obra com capítulos, comentários e avaliações, sem volta.** Peça ao usuário para digitar o título.

**204**, sem corpo.

| Status | `code` | Quando |
|---|---|---|
| 400 | `TITLE_CONFIRMATION_MISMATCH` | O título não confere |
| 409 | `BOOK_PUBLISHED` | Despublique a obra antes |

### `POST /books/{id}/publish` e `POST /books/{id}/unpublish`

Envie um corpo vazio. **200**, com o corpo de `GET /books/{id}/details`.

| Status | `code` | Quando |
|---|---|---|
| 409 | `BOOK_WITHOUT_PUBLISHED_CHAPTERS` | Publique ao menos um capítulo antes da obra |

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
  "authorUsername": "maria-leitora",
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

A reordenação recebe **todos** os volumes da obra. Lista incompleta, repetida ou com itens de outra obra recebe 400 `INVALID_ORDER`.

### Capítulos

| Endpoint | Corpo | Resposta |
|---|---|---|
| `POST /books/{id}/chapters` | `{ "title": "Prólogo", "volumeId": "..." }` | **201**, o capítulo |
| `PATCH /chapters/{id}` | `{ "title": "Prólogo" }` | **200**, o capítulo, com o novo `slug` |
| `DELETE /chapters/{id}` | | **204**. Publicado, 409 `CHAPTER_PUBLISHED` |
| `PUT /books/{id}/chapters/order` | `{ "volumeId": "...", "chapterIds": ["..."] }` | **200**, os capítulos na nova ordem |

`volumeId` é opcional na criação e na reordenação. Títulos têm até 200 caracteres. Um `volumeId` de outra obra recebe 400 `VOLUME_NOT_IN_BOOK`.

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

Gêneros e tags podem responder 409 com `GENRE_NAME_TAKEN`, `TAG_NAME_TAKEN`, `GENRE_IN_USE` ou `TAG_IN_USE`, e 404 com `GENRE_NOT_FOUND` ou `TAG_NOT_FOUND`.

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

## Operacional

### `GET /actuator/health`

Verificação operacional da aplicação. Público, sem detalhes internos. Uma falha de SMTP não altera o resultado.

**200**
```json
{ "status": "UP", "groups": ["liveness", "readiness"] }
```

**503** quando a aplicação ou o banco estão indisponíveis.

### Demais rotas

Qualquer outra rota responde **401** `UNAUTHENTICATED` a requisições anônimas.

## O que o frontend precisará configurar

Nada disso foi alterado no projeto Angular.

| Item | Ação |
|---|---|
| Cliente HTTP | Registrar `provideHttpClient` e usar URLs relativas |
| Proxy de desenvolvimento | Encaminhar `/api`, `/oauth2` e `/login/oauth2` para `http://localhost:8080` |
| SSR | No servidor Node, usar URL absoluta e apenas GETs públicos: os cookies do usuário não são encaminhados |
| Renderização | `app.routes.server.ts` usa `RenderMode.Prerender` em `**`, que não atende rotas dinâmicas previstas para SSR |
| Rotas novas | `/login`, `/conta`, `/verificar-email`, `/redefinir-senha` e `/recuperar-senha` |
| Imagens | Tratar obra e perfil sem capa ou avatar até a etapa de mídia |
| Editor | Adotar o Tiptap com as extensões da [lista do formato](#formato-do-documento-versão-1). Ele ainda não está no `package.json` |
| Leitura | Estilizar as classes `text-left`, `text-center`, `text-right` e `text-justify` |

### Pontos a alinhar

- O README cita Angular 22 e Node 24 LTS; o `package.json` usa Angular 21.2 e o Node local é o 26.
- **Se o editor não for o Tiptap, o formato de conteúdo muda.** Avise antes de haver capítulos reais, porque os documentos salvos dependem dele.

## Planejado (ainda não implementado)

Estes grupos ainda não existem e seus caminhos podem mudar:

- **Leitura e biblioteca:** catálogo, busca, leitura pública, obras seguidas, progresso.
- **Comunidade:** comentários, avaliações, reações, visualizações.
- **Operação:** notificações, denúncias, moderação, administração.
- **Depois do núcleo:** respostas e curtidas em comentários, banners, upload de imagens.
