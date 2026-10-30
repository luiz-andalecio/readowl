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
  "errors": [{ "field": "name", "message": "must not be blank" }]
}
```

### Códigos

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
| 500 | `INTERNAL_ERROR` | Erro inesperado |

Outros erros de protocolo usam o nome do status HTTP como `code`, por exemplo `UNSUPPORTED_MEDIA_TYPE`. Os casos de uso acrescentarão códigos próprios conforme forem implementados.

## CSRF

Toda requisição que altera estado (`POST`, `PUT`, `PATCH`, `DELETE`) precisa do token CSRF. Sem ele, a resposta é 403 com `CSRF_TOKEN_INVALID`.

| Item | Valor |
|---|---|
| Cookie (legível por JavaScript) | `XSRF-TOKEN` |
| Header de envio | `X-XSRF-TOKEN` |

Esses são os nomes padrão do `HttpClient` do Angular, que envia o header automaticamente em requisições com **URL relativa** que alteram estado.

> **Pode mudar na próxima etapa:** hoje o cookie é emitido em qualquer resposta a um cliente que ainda não o tem. A etapa de autenticação deve restringir essa emissão para não atrapalhar o cache de páginas públicas, com um endpoint próprio para obter o token.

## Sessão

A autenticação será por cookie de sessão `HttpOnly`, inacessível ao JavaScript. **Não armazene tokens de autenticação no navegador.** Requisições anônimas não criam sessão.

## Endpoints implementados

### `GET /actuator/health`

Verificação operacional da aplicação. Público, sem detalhes internos.

**200**
```json
{ "status": "UP", "groups": ["liveness", "readiness"] }
```

**503** quando a aplicação ou o banco estão indisponíveis.

### Demais rotas

Qualquer outra rota responde **401** `UNAUTHENTICATED` a requisições anônimas: ainda não há endpoints de negócio.

## O que o frontend precisará configurar

Nada disso foi alterado no projeto Angular.

| Item | Ação |
|---|---|
| Cliente HTTP | Registrar `provideHttpClient` e usar URLs relativas |
| Proxy de desenvolvimento | Encaminhar `/api`, `/oauth2` e `/login/oauth2` para `http://localhost:8080` |
| SSR | No servidor Node, usar URL absoluta e apenas GETs públicos: os cookies do usuário não são encaminhados |
| Renderização | `app.routes.server.ts` usa `RenderMode.Prerender` em `**`, que não atende rotas dinâmicas previstas para SSR |
| Imagens | Tratar obra e perfil sem capa ou avatar até a etapa de mídia |

### Pontos a alinhar

- O README cita Angular 22 e Node 24 LTS; o `package.json` usa Angular 21.2 e o Node local é o 26.
- O modelo de capítulos armazena o documento do editor em JSON, conforme o README, assumindo Tiptap. **Se o editor mudar, o formato de conteúdo muda:** avise antes da etapa de publicação.

## Planejado (ainda não implementado)

Estes grupos ainda não existem e seus caminhos podem mudar:

- **Contas:** cadastro, verificação de e-mail, login, logout, sessão atual, recuperação de senha, login com Google, perfil.
- **Obras e publicação:** obras, gêneros, tags, volumes, capítulos, rascunhos, publicação.
- **Leitura e biblioteca:** catálogo, busca, leitura pública, obras seguidas, progresso.
- **Comunidade:** comentários, avaliações, reações, visualizações.
- **Operação:** notificações, denúncias, moderação, administração.
- **Depois do núcleo:** respostas e curtidas em comentários, banners, upload de imagens.
