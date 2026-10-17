# Resumo do projeto — Watchwise API

Este documento é uma síntese de tudo que já foi construído, organizada por categoria (não por ordem
cronológica — para isso, ver `progress.md`). Cada bloco segue o quê foi implementado, como foi
implementado e por quê. Padrões repetidos por várias features são agrupados numa categoria só; quando
uma feature reaproveita o padrão mas tem uma lógica própria adicional, isso é destacado separadamente
dentro do bloco dela.

---

## 1. Visão geral

Watchwise é uma rede social para acompanhar, avaliar e comentar filmes, séries, temporadas e episódios.
O backend é uma API REST em Spring Boot 4.1 / Java 21, com PostgreSQL (Flyway + Testcontainers), Spring
Data JPA, MapStruct, Lombok, e autenticação stateless via JWT entregue em cookies httpOnly com proteção
CSRF. Filmes/séries/elenco nunca são armazenados no banco — só uma referência leve (`Content`) liga as
interações de um usuário a uma peça de mídia do TMDB.

Estado atual do build order: Fases 1–6 completas (`User`, `Content`, `Follower`, `FollowedPerson`,
`Top5Entry`, `WatchlistEntry`, `DroppedEntry`, `DiaryEntry`, `UserList`+`UserListItem`, `Comment`,
`Like`). Restam `Notification` (Fase 7) e as agregações `Summary`/`Search` (Fase 8).

---

## 2. Fundação: `User` e `Auth`

**O quê:** entidade `User` (base de FK de quase todo o resto do modelo), CRUD de perfil
(`GET/PATCH/DELETE /users/me`, `GET /users/{userId}`, `GET /users` por username), e todo o fluxo de
autenticação: registro/login, JWT em cookie httpOnly, refresh token rotacionável, logout/logout-all,
OAuth Google, verificação de e-mail, rate limiting e lockout de tentativas, invalidação de sessão.

**Por quê:** é a implementação de referência de camadas (Entity → Repository → Service → Mapper → DTOs
→ Controller) usada por toda entidade seguinte, e autenticação via cookie httpOnly (em vez de header
`Authorization: Bearer`) evita expor o token a JavaScript no navegador (mitiga XSS) — o que por sua vez
exige proteção CSRF, já que cookies são enviados automaticamente pelo navegador.

**Como (mecanismos específicos do domínio Auth, não reaproveitados por outras features):**
- `JwtCookieAuthenticationFilter` lê o cookie `access_token` e popula o `SecurityContextHolder` antes do
  `UsernamePasswordAuthenticationFilter`; o path do cookie de refresh token é montado dinamicamente a
  partir do `context-path` real da aplicação (bug corrigido em 15/08 — o path fixo `/auth/refresh`
  nunca batia com `/api/v1/auth/refresh`, invisível nos testes porque `MockMvc` usa context-path vazio
  por padrão).
- Refresh tokens são persistidos (`RefreshTokenRepository`) para permitir revogação/rotação; reuso de
  um token já revogado revoga todos os tokens daquele usuário (assume conta comprometida) — reforçado
  com `@Version` (lock otimista) para que duas rotações concorrentes do mesmo token não emitam dois
  pares válidos em paralelo.
- CSRF via `CookieCsrfTokenRepository` + `SpaCsrfTokenRequestHandler`, rotacionado explicitamente em
  register/login (`AuthController.rotateCsrfToken`); `/auth/register`/`/auth/login` rejeitam requests já
  autenticadas checando `!(authentication instanceof AnonymousAuthenticationToken)`.
- `User.sessionsInvalidatedAt`: `logout-all` grava `now()` truncado ao segundo; o filtro compara o `iat`
  de todo `access_token` contra esse timestamp — invalida sessões dentro dos 60min de expiração natural
  do JWT sem precisar de denylist de `jti`. A mesma invalidação roda em toda troca de senha/e-mail.
  Registrada como `TransactionSynchronization.afterCommit()` quando chamada de dentro de uma transação
  ambiente já aberta (`updateUser`), para evitar deadlock com o lock não commitado da linha `users`.
- Rate limiting em duas camadas: `AttemptLockout` (por identifier — ex. username tentando login) e
  `RequestThrottler` (N requisições/janela por IP, independente de sucesso/falha) — `login` ganhou
  throttle por IP depois que uma auditoria mostrou que só ele, entre os endpoints anônimos, não tinha.
  `willChangeCredentials`/`checkCredentialChanges` compara valor real (não presença de campo) para
  decidir se uma troca de senha/e-mail deve resetar o contador de tentativas falhas.
- `isSessionStillValid` devolve `false` (nunca autentica) quando o `userId` do token não existe mais em
  `users` (conta deletada) — bug de segurança corrigido em 20/08, e otimizado em 24/08 para consultar
  uma projeção (`findSessionsInvalidatedAtById`) em vez da entidade `User` inteira em toda requisição
  autenticada.
- `GlobalExceptionHandler` (`@RestControllerAdvice`, estende `ResponseEntityExceptionHandler`) é o
  ponto central de tradução de exceções tipadas (`NotFoundException`/`BadRequestException`/
  `ConflictException`) em `ApiError`; ganhou handlers explícitos para os casos que
  `ResponseEntityExceptionHandler` já cobre nativamente mas com o `ProblemDetail` padrão do Spring
  (corpo malformado, `Content-Type` não suportado, tipo incompatível em path variable, 405, 404 de
  rota), e por fim um `@ExceptionHandler(Exception.class)` catch-all que loga a exceção real
  server-side mas nunca vaza mensagem/stack trace ao cliente.

---

## 3. `Content`: referência ao TMDB

**O quê:** entidade que guarda só `type` (`MOVIE`/`SERIES`/`SEASON`/`EPISODE`) + os IDs necessários para
reconstruir a consulta ao TMDB (`tmdbId` para movie/series; `seriesTmdbId`+`seasonNumber`[+`episodeNumber`]
para season/episode), mais duas flags cliente-supridas: `isSeasonFinale`/`isSeriesFinale`.

**Por quê:** TMDB não tem endpoint de busca direta por ID para temporada/episódio (só a rota composta
`/tv/{seriesId}/season/{seasonNumber}[...]`), então `Content` guarda o que é necessário para reconstruir
essa rota. As flags de finale existem porque o backend não consulta o TMDB para saber se um
episódio/temporada é o último — o cliente informa isso ao logar, permitindo a auto-conclusão de
temporada/série no diário (ver seção 6) sem round-trip externo.

**Como:** `getOrCreateReference` é o primeiro e principal exemplo do padrão "get-or-create idempotente"
(seção 4.d). `Content` é deliberadamente imutável — a única exceção documentada é a transferência de
`isSeasonFinale`/`isSeriesFinale` para um episódio/temporada mais recente
(`clearPreviousSeasonFinale`/`clearPreviousSeriesFinale`, só avança nunca retrocede; um valor
igual-ou-anterior é rejeitado com `409` em vez de transferir). Reenviar um `Content` já existente com uma
flag de finale diferente da persistida é rejeitado com `409` (`assertNoFinaleMismatch`) em vez de
devolver a referência antiga silenciosamente. `ContentRefCreationDTO` valida `episodeNumber` como
`@Positive` e `seasonNumber` como `@PositiveOrZero` (temporada 0 "Specials" do TMDB é legítima), espelhado
por `CHECK` no banco — sem isso, um `episodeNumber: 0` alimentava um laço de catch-up de auto-conclusão
praticamente ilimitado (bug corrigido em 16/08).

---

## 4. Padrões arquiteturais compartilhados

Estes são os mecanismos que se repetem, com pequenas variações, em múltiplas features. Documentados uma
vez aqui; as seções de cada entidade (5–8) só apontam para eles e destacam o que é próprio.

### 4.a Camadas e mapeamento
Toda feature segue `Controller → Service (interface) → ServiceImpl → Repository (Spring Data JPA) /
Mapper (MapStruct) → Entity`. DTOs são records; entidades são Lombok + builder. Mappers MapStruct usam
`unmappedTargetPolicy = ReportingPolicy.ERROR` (todo campo precisa mapeamento explícito ou `@Mapping`
de ignore — falha o build se algo for esquecido). Entidades com FK de dono/alvo (`Top5Entry`,
`WatchlistEntry`, `DroppedEntry`, `UserList`, `UserListItem`, `Comment`, `Like`) são construídas via
builder direto no service, não via um método DTO→entity do mapper — esse padrão só faz sentido para
`User`, que não tem FK de dono para injetar depois.

### 4.b Paginação: `PageRequestFactory` + `PageResponseDTO`
**O quê:** toda listagem paginada usa `PageRequestFactory.build(...)` (normaliza página 1-based do
cliente para 0-based do Spring, aplica `DEFAULT_PAGE`/`DEFAULT_PAGE_SIZE`/`MAX_PAGE_SIZE`, valida
página/tamanho inválidos com `400`) e devolve um envelope `PageResponseDTO` (`content`, `page`, `size`,
`totalElements`, `totalPages`, `hasNext`) em vez de array puro.

**Por quê:** todo endpoint paginado já paga o custo de uma query `COUNT` internamente via `Page<T>` —
expor esse metadata no corpo não tem custo adicional, então descartá-lo (como faria `Slice`) seria
perder informação de graça. O clamp em `MAX_PAGE_SIZE` (1000) existia desde a criação, mas um bug
tratava `pageSize > 1000` como se fosse `null` (caindo no default de 20) em vez de clampar — corrigido
em 25/08 nas 8 cópias então existentes do método.

**Como evoluiu:** originalmente cada `*ServiceImpl` tinha seu próprio `buildPageRequest` duplicado (8
cópias idênticas, incluindo a variante com `sortBy`/`sortDirection` de `UserServiceImpl`). Extraído em
25/08 para `common.pagination.PageRequestFactory`, um `@Component` único injetado nos 8 services —
única fonte de verdade das constantes e da aritmética de paginação, testada uma vez em
`PageRequestFactoryTest` em vez de repetida em cada suíte de serviço.

### 4.c Regra de visibilidade/privacidade
Duas variantes convivem no projeto:

- **Visibilidade por perfil** (a mais comum): dono sempre vê; qualquer outro viewer só vê se o perfil do
  dono for público, ou privado mas o viewer seguir com `FollowStatus.ACCEPTED`. Aplicada em
  `getFollowers`/`getFollowing`, `getTop5`, `getWatchlist`, `getDropped`, `getDiaryEntries`, e (a partir
  de `Comment`) também num acesso direto por id a uma `DiaryEntry` de terceiro
  (`assertDiaryEntryIsVisibleTo` — até `Comment`, só a listagem checava visibilidade, nunca um acesso
  direto, já que `PATCH`/`DELETE` de diário sempre foram só-dono).
- **Visibilidade em três estados por recurso** (`UserList`, via `UserListVisibility`:
  `PUBLIC`/`FOLLOWERS`/`PRIVATE`): não depende do perfil do dono ser público ou privado — uma lista
  `PUBLIC` é acessível mesmo com o perfil do dono privado. Implementada em `assertListIsVisibleTo`,
  reaproveitada (duplicada, não compartilhada — decisão consciente) por `CommentServiceImpl` e
  `LikeServiceImpl` quando o alvo é uma `UserList`.

Ambas devolvem `403` (não `404`) quando o recurso existe mas está fora de alcance — exceto o padrão de
"não revelar posse", usado em ownership (`findOwnedEntry`/`findOwnedList`/`findOwnedItem`): editar ou
apagar um recurso que não existe ou pertence a outro usuário sempre devolve `404`, nunca `403`, para não
revelar a existência de um recurso alheio.

### 4.d Get-or-create idempotente sob corrida
**O quê:** para qualquer service method que busca-ou-cria por chave natural (`Content.getOrCreateReference`,
`FollowedPersonServiceImpl.followPerson`, `DroppedEntryServiceImpl.markAsDropped`,
`LikeServiceImpl.likeComment`/`likeDiaryEntry`/`likeList`, `DiaryEntryServiceImpl.persistAutoGeneratedEntry`),
duas chamadas concorrentes para o mesmo recurso ainda-não-existente não podem ambas falhar com `500`.

**Por quê e como:** check-then-act simples (lookup, depois save) tem uma janela de corrida onde ambas as
chamadas passam no lookup e tentam inserir, uma delas colidindo com a constraint única. A correção
consolidada: `existsBy...` antes de tentar (evita trabalho redundante quando já existe), tentativa de
`saveAndFlush` isolada em `common.transaction.NewTransactionExecutor.runInNewTransaction`
(`@Transactional(propagation = Propagation.REQUIRES_NEW)`) — necessário porque um `saveAndFlush` que
falha aborta a transação física imediatamente, e se essa transação fosse a mesma do chamador (ex.
`DiaryEntryServiceImpl.createDiaryEntry`, já `@Transactional`), a re-consulta de recuperação também
falharia com "current transaction is aborted" no Postgres — descoberto e corrigido em 15/08, documentado
como regra permanente em `CLAUDE.md` para todo caso futuro. A entidade é sempre construída *dentro* do
mesmo lambda `REQUIRES_NEW` quando referencia outra entidade via `getReferenceById` (proxy preso à
sessão ambiente), evitando o erro do Hibernate de associar um proxy a duas sessões. No `catch` de
`DataIntegrityViolationException`, uma nova busca por chave natural é feita; só se essa segunda busca
também não achar nada a exceção original é relançada (sinal de erro real de banco, não corrida).

### 4.e Deslocamento de posição (reorder/shift)
Três domínios têm listas ordenadas por `position` com invariante de unicidade
(`user_id`+`type`+`position`, ou `user_list_id`+`position`), mas com técnicas diferentes conforme o teto
existe ou não:

- **`Top5Entry`** (teto rígido de 5, `CHECK BETWEEN 1 AND 5`): shift item a item via loop (`save`+`flush`
  por linha deslocada), sempre processando da posição mais alta para a mais baixa ao inserir e da mais
  baixa para a mais alta ao remover — evita colidir com a constraint no meio da transação. Uma tentativa
  de trocar essa implementação pela técnica de "park/settle" abaixo foi revertida em 21/08: o `CHECK`
  do Postgres é validado por linha, não no fim da transação, então "estacionar" numa posição fora da
  faixa 1–5 viola o `CHECK` imediatamente — o loop continua sendo a implementação correta aqui, e,
  dado o teto de 5, não tem problema real de escala.
- **`WatchlistEntry`** e **`UserListItem`** (sem teto superior, só `position >= 1`): a versão inicial
  também usava loop item a item, mas isso custava `O(N)` round-trips por operação (e `O(N²)` num lote) em
  listas grandes. Corrigido (20–21/08) com a técnica de "park then settle": duas queries `UPDATE` em
  massa — a primeira desloca o intervalo afetado para um offset astronomicamente distante
  (`POSITION_PARK_OFFSET = 1_000_000_000`, `parkPositionsInRange`), a segunda assenta no valor final
  (`settlePositionsInRange`) — correta independente da ordem interna de processamento do Postgres porque
  o offset nunca colide com nenhuma posição real, viva ou ainda-não-processada; validada empiricamente
  contra um Postgres 16 real via container descartável antes de codar (um `UPDATE` único com
  `position ± 1` não bastaria, já que a constraint não é `DEFERRABLE`). `moveEntry`/`updateItem` também
  usam uma posição temporária fora da faixa válida para a própria entrada sendo movida, pela mesma razão.

### 4.f Alvo polimórfico
**O quê:** três entidades apontam para "um entre N alvos possíveis" via colunas FK nullable +
`CHECK` garantindo exatamente uma preenchida:
- `Content` (dois alvos: movie/series usam `tmdbId`; season/episode usam `seriesTmdbId`+`seasonNumber`).
- `UserListItem` (dois alvos: `content_id` ou `child_list_id` — nunca os dois, nunca nenhum).
- `Comment` (três alvos: `content_id`/`list_id`/`diary_entry_id`).
- `Like` (três alvos: `comment_id`/`diary_entry_id`/`list_id` — o terceiro adicionado depois, por
  migration separada, quando curtir uma lista diretamente foi pedido).

**Por quê:** a mesma ação (comentar, curtir, ser item de uma lista) se aplica a alvos de domínio
diferentes, e um `CHECK ck_<tabela>_target` no banco garante a invariante mesmo contra um bug de
aplicação — a técnica de "coluna oposta sempre `NULL`" também permite `UNIQUE` por alvo sem colidir
(`NULL` não colide em `UNIQUE` no Postgres).

### 4.g Conflitos de unicidade e mensagens de erro
Violações de `unique constraint` não são pré-checadas — o service deixa a constraint do banco disparar,
captura `DataIntegrityViolationException`, extrai o nome via `ConstraintViolationException`, e mapeia
nomes conhecidos (`uq_<tabela>_<coluna>`) para mensagens específicas de `ConflictException`, com
fallback genérico para constraint desconhecida ou causa não reconhecida.

### 4.h Otimização de N+1 e queries em lote
Padrão recorrente numa auditoria de performance dedicada (20–21/08 e continuada em 24–25/08): qualquer
associação `@ManyToOne(fetch = LAZY)` acessada por linha de uma página (`content`, `follower`/`followed`,
`user`, `childList`) sofria N+1 — corrigido trocando finders derivados por `@Query` com `JOIN FETCH`
(seguro em `@ManyToOne`, diferente do problema clássico de paginação em memória que `JOIN FETCH` causa
em `*ToMany`). Onde um valor é calculado por item de uma página inteira (preview de conteúdo de lista,
contagem de listas aninhadas, percentual assistido, `likedByMe`), o método existe em duas formas: uma
por-recurso-único (reusada por `getById`/`update`/`create`) e uma em lote
(`Collection<UUID> -> Map<UUID, ...>`, usada pela listagem paginada) — reduzindo o custo de `1 + kN`
para um número fixo de queries por página. Índices compostos cobrindo `(FK, coluna de ordenação)` foram
adicionados nas queries de maior tráfego (`diary_entries(user_id, created_at)`,
`diary_entries(user_id, watched_date)`, `comments(<fk>, created_at)`), e configuração de batch do
Hibernate (`hibernate.jdbc.batch_size=25`, `order_inserts`/`order_updates`, `reWriteBatchedInserts` na
URL JDBC) foi adicionada para que `saveAll` de fato emita statements multi-linha — segura porque toda
entidade usa `@GeneratedValue(strategy = GenerationType.UUID)` (sem ID gerado pelo banco quebrando o
batching).

---

## 5. Follower e FollowedPerson

**O quê:** `Follower` (usuário segue usuário, com fluxo de solicitação pendente/aceito/recusado para
perfis privados) e `FollowedPerson` (usuário segue uma pessoa do TMDB — ator/diretor —, sem aprovação,
já que o alvo não é um `User` local).

**Por quê:** contas privadas exigem aprovação para serem seguidas; seguir uma pessoa do TMDB não tem
esse conceito porque não há um "dono" da relação do outro lado.

**Como (além dos padrões da seção 4):** `acceptAllPendingFollowRequestsFor` — na transição
`false → true` de `isProfilePublic`, todas as solicitações `PENDING` recebidas são aceitas em cascata
(bulk `@Modifying @Query`) — antes ficavam presas para sempre. Paginação de `getFollowers`/`getFollowing`
ganhou `ORDER BY created_at DESC` hardcoded na query (decisão revertida depois de inicialmente aceita
como "padrão do projeto sem ordenação").

---

## 6. `Top5Entry`, `WatchlistEntry`, `DroppedEntry`

Os três seguem o mesmo esqueleto — entidade escopada por `user_id`+`type` (`MOVIE`/`SERIES`), visibilidade
de perfil (seção 4.c), get-or-create de `Content` (seção 4.d quando aplicável) — mas cada um tem uma regra
própria de posição/estado:

- **`Top5Entry`**: exatamente 5 posições por tipo; inserir numa posição desloca as seguintes e descarta
  quem cai na posição 6 (eviction); não existe endpoint de "mover", só inserir e remover.
- **`WatchlistEntry`**: sem teto; inserir sempre vai para o fim (decisão de produto — o corpo do `POST`
  não aceita mais `position`); mover é um endpoint `PATCH` dedicado (`moveEntry`), usando a técnica de
  park/settle (seção 4.e).
- **`DroppedEntry`**: sem posição alguma (marcação idempotente por `userId`+`type`+`contentId`, com
  `comment` opcional de motivo, upsertável numa remarcação).

**Efeito colateral cruzado:** logar um conteúdo no diário (`POST /diary`) ou marcá-lo como abandonado
remove automaticamente a entrada correspondente da watchlist (`removeEntryIfPresent`, variante idempotente
sem `404`) — gap reportado pelo usuário e corrigido depois que o efeito já estava documentado mas nunca
implementado. Essa remoção precisou virar parte da mesma transação do `markAsDropped` (24/08) para não
deixar um item removido da watchlist "perdido" caso a criação do `DroppedEntry` falhasse depois.

---

## 7. `DiaryEntry` — logging, avaliação e review

Não existe entidade `Rating` separada: logar, avaliar e escrever review são a mesma ação, com
`score`/`comment` opcionais direto em `DiaryEntry`. É a feature mais complexa do projeto por causa da
cascata de auto-conclusão.

### 7.a CRUD base
`createDiaryEntry` resolve/cria o `Content` (get-or-create), sem checagem de unicidade (reassistir é
permitido); `updateDiaryEntry` trata os campos como patch (`null` = não mexe) mas nunca permite trocar o
`content` associado (decisão de que reaproveitar o registro para outro conteúdo não faz sentido — se
mudou o conteúdo, é uma entrada nova). `watchedInTheater` só pode ser setado quando o `Content` é `MOVIE`.

### 7.b `watchNumber` e cascata de auto-conclusão
**O quê:** logar um episódio marcado como finale de temporada auto-cria uma `DiaryEntry` de temporada
(`autoGenerated = true`); se essa também completa a série, auto-cria a de série — cascata recursiva
(`triggerCompletionCascade` → `maybeCompleteSeason` → `maybeCompleteSeries`).

**Por quê:** o app quer refletir "assisti a temporada inteira" sem exigir uma ação manual extra do
usuário, e sem round-trip ao TMDB (as flags de finale já vêm do cliente, ver seção 3).

**Como:** reconhece múltiplas passadas completas (rewatch), não só a primeira — `minEpisodeWatchCount`/
`minSeasonWatchMax` calculam quantas passadas completas já foram logadas (mínimo de vezes que cada
episódio/temporada do conjunto aparece), e um laço `while` cria uma entrada auto-gerada por passada em
aberto. `watchNumber` (inteiro, substituiu o antigo `isRewatch` booleano no contrato da API) identifica a
passada. A criação da entrada auto-gerada usa o padrão get-or-create idempotente (seção 4.d,
`persistAutoGeneratedEntry`) porque duas requisições concorrentes completando a mesma passada podem
colidir na constraint `uq_diary_entries_user_content_watch_number`.

Apagar uma `DiaryEntry` retrai (apaga) a entrada auto-gerada correspondente, mas nunca uma editada
manualmente (`autoGenerated = false` protege), com threshold recalculado a cada delete para lidar com
múltiplas passadas (`retractSeasonIfIncomplete`/`retractSeriesIfIncomplete`). Apagar diretamente uma
entrada de nível `SERIES` limpa todo o histórico auto-gerado daquela série/passada
(`wipeSeriesHistory`, escopado por `watchNumber` desde a correção de 24/08 — antes apagava todos os
ciclos de rewatch, não só o afetado). Um parâmetro `overrideProtectedEntries` (query param em `DELETE`,
default `false`) permite, com confirmação explícita, incluir também entradas manuais na cascata.

### 7.c Preview de impacto e resposta de criação
`GET /diary/{id}/deletion-impact` simula o efeito de um `DELETE` sem persistir — implementado como uma
transação real que executa o delete e as mesmas queries de candidatos do delete de verdade, depois marca
`setRollbackOnly()` antes de montar a resposta (dry-run transacional, correto por construção porque
reusa a lógica real sem reimplementá-la à mão — uma primeira versão que recalculava a fórmula manualmente
tinha falsos positivos/negativos).

`POST /diary` devolve um envelope `{ entry, completedSeason, completedSeries }`
(`DiaryEntryCreationResultDTO`) em vez do `DiaryEntry` nu, expondo o que a cascata de conclusão criou na
mesma chamada (breaking change aceito, pré-lançamento).

### 7.d Bulk logging
`POST /diary/bulk` loga uma temporada ou série inteira numa chamada, reusando toda a maquinaria de
criação/cascata acima por episódio (mesmo comportamento de `watchNumber` e auto-completude que um log
individual). Limitado a 100 episódios no total; exige o número do episódio/temporada final via corpo ou
inferido de um `EPISODE`/`SEASON` já marcado como finale no banco (com `seasonFinaleEpisodeNumbers`,
mapa por temporada, cobrindo o caso de bulk-logar uma série do zero sem nenhum finale conhecido ainda).
Rate limit próprio, mais restrito que o do log individual.

---

## 8. `UserList` e `UserListItem`

**O quê:** listas customizadas de usuário (`UserList`), cujos itens (`UserListItem`) podem ser um
`Content` ou outra `UserList` aninhada ("lista de listas").

**Por quê "lista de lista":** decisão de produto do usuário — uma lista pode curar outras listas, com
regras para não criar recursão nem ambiguidade de tipo.

**Como (regras próprias, além dos padrões da seção 4):**
- Profundidade máxima de um nível, sem ciclo, sem auto-referência (`ck_user_list_items_no_self_reference`,
  `assertListIsNotLockedAsListOfLists`/`existsByChildListId` para bloquear encadeamento).
- Uma lista trava como "de conteúdo" ou "de listas" a partir do primeiro item, nunca mistura.
- Uma lista-de-listas nunca recebe `Comment` nem `Like` diretamente.
- `childListId` pode apontar tanto para uma lista própria quanto para uma lista `PUBLIC` de outro
  usuário (curadoria de listas de terceiros).
- Visibilidade em três estados (`PUBLIC`/`FOLLOWERS`/`PRIVATE`), independente do perfil do dono (seção
  4.c) — refatorado a partir de um booleano `isPublic` inicial.
- `watchedPercentage` é calculado por *viewer*, não por dono da lista (cada pessoa vê seu próprio
  progresso sobre os itens de conteúdo de uma mesma lista pública).
- `PATCH /lists/{listId}` é patch parcial de verdade (campo omitido = não muda); `PATCH
  /lists/{listId}/items/{itemId}` edita `position`/`description` de um item sem precisar remover e
  recriar (perdendo `id`/`createdAt`).
- Criação em lote (`POST /users/me/lists/bulk`, `POST /lists/{listId}/items/bulk`) é tudo-ou-nada (uma
  falha desfaz o lote inteiro) e batched de verdade desde 24/08 (checagens de posse/lock rodam uma vez
  antes do loop, entidades montadas em memória com posições pré-calculadas, `saveAll` único).
- Apagar uma `UserList` que está aninhada como item de outra lista precisa fechar o buraco de posição na
  lista-pai também (não só deixar o `ON DELETE CASCADE` remover o item silenciosamente) — bug corrigido
  em 24/08, cobrindo o caso de a mesma lista estar aninhada em várias listas-pai de donos diferentes.

---

## 9. `Comment` e `Like`

Ambos são alvos polimórficos (seção 4.f) sobre o mesmo conjunto de destinos (`Content` sempre visível,
`UserList` com visibilidade em três estados, `DiaryEntry` com visibilidade por perfil — seção 4.c
reaplicada, não compartilhada em código).

**`Comment`:** suporta resposta a outro comentário (`parentCommentId`, sem limite de profundidade), que
precisa apontar para um comentário do mesmo alvo (`400` caso contrário). Uma lista-de-listas nunca aceita
comentário.

**`Like`:** três alvos (`Comment`, `DiaryEntry`, `UserList` — o terceiro adicionado depois, por pedido
explícito do usuário, mesma trava de lista-de-listas de `Comment`). Curtir é idempotente (get-or-create,
seção 4.d); descurtir é delete-if-present sem `404`. `likesCount` é desnormalizado em `Comment`/
`DiaryEntry`/`UserList` (incrementado/decrementado via `@Modifying UPDATE`, na mesma transação do
save/delete do `Like`) para não exigir `COUNT` a cada leitura; `likedByMe` por página é resolvido em lote
(seção 4.h). Um bug de corrida real foi corrigido em 25/08: `unlikeX` fazia find-then-delete, e duas
chamadas concorrentes podiam ambas rodar `decrementLikesCount` mesmo quando só uma linha existia para
apagar — substituído por `DELETE` em massa (`deleteByUserIdAndX`, retorna `int` linhas afetadas),
serializado pelo lock de linha do próprio `DELETE` no Postgres, decrementando só quando de fato apagou
algo.

---

## 10. Processo de auditoria contínua

Um padrão recorrente a partir de 15/08: auditorias dedicadas (segurança, silêncio/incoerência/falha,
performance/escala, revisão do `openapi.yaml` contra a implementação real) catalogam achados em
documentos de pendência (consolidados depois em `docs/pending/to-fix.md`), revisitados e corrigidos
sessão a sessão, item por item, sempre com teste novo provando o comportamento antigo (incorreto) e o
novo. Esse processo é a origem da maior parte das correções de concorrência/atomicidade, N+1 e validação
de entrada listadas nas seções 4.d, 4.h e 7 acima — não foram encontradas ao implementar a feature
original, mas em revisões estruturadas posteriores.
