# A corrigir — itens ainda abertos e implementáveis

Consolidado em 2026-08-20 a partir de `audit-silencio-incoerencia-falha.md`, `doc-divergences.md`,
`pending.md`, `pending-fixes-consolidated.md` e `season-series-auto-completion.md` — os cinco arquivos
foram apagados depois daquela consolidação (histórico deles vive só no git, se precisar recuperar).

**Atualizado em 2026-08-24**, incorporando tudo de `openapi-review-2026-08-21.md`,
`performance-and-scale-review-2026-08-21.md` e `problems.md` (2026-08-22) — os três arquivos-fonte
continuam existindo separadamente, isto aqui é a fusão dos três + o que já estava neste arquivo, com
duplicatas entre eles unificadas em um item só e cada item reconferido contra o código atual em
2026-08-24 (não só copiado dos docs). Itens que já estavam corrigidos nos arquivos-fonte, ou que se
mostraram corrigidos nessa reconferência, foram movidos para a seção "Já corrigido" no fim, e não
para a lista de pendências.

**Atualizado em 2026-08-25:** os itens marcados `CORRIGIDO` (antigos #1-#9, #12 e #14, todos da seção
"Alta severidade" e parte da "Média severidade") foram reconferidos um a um contra o código atual e
removidos deste arquivo — não só o marcador, o item inteiro — por já estarem implementados de fato
(verificado lendo o código, não só a nota do próprio arquivo). A seção "Alta severidade" ficou vazia e
foi removida. Os itens restantes foram renumerados sequencialmente.

**Atualizado em 2026-08-25 (2):** o antigo item 4 (`Top5EntryServiceImpl` — loop `save`/`flush` por
linha) foi removido — não é bug, é decisão deliberada já documentada em `business-rules.md` §Top5Entry:
a mesma troca por `parkPositionsInRange`/`settleParkedPositions` foi tentada e revertida porque
`top5_entries` tem `CHECK (position BETWEEN 1 AND 5)` (`ck_top5_entries_position`), e Postgres valida
`CHECK` linha a linha — o passo de "estacionar" a posição num offset gigante viola esse `CHECK`
imediatamente, mesmo a query seguinte corrigindo o valor na mesma transação. Diferente de
`WatchlistEntry`/`UserListItem`, cujo `position` só tem piso. Como `MAX_ENTRIES = 5`, o loop nunca passa
de 5 iterações — sem problema de escala real a resolver. O antigo item 5 (config de batch insert/update
do JPA ausente) foi corrigido e movido pra "Já corrigido". Itens restantes renumerados novamente.

**Atualizado em 2026-08-25 (3):** item "Paginação de followers/following sem ordenação determinística"
corrigido (decisão de 2026-08-20 reaberta a pedido do usuário) e movido pra "Já corrigido". Item
"Validações de entrada ausentes / clamps silenciosos" também corrigido nas suas duas partes — `@Max`
adicionado em `DiaryEntryBulkCreationDTO` e o clamp silencioso de `pageSize` corrigido nas 8 cópias de
`buildPageRequest` — e movido pra "Já corrigido". Itens restantes renumerados mais uma vez.

**Atualizado em 2026-08-25 (4):** item "`buildPageRequest` duplicado literalmente em 8 services"
corrigido — extraído pra `common.pagination.PageRequestFactory` — e movido pra "Já corrigido". Item
"Ausência de lock pessimista em reordenações concorrentes de `UserListItem`" retirado a pedido do
usuário (risco aceito, sem correção pendente). Itens restantes renumerados mais uma vez.

**Atualizado em 2026-08-25 (5):** item "`computeDeletionImpact` — dry-run mascara o que a cascata do
banco vai apagar" retirado a pedido do usuário (sem correção pendente). Seção "Já corrigido" removida
por completo a pedido do usuário — histórico dela vive no git (`957bb4b` e anteriores), não é mais
necessária neste arquivo. Itens restantes renumerados mais uma vez.

Ficaram de fora, deliberadamente:

- **Secret do JWT em texto puro** (`application-dev.properties:14`) — risco aceito por decisão
  explícita do usuário, perguntado duas vezes (2026-08-16 e 2026-08-17), sem pedido de ação nas duas.
- Os dois itens de "Fora de escopo/incerto" do audit original (rate-limits comentados em
  `application-prod.properties`, CSRF ignorado em `/auth/logout` mitigado por `SameSite=Lax`) — nunca
  chegaram a ser investigados a fundo nem têm uma correção proposta, ficaram como observação, não como
  pendência.
- **`Top5EntryServiceImpl` — loop `save`/`flush` por linha pra deslocar posição** (antigo item 4,
  `performance-and-scale-review-2026-08-21.md` #7) — ver nota "Atualizado em 2026-08-25 (2)" acima;
  incompatível com o `CHECK` de teto da tabela, loop é a implementação correta aqui.
- **Ausência de lock pessimista em reordenações concorrentes de `UserListItem`** (antigo item 5,
  `problems.md` #14) — risco aceito por decisão explícita do usuário em 2026-08-25, sem pedido de ação.
- **`computeDeletionImpact` — dry-run mascara o que a cascata do banco vai apagar** (antigo item 7,
  `pending.md`) — retirado por decisão explícita do usuário em 2026-08-25, sem pedido de ação.

---

## Média severidade

### 1. 🟡 `User.isEmailVerified` sempre `true` — feature de verificação de email morta

**Origem:** `pending.md` (item original deste arquivo) + `problems.md` #8 — mesmo achado, unificado
aqui. **Estado:** não é bug — é o volante já instalado esperando o motor, feature parcialmente
construída (schema + checagem em `login`) mas nunca ativada. Opcional, sem prazo.

`saveNewUser` força `isEmailVerified=true` na criação. Não existe nenhum fluxo (endpoint de
verificação, envio de token, etc.) que crie um usuário com `isEmailVerified=false`. O check em
`login()` (`if (!isEmailVerified) throw new ForbiddenException(...)`) é código morto em produção.

**Passos, nessa ordem, se/quando decidir implementar:**
1. Adicionar dependência de e-mail (`spring-boot-starter-mail`) + configurar um provedor de verdade.
2. Escolher e implementar o mecanismo de token de verificação (tabela nova tipo `RefreshToken`, ou JWT
   com um `TokenType.EMAIL_VERIFICATION` novo).
3. Migration nova, só se for tabela de tokens (contas antigas continuam `true` pra sempre).
4. Endpoint novo de confirmação (`.../verify-email`) + atualizar `openapi.yaml`.
5. Trocar `UserServiceImpl.saveNewUser` pra setar `isEmailVerified = false` no cadastro comum (OAuth
   continua `true`) e disparar o envio do e-mail com o token.
6. Decidir sobre endpoint de reenvio e envio síncrono vs assíncrono.
7. Testes: login recusado quando não verificado, fluxo de confirmação, envio de e-mail interceptado em
   teste.
8. (Opcional dentro do opcional) Job de limpeza de contas não verificadas, reaproveitando o padrão
   `@Scheduled` já usado em `RefreshTokenCleanupJob`.

### 2. 🟡 `GoogleTokenVerifier` mascara falhas de rede como token inválido

**Origem:** `problems.md` #9. **Reconferido em 2026-08-24:** ainda presente
(`GoogleTokenVerifier.java:22` continua com o mesmo `catch` combinado).

**Arquivo:** `common/security/GoogleTokenVerifier.java` (~linhas 22-24)

`catch (GeneralSecurityException | IOException | IllegalArgumentException e)` converte tudo —
inclusive `IOException` por indisponibilidade da API do Google — na mesma mensagem "Invalid or expired
provider token", sem logar a exceção original. Mascara uma falha de infraestrutura como erro do
usuário, dificultando diagnóstico de outages do Google.

### 3. 🟡 `spring.jpa.open-in-view` nunca desabilitado

**Origem:** `performance-and-scale-review-2026-08-21.md` #4. **Reconferido em 2026-08-24:** ainda
presente (`application-prod.properties:6` comentado; não setado em `application.properties`/dev).

Só está setado em `src/test/resources/application-test.properties`. Dev (e prod, quando descomentado)
rodam com o default `open-in-view=true` do Spring Boot, que segura uma conexão de banco aberta pelo
ciclo inteiro de request/response em vez de devolver pro HikariCP assim que a transação termina.
Troca de zero risco — o app já mapeia entidade→DTO inteiramente dentro de métodos `@Transactional` do
service, sem acesso lazy na camada de view.

**Correção:** adicionar `spring.jpa.open-in-view=false` em `application.properties` (ou dev/prod), e
descomentar no template de prod.

### 4. 🟡 Doc: `GET /notifications` e `GET /search` não seguem a convenção de paginação do projeto

**Origem:** `openapi-review-2026-08-21.md` #5. **Reconferido em 2026-08-24:** ainda presente —
`GET /notifications` (openapi.yaml:1609) devolve array puro sem `page`/`size`; `GET /search`
(openapi.yaml:1639) idem.

Todo outro endpoint de listagem envelopa `content` no `PageMeta` (`page`/`size`/`totalElements`/
`totalPages`/`hasNext`) — convenção documentada e deliberada (ver `CLAUDE.md` → Architecture →
Pagination). Notificações em particular não têm limite natural por usuário — isso vira uma
inconsistência real assim que `Notification` for de fato implementado (ainda não existe no código).

**Considerar:** aplicar o mesmo envelope `PageMeta` + params `page`/`size` nos dois, ao menos nas
partes de `SearchResult` que vêm do banco local (`lists`, `users`).

---

## Baixa severidade / informativo

### 5. 🟢 `show-sql`/`format_sql` no template de prod

**Origem:** `performance-and-scale-review-2026-08-21.md` #11.

`application-prod.properties` hoje tem essas flags desligadas junto com o resto do template
comentado, então não há misconfiguração ativa hoje. Só um lembrete pra quem ativar o profile de prod
não esquecer de manter `show-sql=false`/`format_sql=false` (e `open-in-view=false`, item 3 acima)
descomentados — log de SQL é custo real de volume de log/perf em produção quando esse profile rodar
de verdade.

### 6. 🟢 Doc: nenhum endpoint pra listar follow requests *enviados*

**Origem:** `openapi-review-2026-08-21.md` #6. **Reconferido em 2026-08-24:** ainda ausente
(`openapi.yaml` só tem `/users/me/follow-requests`, `.../accept` e `.../{requesterId}`, nenhum
`/sent`).

`GET /users/me/follow-requests` só lista requests *recebidas*. Um usuário que pediu pra seguir um
perfil privado não tem como listar os próprios pedidos pendentes enviados — cancelar um exige chamar
`DELETE /users/{userId}/follow` de novo, o que só funciona se o cliente já lembrar quem foi
requisitado. Pode ser decisão deliberada de escopo, mas é a única assimetria clara do fluxo de follow
que vale um segundo olhar.

**Considerar:** `GET /users/me/follow-requests/sent` (ou um query param `direction` no endpoint
existente) devolvendo os `PENDING` enviados pelo usuário atual.

---

## Bloqueado / decisão pendente

### 7. 🟢 Opcional — verificação de e-mail de verdade

Ver item 1 acima (unificado com `problems.md` #8) — o roadmap completo está lá.
