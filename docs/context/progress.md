# Progresso do projeto

Última atualização: 2026-08-20.

Este documento resume o que já foi construído no Watchwise API, em ordem cronológica por dia de
desenvolvimento: o quê, por quê e como. Serve como retrato do estado atual do projeto — para a visão
de arquitetura permanente (convenções, padrões, como fazer as coisas neste código) o documento de
referência continua sendo o `CLAUDE.md` na raiz do repositório; para o roteiro planejado de fases
futuras, `docs/context/development-stages.md`.

## Visão geral

Watchwise é uma rede social para acompanhar, avaliar e comentar filmes, séries, temporadas e
episódios. O backend (`watchwise-api`) é uma API REST em Spring Boot 4.1 / Java 21, com PostgreSQL
(Flyway para migrations, Testcontainers nos testes de repositório), Spring Data JPA, MapStruct para
mapeamento entidade/DTO, Lombok, e autenticação stateless via JWT entregue em cookies httpOnly com
proteção CSRF.

Dado de arquitetura central: filmes, séries, elenco e premiações **nunca são armazenados no banco** —
essa informação sempre vem da API do TMDB. O banco guarda só uma referência leve (`Content`) usada
como chave interna para ligar as interações de um usuário (comentário, avaliação, diário, listas,
top5 etc.) a uma peça de mídia.

---

## ✅ 2026-08-05 — User: fundação

O quê: setup do projeto (Maven, docker-compose do Postgres) e a entidade `User`, com repositório,
`PostUserDTO`, service, `UserResponseDTO` e `UserMapper` (MapStruct).

Por quê: quase toda tabela do modelo lógico tem FK para `User` — é a base sobre a qual tudo mais é
construído, e serviu como implementação de referência de camadas (Entity → Repository → Service →
Mapper → DTOs) para todas as entidades seguintes.

Como: migration inicial `V1__create-users-table.sql`; primeira versão do bloco de exceções tipadas
(`BadRequestException`, `ConflictException`, `NotFoundException`, `ApiError`) que viria a se tornar o
`GlobalExceptionHandler`.

## ✅ 2026-08-06 — User: consultas

O quê: `getUserById` e busca de usuários por username (paginada) no service, com query customizada no
repositório; primeiros testes de repositório (`UserRepositoryTest`, Testcontainers).

Por quê: base para os endpoints de consulta de perfil que viriam depois.

## ✅ 2026-08-07 — User: atualização parcial e visibilidade; Auth: login e cookies JWT

O quê:
- Atualização parcial de perfil (`PatchUserDTO`) e restrição de visualização de perfil a contas
  públicas (`ForbiddenException`, primeira versão do `SecurityConfig`).
- Login (`LoginUserDTO`) e tratamento de erro de não autorizado (`UnauthorizedException`).
- Autenticação JWT entregue via cookie httpOnly, com CSRF e CORS (`JwtService`,
  `JwtCookieAuthenticationFilter`, `CookieUtil`, `AuthController` com `register`/`login`).
- Persistência de refresh tokens para permitir revogação (entidade `RefreshToken`,
  `RefreshTokenRepository`, migration `V2__create-refresh-tokens-table.sql`).

Por quê: autenticação stateless via JWT, mas em cookie `httpOnly` (não acessível a JavaScript no
navegador, mitigando XSS) em vez de header `Authorization: Bearer` — isso por sua vez exige proteção
CSRF, já que cookies são enviados automaticamente pelo navegador. Persistir o refresh token
server-side é o que torna possível revogá-lo depois (logout, detecção de reuso).

Como: `JwtCookieAuthenticationFilter` lê o cookie `access_token` e popula o `SecurityContextHolder`
antes do `UsernamePasswordAuthenticationFilter`.

## ✅ 2026-08-08 — Auth: refresh/logout/CSRF hardening; User: endpoints de `/users/me`

O quê, em sequência ao longo do dia:
- CSRF SPA handler (`SpaCsrfTokenRequestHandler`), `JsonAuthenticationEntryPoint` e suporte a cookie
  de refresh token.
- Fix: `createdAt`/`updatedAt` passam a ser setados no create e no update.
- `POST /auth/refresh` com rotação de refresh token.
- `POST /auth/logout` com revogação de refresh token e limpeza de cookies.
- Fix: `GlobalExceptionHandler` registrado como bean `@RestControllerAdvice` (não estava sendo
  aplicado).
- Teste de integração de auth cobrindo login, rotação de refresh, logout e cookie CSRF.
- `JsonAccessDeniedHandler`: corpo JSON consistente para respostas de acesso negado (ex. CSRF
  ausente), em vez do body default do Spring Security.
- Teste de integração de segurança verificando que rotas protegidas exigem autenticação por padrão e
  que CSRF é reforçado.
- `updatedAt` incluído no `UserResponseDTO`.
- `getCurrentUser` e `deleteAccount` no service de `User`.
- `PATCH /users/me`, resolvendo o usuário autenticado a partir do contexto de segurança.
- Fix: cookies de auth passam a ser escritos via `HttpServletResponse.addCookie` para parar de
  sobrescrever o cookie CSRF.
- Fix: `CsrfAuthenticationStrategy` parou de limpar o token CSRF a cada requisição autenticada
  stateless.
- Rotação explícita do token CSRF em `register` e `login`, em vez de depender da estratégia padrão
  por-requisição.
- `GET /users/me`.

Por quê: fechar, no mesmo dia em que o fluxo de auth nasceu, os bugs sutis de interação entre cookies
de sessão e cookie CSRF (sobrescrita, limpeza indevida) antes que endpoints futuros passassem a
depender desse mecanismo já quebrado.

## ✅ 2026-08-09 — User: endpoints restantes; Content: fundação

O quê, em sequência ao longo do dia:
- Cobertura de testes: `updateCurrentUser`, login/registro de auth, lacunas de refresh.
- `GET /users/{userId}`.
- `GET /users` (busca por username).
- Fix: username marcado como obrigatório em `GET /users` e tratamento consistente de parâmetros
  ausentes.
- Fix: rejeitar `register` quando a sessão já está autenticada.
- Fix: rejeitar `login` quando a sessão já está autenticada.
- `GET /users` e `GET /users/{userId}` liberados como `permitAll` para navegação anônima (política
  revertida depois, em 12/08 — ver abaixo).
- `DELETE /users/me`.
- Cobertura de testes adicionais: 404 de token obsoleto, conflito real (não mockado), payload
  inválido, offset de paginação.
- `Content`: entidade (`type` + `tmdbId` ou `seriesTmdbId`/`seasonNumber`/`episodeNumber`),
  repositório, service com get-or-create idempotente, testes — migration
  `V3__create-contents-table.sql`.
- Fix: trim de identificadores de entrada e erros consistentes para falhas em nível de framework
  (corpo malformado, `Content-Type` não suportado).

Por quê (`Content`): TMDB não tem endpoint de busca direta por ID para temporada/episódio (só a rota
composta `/tv/{seriesId}/season/{seasonNumber}[...]`), então `Content` guarda o que é necessário para
reconstruir essa rota em vez do ID próprio da temporada/episódio, que sozinho não serve para consulta.

Como (`Content`): `getOrCreateReference` busca por chave natural primeiro; como duas chamadas
concorrentes para o mesmo conteúdo ainda não existente poderiam ambas falhar no lookup e tentar
inserir, o `save` é envolto em try/catch para `DataIntegrityViolationException` — no catch, refaz o
mesmo lookup e retorna o registro já existente em vez de lançar erro.

*(Sem commits em 2026-08-10.)*

## ✅ 2026-08-11 — User/Auth hardening avançado; Follower

O quê, em sequência ao longo do dia:
- `docs: update application docs` — atualização geral da documentação do projeto (`CLAUDE.md`,
  `docs/context/*`).
- `isEmailVerified` na entidade `User`, exigido no login (migration
  `V4__add-email-verified-to-users.sql`).
- `POST /auth/oauth/{provider}` (Google) via `GoogleTokenVerifier`/`GoogleOAuthConfig`, e username
  passa a ser case-insensitive (migration `V5__make-username-case-insensitive.sql`).
- Refactor: e-mail removido do payload do JWT (mantém só `sub`/userId).
- Reuso de um refresh token já revogado passa a revogar todos os refresh tokens daquele usuário
  (assume conta comprometida).
- `POST /auth/logout-all` e fechamento de lacunas de cobertura de teste.
- Rate limiting em memória para tentativas de login (`LoginRateLimiter`, antecessor do
  `AttemptLockout` atual).
- Job agendado (`RefreshTokenCleanupJob`, `@Scheduled`) de limpeza de refresh tokens
  expirados/revogados.
- `Follower`: entidade (`FollowStatus` pendente/aceito), repositório e service com fluxo de
  solicitação de follow — migration `V6__create-followers-table.sql`.
- `FollowerController`: endpoints de seguidores, seguindo, solicitar seguir, aceitar/recusar
  solicitação.

Por quê: `isEmailVerified` na entidade precisava entrar antes de outras fases dependerem dela; o
e-mail no JWT era PII desnecessária num token apenas assinado, não criptografado; sem limite de
tentativas, `/auth/login` era vulnerável a credential stuffing; sem rotina de purge, a tabela
`refresh_tokens` cresceria indefinidamente. `Follower` precisa do conceito de solicitação pendente
porque contas privadas exigem aprovação para serem seguidas — um simples "seguir direto" não bastaria.

## ✅ 2026-08-12 — FollowedPerson; políticas de acesso e segurança final

O quê, em sequência ao longo do dia:
- `FollowedPerson`: entidade, repositório, service (migration
  `V7__create-followed-people-table.sql`).
- `FollowedPersonController`.
- Refactor: rota de follow-people padronizada sob `/users/me/follow-people/{personTmdbId}`,
  consistente com o padrão `/users/me/**` já usado em outros recursos do usuário autenticado.
- `GET /users/{userId}/follow-people`, para listar as pessoas seguidas por qualquer usuário.
- Toda rota passa a exigir sessão autenticada, exceto `/auth/**` e `/error` — remove o `permitAll`
  temporário de `GET /users`/`GET /users/{userId}` criado em 09/08.
- `PATCH /users/me` passa a exigir `currentPassword` para trocar `password` ou `email`.
- Rate limiting em follow, follow-people e `POST /contents/reference`: `LoginRateLimiter` generalizado
  para `AttemptLockout` (reusável por ação: login, delete-account, patch-account), e novo componente
  `RequestThrottler` (N requisições/janela, independente de sucesso/falha) aplicado a busca de perfil,
  follow-action, follow-people-action e content-reference, cada um com bucket de chave dedicado
  (alguns compartilhados entre endpoints para impedir dobrar o limite alternando entre eles).

Por quê: `FollowedPerson` é distinto de `Follower` porque o alvo não é um `User` da base — é uma
referência externa ao TMDB, sem necessidade de solicitação/aprovação. A política de acesso
"autenticado por padrão" fecha a superfície de dados que a navegação anônima tinha reaberto. O rate
limiting cobre endpoints com risco de scraping (busca de perfil), spam em massa (follow,
follow-people) ou inflar a tabela `contents` sem limite (o get-or-create de `Content` não valida
contra o TMDB).

## ✅ 2026-08-13 — Top5Entry: entity, repository, service e controller; DiaryEntry: entity, repository, service e controller

O quê, em sequência ao longo do dia:
- Entidade `Top5Entry`, repositório e testes — migration `V8__create-top5-entries-table.sql`
  (`user_id`/`content_id` FK com `ON DELETE CASCADE`, `type` restrito a `MOVIE`/`SERIES`
  (`CHECK`, `VARCHAR(6)`), `position` com `CHECK BETWEEN 1 AND 5`, `uq_top5_entries_user_id_type_position`
  e `uq_top5_entries_user_id_type_content_id`). `Top5EntryRepository` com
  `findByUserIdAndTypeOrderByPositionAsc`.
- `Top5EntryService`/`Top5EntryServiceImpl`: `getTop5` (lista por usuário+tipo, com a mesma regra de
  privacidade de `getFollowers` — perfil privado só visível pro dono ou por um seguidor aceito),
  `insertEntry` (insere numa posição, deslocando as seguintes uma casa para baixo e descartando quem
  estiver na posição 5) e `removeEntry` (remove e fecha o buraco deslocando as seguintes uma casa para
  cima) — ambas as escritas em `@Transactional`, com `flush()` explícito a cada linha deslocada pra
  evitar colisão passageira com a constraint de posição única. `Top5EntryCreationDTO`/
  `Top5EntryResponseDTO` e `Top5EntryMapper` (reaproveita `ContentMapper`).
- `Top5EntryController`: `GET /users/{userId}/top5/{type}` e `POST`/`DELETE /users/me/top5/{type}` —
  insert/remove padronizados sob `/me`, mesmo padrão de `PATCH /users/me` e follow-people, já que só
  fazem sentido pro usuário autenticado. Testes unitários (service mockado) e de integração
  (`@SpringBootTest` + `MockMvc` + Testcontainers) cobrindo happy path, shift/evicção real via SQL,
  404, 409, 400 (validação e regra de negócio), 401, 403 (CSRF) e o caso de perfil privado.
- Fix: `insertEntry` tinha o `try/catch` de `DataIntegrityViolationException` em volta de um
  `save()` sem `flush()` — como o método é `@Transactional`, o Hibernate adiava o `INSERT` real até o
  commit da transação, então a violação da constraint de unicidade só aparecia depois que o método (e
  o `try/catch`) já tinham retornado, escapando pro handler genérico em vez de virar `ConflictException`.
  Encontrado pelo teste de integração de conflito real (não mockado); corrigido com `flush()` explícito
  dentro do `try`.
- Refactor: corpo de `POST /users/me/top5/{type}` trocou o `content: ContentRefCreation` aninhado por
  um `tmdbId: string` direto — `Top5EntryCreationDTO.content` virou `Top5EntryCreationDTO.tmdbId`, e
  `Top5EntryServiceImpl.insertEntry` monta o `ContentRefCreationDTO` internamente a partir do `type`
  do path antes de chamar `contentService.getOrCreateReference`. Isso eliminou a checagem
  `content.type() != type` (e o teste associado a ela) — o descasamento deixou de ser representável na
  entrada, já que o `type` nunca mais vem duplicado no corpo.

Por quê: top5 é por tipo — um usuário tem até 5 filmes e até 5 séries, cada grupo com sua própria
numeração de posição — por isso `type` faz parte das duas constraints de unicidade, não só
`user_id`. Não existe endpoint de "substituir a lista inteira" nem de "mover" um item: só inserir
(numa posição, deslocando o resto) e remover (fechando o buraco) — decisão registrada em
`development-stages.md`/`database-schema.html` antes da entidade. O deslocamento em cadeia (inserir
sempre processa da posição mais alta pra mais baixa, remover sempre da mais baixa pra mais alta)
evita violar a constraint de posição única no meio da transação.

Entidade `DiaryEntry` (Fase 3, `LOG` do modelo lógico), repositório e testes — migration
`V9__create-diary-entries-table.sql` (`user_id`/`content_id` FK com `ON DELETE CASCADE`, `score`
opcional com `CHECK BETWEEN 1 AND 10`, `comment` (`TEXT`), `watched_date`, `is_rewatch` (`NOT NULL
DEFAULT FALSE`), `watched_in_theater` e `custom_poster_url` todos opcionais). `DiaryEntryRepository`
com `findByUserIdOrderByCreatedAtDesc` (paginado) e `findFirstByUserIdAndContentIdOrderByCreatedAtDesc`
(dá suporte à regra de negócio do `openapi.yaml`: a nota "atual" de um usuário para um conteúdo é a do
`DiaryEntry` mais recente daquele par, não uma média). Não existe mais uma entidade `Rating`/`Avaliacao`
separada — logar, avaliar e escrever review de um conteúdo são a mesma ação, com `score`/`comment` como
campos opcionais direto em `DiaryEntry`; `CLAUDE.md` (tabela de tradução e ordem de fases) foi corrigido
para refletir essa decisão, que já estava em `development-stages.md`/`database-schema.html` mas não
tinha sido propagada.

`DiaryEntryService`/`DiaryEntryServiceImpl`: `getDiaryEntries` (lista paginada por usuário, com o mesmo
filtro opcional `year` do `openapi.yaml` — filtra por `watchedDate`, então entradas sem data assistida
nunca aparecem num filtro por ano; mesma regra de privacidade de `getFollowers`/`getTop5`),
`createDiaryEntry` (resolve/cria o `Content` via `ContentService.getOrCreateReference` e monta a
entrada — sem checagem de unicidade, já que reassistir é permitido), `updateDiaryEntry` e
`deleteDiaryEntry` (dono do recurso validado buscando por id e comparando `entry.getUser().getId()`
com o `userId` autenticado, devolvendo `NotFoundException` — não `ForbiddenException` — quando não bate,
mesmo padrão já usado em `Top5EntryServiceImpl.removeEntry` para não revelar a existência de um recurso
alheio). `updateDiaryEntry` sempre reresolve e substitui o `content` (é campo obrigatório em
`DiaryEntryCreation`, então editar pode inclusive trocar a que conteúdo a entrada aponta), e trata os
demais campos como patch — `null` significa "não mexe", igual ao `UserServiceImpl.applyPatch`.
`DiaryEntryCreationDTO`/`DiaryEntryResponseDTO` e `DiaryEntryMapper` (reaproveita `ContentMapper`).
Repositório ganhou `findByUserIdAndWatchedDateBetweenOrderByCreatedAtDesc` para o filtro por ano, e
`findByUserId` foi renomeado para `findByUserIdOrderByCreatedAtDesc` (diário sem filtro nenhum de ordem
não fazia sentido pra um histórico). Testes unitários (service mockado) cobrindo happy path, paginação,
privacidade, filtro por ano (incluindo ano fora do range aceito por `LocalDate`, que vira
`BadRequestException` em vez de vazar uma exceção não tratada), patch campo a campo e as duas variações
de dono do recurso (não existe / pertence a outro usuário).

`DiaryEntryController`: `GET /users/{userId}/diary`, `POST /diary`, `PATCH /diary/{diaryEntryId}` e
`DELETE /diary/{diaryEntryId}` — sem prefixo comum de classe (diferente de `Top5EntryController`), já
que as rotas vivem em dois namespaces distintos (`/users/{userId}/diary` e `/diary/...`). `PATCH`
devolve `DiaryEntryResponseDTO` no corpo mesmo o `openapi.yaml` documentando só `200: Atualizado` sem
schema — mantém o padrão do resto da API (`PATCH /users/me` já devolve o recurso atualizado) em vez de
um 200 vazio; não é uma divergência do contrato, só preenche uma lacuna de documentação. `POST` e
`PATCH` ganharam rate limiting (`app.rate-limit.diary-action`, 60 requisições/5min, mesma chave para os
dois) porque ambos chamam `ContentService.getOrCreateReference` internamente — o mesmo risco de inflar
a tabela `contents` sem validação contra o TMDB que motivou o rate limit em `/contents/reference`;
`GET`/`DELETE` não chamam esse método, então ficaram de fora. Testes de integração
(`@SpringBootTest` + `MockMvc` + Testcontainers) cobrindo happy path, paginação/filtro por ano,
privacidade de perfil, 404, 400 (validação e ano inválido), 409 não se aplica aqui (sem constraint
única em `diary_entries`), 429 (rate limit), 401 e 403 (CSRF) em todos os endpoints mutáveis, e as duas
variações de dono do recurso em `PATCH`/`DELETE`.

## ✅ 2026-08-14 — DiaryEntry: `isRewatch` automático, `content` imutável no PATCH, `watchedInTheater` restrito a MOVIE; envelope de paginação

`DiaryEntryServiceImpl.createDiaryEntry` agora força `isRewatch = true` sempre que já existe uma
`DiaryEntry` anterior do mesmo usuário para o mesmo `Content` (via
`DiaryEntryRepository.findFirstByUserIdAndContentIdOrderByCreatedAtDesc`, que já existia desde a etapa
anterior mas ainda não era usado no service), independente do valor enviado pelo cliente no
`isRewatch` do `DiaryEntryCreationDTO`.

`PATCH /diary/{diaryEntryId}` deixou de aceitar `content`: `content` agora é imutável após a criação de
uma `DiaryEntry` — não faz sentido reaproveitar o mesmo registro de diário para apontar para outro
conteúdo, o correto é criar uma nova entrada. Criado `DiaryEntryUpdateDTO` (sem o campo `content`, só
`comment`/`score`/`watchedDate`/`isRewatch`/`watchedInTheater`/`customPosterUrl`) e `DiaryEntryUpdate`
em `openapi.yaml`, substituindo o reaproveitamento do `DiaryEntryCreation` no corpo do PATCH.
`DiaryEntryServiceImpl.updateDiaryEntry` não chama mais `ContentService.getOrCreateReference` nem toca
em `entry.content`.

`watchedInTheater` agora só pode ser definido (não-nulo) quando o `Content` da entrada é do tipo
`MOVIE` — `BadRequestException` caso contrário, validada tanto em `createDiaryEntry` (contra o tipo do
`ContentRefDTO` recém-resolvido) quanto em `updateDiaryEntry` (contra o tipo do `Content` já associado
à entrada, já que `content` não muda mais no PATCH). `openapi.yaml` documenta a restrição na descrição
do campo em `DiaryEntryCreation` e `DiaryEntryUpdate`.

Testes unitários novos em `DiaryEntryServiceImplTest` e de integração em
`DiaryEntryControllerIntegrationTest` cobrindo o forçamento de `isRewatch`, a rejeição de
`watchedInTheater` para tipos diferentes de `MOVIE` (criação e atualização) e a imutabilidade de
`content` no PATCH.

Todos os endpoints paginados passaram a devolver um envelope de página em vez de um array puro: novo
`PageResponseDTO<T>` (`common/dto/PageResponseDTO.java`), com `content`/`page`/`size`/`totalElements`/
`totalPages`/`hasNext` — `page` no formato 1-based da API pública, não o 0-based interno do Spring Data.
Os 6 controllers com endpoint paginado já implementado (`UserController.getUsersByUsername`,
`FollowerController.getFollowers`/`getFollowing`/`getPendingFollowRequests`,
`FollowedPersonController.getFollowedPeople`, `DiaryEntryController.getDiaryEntries`) trocaram
`Page<T>.getContent()` por `PageResponseDTO.of(page)`; o service layer não mudou, continua devolvendo
`Page<T>`. A decisão veio de uma discussão com o usuário: como todo endpoint paginado já usa
`Page<T>`/`buildPageRequest`, o Spring Data JPA já executa a query de `COUNT` internamente mesmo hoje —
expor esse metadata no corpo da resposta não tem custo adicional no banco, então não fazia sentido
descartá-lo. `openapi.yaml` ganhou um schema reaproveitável `PageMeta` combinado via `allOf` com um
`content` específico de cada endpoint, aplicado nos 6 endpoints já implementados e também nos 3 ainda
não implementados (`/contents/{contentId}/comments`, `/lists/{listId}/comments`,
`/diary/{diaryEntryId}/comments`, que dependem da feature `Comment`, ainda não construída) para já
nascerem consistentes com a nova convenção. `CLAUDE.md` (seção Pagination) documenta o padrão. Testes de
controller (mockados) e de integração dos 6 endpoints atualizados para validar o novo formato de
resposta, incluindo metadata de paginação (`totalElements`, `totalPages`, `hasNext`) nos casos de
múltiplas páginas.

**Adição posterior (mesmo dia):** Auto-conclusão de temporada/série no diário. Adicionadas as flags `isSeasonFinale` (em `EPISODE`) e `isSeriesFinale` (em `EPISODE` ou `SEASON`) à entidade `Content` — campos client-supplied, imutáveis após criação, que indicam se um episódio/temporada é o último de sua série/temporada. O backend não chama TMDB para descobrir isso; o cliente faz o trabalho de consultar a API do TMDB e marcar ao logar a entrada. Adicionado o campo `autoGenerated` à entidade `DiaryEntry`, sinalizando se a entrada foi criada manualmente pelo usuário ou automaticamente pelo sistema. Implementada a cascata de auto-conclusão em `DiaryEntryServiceImpl.createDiaryEntry` (via `triggerCompletionCascade` → `maybeCompleteSeason` → `maybeCompleteSeries`): sempre que o usuário loga um episódio marcado `isSeasonFinale = true`, o sistema verifica se todos os episódios daquela temporada já foram logados (independente de ordem de assistência); se sim, auto-cria uma `DiaryEntry` para a temporada com `autoGenerated = true`, `comment`/`score` nulos, e `watchedDate` igual à entrada do episódio. Mesmo padrão se repete em cascata: se a entrada auto-criada da temporada é a última da série (todos os episódios de todas as temporadas logados), auto-cria a entrada da série. Implementada a cascata de retração em `DiaryEntryServiceImpl.deleteDiaryEntry`: apagar um episódio ou temporada que "sustentava" uma auto-conclusão retrai a `DiaryEntry` auto-criada correspondente (só afeta entradas com `autoGenerated = true`; entradas criadas manualmente pelo usuário nunca são removidas por essa lógica, mesmo que os episódios que as "sustentavam" tenham sido apagados depois). Testes unitários e de integração cobrindo a detecção de episódios/temporadas finais e a cascata de criação/retração de entradas auto-geradas. `openapi.yaml` atualizado com as novas propriedades em `ContentRefCreation` e `ContentRef`, e `autoGenerated` em `DiaryEntry`; `docs/context/business-rules.md` documenta as regras de negócio da cascata em duas novas bullets na seção `## DiaryEntry`; `CLAUDE.md` clarifica a deliberada exceção de armazenar `isSeasonFinale`/`isSeriesFinale` na tabela `contents` na seção "Avoid".

**Adição posterior (mesmo dia):** Revisão final do branch inteiro (as 10 tasks já implementadas e revisadas individualmente) encontrou 3 problemas que só apareciam com tudo junto. Primeiro, a `ConflictException` de conflito de finale nunca chegava ao cliente — virava um 500 não tratado, porque `Content.id` usa `@GeneratedValue` em memória e o `save()` original só enfileirava o `INSERT`, que só executava no próximo flush automático, fora do `try/catch` de `getOrCreateReference`; corrigido trocando `save` por `saveAndFlush` para o `INSERT` (e qualquer violação de constraint) acontecer de forma síncrona dentro do bloco. Segundo, a cascata de conclusão podia bloquear permanentemente a escrita principal de um usuário quando uma série é revivida com uma temporada posterior (a finale antiga nunca era limpa e colidia para sempre com `uq_contents_series_finale`); corrigido com uma exceção estreita e documentada à imutabilidade de `Content` — `ContentServiceImpl.clearPreviousSeriesFinale` transfere `isSeriesFinale` da temporada-finale antiga para a nova apenas quando a temporada nova tem número maior que o da finale já registrada; se a temporada nova for igual ou anterior, a criação é rejeitada com `409` em vez de transferir (restrição adicionada depois, no commit `7b63496`). Terceiro, a retração de auto-completude apagava silenciosamente uma `DiaryEntry` auto-gerada que o usuário já tinha editado; corrigido fazendo `DiaryEntryServiceImpl.updateDiaryEntry` desligar `autoGenerated` em todo `PATCH` bem-sucedido, mesmo que nenhum campo mude de fato. Adicionado um teste de integração real provando a cadeia completa constraint do Postgres → `DataIntegrityViolationException` → `ConflictException` → `409` (sem mockar nada), além dos testes cobrindo a transferência de `isSeriesFinale` e o novo comportamento de `updateDiaryEntry`. `docs/context/database-schema.html`, `docs/context/openapi.yaml`, `docs/context/business-rules.md` e `CLAUDE.md` atualizados para refletir as três correções.

## ✅ 2026-08-15 — Fix: cookie de refresh token desalinhado do context-path

Auditoria de segurança (4 agentes em paralelo) encontrou um achado crítico: `CookieUtil` gravava o
cookie do refresh token com `Path=/auth/refresh` fixo, mas `server.servlet.context-path=/api/v1` faz o
endpoint real ser `/api/v1/auth/refresh` — o browser nunca enviava o cookie pra lá, então todo refresh
falhava com 401 depois que o access token expirava (60min), derrubando a sessão. `MockMvc` usa
context-path vazio por padrão nos testes, então o bug era invisível na suíte existente.

Corrigido injetando `server.servlet.context-path` (`@Value("${server.servlet.context-path:}")`) no
construtor de `CookieUtil` e montando o path do cookie de refresh dinamicamente
(`contextPath + "/auth/refresh"`) em vez da constante estática `REFRESH_TOKEN_PATH` hardcoded. Novo
método `CookieUtil.getRefreshTokenPath()` substitui a constante nos pontos que limpam o cookie no
logout/logout-all/delete-account (`AuthController`, `UserController`). Testes novos em `CookieUtilTest`
provam o path prefixado com o context-path real (`/api/v1/auth/refresh`) e o comportamento com
context-path vazio (`/auth/refresh`); testes de controller existentes (`AuthControllerTest`,
`UserControllerTest`) ajustados para estubar `getRefreshTokenPath()` em vez de referenciar a constante
removida.

A mesma auditoria apontou que `/auth/login` era o único entre os endpoints anônimos sem throttle por
IP: a chave do `AttemptLockout` combina IP + identifier informado, então um atacante alternando
milhares de identifiers a partir de um único IP (password spraying) nunca acumulava 5 tentativas contra
uma mesma chave, e cada identifier novo inflava o mapa em memória até a limpeza horária. `register`,
`oauth` e `refresh` já tinham `RequestThrottler` por IP; `login` não.

Corrigido adicionando `requestThrottler.checkAllowed(throttleKey("login", request), ...)` em
`AuthController.login`, antes do `attemptLockout.checkAllowed` por identifier — mesmo padrão já usado
nos outros três endpoints. Novas propriedades `app.rate-limit.login-ip.max-requests` (20) e
`app.rate-limit.login-ip.window-minutes` (15) em `application-dev.properties`/`application-prod.properties`.
Isso limita o volume de tentativas por IP independente de quantos identifiers distintos o atacante
tenta, o que também limita o crescimento do mapa do `AttemptLockout` na mesma janela. Novos testes em
`AuthControllerTest` cobrem a ordem de verificação (throttle de IP antes do lockout por identifier), a
chave do throttle e o 429 quando o IP é limitado.

A mesma auditoria apontou um terceiro achado em `UserController.updateCurrentUser`: `touchesCredentials`
era calculado pela presença de `email`/`password` no payload do `PATCH /users/me`, não por eles terem
mudado de valor. Um payload reenviando o e-mail atual do usuário (legível via `GET /users/me`) passava
como "toca credencial" mesmo sem alterar nada, o que fazia a requisição cair em `recordSuccess` e zerar
o contador de tentativas falhas do `attemptLockout` do `patch-account` — intercalando isso com tentativas
de `currentPassword`, o brute-force contra a senha atual do usuário nunca acionava o `429`.

Corrigido movendo a decisão de "toca credencial" para o service, onde já existe a comparação real de
valor: novo método `UserService.willChangeCredentials(id, patchUserDTO)` reaproveita a mesma lógica de
`applyPatch` (extraída para `UserServiceImpl.resolveCredentialChanges`/`CredentialChanges`) e o
controller passou a chamá-lo em vez de checar a presença dos campos. Testes novos em
`UserServiceImplTest` (`willChangeCredentials`) e `UserControllerTest` cobrem o caso de e-mail
reenviado sem mudança não interagindo com o `attemptLockout`; um teste de integração novo em
`UserControllerIntegrationTest` reproduz o cenário completo (tentativas erradas de senha intercaladas
com o reenvio do e-mail atual) provando que o contador não é mais resetado.

Um quarto achado da mesma auditoria: o padrão idempotente de "get-or-create" que este arquivo documenta
(capturar `DataIntegrityViolationException`, re-consultar, devolver o existente) não é seguro quando o
método é chamado de dentro de uma transação `@Transactional` já aberta pelo chamador —
`ContentServiceImpl.getOrCreateReference` funciona sozinho via `POST /contents/reference` (sem
transação ambiente, cada chamada de repositório roda na sua própria transação curta), mas falhava com
500 quando chamado de dentro de `DiaryEntryServiceImpl.createDiaryEntry`/`maybeCompleteSeason`/
`maybeCompleteSeries` (todos `@Transactional`): o `saveAndFlush` que falha aborta a transação do banco
imediatamente, e no Postgres isso derruba até a re-consulta de recuperação com "current transaction is
aborted, commands ignored until end of transaction block". O mesmo padrão em
`FollowedPersonServiceImpl.followPerson` tinha a mesma fragilidade estrutural, mesmo não sendo hoje
chamado de dentro de outra transação.

Corrigido com um novo componente reutilizável, `common.transaction.NewTransactionExecutor`
(`@Transactional(propagation = Propagation.REQUIRES_NEW)` em torno de um `Supplier<T>`): a construção
da entidade e a tentativa de `saveAndFlush` agora rodam numa transação física isolada, então uma
violação de constraint ali derruba só essa transação isolada e nunca contamina a transação ambiente do
chamador; a re-consulta de recuperação roda normalmente na transação ambiente (ainda saudável) logo
depois. Em `FollowedPersonServiceImpl.followPerson`, a construção do `FollowedPerson` (que referencia
`User` via `getReferenceById`, um proxy preso à sessão) foi movida para dentro do mesmo bloco
`REQUIRES_NEW`, evitando o erro do Hibernate de associar um proxy a duas sessions abertas. `CLAUDE.md`
atualizado com essa regra para qualquer get-or-create idempotente futuro. Teste novo em
`DiaryEntryControllerIntegrationTest` dispara duas requisições reais e concorrentes de dois usuários
logando o mesmo `Content` ainda inexistente (via `CyclicBarrier` + `ExecutorService`, contra Postgres
real via Testcontainers) e prova que ambas retornam 201 em vez de uma delas cair em 500 — sem o fix,
esse teste falha de forma reprodutível com o erro do Postgres acima.

## ✅ 2026-08-16 — DiaryEntry: rewatches completos, bulk logging, pré-visualização de exclusão e envelope de completude

**Auto-conclusão reconhece rewatches completos, não só a primeira passada.** A cascata de auto-conclusão (`maybeCompleteSeason`/`maybeCompleteSeries`) parava na primeira `DiaryEntry`
auto-gerada encontrada para a temporada/série e nunca criava uma segunda, mesmo que o usuário tivesse
relogado todos os episódios/temporadas do zero (rewatch completo). Reescritos como laços `while`
multi-passada: `minEpisodeWatchCount`/`minSeasonWatchMax` calculam quantas passadas completas já foram
logadas (o mínimo de vezes que cada episódio/temporada do conjunto aparece no diário, via duas novas
queries de projeção — `DiaryEntryRepository.countEntriesByEpisodeNumberInSeason`/
`maxWatchNumberBySeasonInSeries`), e o laço cria uma `DiaryEntry` auto-gerada para cada passada em aberto
além do `watchNumber` mais alto já existente, disparando `triggerCompletionCascade` recursivamente a cada
uma. Reduz ao comportamento anterior quando só há uma passada completa. As antigas
`countDistinctWatchedEpisodesInSeason`/`countDistinctWatchedSeasonsInSeries` continuavam em uso pela
cascata de retração em `deleteDiaryEntry` (`retractSeasonIfIncomplete`/`retractSeriesIfIncomplete`), que
este trabalho não alterou — ver entrada abaixo, que substituiu ambas.

Como a criação da entrada auto-gerada agora pode rodar mais de uma vez por requisição, e duas
requisições concorrentes completando a mesma passada podem colidir na constraint
`uq_diary_entries_user_content_watch_number` (adicionada junto com a coluna `watchNumber`), extraído
`DiaryEntryServiceImpl.persistAutoGeneratedEntry`: mesmo padrão get-or-create idempotente já documentado
neste arquivo para `ContentServiceImpl`/`FollowedPersonServiceImpl` — constrói e salva a entrada dentro de
`NewTransactionExecutor.runInNewTransaction` (transação física isolada, para uma falha de `saveAndFlush`
não contaminar a transação ambiente de `createDiaryEntry`), e se a inserção colide por corrida, busca de
novo por `findFirstByUserIdAndContentIdAndWatchNumber` e devolve a entrada que o outro request já criou
em vez de propagar erro; só relança a exceção original se essa nova busca também não encontrar nada.
Testes unitários cobrem primeira passada, segunda passada (rewatch completo), passada incompleta (não
cria), e os dois ramos da recuperação de corrida (recupera a entrada existente / relança quando a busca
de recuperação também falha); teste de integração novo prova o rewatch completo de ponta a ponta via
Postgres real, checando os dois `watchNumber`s (1 e 2) da `DiaryEntry` da temporada pela resposta HTTP.
`docs/context/business-rules.md` atualizado com duas novas bullets na seção `## DiaryEntry`.

**Retração de auto-completude passa a ser multi-passada, com threshold.** Como a auto-conclusão agora reconhece múltiplas passadas completas (entrada anterior), a retração
precisava do mesmo tratamento: `retractSeasonIfIncomplete`/`retractSeriesIfIncomplete` ainda apagavam
sempre "a" `DiaryEntry` auto-gerada mais recente da temporada/série (via
`findFirstByUserIdAndContentIdOrderByCreatedAtDesc`), então com duas ou mais passadas completas um
delete podia acabar apagando a passada errada ou nenhuma. Reescritos para recalcular, após o delete, o
mínimo de passadas completas ainda sustentadas (`minEpisodeWatchCount`/`minSeasonWatchMax`, já existentes
da entrada anterior) e buscar todas as `DiaryEntry` da temporada/série com `watchNumber` acima desse novo
mínimo, via a nova query `DiaryEntryRepository.findByUserIdAndContentIdAndWatchNumberGreaterThan`; só as
candidatas com `autoGenerated = true` são de fato removidas (`deleteAll`), preservando entradas editadas
manualmente mesmo que fiquem acima do threshold. A busca das candidatas foi extraída para dois métodos
próprios, `computeSeasonRetractionCandidates`/`computeSeriesRetractionCandidates`, retornando a lista
completa (sem o filtro de `autoGenerated`) — pensados para reuso futuro por uma prévia de impacto de
deleção. As antigas `countDistinctWatchedEpisodesInSeason`/`countDistinctWatchedSeasonsInSeries` foram
removidas do repositório, já sem nenhum uso em produção ou teste depois dessa troca.

Testes unitários reescritos para o novo fluxo (season/série retraindo, preservação de entrada manual,
ainda completa não retrai) e três novos cobrindo o caso multi-passada: deletar um episódio de uma
passada mais antiga não retrai uma passada mais nova ainda completa; deletar o episódio que sustentava
a passada mais recente retrai só essa passada; entrada editada manualmente acima do threshold é
preservada. Teste de integração novo loga a mesma temporada de episódio único duas vezes (dois
`watchNumber`s), apaga o segundo watch do episódio, e confirma pela API que só a `DiaryEntry` de
`watchNumber == 1` da temporada permanece. `docs/context/business-rules.md` atualizado para descrever o
mecanismo de threshold/multi-passada.

**Apagar direto uma `DiaryEntry` de série limpa todo o histórico auto-gerado da série.** Adicionada a funcionalidade de apagar direto uma `DiaryEntry` de série (`ContentType = SERIES`) para
limpar todo o histórico de auto-completude associado à série. `DiaryEntryServiceImpl.deleteDiaryEntry`
ganhou um terceiro ramo: quando a entrada sendo apagada é do tipo `SERIES`, chama
`wipeSeriesHistory(userId, content.getTmdbId(), false)` para deletar em cascata todas as `DiaryEntry`
da série (episódios, temporadas e a própria série) que têm `autoGenerated = true` — mesma proteção
`autoGenerated` usada em `retractSeasonIfIncomplete`/`retractSeriesIfIncomplete`, garantindo que
entradas editadas manualmente (`autoGenerated = false`) nunca são removidas, nem por retração durante
o delete de episódio/temporada nem por esse novo apagamento direto de série. O método privado
`wipeSeriesHistory(userId, seriesTmdbId, overrideProtectedEntries)` busca todas as `DiaryEntry` da
série via três novas queries do repositório (`findAllEpisodeEntriesInSeries`/
`findAllSeasonEntriesInSeries`/`findAllSeriesEntries`), agrupa-as numa única lista, filtra apenas as
`autoGenerated = true` (a menos que `overrideProtectedEntries = true`, preparando o caminho para uma
futura feature de override), e apaga tudo em uma única operação `deleteAll`. Task C1 (não implementada
neste trabalho) ligará o boolean `overrideProtectedEntries` a um flag de request do cliente.

Testes unitários novos em `DiaryEntryServiceImplTest` cobrem (1) delete de série deletando todos os
episódios, temporadas e série auto-gerados quando `autoGenerated = true`, e (2) preservação de todos
os episódios, temporadas e série quando `autoGenerated = false`. Teste de integração novo em
`DiaryEntryControllerIntegrationTest` simula o fluxo completo: loga um episódio marcado como finale
de temporada e série (gerando 3 entradas auto-geradas: episódio, temporada, série), busca pela API a
entrada da série, deleta-a, e confirma que nenhuma entrada para a série permanece no diário do usuário.
`docs/context/business-rules.md` atualizado com uma nova bullet na seção `## DiaryEntry` documentando
a regra de apagamento direto em cascata, protegido por `autoGenerated`.

**`watchNumber` substitui `isRewatch` no contrato da API.** Fechamento da fundação de `watch_number` (fase A): o campo persistido e as passagens de conclusão/
retração multi-passada já existiam desde os dias anteriores, faltava expor o resultado corretamente na
API. `DiaryEntryResponseDTO.watchNumber(): Integer` substitui o antigo `isRewatch(): Boolean` — mudança
que quebra o contrato anterior, já que todo consumidor do endpoint precisa passar a ler um inteiro (1 =
primeira vez, 2+ = reassistida) em vez de um booleano. `DiaryEntryMapper` mapeia `watchNumber` de
`DiaryEntry` para `DiaryEntryResponseDTO` automaticamente por nome (MapStruct), sem `@Mapping` explícito
— mesmo mecanismo que antes mapeava `isRewatch`. `DiaryEntryUpdateDTO` perdeu o campo `isRewatch`: o
número da passada nunca foi editável via `PATCH`, então o campo não tem substituto — `updateDiaryEntry`
já não tinha nenhum ramo de `isRewatch` para remover (retirado quando `watchNumber` assumiu o cálculo
automático). `DiaryEntryCreationDTO.isRewatch` continua existindo sem mudança — é só um hint de entrada
que empurra o primeiro log de um conteúdo de `watchNumber = 1` para `2`.

Testes: criado `DiaryEntryMapperTest` (não existia antes) cobrindo o mapeamento completo de `DiaryEntry`
para `DiaryEntryResponseDTO`, incluindo `watchNumber`. Removidas as construções posicionais de
`DiaryEntryUpdateDTO` com o extra `null` de `isRewatch` em `DiaryEntryServiceImplTest` e
`DiaryEntryControllerTest`. Novo teste de integração em `DiaryEntryControllerIntegrationTest`
(`shouldIgnoreIsRewatchAndReturnWatchNumberWhenSentInTheUpdateRequestBody`) confirma que um corpo de
`PATCH` contendo `isRewatch` é ignorado silenciosamente (sem `FAIL_ON_UNKNOWN_PROPERTIES`) e retorna
`200` com `watchNumber` no corpo, sem nenhum campo `isRewatch`. `docs/context/openapi.yaml` (schemas
`DiaryEntry`/`DiaryEntryUpdate`) e `docs/context/database-schema.html` (coluna `numero_visualizacao` no
lugar de `reassistiu`, versão do diagrama bump para v6) atualizados para refletir o novo formato.

**`POST /diary/bulk`: logging de temporada/série inteira.** Primeiro endpoint de bulk logging: `POST /diary/bulk` registra que o usuário assistiu a uma temporada
ou série inteira em uma única requisição. Reusa toda a maquinaria de Phase A (`persistDiaryEntry`,
`mapWatchNumberConflict`, `triggerCompletionCascade`) — cada episódio logado em bulk passa pela mesma
persistência e cascata de conclusão que um log individual, garantindo o comportamento idêntico de
`watchNumber` (passada fresca para cada episódio) e auto-completude de temporada/série.

Limitações/regras: (1) Só aceita `content.type = SEASON` ou `SERIES` — outros tipos devolvem `400`.
(2) Para `SEASON`: exige `finaleEpisodeNumber` (número do último episódio da temporada) ou, se houver
um `EPISODE` existente com `isSeasonFinale = true`, usa aquele; falta ambos = `400`. (3) Para `SERIES`:
exige `finaleSeasonNumber` (número da última temporada) ou, se houver uma `SEASON` existente com
`isSeriesFinale = true`, usa aquela; falta ambos = `400`. (4) Máximo de 100 episódios no total
(constante `MAX_BULK_EPISODES`); série/temporada com mais = `400`. (5) Rate limit próprio: 10
requests/15min (vs. 60/5min do single-entry `/diary`), configurável via
`app.rate-limit.diary-bulk-action.max-requests`/`window-minutes`.

Novo DTO: `DiaryEntryBulkCreationDTO` com campos `content`, `watchedDate`, `finaleEpisodeNumber`,
`finaleSeasonNumber` — uma simplificação de design: todo episódio logado em um batch compartilha a mesma `watchedDate` fornecida na requisição, ao invés de permitir uma data por episódio. Novos métodos no service: `createDiaryEntriesInBulk` (público) + 5 privados
(bulkLogSeason, bulkLogEpisode, bulkLogSeries, resolveSeasonFinaleEpisodeNumber,
resolveSeriesFinaleSeasonNumber) para orquestrar o logging. Novo endpoint controller: `POST /diary/bulk`
retorna `201` com a lista de `DiaryEntryResponseDTO` criadas.

Testes: 7 testes unitários em `DiaryEntryServiceImplTest` cobrindo (1) rejeição de tipo não-bulk (MOVIE),
(2) erro quando season tem `finaleEpisodeNumber = 101` (exceeds MAX), (3) erro quando requisição de série
falta `finaleSeasonNumber` , (4) erro quando série tem uma season sem finale episode conhecida,
(5) processamento bem-sucedido para series com múltiplas seasons completadas, e (6) fresh pass (`watchNumber`
incrementado) quando alguns episódios já foram logados. 2 testes unitários em `DiaryEntryControllerTest`
(mocked service) checando `201` response com lista de DTOs e que `requestThrottler.checkAllowed` é invocado
antes do service. 2 testes de integração em `DiaryEntryControllerIntegrationTest` cobrindo bulk-log
bem-sucedido (3 episódios + 1 temporada auto-gerada = 4 entradas no total) e excesso de rate limit
(10 requests depois excede, 11º retorna `429`).

`docs/context/openapi.yaml` atualizado com novo endpoint `/diary/bulk` (POST) e novo schema
`DiaryEntryBulkCreation`, modelando os 4 campos e explicando as regras de `finaleEpisodeNumber`/
`finaleSeasonNumber` e o limite de 100. `docs/context/business-rules.md` atualizado com nova bullet
na seção `## DiaryEntry` documentando o comportamento de bulk logging. Rate limit properties
adicionadas a `application-dev.properties` e comentadas em `application-prod.properties`.

**`overrideProtectedEntries` propagado por toda a cascata de delete.** Primeira tarefa da Fase C ("Deletion-impact preview") — groundwork para permitir um usuário, com
confirmação explícita, deletar o histórico manual de uma série/temporada mesmo quando já houve edição
manual. O parâmetro `overrideProtectedEntries` foi adicionado à signature pública
`DiaryEntryService.deleteDiaryEntry(UUID userId, UUID diaryEntryId, boolean overrideProtectedEntries)`
e propagado internamente para os métodos privados `retractSeasonIfIncomplete`,
`retractSeriesIfIncomplete` e `wipeSeriesHistory`. Quando `true`, o flag significa "incluir entradas
com `autoGenerated = false` na cascata de deletes"; quando `false` (default), mantém a proteção
padrão (só deleta auto-geradas). Atualmente hardcoded como `false` em todas as call sites
(`DiaryEntryController.deleteDiaryEntry` e testes) — esse é um "override sem efeito" configurado
para as Tasks C2/C3 que virão depois, as quais exponham o flag via query parameter ou UI de
confirmação.

Novo método privado `computeSeriesWipeCandidates(userId, seriesTmdbId)` extrai a lógica de busca de
todas as entradas de uma série (episódios, temporadas, série) em um único lugar, reutilizável tanto
por `wipeSeriesHistory` quanto pela preview da próxima task. Todos os call sites em testes foram
atualizados da forma 2-arg `deleteDiaryEntry(userId, entryId)` para 3-arg
`deleteDiaryEntry(userId, entryId, false)`.

Testes: 2 testes unitários novos em `DiaryEntryServiceImplTest` — um cobrindo deletar uma entrada
manual acima do threshold de uma temporada quando `overrideProtectedEntries = true` (retrai-a,
diferente do comportamento padrão que a preserva), e outro cobrindo o caso equivalente de série
inteira (deleta episódios, temporadas e série com `autoGenerated = false` quando `overrideProtectedEntries
= true`). `docs/context/business-rules.md` atualizado com nova bullet documentando a proteção
`autoGenerated` com flag de override opcional, anotando que atualmente é plumbing-only (hardcoded
como `false` na API).

**`GET /diary/{diaryEntryId}/deletion-impact`: pré-visualização do impacto de exclusão.** Segundo endpoint da Fase C — pré-visualização em "dry-run" do impacto de cascata de um delete de
`DiaryEntry` sem de fato deletar nada. O novo endpoint `GET /diary/{diaryEntryId}/deletion-impact`
retorna um DTO `DeletionImpactDTO` contendo a lista de entradas que **seriam** deletadas se aquela
entrada fosse apagada; cada item inclui `type`, `watchedDate`, `watchNumber` e um boolean `hasReview`
que indica se a entrada é protegida (tem `comment` ou `score` não-nulo) ou auto-gerada.

Novos DTOs: `DeletionImpactDTO` (envelope com `wouldDelete: List<DeletionImpactItemDTO>`) e
`DeletionImpactItemDTO` (item individual com type, watchedDate, watchNumber, hasReview).

Implementação em `DiaryEntryServiceImpl.computeDeletionImpact(userId, diaryEntryId)` reutiliza os
métodos privados de cálculo de candidatos já existentes — `computeSeasonRetractionCandidates`,
`computeSeriesRetractionCandidates`, `computeSeriesWipeCandidates` — invocando-os de acordo com o
tipo de conteúdo da entrada consultada (EPISODE → retrações de temporada + série; SEASON → retrações
de série; SERIES → wipe da série inteira; MOVIE → lista vazia). Novo método privado
`computeEpisodeDeletionImpact` agrupa a lógica especial de episódios (que podem afetar temporada e
série em cascata).

Testes: 3 testes unitários em `DiaryEntryServiceImplTest` — um cobrindo episódio que não quebra
completude (retorna lista vazia); um cobrindo episódio que sustenta season + series (retorna ambas);
um cobrindo o campo `hasReview` (verifica que é `true` quando entry tem comment ou score, `false`
caso contrário, com testes individuais por item). 1 teste unitário em `DiaryEntryControllerTest`
cobrindo o happy path do endpoint. 1 teste integração em `DiaryEntryControllerIntegrationTest`
cobrindo o cenário full: um único episódio de uma season de 1 episódio logado via `POST /diary`
(não bulk-log), PATCH nessa entry com comment, GET deletion-impact do episódio que sustentava a
season, validando que a season aparece na resposta com `hasReview = true`.

Documentação: `openapi.yaml` atualizado com novo path `/diary/{diaryEntryId}/deletion-impact` (GET,
200/404), novos schemas `DeletionImpact` e `DeletionImpactItem` em components. `business-rules.md`
atualizado com nova bullet descrevendo o endpoint como reusando a mesma lógica dos deletes reais.

**Correção (mesmo dia):** a implementação inicial acima tinha um bug real — para EPISODE ela
tentava simular "e se essa entry fosse apagada" reimplementando `minEpisodeWatchCount(...) - 1` na
mão em vez de reusar `computeSeasonRetractionCandidates` sem alterações, e para SEASON ela nunca
chegou a simular a deleção (chamava `computeSeriesRetractionCandidates` contra o estado atual do
banco, pré-deleção). Isso causava falsos positivos (reportava season/series como impactadas quando
o episódio apagado não era o gargalo de contagem) e falsos negativos (não detectava quando a própria
SEASON sendo pré-visualizada sustentava a completude da série). A correção reescreve
`computeDeletionImpact` como um `@Transactional` que de fato executa `diaryEntryRepository.delete` +
`flush()` da entry consultada, chama os métodos de candidatos reais (agora sempre contra o estado
pós-deleção dentro da mesma transação) e então chama
`TransactionAspectSupport.currentTransactionStatus().setRollbackOnly()` antes de montar a resposta,
garantindo que a "exclusão" nunca é de fato commitada — um dry-run transacional real em vez de uma
fórmula derivada à mão, correto por construção porque reusa a mesma lógica de candidatos do delete
real sem modificação. O `setRollbackOnly()` só é chamado quando
`TransactionSynchronizationManager.isActualTransactionActive()` é verdadeiro, para não quebrar os
testes unitários com Mockito puro (`@InjectMocks`, sem proxy do Spring, sem transação real ativa).

Novos testes: em `DiaryEntryServiceImplTest`, NotFound (entry inexistente / de outro usuário) para
`computeDeletionImpact`; testes de ordenação via `InOrder` provando que `delete()`+`flush()` sempre
acontecem antes das queries de candidatos, para EPISODE, SEASON e MOVIE; um teste provando que
apagar uma entry de episódio que **não** é o gargalo de contagem retorna lista vazia (contraexemplo
que a implementação anterior reportava incorretamente como não-vazia); um teste provando que apagar
o episódio que **é** o gargalo retorna corretamente a season como candidata. Em
`DiaryEntryControllerIntegrationTest`: 401 sem cookie de autenticação; 404 para entry inexistente e
para entry de outro usuário; um cenário real via Testcontainers provando que apagar um episódio
não-gargalo (`rewatch` de um episódio que não é o mínimo de contagem) retorna `wouldDelete` vazio e
não persiste nenhuma alteração no banco; um cenário provando que apagar o episódio gargalo retorna a
season impactada; e um cenário provando que apagar diretamente a `DiaryEntry` de tipo SEASON que
sustenta a completude da série retorna corretamente a entry SERIES como impactada — o bug que a
implementação anterior nunca chegou a corrigir para o branch SEASON. Todos os cenários de integração
também confirmam, via `diaryEntryRepository.findById`, que nenhuma linha reportada como "seria
apagada" foi de fato removida do banco.

**`overrideProtectedEntries` exposto como query parameter em `DELETE /diary/{diaryEntryId}`.** Terceira e última tarefa da Fase C — expor o parâmetro `overrideProtectedEntries` como query parameter
no endpoint `DELETE /diary/{diaryEntryId}`, permitindo que um cliente, após chamar
`GET /diary/{diaryEntryId}/deletion-impact` para pré-visualizar o impacto, passe `?overrideProtectedEntries=true`
se quiser de fato deletar também as entradas manualmente-editadas (`autoGenerated = false`) da cascata.

Implementação em `DiaryEntryController.deleteDiaryEntry`: adicionado `@RequestParam(required = false, defaultValue = "false") boolean overrideProtectedEntries`,
passando o valor para `diaryEntryService.deleteDiaryEntry(userId, diaryEntryId, overrideProtectedEntries)`.
O parâmetro é opcional, defaultando para `false` (comportamento de proteção padrão) para manter compatibilidade
com clientes que não enviam o query param.

Testes: 2 testes unitários em `DiaryEntryControllerTest` — um verificando que o valor `true` é passado
para o service (`verify(diaryEntryService).deleteDiaryEntry(userId, entryId, true)`), outro verificando
que o default é `false` quando o parâmetro é ausente. 2 testes de integração em
`DiaryEntryControllerIntegrationTest` — um logando um episódio de temporada única (1 episódio = auto-gera season),
patcheando a season com comment (sets `autoGenerated = false`), deletando o episódio com
`?overrideProtectedEntries=true`, e confirmando via `GET /diary` que a season foi removida; outro teste
idêntico mas deletando **sem** o query param (defaults to `false`), confirmando que a season é
preservada. Ambos os testes validam via JsonPath que não há mais entradas ou que a season ainda existe,
respectivamente.

Documentação: `openapi.yaml` atualizado com novo query parameter `overrideProtectedEntries` na seção
DELETE de `/diary/{diaryEntryId}`, descrevendo o comportamento default (`false`) e recomendando a
pré-visualização via `GET /diary/{id}/deletion-impact`. `business-rules.md` reescrito o bullet de
apagamento cascata para remover a frase sobre hardcoding (`overrideProtectedEntries` agora é de fato
exposto via query parameter) e mencionar explicitamente que a flag está wired ao endpoint DELETE,
com recomendação de chamar a preview first.

**`triggerCompletionCascade` passa a retornar o que criou.** Primeira tarefa da Fase D (sinalização de completude na resposta de `POST /diary`). `triggerCompletionCascade`,
`maybeCompleteSeason` e `maybeCompleteSeries`, em `DiaryEntryServiceImpl`, eram `void` e só causavam
efeito colateral (criação das `DiaryEntry` auto-geradas via `persistAutoGeneratedEntry`). Agora
`maybeCompleteSeason`/`maybeCompleteSeries` retornam a última `DiaryEntry` que criaram (`null` se o loop
nunca rodou), e `triggerCompletionCascade` retorna um novo record privado `CompletionSignal`
(`completedSeason`, `completedSeries`, ambos `DiaryEntry` nullable) combinando o resultado do episódio
com o resultado da recursão para a série. `createDiaryEntry` já captura o sinal numa variável local
(ainda não consumida na resposta — isso é trabalho da Fase D2).

**Correção de um bug real da Fase B (Task B1):** `bulkLogEpisode` chamava `triggerCompletionCascade`
mas descartava o retorno por completo — a resposta do endpoint de bulk-logging (`POST /diary/bulk`)
omitia silenciosamente toda entrada auto-gerada de season/series produzida por um lote, apesar do
teste de integração da Fase B1 (`shouldCreateEveryEpisodeAndTheSeasonEntryWhenBulkLoggingACompleteSeason`)
ter um comentário afirmando esse comportamento como esperado ("the auto-generated season entry ... is
not included in the response"). A mudança de assinatura corrige isso de graça: `bulkLogEpisode` agora
captura o `CompletionSignal` retornado e adiciona `completedSeason`/`completedSeries` (quando não nulos)
na lista `created`, antes de repassá-la por `bulkLogSeason`/`bulkLogSeries`. O teste de integração citado
foi corrigido para afirmar o comportamento correto: a resposta do bulk-logging de uma season completa
agora tem 4 itens (3 episódios + a season auto-gerada), com `autoGenerated = true` e `watchNumber = 1`
na entrada da season.

Testes: 3 testes unitários novos em `DiaryEntryServiceImplTest` invocando `triggerCompletionCascade`
via reflection (método privado, ainda não exposto por nenhuma API pública) para afirmar diretamente os
campos do `CompletionSignal` retornado — season não completada (`null`/`null`), apenas season completada
(`completedSeason` com `watchNumber = 1`, `completedSeries` nulo), e season+series completadas na mesma
chamada (ambos não nulos, cada um com `watchNumber = 1`), confirmando que a recursão de
`triggerCompletionCascade` (episódio → season → série) combina corretamente os dois resultados. 1 teste
unitário novo em `DiaryEntryServiceImplTest` para `createDiaryEntriesInBulk` provando, via filtro real na
lista retornada (não apenas checagem de tamanho), que a entrada SEASON auto-gerada está presente com
`autoGenerated = true`.

**Envelope `DiaryEntryCreationResultDTO` na resposta de `POST /diary`.** Segunda e última tarefa da Fase D — consumir o `CompletionSignal` que a Fase D1 já calculava mas
descartava em `createDiaryEntry`, expondo-o na resposta de `POST /diary`. Novo record
`DiaryEntryCreationResultDTO` (`entry`, `completedSeason`, `completedSeries`, os dois últimos
`DiaryEntryResponseDTO` nullable) substitui `DiaryEntryResponseDTO` como tipo de retorno de
`DiaryEntryService.createDiaryEntry`/`DiaryEntryServiceImpl.createDiaryEntry` e como corpo de resposta
de `DiaryEntryController.createDiaryEntry`. `completedSeason`/`completedSeries` só vêm preenchidos
quando logar o episódio (ou a season) completou, respectivamente, a season ou a série na mesma
chamada — mapeados via `diaryEntryMapper.diaryEntryToResponseDto` a partir das entradas auto-geradas
já presentes no `CompletionSignal`.

**Breaking change aceita** na resposta de `POST /diary` (mesma postura já aceita na Task A7, troca de
`isRewatch` por `watchNumber`): a resposta deixa de ser o `DiaryEntry` "nu" e passa a ser o envelope
`{ entry, completedSeason, completedSeries }`. Não há consumidores externos ainda (pré-lançamento).
`POST /diary/bulk` não muda — sua resposta já é uma lista plana onde season/series auto-geradas
aparecem como itens normais desde a correção da Fase D1.

Testes: em `DiaryEntryServiceImplTest`, o teste de `createDiaryEntry` que antes verificava o
`DiaryEntryResponseDTO` retornado passou a verificar `result.entry()`; novo teste chamando
`createDiaryEntry` (não mais via reflection) numa cena que completa season e série na mesma chamada,
afirmando que `result.completedSeason()`/`result.completedSeries()` são `DiaryEntryResponseDTO`
não-nulos com `watchNumber = 1` e `autoGenerated = true`, e que `result.entry()` tem
`autoGenerated = false`. Em `DiaryEntryControllerTest`, os dois testes de `createDiaryEntry` passaram
a mockar/afirmar `DiaryEntryCreationResultDTO`. Em `DiaryEntryControllerIntegrationTest`, sweep
mecânico de todo `jsonPath` que lia campos do corpo de `POST /diary` diretamente (`$.id`, `$.score`,
`$.watchNumber`, `$.content.tmdbId`) para o caminho aninhado (`$.entry.id`, `$.entry.score`, etc.) em
todos os cenários existentes que usam `createRequest(...)`; 2 testes de integração novos — um logando
o episódio finale de uma season de 1 episódio e afirmando `$.completedSeason.watchNumber == 1` com
`$.completedSeries` ausente, outro logando um episódio que não completa nada e afirmando
`$.completedSeason`/`$.completedSeries` ambos ausentes.

Documentação: `openapi.yaml` ganhou o schema `DiaryEntryCreationResult` (`entry: DiaryEntry`,
`completedSeason`/`completedSeries: DiaryEntry, nullable`) e a resposta `201` de `POST /diary` passou
a referenciá-lo em vez de `DiaryEntry`.

**Correções da revisão final do branch (mesmo dia).** A revisão do branch inteiro (22 commits, as
tarefas das Fases A–D acima) achou dois problemas críticos que só apareciam com tudo junto.

O primeiro era um laço ilimitado: `minEpisodeWatchCount`/`minSeasonWatchMax` começam o cálculo de
mínimo no sentinela `Integer.MAX_VALUE` e só o reduzem dentro do `for` sobre o conjunto; quando o
número do finale era menor que 1, o `for` nunca rodava e o sentinela era devolvido intacto, fazendo o
`while` de catch-up de `maybeCompleteSeason`/`maybeCompleteSeries` inserir uma `DiaryEntry`
auto-gerada por iteração ~2^31 vezes — alcançável com um único `POST /diary` de `episodeNumber: 0`
com `isSeasonFinale: true`. Corrigido em três camadas: os dois helpers devolvem `0` ("nada completo")
quando a faixa é vazia; `ContentRefCreationDTO` ganhou `@Positive` em `episodeNumber` e
`@PositiveOrZero` em `seasonNumber` (a temporada 0 "Specials" do TMDB é legítima, o episódio 0 não);
e `V13__add-content-episode-season-number-checks.sql` repete as duas regras no banco
(`ck_contents_episode_number`, `ck_contents_season_number`). `DiaryEntryBulkCreationDTO` ganhou
`@Min(1)` em `finaleEpisodeNumber`/`finaleSeasonNumber` pelo mesmo motivo.

O segundo era o preview de exclusão sub-reportando a entrada de SÉRIE: `computeDeletionImpact`
calculava as candidatas de temporada mas nunca as aplicava antes de calcular as de série, então a
consulta de série enxergava o estado pré-retração e a `DiaryEntry` da série sumia da lista — um
usuário confirmando `overrideProtectedEntries=true` com base nesse preview perdia uma review de série
que nunca lhe foi mostrada. Corrigido eliminando o segundo caminho de código: `deleteDiaryEntry` ganhou
uma sobrecarga privada que recebe um acumulador `List<DiaryEntry>`, preenchido pelo novo helper
compartilhado `deleteRespectingProtection` (que também substitui o trecho filtro + `isEmpty` +
`deleteAll` triplicado em `retractSeasonIfIncomplete`/`retractSeriesIfIncomplete`/`wipeSeriesHistory`),
e `computeDeletionImpact` passou a chamar o delete real com esse acumulador e marcar a transação como
rollback-only — o acumulador *é* a lista de impacto. `computeEpisodeDeletionImpact` foi removido.
Junto vieram: `overrideProtectedEntries` como query parameter também no
`GET /diary/{diaryEntryId}/deletion-impact` (preview e delete passam a fazer a mesma pergunta),
`id` e `autoGenerated` em `DeletionImpactItemDTO` (`autoGenerated` é a flag de proteção de verdade;
`hasReview` sempre foi só uma dica de severidade, já que um `PATCH` sem campos zera `autoGenerated`
sem escrever nada), e `@Transactional(propagation = Propagation.REQUIRES_NEW)` em
`computeDeletionImpact`, para o rollback-only do dry-run nunca poder envenenar a transação de um
chamador futuro.

Testes novos: guardas de faixa vazia para temporada e série; catch-up de três passadas pendentes de
uma vez, afirmando `watchNumber` 1, 2 e 3 em sequência (temporada e série); constraints novas em
`ContentRepositoryTest` (episódio 0 e temporada -1 estouram, temporada 0 persiste); `400` de
`episodeNumber: 0` e `seasonNumber: -1` em `POST /diary`; `401`, `403` e três casos de `400` em
`POST /diary/bulk`; conjunto misto de `autoGenerated` em `wipeSeriesHistory`; e a reprodução real do
bug de C-2 — série com duas temporadas, preview do delete do episódio S2E1 devolvendo temporada **e**
série, mais um teste que compara a lista do preview com o que o `DELETE` de fato apaga. O teste
unitário que "cobria" esse caso antes stubbava estados mutuamente contraditórios (uma entrada de
temporada existindo e `maxWatchNumberBySeasonInSeries` devolvendo vazio ao mesmo tempo) e por isso
passava com a implementação errada; foi reescrito com um stub que reflete o estado real do banco antes
e depois da retração. Removida do repositório a query
`findFirstByUserIdAndContentIdOrderByCreatedAtDesc`, sem uso em produção desde a troca para retração
por threshold.

`openapi.yaml`, `business-rules.md` e `fixes.md` atualizados junto (novos `minimum`, descrição correta
de `isRewatch` e de `hasReview`, o novo query parameter do preview, a limitação conhecida de entradas
auto-geradas órfãs quando a transação ambiente do bulk faz rollback depois de um commit `REQUIRES_NEW`,
e o registro do cuidado futuro com FKs de `Comment`/`Like` sobre o dry-run transacional).

## ✅ 2026-08-17 — Fix: ordem de recuperação em `resolveConcurrentCreation`; CHECK de finale flags por type

`ContentServiceImpl.resolveConcurrentCreation` mapeava o nome da constraint colidida (`extractConstraintName`)
antes de re-consultar por chave natural. Quando duas requisições concorrentes criam o mesmo
episódio/temporada já marcado como finale, o Postgres pode reportar a constraint de unicidade de finale
em vez da de chave natural (a ordem de checagem não é garantida quando o mesmo `insert` viola as duas ao
mesmo tempo) — isso rejeitava como `409` uma criação concorrente legítima e idêntica à que já existia,
em vez de devolvê-la como o padrão idempotente de get-or-create exige. Corrigido invertendo a ordem: a
re-consulta por chave natural roda primeiro e é devolvida se encontrar algo; o nome da constraint só é
mapeado para um `409` específico quando a re-consulta não acha nada. Teste novo em `ContentServiceImplTest`
reproduz o cenário (falha reportada como `uq_contents_season_finale`, mas o episódio já existe) e prova
que o resultado agora é o `Content` existente, não uma exceção.

Adicionada a migration `V14__add-content-finale-flags-by-type-check.sql`
(`ck_contents_finale_flags_by_type`), espelhando no banco a mesma regra que `ContentServiceImpl.validate`
já impõe na aplicação: `is_season_finale` só pode ser não-nulo em `EPISODE`, `is_series_finale` só em
`EPISODE`/`SEASON`. Três testes novos em `ContentRepositoryTest` provam a constraint diretamente (MOVIE
com `isSeasonFinale`, SERIES com `isSeriesFinale`, SEASON com `isSeasonFinale`, todos rejeitados).

Reauditoria completa dos status HTTP documentados em `openapi.yaml` pros 4 endpoints de diary
(`GET /users/{userId}/diary`, `POST /diary`, `PATCH /diary/{diaryEntryId}`, `DELETE /diary/{diaryEntryId}`)
e pros 3 de top5, cujas respostas reais tinham mudado bastante desde a última vez que o contrato foi
escrito (plano de rewatch/bulk logging/deletion preview). Também documentado sistematicamente, em todo
endpoint mutável autenticado ainda sem isso (~13: follow/follow-requests/follow-people,
`POST /contents/reference`, os 4 de diary, os 2 de top5): o `403` de CSRF (`X-XSRF-TOKEN` ausente). E em
todo path com um `{algumId}` que é UUID de verdade (~14, excluindo `personTmdbId` que é string TMDB): o
`400` que `GlobalExceptionHandler.handleTypeMismatch` já devolve pra um valor não-UUID no path. Nenhuma
mudança de código nessas duas frentes — só o contrato documentado alcançando o que o backend já fazia.

**Adição posterior (mesmo dia): unificação de transação em `getOrCreateReference`.** `clearPreviousSeriesFinale`
rodava numa transação separada da criação do novo `Content` — sem `@Transactional` em `getOrCreateReference`,
a limpeza da flag antiga usava a transação ambiente (ou nenhuma, se chamado direto via
`POST /contents/reference`) enquanto a criação sempre rodava em `REQUIRES_NEW`
(`NewTransactionExecutor`). Uma falha entre as duas escritas deixava a série sem nenhuma temporada
marcada como finale, parando `maybeCompleteSeries` silenciosamente pra todos os usuários daquela série;
e quando havia transação ambiente (ex. via `POST /diary`), um rollback posterior da ambiente desfazia só
a limpeza, não a criação já commitada em `REQUIRES_NEW` — um split-brain pior que o originalmente
identificado. Corrigido movendo a chamada de `clearPreviousSeriesFinale` pra dentro da mesma lambda
`REQUIRES_NEW` que já cria e salva o novo `Content`, então as duas escritas sempre commitam ou dão
rollback juntas, com ou sem transação ambiente. Teste novo em `ContentServiceImplTest` captura a
`Supplier` passada a `runInNewTransaction` sem executá-la, prova que a flag antiga continua intacta até
a `Supplier` ser de fato invocada, e só então confirma a limpeza — o que faz o teste falhar contra o
código anterior à correção.

Também renomeado `docs/pending/fase-1.5-todo.md` pra `docs/pending/pending.md`: removida a seção
"Rate limiting / lockout — endpoints existentes" (totalmente implementada, sem valor como to-do),
mantida "Upgrade futuro (opcional) — verificação de e-mail de verdade", e adicionada uma seção
"Pendente" nova recebendo o item de `fixes.md` #8 (`computeDeletionImpact` vai precisar tratar FK de
`Comment`/`Like` quando essas tabelas existirem — bloqueado por fase futura, não por decisão pendente).

**Adição posterior (mesmo dia): decisão de produto sobre posse das flags de finale + `isSeasonFinale`
ganha transferência.** Bug conhecido: `isSeasonFinale`/`isSeriesFinale` são estado global sem dono —
qualquer usuário autenticado pode setá-las via `POST /contents/reference` (direto ou embutido em
`POST /diary`), sem verificação nem registro de quem criou. Decisão tomada: aceitar esse risco
(rastrear autor ou restringir quem pode setar não tem consumidor hoje — não há superfície de moderação,
`Comment`/`Like`/papel de admin são fases futuras — e a flag não é alvo com incentivo real de má-fé).
Como única correção de código, fechado o caso legítimo mais comum que ainda não tinha solução: uma
temporada que ganha um episódio extra/atrasado depois do finale antigo já ter sido logado. Novo
`ContentServiceImpl.clearPreviousSeasonFinale` espelha exatamente `clearPreviousSeriesFinale` um nível
abaixo — transfere `isSeasonFinale` do episódio-finale antigo pro novo apenas quando o episódio novo
tem número maior, rodando dentro da mesma lambda `REQUIRES_NEW` (mesma atomicidade da correção
anterior); um episódio novo igual ou anterior continua rejeitado com `409`, comportamento inalterado.
Testes novos: 3 em `ContentServiceImplTest` (transferência, rejeição quando não-posterior, ausência de
lookup quando a flag não é setada — mesmo padrão dos testes já existentes pra `isSeriesFinale`) e 1 de
integração em `ContentControllerIntegrationTest` provando a transferência ponta a ponta via HTTP; o
teste de conflito existente (`shouldReturnConflictWhenAnotherEpisodeIsAlreadyMarkedAsTheSeasonFinale`)
foi renomeado e ajustado pra usar um episódio anterior, já que um posterior agora transfere em vez de
rejeitar. `CLAUDE.md` § Avoid e `docs/context/business-rules.md` § Content atualizados pra descrever as
duas vias de transferência espelhadas, em vez de só a de série.

**Adição posterior (mesmo dia): correção em massa dos achados mecânicos de
`audit-silencio-incoerencia-falha.md`.** Reconferida a auditoria de 2026-08-15 item por item contra o
código atual; corrigidos todos os achados que não dependiam de decisão de design (25 dos 39 de
Alto/Médio/Baixo, incluindo 3 já resolvidos em sessões anteriores — 22 novos nesta rodada):

- **`content`** — reenviar um `Content` existente com uma flag de finale diferente da já persistida
  agora é rejeitado com `409` (`ContentServiceImpl.assertNoFinaleMismatch`), em vez de devolver a
  referência antiga silenciosamente sem aplicar a correção. Chamado tanto no caminho normal quanto em
  `resolveConcurrentCreation`. Um request que omite a flag (`null`) nunca conflita.
- **`top5entry`** — `resolvePosition` rejeita com `400` qualquer `position` maior que `count + 1` (a
  próxima posição livre), fechando o buraco que permitia inserir além do necessário e depois apagar uma
  entrada existente silenciosamente na inserção seguinte (`shiftUpFrom` evictava por
  `position == MAX_ENTRIES`, que deixava de ser uma checagem confiável sem essa invariante).
- **`auth`** — `RefreshToken` ganhou `@Version` (migration `V15`); duas rotações concorrentes do mesmo
  refresh token agora fazem a segunda `saveAndFlush` falhar com `ObjectOptimisticLockingFailureException`,
  tratada como reuso (`401` + `revokeAllRefreshTokens`) em vez de emitir dois pares de token válidos em
  paralelo. A limpeza noturna (renomeada `deleteExpired`) não apaga mais tokens só por `revoked = true`
  — só por `expiresAt < now` — porque a detecção de reuso depende da linha revogada sobreviver até sua
  expiração natural. Removido o comentário de código em `RefreshTokenServiceImpl` (regra "sem
  comentários" do `CLAUDE.md`).
- **`common`** — `GlobalExceptionHandler` ganhou `handleHttpRequestMethodNotSupported` (405) e
  `handleNoResourceFoundException` (404 de rota), ambos devolvendo `ApiError` em vez do `ProblemDetail`
  padrão do Spring. Testes novos em `SecurityConfigIntegrationTest`.
- **`user`** — `getUsersByUsername` agora faz `trim()` antes de checar vazio (string só com espaço
  passava pelo guard antigo). Wildcards do LIKE (`%`, `_`) são escapados antes da query
  (`escapeLikeWildcards` + `ESCAPE '\\'` no JPQL), com um parâmetro separado e não-escapado alimentando
  o `CASE` de match exato pra não quebrar a ordenação. `login` não usa mais a query ambígua
  `findByUsernameIgnoreCaseOrEmailIgnoreCase` — tenta `findByUsernameIgnoreCase` primeiro, só cai pra
  `findByEmailIgnoreCase` se não achar; evita `IncorrectResultSizeDataAccessException` quando o
  identifier colide com o username de um usuário e o e-mail de outro (nada impede isso, já que
  `uq_users_username`/`uq_users_email` são constraints independentes). `updateUser` chama
  `revokeAllRefreshTokens` depois de salvar com sucesso sempre que a troca envolveu `password` e/ou
  `email`. Removidos `existsByUsername`/`existsByEmail` do repositório (sem uso, padrão check-then-act
  que o `CLAUDE.md` já pede pra evitar).
- **`followedperson`** — novo `FollowedPersonServiceImpl.validatePersonTmdbId` rejeita com `400`
  qualquer `personTmdbId` que não seja só dígitos com até 20 caracteres, chamado no início de
  `followPerson`/`unfollowPerson` — mesmo padrão de validação de service já usado no resto do projeto,
  em vez de introduzir `@Validated`/`@Pattern` como padrão novo. Bloqueia o overflow de `VARCHAR(20)`
  antes de chegar no banco.

Ficaram de fora: secret de assinatura do JWT commitado em texto puro — perguntado nesta sessão (e numa
sessão anterior, sem resposta na época); decisão explícita desta vez foi não agir agora, risco aceito
— e invalidação de access token no `logout-all`, bloqueada por decisão de arquitetura (exigiria
denylist de `jti` ou encurtar bastante o tempo de expiração). `docs/context/business-rules.md` (seções
Content, Top5Entry,
FollowedPerson, User/Auth) e `docs/context/openapi.yaml` atualizados com as novas regras e status HTTP.
Suíte completa: 811 testes passando.

**Adição posterior (mesmo dia): invalidação de access token no `logout-all`, e todo o restante do "Baixo"
de `audit-silencio-incoerencia-falha.md`.** A invalidação de access token, tida como bloqueada por
decisão de arquitetura na rodada anterior, acabou não precisando de denylist: `User` ganhou
`sessionsInvalidatedAt` (migration `V16`); `RefreshTokenServiceImpl.invalidateAllSessions` (renomeado de
`revokeAllRefreshTokens`) grava `now()` ali, truncado pro segundo — mesma precisão do claim `iat` do JWT
— além de revogar os refresh tokens como antes. `JwtCookieAuthenticationFilter` passou a comparar o
`iat` de todo `access_token` recebido contra esse timestamp (usando `UserRepository.findById`, já que o
filtro roda em toda requisição autenticada); um token emitido antes ou no mesmo segundo da invalidação
(empate resolve pra rejeitar, a direção mais segura) vira não-autenticado mesmo dentro dos 60 minutos de
expiração natural. Usuário deletado continua autenticando via assinatura (mantém o `404` já documentado
de `/users/me`, não vira `401` por engano — comportamento preexistente preservado deliberadamente, não
tocado por esta mudança). A mesma `invalidateAllSessions` já é chamada por `updateUser` numa troca de
`password`/`email` (achado anterior), então ganhou o mesmo reforço automaticamente.

Restante do "Baixo": `handleMethodArgumentNotValid` agora concatena `getFieldErrors()` +
`getGlobalErrors()`; `revokeRefreshToken` passou a checar `TokenType.REFRESH` antes de qualquer coisa,
igual `rotateRefreshToken`; `CookieUtil` ganhou `getCsrfTokenPath()` (mesma lógica do
`CookieCsrfTokenRepository` real) usado por `logout`/`logoutAll`/`deleteCurrentUser` em vez do `"/"`
hardcoded; `UserServiceImpl.validateUsernameLength` rejeita com `400` um username cujo comprimento
trimado fique abaixo de 3 (o `@Size(min=3)` do bean validation roda antes do `.trim()`, então espaços
extras driblavam o mínimo); o filtro morto `isProfilePublic` de `getUsersByUsername` foi removido de
`UserController`/`UserService`/`UserServiceImpl`/`UserRepository` (nunca era exposto por parâmetro
algum); nomes de teste com `should..._when...` renomeados pra camelCase em `UserServiceImplTest`;
`DiaryEntryController.deleteDiaryEntry` ganhou o mesmo `requestThrottler.checkAllowed` que
`POST`/`PATCH` já tinham; `Top5EntryServiceImpl.removeEntry` passou a chamar `validateType` como
`get`/`insert` já chamavam; `handleTypeMismatch` lista os valores aceitos quando o tipo alvo é um enum,
igual o handler de corpo malformado já fazia; `FollowerServiceImpl.followUser` e
`FollowedPersonServiceImpl.followPerson` trocaram `getReferenceById` (proxy preguiçoso, nunca valida
existência) por `findById().orElseThrow(NotFoundException)` pro usuário agente, fechando o `409`/`500`
confuso quando esse usuário foi deletado mas o token ainda é válido (resolve pra `404`, consistente com
o padrão já usado em `/users/me`).

Novo: `UserServiceImpl.applyPatch`, na transição `false → true` de `isProfilePublic`, chama o novo
`FollowerService.acceptAllPendingFollowRequestsFor` — aceita em cascata todas as solicitações `PENDING`
recebidas por esse usuário (antes ficavam presas pra sempre, já que só uma solicitação nova nascia
`ACCEPTED` direto quando o perfil já era público). Implementado como `@Modifying @Query` bulk-update em
`FollowerRepository`, `@Transactional` no próprio método do repositório.

`docs/context/business-rules.md` (seções User/Auth, Follower) atualizado. Único achado "Baixo" que
permanece aberto: paginação sem `Sort` em follower/followed-people — mantido como está, por design
(mesmo padrão já usado em `user`). Suíte completa passando após as mudanças.

**Adição posterior (mesmo dia): bulk logging de `SERIES` aceita finale por temporada.**
`bulkLogSeries` (`DiaryEntryServiceImpl`) exigia, para cada temporada de `1` até `finaleSeasonNumber`,
que já existisse no banco um `EPISODE` com `isSeasonFinale = true` — não havia como informar o episódio
final de uma temporada ainda não logada, então bulk-logar uma série inteira do zero (nenhuma temporada
tocada antes) sempre falhava com `400` na primeira temporada sem finale conhecido, mesmo com
`finaleSeasonNumber` correto. `DiaryEntryBulkCreationDTO` ganhou `seasonFinaleEpisodeNumbers`
(`Map<Integer, Integer>`, `seasonNumber -> finaleEpisodeNumber`); `resolveSeasonFinaleEpisodeNumber`
agora recebe, pra cada temporada do laço de `SERIES`, o valor desse mapa como fallback explícito quando
não há `EPISODE` finale já conhecido — o mesmo fallback que `SEASON` já tinha via `finaleEpisodeNumber`,
agora por temporada. Validação nova: um valor `< 1` no mapa é rejeitado com `400`
(`"finaleEpisodeNumber for season X must be greater than or equal to 1"`), já que a validação Bean do
`@Min(1)` não alcança valores de `Map`. `docs/context/openapi.yaml` (`DiaryEntryBulkCreation`) e
`docs/context/business-rules.md` (bullet de bulk logging) atualizados. Testes novos em
`DiaryEntryServiceImplTest`: valor abaixo de 1 no mapa rejeitado, e uma série de 2 temporadas
bulk-logada do zero (nenhum `EPISODE` finale pré-existente) usando só o mapa.

## ✅ 2026-08-18 — WatchlistEntry: entity, repository, service e mapper

Entidade `WatchlistEntry` (Fase 3, `WATCHLIST` do modelo lógico), repositório, service e mapper —
migration `V17__create-watchlist-entries-table.sql`, mesmo shape de `Top5Entry` (`user_id`/`content_id`
FK com `ON DELETE CASCADE`, `type` restrito a `MOVIE`/`SERIES`, `uq_watchlist_entries_user_id_type_position`
e `uq_watchlist_entries_user_id_type_content_id`), mas sem teto de 5 — `position` só tem `CHECK (position >= 1)`,
sem limite superior. `WatchlistEntryRepository.findByUserIdAndTypeOrderByPositionAsc` em duas variantes
(lista simples, usada pela lógica de shift do service; paginada, usada pela listagem).

Decisão de produto tomada durante a implementação (divergência do `openapi.yaml` original, anunciada e
aprovada antes de codar): diferente do Top5, o `POST /users/me/watchlist/{type}` **não aceita mais
`position` no corpo** — um item novo sempre entra na última posição da watchlist, nunca em uma posição
escolhida pelo cliente. A única forma de adiantar um item é um novo endpoint,
`PATCH /users/me/watchlist/{type}/{watchlistEntryId}`, que move a entrada para a `position` informada
(1..total de entradas), deslocando as entradas entre a posição antiga e a nova para abrir espaço.
`openapi.yaml` atualizado nos dois endpoints (`WatchlistEntryCreation` sem `position`, novo `PATCH`
documentado).

`WatchlistEntryService`/`WatchlistEntryServiceImpl`: `getWatchlist` (paginado, mesma regra de
privacidade de `getTop5`/`getFollowers`), `insertEntry` (sempre `count + 1`, sem shift/eviction — mais
simples que `Top5EntryServiceImpl.insertEntry` porque não há posição arbitrária nem teto pra evictar),
`removeEntry` (remove e fecha o buraco, idêntico ao Top5) e `moveEntry` (novo: desloca a entrada movida
pra uma posição temporária fora da faixa válida — `count + 1` — antes de deslocar as entradas
intermediárias, pra nunca colidir com a constraint de posição única durante os saves intermediários;
movendo pra uma posição menor desloca `[newPosition, oldPosition)` uma casa pra cima, processando da
maior posição pra menor; movendo pra uma posição maior desloca `(oldPosition, newPosition]` uma casa pra
baixo, processando da menor pra maior — só então a entrada movida assume a posição final).
`WatchlistEntryCreationDTO` (só `tmdbId`), `WatchlistEntryReorderDTO` (`position`), `WatchlistEntryResponseDTO`
e `WatchlistEntryMapper` (reaproveita `ContentMapper`). Testes unitários (service mockado) cobrindo
happy path de todos os métodos, paginação completa (checklist fixo), privacidade, 400/404/409 de cada
método, e os dois sentidos de `moveEntry` (avançar/recuar) com verificação por log de chamada (não por
`ArgumentCaptor` direto — a entrada movida é salva duas vezes com o mesmo objeto mutável, então o
captor só enxergaria o estado final nas duas capturas).

`WatchlistEntryController`: `GET /users/{userId}/watchlist/{type}` (paginado, envelope `PageResponseDTO`),
`POST /users/me/watchlist/{type}`, `PATCH /users/me/watchlist/{type}/{watchlistEntryId}` (reordenar) e
`DELETE /users/me/watchlist/{type}/{watchlistEntryId}` — mesmo prefixo `/users` de `Top5EntryController`.
Testes unitários (service mockado, 8 casos: happy path + resolução do `userId` do `SecurityContextHolder`
por endpoint) e de integração (`@SpringBootTest` + `MockMvc` + Testcontainers, 34 casos) cobrindo happy
path de cada endpoint (incluindo os dois sentidos de `moveEntry` e o caso de no-op), paginação,
privacidade, 404, 409, 400 (validação e regra de negócio), 401 e 403 (CSRF) em todos os endpoints
mutáveis.

Entidade `DroppedEntry` (Fase 3, `SERIE_ABANDONADA` do modelo lógico), migration, repositório e testes —
migration `V18__create-dropped-entries-table.sql` (`user_id`/`content_id` FK com `ON DELETE CASCADE`,
`type` restrito a `MOVIE`/`SERIES`, `comment` opcional (`TEXT`), `uq_dropped_entries_user_id_type_content_id`).
Decisão de escopo tomada durante a implementação (a pedido do usuário, anunciada antes de codar):
`SERIE_ABANDONADA` cobria só `SERIES` — passou a cobrir `MOVIE` também, e ganhou um `comment` opcional
(igual `DiaryEntry`) para registrar o motivo do abandono. Entidade renomeada de `DroppedSeries` para
`DroppedEntry` (segue a convenção `*Entry` de `Top5Entry`/`WatchlistEntry`/`DiaryEntry`), tabela
`dropped_entries` (era `dropped_series`), e ganhou uma coluna `type` (como `Top5Entry`/`WatchlistEntry`)
para desambiguar MOVIE de SERIES na constraint única, já que `content_id` sozinho já garantiria isso mas
o padrão do restante do código sempre denormaliza `type` na própria linha. `DroppedEntryRepository`:
`findByUserIdAndTypeAndContentId`/`existsByUserIdAndTypeAndContentId` (suporte à marcação idempotente,
mesmo padrão de `FollowedPersonRepository`) e `findByUserIdAndTypeOrderByCreatedAtDesc` (paginado, para
a listagem). `openapi.yaml` (`DroppedSeriesEntry` → `DroppedEntry`, rotas `/dropped-series` →
`/dropped/{type}`, efeito colateral de `POST /diary`/`POST /diary/bulk` sobre a marcação atualizado para
mencionar MOVIE), `database-schema.html` (v7 → v8, entidade renomeada no diagrama e nas colunas,
`comentario`/`updated_at` adicionados) e `CLAUDE.md`/`development-stages.md` (tabela de tradução e ordem
de fases) atualizados junto. 15 testes de repositório cobrindo idempotência de busca/existência,
paginação, ordenação por `createdAt` decrescente, persistência do `comment` (presente e nulo), constraint
única por usuário+type+conteúdo, `CHECK` de type e cascade delete. Service e controller ainda não
implementados — próximo passo da Fase 3.

Fix (reportado pelo usuário): `POST /diary` não estava removendo o filme/série da watchlist ao logar —
gap conhecido desde a implementação do Watchlist, agora fechado junto com a remoção da marcação de
`DroppedEntry` (efeito colateral já documentado em `openapi.yaml`/`database-schema.html`, mas nunca
implementado). `DiaryEntryServiceImpl.removeFromWatchlistAndDropped`, chamado por `createDiaryEntry` e
`createDiaryEntriesInBulk`: se o conteúdo logado é `MOVIE`, remove direto pelo `contentId` já resolvido;
se é `EPISODE`/`SEASON`/`SERIES`, resolve a série (por `tmdbId` sem round-trip se já for `SERIES`, por
`seriesTmdbId` via `ContentRepository.findByTmdbIdAndType` caso contrário) e remove pra ela — pulando
silenciosamente se a linha de `Content` da série ainda não existe. No bulk a remoção roda uma única vez
por chamada (resolvida do `tmdbId`/`seriesTmdbId` do próprio `DiaryEntryBulkCreationDTO.content()`), não
por episódio. Novo método `WatchlistEntryService.removeEntryIfPresent` (variante idempotente de
`removeEntry`, sem `404` quando não há nada pra remover; `WatchlistEntryServiceImpl.removeEntry` e
`removeEntryIfPresent` agora compartilham `deleteAndCloseGap`) preserva a invariante de fechar o buraco
nas posições; a remoção do `DroppedEntry` é um delete direto via `DroppedEntryRepository`, sem invariante
de posição pra proteger. Testes novos: `WatchlistEntryRepositoryTest`/`WatchlistEntryServiceImplTest`
(`findByUserIdAndTypeAndContentId`, `removeEntryIfPresent`), `DiaryEntryServiceImplTest` (MOVIE, EPISODE
resolvendo a série, SERIES direto, série ainda não referenciada) e `DiaryEntryControllerIntegrationTest`
(3 casos ponta a ponta com banco real — o cenário exato relatado pelo usuário). `business-rules.md`
atualizado com a regra em DiaryEntry e WatchlistEntry.

`DroppedEntryService`/`DroppedEntryServiceImpl`: `getDropped` (paginado, mesma regra de privacidade de
`getWatchlist`/`getTop5`), `markAsDropped` (idempotente por `userId`+`type`+`contentId`, mesmo padrão de
`FollowedPersonServiceImpl.followPerson` — checagem de existência e `saveAndFlush` de uma marcação nova
numa transação própria via `NewTransactionExecutor`, com recuperação de corrida no `catch` de
`DataIntegrityViolationException` — mas com uma diferença: como a marcação agora carrega um `comment`,
marcar de novo algo já marcado não é um no-op puro, faz upsert do `comment` quando informado
`applyCommentIfProvided`, deixando intacto quando omitido) e `unmarkAsDropped` (idempotente, busca o
`Content` só por `findByTmdbIdAndType` — nunca cria um `Content` novo, diferente do `markAsDropped`).
`DroppedEntryCreationDTO` (só `comment`, opcional), `DroppedEntryResponseDTO` e `DroppedEntryMapper`
(reaproveita `ContentMapper`). 33 testes unitários (service mockado) cobrindo happy path de todos os
métodos, paginação completa (checklist fixo), privacidade, upsert/no-op do `comment` em marcação
repetida, corrida concorrente na criação (resolve e aplica `comment`, ou relança se a recuperação também
não achar nada) e os dois casos de no-op de `unmarkAsDropped` (`Content` inexistente, marcação
inexistente). `business-rules.md` ganhou a seção DroppedEntry.

`DroppedEntryController`: `GET /users/{userId}/dropped/{type}` (paginado), `POST`/`DELETE
/users/me/dropped/{type}/{tmdbId}` — mesmo prefixo `/users` do Watchlist/Top5. `POST` aceita corpo
opcional (`{ comment }`) e ganhou rate limiting (`app.rate-limit.dropped-action`, 20 requisições/5min,
mesmo padrão de `follow-people-action`, já que chama `ContentService.getOrCreateReference` — mesmo
risco de inflar a tabela `contents` que motivou o rate limit em `/contents/reference` e `/diary`);
`DELETE` não chama esse método (só busca `Content` já existente por `tmdbId`+`type`), então ficou de
fora, mesmo critério já usado entre `POST`/`PATCH` vs `GET`/`DELETE` em `/diary`. 5 testes unitários
(service mockado: happy path + resolução do `userId`) e 26 testes de integração (`@SpringBootTest` +
`MockMvc` + Testcontainers) cobrindo happy path de cada endpoint (marcar com/sem comentário, marcar
duas vezes idempotente, sobrescrever/preservar o comentário numa remarcação, desmarcar idempotente),
paginação, privacidade, 404, 400, 401, 403 (CSRF) e 429 (rate limit) em `POST`.

Com isso a Fase 3 do roteiro (`Top5Entry`, `WatchlistEntry`, `DroppedEntry`, `DiaryEntry` e a integração
entre eles) está completa.

Entidade `UserList` (Fase 4, `LISTA` do modelo lógico), migration `V19__create-user-lists-table.sql` e
repositório — `user_id` FK com `ON DELETE CASCADE`, `name` obrigatório (`VARCHAR(255)`), `description`
opcional (`TEXT`, sem teto, igual `DiaryEntry.comment`), `is_public` (`BOOLEAN NOT NULL DEFAULT TRUE`,
mesmo padrão de `User.isProfilePublic` — o default em nível de banco é uma rede de segurança, a
aplicação do default de fato fica pro service (ver correção abaixo). Sem constraint de unicidade em
`name` — não documentada em `database-schema.html` (diferente de `Top5Entry`/`WatchlistEntry`/
`DroppedEntry`, que têm `UNIQUE` explícito), e o `openapi.yaml` não sugere que nomes de lista precisem
ser únicos por usuário, então um usuário pode ter duas listas com o mesmo nome. `UserListRepository.
findByUserId` — retorna `List<UserList>` sem paginação, espelhando o `GET /users/{userId}/lists` atual
do `openapi.yaml` (array simples, não envelope `PageMeta`); vale notar que isso é inconsistente com o
padrão de paginação já adotado em `Top5Entry`/`WatchlistEntry`/`DroppedEntry` para listagens sem teto —
decisão de manter paginado ou não fica para quando o service for implementado. 9 testes de repositório
cobrindo filtro por usuário, persistência de `description`/`is_public`, ausência de unicidade de
`name`, `NOT NULL` de `name` e cascade delete. Service, mapper, DTOs e controller ainda não
implementados.

`UserListService`/`UserListServiceImpl`, `UserListMapper` e DTOs. Decisão tomada com o usuário antes de
codar: `GET /users/{userId}/lists` passou a ser paginado (envelope `PageMeta`), alinhando com
Top5Entry/WatchlistEntry/DroppedEntry — `openapi.yaml` atualizado (parâmetros `page`/`size`, resposta
com `allOf` `PageMeta`, `400`/`404` documentados). `UserListRepository` ganhou
`findByUserId(userId, Pageable)` e `findByUserIdAndIsPublicTrue(userId, Pageable)` — a privacidade aqui
é por lista, não por perfil: diferente de todo outro `getXsByFilter` do app (que bloqueia a requisição
inteira com `403` se o perfil for privado), `getUserLists` nunca dá `403` — o dono vê tudo, qualquer
outro viewer só vê as listas com `isPublic = true`, filtrado direto na query certa pra cada caso
(paginação correta, sem filtrar em memória depois de paginar). `createUserList` constrói a entidade
direto via builder no service (não via mapper — ver `CLAUDE.md`, mesmo padrão de
`Top5Entry`/`WatchlistEntry`/`DroppedEntry`; tentei inicialmente imitar o `@AfterMapping` do
`UserMapper`, mas esse padrão só faz sentido pra `User`, que não tem FK de dono pra injetar depois).
`updateUserList` reaproveita o mesmo `UserListCreationDTO` do create (mesmo schema documentado no
`openapi.yaml` pro `POST` e pro `PATCH`) — é uma reescrita completa dos campos editáveis, não um patch
parcial com `null = não mexe`: `name` continua obrigatório, `description` é sempre sobrescrito,
`isPublic` omitido volta pro default `true`. `deleteUserList` e `updateUserList` compartilham
`findOwnedList` (mesmo padrão de `DiaryEntryServiceImpl.findOwnedEntry` — `404`, não `403`, quando a
lista não existe ou não pertence ao usuário). `watchedPercentage` no `UserListResponseDTO` é um
placeholder fixo em `0.0` até `UserListItem` existir (próximo passo da Fase 4) — não há itens ainda pra
calcular a porcentagem assistida de verdade. 25 testes unitários (service mockado) cobrindo happy path
de todos os métodos, paginação completa (checklist fixo), a regra de visibilidade por lista (não por
perfil), default de `isPublic` na criação e na atualização, e os dois casos de dono do recurso
(inexistente / pertence a outro usuário) em `PATCH`/`DELETE`. 3 testes novos de repositório para os
finders paginados. `business-rules.md` ganhou a seção UserList.

`UserListController`, com duas decisões tomadas com o usuário antes de codar. Primeira:
`POST /users/{userId}/lists` virou `POST /users/me/lists` — nenhum outro endpoint de criação "pra si
mesmo" no app usa `{userId}` no path (todos usam `/me/`), então a posse nunca depende de um `userId`
vindo do cliente; `openapi.yaml` atualizado (rota nova, `CLAUDE.md` também). Segunda: `UserListResponseDTO`
passou a embutir `user: UserPreviewDTO` (reaproveitando o DTO já usado em `GET /users`) em vez de só
`userId` — evita uma chamada extra pro cliente descobrir username/avatar do dono, principalmente em
`GET /lists/{listId}` que nem tem `userId` no path; `UserListMapper` resolve isso automaticamente
(`uses = UserMapper.class`, MapStruct casa `user` (`User`) com `user` (`UserPreviewDTO`) via
`UserMapper.userToUserPreviewDto`). Rotas: `GET /users/{userId}/lists` (paginado),
`POST /users/me/lists`, `PATCH /lists/{listId}`, `DELETE /lists/{listId}` — `GET /lists/{listId}`
(lista única com itens) continua fora de escopo, já que depende de `UserListItem` pra fazer sentido
(retornaria `items` sempre vazio). `openapi.yaml` ganhou respostas de erro (`400`/`403`/`404`) nos
quatro endpoints, que antes não tinham nenhuma documentada. 8 testes unitários (service mockado) e 23
de integração (`@SpringBootTest` + `MockMvc` + Testcontainers) cobrindo happy path de cada endpoint,
visibilidade por lista, paginação, default de `isPublic`, `400`/`404`/`401`/`403` (CSRF).

Durante a conversa, o usuário também confirmou que quer suporte a "lista de lista" (uma `UserList`
contendo outras `UserList`s como item, não só `Content`) — registrado como decisão de design em
`development-stages.md` (seção Fase 4) pra aplicar quando `UserListItem` for desenhado: profundidade
máxima de um nível, uma lista é ou "de conteúdo" ou "de listas" (nunca as duas), sem ciclo, e uma
lista-de-listas não recebe `Comentario` nem `Curtida`. Nenhum código mudou por causa disso ainda — é
puramente uma decisão registrada pra o próximo passo da Fase 4.

Fix (pedido pelo usuário): `POST /users/me/dropped/{type}/{tmdbId}` não estava removendo o conteúdo da
watchlist ao marcar como abandonado — mesma classe de gap já corrigida em `POST /diary`, agora fechada
aqui também. `DroppedEntryServiceImpl.markAsDropped` ganhou uma chamada a
`WatchlistEntryService.removeEntryIfPresent`, rodando toda vez que o método é chamado (criação nova ou
remarcação idempotente de uma já existente) — reaproveita o mesmo método idempotente já criado pra
`DiaryEntryServiceImpl`. `unmarkAsDropped` não ganhou o efeito inverso (desmarcar não reinsere na
watchlist). `openapi.yaml` e `database-schema.html` atualizados com o novo efeito colateral,
`business-rules.md` ganhou a regra em DroppedEntry. Testes novos: `DroppedEntryServiceImplTest` (2
casos — remove na criação nova e na remarcação idempotente) e `DroppedEntryControllerIntegrationTest`
(1 caso ponta a ponta com banco real).

Entidade `UserListItem` (Fase 4, `ITEM_LISTA` do modelo lógico), migration
`V20__create-user-list-items-table.sql` e repositório — schema desenhado e confirmado com o usuário
antes de codar, aplicando as regras de "lista de lista" combinadas mais cedo nesta mesma conversa
(ver parágrafo acima). `user_list_id` (dono, `ON DELETE CASCADE`), `content_id` e `child_list_id`
ambos nullable com FK pra `contents`/`user_lists` respectivamente — referência polimórfica igual
`Comentario` mira em `Conteudo`/`Lista`/`Log`, mas só com dois alvos aqui. `position` (`INTEGER NOT
NULL`) e `description` (`VARCHAR(400)` opcional, mesmo padrão de `User.description`/
`DiaryEntry.customPosterUrl` — teto reforçado no banco, não só na aplicação) espelham `UserListItem`
do `openapi.yaml`. Sem `score`/nota própria — decisão do usuário durante a conversa: a avaliação de um
conteúdo continua sendo só a de `DiaryEntry.score`, não uma por item de lista (`openapi.yaml` ainda
documentava um `score` nesse endpoint; removido junto). Constraints novas: `ck_user_list_items_target`
(exatamente um entre `content_id`/`child_list_id`, nunca os dois nem nenhum),
`ck_user_list_items_no_self_reference` (`child_list_id <> user_list_id`, bloqueia uma lista se
auto-referenciando), `ck_user_list_items_position` (`>= 1`),
`uq_user_list_items_user_list_id_position` (sem posição duplicada dentro da mesma lista) e mais duas
`UNIQUE` pra bloquear o mesmo `content_id`/`child_list_id` duas vezes na mesma lista (`NULL` não
colide em `UNIQUE` no Postgres, então não atrapalham a coluna que fica vazia em cada linha). As duas
regras que dependem de olhar o conteúdo de *outra* lista — profundidade máxima de um nível, e uma
lista travar como "de conteúdo" ou "de listas" a partir do primeiro item — não cabem num `CHECK`
simples e ficam pro `service` (próximo passo). `UserListItemRepository` ganhou
`findByUserListIdOrderByPositionAsc`, e `existsByUserListIdAndContentIdIsNotNull`/
`existsByUserListIdAndChildListIdIsNotNull` (para o service futuro decidir o "tipo" já travado de uma
lista antes de aceitar um novo item). 19 testes de repositório cobrindo os dois formatos de item
(conteúdo e lista aninhada), todo `CHECK`/`UNIQUE` novo e cascade delete nos três sentidos (lista
dona, `Content`, lista filha). `database-schema.html` e `business-rules.md` atualizados. Service,
mapper, DTOs e controller ainda não implementados.

`UserListItemService`/`UserListItemServiceImpl`, `UserListItemMapper` e DTOs (`UserListItemCreationDTO`,
`UserListItemResponseDTO`, `UserListPreviewDTO`), com duas decisões tomadas com o usuário antes de
codar. Primeira: `childListId` pode apontar tanto para uma lista do próprio usuário quanto para uma
lista pública de outro usuário (uso de curadoria — "minhas listas favoritas de outros perfis"), não só
listas próprias; `resolveChildList` checa `childList.user.id == userId` OU `childList.isPublic`, `403`
caso contrário. Segunda: `position` continua sendo aceito e opcional no `POST` (como já documentado no
`openapi.yaml`), com shift dos itens seguintes (+1) quando informado — em vez de trocar pro padrão
"sempre insere no fim + reorder separado" usado por `WatchlistEntry`/`Top5Entry`; não existe endpoint
de reorder pra `UserListItem` ainda. Durante a implementação, o usuário também cortou o campo `score`
do desenho original: `UserListItem` não tem nota própria, a avaliação de um conteúdo continua sendo só
a de `DiaryEntry.score` — migration, entity e `openapi.yaml` foram todos ajustados retroativamente
antes desse `score` chegar a ser usado em código (removido de `V20`, da entity, do DTO de criação e do
schema `UserListItem`); `description` (o único campo de anotação livre que sobrou) ganhou teto de 400
caracteres, com a coluna trocada de `TEXT` para `VARCHAR(400)` (mesmo padrão de `User.description`/
`DiaryEntry.customPosterUrl` — teto reforçado no banco, não só na aplicação).

`addItem` (`POST /lists/{listId}/items`, ainda sem controller) implementa as duas regras de "lista de
lista" que tinham ficado pendentes da migration: `assertListIsNotLockedAsListOfLists`/
`assertListIsNotLockedAsContentList` travam uma lista como "de conteúdo" ou "de listas" a partir do
primeiro item (`400` ao tentar misturar), e `resolveChildList` rejeita (`400`) uma `childListId` que já
contém outras listas como item (profundidade máxima de um nível) ou que é a própria lista sendo editada
(auto-referência). `insertAtPosition` espelha o `performMove` de `WatchlistEntryServiceImpl`: sem
`position` no body, insere no fim (`currentCount + 1`); com `position`, desloca os itens a partir dali
(+1, processados em ordem decrescente pra nunca colidir com uma posição ainda ocupada) antes de inserir
o novo item ali. `removeItem` (`DELETE /lists/{listId}/items/{itemId}`) fecha o buraco deixado (-1 nos
itens seguintes), mesmo padrão de `deleteAndCloseGap`. Conflitos de unique constraint
(`uq_user_list_items_user_list_id_content_id`/`_child_list_id`/`_position`) mapeados pra mensagens
específicas de `409`, igual ao padrão já usado em `WatchlistEntryServiceImpl`. `UserListItemMapper`
mapeia `content`/`childList` de forma mutuamente exclusiva (o lado nulo vira `null` no DTO também) e
introduz `UserListPreviewDTO` (id, user, name, isPublic) pra representar uma lista aninhada sem embutir
os próprios itens dela (evita payload pesado e recursão). `openapi.yaml` atualizado: `content` deixou
de ser `required` no `POST` (agora é `content` OU `childListId`), `score` removido, `childListId`
adicionado, `description` ganhou `maxLength: 400`, novo schema `UserListPreview`, e respostas
`400`/`403`/`404`/`409` documentadas nos dois endpoints (antes sem nenhuma resposta de erro
documentada). 28 testes unitários de service (happy paths dos dois formatos de item, embed de lista
pública de terceiro, todas as validações de `400`/`403`/`404`, todos os ramos de `409`, shift de
posição no insert e no remove) e 3 de mapper (`Mappers.getMapper` + `ReflectionTestUtils` pros `uses`,
mesmo padrão de `DiaryEntryMapperTest`). Controller ainda não implementado.

`UserListItemController` (`POST /lists/{listId}/items`, `DELETE /lists/{listId}/items/{itemId}`),
mesmo padrão fino de `WatchlistEntryController` — resolve o `userId` do `SecurityContextHolder` e
delega pro service, sem lógica própria. Com isso a Fase 4 do roteiro (`UserList` + `UserListItem`,
entity → repository → service → mapper/DTOs → controller, incluindo as regras de "lista de lista")
está completa. 4 testes unitários de controller (happy path e resolução do `userId` pros dois
endpoints, service mockado) e 25 de integração (`@SpringBootTest` + `MockMvc` + Testcontainers)
cobrindo: os três formatos de inserção (conteúdo, lista aninhada própria, lista aninhada pública de
terceiro), shift de posição real através do banco, todos os `404` (lista inexistente/de outro dono,
`childListId` inexistente, item inexistente/de outra lista), todos os `400` (nem content nem
childListId, os dois juntos, auto-referência, profundidade de lista excedida, mistura de tipo nos dois
sentidos, posição além da próxima livre), `403` de CSRF e o `403` de domínio (lista aninhada privada de
terceiro), `409` real via constraint de banco (mesmo conteúdo inserido duas vezes) e `401` nos dois
endpoints. Ao testar o shift de posição via integração, uma primeira tentativa de asserção
(`item.getContent().getTmdbId()`) disparou `LazyInitializationException` — a sessão do Hibernate já
tinha fechado quando a asserção rodou fora da requisição HTTP; corrigido comparando `getContent().getId()`
contra o id do `Content` já conhecido no teste (acessar o id de um proxy não força inicialização,
diferente de qualquer outro campo).

## ✅ 2026-08-19 — UserList: criação em lote com itens (`POST /users/me/lists/bulk`)

Novo endpoint `POST /users/me/lists/bulk` — cria uma `UserList` já populada com vários itens de
`Content` (filmes e/ou séries) numa única chamada, em vez de exigir `POST /users/me/lists` seguido de N
chamadas a `POST /lists/{listId}/items`. `UserListServiceImpl.createUserListWithItems` roda tudo numa
única transação: persiste a lista e chama `UserListItemService.addItem` uma vez por item do array
`items`, na ordem enviada (sem `position` explícito, então cada chamada cai na última posição livre) —
reaproveita 100% da lógica já existente de resolução idempotente de `Content` e das constraints de item
único, sem duplicar nada. Se qualquer item falhar (ex.: dois itens do próprio payload apontando pro
mesmo `tmdbId`+`type`), a exceção propaga e desfaz a lista inteira — tudo ou nada, nunca uma lista
parcialmente populada. Novo DTO `UserListBulkCreationDTO` (`items` exige entre 1 e 100 entradas) e novo
DTO de resposta `UserListDetailedResponseDTO` (mapeia pro schema `UserListDetailed` do `openapi.yaml`,
já documentado desde a Fase 4 mas até então sem nenhum endpoint que o retornasse), com um novo método
no `UserListMapper` usando mapeamento de dois parâmetros do MapStruct (`UserList` + `List<UserListItemResponseDTO>`
já resolvida pelo service) para não duplicar os campos de `UserListResponseDTO`. `openapi.yaml`
atualizado com o novo path e o schema `UserListBulkCreation`. Só aceita `content` por item — para
adicionar uma lista aninhada como item de uma lista recém-criada, ainda é preciso usar
`POST /lists/{listId}/items` depois. Testes unitários de service (happy path com ordem preservada,
default de `isPublic`, propagação de exceção de um item que falha) e de controller (happy path e
resolução do `userId`), mais testes de integração cobrindo persistência real, ordem/posição dos itens,
`400` (nome ausente, items vazio), `409` (mesmo conteúdo duplicado dentro do próprio payload), `401` e
`403` de CSRF.

Discutido com o usuário e removido o campo `user` de `UserListResponseDTO`/`UserListDetailedResponseDTO`
— toda rota que os retorna já tem o dono implícito no contexto da chamada (`userId` no path de
`GET /users/{userId}/lists`, ou o usuário autenticado em `POST /users/me/lists(/bulk)` e
`PATCH /lists/{listId}`), mesmo padrão já seguido por `Top5EntryResponseDTO`/`WatchlistEntryResponseDTO`/
`DiaryEntryResponseDTO`/`DroppedEntryResponseDTO`, nenhum dos quais embutia `user`; `UserList` era o
único outlier. `UserListPreviewDTO` (usado só pra representar uma lista aninhada em
`UserListItem.childList`, que pode pertencer a qualquer usuário) mantém `user`, já que ali o dono não é
óbvio pelo contexto. `UserListMapper` não precisa mais de `UserMapper` como dependência. `openapi.yaml`
(schema `UserList`) e todos os testes que construíam esses DTOs ou afirmavam contra `$.user`/`$.content[*].user`
atualizados.

Refatoração de privacidade de `UserList`, a pedido do usuário. `isPublic` (booleano) virou `visibility`
(`UserListVisibility`: `PUBLIC`/`FOLLOWERS`/`PRIVATE`, migration `V21__replace-user-lists-is-public-with-visibility.sql`,
convertendo dados existentes `true→PUBLIC`/`false→PRIVATE`). `GET /users/{userId}/lists`
(`UserListServiceImpl.getUserLists`) ganhou a barreira de perfil que antes só existia em
`Follower`/`Top5Entry`/`WatchlistEntry`/`DroppedEntry`/`DiaryEntry` (`assertCanViewLists` — 403 se o
perfil do dono for privado e o viewer não for o dono nem o seguir com status aceito), combinada com o
filtro por visibilidade que já existia: passada a barreira, o dono vê tudo e qualquer outro viewer vê
`PUBLIC` mais `FOLLOWERS` só se realmente seguir o dono (`findByUserIdAndVisibilityIn`, substituindo
`findByUserIdAndIsPublicTrue`). Novo endpoint `GET /lists/{listId}` (documentado desde a criação do
`UserListDetailed` na Fase 4, mas nunca implementado até agora) devolve a lista com seus itens,
checando a visibilidade só pela própria lista — nunca pelo perfil do dono, decisão explícita do
usuário: uma lista `PUBLIC` é acessível diretamente mesmo com o perfil do dono privado.
`UserListServiceImpl.getUserListById`/`assertListIsVisibleTo` implementam essa checagem, retornando
403 ("This list is private") pra `FOLLOWERS` sem seguir ou `PRIVATE` de outro usuário, nunca 404 (a
lista existe, só não está visível). Novo método `UserListItemService.getItems(listId)` busca e mapeia
os itens da lista pra alimentar essa resposta, reaproveitado pelo service de lista em vez de acessar o
repositório de itens diretamente. `UserListItemServiceImpl.resolveChildList` (checagem de visibilidade
de uma lista aninhada) passou a usar a mesma lógica de três estados via um `assertListIsVisibleTo`
próprio, com `FollowerRepository` injetado ali pela primeira vez. `openapi.yaml` (`UserListCreation`,
`UserList`, `UserListPreview`, os paths de listas) e `database-schema.html` (`LISTA.publica` →
`LISTA.visibilidade`) atualizados. Testes: reescrita ampla de `UserListServiceImplTest` (barreira de
perfil com/sem seguir, filtro por visibilidade, `getUserListById` nos três estados),
`UserListItemServiceImplTest` (childList `FOLLOWERS` com/sem seguir), `UserListRepositoryTest`
(`findByUserIdAndVisibilityIn`), `UserListControllerTest`/`UserListControllerIntegrationTest`
(`getUserListById`, 403 de perfil privado, lista pública acessível com perfil privado do dono). De
quebra, corrigido um bug não relacionado encontrado durante a verificação: `UserListItemController.java`
tinha o nome da classe corrompido (`zqUserListItemController`), quebrando a compilação do projeto
inteiro.

Corrigido `PATCH /lists/{listId}` para ser um patch parcial de verdade, a pedido do usuário — antes
reaproveitava o mesmo `UserListCreationDTO` do `POST`, então `name` era sempre obrigatório mesmo no
update, e não dava pra atualizar só `description` sem reenviar `name`. Novo `UserListPatchDTO`
(`name`/`description`/`visibility` todos opcionais) e `UserListServiceImpl.applyPatch`, que só altera
o campo enviado como não-`null` — mesmo padrão de `PatchUserDTO`/`UserServiceImpl.applyPatch`. `name`,
quando enviado, é `trim()`ado e validado como não-vazio no service (`BadRequestException` se
vazio/só-espaço), já que não dá pra usar `@NotBlank` no DTO sem quebrar o caso de campo omitido.
`visibility` omitido agora mantém o valor atual em vez de voltar pro default `PUBLIC` (esse default
continua existindo só na criação). `openapi.yaml` (`UserListPatch`, descrição do `PATCH`) e
`business-rules.md`/`business-rules-summary.md` atualizados; `UserListServiceImplTest`/
`UserListControllerTest`/`UserListControllerIntegrationTest` reescritos para o novo contrato (nome
único, descrição única, visibilidade única, nenhum campo, e `400` de nome em branco).

`GET /users/{userId}/lists` agora devolve, por lista, um preview de conteúdo em vez de só os metadados
da lista, a pedido do usuário. Dois campos novos em `UserListResponseDTO`: `previewItems` (até 5
primeiros itens de `Content` da lista, ordenados por `position`) e `nestedListsCount` (quantidade de
itens que são lista aninhada). Como uma `UserList` trava como "de conteúdo" ou "de listas" a partir do
primeiro item (nunca mistura, ver `UserListItem`), os dois campos são mutuamente exclusivos na prática:
alinhado com o usuário, uma lista-de-listas reporta `previewItems: []` e só a contagem em
`nestedListsCount`, sem preview do conteúdo das listas aninhadas em si. Dois métodos novos em
`UserListItemRepository` (`findTop5ByUserListIdAndContentIdIsNotNullOrderByPositionAsc`,
`countByUserListIdAndChildListIdIsNotNull`) expostos via `UserListItemService.getPreviewItems`/
`countNestedLists`, reaproveitando o `UserListItemMapper` já injetado ali em vez de acrescentar mais uma
dependência de `ContentMapper`. `UserListServiceImpl.toResponseDto` centraliza a montagem: `POST
/users/me/lists` (lista nova, sem itens) usa `List.of()`/`0` direto sem consultar nada; `PATCH
/lists/{listId}` e `GET /users/{userId}/lists` sempre reconsultam os itens reais, já que uma lista pode
ganhar itens via `POST /lists/{listId}/items` antes de ser atualizada ou listada. `UserListMapper.
userListToResponseDto` passou a exigir os dois valores como parâmetros explícitos (mesmo padrão já usado
por `userListToDetailedResponseDto`), então toda chamada existente (`createUserList`, `updateUserList`,
`getUserLists`) precisou ser ajustada. Sem batching entre as listas de uma mesma página — cada lista da
página de `GET /users/{userId}/lists` dispara duas queries extras — aceito por ora, mesmo nível de
simplicidade já usado em outros pontos do domínio `UserList`. `openapi.yaml` (`UserList.previewItems`/
`nestedListsCount`, descrição de `GET /users/{userId}/lists`) e `business-rules.md`/
`business-rules-summary.md` atualizados. Testes novos em `UserListItemRepositoryTest` (top 5 ordenado
por posição, exclui item de lista aninhada, contagem de listas aninhadas),
`UserListItemServiceImplTest` (`getPreviewItems`/`countNestedLists`) e `UserListServiceImplTest`/
`UserListControllerIntegrationTest` (preview populado de fato em `getUserLists`, lista sem itens, lista
travada como lista-de-listas).

## ✅ 2026-08-20 — UserListItem: inserção em lote numa lista existente (`POST /lists/{listId}/items/bulk`)

Novo endpoint `POST /lists/{listId}/items/bulk` — insere vários itens de `Content` numa `UserList` já
existente numa única chamada, em vez de exigir N chamadas a `POST /lists/{listId}/items`. Espelha o
`POST /users/me/lists/bulk` já existente (2026-08-19), mas para uma lista que já existe em vez de criar
uma nova: `UserListItemServiceImpl.addItems` roda numa única transação, checa posse da lista uma vez
(`findOwnedList`) e então chama `addItem` uma vez por item do array `items`, na ordem enviada, sem
`position` explícito, então cada item cai sempre na última posição livre no momento de sua própria
inserção. Reaproveita 100% da lógica já existente de `addItem` — resolução idempotente de `Content`,
constraints de item único, trava de tipo (conteúdo x lista aninhada) — sem duplicar nada. Se qualquer
item falhar (item de `content` inválido, duplicata dentro do próprio payload, item já presente na
lista, ou lista já travada como lista-de-listas), a exceção propaga e desfaz todos os itens já
inseridos por essa chamada — tudo ou nada, nunca uma lista parcialmente populada. Novo DTO
`UserListItemBulkCreationDTO` (`items` exige entre 1 e 100 entradas, só `content`, sem `childListId` —
mesma restrição de `UserListBulkCreationDTO`). `openapi.yaml` atualizado com o novo path e o schema
`UserListItemBulkCreation`. `business-rules.md`/`business-rules-summary.md` atualizados. Testes
unitários de service (happy path com ordem/posição preservada, propagação de `ConflictException` sem
inserir os itens restantes, trava de lista-de-listas, `NotFoundException` de lista inexistente/de outro
dono) e de controller (happy path e resolução do `userId`), mais testes de integração cobrindo
persistência real em ordem, append após itens já existentes, `400` (items vazio, lista travada como
lista-de-listas), `409` (duplicata dentro do próprio payload e item já existente na lista), `404`
(lista inexistente/de outro dono), `401` e `403` de CSRF.

Limitado `comment` de `DroppedEntry` a 280 caracteres (mesmo teto já usado em `User.description`),
igualando ao padrão de validação já usado em campos de anotação livre do domínio (`UserListItem.
description`, teto de 400). `@Size(max = 280)` em `DroppedEntryCreationDTO.comment`, coluna
`dropped_entries.comment` migrada de `TEXT` para `VARCHAR(280)` (`V22__limit-dropped-entries-comment-length.sql`)
e `DroppedEntry.comment` ajustado pra `@Column(length = 280)` pra bater com `ddl-auto=validate`.
`DroppedEntryController.markAsDropped` não tinha `@Valid` no corpo opcional — adicionado, já que sem
ele a anotação `@Size` no DTO nunca era checada pelo MVC. `openapi.yaml` (`maxLength: 280` no `comment`
de `POST /users/me/dropped/{type}/{tmdbId}`) atualizado. Testes de integração novos: `400` sem persistir
quando o comentário passa de 280 caracteres, `204` persistindo normalmente no limite exato de 280.

Corrigido, a pedido do usuário, um bug na mensagem de erro de `type` inválido em `Top5Entry`/
`WatchlistEntry`/`DroppedEntry`: os três domínios já restringiam `type` a `MOVIE`/`SERIES` (via
`validateType` no service, com `400` pra `SEASON`/`EPISODE`), e o `openapi.yaml` já documentava
`enum: [MOVIE, SERIES]` pra esse parâmetro em cada um — mas o `@PathVariable` dos três controllers
fazia bind direto em `ContentType` (as 4 constantes), então um valor sintaticamente inválido na URL
(ex.: `movie` minúsculo) nunca chegava a rodar `validateType`; falhava antes, no binding do Spring, e o
`GlobalExceptionHandler.handleTypeMismatch` genérico listava as 4 constantes de `ContentType` como
"aceitas", incluindo `SEASON`/`EPISODE`, que na prática nunca foram aceitas por nenhum desses três
endpoints. Novo enum `MovieOrSeriesType` (`content.entity`, só `MOVIE`/`SERIES`, com `toContentType()`)
usado no `@PathVariable type` de `WatchlistEntryController`, `DroppedEntryController` e
`Top5EntryController` no lugar de `ContentType` — a assinatura dos services não muda (continuam
recebendo `ContentType`, convertido no controller), então `validateType` continua existindo como
segunda camada de defesa pra qualquer chamador direto do service. Com o tipo do parâmetro já restrito,
o erro de binding do Spring passa a listar só `MOVIE, SERIES` automaticamente, sem precisar de nenhuma
mudança no `GlobalExceptionHandler`. Dois testes de integração já existentes (`WatchlistEntryController
IntegrationTest`, `Top5EntryControllerIntegrationTest`) afirmavam explicitamente a mensagem antiga
(com `SEASON, EPISODE` incluídos) — corrigidos pra afirmar a mensagem nova; `DroppedEntryController
IntegrationTest` não tinha esse teste ainda, adicionado por consistência com os outros dois. `business-
rules.md`/`business-rules-summary.md` atualizados nos três domínios.

Implementado, a pedido do usuário (reportou logar um filme de uma lista e `watchedPercentage`
continuar `0.0`), o cálculo real de `watchedPercentage` — até então um placeholder fixo em `0.0`
documentado desde a criação de `UserListItem`. Novo `UserListItemRepository.countWatchedContentItems`
(query `COUNT(DISTINCT uli.content.id)` com `EXISTS` contra `DiaryEntry`, pra um rewatch não inflar a
contagem) e `countByUserListIdAndContentIdIsNotNull` (denominador, só itens de `content`, ignora itens
de lista aninhada); `UserListItemService.getWatchedPercentage(listId, viewerId)` combina os dois,
retornando `0.0` sem consultar `DiaryEntry` quando a lista não tem itens de `content`. `UserListMapper.
userListToResponseDto`/`userListToDetailedResponseDto` passaram a receber `watchedPercentage` como
parâmetro explícito (mesmo padrão de `previewItems`/`nestedListsCount`/`items`) em vez do
`@Mapping(constant = "0.0")` hardcoded. Discutido com o usuário e corrigido no meio da implementação:
o percentual é do *usuário autenticado que faz a requisição* (o viewer), não do dono da lista — minha
primeira versão computava a partir do dono; `UserListServiceImpl.getUserLists`/`getUserListById` agora
passam o `viewerId` do próprio método (não o `userId` do path nem `userList.getUser().getId()`), e só
`createUserListWithItems` usa `userId` diretamente, já que ali dono e requisitante são sempre a mesma
pessoa (só existe `POST /users/me/lists/bulk`, nunca em nome de outro usuário). `openapi.yaml`
(`UserList.watchedPercentage`) e `business-rules.md`/`business-rules-summary.md` atualizados. Testes
unitários novos em `UserListItemServiceImplTest` (zero sem itens de content, proporção correta com
parte assistida, 100% com tudo assistido, zero com nada assistido) e em `UserListServiceImplTest`
(wiring do valor real do service pro mapper em `getUserLists`/`getUserListById`/
`createUserListWithItems`, incluindo prova explícita de que o viewer's watch history é usado, não o do
dono, quando os dois são pessoas diferentes). Testes de integração novos em
`UserListControllerIntegrationTest` cobrindo o cenário real reportado pelo usuário: logar via
`POST /diary` um conteúdo que já está numa lista muda `watchedPercentage` de `0.0` pro valor correto
tanto em `GET /users/{userId}/lists` quanto em `GET /lists/{listId}`; rewatch não duplica a contagem;
e, decisivamente, dono e viewer vendo a mesma lista pública recebem percentuais diferentes,
cada um refletindo só o próprio histórico de `DiaryEntry`.

Otimizado, a pedido do usuário, o N+1 de `GET /users/{userId}/lists` deixado como aceito nos dois
trabalhos anteriores deste mesmo dia: `UserListServiceImpl.getUserLists` chamava `getPreviewItems`/
`countNestedLists`/`getWatchedPercentage` uma vez por lista da página, cada um disparando sua própria
query — uma página de N listas custava `1 + 4N` queries. `UserListItemService` ganhou três métodos em
lote — `getPreviewItemsByListIds`/`countNestedListsByListIds`/`getWatchedPercentagesByListIds`
(`Collection<UUID> -> Map<UUID, ...>`) — que `getUserLists` chama uma vez cada com os ids de todas as
listas da página, reduzindo o custo pra 4 queries fixas por página. `UserListItemRepository` trocou as
antigas queries por-lista (`findTop5ByUserListIdAndContentIdIsNotNullOrderByPositionAsc`,
`countByUserListIdAndChildListIdIsNotNull`, `countByUserListIdAndContentIdIsNotNull`,
`countWatchedContentItems`) por equivalentes em lote (`findContentItemsByUserListIdInOrderByPosition`,
`countNestedListsByUserListIdIn`, `countContentItemsByUserListIdIn`,
`countWatchedContentItemsByUserListIdIn`), as três de contagem devolvendo `List<UserListCount>` — uma
projection nova (`userListId`/`count`) agrupada via `GROUP BY uli.userList.id`, mesmo padrão já usado
em `DiaryEntryRepository.EpisodeWatchCount`/`SeasonWatchMax`. A query de preview também ganhou `JOIN
FETCH uli.content`, eliminando um N+1 secundário que já existia dentro do próprio preview (cada item
inicializando o proxy lazy de `Content` on-demand pra montar o `ContentRefDTO`). Os métodos por-lista
(`getPreviewItems`/`countNestedLists`/`getWatchedPercentage`, ainda usados por `getUserListById`,
`updateUserList` e `createUserListWithItems`, que sempre operam sobre uma única lista) passaram a
delegar pros métodos em lote com uma coleção de um elemento, sem duplicar a lógica de query. Nenhum
contrato HTTP muda — puramente uma otimização de acesso a dados. Testes: `UserListItemRepositoryTest`
reescrito pras novas queries em lote (agrupamento por lista, ordenação por posição, exclusão de item de
lista aninhada); `UserListItemServiceImplTest` cobre tanto os métodos por-lista (delegando corretamente)
quanto os em lote (agrupamento e cap de 5 no preview, percentual calculado independentemente por lista);
`UserListServiceImplTest` atualizado pra mockar os métodos em lote em vez dos por-lista em
`getUserLists`. `UserListControllerIntegrationTest` (já cobria os cenários reais de ponta a ponta,
incluindo múltiplas listas na mesma página) continuou passando sem alteração, confirmando as novas
queries JPQL contra Postgres real via Testcontainers.

Auditoria pedida pelo usuário no restante do código (fora do domínio `UserList`, já coberto acima),
catalogada em `docs/pending/problems.md`. Corrigido o único item crítico dessa auditoria: bug de
segurança em `JwtCookieAuthenticationFilter.isSessionStillValid` — quando o `userId` decodificado do
`access_token` não existe mais em `users` (conta deletada), o método devolvia `true` ("sessão ainda
válida") em vez de `false`, então o token de uma conta já apagada continuava autenticando até a
expiração natural do JWT (60 min), populando o `SecurityContextHolder` para um usuário inexistente.
Corrigido pra `return false` — mesma direção "empate resolve pra rejeitar" já usada na checagem de
`sessionsInvalidatedAt` duas linhas abaixo. Descartada, por reavaliação, a ideia inicial de também
fazer `UserServiceImpl.deleteAccount` chamar `refreshTokenService.invalidateAllSessions`: como o
`access_token` é stateless e nunca consulta `sessionsInvalidatedAt` de um usuário que não existe mais,
essa chamada extra não adicionaria proteção real — o `return false` já fecha o buraco sozinho, assim
que a linha é apagada. Três testes existentes tinham o comportamento antigo (errado) embutido nas
próprias asserções e precisaram ser corrigidos: `UserControllerIntegrationTest`
(`shouldReturnNotFoundWhenAccountWasAlreadyDeleted`/`shouldReturnNotFoundWhenAccountWasDeletedAfterTokenWasIssued`,
que reusavam o token de uma conta já deletada e esperavam `404 "User not found"`, agora esperam `401`)
e `SecurityConfigIntegrationTest.shouldLetRequestReachSpringMvcRoutingWhenAccessTokenCookieIsValid`
(gerava um JWT pra um `UUID.randomUUID()` nunca persistido; passou a registrar um usuário de verdade
via `POST /auth/register` antes de emitir o token, preservando a intenção original do teste). Teste
unitário existente em `JwtCookieAuthenticationFilterTest` invertido
(`shouldStillAuthenticateWhenTheUserNoLongerExists` → `shouldNotAuthenticateWhenTheUserNoLongerExists`)
e teste de integração novo em `UserControllerIntegrationTest`
(`shouldReturnUnauthorizedWhenCalledWithTheAccessTokenOfAnAlreadyDeletedAccount`) provando `401` num
`GET /users/me` com o token de uma conta recém-deletada. `business-rules.md`/`business-rules-summary.md`
atualizados com a regra corrigida. Os outros itens da auditoria (N+1 em `GET /lists/{listId}`,
followers/following, diary/watchlist/dropped, e o shift de posição sem teto em `WatchlistEntry`)
continuam pendentes em `docs/pending/problems.md`, ainda não implementados.

Corrigido o item 2 dessa auditoria: `GET /lists/{listId}` (`UserListServiceImpl.getUserListById` →
`UserListItemService.getItems`) buscava todos os itens da lista via `findByUserListIdOrderByPositionAsc`
(sem `JOIN FETCH`) e depois mapeava cada um pra `UserListItemResponseDTO`, inicializando o proxy lazy
de `content` ou `childList` item por item — e, quando era `childList` (lista-de-listas), ainda um
segundo lazy load pra `childList.getUser()` ao montar o `UserListPreviewDTO`. Sem teto de itens (ao
contrário do preview de `getUserLists`, já limitado a 5), então uma lista de detalhe com N itens custava
até `1 + 2N` queries. `UserListItemRepository` ganhou `findByUserListIdWithContentAndChildListOrderByPositionAsc`,
com `LEFT JOIN FETCH uli.content`, `LEFT JOIN FETCH uli.childList cl` e `LEFT JOIN FETCH cl.user` num
único SELECT — `LEFT JOIN` porque as duas associações são mutuamente exclusivas por linha (`CHECK
ck_user_list_items_target`). A query original sem fetch join foi mantida só para o shift de posição
(`insertAtPosition`/`deleteAndCloseGap`), que nunca toca `content`/`childList`. Testes: três casos novos
em `UserListItemRepositoryTest` provando, via `Hibernate.isInitialized`, que `content`/`childList`/
`childList.user` já vêm carregados sem query adicional, mais ordenação por posição e filtragem por
lista inalteradas. Suíte completa do domínio `userlist` (244 testes) e `mvnw.cmd compile` continuam
verdes.

Corrigido o item 3 dessa auditoria: `GET /users/{userId}/followers`, `/following` e
`/me/follow-requests` (`FollowerServiceImpl.getFollowers`/`getFollowing`/`getPendingFollowRequests`)
mapeavam cada `Follower` da página pra `PublicUserDTO` acessando `follow.getFollower()` ou
`.getFollowed()` — ambos `@ManyToOne(fetch = LAZY)`, cada acesso disparando sua própria query, custando
`1 + N` por página. `FollowerRepository.findByFollowedIdAndStatus`/`findByFollowerIdAndStatus`, antes
derivadas, viraram `@Query` com `JOIN FETCH f.follower`/`JOIN FETCH f.followed` — seguro com `Pageable`
aqui porque é um `@ManyToOne`, não uma coleção, então não há o problema clássico de paginação em memória
que `JOIN FETCH` traz em associações `*ToMany`. `findByFollowedIdAndStatus` é reaproveitada tanto por
`getFollowers` (`ACCEPTED`) quanto por `getPendingFollowRequests` (`PENDING`), então a mesma mudança
cobriu os dois endpoints de uma vez. Spring Data continuou derivando a count query da paginação
automaticamente a partir do `@Query` sem precisar de uma `countQuery` explícita. Testes: os três testes
existentes em `FollowerRepositoryTest` que já cobriam essas queries ganharam `Hibernate.isInitialized`
no `follower`/`followed` da primeira linha (prova o fetch join) e `getTotalElements()` (prova a count
query auto-derivada correta com `JOIN FETCH`). Suíte completa do domínio `follower` (105 testes) e
suíte completa do projeto (1286 testes, só a falha pré-existente e não relacionada de
`WatchwiseApiApplicationTests.contextLoads` continua vermelha) passando.

Corrigido o item 4 dessa auditoria: `GET /users/{id}/diary`, `.../watchlist/{type}` e
`.../dropped/{type}` (`DiaryEntryServiceImpl.getDiaryEntries`, `WatchlistEntryServiceImpl.getWatchlist`,
`DroppedEntryServiceImpl.getDropped`) tinham N+1 no `content` de cada linha da página — as três
entidades têm `content` como `@ManyToOne(fetch = LAZY)`, e os três mappers embutem um `ContentRefDTO`
completo na resposta, forçando a inicialização do proxy por linha. Sem teto (as três rotas aceitam
`pageSize` até 1000, e `WatchlistEntry` não tem teto de entradas), uma página custava até `1 + N`
queries. `DiaryEntryRepository.findByUserIdOrderByCreatedAtDesc`/`findByUserIdAndWatchedDateBetweenOrderByCreatedAtDesc`,
`WatchlistEntryRepository.findByUserIdAndTypeOrderByPositionAsc` (só o overload paginado — o overload
`List<WatchlistEntry>` sem paginação, usado no shift de posição, não toca `content` e ficou como estava)
e `DroppedEntryRepository.findByUserIdAndTypeOrderByCreatedAtDesc` viraram `@Query` com `JOIN FETCH
e.content`, cada uma num único SELECT. Spring Data continuou derivando a count query da paginação
automaticamente a partir do `@Query`. Testes: os repository tests existentes das três entidades
ganharam `Hibernate.isInitialized` no `content` da primeira linha da página, provando o fetch join.
Suíte completa dos três domínios (389 testes) e suíte completa do projeto (1286 testes, só a falha
pré-existente e não relacionada de `WatchwiseApiApplicationTests.contextLoads` continua vermelha)
passando.

Corrigido o item 5 dessa auditoria: `WatchlistEntryServiceImpl.deleteAndCloseGap`/`performMove`
recarregavam a watchlist inteira do usuário+tipo e deslocavam as entradas afetadas uma a uma, cada uma
com seu próprio `save()`+`flush()` — sem teto de entradas (diferente do Top5, capado em 5), então mover
ou remover um item perto do início de uma watchlist grande podia custar centenas de round-trips numa
única requisição. Antes de implementar, testado empiricamente contra um Postgres 16 real (container
descartável) se um único `UPDATE ... SET position = position ± 1 WHERE <intervalo>` bastaria — não
basta: a constraint `uq_watchlist_entries_user_id_type_position` não é `DEFERRABLE`, e um shift de
intervalo contíguo processado numa ordem desfavorável pelo Postgres reproduz `duplicate key`. A técnica
usada em vez disso: duas queries `UPDATE` em massa com um offset grande e positivo
(`WatchlistEntryRepository.parkPositionsInRange`/`settleParkedPositions`,
`POSITION_PARK_OFFSET = 1_000_000_000`) — a fase de "park" desloca o intervalo afetado pra um espaço de
valores astronomicamente maior que qualquer posição real, e a fase de "settle" reduz pro valor final;
como o offset nunca colide com nenhuma posição real (viva ou ainda não processada), o resultado é
correto independente da ordem de processamento interna do Postgres, comprovado com um teste manual via
`docker run postgres:16-alpine` antes de tocar no código Java. `deleteAndCloseGap` virou delete+flush
seguido das duas queries em massa; `performMove` manteve o próprio "estacionamento" da entrada sendo
movida (técnica idêntica em miniatura, já existia) e trocou o loop que deslocava as outras entradas
pelo mesmo par de queries em massa sobre o intervalo calculado. `insertEntry`/`moveEntry` também
pararam de carregar a lista inteira só pra tirar `.size()`, usando a nova
`WatchlistEntryRepository.countByUserIdAndType` no lugar. Testes: `WatchlistEntryServiceImplTest`
reescrito pra verificar as chamadas às queries em massa com os parâmetros corretos em vez de capturar
saves item a item; `WatchlistEntryControllerIntegrationTest` (Postgres real via Testcontainers) já
cobria `moveEntry` avançando/recuando e `removeEntry` fechando o buraco com múltiplas entradas,
servindo como validação empírica adicional ponta a ponta. `business-rules.md`/`business-rules-summary.md`
atualizados com a nova técnica de deslocamento. Suíte completa do domínio `watchlist` (103 testes) e
suíte completa do projeto (1284 testes, só a falha pré-existente e não relacionada de
`WatchwiseApiApplicationTests.contextLoads` continua vermelha) passando. Com isso, os cinco itens de
severidade 🔴/🟠/🟡 da auditoria de N+1/performance pedida pelo usuário nesse dia estão resolvidos;
restam só os dois itens 🟢 (baixo impacto) catalogados em `docs/pending/problems.md`.

Corrigidos os dois itens 🟢 restantes dessa auditoria (baixo impacto, também a pedido do usuário). Item
6: `GET /users/{userId}/top5/{type}` tinha o mesmo N+1 no `content` do item 4, só que capado em 5
entradas (`Top5Entry.MAX_ENTRIES`), então de baixo impacto isolado — corrigido por consistência entre os
quatro domínios de "referência a `Content` numa lista pequena". `Top5EntryRepository` ganhou
`findByUserIdAndTypeWithContentOrderByPositionAsc` (com `JOIN FETCH t.content`), usado só por
`Top5EntryServiceImpl.getTop5`; o método original sem fetch join continua servindo
`insertEntry`/`removeEntry`, cuja lógica de shift/eviction não toca `content`. Item 7: `PATCH /users/me`
lia a mesma linha de `User` duas vezes na mesma requisição —
`UserController.updateCurrentUser` chamava `userService.willChangeCredentials(id, dto)` (um `findById`)
pra decidir se checa o lockout antes de mutar, e depois `userService.updateUser(id, dto)` (um segundo
`findById` independente pro mesmo id). `willChangeCredentials` virou `checkCredentialChanges`, devolvendo
um novo record `UserService.CredentialCheck(User user, boolean touchesCredentials)` em vez de só o
booleano; `updateUser` trocou o parâmetro `UUID id` por `User user` (o já carregado), eliminando o
segundo `findById`. A ordem de negócio não mudou (lockout ainda é checado antes da mutação). Essa é a
única exceção do código a services devolverem só DTOs pro controller — deliberada e pragmática, não um
novo padrão geral: `User` não tem nenhuma associação lazy, então não carrega o risco usual de cruzar
essa fronteira. Testes: `UserServiceImplTest` — suíte de `updateUser` parou de estubar `findById` e
passou a chamar `updateUser(savedUser, patchUserDTO)` diretamente; o teste de `NotFoundException`
duplicado entre `updateUser` e `willChangeCredentials` colapsou num só, movido pra
`checkCredentialChanges`; suíte de `willChangeCredentials` virou `checkCredentialChanges`, asserções
trocando de `boolean` pra `.touchesCredentials()`. `UserControllerTest` — os 9 testes de
`updateCurrentUser` ganharam um fixture `User` de teste e passaram a estubar `checkCredentialChanges`
explicitamente (dois deles antes dependiam do retorno `false` padrão do Mockito pra um `boolean` não
estubado, o que deixou de valer pra um record não-primitivo). Suíte completa do domínio `top5entry` (75
testes), suíte completa do domínio `user` (232 testes) e suíte completa do projeto (1286 testes, só a
falha pré-existente e não relacionada de `WatchwiseApiApplicationTests.contextLoads` continua vermelha)
passando. Com isso, todos os sete itens catalogados na auditoria de N+1/performance desse dia estão
resolvidos.

Iniciada a Fase 5 do build order (`Comment`), com as camadas `Entity`/`Repository`/migration —
`Service`/`Mapper`/DTOs/`Controller` ficam para um próximo passo. Nova tabela `comments`
(`V23__create-comments-table.sql`): `user_id` obrigatório, `content_id`/`list_id`/`diary_entry_id`
opcionais com `CHECK ck_comments_target` garantindo que exatamente um dos três esteja preenchido por
linha (mesmo estilo de constraint explícita já usado em `ck_contents_fields_by_type`/
`ck_user_list_items_target`, em vez de `num_nonnulls`), `parent_comment_id` como auto-referência
opcional, e `text VARCHAR(280)`/`contains_spoiler BOOLEAN` conforme `openapi.yaml`. As cinco FKs
(`user`, `content`, `list`, `diary_entry`, `parent_comment`) são todas `ON DELETE CASCADE`, incluindo a
auto-referência — apagar um comentário apaga em cascata toda a subárvore de respostas abaixo dele,
mesmo respostas de outros usuários, e apagar o autor apaga todos os comentários que ele escreveu, sem
placeholder de "usuário excluído" (decisão já registrada em `database-schema.html`). Nova entidade
`Comment` (pacote `comment`) segue o padrão de `DiaryEntry`/`UserListItem`: `@ManyToOne(fetch = LAZY)`
para as cinco associações, `content`/`list`/`diaryEntry` sem `optional = false` (mutuamente exclusivos).
`CommentRepository` ganhou só os três finders concretamente exigidos pelos endpoints já especificados em
`openapi.yaml` (`GET /contents/{contentId}/comments`, `/lists/{listId}/comments`,
`/diary/{diaryEntryId}/comments`) — `findByContentIdOrderByCreatedAtAsc`/`findByListIdOrderByCreatedAtAsc`/
`findByDiaryEntryIdOrderByCreatedAtAsc`, cada um com `JOIN FETCH c.user` pra evitar N+1 na resposta
(`Comment.user` vira `PublicUserDTO`), ordenados por `createdAt` ascendente (thread de conversa,
diferente do padrão descendente usado no diário). Nenhuma validação de aplicação (dono, "lista de
listas" não recebe comentário, `parentCommentId` apontando pro mesmo alvo do pai) foi implementada
ainda — fica para a camada de `Service`. `business-rules.md` não mudou: cataloga só regras de aplicação
já implementadas, e ainda não há nenhuma nessa camada. Novo `CommentRepositoryTest` (Testcontainers,
Postgres real): persistência nos três tipos de alvo e numa resposta, os dois `DataIntegrityViolation
Exception` do `CHECK` de exclusividade (nenhum alvo e mais de um alvo), os três finders (filtragem por
alvo, ordenação ascendente, página vazia, `user` já inicializado via `Hibernate.isInitialized`), e
cascade delete pelos cinco caminhos (`content`, `list`, `diary_entry`, autor, e a subárvore inteira via
`parent_comment_id`). Suíte completa do projeto (1304 testes, só a falha pré-existente e não relacionada
de `WatchwiseApiApplicationTests.contextLoads` continua vermelha) passando.

Completadas as camadas `Service`/`Mapper`/DTOs de `Comment` (`Controller` ainda fica pra depois).
`CommentServiceImpl` expõe três pares get/create — `getCommentsForContent`/`createCommentOnContent`,
`getCommentsForList`/`createCommentOnList`, `getCommentsForDiaryEntry`/`createCommentOnDiaryEntry` — um
por alvo polimórfico, mais `deleteComment`. Cada método resolve o alvo pelo path do endpoint que vai
chamá-lo (nunca por um campo do corpo), valida existência (`404`) e monta o `Comment` via
`Comment.builder()` direto no service (mesmo padrão de `UserList.visibility`, entidade com FK de
alvo/dono). Três regras novas de aplicação: `parentCommentId`, se informado, precisa apontar pra um
comentário do mesmo alvo (`resolveParentCommentOnContent`/`OnList`/`OnDiaryEntry` — `404` se o pai não
existe, `400` se aponta pra outro alvo, sem limite de profundidade de resposta); uma lista travada como
"de listas" nunca recebe comentário no `POST` (`assertListAcceptsComments`, reaproveita a mesma query de
`UserListItemServiceImpl.assertListIsNotLockedAsListOfLists`; o `GET` não precisa da checagem, já que
estruturalmente nunca há comentário numa lista assim); e visibilidade de alvo — `UserList` reaproveita
(duplicada, não compartilhada, mesma decisão já tomada por `UserListServiceImpl`/`UserListItemServiceImpl`)
a regra de três estados já existente, enquanto `DiaryEntry` ganhou uma checagem de visibilidade nova por
linha (`assertDiaryEntryIsVisibleTo`, regra padrão de perfil — público, dono, ou segue o dono aceito) que
não existia antes: até aqui só a listagem `GET /users/{id}/diary` checava visibilidade, nunca um acesso
direto por id, já que `PATCH`/`DELETE` sempre foram só-dono; `Comment` é o primeiro caso que permite
referenciar o `DiaryEntry` de outra pessoa diretamente pelo id, então a mesma regra de visibilidade
padrão (`Follower`/`FollowedPerson`/`Top5Entry`/`WatchlistEntry`/`DroppedEntry`/`DiaryEntry`/`UserList`)
foi replicada pra fechar essa superfície nova. `DiaryEntryRepository` ganhou `findByIdWithUser` (`JOIN
FETCH d.user`), usado só por `CommentServiceImpl`, pra evitar uma query lazy extra ao ler
`isProfilePublic` do dono. `CommentMapper` (MapStruct, reaproveita `UserMapper` pra `user` ->
`PublicUserDTO`) mapeia `content.id`/`list.id`/`diaryEntry.id`/`parentComment.id` pros campos achatados
`contentId`/`listId`/`diaryEntryId`/`parentCommentId` do `CommentResponseDTO` — MapStruct já gera o
null-check automático pra esses quatro (mutuamente exclusivos exceto `parentCommentId`, que é
independente). `CommentCreationDTO` (`text` `@NotBlank @Size(max = 280)`, `parentCommentId` opcional,
`containsSpoiler` opcional). `business-rules.md`/`business-rules-summary.md` ganharam a seção `Comment`
(retirada da lista de features "ainda não construídas" no topo do arquivo, junto com `UserList`, que já
estava documentada há tempos mas continuava citada ali por descuido). Testes: `CommentServiceImplTest`
(56 casos) — checklist fixo de paginação completo em `getCommentsForContent` (sem branch de
visibilidade), reduzido a um teste de "página/tamanho default quando nulos" nos outros dois métodos de
listagem (mesmo padrão já usado por `FollowerServiceImplTest.getFollowing`/`getPendingFollowRequests`,
que reaproveitam o `buildPageRequest` de `getFollowers`); todas as branches de visibilidade de lista e
de diário (dono, público, followers seguindo, followers não seguindo, privado); as três variações de
`resolveParentCommentOn*` (resposta válida, pai inexistente, pai de alvo errado) nos três alvos; trava
de lista-de-listas no `POST` e página vazia (sem erro) no `GET` equivalente; `containsSpoiler` default e
explícito; `deleteComment` (dono, não encontrado, dono errado). Suíte completa do projeto (1360 testes,
só a falha pré-existente e não relacionada de `WatchwiseApiApplicationTests.contextLoads` continua
vermelha) passando.

Fechada a Fase 5 do build order com `CommentController`, última camada faltante de `Comment`. Sete
endpoints, todos sem prefixo comum de classe (mesmo estilo de `DiaryEntryController`, já que os alvos
polimórficos vivem sob três raízes diferentes): `GET`/`POST /contents/{contentId}/comments`, `GET`/
`POST /lists/{listId}/comments`, `GET`/`POST /diary/{diaryEntryId}/comments`, `DELETE /comments/
{commentId}`. Nenhum usa `RequestThrottler` — mesma escolha já feita para `UserList`/`UserListItem`
(domínios mais recentes que também ficaram sem rate limit dedicado), diferente de `Diary`/`Dropped`/
`Follow*`/`Content`, que têm. Cada `GET` devolve o envelope `PageResponseDTO` padrão; cada `POST`
devolve `201` com o `CommentResponseDTO`; `DELETE` devolve `204`. Testes: `CommentControllerTest` (13
casos, service mockado, sem contexto Spring) — um `happy path` por endpoint mais a resolução do
`userId` da sessão nos seis que precisam dele (`getCommentsForContent` não, já que `Content` não tem
dono). `CommentControllerIntegrationTest` (37 casos, `@SpringBootTest` + `MockMvc` + Postgres real via
Testcontainers) — por completude, o checklist da baseline foi aplicado mas sem duplicar toda a matriz de
visibilidade já coberta em `CommentServiceImplTest`: cada endpoint ganhou `happy path` (persistência
real, ordenação ascendente por `createdAt` provada com dois comentários), `404` de alvo inexistente,
`401` sem cookie de sessão, e nos mutantes `403` de CSRF ausente; cada `POST` ganhou `400` de `text` em
branco (mais `@Size(max = 280)` só no de `Content`, já que é a mesma anotação nos três) e um caso de
`403`/`400` "extra guard" — lista privada, diário de perfil privado, lista travada como lista-de-listas,
`parentCommentId` de alvo errado ou inexistente; `DELETE` ganhou o caso de dono errado (`404`, não
revela posse). `business-rules.md` não ganhou regra nova (o controller só expõe o que o service já
decide) — só removida a nota "Controller ainda não existe" da entrada de `Comment` registrada na sessão
anterior. Suíte completa do projeto (1410 testes, só a falha pré-existente e não relacionada de
`WatchwiseApiApplicationTests.contextLoads` continua vermelha) passando. Com isso, a Fase 5 do build
order (`Comment`) está completa em todas as camadas — falta só `Like` (Fase 6) pra fechar o grupo de
endpoints "Comments"/"Likes" do `openapi.yaml`.

Iniciada a Fase 6 do build order (`Like`), com as camadas `Entity`/`Repository`/migration —
`Service`/`Mapper`/DTOs/`Controller` ficam para um próximo passo, mesma sequência já usada em `Comment`.
Nova tabela `likes` (`V24__create-likes-table.sql`): `user_id` obrigatório, `comment_id`/`diary_entry_id`
opcionais com `CHECK ck_likes_target` garantindo que exatamente um dos dois esteja preenchido por linha
(mesmo espírito do polimorfismo de `Comment`, só que com dois alvos em vez de três, replicando a
referência dupla de `UserListItem` — `Content`/`UserList` — mas para `Comment`/`DiaryEntry`). Diferente
de `Comment`, `likes` não tem `updated_at` — uma curtida não tem campo editável, só existe ou não existe
(mesmo formato de `Follower`, que também só tem `created_at`). Adicionado, sem estar explícito no ER do
`database-schema.html` (que só lista as colunas, sem parágrafo de comportamento pra `CURTIDA` ainda —
diferente de `Comment`/`DiaryEntry`/etc., que já têm parágrafo próprio), `UNIQUE(user_id, comment_id)` e
`UNIQUE(user_id, diary_entry_id)` — decisão tomada por inferência direta do contrato já documentado em
`openapi.yaml` (`POST .../like` "Curtir", `DELETE /comments/{commentId}/like` "Remover curtida", ações
no singular, não um contador incremental) e por consistência com todo par curtir/descurtir já
implementado no projeto (`Follower`, `FollowedPerson`, `DroppedEntry` — todos únicos por usuário+alvo);
a técnica de unicidade com coluna oposta sempre `NULL` não colidindo em `UNIQUE` no Postgres é a mesma já
usada em `ck_user_list_items_target`/`ck_comments_target`. Nova entidade `Like` (pacote `like`), mesmo
padrão de `Follower`: `@ManyToOne(fetch = LAZY)` para as três associações, sem `updatedAt`.
`LikeRepository` ganhou só `findByUserIdAndCommentId`/`findByUserIdAndDiaryEntryId` — o par de finders
concretamente necessário pro fluxo de curtir/descurtir idempotente (mesmo papel de
`FollowerRepository.findByFollowerIdAndFollowedId` em `unfollowUser`); nenhum método de contagem
adicionado ainda, já que `openapi.yaml` não expõe hoje nenhum campo de contagem de curtidas em `Comment`
nem `DiaryEntry` — fica para quando o `Service` for implementado, se vier a ser necessário. Observação
não bloqueante encontrada durante a leitura do `openapi.yaml`: `POST /comments/{commentId}/like` tem
par com `DELETE` (curtir/descurtir), mas `POST /diary/{diaryEntryId}/like` não tem `DELETE`
correspondente documentado — provável lacuna da spec a esclarecer com o usuário quando o `Service`/
`Controller` de `Like` for implementado, não resolvida nesta sessão. Nenhuma validação de aplicação foi
implementada ainda — fica para a camada de `Service`. `business-rules.md` não mudou, mesmo raciocínio já
usado em `Comment` nesse estágio. Novo `LikeRepositoryTest` (Testcontainers, Postgres real): persistência
nos dois tipos de alvo, os dois `DataIntegrityViolationException` do `CHECK` de exclusividade (nenhum
alvo e os dois alvos), os dois `DataIntegrityViolationException` de unicidade (mesmo usuário curtindo o
mesmo comentário/registro de diário duas vezes) mais os dois casos que provam que a unicidade não
colide indevidamente (mesmo usuário curtindo alvos diferentes, usuários diferentes curtindo o mesmo
alvo), os dois finders (presente/ausente), e cascade delete pelos três caminhos (`comment`,
`diary_entry`, autor da curtida). Suíte completa do projeto (1425 testes, só a falha pré-existente e não
relacionada de `WatchwiseApiApplicationTests.contextLoads` continua vermelha) passando.

## ✅ 2026-08-21 — Like: service e testes (Controller ainda pendente)

Continuada a Fase 6 do build order (`Like`) com a camada `Service` — `Mapper`/DTOs ficaram de fora por
decisão consultada com o usuário: os três endpoints de `Likes` no `openapi.yaml` (`POST`/
`DELETE /comments/{commentId}/like`, `POST /diary/{diaryEntryId}/like`) respondem `204` sem corpo, então
não há nenhuma superfície de resposta que precisaria de um DTO — mesma situação (e mesma escolha) já
usada em `Follower`/`FollowedPerson`, que também não têm mapper/DTOs. `LikeService` ficou com três
métodos `void`: `likeComment`, `unlikeComment`, `likeDiaryEntry` — sem `unlikeDiaryEntry`, já que o
`openapi.yaml` não documenta `DELETE /diary/{diaryEntryId}/like` (gap identificado na sessão anterior,
ainda não resolvido; fica para quando o `Controller` for implementado e essa assimetria puder ser
decidida com o usuário). `LikeServiceImpl.likeComment`/`likeDiaryEntry` seguem o mesmo padrão de
get-or-create idempotente contra corrida já usado em `FollowedPersonServiceImpl.followPerson`
(documentado em `CLAUDE.md`): checagem `existsByUserIdAndCommentId`/`existsByUserIdAndDiaryEntryId`
antes de tentar salvar (curtir de novo não faz nada, não é `ConflictException`), tentativa de
`saveAndFlush` isolada em `NewTransactionExecutor.runInNewTransaction` (`REQUIRES_NEW`) com a entidade
`Like` inteira construída dentro do mesmo lambda, e reconsulta de existência só se o `saveAndFlush`
falhar com `DataIntegrityViolationException` (propaga a exceção original só se a linha continuar
ausente). `unlikeComment` é delete-if-present sem checar se o comentário existe, mesmo padrão de
`unfollowPerson`. Antes de salvar, `likeComment`/`likeDiaryEntry` reaproveitam (duplicada, não
compartilhada — mesma decisão já tomada em `Comment`/`UserList`/`UserListItem`) a mesma regra de
visibilidade de `CommentServiceImpl`: `Content` sempre visível, `UserList` usa `PUBLIC`/`FOLLOWERS`/
`PRIVATE`, `DiaryEntry` usa a regra padrão perfil público/dono/segue-aceito — `403` se violado, checagem
que só roda enquanto a curtida ainda não existe (uma curtida já registrada nunca é revogada por mudança
de visibilidade posterior). Duas adições de repositório: `LikeRepository.existsByUserIdAndCommentId`/
`existsByUserIdAndDiaryEntryId` (base do curtir idempotente) e `CommentRepository.findByIdWithTargets`
(novo `@Query` com `LEFT JOIN FETCH` em `list`/`list.user`/`diaryEntry`/`diaryEntry.user`, evita
`LazyInitializationException` ao ler o dono do alvo de um comentário fora de uma transação aberta
explicitamente — `likeComment` não é `@Transactional`, diferente de `CommentServiceImpl`).
`business-rules.md`/`business-rules-summary.md` atualizados com a nova seção `Like`. Novo
`LikeServiceImplTest` (24 casos): `likeComment` (novo/idempotente/`NotFoundException`/as três variações
de alvo — `Content`, `UserList` `PRIVATE`/`FOLLOWERS` dono e não-dono, `DiaryEntry` privado dono/segue/
não-segue — mais os dois casos de corrida do `DataIntegrityViolationException`), `unlikeComment`
(presente/ausente), `likeDiaryEntry` (mesmo formato de `likeComment`, sem a variação de alvo por
`UserList`). `LikeRepositoryTest` e `CommentRepositoryTest` ganharam os testes dos métodos novos
(`existsBy...`/`findByIdWithTargets`, incluindo `Hibernate.isInitialized` provando o `JOIN FETCH`).
Suíte completa do projeto (1457 testes, só a falha pré-existente e não relacionada de
`WatchwiseApiApplicationTests.contextLoads` continua vermelha) passando.

Fechada a Fase 6 do build order com `LikeController`, última camada faltante de `Like`. Antes de
implementar, resolvida com o usuário a assimetria já sinalizada nas duas sessões anteriores: adicionado
`DELETE /diary/{diaryEntryId}/like` ao `openapi.yaml` e `LikeService.unlikeDiaryEntry` (mesmo padrão
delete-if-present de `unlikeComment`/`unfollowPerson`) — os quatro endpoints de `Likes` agora são todos
simétricos (curtir/descurtir nos dois alvos). `LikeController` ganhou os quatro: `POST`/
`DELETE /comments/{commentId}/like`, `POST`/`DELETE /diary/{diaryEntryId}/like`, todos `204` sem corpo
(sem `PageResponseDTO`/DTO de resposta, coerente com a decisão já tomada na sessão anterior de não ter
`LikeMapper`/DTOs). Sem `RequestThrottler`, mesma escolha já feita para `Comment`/`UserList`/
`UserListItem`. Testes: `LikeControllerTest` (8 casos, service mockado, sem contexto Spring) — um
`happy path` por endpoint mais a resolução do `userId` da sessão nos quatro. `LikeControllerIntegrationTest`
(19 casos, `@SpringBootTest` + `MockMvc` + Postgres real via Testcontainers) — cada `POST` ganhou `happy
path` (persistência real), `404` de alvo inexistente, `401` sem cookie de sessão (com CSRF válido, pra
isolar do `403`), `403` de CSRF ausente, e um caso de visibilidade (`403` de lista privada pro
`likeComment`, `403` de diário privado pro `likeDiaryEntry`); `likeComment` também ganhou o caso de
idempotência (curtir duas vezes persiste só uma linha); cada `DELETE` ganhou `204` removendo a curtida
existente, `204` idempotente quando não havia curtida (sem `404`, mesmo padrão de `unfollowPerson`),
`401` e `403` de CSRF. `business-rules.md`/`business-rules-summary.md` atualizados — a entrada de `Like`
perdeu a nota de gap aberto (resolvida) e ganhou a nota de ausência de `RequestThrottler`. Suíte completa
do projeto (1486 testes, só a falha pré-existente e não relacionada de
`WatchwiseApiApplicationTests.contextLoads` continua vermelha) passando. Com isso, a Fase 6 do build
order (`Like`) está completa em todas as camadas.

Adicionado curtir/descurtir uma `UserList` diretamente (`likeList`/`unlikeList`), a pedido do usuário
após perceber que só comentário e log tinham curtida — divergência anunciada e consultada antes de
implementar (protocolo de "announce-then-ask" do `CLAUDE.md`), incluindo a pergunta em aberto sobre se
uma lista-de-listas poderia ser curtida diretamente: decidido que não, mesma trava de `Comment`. Terceiro
alvo em `likes` (migration `V25__add-list-target-to-likes.sql`): coluna `list_id`, `FK` pra
`user_lists` `ON DELETE CASCADE`, `ck_likes_target` expandido de dois pra três alvos exclusivos (mesmo
formato do `ck_comments_target`), `uq_likes_user_id_list_id` novo. `Like.list` (`@ManyToOne(LAZY)`),
`LikeRepository.findByUserIdAndListId`/`existsByUserIdAndListId`. `LikeServiceImpl.likeList` reaproveita
o `assertListIsVisibleTo` já existente (mesma regra `PUBLIC`/`FOLLOWERS`/`PRIVATE` usada por
`likeComment` quando o alvo do comentário é uma lista) e o mesmo padrão idempotente de
`likeComment`/`likeDiaryEntry` (`NewTransactionExecutor`/`REQUIRES_NEW`); ganhou `assertListAcceptsLikes`
(reaproveitando `UserListItemRepository.existsByUserListIdAndChildListIdIsNotNull`, mesma query de
`assertListAcceptsComments`), `400` se a lista for de listas. `unlikeList` é delete-if-present, mesmo
padrão dos outros dois alvos. `LikeController` ganhou `POST`/`DELETE /lists/{listId}/like`, sem
`RequestThrottler`, mesma escolha já feita pros outros dois pares. `openapi.yaml` (novo path, tag
`Likes`), `database-schema.html` atualizado pra v10 (coluna `lista_id` em `CURTIDA`, relação
`LISTA ||--o{ CURTIDA : recebe`, novo parágrafo de comportamento explicando o polimorfismo de três alvos
e a trava de lista-de-listas) e `CLAUDE.md` (tradução de `Like` na tabela PT→EN, grupo de endpoints
`Lists`). Aproveitada a sessão pra confirmar e cobrir com teste uma dúvida do usuário: curtir uma resposta
a outro comentário já funcionava (a visibilidade de `likeComment` é resolvida pelos campos `content`/
`list`/`diaryEntry` do próprio comentário, não por `parentCommentId`) — não era um bug, só faltava o
teste explícito. `business-rules.md`/summary atualizados. Testes novos: `LikeRepositoryTest` (persistência
no terceiro alvo, exclusividade de três vias — comentário+lista, os três juntos —, unicidade por lista,
mesmo usuário curtindo lista+comentário, usuários diferentes curtindo a mesma lista, os dois finders,
cascade delete pela lista), `LikeServiceImplTest` (`likeList`/`unlikeList` espelhando `likeComment`/
`unlikeComment`, incluindo a trava de lista-de-listas e o teste de resposta-pode-ser-curtida),
`LikeControllerTest` (4 casos novos), `LikeControllerIntegrationTest` (11 casos novos: happy path,
idempotência, `404`, `403` de lista privada, `400` de lista-de-listas, `401`/`403` de CSRF nos dois
verbos). Suíte completa do projeto (1525 testes, só a falha pré-existente e não relacionada de
`WatchwiseApiApplicationTests.contextLoads` continua vermelha) passando.

A pedido do usuário, corrigido um N+1/O(N²) real encontrado numa auditoria do código em busca de
implementações "só pra funcionar" pensando em escala: `UserListItemServiceImpl.insertAtPosition`/
`deleteAndCloseGap` carregavam a lista inteira de itens (`findByUserListIdOrderByPositionAsc`) e
reposicionavam item a item com `save`/`flush` individual por linha — sem teto de itens numa `UserList`
(diferente de `Top5Entry`, capado em 5), inserir/remover no meio de uma lista grande custava `O(N)`
round-trips, e `addItems` (lote) compunha isso pra `O(N²)`. Corrigido reaproveitando a mesma técnica de
duas queries `UPDATE` em massa já usada por `WatchlistEntryRepository.parkPositionsInRange`/
`settleParkedPositions` (estacionar o intervalo afetado num offset gigante, depois assentar no valor
final — evita colisão com a constraint de posição única independente da ordem interna do `UPDATE`):
`UserListItemRepository` ganhou `countByUserListId` (substitui carregar a lista inteira só pra saber o
tamanho) e as mesmas duas queries de `WatchlistEntry`, escopadas por `userListId`.
`UserListItemServiceImplTest` reescrito pra verificar as chamadas de repositório em vez de mutação de
entidade (o novo fluxo não carrega mais os itens existentes em memória).

Tentativa de aplicar a mesma correção a `Top5EntryServiceImpl` (pedida junto, mesmo a auditoria já tendo
apontado que ali o loop é inofensivo — capado em `MAX_ENTRIES = 5`) foi revertida depois de quebrar
contra o Postgres real nos testes de integração existentes (`Top5EntryControllerIntegrationTest`):
`top5_entries.position` tem `CHECK (position BETWEEN 1 AND 5)` (`ck_top5_entries_position`), e a técnica
de "estacionar" num offset gigante viola esse `CHECK` imediatamente, já que o Postgres valida `CHECK`
por linha, não no fim da transação — diferente de `WatchlistEntry`/`UserListItem`, cujo `position` só
tem piso (`>= 1`), sem teto. `Top5EntryServiceImpl`/`Top5EntryRepository`/`Top5EntryServiceImplTest`
voltaram exatamente ao estado anterior (loop item a item, já correto e, dado o teto de 5, sem problema
de escala real). `business-rules.md`/`business-rules-summary.md` atualizados nos três domínios
(`UserListItem` com a técnica nova, `Top5Entry` com a explicação de por que o loop continua sendo a
implementação certa ali, `WatchlistEntry` com a referência cruzada corrigida). Suíte completa do projeto
(1525 testes, mesma contagem de antes — só trocou implementação, não comportamento externo; só a falha
pré-existente e não relacionada de `WatchwiseApiApplicationTests.contextLoads` continua vermelha)
passando.

Auditoria do `openapi.yaml` contra a implementação real dos controllers/services (documento
`docs/pending/openapi-review-2026-08-21.md`), corrigidos três desvios confirmados: `POST /contents/
{contentId}/comments`, `POST /lists/{listId}/comments`, `POST /diary/{diaryEntryId}/comments`,
`DELETE /comments/{commentId}`, `POST`/`DELETE /comments/{commentId}/like` e `POST`/`DELETE /diary/
{diaryEntryId}/like` ganharam os `400`/`401`/`403`/`404` que `CommentServiceImpl`/`LikeServiceImpl`
já lançavam mas o doc não documentava (as variantes `DELETE .../like`, que são no-op idempotente sem
exceção no código, só ganharam `401`, igual ao padrão já usado em `DELETE /lists/{listId}/like`);
`PATCH /diary/{diaryEntryId}` ganhou o `content`/`schema` do `200` que faltava (`DiaryEntry`, igual ao
padrão de `PATCH /users/me/watchlist/{type}/{watchlistEntryId}`); `DELETE /diary/{diaryEntryId}` ganhou
o `429` que faltava (mesmo bucket de rate limit de `POST /diary`/`PATCH /diary/{diaryEntryId}`), já
documentado nos outros dois verbos.

Dos gaps prováveis listados na mesma auditoria (doc e código concordavam em não ter, mas o produto
provavelmente precisa), implementado o item escolhido pelo usuário: `PATCH /lists/{listId}/items/{itemId}`
para editar `position` e/ou `description` de um item de `UserList` sem precisar remover e recriar
(perdendo `id`/`createdAt`) — mesma lacuna que `WatchlistEntry` já não tinha (`PATCH /users/me/watchlist/
{type}/{watchlistEntryId}`). Novo `UserListItemPatchDTO` (`position`/`description`, ambos opcionais).
`UserListItemServiceImpl.updateItem` reaproveita o mesmo algoritmo de deslocamento em massa
(`parkPositionsInRange`/`settleParkedPositions`) e a mesma técnica de posição temporária de
`WatchlistEntryServiceImpl.performMove` (espelhado como `UserListItemServiceImpl.performMove`) — posição
igual à atual é no-op, maior que o total de itens é `400`, concorrência no `save` vira `409`; description
só é persistida quando o valor difere do atual. Extraído `findOwnedItem` (antes inline em `removeItem`)
pra compartilhar a checagem de posse item+lista entre `updateItem` e `removeItem`. `UserListItemController`
ganhou o `PATCH`; `openapi.yaml` documentado com o mesmo nível de detalhe do `PATCH` da watchlist.
`business-rules.md`/summary atualizados. Testes novos: `UserListItemServiceImplTest` (mudança/no-op de
`description`, mover pra frente/pra trás espelhando os testes de `moveEntry`, no-op quando a posição não
muda, os dois campos juntos na mesma chamada, `400` de posição além do total, quatro variantes de
`NotFoundException` — lista inexistente, lista de outro usuário, item inexistente, item de outra lista —,
`409` de concorrência), `UserListItemControllerTest` (2 casos novos), `UserListItemControllerIntegrationTest`
(18 casos novos: descrição isolada, mover pra frente/pra trás com verificação de posição real no banco,
os dois campos juntos preservando o `id`, no-op, `400`s, os quatro `404`s, `401`/`403` de CSRF). Suíte
completa do projeto passando (1574 testes, 0 falhas).

## ❌ 2026-08-24 — Fix: efeito colateral não atômico em `DroppedEntryServiceImpl.markAsDropped`

Corrigido item de alta severidade catalogado em `docs/pending/to-fix.md`: a remoção da entrada
correspondente da watchlist (`WatchlistEntryService.removeEntryIfPresent`, `@Transactional` própria)
comitava de forma independente, antes da criação/upsert do `DroppedEntry` — que roda em transação
isolada (`NewTransactionExecutor.runInNewTransaction`) pelo padrão de get-or-create idempotente
resistente a corrida. Qualquer falha na criação além da própria corrida de constraint já tratada (erro
transitório de BD, etc.) deixava o item removido da watchlist sem nunca virar "dropped" — perdido dos
dois lugares, sem rollback possível.

`DroppedEntryServiceImpl.markAsDropped` passou a ser `@Transactional`, fazendo o `removeEntryIfPresent`
(que já era `@Transactional` sem propagação própria) participar da mesma transação ambiente em vez de
comitar isoladamente; qualquer falha não recuperada entre os dois passos agora reverte a remoção da
watchlist também. A criação do `DroppedEntry` continua isolada em `REQUIRES_NEW`, necessária pra não
contaminar a transação ambiente numa corrida de constraint (mesmo padrão documentado em `CLAUDE.md` para
`ContentServiceImpl`/`FollowedPersonServiceImpl`) — resta uma janela residual bem mais estreita (só
entre o commit dessa transação isolada e o commit final da ambiente), o mesmo trade-off já aceito nos
outros usos de `NewTransactionExecutor` no projeto. `business-rules.md`/`business-rules-summary.md`
atualizados. Suíte de `dropped` (70 testes: service, controller, controller-integration) passando sem
mudança de comportamento externo.

Também consolidados `docs/pending/openapi-review-2026-08-21.md`, `performance-and-scale-review-2026-08-21.md`
e `problems.md` (achados ainda abertos, reconferidos contra o código atual, itens já corrigidos movidos
pra uma seção própria) dentro de `docs/pending/to-fix.md`; os três arquivos-fonte foram apagados depois
da consolidação.

Corrigido também o item de alta severidade seguinte do `to-fix.md`: `UserServiceImpl.updateUser`
aceitava follow requests pendentes em cascata (`acceptAllPendingFollowRequestsFor`, ao tornar o perfil
público) **antes** do `save` do usuário, cada um comitando de forma independente — se o `save` falhasse
depois (conflito de unicidade de email/username), o aceite ficava permanentemente aplicado mesmo com a
API respondendo erro. `updateUser` passou a ser `@Transactional`, unificando as duas operações na mesma
transação. Isso expôs uma armadilha de deadlock só descoberta rodando o teste de integração: chamar
`refreshTokenService.invalidateAllSessions` (que abre `REQUIRES_NEW` numa conexão física separada, pra
persistir a revogação mesmo se a transação chamadora reverter depois) de dentro dessa mesma transação
trava indefinidamente — a linha `users` já tem um lock não commitado da transação ambiente, e o
`REQUIRES_NEW` bloqueia esperando esse lock liberar, travamento que só se resolveria quando o método
retornasse, que por sua vez esperava o `invalidateAllSessions` retornar primeiro. Resolvido registrando
`invalidateAllSessions` como `TransactionSynchronization.afterCommit()` (roda só depois que a transação
ambiente já comitou e o lock já foi liberado); fora de um contexto transacional real (chamada direta em
teste de unidade) cai de volta pra chamada síncrona, preservando o comportamento dos testes existentes
sem precisar alterar as asserções — só os stubs/verifies de `userRepository.save` viraram
`saveAndFlush` (necessário pra manter o `DataIntegrityViolationException` de conflito de unicidade
observável de forma síncrona dentro do `try/catch`, já que dentro de uma transação um `save` sem flush
explícito só dispara o INSERT/UPDATE real no commit). `business-rules.md`/`business-rules-summary.md`
atualizados. `UserServiceImplTest` (74), `UserControllerTest` (26) e `UserControllerIntegrationTest` (43)
passando.

Corrigido o próximo item de alta severidade do `to-fix.md`: `UserListServiceImpl.deleteUserList` só
chamava `userListRepository.delete(userList)` — se a lista apagada estivesse aninhada como `childList`
dentro de outra lista, a FK `fk_user_list_items_child_list` (`ON DELETE CASCADE`) removia o item
correspondente na lista pai silenciosamente, sem passar pelo fluxo `parkPositionsInRange`/
`settleParkedPositions`, deixando a lista pai com um buraco de posição. Nova
`UserListItemRepository.findByChildListId` + `UserListItemService.removeItemsReferencingChildList`
(novo método, reaproveitando o `deleteAndCloseGap` privado já usado por `removeItem`) buscam **todos**
os itens que referenciam a lista sendo apagada — cobrindo o caso de a mesma lista estar aninhada em
várias listas-pai de donos diferentes, já que `childListId` pode apontar pra lista de terceiros — e
fecham o buraco em cada lista pai correspondente antes do `delete` de fato rodar.
`UserListServiceImpl.deleteUserList` passou a chamar esse método antes de
`userListRepository.delete(userList)`. A metade do achado original sobre `watchlist_entries` (mesmo
padrão de `ON DELETE CASCADE` via `fk_watchlist_entries_content`) ficou de fora por ser inatingível na
prática — nenhum caminho de código apaga um `Content`, que é imutável por design. Testes novos:
`UserListItemServiceImplTest` (item único referenciando, itens em listas-pai distintas, nenhum item
referenciando), `UserListServiceImplTest` (ordem de chamada via `InOrder`, não chamado nos casos de
`NotFoundException`), e um teste de integração ponta a ponta em `UserListControllerIntegrationTest`
apagando uma lista aninhada no meio de três e verificando as posições reais no banco depois. Documentado
em `business-rules.md`/`business-rules-summary.md`. Suíte de `userlist`/`userlist-item` (`UserListServiceImplTest`
53, `UserListItemServiceImplTest` 69, `UserListControllerIntegrationTest` 51,
`UserListItemControllerIntegrationTest` 48, `UserListControllerTest` 12, `UserListItemControllerTest` 8)
passando.

Corrigido o próximo item de alta severidade do `to-fix.md`: `UserListItemServiceImpl.resolveChildList`
só chamava `existsByUserListIdAndChildListIdIsNotNull(childListId)`, validando que a lista **referenciada**
ainda não continha listas aninhadas — nunca validava se a lista que estava **recebendo** o item já era
ela própria filha de outra lista. Isso permitia encadear profundidade arbitrária (`X → A → B → ...`,
cada lista nova sem filhos próprios ainda passa pela checagem existente), contradizendo a mensagem de
erro do próprio código ("nesting depth is limited to one level"). Nova
`UserListItemRepository.existsByChildListId`, chamada em `resolveChildList` antes da checagem existente,
rejeita (`400`) quando a lista-pai já está referenciada como `childList` em qualquer outro item.
Documentado em `business-rules.md`/`business-rules-summary.md`. Novo teste em
`UserListItemServiceImplTest` cobrindo o guard (70 testes passando).

Corrigido o próximo item de alta severidade do `to-fix.md`: `DiaryEntryServiceImpl.wipeSeriesHistory`
apagava uma `DiaryEntry` de nível SERIES sem filtrar por `watchNumber`, ao contrário dos caminhos irmãos
(`retractSeasonIfIncomplete`/`retractSeriesIfIncomplete`), que usam um threshold de `watchNumber` pra
retrair só a passada de rewatch afetada. Apagar uma única entrada de série (ex.: um registro duplicado
de rewatch) removia episódios/temporadas de **todos** os ciclos de rewatch daquela série, inclusive os
não relacionados ao registro apagado. `DiaryEntryRepository.findAllEpisodeEntriesInSeries`/
`findAllSeasonEntriesInSeries`/`findAllSeriesEntries` renomeados para
`findEpisodeEntriesInSeriesByWatchNumber`/`findSeasonEntriesInSeriesByWatchNumber`/
`findSeriesEntriesByWatchNumber`, cada um com um novo parâmetro `watchNumber` filtrando a query;
`deleteDiaryEntry` captura o `watchNumber` da entrada de série antes de apagá-la e repassa pra
`wipeSeriesHistory`/`computeSeriesWipeCandidates`, escopando a limpeza à mesma passada de rewatch do
registro apagado. Documentado em `business-rules.md`/`business-rules-summary.md`. Testes existentes
atualizados para os novos nomes/assinatura (um deles testava exatamente o comportamento desproporcional
antigo, ajustado para um cenário consistente) e novo teste cobrindo a regressão (duas passadas de
rewatch, apagar a segunda preserva a primeira). `DiaryEntryServiceImplTest` (106) e
`DiaryEntryRepositoryTest` (15) passando.

Corrigido o próximo item do `to-fix.md` (performance): `JwtCookieAuthenticationFilter.isSessionStillValid`
carregava a entidade `User` inteira em **toda requisição autenticada**, só pra ler
`sessionsInvalidatedAt` (checagem de "logout all sessions"). Nova
`UserRepository.findSessionsInvalidatedAtById`, uma projeção fechada (`SessionsInvalidatedAtView`), no
lugar do `findById` de entidade inteira. Armadilha descoberta ao implementar, diferente da correção
originalmente esboçada no `to-fix.md` (`@Query` retornando `Optional<LocalDateTime>` puro): projetar só
a coluna anulável faz o Hibernate devolver um `null` cru (não uma tupla) quando o campo é `null`, e o
Spring Data não consegue proxyar a projeção em cima de `null` — `Optional.empty()` acabava representando
tanto "usuário não existe" quanto "usuário existe mas o campo é nulo", quebrando a distinção que a regra
de negócio depende (usuário inexistente → sessão inválida; campo nulo → sessão sempre válida). Resolvido
incluindo uma segunda coluna sempre não-nula (`getId()`) na projeção, forçando uma tupla de verdade.
Documentado em `business-rules.md`/`business-rules-summary.md`. `JwtCookieAuthenticationFilterTest`
atualizado pro novo método (projeção via interface anônima, já que a interface deixou de ser funcional
com dois métodos); três novos testes em `UserRepositoryTest` cobrindo exatamente os três casos da
ambiguidade (valor nulo, valor presente, id inexistente).

Corrigido o próximo item do `to-fix.md` (performance): criação em lote de lista/itens
(`/users/me/lists/bulk`, `/lists/{listId}/items` em lote) não era batched de verdade — cada item do
lote passava pelo `addItem` individual (`findOwnedList` redundante, get-or-create de content,
`countByUserListId`, `INSERT` e `flush()` síncronos por item), ~80-100 round-trips bloqueantes pra um
lote de 20 itens. `UserListItemServiceImpl.addItems` reescrito: `findOwnedList` e
`assertListIsNotLockedAsListOfLists` rodam uma vez só antes do loop, `countByUserListId` também roda
uma vez só pra achar a posição inicial, as entidades `UserListItem` são montadas em memória com
posições incrementais pré-calculadas, e a persistência vira um único `saveAll(...)` + `flush()`.
`UserListServiceImpl.createUserListWithItems` passou a delegar o lote inteiro pra esse mesmo
`addItems` numa única chamada, em vez de mapear cada item pra uma chamada individual de `addItem`
(eliminando a duplicação entre os dois métodos de criação em lote). O get-or-create de content
continua por item (lookup por `tmdbId` distinto, inevitável). Documentado em
`business-rules.md`/`business-rules-summary.md`. Testes: `UserListItemServiceImplTest` (caso de
sucesso e caso de conflito reescritos pra `saveAll`) e `UserListServiceImplTest` (4 testes de
`createUserListWithItems` reescritos pra mockar `addItems`) — suíte `userlist` completa (288 testes,
incluindo integração) passando sem mudança de comportamento observável pelo cliente.

Corrigido o próximo item do `to-fix.md` (corrida): `LikeServiceImpl.unlikeComment`/`unlikeDiaryEntry`/
`unlikeList` faziam find-then-delete (`findByUserIdAndCommentId(...).ifPresent(like -> { delete;
decrementLikesCount; })`) — duas chamadas concorrentes de unlike pro mesmo par usuário+alvo podiam
passar pelo `SELECT` antes de qualquer uma comitar o `DELETE`; quando a primeira comitava, o `DELETE`
da segunda afetava 0 linhas, mas o `decrementLikesCount` rodava incondicionalmente mesmo assim, sem
checar quantas linhas foram de fato apagadas (`Like` não tem `@Version`, sem exceção de lock otimista
pra pegar a corrida). O contador ia silenciosamente ficando abaixo da contagem real com o tempo. Nova
`LikeRepository.deleteByUserIdAndCommentId`/`deleteByUserIdAndDiaryEntryId`/`deleteByUserIdAndListId`
(`@Modifying @Query("DELETE FROM Like l WHERE ...")`, retorno `int`), substituindo o find-then-delete
de entidade; `decrementLikesCount` só roda quando o `DELETE` em massa afeta pelo menos uma linha. O
próprio `DELETE` serializa a corrida via lock de linha do Postgres — a segunda chamada concorrente
bloqueia até a primeira comitar e, liberada, reavalia o `WHERE` sem encontrar mais nada pra apagar. As
antigas `findByUserIdAndCommentId`/`findByUserIdAndDiaryEntryId`/`findByUserIdAndListId` foram
removidas por ficarem sem uso. Documentado em `business-rules.md`/`business-rules-summary.md`,
incluindo nota histórica de que a armadilha de `clearAutomatically` documentada anteriormente pra essas
mesmas queries deixou de se aplicar (sem entidade `Like` carregada na sessão, não há mais nada pro
`clear()` descartar nesse caminho). Testes: `LikeServiceImplTest` (6 casos reescritos pro delete em
massa) e `LikeRepositoryTest` (6 casos reescritos pra `deleteByUserIdAnd*`, verificando linhas afetadas
e o estado real no banco); `LikeControllerIntegrationTest` ajustado (assertivas que dependiam dos
finders removidos passaram a usar `existsByUserIdAnd*`). Suíte `like` completa (124 testes, incluindo
integração) passando.

Também commitado nesta sessão (trabalho de uma sessão anterior que estava pendente, sem commit, na
working tree — só ganhou a correção da corrida acima por cima): `likesCount` desnormalizado em
`Comment`/`DiaryEntry`/`UserList` (coluna `likes_count`, migration `V26`, `NOT NULL DEFAULT 0`,
backfill via subquery contra `likes` na própria migration). `likeComment`/`likeDiaryEntry`/`likeList`
incrementam via `CommentRepository`/`DiaryEntryRepository`/`UserListRepository.incrementLikesCount`
(`@Modifying UPDATE ... + 1`) dentro da mesma transação física do `saveAndFlush` do `Like`.
`unlikeComment`/`unlikeDiaryEntry`/`unlikeList` ganharam `@Transactional` (antes rodavam sem
transação própria). Estado "curtido por mim" (`likedByMe`) resolvido em lote via três novas queries
indexadas em `LikeRepository` (`findLikedCommentIds`/`findLikedDiaryEntryIds`/`findLikedListIds`),
reaproveitando os índices de suporte das `UNIQUE` já existentes — sem índice novo; `getCommentsFor*`
(incluindo `getCommentsForContent`, que ganhou parâmetro `viewerId`), `getDiaryEntries` e
`getUserLists` resolvem a página inteira numa query, endpoints de item único reaproveitam o mesmo
método batched com uma coleção de 1 id, endpoints de criação usam `false` literal. Bug real pego pelo
`LikeControllerIntegrationTest` existente durante essa sessão anterior: `@Modifying(clearAutomatically
= true)` nas queries de incremento/decremento apagava a remoção pendente do `Like` em
`unlikeComment`/`unlikeDiaryEntry`/`unlikeList` (entity manager `clear()` descartava o delete de
entidade ainda não sincronizado) — corrigido removendo `clearAutomatically`; esse cenário específico
deixou de existir de qualquer forma com a correção da corrida acima, que não carrega mais entidade
`Like` nenhuma nesses três métodos. Documentado em detalhe em `business-rules.md`/
`business-rules-summary.md` → Comment/DiaryEntry/UserList/Like.

Corrigido o próximo item do `to-fix.md` (média severidade): `GlobalExceptionHandler` não tinha handler
genérico de fallback — qualquer exceção não mapeada explicitamente (um bug genuíno, uma dependência
lançando algo inesperado) caía no corpo de erro padrão do Spring Boot em vez do `ApiError` do projeto.
Adicionado `@ExceptionHandler(Exception.class)` (`handleUnexpectedException`) ao final da classe
(ganhou `@Slf4j`) — loga a exceção real via `log.error` mas devolve só uma mensagem genérica fixa ("An
unexpected error occurred") ao cliente, nunca a mensagem/stack trace real. `CLAUDE.md` (Architecture →
Error handling) atualizado com uma frase descrevendo o catch-all. Novo
`GlobalExceptionHandlerIntegrationTest` (`@SpringBootTest`+`MockMvc`, `@MockitoBean` substituindo o
`UserRepository` inteiro — token JWT gerado direto via `JwtService` pra autenticar sem precisar de
registro real, já que o repositório está mockado; `findSessionsInvalidatedAtById` estubado pra
autenticar normalmente e `findById` estubado pra lançar uma `RuntimeException` arbitrária, forçando
`GET /users/{userId}` a cair no catch-all) confirma `500` no formato `ApiError` real, não o
`ProblemDetail` padrão do Spring. `GlobalExceptionHandlerTest` ganhou um caso unitário confirmando que
a mensagem genérica não vaza nenhum trecho da exceção original. Suíte completa do projeto (1592
testes) passando.

Corrigido o próximo item do `to-fix.md`: `isRewatch=true` no primeiro registro de um conteúdo
corrompia contadores — `Math.max(maxWatchNumber + 1, isRewatch ? 2 : 1)` criava a entrada direto com
`watchNumber=2` quando não havia registro anterior, sem nunca existir `watchNumber=1` pra aquele par
usuário+conteúdo. Análise (consultada com o usuário antes de implementar, já que divergia do
comportamento documentado): o campo é inofensivo pra `MOVIE`/`SERIES` avulso — nada agrega por cima
desses tipos, e esse é o uso pretendido do campo ("já assisti antes de logar no app"). A corrupção real
só afeta `EPISODE`/`SEASON`: `minEpisodeWatchCount`/`minSeasonWatchMax` contam quantidade de entradas
por episódio/temporada (não o valor de `watchNumber`), então a conclusão automática de temporada/série
ainda dispara certo, mas o `watchNumber` do episódio/temporada mal-rotulado fica permanentemente
deslocado de +1 em relação aos irmãos — quebrando especificamente o `DELETE` em massa escopado por
igualdade de `watchNumber` de `wipeSeriesHistory` (item 5, corrigido antes nesta mesma sessão): apagar
a entrada de série da "passada 1" não encontraria o episódio mal-rotulado (`watchNumber=2`), mesmo ele
logicamente pertencendo a essa mesma passada. Optou-se por travar `isRewatch` só para `EPISODE`/
`SEASON` (nova `DiaryEntryServiceImpl.participatesInCompletionTracking`): primeiro log desses tipos
sempre força `watchNumber=1`, ignorando a flag; `MOVIE`/`SERIES` mantêm o comportamento original.
Documentado em `business-rules.md`/`business-rules-summary.md`. Três novos testes em
`DiaryEntryServiceImplTest` (EPISODE e SEASON ignoram a flag no primeiro log; SERIES continua
honrando). Suíte `diaryentry` completa (205 testes) passando.

Corrigido o próximo item do `to-fix.md` (performance): faltavam índices compostos nas queries
ordenadas de maior tráfego. `DiaryEntryRepository.findByUserIdOrderByCreatedAtDesc` (provavelmente o
read-path de maior tráfego do app — "ver o diário de um usuário") só tinha `idx_diary_entries_user_id`,
sem cobrir a coluna de ordenação; Postgres filtrava por `user_id` e ordenava cada página na hora em vez
de percorrer um índice já ordenado — piorava linearmente com o tamanho do diário de cada usuário.
Mesmo gap em `comments` (indexado por FK mas não pareado com `created_at`). Nova migration
`V27__add-composite-indexes-for-ordered-feed-queries.sql`: `idx_diary_entries_user_id_created_at
(user_id, created_at DESC)` — casando com o `ORDER BY ... DESC` da query — e três índices ascendentes
em `comments` (`content_id`/`diary_entry_id`/`list_id` + `created_at`), casando com o `ORDER BY ...
ASC` das três queries de listagem de comentário (ordem cronológica de leitura de thread). Só adiciona
índices, sem mudança de schema/comportamento — validado rodando `DiaryEntryRepositoryTest`/
`CommentRepositoryTest` (Testcontainers, Flyway real) pra confirmar que a migration aplica sem erro.

## ❌ 2026-08-25 — Fix: configuração de batch insert/update do JPA ausente

Corrigido o próximo item do `to-fix.md` (performance): nem `hibernate.jdbc.batch_size` nem
`hibernate.order_inserts`/`order_updates` estavam setados em lugar nenhum, então operações em lote
(mesmo já usando `saveAll`) continuavam emitindo um round-trip por linha em vez de statements
multi-linha de verdade. Adicionado `spring.jpa.properties.hibernate.jdbc.batch_size=25`,
`hibernate.order_inserts=true` e `hibernate.order_updates=true` no `application.properties` base (e não
duplicado por profile, já que é uma configuração universal) — seguro porque toda entidade do projeto
usa `@GeneratedValue(strategy = GenerationType.UUID)`, sem round-trip de ID gerado pelo banco pra
quebrar o batching do Hibernate. `reWriteBatchedInserts=true` adicionado na URL JDBC do Postgres em
`application-dev.properties`; `application-prod.properties` ganhou um comentário lembrando de incluir
o mesmo parâmetro na `DB_URL` de produção, já que ali a URL vem de variável de ambiente.

Corrigido o próximo item do `to-fix.md` (performance): faltava índice em `diary_entries.watched_date`
pra visão "ano em revisão" (`findByUserIdAndWatchedDateBetweenOrderByCreatedAtDesc`), que filtra por
`user_id` + range de `watched_date` sem nenhum índice cobrindo essa coluna. Nova migration
`V28__add-diary-entries-watched-date-index.sql`: `idx_diary_entries_user_id_watched_date (user_id,
watched_date)`. O `ORDER BY created_at DESC` da mesma query continua sendo ordenado à parte — um único
índice `btree` não cobre ao mesmo tempo um range numa coluna e ordenação por outra, então o índice cobre
a parte cara (o filtro num diário grande) e deixa o Postgres ordenar só o resultado já filtrado do ano.
Só adiciona índice, sem mudança de schema/comportamento.

Corrigido o próximo item do `to-fix.md`: paginação de followers/following sem ordenação
determinística. A decisão anterior (2026-08-20, "deixar como está, é um padrão do projeto inteiro") foi
reaberta e revertida a pedido do usuário. `FollowerRepository.findByFollowedIdAndStatus`/
`findByFollowerIdAndStatus` ganharam `ORDER BY f.createdAt DESC` na própria JPQL — mesmo padrão de
ordenação hardcoded no `@Query` já usado em `WatchlistEntry`/`Top5Entry`/`DiaryEntry`, em vez de injetar
`Sort` via `buildPageRequest`. Nenhum índice novo necessário — os índices compostos já existentes
(`idx_followers_followed_id_status`/`idx_followers_follower_id_status`) já deixam o filtro barato, o
Postgres só ordena a página resultante. Dois testes novos em `FollowerRepositoryTest`
(`shouldReturnEntriesOrderedByMostRecentlyCreatedFirstWhenMultipleEntriesExistOnFindByFollowedIdAndStatus`/
`...OnFindByFollowerIdAndStatus`) provam a ordem mais-recente-primeiro rodando contra Postgres real
(Testcontainers). Suítes `FollowerRepositoryTest` (15 testes), `FollowerServiceImplTest` (42 testes) e
`FollowerControllerIntegrationTest` (32 testes) passando.

Corrigido o próximo item do `to-fix.md`: validações de entrada ausentes / clamps silenciosos, nas suas
duas partes. Primeira: `DiaryEntryBulkCreationDTO.finaleEpisodeNumber`/`finaleSeasonNumber` ganharam
`@Max(100)` (mesmo teto de `DiaryEntryServiceImpl.MAX_BULK_EPISODES`), e `seasonFinaleEpisodeNumbers`
ganhou `Map<@Min(1) @Max(100) Integer, @Min(1) @Max(100) Integer>` nas chaves e valores. Achado durante
a correção: sem o `@Max` em `finaleSeasonNumber`, `bulkLogSeries` soma `totalEpisodes` num loop que
consulta o banco (`resolveSeasonFinaleEpisodeNumber`) uma vez por temporada **antes** de checar
`MAX_BULK_EPISODES` — um `finaleSeasonNumber` grande combinado com um `seasonFinaleEpisodeNumbers`
igualmente grande (sem limite de tamanho antes da correção) permitia até uma consulta ao banco por
temporada informada, sequencialmente, num único request, antes do `400` disparar. Limitar a chave/valor
do mapa a 1-100 também limita o tamanho do mapa (no máximo 100 chaves distintas possíveis), fechando o
caminho. Três testes novos em `DiaryEntryControllerIntegrationTest` provam o `400` contra Postgres real.
Segunda parte: `buildPageRequest` (duplicado em 8 services) tratava `pageSize > 1000` silenciosamente
como se fosse `null` (caía no default de 20) em vez de clampar em 1000 — corrigido nas 8 cópias
(`UserServiceImpl`, `DiaryEntryServiceImpl`, `CommentServiceImpl`, `UserListServiceImpl`,
`WatchlistEntryServiceImpl`, `DroppedEntryServiceImpl`, `FollowerServiceImpl`,
`FollowedPersonServiceImpl`), cada uma ganhando uma constante `MAX_PAGE_SIZE = 1000` e clampando nela em
vez de cair no default; os 8 testes correspondentes foram renomeados e reafirmados contra
`MAX_PAGE_SIZE`. Suítes das 8 services e `DiaryEntryControllerIntegrationTest` (68 testes) passando.

Corrigido o próximo item do `to-fix.md` (reuso): `buildPageRequest` estava duplicado literalmente nos 8
services acima (mesmo corpo, mesmas constantes `DEFAULT_PAGE`/`DEFAULT_PAGE_SIZE`/`MAX_PAGE_SIZE`),
inclusive a variante com suporte a `sortBy`/`sortDirection` do `UserServiceImpl`. Extraído para
`common.pagination.PageRequestFactory`, um `@Component` único injetado nos 8 services via
`@RequiredArgsConstructor`, com `build(pageNumber, pageSize)` e `build(pageNumber, pageSize, sortBy,
sortDirection)`; as constantes viraram `public static final` nessa classe. Os métodos e constantes
duplicados foram removidos de cada `*ServiceImpl`. Nos 8 testes de serviço, o mock de
`PageRequestFactory` foi trocado de `@Mock` para `@Spy` com uma instância real (`new
PageRequestFactory()`) — assim os testes que já capturavam o `PageRequest` calculado (via
`ArgumentCaptor`) continuam exercitando a aritmética real de paginação sem precisar re-stubar cada
combinação de entrada. Novo `PageRequestFactoryTest` cobre o checklist fixo de paginação uma única vez
(antes reafirmado em cada um dos 8 testes de serviço). `CLAUDE.md` atualizado para referenciar
`PageRequestFactory` em vez de `UserServiceImpl.buildPageRequest`. Suítes das 8 services, o novo
`PageRequestFactoryTest` (14 testes) e as suítes de repositório/integração afetadas passando.

## 2026-08-27 — Minutos e gêneros assistidos: `runtimeMinutes`/`genres` em `Content`; stats no perfil do usuário

Nova feature, a pedido do usuário: expor quantos minutos (total e últimos 30 dias) e quais gêneros um
usuário já assistiu, com base no `DiaryEntry`. Como filme/série nunca é armazenado no banco e o backend
nunca chama o TMDB sozinho, a duração e os gêneros de cada conteúdo precisaram virar dado
client-supplied em `Content` — 3ª e 4ª exceções à regra de imutabilidade de `Content` (as duas primeiras
sendo `isSeasonFinale`/`isSeriesFinale`, ver `CLAUDE.md` § Avoid).

`Content` ganhou `runtimeMinutes` (`Integer`, migration `V29`, só aceito em `MOVIE`/`EPISODE`) e `genres`
(`List<String>`, migration `V30`, coluna `text[]` mapeada via `@JdbcTypeCode(SqlTypes.ARRAY)`, só aceito
em `MOVIE`/`SERIES` — gênero é propriedade de filme/série inteiro no TMDB, não existe por episódio).
Ambos opcionais (cliente pode não saber o valor; nesse caso o conteúdo simplesmente não contribui pra
nenhuma agregação). `ContentServiceImpl.validate` ganhou a restrição por `type` de cada campo;
`normalize`/`normalizeGenres` faz trim + ordenação alfabética dos gêneros antes de salvar/comparar, pra
não gerar falso-conflito só por causa da ordem em que o cliente mandou a lista. `assertNoFinaleMismatch`
foi renomeado para `assertNoMetadataMismatch` e ganhou a mesma checagem de divergência (409) já aplicada
a `isSeasonFinale`/`isSeriesFinale`, agora cobrindo os dois campos novos.

`DiaryEntryRepository` ganhou quatro queries de agregação por `userId` (all-time e últimos 30 dias):
`sumRuntimeMinutesByUserId*` (JPQL, soma só `MOVIE`/`EPISODE` — `SEASON`/`SERIES` são marcadores
sintéticos de conclusão de `maybeCompleteSeason`/`maybeCompleteSeries` e contariam o mesmo tempo em
dobro) e `sumRuntimeMinutesByGenreAndUserId*` (native query — `unnest()` de array não existe em JPQL;
pra uma `DiaryEntry` de `EPISODE`, resolve os gêneros a partir do `Content` `SERIES` com o mesmo
`seriesTmdbId`, já que o episódio nunca carrega `genres` próprio).

Os quatro números (`totalMinutesWatched`, `minutesWatchedLast30Days`, `genreMinutesWatched`,
`genreMinutesWatchedLast30Days`) foram adicionados a `UserResponseDTO` (`/users/me`, `/auth/*` — sempre
computados de verdade, exceto em `saveNewUser`, onde um usuário recém-criado não pode ter `DiaryEntry`
e o cálculo é pulado). Para `GET /users/{userId}`, em vez de colocá-los em `PublicUserDTO` — que também é
reaproveitado por `FollowerServiceImpl` pras listas paginadas de seguidores/seguindo/solicitações
pendentes, o que geraria uma consulta de agregação por item da página — foi criado `PublicUserProfileDTO`
(perfil público completo, exclusivo do item único), com um método de mapper próprio
(`UserMapper.userToPublicUserProfileDto`). `PublicUserDTO` continua sem os campos de stats.
`GenreWatchTimeDTO(genre, minutes)` novo em `common.dto`. `openapi.yaml` ganhou o schema reusável
`WatchTimeStats`, combinado via `allOf` em `UserResponseDTO`/`PublicUserProfileDTO` (mesmo padrão já
usado por `PageMeta`/`ContentRef`).

`ContentRefCreationDTO`/`ContentRefDTO` ganharam construtor secundário com a aridade antiga (sem
`runtimeMinutes`/`genres`), evitando reescrever as dezenas de call sites posicionais já existentes nos
testes — só os pontos que exercitam os dois campos novos usam o construtor completo.

Testes novos: 9 em `ContentServiceImplTest` (aceitação por tipo, rejeição por tipo, conflito de
divergência, normalização de ordem dos gêneros), 2 em `UserServiceImplTest` (stats propagadas de
verdade via `ArgumentCaptor`, `saveNewUser` não consulta `DiaryEntryRepository`), 1 novo em
`UserMapperTest` para `userToPublicUserProfileDto`, 6 em `DiaryEntryRepositoryTest` e 2 em
`UserControllerIntegrationTest` (Testcontainers — não executados nesta sessão por falta de Docker no
ambiente; suítes restantes, ~900 testes fora dos que dependem de Testcontainers, passando).

## 2026-08-27 — Endpoints de suporte às telas do frontend (`telas.md`): gênero por contagem, filtros do diário, agregados de listas, rank e ordenação

Levantamento de `docs/context/telas.md` contra o backend atual identificou várias lacunas reais. Este
bloco cobre a primeira leva delas.

**Gêneros mais assistidos: de soma de minutos para contagem de títulos.** Revertida parte do dia
anterior: `genreMinutesWatched`/`genreMinutesWatchedLast30Days` (soma de `runtimeMinutes` por gênero)
trocado por um único `genreCounts` (`GenreCountDTO`, renomeado de `GenreWatchTimeDTO`), contando títulos
`MOVIE`/`SERIES` **distintos** por gênero, all-time, sem recorte de 30 dias — soma de minutos por gênero
não tinha uso claro em `telas.md`, que só pede "gêneros mais assistidos" como contagem. Para `EPISODE`,
a contagem resolve pro `Content` `SERIES` do mesmo `seriesTmdbId` (`COUNT(DISTINCT` conteúdo `)`), assim
10 episódios da mesma série contam 1, não 10.

**Diário: filtros de tipo, range de datas e review.** `GET /users/{id}/diary` ganhou três parâmetros
opcionais: `type` (filtra por `content.type`), `dateFrom`/`dateTo` (substituem/complementam `year` —
`year` continua funcionando como atalho pro range do ano inteiro; os dois grupos juntos na mesma
request são `400`), e `hasReview` (`true` filtra só entradas com `comment` preenchido). Serve tanto a
tela de History (filtro de tipo, range livre) quanto Reviews (`hasReview=true`) e o futuro resumo do
Perfil (últimos episódios via `type=EPISODE`).

**`UserList`: `itemsCount`, `commentsCount`, `totalRuntimeMinutes`.** `UserListResponseDTO`/
`UserListDetailedResponseDTO` ganharam os três campos. `itemsCount` reaproveita
`UserListItemRepository.countByUserListId`; `commentsCount` é uma query nova em `CommentRepository`
(`countByListId`/`countByListIdIn`, batched no preview da lista de listas, mesmo padrão já usado pra
`nestedListsCount`); `totalRuntimeMinutes` soma `content.runtimeMinutes` só dos itens que apontam pra um
`Content` direto (itens que são lista aninhada não têm runtime próprio e ficam fora da soma).

**`UserList.rank` — ordenação manual da lista de listas.** Novo campo `rank` (`Integer`, nullable,
migration `V31`, `UNIQUE (user_id, rank)` — múltiplos `NULL` não colidem entre si no Postgres, então
várias listas sem rank definido coexistem normalmente). Atribuído automaticamente na criação
(próximo valor livre). Reordenar é feito via `PATCH /lists/{listId}` (`rank` como mais um campo
opcional do patch), reaproveitando a técnica de posição temporária + deslocamento em massa já usada por
`WatchlistEntry.moveEntry` (`UserListServiceImpl.applyRankChange`, com `parkRanksInRange`/
`settleParkedRanks`), escopada por `userId` (rank é por dono, não global). `updateUserList` passou a usar
`saveAndFlush` (era `save`) para capturar `DataIntegrityViolationException` de uma reordenação
concorrente como `ConflictException`, com o `applyRankChange` chamado dentro do mesmo bloco try/catch do
save final.

**Ordenação da lista de listas.** `GET /users/{userId}/lists` ganhou `sortBy`/`sortDirection`, aceitando
`rank`, `updatedAt`, `name` (alfabética — `UserList.name` é coluna real, diferente de item de lista, ver
abaixo), `likesCount`, `itemsCount` e `commentsCount`. Os quatro primeiros usam o `Sort` genérico do
`PageRequestFactory`; `itemsCount`/`commentsCount` não são colunas de `UserList`, então usam duas
queries nativas próprias (`findByUserIdOrderByItemsCount`/`findByUserIdOrderByCommentsCount`) com o
truque `CASE WHEN :sortDirection = 'ASC' THEN x END ASC, CASE WHEN :sortDirection = 'DESC' THEN x END
DESC` pra parametrizar a direção em SQL nativo.

**Itens da lista: filtro por tipo/gênero, ordenação por posição/data/duração.** `GET /lists/{listId}`
ganhou `type`, `genre`, `sortBy` (`position`, `dateAdded`, `duration`) e `sortDirection`. Aplicado em
memória (o endpoint nunca foi paginado — sempre busca a lista inteira de itens), preservando
`itemsCount`/`totalRuntimeMinutes` refletindo a lista inteira, não a view filtrada. Ordenação
alfabética e por data de lançamento de **item** continuam fora do backend (diferente da lista de listas
acima) — `Content` nunca guarda título nem data de lançamento do filme/série.

`docs/context/telas.md` atualizado marcando cada lacuna resolvida; `openapi.yaml`, `business-rules.md`
e `business-rules-summary.md` atualizados junto. Suítes de `UserList`/`UserListItem` (159 testes fora
dos que dependem de Testcontainers) e as suítes de `DiaryEntry`/`User` afetadas passando.

**Séries em andamento.** `GET /users/{userId}/series-in-progress` — sem entidade nova, totalmente
derivado de `DiaryEntry`+`Content` já existentes. Uma série entra na lista quando o usuário tem pelo
menos uma `DiaryEntry` de `EPISODE` daquele `seriesTmdbId` e nenhuma `DiaryEntry` de `SERIES`
correspondente (checagem `NOT EXISTS`, mesma semântica de conclusão que `maybeCompleteSeries` já
fecharia se a série tivesse sido completada). Nova query nativa em `DiaryEntryRepository`
(`findSeriesInProgressByUserId`, com CTEs pra achar a temporada mais avançada e o maior episódio dentro
dela) devolve `seriesTmdbId`, `maxSeasonNumber`, `maxEpisodeNumber` e `lastWatchedDate`
(`COALESCE(watched_date, created_at::date)`), paginada e ordenada por `lastWatchedDate DESC` — o único
dos cinco critérios de ordenação pedidos em `telas.md` que o backend consegue calcular sem o total de
episódios da série (dado só do TMDB). Reaproveita a mesma checagem de visibilidade de perfil
(`assertCanViewDiary`) já usada por `getDiaryEntries`. `docs/context/telas.md`, `openapi.yaml`
(novo schema `SeriesInProgress`) e `business-rules.md`/`-summary.md` atualizados junto. Testes novos:
13 em `DiaryEntryServiceImplTest`, 2 em `DiaryEntryControllerTest`, 5 em `DiaryEntryRepositoryTest` e 6
em `DiaryEntryControllerIntegrationTest` (esses dois últimos não executados nesta sessão por falta de
Docker no ambiente).

**Listas curtidas.** `GET /users/me/liked-lists` — sempre auto-visão (não existe a variante
`/users/{userId}/liked-lists`), já que o viewer só pode estar pedindo as próprias curtidas, então não
há visibilidade de terceiro pra resolver. Nova query em `LikeRepository`
(`findLikedListsByUserId`, JPQL `SELECT l.list ... ORDER BY l.createdAt DESC`) devolve as `UserList`
curtidas, mais recente primeiro. `UserListServiceImpl.getLikedLists` reaproveita o mesmo batching de
preview/`itemsCount`/`commentsCount`/`totalRuntimeMinutes`/`likedByMe` já usado por `getUserLists` —
essa lógica foi extraída pro método privado comum `mapToResponseDtoPage` nesta mudança, em vez de
duplicada entre os dois métodos. `docs/context/telas.md`, `openapi.yaml` (novo path
`/users/me/liked-lists`) e `business-rules.md`/`-summary.md` atualizados junto. Testes novos: 9 em
`UserListServiceImplTest`, 2 em `UserListControllerTest`, 3 em `LikeRepositoryTest` e 4 em
`UserListControllerIntegrationTest` (esses dois últimos não executados nesta sessão por falta de Docker
no ambiente).

**Perfil: endpoint de resumo (Fase 8).** `GET /users/{userId}/summary?type=MOVIE|SERIES` — novo pacote
`summary` (`controller`/`service`/`service/impl`/`dto`), fechando a Fase 8 do `development-stages.md`.
`type` é obrigatório e escopa a resposta inteira, sem variante agregada — mesmo padrão de
`Top5Entry`/`WatchlistEntry`/`DroppedEntry`; ausente/inválido é `400`, validado no service (não via
`@RequestParam(required = true)`, pra não vazar o erro default do Spring). `SummaryServiceImpl` não tem
regra de domínio própria, é só agregação sobre queries/serviços já existentes: `watchTime` (total +
últimos 30 dias) e `genreCounts` reaproveitam a mesma convenção de `EPISODE` como tipo interno de SERIES
já usada pelo stats de perfil; `ratingsDistribution` é uma query nova (`GROUP BY score`);
`recentEpisodes` (só quando `type=SERIES`) e `recentReviews` chamam `DiaryEntryService.getDiaryEntries`
direto, sem query nova; `recentActivity` mescla em memória o top-6 de `DiaryEntry` (nível MOVIE/SERIES,
o marcador de conclusão) com o top-6 de `DroppedEntry`, ordena por data e corta em 6. Quatro queries
novas em `DiaryEntryRepository` (`sumRuntimeMinutesByUserIdAndContentType[AndWatchedDateBetween]`,
`countDistinctTitlesByGenreAndUserIdForMovies`/`...ForSeries`, `countByUserIdAndContentTypeGroupByScore`,
`findTopByUserIdAndContentTypeOrderByCreatedAtDesc`). `openapi.yaml`'s antigo schema `ActivitySummary`
(nunca implementado, formato divergente do decidido aqui) foi substituído por `Summary`, não só
complementado. `docs/context/telas.md` e `development-stages.md` atualizados junto, marcando a Fase 8
("Resumo") como concluída e anotando `series-in-progress`/`liked-lists` como agregações do mesmo tipo
que surgiram fora do build order original. Testes novos: 15 em `SummaryServiceImplTest`, 2 em
`SummaryControllerTest`, 8 em `DiaryEntryRepositoryTest` e 7 em `SummaryControllerIntegrationTest`
(esses dois últimos não executados nesta sessão por falta de Docker no ambiente).

**Docker ficou disponível no meio da sessão — primeira validação real de todo o trabalho do dia contra
Postgres.** Até aqui, todo o trabalho desta sessão (itens acima) só tinha rodado os testes que não
dependem de Testcontainers; Docker ficou disponível no ambiente e a suíte completa (1748 testes) rodou
pela primeira vez de ponta a ponta. Isso expôs um bug real de produção e três bugs de teste, todos
corrigidos na mesma sessão:

- **Bug real**: `DiaryEntryRepository.findByUserIdWithFilters` (o filtro `year`/`dateFrom`/`dateTo`
  introduzido nesta sessão) quebrava com `could not determine data type of parameter` no Postgres real
  toda vez que uma data era informada — `GET /users/{id}/diary?year=`/`dateFrom=`/`dateTo=` estava
  efetivamente `500` desde que o filtro foi implementado. Corrigido com `CAST(... AS date)` explícito
  na checagem `IS NULL` dos dois parâmetros `LocalDate` (ver `business-rules.md` § DiaryEntry pro
  detalhe técnico).
- **Bug de teste**: `DiaryEntryRepositoryTest.shouldExcludeEntriesOutsideTheWindowOrWithNoWatchedDateWhenSummingRuntimeMinutes`
  passava um `Content` transiente (nunca persistido) direto pro `DiaryEntry` antes de salvar — corrigido
  persistindo o `Content` primeiro.
- **Bug de teste**: `UserListRepositoryTest.shouldShiftRanksForwardWhenMovingAListBackward` (item de rank
  desta sessão) testava `parkRanksInRange`/`settleParkedRanks` sem primeiro afastar o rank da lista que
  seria "movida" (o que `UserListServiceImpl.applyRankChange` sempre faz antes de chamar essas duas
  queries) — o settle tentava assentar em um rank ainda ocupado. Corrigido replicando esse passo no teste.
- **Bug de teste**: `UserListItemRepositoryTest.shouldCountContentAndNestedListItemsTogetherWhenSeveralListsAreRequested`
  usava `save()` em vez de `saveAndFlush()` no último item antes do `entityManager.clear()`, descartando
  o insert ainda não sincronizado. Corrigido trocando para `saveAndFlush()`.
- Também corrigidas várias colisões de `tmdbId` (`"550"`/`"680"`) entre `Content`s criados em testes
  novos desta sessão em `DiaryEntryRepositoryTest` e os mesmos `tmdbId`s já reservados pelo
  `fightClub`/`pulpFiction` do `setUp()` da classe — violavam `uq_contents_tmdb_id_type`. Renomeados
  pra `tmdbId`s únicos (`"9001"`/`"9002"`).

Todos os 1748 testes (unitários + repositório + integração, incluindo Testcontainers) passam depois
das correções.

## ✅ 2026-08-28 — Perfil: banner e contagem de seguidores/seguindo

Últimas duas lacunas remanescentes de `telas.md` (seção Perfil), fechando por completo o levantamento
de gaps contra as telas do frontend iniciado nos dias anteriores.

`User` ganhou `banner` (`String`, nullable, migration `V32`, mesmo formato `@Size(max=2048) @URL` de
`profilePicture`, mas sem valor default — diferente de `profilePicture`, que sempre tem uma imagem
padrão, um usuário sem banner definido simplesmente não tem um). Aceito em `POST /auth/register` e
`PATCH /users/me` (`PostUserDTO`/`PatchUserDTO`, ambos com o campo acrescentado ao final via construtor
secundário de aridade antiga, evitando reescrever os call sites posicionais já existentes),
exposto em `UserResponseDTO`/`PublicUserDTO`/`PublicUserProfileDTO`, mapeado automaticamente por nome
pelo MapStruct (sem mudança nos métodos do `UserMapper` além da assinatura).

`followersCount`/`followingCount` passaram a vir prontos em `UserResponseDTO`/`PublicUserProfileDTO`
— antes o cliente precisava de duas chamadas extras (`GET /followers`, `GET /following`, usando
`totalElements`) só pra montar o cabeçalho do perfil. Duas novas queries `count` em
`FollowerRepository` (`countByFollowedIdAndStatus`/`countByFollowerIdAndStatus`, derivadas pelo Spring
Data, contando só `Follower` com `status = ACCEPTED` — pedidos `PENDING` não entram), calculadas a cada
request, não desnormalizadas (mesma escolha já usada em `commentsCount` de `UserList`). `PublicUserDTO`
(listas paginadas de seguidores/seguindo/busca) continua sem esses campos, só `PublicUserProfileDTO`
(item único) — evita uma consulta de agregação por item de página, mesmo padrão já usado pro
watch-time stats. `UserServiceImpl.computeWatchStats`/`WatchStats` renomeados para
`computeProfileStats`/`ProfileStats` já que agora computam mais do que tempo assistido.

`openapi.yaml` (`banner` em `UserResponseDTO`/`PublicUserDTO`/`PatchUserDTO`/registro; novo schema
reusável `FollowCounts`, combinado via `allOf` em `UserResponseDTO`/`PublicUserProfileDTO`, mesmo
padrão de `WatchTimeStats`), `database-schema.html` (`banner` na entidade `USUARIO`),
`business-rules.md`/`-summary.md` e `docs/context/telas.md` (as duas últimas lacunas ⚠️ do Perfil,
e as referências cruzadas "mesmas lacunas do Perfil acima" nas demais telas, marcadas como resolvidas)
atualizados junto. Testes novos: 5 em `UserServiceImplTest` (patch de banner com/sem mudança,
propagação de followersCount/followingCount ao mapper), 2 em `UserMapperTest` (mapeamento de banner e
dos dois contadores), 4 em `FollowerRepositoryTest` (as duas novas queries de contagem), 2 em
`UserControllerIntegrationTest` (patch de banner e contadores via `GET /users/me` fim a fim). Suíte
completa (1755 testes, incluindo Testcontainers — Docker seguiu disponível) passando.

## ✅ 2026-08-28 — Month/Year in Review, All Time Stats e grade de notas por episódio

Últimas telas novas de `telas.md` ("novas telas 1"): quatro endpoints de estatística agregada sobre o
diário do usuário, estilo Trakt/SeriesGraph. Os itens de "5 ator/atriz/diretor mais assistidos" saíram
do `.md` — elenco/direção nunca é salvo no banco (`Content` só guarda `tmdbId`+`type`), fora de escopo
permanente. O gráfico de "horário do dia" também saiu — exigiria um campo novo de hora em `DiaryEntry`,
descartado por enquanto (ver decisão abaixo).

Duas novas exceções à imutabilidade de `Content` (5ª e 6ª, depois de `isSeasonFinale`/`isSeriesFinale`/
`runtimeMinutes`/`genres`): `releaseYear` (`Integer`) e `countries` (`text[]`, códigos ISO 3166-1
alpha-2), ambos client-supplied, opcionais, só aceitos em `MOVIE`/`SERIES` (mesma restrição de `genres`,
`EPISODE` resolve pelo `Content` tipo `SERIES` do mesmo `seriesTmdbId`), mesmo padrão de conflito `409`
em reenvio divergente dos outros 4 campos — decisão tomada explicitamente contra update-on-mismatch:
`Content` é um recurso compartilhado entre todos os usuários sem verificação server-side contra a TMDB,
então aceitar correção de "quem chamou por último" deixaria qualquer usuário autenticado sobrescrever
silenciosamente dado já correto pra todo mundo. `ContentServiceImpl.normalizeCountries` normaliza
(trim + maiúsculo + ordenação) antes de salvar/comparar, mesmo motivo de `normalizeGenres`. Migration
`V33` (coluna `release_year INTEGER`, não `SMALLINT` — usada inicialmente e corrigida depois de a
suíte completa expor um `SchemaManagementException` sob `ddl-auto=validate`, já que `Integer` do Java
mapeia pra `integer`, não `smallint`, por padrão).

`DiaryEntryRepository` ganhou ~25 métodos de agregação novos, generalizando os padrões já existentes
(`sumRuntimeMinutesByUserIdAndContentTypeAndWatchedDateBetween`, `countByUserIdAndContentTypeGroupByScore`,
`countDistinctTitlesByGenreAndUserIdForMovies/ForSeries`) pra range arbitrário de datas e pra métricas
que não existiam: contagem/soma no período, primeiro/último assistido, tempo por dia, dia da semana e
mês (native SQL com `EXTRACT(ISODOW/MONTH/YEAR FROM watched_date)`, dia da semana em ISO 8601),
melhor/pior avaliado (JPQL ordenado por `score`, tipo `MOVIE`/`SERIES` direto — nível de título, não de
episódio, diferente da granularidade `MOVIE`/`EPISODE` usada pro resto das agregações de atividade),
top séries por tempo assistido, top filmes mais longos (`SELECT DISTINCT` + `ORDER BY runtime_minutes`),
conteúdo mais logado, década e país (mesmo padrão de `unnest` + `LEFT JOIN` pro `Content` tipo `SERIES`
já usado em `countDistinctTitlesByGenreAndUserId`, agora sobre `release_year`/`countries`).

`SummaryService`/`SummaryServiceImpl` ganharam 4 métodos novos reaproveitando `assertCanViewSummary`
(mesma checagem de visibilidade de `getSummary`, duplicada por serviço nesse projeto — não existe
utilitário compartilhado): `getMonthInReview`/`getYearInReview` (escopados por `type`, mesma derivação
MOVIE→MOVIE/SERIES→EPISODE de `getSummary` pra tempo assistido, mas `type` direto pra ranking de
melhor/pior avaliado; campos exclusivos de aba — `topSeriesByWatchTime`/`topLongestMovies` — ficam
vazios fora da aba correspondente), `getAllTimeStats` (sem `type`, combina MOVIE+SERIES — contagens
que fazem sentido por tipo vêm em pares de campos, rankings que fazem sentido misturados combinam num
só top 10) e `getEpisodeRatingsGrid` (grade temporada×episódio; em rewatch, usa a nota do maior
`watchNumber`). Promoção do Top5 em `topRated`/`bottomRated` implementada como reordenação estável do
conjunto já buscado (quem está no Top5 do usuário sobe pro topo, mantendo a ordem por nota dentro de
cada grupo) — não força membro do Top5 a aparecer se ele não estivesse já entre os mais bem/mal
avaliados retornados pela query.

Endpoints novos, todos em `SummaryController`, tag `Users` no `openapi.yaml` (sem tag `Summary`
própria, seguindo a convenção já usada no arquivo de taguear por recurso, não por feature):
`GET /users/{userId}/summary/month?type=&month=YYYY-MM`,
`GET /users/{userId}/summary/year?type=&year=`, `GET /users/{userId}/summary/all-time` (sem `type`),
`GET /users/{userId}/series/{seriesTmdbId}/episode-ratings`.

`CLAUDE.md` (§ Avoid, contagem de exceções atualizada de 4 pra 6, com as duas novas descritas),
`business-rules.md`/`-summary.md` (nova entrada pras duas exceções) e `docs/context/telas.md`
(todos os itens de Month/Year in Review, All Time Stats e grade de notas marcados ✅) atualizados
junto. Testes novos: 6 em `ContentServiceImplTest` (validação de tipo e conflito `409` pra
`releaseYear`/`countries`), ~15 em `SummaryServiceImplTest` (NotFound/Forbidden/BadRequest por método,
promoção do Top5, populações condicionais por aba), 6 em `SummaryControllerTest` (delegação e parsing
de `month`), 12 em `DiaryEntryRepositoryTest` rodando contra Postgres real via Testcontainers
(numeração ISO do dia da semana, unnest de gênero/país, bucket de década, ordenação de melhor
avaliado/mais longo, soma de runtime por série). Suíte completa (1798 testes, incluindo
Testcontainers) passando.

## ✅ 2026-08-28 — Estatísticas agregadas por Content, reviews por Content e resumo da Home

Levantamento de gaps contra as telas novas de `telas.md` (Home, Filme/Série, Temporada, Episódio)
apontou três lacunas reais sem endpoint equivalente hoje — nenhuma exige entidade nova, mesmo espírito
de agregação pura de `Resumo`/`series-in-progress`/`liked-lists`.

`GET /contents/{contentId}/stats` e `GET /contents/stats` (batch, até 100 ids): primeira agregação
cruzando `DiaryEntry`/`Comment` de **todos** os usuários por `contentId`, não mais escopada por um
único usuário como todo o resto de `summary`/`diary`. `ContentStatsService`/`ContentStatsServiceImpl`
(pacote `content`, novo) somam `averageScore`/`playsCount`/`reviewsCount` só de `DiaryEntry` de
usuários com perfil público (`DiaryEntryRepository.findContentStatsByContentIdIn`) — decisão de
privacidade: uma métrica agregada e anônima não tem um "dono" único pra checar segue-aceito contra o
viewer, então a saída conservadora é excluir perfil privado inteiramente, em vez de deixar sua
contribuição vazar num número. `commentsCount` (`CommentRepository.countByContentId(In)`, mesmo padrão
de `countByListId(In)` já existente) não sofre esse filtro. Um `contentId` sem nenhuma interação
devolve tudo zerado/`null`, nunca `404` — mesmo idempotente-por-ausência já usado em outras leituras
agregadas do projeto; o batch devolve um item por id pedido, na mesma ordem, com o mesmo default.

`GET /contents/{contentId}/reviews`, paginado: `DiaryEntry` com `comment` preenchido de todos os
usuários escopada por `contentId` — diferente de `hasReview=true` em `/diary`, que é por usuário.
Reaproveita o DTO/mapper de `DiaryEntry` já existentes; a visibilidade é filtrada linha a linha
**na própria query** (`DiaryEntryRepository.findReviewsByContentId`, `EXISTS` contra `Follower`, mesmo
motivo de `UserList.findByUserIdAndVisibilityIn` — filtrar em memória depois de paginar mentiria a
contagem da página), reaproveitando a mesma regra padrão perfil público/dono/segue-aceito. `404` se o
`contentId` não existir, ao contrário de `/stats`.

`GET /users/{userId}/summary/home`: novo método em `SummaryService`/`SummaryServiceImpl`, mesmo padrão
de `getMonthInReview`/`getAllTimeStats`. A maior parte reaproveita queries já existentes sem nenhuma
nova (totais all-time, `nextEpisodes` via `series-in-progress` com `size=6`, os dois `genreCounts` via
as mesmas queries do Month in Review só trocando o range de data). Duas peças novas: uma query de
contagem por dia (`countByUserIdAndWatchedDateBetween`, `COUNT` agrupado por `watchedDate`, MOVIE+
EPISODE numa query só) pro gráfico dos últimos 30 dias **corridos** (janela rolante a partir de hoje,
não mês calendário — diferente de todo o resto de `summary`), e `recentlyWatched` (merge em memória de
MOVIE+EPISODE por `createdAt` desc, cortado em 4, mesmo padrão de `recentActivity` mas sem o lado
`DroppedEntry`). O preview de calendário de 7 dias da tela ficou de fora — depende de data de
lançamento (TMDB), que `Content` nunca guarda; o cliente cruza `nextEpisodes` + watchlist com o TMDB.

Também corrigido um erro de especificação em `telas.md`: o bullet "marca como visto (bulk)" da tela
Episódio era copy-paste de Temporada/Filme-Série (um episódio isolado não tem o que mandar em bulk) —
removido, sem endpoint novo associado; `POST /diary` de sempre já cobre marcar um episódio como visto.

`openapi.yaml` (3 endpoints + schemas `ContentStats`/`HomeSummary`), `CLAUDE.md` (§ Endpoint groups),
`development-stages.md` (Fase 8, mesmo parágrafo que já lista as adições retroativas do tipo),
`business-rules.md`/`-summary.md` (privacidade das stats, visibilidade linha-a-linha das reviews,
janela rolante da Home) e `telas.md` (gaps `🆕` viram `✅`) atualizados junto. Testes novos: 5 em
`ContentStatsServiceImplTest`, 4 em `DiaryEntryServiceImplTest` (reviews), 6 em `SummaryServiceImplTest`
(Home), mais os controllers (mock) e integração (Testcontainers) correspondentes — 401/404/400/visibilidade
por perfil privado cobertos nos três grupos. Suíte completa (1830 testes) passando.

## ✅ 2026-08-29 — Feed de atividades (GET /feed)

Levantamento de uma tela nova ("Social", estilo Twitter) em `telas.md`: timeline com as "atualizações"
de quem o usuário segue — assistiu/completou algo, abandonou um filme/série, ou trocou o Top5. Discutido
em conversa antes de implementar (decisões registradas em `business-rules.md` § Feed) e sem entidade
nova, mesmo espírito de `recentActivity`/`series-in-progress`/`liked-lists`: `FeedService`/
`FeedServiceImpl` (pacote `feed`, novo) mesclam `DiaryEntry`+`DroppedEntry`+`Top5Entry` de quem o
usuário segue (`Follower` status `ACCEPTED`) em tempo de leitura — pull, não fan-out no write, decisão
justificada pela escala do app (sem tabela nova, sem escrita duplicada, sem lógica extra pra
follow/unfollow retroativo).

Duas decisões de arquitetura que divergem do resto do projeto, ambas documentadas: paginação por
cursor/keyset (`CursorPageResponseDTO`, schema `CursorPageMeta` em `openapi.yaml`) em vez de página
numerada — offset seria incorreto num feed com inserts constantes, não só menos otimizado, já que
duplicaria ou pularia item ao rolar; e o evento de Top5 é genérico ("atualizou o Top 5 de {type}"), sem
detalhar qual item entrou/saiu, depois de confirmar em `Top5EntryServiceImpl` que shift de posição nunca
toca `updatedAt` (só `insertEntry`/`removeEntry` existem), então `createdAt` sozinho já é sinal limpo de
post sem risco de falso positivo. Cursor é `base64(createdAt|id)`, com desempate por `id` sempre da
própria tabela de cada fonte (nunca cruzado entre `DiaryEntry`/`DroppedEntry`/`Top5Entry`); `hasNext` é
resolvido buscando `size+1` por fonte em vez de `COUNT`. Curtida/comentário só existe hoje pra eventos
`DIARY_ENTRY` (reaproveita `POST /diary/{id}/like` e `/diary/{id}/comments` via `FeedItemDTO.id`, sem
código novo) — `DROPPED`/`TOP5_UPDATE` ainda não têm alvo de `Like`/`Comment` válido, gap conhecido e
deixado em aberto (`Like`/`Comment` continuam restritos a `Content`/`UserList`/`DiaryEntry`).

`openapi.yaml` (`GET /feed`, schemas `CursorPageMeta`/`FeedItem`), `development-stages.md` (Fase 8,
mesmo parágrafo que já lista as adições retroativas do tipo), `business-rules.md`/`-summary.md` (nova
seção Feed) e `telas.md` (seção Social atualizada com as decisões) atualizados junto. Testes novos: 14
em `FeedServiceImplTest` (merge/ordenação das três fontes, mapeamento por `eventType`, validação de
`size`/cursor, cálculo de `hasNext`), 2 em `FeedControllerTest`, 5 em `FeedControllerIntegrationTest`
(Testcontainers — merge real, isolamento por seguidor, `400`/`401`). Suíte completa (1851 testes)
passando.

## 2026-08-29 (2) — Apagar todo o histórico de diário de uma série (DELETE /diary/series/{seriesTmdbId})

Pergunta em conversa: `DELETE /diary/{id}` só limpa a passada (`watchNumber`) da entrada apagada
(comportamento correto e intencional, corrigido em 2026-08-24) — não existia forma de apagar **todas**
as passadas de uma série de uma vez (todos os rewatches). Endpoint novo, direto e explícito, sem
cascata: `DiaryEntryServiceImpl.deleteAllDiaryEntriesForSeries` busca toda `DiaryEntry`
`EPISODE`/`SEASON`/`SERIES` do usuário para o `seriesTmdbId` informado, sem filtrar `watchNumber` (ao
contrário de `wipeSeriesHistory`) e sem a proteção de `autoGenerated`/`overrideProtectedEntries` que
`deleteDiaryEntry` aplica em cascata — a distinção é a mesma já usada lá: o alvo direto de um delete
sempre é removido incondicionalmente, só o efeito colateral sobre entradas que o usuário não pediu
diretamente é que é protegido. Idempotente: sem nenhuma entrada pra aquela série, devolve `204` sem
chamar `deleteAll`. Reaproveita o bucket de rate limit de `POST /diary/bulk`
(`diaryBulkActionKey`/`diaryBulkActionMaxRequests`/`diaryBulkActionWindowMinutes`), não o de
`DELETE /diary/{id}`, por ter um raio de impacto parecido (mexe em várias linhas de uma vez).

Duas queries novas em `DiaryEntryRepository` (`findAllSeasonEntriesInSeries`/
`findAllSeriesEntriesInSeries`, mesmo filtro das já existentes `*ByWatchNumber` mas sem a cláusula de
`watchNumber`); o lado de episódio reaproveita `findEpisodeEntriesBySeriesForUser`, que já não tinha
esse filtro. `openapi.yaml` (`DELETE /diary/series/{seriesTmdbId}`), `CLAUDE.md` (§ Endpoint groups) e
`business-rules.md`/`-summary.md` (nova entrada em DiaryEntry) atualizados junto. Testes novos: 3 em
`DiaryEntryServiceImplTest` (mescla das três fontes, inclui entradas manuais, no-op sem entradas), 3 em
`DiaryEntryControllerTest` (204, delegação, ordem do rate limit), 5 em
`DiaryEntryControllerIntegrationTest` (Testcontainers — remove todas as passadas incluindo rewatch,
não toca outra série, no-op idempotente, `401`/`403`). Suíte completa (1862 testes) passando.

## 2026-08-29 (3) — Filtrar posts mecânicos de bulk log do feed (DiaryEntry.ignore)

Problema levantado em conversa: marcar uma temporada/série inteira como assistida via
`POST /diary/bulk` cria uma `DiaryEntry` por episódio — sem nenhum filtro, `GET /feed` mostraria um
post por episódio pra quem segue o usuário, inundando o feed por uma única ação. Design discutido e
refinado em conversa antes de implementar (registrado em `business-rules.md` § DiaryEntry): coluna
booleana nova `ignore` em `diary_entries` (migration `V34`), ortogonal a `autoGenerated` (que protege
contra deleção em cascata, não visibilidade de feed). Cada chamada de criação (`POST /diary` ou
`POST /diary/bulk`) tem um `requestedType` — o `content.type()` pedido no topo daquela chamada — que
`DiaryEntryServiceImpl` agora propaga por `persistDiaryEntry`/`triggerCompletionCascade`/
`maybeCompleteSeason`/`maybeCompleteSeries`/`persistAutoGeneratedEntry`/`bulkLogEpisode`/
`bulkLogSeason`/`bulkLogSeries`. Numa hierarquia EPISODE(0) < SEASON(1) < SERIES(2), uma entrada
recebe `ignore = true` sse seu nível é menor que o de `requestedType`; `MOVIE` fica fora da hierarquia
(sempre `ignore = false`). Resultado prático: um `POST /diary` individual nunca gera `ignore = true`
em nada; um bulk de temporada marca os episódios como `ignore = true` mas a própria temporada (o
nível pedido) como `ignore = false`; um bulk de série marca episódios **e** temporadas intermediárias
como `ignore = true`, só a série final fica visível. `PATCH /diary/{id}` zera `ignore` junto com
`autoGenerated` — editar uma entrada bulk-child é o usuário assumindo aquele registro como deliberado.

`ignore` só afeta `GET /feed` (`DiaryEntryRepository.findFeedCandidates`, filtro `d.ignore = false`) —
todo outro endpoint que lista `DiaryEntry` continua mostrando tudo, já que é uma preocupação de spam
de timeline social, não de histórico pessoal. `recentActivity`/`recentlyWatched` do Summary não
precisaram de mudança: só consultam nível `MOVIE`/`SERIES`, e `SERIES` nunca fica `ignore = true` por
ser o topo da hierarquia. `openapi.yaml` (campo `DiaryEntry.ignore`, nota em `GET /feed`),
`business-rules.md`/`-summary.md` (nova entrada em DiaryEntry e em Feed) atualizados junto. Testes
novos/estendidos em `DiaryEntryServiceImplTest` (bulk de temporada ignora episódios mas não a
temporada; bulk de série ignora episódios e temporada intermediária mas não a série; cascata a partir
de uma temporada pedida diretamente não ignora a série completada; `PATCH` zera `ignore`) e em
`FeedControllerIntegrationTest` (entrada `ignore = true` não aparece no feed, via Postgres real).
Suíte completa (1866 testes) passando.

## 2026-08-29 (4) — "Assistido com" (WatchCompanion)

Feature nova pedida em conversa, fora do escopo original de `openapi.yaml`/`database-schema.html`/
`development-stages.md`: marcar pessoas com quem o usuário assistiu um filme/temporada/série/episódio
junto. Passou por brainstorming completo antes de implementar — decisões confirmadas com o usuário:
marcação por passada (`DiaryEntry`/`watchNumber`), não por usuário+conteúdo; sem fluxo de aceite/recusa
da pessoa marcada (sem notificação, sem visibilidade reversa no perfil dela); editável via
`PATCH /diary/{id}`; e o mecanismo de completude por unanimidade descrito abaixo.

Entidade nova `WatchCompanion` (tabela `watch_companions`, migration `V35`, FK pra `diary_entries` com
`ON DELETE CASCADE` e pra `users`, único em `diary_entry_id`+`user_id`) — sem controller/endpoint
próprio, lida e escrita só através de `POST /diary`, `POST /diary/bulk` e `PATCH /diary/{id}`
(campo `watchedWith`, lista de ids). `DiaryEntryServiceImpl.validateCompanions` rejeita com `400`
marcar a si mesmo ou marcar alguém que o dono não segue (`Follower` status `ACCEPTED`); ids duplicados
são deduplicados silenciosamente.

O mecanismo mais delicado é como a temporada/série autogerada por completude herda (ou não) um
companion: `computeUnanimousCompanions` olha só os filhos diretos daquele nível (episódios pra
temporada, temporadas pra série — nunca os episódios crus de todas as temporadas de uma vez) e só
herda quando todos concordam exatamente no mesmo conjunto de pessoas; qualquer divergência (inclusive
um filho sem marcação) deixa a entrada sem companion. Esse único mecanismo cobre tanto bulk (todo
episódio nasce com a mesma lista, unanimidade trivial) quanto passada orgânica episódio-a-episódio
(só herda se o usuário realmente assistiu tudo com a mesma pessoa) — calculado uma vez, no momento da
completude, nunca revisitado depois, mesmo padrão já usado por `autoGenerated`/`ignore`. A gravação do
companion roda dentro da mesma transação `REQUIRES_NEW` da entrada autogerada, mesmo motivo já
documentado pra `getOrCreateReference`/`followPerson`. `PATCH /diary/{id}` substitui a lista inteira
(delete-then-insert, não merge) — `null` não mexe, qualquer lista (mesmo vazia) apaga tudo. `GET /feed`
ganhou `FeedItem.watchedWith`, reaproveitando o mesmo batch load já usado em `getDiaryEntries`, sem
query nova, só populado pra `eventType=DIARY_ENTRY`.

Nova query em `DiaryEntryRepository` (`findEpisodeEntriesInSeasonByWatchNumber`, escopada por
temporada, diferente da já existente `findEpisodeEntriesInSeriesByWatchNumber` que é série inteira);
`findSeasonEntriesInSeriesByWatchNumber` (já existente) foi reaproveitada pro lado de série.
`DiaryEntryMapper` ganhou uma sobrecarga de 3 argumentos (a de 2 continua existindo, usada só por
`SummaryServiceImpl`, que ficou fora do escopo desta rodada — `recentActivity`/`recentlyWatched` não
mostram `watchedWith`). `openapi.yaml` (campo `watchedWith` em `DiaryEntryCreation`/
`DiaryEntryBulkCreation`/`DiaryEntryUpdate`/`DiaryEntry`/`FeedItem`), `business-rules.md`/`-summary.md`
(nova entrada em DiaryEntry e em Feed) atualizados junto. Testes novos: 8 em `DiaryEntryServiceImplTest`
(rejeição de auto-marcação e de não-seguido, gravação do companion, unanimidade positiva e negativa na
completude, `PATCH` não mexe/substitui/limpa), 5 em `DiaryEntryControllerIntegrationTest` (companion
aparece na resposta, `400` nos dois casos de validação, bulk aplica a mesma lista a episódio e
temporada completada, `PATCH` substitui) e 1 em `FeedControllerIntegrationTest` (companion aparece no
feed), todos via Postgres real. Suíte completa (1880 testes) passando.

## 2026-08-29 (5) — Poster customizado no Top 5 e em itens de lista

Estendido o `customPosterUrl` que já existia só em `DiaryEntry` pras outras duas telas onde o usuário
escolhe um conteúdo: Top 5 e listas. `Top5Entry` ganhou o campo no `POST /users/me/top5/{type}`
(existente) e um `PATCH /users/me/top5/{type}/{top5EntryId}` novo — antes desse endpoint, Top5Entry só
tinha inserir/remover, nenhuma operação de update. `UserListItem` ganhou o campo em
`POST /lists/{listId}/items` e `PATCH /lists/{listId}/items/{itemId}`, restrito a item de `content`:
informar `customPosterUrl` junto com `childListId` (criação) ou contra um item de lista aninhada
(patch) devolve `400`, já que uma lista aninhada não tem poster próprio. Mesma validação em todo lugar
(`@Size(max = 2048) @URL`, nullable), migration `V36` adicionando a coluna `custom_poster_url` em
`top5_entries` e `user_list_items`. `openapi.yaml`, `database-schema.html`, `business-rules.md`/
`-summary.md` atualizados junto.

## 2026-08-29 (6) — Ordenar lista por nota média de episódios (sortBy=episodeAvgRating)

Pedido em conversa: ordenar `GET /lists/{listId}` pela nota média que o dono da lista deu aos
episódios de uma série, não a de quem está vendo a lista. Novo valor `episodeAvgRating` em `sortBy`,
ao lado de `position`/`dateAdded`/`duration` já existentes. Calculado em memória a partir de
`DiaryEntry.score` do dono (`UserListItem` não tem nota própria — nunca foi armazenado): item
`SERIES` usa a média de todo `DiaryEntry` tipo `EPISODE` do dono com o mesmo `seriesTmdbId`; item
`SEASON` restringe também por `seasonNumber`; item `EPISODE` usa a média das notas daquele episódio
exato (cobre rewatch). Item `MOVIE` e item de lista aninhada não têm esse valor. Nova query
`DiaryEntryRepository.findScoredEpisodeEntriesByUserIdAndSeriesTmdbIdIn` busca de uma vez só todos os
`DiaryEntry` de episódio relevantes (filtrando `score IS NOT NULL`), agrupados em Java por série/
temporada/episódio em `UserListServiceImpl.computeEpisodeAverageRatings` — uma query só, não uma por
item da lista. Item sem nenhuma nota do dono sempre ordena por último, tanto em `asc` quanto em
`desc` — cuidado tomado no comparator porque um simples `.reversed()` sobre `Comparator.nullsLast`
inverteria também a posição dos nulos, jogando-os pro início em `desc`; a direção só é aplicada entre
os itens que de fato têm nota. `openapi.yaml`, `business-rules.md`/`-summary.md` atualizados junto.
Testes novos: 4 em `UserListServiceImplTest` (ordena por rating desc, itens sem nota sempre por
último em asc e desc, usa só a nota do dono e não a do viewer) e 2 em
`UserListControllerIntegrationTest` (ordenação end-to-end via Postgres real com séries/temporada/
filme misturados, e `400` pra sortBy desconhecido), todos passando.

## 2026-08-29 (7) — Notificações e rastreamento de mudanças no TMDB (Fase 7)

Primeira integração do backend com a API do TMDB propriamente dita (até aqui só o frontend consumia o
TMDB direto; o backend nunca tinha feito uma chamada HTTP pra fora). `common/tmdb/TmdbClient` (config
em `TmdbClientConfig`, propriedades `app.tmdb.base-url`/`app.tmdb.api-key`/`app.tmdb.timeout-ms`) expõe
`getMovieDetails`/`getTvDetails`/`getPersonCombinedCredits`, retornando `Optional` vazio em vez de
lançar quando o TMDB responde 404 ou a chamada falha, via DTOs `TmdbMovieDetails`/`TmdbTvDetails`/
`TmdbNextEpisode`/`TmdbCredit`/`TmdbPersonCredits`.

Três tabelas de cache internas, nunca expostas por nenhum endpoint —
`notification.entity.TrackedContentState` (último status/data de lançamento/próximo episódio
conhecidos de um `Content` MOVIE/SERIES rastreado), `TrackedPersonState` (última checagem de uma
pessoa TMDB seguida) e `TrackedPersonCredit` (créditos TMDB já vistos daquela pessoa) — migrações V38/
V39. `Notification` (entidade + `NotificationType` enum: `RELEASE`, `ANNOUNCED_DATE`, `CANCELLED`,
`RENEWED`, `NEW_EPISODE`, `FOLLOWED_PERSON_NEW_CREDIT`) ganhou sua própria tabela (V40), com
`personTmdbId` nullable (só preenchido em `FOLLOWED_PERSON_NEW_CREDIT`). `GET /notifications`
(filtro opcional `isRead`, paginado no envelope padrão do projeto) e `PATCH
/notifications/{notificationId}/read` (204, com checagem de dono — 403 se a notificação é de outro
usuário, 404 se não existe) em `NotificationController`/`NotificationServiceImpl`.

`notification.tracking.ContentChangeDetector` é lógica pura de diff (`detectMovieChange`/
`detectTvChange`) comparando o `TrackedContentState` anterior contra a resposta fresca do TMDB:
`ANNOUNCED_DATE` (data futura nova), `RELEASE` (data conhecida já chegou), `CANCELLED` (status virou
`Canceled`), `RENEWED` (heurística — `Ended`/`Canceled` virando `Returning Series`, já que o TMDB não
tem status "renovada" literal) e `NEW_EPISODE` (próximo episódio conhecido já chegou). Dois jobs
`@Scheduled` novos: `ContentTrackingJob` (diário, `app.content-tracking.cron`, `0 0 4 * * *` em dev)
mescla conteúdo rastreado via `WatchlistEntry` + série em andamento no diário antes de chamar o TMDB,
uma chamada por título distinto por execução, e notifica quem tem o título na watchlist (ou, pra
`NEW_EPISODE`, quem está assistindo a série no diário); `FollowedPersonTrackingJob` (semanal,
`app.followed-person-tracking.cron`, segunda às 5h em dev) busca créditos combinados de cada pessoa
seguida via `TmdbClient.getPersonCombinedCredits`, resolve a referência de `Content` via
`ContentRepository.getReferenceById` e notifica quem segue aquela pessoa. Cada título/pessoa roda numa
transação `NewTransactionExecutor.runInNewTransaction` isolada mais um catch-and-continue por cima,
pra uma falha isolada (TMDB fora do ar, erro de rede) não abortar o resto da execução agendada.
Limitação aceita e documentada em `business-rules.md`: os `save` das três tabelas de cache não seguem
o padrão catch-and-requery de `DataIntegrityViolationException` apesar das constraints únicas — decisão
deliberada pra aplicação de instância única sem escalonamento horizontal planejado.

`openapi.yaml`, `database-schema.html`, `business-rules.md`/`-summary.md` e `CLAUDE.md` atualizados
junto. Testes novos: 16 em `NotificationServiceImplTest`, 3 em `NotificationControllerTest`, 9 em
`NotificationControllerIntegrationTest`, 10 em `ContentChangeDetectorTest`, 5 em
`ContentTrackingJobTest`, 5 em `FollowedPersonTrackingJobTest`, além de testes de repositório para
`NotificationRepository`/`TrackedContentStateRepository`/`TrackedPersonStateRepository` e de
`TmdbClientTest`, todos passando.

## 2026-08-30 — Excluir séries abandonadas de "série em andamento"

`DiaryEntryRepository.findSeriesInProgressByUserId` (`GET /users/{userId}/series-in-progress`, e
`nextEpisodes` da Home que a reaproveita) excluía do resultado só séries já concluídas (`DiaryEntry`
tipo `SERIES`), sem excluir séries que o usuário abandonou (`DroppedEntry` tipo `SERIES`) — uma série
dropped com episódios assistidos antes do drop continuava aparecendo como "em andamento". Adicionado
um segundo `NOT EXISTS` na query nativa (principal e `countQuery`), join `dropped_entries` →
`contents` pra resolver `tmdb_id` a partir de `content_id`, mesmo padrão do `NOT EXISTS` de conclusão
já existente. `business-rules.md`/`-summary.md` atualizados junto. Novo teste de repositório
`shouldExcludeSeriesWhenUserAlreadyDroppedIt` em `DiaryEntryRepositoryTest`.

## 2026-08-30 (2) — Proxy de detalhe do TMDB (`GET /contents/{contentId}/details`, `GET /contents/details`)

Até aqui só o frontend consumia o TMDB diretamente pra montar as telas de `telas.md` (título, pôster,
elenco, watch providers etc. de um `Content`), o que exigiria embutir a `api-key` do TMDB no cliente.
Passou a ser o backend quem resolve, cacheia e devolve — cliente nunca mais chama o TMDB direto pra
isso. `TmdbClient` ganhou 4 métodos novos (`getMovieFullDetails`/`getTvFullDetails`/
`getSeasonFullDetails`/`getEpisodeFullDetails`), cada um 1 única chamada HTTP usando
`append_to_response` do TMDB (`credits`/`aggregate_credits`+`watch/providers`+`alternative_titles`
em MOVIE/SERIES, só `watch/providers` em SEASON, nenhum append em EPISODE — o corpo base já traz
`guest_stars`/`crew`) em vez de N chamadas separadas por tela. Cada método é `@Cacheable`
(`TmdbCacheConfig`, Caffeine, `spring-boot-starter-cache` novo no `pom.xml`, TTL
`app.tmdb.details-cache-ttl-hours`), chave `(natural key TMDB, language)` — `region` nunca entra na
chave, já que o TMDB devolve todas as regiões numa resposta só e a filtragem por região acontece em
memória depois da leitura do cache.

`ContentDetailsServiceImpl` (`content/service/impl`) resolve o `Content` pelo `contentId`, resolve
`preferredLanguage`/`preferredRegion` do usuário autenticado (dois campos novos em `User`, migration
`V41`, default `en-US`/`US`, patcháveis só via `PATCH /users/me` — nunca no registro), chama o
`TmdbClient` certo por `type` e monta `ContentDetailsDTO`. `SEASON`/`EPISODE` reaproveitam
gêneros/países/elenco regular/criadores buscando a `SERIES` do mesmo `seriesTmdbId` (também
cacheada, compartilhada entre todas as temporadas/episódios) — mesmo padrão que `Content.genres`/
`countries` já usa pra resolver `EPISODE` via `seriesTmdbId`. Título resolvido nessa ordem
(`resolveMovieTitle`/`resolveTvTitle`, só MOVIE/SERIES): tradução no idioma do usuário → título
alternativo do TMDB (`alternative_titles`, já embutido no mesmo `append_to_response`) cujo país bate
com a região do usuário → título original. TMDB indisponível (`Optional.empty()` do `TmdbClient`
após o retry já existente) vira `TmdbUnavailableException` → `502` via `GlobalExceptionHandler`, nova
entrada dedicada.

Dois endpoints em `ContentController`: `GET /contents/{contentId}/details` (singular) e
`GET /contents/details?ids=` (batch, mesmo limite de 100 e mensagem de `GET /contents/stats`),
mesmo padrão singular+batch já usado por `ContentStatsService`. `openapi.yaml`,
`database-schema.html`, `business-rules.md`/`-summary.md` e `CLAUDE.md` atualizados junto — desenho
completo documentado em `docs/context/tmdb-proxy-design.md` (arquivo de escopo local, não versionado
no git). Testes novos: 5 em `TmdbClientTest` (as 4 chamadas novas via `MockRestServiceServer` mais o
caso de retry), 13 em `ContentDetailsServiceImplTest`, 2 em `ContentControllerTest`, 6 em
`ContentControllerIntegrationTest` (happy path, 404, 502, 401, batch, batch acima do limite), 4 em
`UserServiceImplTest` (patch de `preferredLanguage`/`preferredRegion`, muda/não muda), todos
passando — suíte completa em 2018 testes, `BUILD SUCCESS`.

## 2026-08-30 (3) — Corrigido `unless` quebrado do `@Cacheable` no proxy de TMDB

Achado em teste manual pelo Postman logo depois de implementar o item acima: `GET
/contents/{contentId}/details` de uma série devolvia `500` genérico. O stack trace real (só visível
no log do servidor, por design do `GlobalExceptionHandler`) apontava
`SpelEvaluationException: Method call: Method isEmpty() cannot be found on type
TmdbTvFullDetails` dentro do `unless` do `@Cacheable` de `TmdbClient.getTvFullDetails`. Causa: pra
método que retorna `Optional<T>`, o Spring Cache desembrulha o `Optional` **antes** de avaliar
`key`/`unless`/`condition` — `#result` no SpEL já é o `T` puro (ou `null` na falha), nunca o
`Optional` em si; `unless = "#result.isEmpty()"` (escrito nos 4 métodos novos) sempre quebrava,
menos quando o resultado real acabava não caindo nesse branch por acaso. Trocado pra
`unless = "#result == null"` nos 4 métodos.

Nenhum teste unitário pegou isso porque `@Cacheable` só é tecido via proxy AOP com contexto Spring
de verdade — `TmdbClientTest`/`ContentDetailsServiceImplTest` chamam `new TmdbClient(...)`/mocks
direto (sem contexto Spring) e `ContentControllerIntegrationTest` substitui o `TmdbClient` inteiro
via `@MockitoBean`, então nenhum dos três exercitava o interceptor de cache real. Novo
`TmdbClientCachingTest`: contexto Spring mínimo (`@SpringJUnitConfig`) com `TmdbCacheConfig` real,
`TmdbClient` real e `MockRestServiceServer` ligado ao `RestClient.Builder` antes do bean ser
construído (`@DependsOn`), 8 testes (sucesso cacheia + não lança, falha não cacheia + não lança,
pros 4 métodos), cache limpo a cada teste (`CacheManager.getCache(name).clear()`, senão o mesmo
contexto Spring reusado entre métodos vazava cache de um teste pro outro — pego e corrigido antes
do commit). `business-rules.md`/`-summary.md` atualizados junto. Suíte completa: 2026 testes,
`BUILD SUCCESS`.

## 2026-08-30 (4) — `totalRuntimeMinutes` e `recentEpisodes` na tela de Série

Pedido em conversa: a tela de Série de `telas.md` pedia "duração total da série" e "últimos 3
episódios lançados", que o `ContentDetailsDTO` inicial não tinha — só `runtimeMinutes` (média por
episódio, geralmente `null` porque o TMDB não popula `episode_run_time` pra boa parte das séries,
confirmado com uma chamada real ao TMDB pra The Office/2316 durante o debug do item anterior).
Nenhum dos dois dá pra calcular a partir do corpo base de `/tv/{id}` — precisa buscar todas as
temporadas da série (`episodes[].runtime` de cada uma).

`ContentDetailsServiceImpl.fetchAllSeasonsInParallel` busca as N temporadas listadas em
`details.seasons()` via `getSeasonFullDetails` (já cacheado individualmente) em paralelo, usando um
`ExecutorService` dedicado de 8 threads (`TmdbCacheConfig.tmdbSeasonFetchExecutor`) em vez do
`ForkJoinPool.commonPool()` compartilhado — decisão deliberada pra não competir por esse pool com
outro código do app, e pra não deixar a primeira pessoa a abrir uma série de muitas temporadas
esperando N round-trips sequenciais ao TMDB (com paralelismo, o tempo vira só o da temporada mais
lenta). `totalRuntimeMinutes` soma `runtime` de todos os episódios encontrados (`null` se nenhum
tiver runtime conhecido); `recentEpisodes` filtra só episódios com `airDate` já passado, ordena do
mais recente pro mais antigo e corta em 3 — reaproveita as mesmas respostas de temporada já
buscadas pro total, sem chamada TMDB extra. Ambos só preenchidos em `SERIES`.

`ContentDetailsDTO` ganhou os dois campos novos (`totalRuntimeMinutes` depois de `runtimeMinutes`,
`recentEpisodes` no fim); `EpisodeSummaryDTO` ganhou `seasonNumber` (necessário pra distinguir
episódios de temporadas diferentes dentro de `recentEpisodes`, já que ele mistura episódios de
mais de uma temporada — `episodes` de uma única temporada não precisava disso antes, mas passou a
carregar o campo também por consistência). `openapi.yaml` (schema `EpisodeSummary` extraído,
reaproveitado por `episodes` e `recentEpisodes`), `database-schema.html` (nada, sem coluna nova),
`business-rules.md`/`-summary.md` atualizados junto. Testes novos: 3 em
`ContentDetailsServiceImplTest` (soma de runtime entre temporadas, últimos 3 episódios ordenados
cortando o mais antigo, `totalRuntimeMinutes` nulo quando nenhum episódio tem runtime conhecido) —
o teste de série reaproveita um `ExecutorService` real (`Executors.newSingleThreadExecutor`, não
mock, já que `CompletableFuture.supplyAsync` numa `ExecutorService` mockada nunca executaria a
tarefa e o teste travaria esperando o `.join()`). Suíte completa: 2029 testes, `BUILD SUCCESS`.

## 2026-08-31 — Fix: `runtimeMinutes` de série sempre `null` em `/contents/{id}/details`

Reportado pelo usuário: `GET /contents/{id}/details` de uma `SERIES` sempre voltava
`runtimeMinutes: null`. Causa: `buildSeriesDetails` calculava a média a partir de
`TmdbTvFullDetails.episodeRunTime` (`episode_run_time` do `/tv/{id}`), o mesmo campo já registrado
como geralmente vazio no dia anterior (ver entrada de `totalRuntimeMinutes` acima) — só que ali só o
`totalRuntimeMinutes` ganhou um cálculo alternativo a partir dos episódios já buscados, e a média
(`runtimeMinutes`) ficou esquecida usando o campo quebrado.

`ContentDetailsServiceImpl.buildSeriesDetails` agora extrai a lista de runtimes por episódio uma
única vez (`episodeRuntimes(allSeasons)`, mesmos dados já buscados por `fetchAllSeasonsInParallel`
pro `totalRuntimeMinutes`) e passa essa lista tanto pra `totalRuntimeMinutes` (soma) quanto pra
`averageRuntime` (média) — nenhuma chamada TMDB extra. `totalRuntimeMinutes(List<TmdbSeasonFullDetails>)`
virou `totalRuntimeMinutes(List<Integer>)`, reaproveitando o mesmo formato de entrada que
`averageRuntime` já usava. `ContentDetailsServiceImplTest`: teste de soma passou a assertar também a
média (`runtimeMinutes`), e o teste de runtime desconhecido passou a assertar `runtimeMinutes` nulo
junto com `totalRuntimeMinutes` nulo.

## 2026-08-31 (2) — Excluir temporada 0 (Specials) de `recentEpisodes`/`totalRuntimeMinutes`/`runtimeMinutes`

Reportado pelo usuário: `recentEpisodes` de uma série trazia episódios especiais da temporada 0.
Perguntado se o filtro deveria valer só pra `recentEpisodes` ou também pra soma/média de runtime —
usuário escolheu excluir em tudo, já que um episódio bônus normalmente tem duração atípica e
distorceria `totalRuntimeMinutes`/`runtimeMinutes`.

`ContentDetailsServiceImpl.fetchAllSeasonsInParallel` agora filtra `seasonNumber == 0` de
`details.seasons()` antes de disparar as buscas paralelas — a temporada nem chega a ser requisitada
ao TMDB, então `allSeasons` (base de `episodeRuntimes`/`totalRuntimeMinutes`/`runtimeMinutes`/
`recentEpisodes`) nunca inclui especiais. `seasonSummaries` (campo `seasons` da resposta) continua
lendo `details.seasons()` sem esse filtro — a temporada Specials continua listada e acessível via
`/contents/{id}/details` direto, só fica de fora dos agregados calculados a partir de `allSeasons`.
Teste novo em `ContentDetailsServiceImplTest`
(`shouldExcludeSpecialsSeasonZeroFromRecentEpisodesAndRuntimeWhenContentIsASeries`) confirma
`recentEpisodes`/`totalRuntimeMinutes`/`runtimeMinutes` sem a especial e que
`tmdbClient.getSeasonFullDetails` nunca é chamado com `seasonNumber=0`. `business-rules.md`/
`-summary.md` atualizados junto. Suíte de `ContentDetailsServiceImplTest`: 17 testes, sem falhas.

## 2026-08-31 (3) — `numberOfSeasons`/`numberOfEpisodes` em `/contents/{id}/details` de série

Reportado pelo usuário: `episodes: null` numa série o levou a esperar a "quantidade total de
episódios" ali — mas `episodes` só é preenchido em `SEASON` (lista de episódios daquela temporada),
por design (`openapi.yaml`). Perguntado se o certo era um campo novo com a contagem total ou somar
`seasons[].episodeCount` no cliente — usuário escolheu campo novo.

`TmdbTvFullDetails` ganhou `numberOfSeasons`/`numberOfEpisodes` (`number_of_seasons`/
`number_of_episodes` do TMDB, já vêm de graça no corpo base de `/tv/{id}`, sem chamada extra).
`ContentDetailsDTO` ganhou os mesmos dois campos (`Integer`, depois de `totalRuntimeMinutes`), só
preenchidos em `buildSeriesDetails` — `null` em MOVIE/SEASON/EPISODE. Diferente de
`totalRuntimeMinutes`/`runtimeMinutes`/`recentEpisodes` (calculados a partir de `allSeasons`, que
exclui a temporada 0 desde a entrada anterior), esses dois são passthrough puro do TMDB e por isso
*incluem* a temporada 0 se a série tiver especiais — documentado como ressalva explícita em
`openapi.yaml`/`business-rules.md` pra não parecer inconsistência. `ContentDetailsServiceImplTest`:
teste de soma/média de runtime passou a assertar também `numberOfSeasons`/`numberOfEpisodes`.
Atualizados os 7 call sites de `new TmdbTvFullDetails(...)` e os 3 de `new ContentDetailsDTO(...)`
em teste (posicional, sem builder) pros dois campos novos. `openapi.yaml`/`business-rules.md`/
`-summary.md` atualizados junto. Suítes `ContentDetailsServiceImplTest` (17), `ContentControllerTest`
(6), `TmdbClientTest` (10) e `TmdbClientCachingTest` (8): sem falhas.

## 2026-08-31 (4) — `seasons[].airedEpisodeCount`: número certo pra `POST /diary/bulk` não incluir episódio futuro

Reportado pelo usuário ao discutir o fluxo de "marcar série inteira como assistida" (sugerido na
entrada anterior de conversa, usando `seasons[].episodeCount`/`numberOfEpisodes`): esses dois campos
são passthrough cru do TMDB e podem incluir episódio agendado/ainda não exibido numa temporada em
exibição — usá-los pra montar `finaleEpisodeNumber`/`seasonFinaleEpisodeNumbers` do bulk marcaria
episódio futuro como assistido. `POST /diary/bulk` não valida data nenhuma no servidor (mesmo padrão
já documentado de `DiaryEntryUpdate.watchedDate`), então esse número certo precisava vir de algum
lugar.

`ContentDetailsServiceImpl.seasonSummaries` agora recebe também `allSeasons` (mesmos dados de
episódio já buscados pra `totalRuntimeMinutes`/`recentEpisodes`, sem chamada TMDB extra) e calcula
`airedEpisodeCount` por temporada — quantos episódios têm `airDate <= hoje`
(`ContentDetailsServiceImpl.airedEpisodeCount`, usando um `Map<Integer, TmdbSeasonFullDetails>`
indexado por `seasonNumber` pra casar cada `TmdbSeasonSummary` com seus episódios). `null` quando a
busca daquela temporada específica falhou no TMDB (`fetchAllSeasonsInParallel` já descarta season
que falhou silenciosamente) — importante não virar `0` nesse caso, senão o cliente concluiria
"nenhum episódio lançado" quando na verdade é "não sei". `SeasonSummaryDTO` ganhou o campo (depois
de `episodeCount`). Documentado também um efeito colateral já existente e inevitável: usar
`airedEpisodeCount` pra logar até o último episódio já exibido de uma temporada ainda em produção
força esse episódio a virar `isSeasonFinale`/`isSeriesFinale` mesmo a temporada não tendo
terminado de verdade — recuperável depois (a flag só transfere pra frente), mas o cliente não
deveria oferecer "marcar série inteira" sem avisar disso pra séries com status TMDB "em exibição".

Testes novos em `ContentDetailsServiceImplTest`: o teste combinado de agregados de série passou a
assertar `seasons[].episodeCount`/`airedEpisodeCount` lado a lado (temporada com episódio futuro
mostrando os dois valores divergindo); teste novo
`shouldReturnNullAiredEpisodeCountWhenThatSeasonsFullDetailsFailedToFetch` cobre a falha pontual de
uma temporada. `openapi.yaml` (descrição de `episodeCount` avisando pra não usar em bulk,
`airedEpisodeCount` novo, nota em `DiaryEntryBulkCreation` sobre qual campo usar)/`business-rules.md`/
`-summary.md` atualizados junto. Suíte `ContentDetailsServiceImplTest`: 18 testes, sem falhas.

## 2026-08-31 (5) — `CastMemberDTO.id`/`episodeCount`: quantos episódios cada ator aparece

Pedido do usuário: mostrar quantos episódios um ator/personagem aparece — total na tela de Série,
só da temporada na tela de Temporada — e incluir o `id` (TMDB person id) de cada ator no JSON.

Primeira tentativa (descartada): buscar os créditos de cada episódio individualmente
(`/tv/{id}/season/{sn}/episode/{en}/credits`) pra montar a contagem por temporada, paralelizado
como `fetchAllSeasonsInParallel` — chegou a ser implementada (`TmdbClient.getEpisodeCredits`,
`ContentDetailsServiceImpl.seasonEpisodeCountByCastId`/`castForSeason`) só pra ser descartada no
mesmo turno quando o usuário apontou que o TMDB já expõe `aggregate_credits` **por temporada**
também, não só no nível da série — com `episode_count` já reescopado pra aquela temporada
especificamente. Isso elimina a necessidade de qualquer chamada extra: `getSeasonFullDetails` só
precisou ganhar `aggregate_credits` no `append_to_response` (`aggregate_credits,watch/providers`)
igual a `getTvFullDetails` já fazia, e `TmdbSeasonFullDetails` ganhou o campo `aggregateCredits`
(mesmo tipo `TmdbAggregateCredits` já usado pela série). Todo o código de créditos por episódio foi
revertido no mesmo turno (método `getEpisodeCredits`, cache `tmdbEpisodeCredits`, os dois métodos
auxiliares) — não sobrou nada da tentativa descartada no código final.

`buildSeasonDetails` agora chama `castFromAggregateCredits(season.aggregateCredits())` em vez de
`series.aggregateCredits()` — o elenco da tela de Temporada passa a ser o conjunto (possivelmente
menor) de quem realmente apareceu naquela temporada, não o elenco regular da série inteira como
antes. `TmdbAggregateCastMember` ganhou `totalEpisodeCount` (`total_episode_count` do TMDB, soma
de todos os personagens que a pessoa interpretou); `CastMemberDTO` ganhou `id` e `episodeCount`.
`castFromAggregateCredits` (tela de Série e elenco reaproveitado por EPISODE) usa
`member.totalEpisodeCount()`; `castFromCredits` (MOVIE) e `guestStars` (EPISODE) sempre mandam
`episodeCount=null` — filme não tem conceito de episódio, guest star já é implicitamente daquele
único episódio. `id` foi adicionado nos três (`TmdbCastMember`/`TmdbAggregateCastMember`/
`TmdbGuestStar` já tinham o campo no modelo TMDB, só não estava sendo mapeado pro DTO).

Teste da tela de Temporada reescrito
(`shouldReturnCastFromSeasonScopedAggregateCreditsNotTheSeriesWideOnesWhenContentIsASeason`) —
monta `aggregate_credits` diferentes na temporada (`episodeCount=2`) e na série
(`totalEpisodeCount=62`) pro mesmo ator, e assegura que a resposta usa o valor da temporada, não o
da série, provando que a fonte certa está sendo lida. `openapi.yaml` (`CastMember.id`/
`episodeCount` documentados, descrição de `ContentDetailsDTO`/`cast` corrigida — não é mais
verdade que "cast em SEASON/EPISODE reaproveita o elenco regular da SÉRIE", só EPISODE faz
isso agora), `business-rules.md`/`-summary.md` atualizados junto. `TmdbClientTest`/
`TmdbClientCachingTest`: URL esperada de `getSeasonFullDetails` atualizada pra incluir
`aggregate_credits` no `append_to_response`. Suíte completa: 2031 testes, `BUILD SUCCESS`.

## 2026-08-31 (6) — Fix: `POST /diary/bulk` retornava 409 ao logar uma série com episódio já existente

Reportado pelo usuário: chamar `/diary/bulk` pra uma série inteira devolvia `409 "This content is
already registered with a different isSeasonFinale value"` mesmo em requests legítimos. Causa:
`DiaryEntryServiceImpl.bulkLogEpisode` mandava um `isSeasonFinale=false` explícito pra
`ContentService.getOrCreateReference` em todo episódio não-final do lote (o parâmetro era
`boolean` primitivo, nunca `null`) — isso conflita com `assertNoMetadataMismatch` sempre que aquele
episódio já existisse no banco com `isSeasonFinale` não setado (`null`), por exemplo por já ter
sido logado individualmente antes via `POST /diary` sem informar a flag. `isSeriesFinale` tinha o
mesmo problema no episódio final de uma temporada que não é a finale da série. Corrigido pra só
mandar `Boolean.TRUE` quando o episódio de fato é o finale (season/series), e `null` (nenhuma
asserção) nos demais casos — consistente com a regra já documentada em `business-rules.md` de que
omitir a flag nunca gera conflito.

Novo teste de integração
`shouldBulkLogTheWholeSeasonWhenOneEpisodeWasAlreadyLoggedIndividuallyWithoutAFinaleFlag`
reproduz o cenário exato (loga um episódio avulso sem flag de finale, depois faz bulk da temporada
inteira) — falha com `409` sem a correção, passa com ela. Suíte `diaryentry`/`content` completa: sem
falhas.

## 2026-08-31 (7) — Fix: `POST /diary/bulk` de série inteira travava ao completar a temporada finale

Segunda parte do bug reportado pelo usuário na entrada (6): mesmo depois daquele fix, um bulk de
série inteira ainda dava `409` ("already registered with a different isSeriesFinale value") ao
tentar completar a última temporada. Causa raiz descoberta consultando o banco direto: a `Content`
tipo `SEASON` da temporada finale já existia (criada antes, sem `isSeriesFinale` nunca definido —
`null`), e `DiaryEntryServiceImpl.maybeCompleteSeason` tentava gravar `isSeriesFinale=true` nela ao
descobrir que aquela temporada era de fato a finale da série. `ContentServiceImpl.assertNoMetadataMismatch`
tratava "valor existente `null`" igual a "valor existente divergente" e rejeitava — bloqueando pra
sempre qualquer tentativa de preencher uma flag de finale que ficou desconhecida na criação do
`Content`.

Decisão (depois de avaliar e descartar a alternativa de só parar de conflitar sem persistir): sem
gravar o valor, `maybeCompleteSeries`/`maybeCompleteSeason` nunca encontrariam a `SEASON`/`EPISODE`
finale via `findBySeriesTmdbIdAndTypeAndIsSeriesFinaleTrue`, e a criação automática da `DiaryEntry`
de série/temporada completa simplesmente nunca aconteceria — bug silencioso e permanente pior que o
`409` visível. Implementado `ContentServiceImpl.reconcileExisting`: quando o valor salvo é `null`
(nunca setado) e o request novo manda um valor não-nulo, grava esse valor na `Content` existente em
vez de rejeitar — só `isSeasonFinale`/`isSeriesFinale` (não estendido a `runtimeMinutes`/`genres`/
`releaseYear`/`countries`, que continuam sem backfill). Roda dentro de `NewTransactionExecutor`
(`REQUIRES_NEW`), reaproveitando `clearPreviousSeasonFinale`/`clearPreviousSeriesFinale` pra manter a
transferência-pra-frente já existente; perder uma corrida concorrente (`DataIntegrityViolationException`
na constraint de unicidade) devolve a referência existente em vez de propagar erro. `assertNoMetadataMismatch`
ajustado pra só rejeitar quando o valor **já existente** também é não-nulo e diverge — comparação
`existing == null` deixou de contar como conflito pra essas duas flags.

Três testes novos em `ContentServiceImplTest`
(`shouldBackfillIsSeriesFinaleOnTheExistingSeasonWhenItWasNeverSetBefore`,
`shouldBackfillIsSeasonFinaleOnTheExistingEpisodeWhenItWasNeverSetBefore`,
`shouldFallBackToTheExistingValueWhenBackfillingIsSeriesFinaleLosesAConcurrentRace`) e um teste de
integração novo em `DiaryEntryControllerIntegrationTest`
(`shouldBulkLogTheWholeSeriesAndBackfillIsSeriesFinaleWhenTheFinaleSeasonContentAlreadyExistedWithoutIt`)
reproduzindo o cenário real (bulk de série completa com a `SEASON` finale pré-existente sem a flag) —
confirmado que os quatro falham sem a correção (revertida temporariamente via `git stash` só pra essa
checagem) e passam com ela. `business-rules.md`/`-summary.md` atualizados (regra de imutabilidade de
`Content` agora distingue reenvio-divergente-rejeitado de valor-nulo-preenchido, só pras duas flags de
finale). Suíte `content`/`diaryentry` completa: sem falhas.

## 2026-08-31 (8) — `runtimeMinutes`/`totalRuntimeMinutes`/`numberOfEpisodes`/`creators`/`guestStars` de SEASON em `GET /contents/{contentId}/details`

A pedido do usuário: até então `ContentDetailsDTO` deixava esses cinco campos sempre `null` em
`SEASON`, mesmo quando o dado já estava disponível na mesma resposta que `buildSeasonDetails` já
buscava pra montar `episodes`/`cast`/`genres`. `runtimeMinutes` (média) e `totalRuntimeMinutes`
(soma) passaram a reaproveitar os helpers já usados em SERIES, aplicados só aos episódios daquela
temporada (`ContentDetailsServiceImpl.runtimesOf`, extraído do antigo `episodeRuntimes` pra ser
reutilizável nos dois escopos); `numberOfEpisodes` virou `season.episodes().size()`; `creators`
passou a reaproveitar `series.createdBy()` (já buscado no mesmo request pra genres/countries). Nenhum
dos três exige chamada TMDB extra.

`guestStars` de SEASON foi o pedido mais específico do usuário: TMDB já embute `guest_stars` em
cada episódio dentro do corpo de `GET /tv/{id}/season/{n}` (sem precisar de `append_to_response`
nem chamada extra por episódio) — só não estava sendo lido porque `TmdbEpisodeSummary` não mapeava
esse campo. Adicionado `guest_stars` em `TmdbEpisodeSummary` e
`ContentDetailsServiceImpl.seasonGuestStars`, que agrega os guest stars de todos os episódios da
temporada deduplicados por `TmdbGuestStar.id` (mesma pessoa em mais de um episódio vira uma entrada
só, com `CastMemberDTO.episodeCount` contando em quantas ela apareceu naquela temporada).
`guestStars` de SERIES permanece sempre vazio — o usuário confirmou explicitamente que não precisa
disso, e agregar exigiria uma chamada TMDB por episódio de cada temporada (N+1), fora do orçamento
de 1 chamada por temporada já usado por `totalRuntimeMinutes`/`recentEpisodes`.

Dois testes novos em `ContentDetailsServiceImplTest`
(`shouldReturnRuntimeMinutesTotalRuntimeMinutesNumberOfEpisodesAndCreatorsWhenContentIsASeason`,
`shouldAggregateGuestStarsAcrossEpisodesWithPerSeasonEpisodeCountWhenContentIsASeason`); os testes
existentes que instanciam `TmdbEpisodeSummary` foram ajustados pro novo campo `guestStars`.
`openapi.yaml`/`business-rules.md`/`-summary.md` atualizados. Suíte `ContentDetailsServiceImplTest`
completa: 20 testes, sem falhas.

## 2026-09-01 — Trava de grupo de tipo de conteúdo em `UserListItem`

Até então uma `UserList` de conteúdo só travava entre "conteúdo" e "lista aninhada" (primeiro item
decide o formato), mas aceitava misturar livremente filme, série, temporada e episódio dentro da
mesma lista — o que não fazia sentido pra uma lista curada em torno de um tipo de mídia (ex.: um
top de temporadas favoritas terminando com um filme dentro). Adicionado `UserListItemScope`, um enum
com cinco valores (`MOVIE_OR_SERIES`, `SEASON`, `EPISODE`, `LIST`, `MIXED`) e dois métodos estáticos:
`forContentType` mapeia um `ContentType` pro grupo correspondente (`MOVIE`/`SERIES` juntos no mesmo
grupo, já que uma lista de "filmes e séries favoritos" é um caso de uso legítimo; `SEASON` e
`EPISODE` cada um isolado no seu próprio grupo), e `resolve` infere o grupo atual de uma lista a
partir dos tipos de conteúdo já presentes nela. `UserListItemServiceImpl.addItem`/`addItems` passaram
a chamar `assertContentTypeGroupMatches`/`resolveExistingContentScope` antes de inserir um item de
conteúdo, rejeitando com `400` quando o tipo do item novo não bate com o grupo já travado pela lista
— mesmo espírito da trava de conteúdo-vs-lista existente, sem coluna nova no banco: o grupo é sempre
recalculado a partir dos itens já persistidos.

`addItems` (o caminho de bulk insert) exigiu um cuidado a mais: como o método monta todos os
`UserListItem` em memória antes de um único `saveAll`, dois itens de grupos diferentes no mesmo
payload nunca colidiriam numa consulta ao banco, já que nenhum dos dois estaria persistido ainda no
momento da checagem. `addItems` passou a rastrear o grupo já confirmado como uma variável local,
atualizada a cada item do lote, em vez de reconsultar o banco item a item. `UserListItemService`
ganhou `getItemScope`/`getItemScopeByListIds` (versão em lote, reaproveitada pelo batching de
`getUserLists`), expostos como o novo campo `itemScope` (nullable) em `UserListResponseDTO`/
`UserListDetailedResponseDTO` — `null` numa lista ainda sem itens de conteúdo, aceitando qualquer
tipo. Listas criadas antes dessa regra que já misturavam grupos resolvem para `MIXED` em vez de expor
um grupo arbitrário; não há migração retroativa, então essas listas ficam, na prática, travadas contra
qualquer novo item de conteúdo, já que nenhum tipo candidato bate com um grupo já misto.
`openapi.yaml`/`business-rules.md`/`-summary.md` atualizados com o novo campo e a nova regra.

## 2026-09-01 (2) — Revisão e correções na trava de grupo de tipo de conteúdo

Revisão de código (`/code-review origin/main`) nos 8 commits da entrada anterior achou três problemas,
todos corrigidos no mesmo dia:

**1. `itemScope` inconsistente entre endpoints.** `UserListServiceImpl.getUserListById` calculava
`itemScope` a partir dos itens já retornados por `getItems`, que passam por
`toVisibilityScopedResponseDto` (zera `childList` de itens aninhados invisíveis ao viewer). Uma
lista-de-listas pública cujas listas filhas fossem todas `FOLLOWERS`/`PRIVATE` pra um viewer sem acesso
reportava `itemScope: null` em vez de `LIST` — divergindo do valor correto que
`GET /users/{userId}/lists` já retornava pra mesma lista (consulta direta ao banco, sem esse filtro).
Corrigido: `getUserListById` agora chama `UserListItemService.getItemScope(listId)`, a mesma consulta
não-filtrada usada em toda listagem.

**2. Query de listas aninhadas duplicada por página.** `UserListServiceImpl.mapToResponseDtoPage`
chamava `countNestedListsByListIds` e depois `getItemScopeByListIds`, que internamente repetia a mesma
consulta (`countNestedListsByUserListIdIn`) — dobrando essa query em toda listagem paginada
(`GET /users/{userId}/lists`, `/users/me/lists`, listas curtidas). Adicionada sobrecarga
`UserListItemService.getItemScopeByListIds(listIds, nestedListsCountByListId)` que reaproveita a
contagem já buscada pelo chamador em vez de reconsultar; `mapToResponseDtoPage` e `toResponseDto`
(usado no `PATCH /lists/{listId}`) passaram a usá-la.

**3. Trava de grupo de tipo sem lock, corrigindo resposta sob concorrência.**
`UserListItemServiceImpl.assertContentTypeGroupMatches` era check-then-act sem lock nem constraint no
banco — o padrão de bug já listado como recorrente neste projeto. Verificação empírica com
`CyclicBarrier`/`ExecutorService` (`UserListItemControllerIntegrationTest.shouldOnlyLetOneContentTypeGroupWinWhenTwoDifferentGroupsRaceOnTheSameEmptyList`)
mostrou que a corrupção de dado (lista terminar com dois grupos misturados) nunca chegava a acontecer de
fato: duas inserções concorrentes numa lista vazia sempre calculam a mesma próxima `position` e colidem
em `uq_user_list_items_user_list_id_position`, então uma das duas sempre falha — mas com um `409`
genérico de "posição já ocupada" em vez do `400` real de "grupo de tipo incompatível", e dependendo de
um efeito colateral acidental de outra constraint pra manter o invariante, não de uma garantia própria.
`addItem`/`addItems` passaram a adquirir um lock pessimista na linha da `UserList`
(`UserListRepository.findByIdForUpdate`, `PESSIMISTIC_WRITE`, via nova `UserListItemServiceImpl.findOwnedListForUpdate`)
antes de checar o grupo — o mesmo lock também serializa a checagem de conteúdo-vs-lista-aninhada
(`assertListIsNotLockedAsListOfLists`/`assertListIsNotLockedAsContentList`), mesma forma de bug.

`business-rules.md`/`-summary.md` atualizados com os três pontos. Suíte `userlist` completa: 374 testes
(1 novo), sem falhas.

## 2026-09-01 (3) — Budget, revenue, production companies, crew filtrado por job e videos em `/contents/{contentId}/details`

Ampliado o proxy de detalhes do TMDB (`ContentDetailsDTO`/`ContentDetailsServiceImpl`/`TmdbClient`)
com 5 campos novos, sem nenhuma chamada HTTP nova ao TMDB: `budget`/`revenue` (só MOVIE — TMDB não
tem esses campos por série — `0` tratado como não informado), `productionCompanies` (MOVIE/SERIES,
herdado por SEASON/EPISODE), `crew` (equipe técnica filtrada por uma lista fixa de 7 jobs — Director,
Screenplay, Executive Producer, Production Manager, First Assistant Director, Director of
Photography, Supervising Art Director — um registro por pessoa com jobs agrupados; MOVIE lê
`credits.crew`, SERIES lê `aggregate_credits.crew`, ambos já buscados hoje só sem o campo mapeado) e
`videos` (todos os vídeos do TMDB sem filtro de type/official/site, campo `key` incluído pra montar a
URL de reprodução — adicionado ao `append_to_response` de `getMovieFullDetails`/`getTvFullDetails`).
SEASON/EPISODE herdam `productionCompanies`/`crew`/`videos` da SERIES do mesmo `seriesTmdbId`, mesmo
padrão já usado por `genres`/`countries`/`creators` — `budget`/`revenue` continuam `null` pra esses
tipos. `openapi.yaml`/`business-rules.md`/`business-rules-summary.md` atualizados junto.

## 2026-09-01 (4) — Corrigido backfill de metadados de `Content` pra `runtimeMinutes`/`genres`/`releaseYear`/`countries`

`ContentServiceImpl.assertNoMetadataMismatch` lançava `409` ("already registered with a different ...
value") ao reenviar `runtimeMinutes`/`genres`/`releaseYear`/`countries` pra um `Content` já existente
cujo campo ainda estava `null`, em vez de tratar como ausência de valor — o guard só checava se o valor
novo era não-nulo, sem checar se o já persistido também era. Estendida a mesma lógica de backfill já
usada por `isSeasonFinale`/`isSeriesFinale` (`ContentServiceImpl.reconcileExisting`, bug corrigido em
2026-08-31) pros outros quatro campos: quando o valor persistido é `null`, o valor novo não-nulo passa a
ser gravado em vez de gerar conflito. Corrige `GET /users/{userId}/summary` retornando
`watchTime`/`genreCounts` zerados mesmo quando o cliente tentava reenviar os dados depois de descobri-los
(ex. via `GET /contents/{contentId}/details`, que busca na TMDB mas nunca persiste nada por si só). 4
novos testes em `ContentServiceImplTest` (um por campo, cobrindo o backfill); suíte completa: 68 testes,
sem falhas. `business-rules.md`/`business-rules-summary.md` atualizados junto.

## 2026-09-01 (5) — `runtimeMinutes` por episódio em `POST /diary/bulk`

Todo episódio criado por `POST /diary/bulk` nascia sempre com `runtimeMinutes = null` (não havia como o
cliente informar), zerando `totalMinutesWatched` pra qualquer conteúdo logado em lote — mesmo que o
cliente já soubesse o valor de cada episódio (`GET /contents/{contentId}/details` de uma temporada já
devolve `EpisodeSummaryDTO.runtime` pra cada episódio, vindo direto do TMDB). Adicionados dois campos
opcionais em `DiaryEntryBulkCreationDTO`: `episodeRuntimeMinutes` (mapa episódio→minutos, só pra bulk de
`SEASON`) e `seasonEpisodeRuntimeMinutes` (mapa temporada→episódio→minutos, só pra bulk de `SERIES`),
espelhando o mesmo padrão já usado por `finaleEpisodeNumber`/`seasonFinaleEpisodeNumbers`.
`DiaryEntryServiceImpl.bulkLogEpisode` passa a repassar o valor correspondente (quando presente) direto
pro `ContentRefCreationDTO.runtimeMinutes` de cada episódio, exatamente como o caminho de `POST /diary`
individual já fazia — sem estimativa/média, já que o dado real está sempre disponível na mesma chamada
de detalhes que o cliente já precisa fazer pra saber o `finaleEpisodeNumber`/`airedEpisodeCount`. Um
episódio ausente do mapa simplesmente não recebe `runtimeMinutes` (mesmo comportamento de omitir o campo
em `POST /diary`); um episódio cujo `Content` já tem um valor diferente e não-nulo continua rejeitado com
`409`, igual ao caminho de entrada única. 2 novos testes em `DiaryEntryServiceImplTest` (um por tipo de
bulk, verificando via `ArgumentCaptor` que o `runtimeMinutes` de cada episódio chega correto em
`ContentService.getOrCreateReference`); suíte completa: 2084 testes, sem falhas. `openapi.yaml`/
`business-rules.md`/`business-rules-summary.md` atualizados junto.

## 2026-09-02 — Revertido `runtimeMinutes` client-supplied em `POST /diary/bulk` pra resolução server-side via TMDB

A versão de `runtimeMinutes` por episódio em `POST /diary/bulk` (2026-09-01, item acima) quebrava a
regra geral de que o cliente nunca fala com o TMDB diretamente (ver "TMDB detail proxy" no
`CLAUDE.md`): o cliente só conseguia preencher `episodeRuntimeMinutes`/`seasonEpisodeRuntimeMinutes` se
já tivesse chamado `GET /contents/{contentId}/details` daquela temporada antes — uma viagem redundante,
já que o backend tem a mesma chamada TMDB cacheada (`TmdbClient.getSeasonFullDetails`). Removidos os
dois campos client-supplied de `DiaryEntryBulkCreationDTO`; `DiaryEntryServiceImpl.bulkLogSeason`/
`bulkLogSeries` agora chamam `TmdbClient.getSeasonFullDetails(seriesTmdbId, seasonNumber,
user.getPreferredLanguage())` diretamente — uma vez por temporada envolvida no bulk, nunca por episódio,
já que a resposta de `/tv/{seriesId}/season/{seasonNumber}` já traz o `runtime` de todos os episódios
daquela temporada de uma vez (`TmdbEpisodeSummary.runtime`) — e alimentam `ContentRefCreation.runtimeMinutes`
de cada `EPISODE` criado, exatamente como antes. TMDB indisponível ao buscar uma dessas temporadas agora
falha o bulk inteiro com `502` (`TmdbUnavailableException`, nova dependência `TmdbClient` injetada
em `DiaryEntryServiceImpl`), em vez de logar sem `runtimeMinutes` — decisão deliberada, diferente da
omissão legítima de um episódio ausente do mapa client-supplied de antes. 3 testes reescritos/adicionados
em `DiaryEntryServiceImplTest` (fetch por temporada via TMDB mock, e `TmdbUnavailableException`),
`DiaryEntryControllerIntegrationTest` ganhou `@MockitoBean TmdbClient` com stub padrão e um novo teste
de `502`; suíte completa: 2086 testes, sem falhas. `openapi.yaml`/`business-rules.md`/
`business-rules-summary.md` atualizados junto.

## 2026-09-02 (2) — `finaleEpisodeNumber`/`finaleSeasonNumber`/`seasonFinaleEpisodeNumbers` derivados do TMDB em `POST /diary/bulk` quando omitidos

Mesmo dia da mudança de `runtimeMinutes` acima: como o bulk já busca `TmdbClient.getSeasonFullDetails`
de toda temporada envolvida incondicionalmente (pro `runtimeMinutes`), a mesma resposta já dá pra
derivar `finaleEpisodeNumber`/`seasonFinaleEpisodeNumbers` sem custo extra de chamada — contando quantos
`episodes[]` têm `airDate` não-futuro (`DiaryEntryServiceImpl.airedEpisodeCount`). `finaleSeasonNumber`
precisou de uma chamada nova, `TmdbClient.getTvFullDetails`, resolvida como a maior temporada
(excluindo a 0/especiais) com `airDate` não-futuro em `seasons[]` (`latestAiredSeasonNumber`). Os três
campos deixaram de ser obrigatórios quando não há finale conhecido no banco — antes davam `400`
("finaleEpisodeNumber/finaleSeasonNumber is required..."), agora só continuam dando `400` se nem o TMDB
tiver nada lançado ainda (série anunciada mas não estreada). Diferente do `runtimeMinutes` (removido do
request), os três continuam aceitos como valor explícito — a checagem de finale já existente no banco
continua tendo prioridade sobre os dois, e o valor explícito do cliente continua tendo prioridade sobre
o TMDB quando informado. `bulkLogSeries` foi reestruturado pra buscar cada temporada do TMDB uma única
vez, guardada num mapa local reaproveitado pelas duas passadas do método (contagem total e criação),
em vez das duas chamadas por temporada que já existiam antes dessa mudança. 2 novos testes em
`DiaryEntryServiceImplTest` (auto-derive de `finaleEpisodeNumber` via episódios lançados, auto-derive
de `finaleSeasonNumber` via temporadas lançadas) mais ajuste em 2 testes existentes que dependiam do
comportamento antigo de erro imediato sem TMDB; suíte completa: 2088 testes, sem falhas. `openapi.yaml`/
`business-rules.md`/`business-rules-summary.md`/`telas-reqs.md` atualizados junto.

## 2026-09-02 (3) — `watchedDate` validado no servidor: nunca no futuro, e nunca antes do lançamento em `POST /diary/bulk`

Antes, `watchedDate` era 100% responsabilidade do cliente em todo endpoint de escrita do diário —
`openapi.yaml` documentava "neither rule is validated server-side" tanto pra "não pode ser no futuro"
quanto pra "não pode ser antes do lançamento do conteúdo". Adicionados
`DiaryEntryServiceImpl.assertWatchedDateNotInFuture`/`assertWatchedDateNotBeforeRelease`. A checagem
"não no futuro" não depende do TMDB (só `LocalDate.now()`), então passou a valer nos três endpoints de
escrita — `POST /diary`, `POST /diary/bulk` e `PATCH /diary/{id}` — sempre o mais cedo possível em cada
método, antes de qualquer chamada a `ContentService`/TMDB. Já "não antes do lançamento" só ficou
possível em `POST /diary/bulk` (`SEASON`/`SERIES`): reaproveita a mesma resposta de
`TmdbClient.getSeasonFullDetails` já buscada pro `runtimeMinutes`/derivação de finale (itens acima,
mesmo dia), sem chamada nova ao TMDB — comparada só contra a data de lançamento do último episódio
efetivamente logado no lote (`DiaryEntryServiceImpl.episodeAirDate`), não contra cada episódio
individualmente, já que episódios de uma temporada vão ao ar em ordem cronológica. `POST /diary` e
`PATCH /diary/{id}` (filme, episódio avulso fora do bulk) continuam sem essa segunda checagem — exigiria
uma chamada nova ao TMDB que esses dois caminhos não fazem hoje; gap documentado que segue existindo,
mas restrito a esses dois endpoints agora. 6 novos testes em `DiaryEntryServiceImplTest` (futuro em
create/bulk/update, antes-do-lançamento em bulk) e 4 em `DiaryEntryControllerIntegrationTest`; suíte
completa: 2096 testes, sem falhas. `openapi.yaml`/`business-rules.md`/`business-rules-summary.md`/
`telas-reqs.md` atualizados junto.

## 2026-09-02 (4) — Fix: `genreCounts` não contava `DiaryEntry` logada diretamente como SERIES

`POST /diary` sempre aceitou `content.type=SERIES` diretamente (logar a série inteira de uma vez, sem
passar por episódio nenhum — caminho já tratado explicitamente em
`DiaryEntryServiceImpl.removeFromWatchlistAndDropped`), mas as quatro queries nativas de gênero em
`DiaryEntryRepository` (`countDistinctTitlesByGenreAndUserId`, usada no `genreCounts` de
`UserResponseDTO`/`PublicUserProfileDTO`; `countDistinctTitlesByGenreAndUserIdForSeries` e
`countEntriesByGenreAndUserIdForSeries`/`...AndWatchedDateBetween`, usadas em `GET
/users/{userId}/summary`/Month/Year/Home/All Time Stats) filtravam só `c.type IN ('MOVIE', 'EPISODE')` —
uma série cuja única `DiaryEntry` do usuário fosse do tipo `SERIES` (log direto, sem episódio nenhum)
nunca contribuía pro `genreCounts`, nem na primeira vez nem num reassistir. As quatro queries agora
também leem `c.type = 'SERIES'` direto, usando o gênero do próprio `Content` `SERIES`
(`c.genres`) em vez de resolvê-lo via `EPISODE`→`SERIES`; dedupe entre um log direto em `SERIES` e os
`EPISODE`s da mesma série usa a mesma chave (`tmdbId` da `SERIES` = `seriesTmdbId` do episódio), então a
série não é contada duas vezes se tiver os dois tipos de entrada. `ratingsDistribution` (que tem a mesma
lacuna pra nota dada direto numa `DiaryEntry` SERIES) ficou fora de escopo por decisão do usuário — segue
documentada como limitação conhecida. 5 novos testes em `DiaryEntryRepositoryTest`; suíte completa: 2101
testes, sem falhas. `business-rules.md`/`business-rules-summary.md` atualizados junto.

## 2026-09-02 (5) — Fix: `POST /diary/bulk` rejeitava reenvio de `runtimeMinutes` já divergente vindo do TMDB

Um `Content` de `EPISODE` já registrado antes (com `runtimeMinutes` informado por um `POST
/diary` individual) passou a colidir com `409` ao ser relogado via `POST /diary/bulk`, porque o
`runtimeMinutes` que o bulk agora resolve sozinho no TMDB (desde a mudança de 2026-09-02 mais
cedo) divergia do valor antigo salvo — travando o usuário permanentemente, sem forma de corrigir,
mesmo o valor novo sendo a fonte verificada (TMDB) e o antigo sendo só o que um cliente informou
uma vez. `ContentService.getOrCreateReference` ganhou um overload
(`getOrCreateReference(dto, trustedRuntimeMinutes)`) — o `getOrCreateReference(dto)` de 1
argumento usado em todo o resto da aplicação continua delegando pra esse com
`trustedRuntimeMinutes = false`, mantendo o `409` de sempre; só `DiaryEntryServiceImpl.
bulkLogEpisode` passa `true`, e só afeta o campo `runtimeMinutes` — os outros 5 campos
client-supplied (`isSeasonFinale`/`isSeriesFinale`/`genres`/`releaseYear`/`countries`) continuam
batendo em `409` em qualquer divergência, em qualquer caminho, sem exceção. Quando
`trustedRuntimeMinutes = true` e o valor já salvo diverge do resolvido via TMDB,
`ContentServiceImpl.reconcileExisting` sobrescreve em vez de lançar `ConflictException`.
Separadamente, `POST /diary/bulk` também passou a rejeitar com `400` (em vez de ignorar
silenciosamente) o envio de `genres`/`releaseYear`/`countries` no `content` de nível superior —
nenhum dos três nunca chegava a ser usado por `bulkLogSeason`/`bulkLogSeries` nem pela cascata de
completude, então aceitá-los sem uso seria uma perda de dado invisível pro cliente. 4 novos testes
(`ContentServiceImplTest` x2, `DiaryEntryServiceImplTest` x1, mais ajustes de mock em 11 testes
de bulk já existentes pro novo overload); suíte completa: 2104 testes, sem falhas.
`CLAUDE.md`/`openapi.yaml`/`business-rules.md`/`business-rules-summary.md` atualizados junto.

## 2026-09-02 (6) — Revert parcial: `runtimeMinutes` de `POST /diary/bulk` volta a ser client-supplied pra SEASON

Por decisão explícita do usuário, o `runtimeMinutes` de cada episódio no bulk (item anterior)
voltou a ser informado manualmente pelo cliente quando `content.type = SEASON` — o `SERIES`
continua resolvendo tudo sozinho no TMDB, inalterado. Justificativa: quem está numa tela de uma
temporada específica já tem o `runtime` de cada episódio em mãos (voltou de
`GET /contents/{contentId}/details` daquela temporada), diferente de logar uma série inteira de
uma vez, onde reunir isso manualmente pra todas as temporadas seria a "viagem redundante" que
motivou a resolução automática originalmente. `DiaryEntryBulkCreationDTO` ganhou de volta
`episodeRuntimeMinutes` (mapa `episodeNumber → runtimeMinutes`, opcional, mesmas constraints de
`ContentRefCreation.runtimeMinutes`), usado só por `bulkLogSeason`. `bulkLogEpisode` ganhou um
parâmetro `trustedRuntimeMinutes` repassado pro `ContentService.getOrCreateReference(dto,
trustedRuntimeMinutes)` do item anterior — `bulkLogSeries` continua passando `true` (TMDB
verificado), `bulkLogSeason` agora passa `false` (client-supplied, mesma trava de `409` em
divergência que `POST /diary` individual). O bulk de `SEASON` continua chamando
`TmdbClient.getSeasonFullDetails` incondicionalmente mesmo sem usar o `runtime` dali — a mesma
busca ainda resolve `finaleEpisodeNumber` (quando omitido) e valida `watchedDate` contra a data de
lançamento do último episódio, então TMDB indisponível ainda falha o bulk inteiro com `502`. 1
teste antigo (que verificava resolução automática pra SEASON) reescrito pro novo comportamento,
mais 1 teste novo cobrindo episódio ausente do mapa; suíte completa: 2105 testes, sem falhas.
`CLAUDE.md`/`openapi.yaml`/`business-rules.md`/`business-rules-summary.md`/`telas-reqs.md`
atualizados junto.

## 2026-09-02 (7) — Fix: `recentEpisodes` de `GET /contents/{contentId}/details` não desempatava episódios lançados no mesmo dia

`ContentDetailsServiceImpl.recentlyAiredEpisodes` (SERIES) ordenava só por `airDate` decrescente
antes de cortar nos 3 mais recentes. Quando uma temporada lança todos os episódios no mesmo dia
(empate total em `airDate`), `Stream.sorted` é estável e o desempate caía pra ordem de chegada do
`flatMap` — episódios em ordem *crescente* dentro de cada temporada — então o corte em 3 devolvia
os *primeiros* episódios daquela leva, não os últimos. Adicionado `seasonNumber`/`episodeNumber`
(ambos decrescentes) como desempate depois de `airDate`. Investigado a pedido do usuário, que
também suspeitou de uma causa relacionada em `POST /diary/bulk` não inserir episódios na ordem de
lançamento (o `recentEpisodes` do resumo de perfil, `SummaryResponseDTO`, via
`DiaryEntryRepository.findByUserIdWithFilters` `ORDER BY d.createdAt DESC`) — não encontrada
evidência de bug ali: `bulkLogSeason`/`bulkLogSeries` persistem episódios sequencialmente em ordem
crescente de número (que corresponde à ordem de lançamento), cada `persistDiaryEntry` gera um
`LocalDateTime.now()` com precisão de microssegundos, e uma consulta direta ao banco confirmou
`created_at` estritamente distinto e corretamente ordenado entre episódios de um mesmo bulk — sem
alteração feita nesse caminho. 1 novo teste em `ContentDetailsServiceImplTest`, sanity-checado
revertendo o fix pra confirmar que falha sem ele; suíte completa: 2106 testes, sem falhas.
`business-rules.md`/`business-rules-summary.md` atualizados junto.

## 2026-09-02 (8) — Contagem de idas ao cinema no perfil, vídeos restritos a YouTube com URL pronta, e `containsContent` em `GET /users/{userId}/lists`

Três pedidos do usuário, implementados juntos. **`totalTheaterVisits`**: novo campo em
`UserResponseDTO`, `PublicUserProfileDTO` e `AllTimeStatsResponseDTO` — `COUNT` de `DiaryEntry` com
`watchedInTheater = true` (`DiaryEntryRepository.countByUserIdAndWatchedInTheaterTrue`), computado em
`UserServiceImpl.computeProfileStats`/`SummaryServiceImpl.getAllTimeStats`; como `watchedInTheater`
só pode ser setado em `Content` tipo `MOVIE`, a contagem já é implicitamente só de filmes. **Vídeos
restritos a YouTube**: `ContentDetailsServiceImpl.videos` passou a descartar qualquer vídeo cujo
`site` não seja `YouTube` (revertendo a decisão de 2026-09-01 de não filtrar por site, a pedido do
usuário — o app só toca vídeo do YouTube); `VideoDTO` ganhou um campo `url` montado no backend
(`https://www.youtube.com/watch?v={key}`), pra o cliente não remontar a URL a partir de `key`.
**`containsContent`**: `GET /users/{userId}/lists` ganhou o parâmetro opcional `contentId` — quando
informado, `UserListServiceImpl.mapToResponseDtoPage` resolve em lote (`UserListItemRepository.
findUserListIdsContainingContent`, 1 query pra página inteira) quais listas já contêm esse content e
preenche `containsContent` (`true`/`false`) em vez de deixá-lo `null`; pensado pra tela de "adicionar
a uma lista" a partir da tela de um content marcar as que já o contêm. Um `contentId` inexistente
resolve `false` pra todas, sem `404`, mesma filosofia de `GET /contents/{contentId}/stats`. 9 testes
novos (repositório, serviço e controller dos três pontos), suíte completa: 2114 testes, sem falhas.
`openapi.yaml`/`business-rules.md`/`business-rules-summary.md` atualizados junto.

## 2026-09-02 (9) — `topWatchCompanions` em Month/Year in Review e All Time Stats

Novo campo `topWatchCompanions` (top 3, com quantidade) nas três respostas de summary escopadas por
período/all-time — pedido do usuário. Reaproveita as tags de `WatchCompanion` ("assistido com") já
existentes em vez de inferir overlap entre diários; como `WatchCompanion` só permite marcar quem o
próprio dono segue, "seguidores" aqui significa pessoas que o usuário segue, não quem o segue.
`WatchCompanionRepository` ganhou duas queries novas — uma escopada por `type`+intervalo de datas
(Month/Year in Review), outra combinando `MOVIE`+`EPISODE` sem data (All Time Stats) — agrupando por
companion e contando tags (rewatches incluídos), sem desempate explícito no 3º lugar, mesmo padrão
de `mostLoggedContent`. Novo DTO `WatchCompanionCountDTO` reaproveita `UserPreviewDTO`, o mesmo já
usado em `DiaryEntry.watchedWith`. 4 testes novos em `SummaryServiceImplTest`; suíte completa: 2118
testes, sem falhas. `openapi.yaml`/`business-rules.md`/`business-rules-summary.md` atualizados
junto.

## 2026-09-03 — Fix: `POST /diary/bulk` rejeitava séries comuns por causa do teto de 100 episódios

`MAX_BULK_EPISODES` subiu de 100 para 2000 — o teto antigo rejeitava até séries médias (5 temporadas
de ~24 episódios já somam mais de 100 no total), não só outliers. Acima de 2000, em vez de rejeitar
sempre, `bulkLogSeason`/`bulkLogSeries` agora verificam a contagem real de episódios no TMDB antes de
decidir: `SEASON` compara com `TmdbSeasonFullDetails.episodes().size()` (já buscado pra outras
validações do bulk); `SERIES` faz uma chamada adicional (cacheada) a `TmdbClient.getTvFullDetails` e
compara com `number_of_episodes`. Bate ou fica abaixo → segue sem teto superior, cobrindo séries
genuinamente longas (novelas diárias, animes de centenas/milhares de episódios); não bate ou o TMDB
não informa `number_of_episodes` → `400`, fechando a brecha de um cliente inflar
`finaleEpisodeNumber`/`seasonFinaleEpisodeNumbers` explicitamente muito além do que a série realmente
tem. O caminho auto-derivado do TMDB (sem override explícito) nunca precisa dessa verificação, já que
já é sempre baseado em dado real do TMDB. 4 testes novos em `DiaryEntryServiceImplTest` (rejeição e
sucesso verificado, pra `SEASON` e `SERIES`); suíte completa: 156 testes na classe, sem falhas.
`openapi.yaml`/`business-rules.md`/`business-rules-summary.md` atualizados junto.

## 2026-09-03 (2) — `POST /diary/bulk` de SERIES deriva genres/releaseYear/countries do TMDB sozinho

`content.genres`/`releaseYear`/`countries` no `POST /diary/bulk` passaram a ser rejeitados com `400`
só quando `type = SEASON` (nunca fazem sentido pra uma temporada) — pra `type = SERIES`, o backend
agora deriva os três sozinho do TMDB (`DiaryEntryServiceImpl.backfillSeriesMetadataIfNeeded`, chamado
uma vez no início de `bulkLogSeries`), mesmo padrão de confiança já usado por `runtimeMinutes` no
mesmo endpoint. Motivado por uma sessão de discussão sobre a experiência do cliente: forçar uma
chamada separada a `POST /contents/reference` só pra registrar esses três campos (a alternativa
documentada antes desse fix) era um atrito real sem necessidade, já que o próprio backend já tem
acesso ao TMDB pra essa mesma série. Duas alternativas client-supplied foram descartadas por
motivo de integridade de dado: rejeitar em divergência (`409`) entrincheira pra sempre o primeiro
valor registrado, mesmo se errado; sobrescrever em divergência reabriria a brecha de qualquer usuário
corromper silenciosamente dado compartilhado que a decisão de 2026-08-28 já tinha fechado (`Content`
é compartilhado entre todos os usuários). `backfillSeriesMetadataIfNeeded` checa primeiro se o
`Content` `SERIES` já tem os três campos preenchidos (`ContentRepository.findByTmdbIdAndType`) antes
de chamar `TmdbClient.getTvFullDetails` — como `Content` é compartilhado, isso converge pra no máximo
uma chamada real ao TMDB por série, pra sempre, não por usuário nem por bulk. É best-effort: TMDB
indisponível ou conflito com um valor já registrado (`ConflictException`) são só ignorados, nunca
derrubam o log das entradas de diário — diferente de `runtimeMinutes`, que segue obrigatório
(`502` se a busca falhar) e sobrescreve em divergência em vez de ignorar. 4 testes novos em
`DiaryEntryServiceImplTest` (backfill populado, pulado quando já preenchido, TMDB indisponível não
quebra o bulk, conflito não quebra o bulk); suíte completa: 2131 testes, sem falhas.
`openapi.yaml`/`business-rules.md`/`business-rules-summary.md`/`telas-reqs.md` atualizados junto.

## 2026-09-03 (3) — `ContentService.getOrCreateReference` passa a verificar existência no TMDB

Motivado por uma auditoria pedida pelo usuário: nenhum caminho de escrita do app verificava se um
`tmdbId`/`seriesTmdbId` informado pelo cliente correspondia a algo real no TMDB — qualquer string
virava uma linha de `Content` permanente e compartilhada entre todos os usuários. Auditoria achou 6
pontos afetados (`createDiaryEntry`, `UserListItemServiceImpl.addItem`/`addItems`,
`Top5EntryServiceImpl`/`WatchlistEntryServiceImpl`/`DroppedEntryServiceImpl`), todos convergindo no
mesmo `getOrCreateReference`. `ContentServiceImpl.assertExistsOnTmdb` (novo) roda logo depois de
`findExisting` devolver vazio, antes do bloco de criação — nunca roda pra uma referência já
existente. `MOVIE`/`SERIES` verificam o próprio `tmdbId` (`getMovieFullDetails`/`getTvFullDetails`);
`SEASON`/`EPISODE` verificam só o `seriesTmdbId` via `getTvFullDetails` — a existência do
`seasonNumber`/`episodeNumber` específico continua sem checagem, decisão deliberada de escopo (fica
pra uma iteração futura). Idioma fixo `en-US` pra essa checagem, já que `getOrCreateReference` não
tem acesso ao `preferredLanguage` de quem chama.

Exigiu refatorar `TmdbClient`: os 4 métodos `get*FullDetails` passaram de `Optional<T>` pra um novo
`TmdbLookupResult<T>` (sealed interface `Found`/`NotFound`/`Unavailable`) — um 404 confirmado do
TMDB e uma indisponibilidade real (timeout, 5xx, rede) eram indistinguíveis antes, os dois viravam
`Optional.empty()`. `callWithRetry` não retenta mais um 404 (não tem por quê). Todo caller
pré-existente (`ContentDetailsServiceImpl`, `DiaryEntryServiceImpl`) ganhou um `.toOptional()`
mecânico pra manter o comportamento antigo — `NotFound`/`Unavailable` continuam colapsando pra `502`
sem distinção nesses pontos, só `assertExistsOnTmdb` usa a distinção de verdade
(`NotFoundException`/`404` vs `TmdbUnavailableException`/`502`). Efeito colateral positivo
encontrado no caminho: o cache Caffeine (`unless`) antes guardava uma indisponibilidade transitória
pela TTL inteira de 24h por acidente — Spring desembrulha `Optional` antes de avaliar `unless`,
então `#result == null` só funcionava por coincidência; `TmdbLookupResult` não é desembrulhado, e
`#result.isUnavailable()` corrige isso de propósito.

Descoberta no meio do caminho: só 2 dos 16 testes de integração de controller mockavam `TmdbClient`
(`ContentControllerIntegrationTest`, `DiaryEntryControllerIntegrationTest`) — os outros 14 usavam o
bean real, apontando pra API real do TMDB, e nunca dependiam de rede porque `getOrCreateReference`
nunca chamava o TMDB antes dessa mudança. Adicionado `@MockitoBean TmdbClient` + stub padrão
(`Found`) em `Top5Entry`/`Watchlist`/`Dropped`/`Comment`/`Like`/`UserList`/`UserListItem`
`ControllerIntegrationTest` (os 7 que de fato criam `Content` via endpoint — `Feed`/`Summary`/
`User`/`Notification`/`Follower`/`FollowedPerson`/`Auth` não precisaram, criam fixtures direto via
repositório ou nunca tocam `Content`). 10 testes novos (`ContentServiceImplTest`: NotFound/Unavailable
por tipo + skip quando já existe; `ContentControllerIntegrationTest`: 404/502 end-to-end;
`TmdbClientTest`/`TmdbClientCachingTest`: NotFound sem retry, NotFound cacheado, Unavailable nunca
cacheado); suíte completa: 2141 testes, sem falhas.
`openapi.yaml`/`business-rules.md`/`business-rules-summary.md` atualizados junto.

## 2026-09-03 (4) — `finaleEpisodeNumber`/`finaleSeasonNumber` explícitos passam a ser fallback validado, não override cego

Fechamento de uma brecha achada numa conversa sobre os pontos onde o backend confia em valor
client-supplied: `resolveSeasonFinaleEpisodeNumber`/`resolveSeriesFinaleSeasonNumber`
(`DiaryEntryServiceImpl`) davam prioridade ao valor explícito do cliente mesmo quando o TMDB já sabia
responder sozinho, e nunca comparavam esse valor contra a contagem real de episódios/temporadas que a
mesma resposta do TMDB já trazia — `finaleEpisodeNumber: 100` numa temporada real de 10 episódios
passava direto, criando 100 `Content` do tipo `EPISODE`, a maioria fake.

Invertida a prioridade nos dois métodos: (1) finale já confirmado no banco continua vencendo sempre;
(2) a derivação automática do TMDB (`airedEpisodeCount`/`latestAiredSeasonNumber`) agora é tentada
antes do valor explícito — se o TMDB souber responder, esse valor vence e o explícito é ignorado; (3)
o explícito só é usado como fallback quando o TMDB não souber responder, e nesse caso é validado
contra `realSeasonEpisodeCount`/`realSeasonCount` (contagem de `episodes[]`/`seasons[]` que a mesma
resposta do TMDB já retornou, independente de `airDate`), rejeitando com `400` um valor acima dela
quando conhecida.

Cuidado extra em `resolveSeriesFinaleSeasonNumber`: a inversão fazia `getTvFullDetails` passar a ser
chamado sempre (antes só quando o valor vinha omitido), o que teria transformado um caminho antes
resiliente a TMDB fora do ar (explícito bypassava o TMDB inteiramente) numa dependência rígida dele.
Corrigido pra só lançar `TmdbUnavailableException`/`502` quando o TMDB está indisponível **e** nenhum
valor explícito foi enviado — com um explícito em mãos, o bulk log continua funcionando mesmo com o
TMDB fora do ar (sem checagem de teto nesse caso, já que não há contagem real disponível).

Um teste existente (`shouldNotCallTmdbForSeriesMetadataWhenContentAlreadyHasGenresReleaseYearAndCountries`)
dependia do comportamento antigo de nunca chamar `getTvFullDetails` com um `finaleSeasonNumber`
explícito — renomeado e ajustado pra verificar só que o backfill de metadata não roda (a chamada a
`getTvFullDetails` em si passou a ser esperada, agora por causa da resolução de finale). 5 testes novos
em `DiaryEntryServiceImplTest`: prioridade do TMDB sobre o explícito (`SEASON` e `SERIES`), rejeição
`400` quando o explícito ultrapassa a contagem real conhecida (`SEASON` e `SERIES`), e `502` quando o
TMDB está indisponível sem finale no banco e sem explícito. `business-rules.md`/
`business-rules-summary.md` atualizados junto.

## 2026-09-03 (5) — `runtimeMinutes`/`genres`/`releaseYear`/`countries` deixam de ser client-supplied em qualquer caminho

Fechamento de um gap de segurança de dados encontrado em conversa com o usuário: nenhum dos quatro
campos de metadata client-supplied em `Content` (`ContentServiceImpl.assertNoMetadataMismatch`) tinha
verificação real contra o TMDB — a trava de `409` em reenvio divergente protegia contra uma *segunda*
submissão errada sobrescrever a primeira, mas nunca contra a primeira submissão em si estar errada.
Como `Content` é uma linha compartilhada entre todos os usuários que referenciam o mesmo `tmdbId`, um
valor incorreto gravado por qualquer usuário autenticado inflava `totalMinutesWatched`/`genreCounts`
permanentemente pra todo mundo.

`ContentServiceImpl.validate` passou a rejeitar com `400` qualquer valor não-nulo de
`runtimeMinutes`/`genres`/`releaseYear`/`countries` vindo do cliente, pra `MOVIE`/`SERIES`/`SEASON`
(já rejeitava antes) e, condicionalmente, `EPISODE`:

- **`MOVIE`/`SERIES`**: os quatro campos (três pra `SERIES`, que não tem `runtimeMinutes` próprio)
  passam a ser sempre extraídos da mesma resposta `getMovieFullDetails`/`getTvFullDetails` que
  `ContentServiceImpl` já chama pra verificar que o `tmdbId` existe no TMDB (`resolveNewContentMetadata`,
  renomeado a partir do antigo `assertExistsOnTmdb`) — nenhuma chamada TMDB nova em relação ao que já
  existia. Uma referência já existente que ainda não tem os campos é preenchida da mesma forma numa
  chamada posterior (`backfillMissingTmdbMetadata`), best-effort — TMDB indisponível ou uma referência
  já totalmente preenchida não gera nenhuma chamada/erro.
- **`EPISODE`**: só `runtimeMinutes` se aplica. `ContentService.getOrCreateReference(dto,
  trustedRuntimeMinutes)` — overload já existente — ganha um segundo uso: quando `trustedRuntimeMinutes
  = true` (só `DiaryEntryServiceImpl.bulkLogSeason`/`bulkLogSeries`, que já têm o runtime calculado a
  partir da temporada que buscam por outro motivo), o valor passa direto, sem chamada nova. Quando
  `false` (`POST /contents/reference` direto, `POST /diary` de uma entrada individual — os únicos
  caminhos que antes aceitavam o campo do cliente), o cliente não pode mais enviar o campo, e
  `ContentServiceImpl` faz uma chamada nova `TmdbClient.getEpisodeFullDetails` (cacheada, custa uma
  requisição real só na primeira vez que aquele episódio específico é referenciado por qualquer
  usuário) — que, de brinde, também verifica que aquele número de episódio existe de verdade, fechando
  parte do scope boundary que a checagem de existência de 2026-09-03 (item 3 acima) tinha deixado aberto
  (só verificava a série-mãe).
- **`SEASON`**: nenhum dos quatro campos se aplica, continua rejeitando todos com `400`.

Como consequência direta, `DiaryEntryServiceImpl.bulkLogSeason` (`POST /diary/bulk` de `SEASON`) parou
de aceitar `episodeRuntimeMinutes` do cliente (campo removido de `DiaryEntryBulkCreationDTO`) e passou
a derivar o runtime de cada episódio da mesma `TmdbSeasonFullDetails` que já busca incondicionalmente
pra `finaleEpisodeNumber`/validação de `watchedDate` — reabrindo, de forma definitiva, uma decisão que
tinha revertido pra client-supplied só um dia antes (2026-09-02) por achar que custaria uma chamada TMDB
extra; na prática a temporada já era buscada de qualquer forma, então o custo nunca existiu.
`DiaryEntryServiceImpl.backfillSeriesMetadataIfNeeded` (método específico do bulk de `SERIES`) foi
removido inteiramente — a mesma lógica agora é o comportamento padrão de
`ContentServiceImpl.getOrCreateReference` pra qualquer `Content` `SERIES`, então `bulkLogSeries` só
precisa de uma chamada nua no topo da função pra garantir que a referência existe.

Também corrigido, no meio dessa mudança: `ContentServiceImpl.genreNames`/`countryCodes` retornavam
`List.of()` (lista vazia) quando o TMDB não tinha genres/countries pra um título, em vez de `null` — o
resto do código (`Content.getGenres()`, `hasMovieOrSeriesMetadata`) sempre tratou ausência como `null`,
não lista vazia; corrigido antes de gerar inconsistência.

`openapi.yaml` (`ContentRefCreation`, descrições de `/contents/reference`/`/diary`/`/diary/bulk`,
schema `DiaryEntryBulkCreation`), `business-rules.md`, `business-rules-summary.md` e `CLAUDE.md` § Avoid
atualizados junto. `ContentServiceImplTest` e `DiaryEntryServiceImplTest` reescritos nos pontos afetados
(testes de 409-por-reenvio-divergente pra esses quatro campos removidos, já que não são mais alcançáveis
via API pública; testes novos de derivação/backfill via TMDB no lugar). Duas integrações
(`UserListControllerIntegrationTest`, `UserListItemControllerIntegrationTest`) precisaram de um mock
default novo pra `getEpisodeFullDetails`, mesmo padrão já usado nas outras integrações que criam
`EPISODE`.

## 2026-09-03 (6) — `isSeasonFinale`/`isSeriesFinale` e validação de `watchedDate` contra lançamento no log unitário de `POST /diary`

Pergunta do usuário: a auto-completude de temporada/série ao logar episódio por episódio (`POST /diary`,
fora do bulk) ainda dependia do cliente enviar `isSeasonFinale`/`isSeriesFinale` explicitamente? Sim —
`DiaryEntryServiceImpl.maybeCompleteSeason`/`maybeCompleteSeries` só fazem lookup no banco dessas duas
flags, e no log unitário elas vinham direto do `ContentRefCreationDTO` do cliente, sem nenhuma
verificação contra o TMDB (diferente do `POST /diary/bulk`, que já derivava as duas sozinho desde a
mudança de "finaleEpisodeNumber vira fallback" do mesmo dia).

Fechado reaproveitando a mesma chamada que precisava ser feita de qualquer forma: pra um `EPISODE`,
`DiaryEntryServiceImpl.resolveContentRefForCreation`/`withDerivedEpisodeFinaleFlags` buscam
`TmdbClient.getSeasonFullDetails` e comparam o `episodeNumber` logado contra `airedEpisodeCount`
(mesma função já usada pelo bulk) — se bate, `isSeasonFinale=true` é derivado e sobrescreve qualquer
valor enviado pelo cliente (inclusive um `true` que o TMDB contradiga, que vira `null`); `isSeriesFinale`
só é derivado quando a temporada também bate com `latestAiredSeasonNumber` (`getTvFullDetails`,
`deriveSeriesFinaleFlag`). Quando o TMDB não tem nenhum episódio exibido pra aquela temporada
(`airedEpisodeCount == 0` — temporada anunciada mas não estreada, ou indisponível), o valor do cliente é
preservado sem alteração, mesmo fallback que o bulk já usa pro mesmo cenário.

A mesma busca de temporada resolveu de graça uma segunda pergunta do usuário: dava pra verificar se o
episódio já foi lançado? Sim — a resposta já traz o `airDate` de cada episódio, então
`assertWatchedDateNotBeforeRelease` (existente, só usada no bulk até então) passou a ser aplicada também
no log unitário, contra o `airDate` do episódio específico sendo logado. Terceira pergunta, sobre
filmes: mesmo gap existia lá (documentado desde a mudança de `watchedDate` anterior no mesmo dia) — pra
`MOVIE`, `DiaryEntryServiceImpl` passou a sempre chamar `TmdbClient.getMovieFullDetails` (cacheado) e
validar `watchedDate` contra `releaseDate`. Diferença importante em relação ao bulk: aqui a chamada TMDB
passa a acontecer sempre, mesmo pra `Content` já existente que antes não pagava nenhum custo de TMDB no
log — troca deliberada de custo por essa validação existir nesses dois caminhos. `PATCH /diary/{id}`
ganhou a mesma checagem de `watchedDate` (`DiaryEntryServiceImpl.resolveReleaseDate`, reaproveitado dos
dois casos acima), com `SEASON`/`SERIES` explicitamente pulados (marcadores sintéticos, sem data própria
que faça sentido validar).

7 testes novos em `DiaryEntryServiceImplTest`: derivação de `isSeasonFinale`/`isSeriesFinale` (episódio
bate/não bate com o último aired), fallback pro valor do cliente quando o TMDB não tem dado, rejeição de
`watchedDate` antes do lançamento pra `EPISODE`/`MOVIE` em `POST /diary`, e a mesma rejeição em
`PATCH /diary/{id}`. `DiaryEntryServiceImplTest` e `UserListControllerIntegrationTest` (única integração
afetada fora de `DiaryEntryControllerIntegrationTest`, via seu helper `logEpisode`) precisaram de um
mock default novo pra `getMovieFullDetails`/`getSeasonFullDetails`, mesmo padrão já usado nas outras
integrações que criam `EPISODE`/`MOVIE`. `business-rules.md`, `business-rules-summary.md` e `CLAUDE.md`
§ Avoid atualizados junto.

## 2026-09-03 (7) — `genreCounts` do perfil separado por MOVIE/SERIES

Pedido do usuário: separar `genreCounts` de `UserResponseDTO`/`PublicUserProfileDTO` por tipo (MOVIE e
SERIES), em vez de uma única lista combinada.

`UserResponseDTO`/`PublicUserProfileDTO.genreCounts` virou dois campos, `genreCountsMovies` e
`genreCountsEpisodes` — mesma convenção de nomes já usada por `AllTimeStatsResponseDTO` (ver Summary,
2026-08-28). `UserServiceImpl.computeProfileStats` passou a chamar
`DiaryEntryRepository.countDistinctTitlesByGenreAndUserIdForMovies`/`...ForSeries` (as mesmas queries já
usadas por `SummaryServiceImpl.computeGenreCounts` pro All Time Stats) em vez da antiga
`countDistinctTitlesByGenreAndUserId` combinada, que foi removida do repositório inteiramente — não
sobrou nenhum outro chamador depois da troca. `UserMapper` ganhou o segundo parâmetro `List<GenreCountDTO>`
nas duas assinaturas afetadas.

Os 7 testes de repositório da query combinada removida foram portados para `...ForMovies`/`...ForSeries`
(casos de borda que ainda não tinham cobertura ali: múltiplos gêneros, rewatch de filme, filme/série sem
gênero, dedupe entre log direto em `SERIES` e episódios da mesma série) em vez de simplesmente
descartados. `UserServiceImplTest`, `UserMapperTest`, `UserControllerTest` e
`UserControllerIntegrationTest` atualizados pras novas assinaturas/campos. `openapi.yaml`
(`WatchTimeStats`, referência cruzada em `Summary`), `business-rules.md` e `business-rules-summary.md`
atualizados junto.

## 2026-09-03 (8) — Unificação da semântica de `genreCounts` entre perfil, Summary, All Time Stats e Home

Auditoria encontrou que os campos de `genreCounts` do perfil (`UserResponseDTO`/
`PublicUserProfileDTO`, item (7) acima), do `GET /users/{userId}/summary` (e das variantes Month/Year
in Review) e do `AllTimeStatsResponseDTO`/`HomeSummaryResponseDTO` tinham duas semânticas diferentes,
não documentadas como diferentes, escondidas atrás de nomes parecidos: os campos "`...Episodes`"
(windowed e all-time) contavam cada `DiaryEntry` `EPISODE` individualmente — um rewatch de uma
temporada com 10 episódios somava +10 num gênero em vez de +1 — enquanto os campos de perfil e de
`/summary` deduplicavam por título distinto tanto pra `MOVIE` quanto pra `SERIES` (um rewatch de
filme nunca somava de novo em lugar nenhum).

A semântica foi unificada nos seis lugares (perfil, `/summary`, Month in Review, Year in Review,
All Time Stats e Home Summary): `MOVIE` agora conta cada `DiaryEntry` (rewatch soma de novo, sempre);
`SERIES` conta séries distintas **iniciadas** por gênero — `EPISODE` ou um log direto em `SERIES`
contam, mas um rewatch de uma série já iniciada não soma de novo. Toda referência a "`...Episodes`"
no nome do campo virou "`...Series`", já que o campo sempre contou séries (nunca episódios
individuais) e o nome antigo sugeria o contrário: `genreCountsEpisodes`→`genreCountsSeries` em
`UserResponseDTO`/`PublicUserProfileDTO`/`AllTimeStatsResponseDTO`,
`genreCountsEpisodesLast30Days`→`genreCountsSeriesLast30Days` em `HomeSummaryResponseDTO`. No
repositório, `DiaryEntryRepository.countDistinctTitlesByGenreAndUserIdForSeriesAndWatchedDateBetween`
foi adicionada (query windowed de série por título distinto, nova, usada por Month in Review, Year in
Review e pela Home); `countDistinctTitlesByGenreAndUserIdForMovies` (a antiga query all-time de filme
por título distinto, sem `AndWatchedDateBetween`) e `countEntriesByGenreAndUserIdForSeries`/
`...AndWatchedDateBetween` (as antigas queries de série por episódio individual, all-time e windowed)
foram removidas do repositório — não sobrou nenhum chamador depois da unificação.

`openapi.yaml` (schemas `WatchTimeStats`, `Summary`, `MonthInReview`, `YearInReview`, `AllTimeStats`,
`HomeSummary`) e `business-rules.md` (§ User, § Summary, § Summary Home) atualizados junto.

## 2026-09-04 — Eliminação de chamadas TMDB duplicadas em `POST /diary` (entrada única)

Auditoria (`docs/pending/tmdb-request-audit-2026-09-04.md`) encontrou que `createDiaryEntry` fazia duas
chamadas reais ao TMDB pro mesmo `tmdbId`/`seriesTmdbId` quando o conteúdo ainda não existia como
`Content`: uma pra validar `watchedDate` contra a data de lançamento (no idioma do usuário) e outra
dentro de `ContentServiceImpl.getOrCreateReference` (fixada em `en-US`) pra verificar existência e
derivar `genres`/`releaseYear`/`countries`/`runtimeMinutes` — o cache do `TmdbClient` é chaveado por
`(id, idioma)`, então as duas nunca reaproveitavam uma da outra a menos que o usuário usasse `en-US`.

Duas correções, uma por causa raiz:

- `TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE` (nova constante pública, `en-US`) substituiu a
  antiga `ContentServiceImpl.EXISTENCE_CHECK_LANGUAGE` privada — agora compartilhada entre
  `ContentServiceImpl` e `DiaryEntryServiceImpl`. `DiaryEntryServiceImpl.resolveReleaseDate` (usado
  tanto na criação quanto em `updateDiaryEntry`) passou a sempre consultar o TMDB nessa língua fixa em
  vez do idioma preferido do usuário — `releaseDate` não varia por idioma no TMDB, então a segunda
  chamada (dentro de `getOrCreateReference`) agora bate na mesma chave de cache da primeira. Fecha a
  duplicidade pra `MOVIE` (e pra atualização de `watchedDate` em `EPISODE`).
- Pra `EPISODE`, a duplicidade era de endpoint, não de idioma (`getSeasonFullDetails` na criação vs.
  `getEpisodeFullDetails` dentro de `getOrCreateReference`), então normalizar idioma sozinho não
  resolvia. `DiaryEntryServiceImpl.withDerivedEpisodeFinaleFlags` já busca a temporada inteira (que já
  traz o `runtime` de cada episódio); passou a extrair o `runtime` do episódio sendo logado dali mesmo
  e propagar isso com `trustedRuntimeMinutes=true` até `contentService.getOrCreateReference`, mesmo
  padrão que `bulkLogSeason`/`bulkLogEpisode` já usavam. `resolveContentRefForCreation` e
  `withDerivedEpisodeFinaleFlags` passaram a retornar um record interno `ResolvedContentRef`
  (`ContentRefCreationDTO` + `boolean trustedRuntimeMinutes`) em vez de só o DTO, pra carregar essa
  flag até o call site em `createDiaryEntry`. Quando a temporada não tem o `runtime` do episódio (TMDB
  ainda não anunciou), cai de volta pro comportamento antigo (`trustedRuntimeMinutes=false`,
  `getOrCreateReference` refaz a chamada por episódio).

Pior caso (episódio novo, de temporada nova, que também é final de temporada) caiu de até 4 chamadas
TMDB pra 2; filme novo caiu de 2 chamadas pra 1. 3 testes novos em `DiaryEntryServiceImplTest`: runtime
confiável derivado da temporada, fallback pra não confiável quando a temporada não tem o dado, e
verificação de que a checagem de `releaseDate` de filme usa `en-US` mesmo com usuário preferindo outro
idioma. Nenhuma mudança de contrato público — `ContentServiceImpl` já usava `en-US` internamente antes
disso, só não compartilhava a constante.

## 2026-09-04 (2) — Proteção contra cache stampede nas 4 chamadas TMDB cacheadas

Ponto 2 da mesma auditoria: as 4 chamadas cacheadas do `TmdbClient` (`getMovieFullDetails`,
`getTvFullDetails`, `getSeasonFullDetails`, `getEpisodeFullDetails`) não tinham proteção contra cache
stampede — duas requisições concorrentes pedindo a mesma chave (`tmdbId`/idioma) ainda sem cache
disparavam a chamada real ao TMDB cada uma, em vez de uma esperar a outra.

A correção sugerida pela auditoria (`sync = true` no `@Cacheable`) foi testada e descartada: o Spring
falha em runtime com `IllegalStateException: A sync=true operation does not support the unless
attribute` — as 4 anotações usam `unless = "#result.isUnavailable()"` (regra de 2026-09-03, que impede
cachear uma indisponibilidade transitória do TMDB por 24h) e o Spring rejeita a combinação das duas.

Solução: as 4 chamadas deixaram de usar `@Cacheable`/`CacheManager` do Spring e passaram a usar
`com.github.benmanes.caffeine.cache.Cache<String, TmdbLookupResult<X>>` diretamente
(`TmdbClient.cachedLookup`), injetado como bean via `TmdbCacheConfig` (4 beans, um por tipo, com TTL
configurável via `app.tmdb.details-cache-ttl-hours`, mesma propriedade de antes). `Cache.get(key,
mappingFunction)` do Caffeine já é atômico por chave (bloqueia chamadas concorrentes pra mesma chave
até a primeira terminar — a mesma proteção que `sync = true` daria) e permite retornar `null` da função
de carga pra sinalizar "não cachear" — usado exatamente pro caso `isUnavailable()`, preservando a regra
de 2026-09-03 sem depender do `unless` do Spring. `@EnableCaching` removido (não sobrou nenhum outro uso
de cache do Spring no projeto).

Teste novo em `TmdbClientCachingTest` dispara 8 chamadas concorrentes pra mesma chave (com resposta
mockada propositalmente lenta) e verifica que só uma chegou de verdade ao servidor mockado — sanity-check
manual confirmou que o teste falha (6 das 8 chamadas escapavam) com uma implementação ingênua de
check-then-act antes da correção. `TmdbClientCachingTest`/`TmdbClientTest` também precisaram trocar a
injeção do antigo `CacheManager` pelos 4 beans `Cache` novos.

## 2026-09-05 — Job diário para de rechecar conteúdo terminal e passa a manter o runtime total de série

Continuação da auditoria de chamadas TMDB, agora sobre `ContentTrackingJob`/`ContentTrackingServiceImpl`
e sobre `GET /contents/{id}/details` de série (`ContentDetailsServiceImpl.fetchAllSeasonsInParallel`,
que buscava todas as temporadas só pra calcular `totalRuntimeMinutes`/`runtimeMinutes`).

Três mudanças, cada uma em commit próprio:

1. **`ContentTrackingServiceImpl` para de rechecar TMDB pra conteúdo com status terminal todo dia** —
   `processTrackedContent` consulta só o status conhecido (novo método
   `TrackedContentStateRepository.findLastKnownStatusByContentId`, projeção leve) antes de decidir se
   chama o TMDB; se já é `Released`/`Canceled` (`MOVIE`) ou `Ended`/`Canceled` (`SERIES`) — constantes
   agora públicas em `ContentChangeDetector` —, pula sem nenhuma chamada. Conteúdo sem
   `TrackedContentState` ainda continua sendo checado normalmente (baseline).
2. **`RELEASE`/`ANNOUNCED_DATE` passam a ser emitidos também por série, reaproveitando os
   `NotificationType` já existentes** (sem enum novo, sem mudança em `openapi.yaml`): `RELEASE` na
   transição de pré-lançamento (`Planned`/`In Production`/`Pilot`) pra `Returning Series`/`Ended`
   (fechava uma lacuna — hoje só existia via `NEW_EPISODE` incidental); `ANNOUNCED_DATE` (agora com
   `seasonNumber` no evento) quando a temporada de maior número ganha uma data futura conhecida.
   `TmdbTvDetails` passou a expor `seasons` (mesmo endpoint `/tv/{id}`, sem custo extra), e
   `TrackedContentState` ganhou `lastKnownSeasonNumber`/`lastKnownSeasonAirDate` (migração V42) pra essa
   comparação.
3. **`totalRuntimeMinutes`/`runtimeMinutesEpisodeCount` (`Content`, só `SERIES`, migração V43) passam a
   ser mantidos incrementalmente pelo backend**, fora do pipeline de metadado write-once já existente
   (`ContentRefCreationDTO`/`assertNoMetadataMismatch`) — são mutáveis por natureza, nunca
   client-supplied. `ContentDetailsServiceImpl.buildSeriesDetails` só pula a busca de todas as
   temporadas quando o status fresco já é terminal e já existe baseline salvo (busca só as 2 temporadas
   mais recentes pra `recentEpisodes`); em qualquer outro caso busca tudo como antes e persiste o total
   calculado. `ContentTrackingServiceImpl.processSeries` incrementa o total só com o episódio novo
   quando `NEW_EPISODE` é detectado (`getEpisodeFullDetails`, 1 chamada), e recalcula tudo do zero no
   instante em que a série transiciona pra `Ended`/`Canceled` (corrige drift antes de congelar). Revival
   de série congelada é detectado de forma lazy, via `ContentDetailsServiceImpl` chamando o novo
   `ContentTrackingService.reactivateAfterRevival` sempre que os detalhes são pedidos de novo — única
   dependência do pacote `content` sobre `notification` no projeto, decisão deliberada.

Bug real encontrado durante o desenvolvimento do item 3: `saveSeriesState` muta o mesmo objeto
`TrackedContentState` recebido como "estado anterior" (`setLastKnownStatus`); guardar uma referência a
esse objeto pra comparar "status anterior vs. status fresco" *depois* de `saveSeriesState` já ter
rodado sempre comparava o status fresco contra ele mesmo — corrigido capturando o status anterior numa
`String` antes de `saveSeriesState` ser chamado, coberto por teste
(`shouldRecalculateRuntimeFromScratchBeforeFreezingWhenSeriesTransitionsToEnded`).

22 testes novos entre `ContentTrackingServiceImplTest`/`ContentChangeDetectorTest`/
`ContentDetailsServiceImplTest`; suíte completa (2179 testes) e migrações V42/V43 validadas contra
Postgres real via Testcontainers. `docs/context/database-schema.html`, `business-rules.md`,
`business-rules-summary.md` e `CLAUDE.md` (seção Avoid, agora com 7 exceções em vez de 6) atualizados
junto.

Item 4 da mesma auditoria também corrigido: `ContentTrackingServiceImpl.notifyWatchers` e
`FollowedPersonTrackingServiceImpl.notifyFollowers` faziam um `notificationRepository.save(...)` por
usuário notificado (`userIds.forEach(...)`) — trocado por montar a lista via
`stream().map(...).toList()` e um único `saveAll(...)`, eliminando N round-trips ao banco por evento
de notificação em massa. Testes existentes atualizados para capturar `List<Notification>` em vez de
uma `Notification` por invocação.

## 2026-09-05 (2) — `DELETE /users/me` quebrava com 500 se o usuário era companion em diário alheio

Item 1 (alta severidade) de `docs/pending/audit-completa-2026-09-04.md`. `fk_watch_companions_user`
(`watch_companions.user_id → users.id`, criada em `V35`) era a única FK pra `users` em todo o schema
sem `ON DELETE CASCADE` — as outras 15+ já tinham. Um usuário marcado como "assistido com" no diário de
outra pessoa (sem nunca precisar logar nada no próprio diário) não conseguia apagar a própria conta: a
constraint barrava o delete, `UserServiceImpl.deleteAccount` não tratava
`DataIntegrityViolationException`, e caía no catch-all genérico do `GlobalExceptionHandler` → `500`.

Corrigido via `V44__add-cascade-delete-to-watch-companions-user-fk.sql` (drop + recria a constraint com
`ON DELETE CASCADE`), em vez de tratar a exceção no service — mesmo raciocínio de domínio já aplicado ao
FK de `diary_entry_id`: se um dos dois participantes do "assistimos juntos" some, o registro sozinho não
faz mais sentido. Novo teste de integração
(`UserControllerIntegrationTest.shouldDeleteAccountAndCascadeWatchCompanionRowWhenUserWasTaggedAsACompanionInAnotherUsersDiaryEntry`)
prova o cenário: usuário B marcado como companion na `DiaryEntry` de A consegue `DELETE /users/me` com
`204`, a linha de `watch_companions` some (cascade) e a `DiaryEntry` de A permanece intacta. Migração
validada contra Postgres real via Testcontainers. `business-rules.md`/`business-rules-summary.md`
atualizados junto.

## 2026-09-05 (3) — Lockout de login por conta era contornável rotacionando IP

Item 2 (média severidade) de `docs/pending/audit-completa-2026-09-04.md`. `AuthController.buildLockoutKey`
combinava IP + identifier (`"login|" + request.getRemoteAddr() + "|" + identifier`) — um atacante fazendo
brute-force distribuído contra uma conta específica (IPs diferentes: proxies, botnet, VPN) nunca acionava
o lockout daquela conta, já que cada IP tinha contador de tentativas falhas independente. Inconsistente
com o próprio código: `UserController.lockoutKey` (usado em patch/delete de conta) já usava só o
identificador, sem IP.

Corrigido removendo o componente de IP — a chave agora é só `"login|" + identifier.trim().toLowerCase()`.
`requestThrottler` por IP (rate limit de requisições totais por IP, independente de credenciais) continua
existindo sem mudanças, é uma camada complementar, não o lockout por conta. Teste de integração que antes
documentava o bypass (`shouldNotBlockADifferentIpWhenAnotherIpIsRateLimited`) foi invertido pra provar o
comportamento corrigido (`shouldStillBlockADifferentIpWhenSameIdentifierIsRateLimited`); teste unitário
correspondente em `AuthControllerTest` também atualizado. Suíte completa validada contra Postgres real via
Testcontainers.

## 2026-09-05 (4) — `forward-headers-strategy` habilitado no template de prod

Item 3 (média severidade) de `docs/pending/audit-completa-2026-09-04.md`.
`server.forward-headers-strategy=native` estava comentado em `application-prod.properties` junto com o
resto do arquivo (um template só, nenhuma variável de prod de fato ativa — decisão maior e já rastreada
em `docs/pending/pending-to-deploy.md` item 1). Sem essa flag, atrás de um reverse proxy/load balancer
(cenário normal pra terminar HTTPS), `request.getRemoteAddr()` sempre retorna o IP do proxy — todo
rate-limit por IP (`AttemptLockout`, `RequestThrottler`: login, registro, oauth, refresh) vira um contador
global compartilhado por todos os usuários atrás do mesmo proxy, em vez de um limite por cliente.

Corrigido descomentando só essa linha, antecipadamente ao resto do template — diferente das outras
(`DB_URL`, `app.jwt.secret`, etc.), essa não depende de segredo/variável de ambiente, então não fazia
sentido esperar a decisão maior de "virar config real" pra fechar esse gap específico. Comentário acima
da linha reescrito pra deixar explícito que a suposição "atrás de proxy" agora é o padrão assumido em
prod (quem fizer deploy sem reverse proxy precisa remover a linha, não só recomentar).
`docs/pending/pending-to-deploy.md` (item 7) atualizado com uma nota sobre isso.
