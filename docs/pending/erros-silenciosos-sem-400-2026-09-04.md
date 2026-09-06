# Erros silenciosos que deveriam retornar 400 — 2026-09-04

Busca focada pedida pelo usuário, em cima da auditoria completa do mesmo dia
(`audit-completa-2026-09-04.md`): lugares onde uma entrada/condição inválida é **engolida
silenciosamente** — a operação segue e o endpoint responde sucesso (200/201/204) — quando deveria
responder **400 Bad Request**. Não inclui exceptions viradas em 500 nem gaps de 401/403 (isso já está
no outro documento). Reaproveitei os 5 revisores da auditoria anterior, cada um relendo sua própria
área sob esse critério específico. Só investigação — nada foi implementado.

---

## Achados

### 1. ✅ CORRIGIDO (2026-09-06) — `sortDirection` aceita qualquer valor e vira `ASC` silenciosamente — `sortBy`, no mesmo request, é rigoroso

**Arquivo raiz:** `common/pagination/PageRequestFactory.java:45-47`

```java
Sort sort = (sortDirection == null || !sortDirection.equals("desc"))
        ? Sort.by(Sort.Order.asc(sortBy))
        : Sort.by(Sort.Order.desc(sortBy));
```

`sortBy` é validado com whitelist explícita antes de chegar aqui (`UserListServiceImpl.java:83-84,195-196`
lançam `BadRequestException` para valor fora da lista permitida). `sortDirection` não tem validação
nenhuma, em nenhum dos pontos de uso (`UserListServiceImpl.java:94,241,257`, todos fazendo
`"desc".equals(sortDirection)` sem tratar qualquer outro valor). Um cliente que manda
`sortDirection=DESC` (maiúsculo, forma comum), `sortDirection=descending` ou um typo qualquer não recebe
400 — a API silenciosamente ordena ascendente, dando a entender que o parâmetro foi aceito quando na
verdade foi ignorado. Alcançável via `GET /users/{userId}/lists`, `GET /users/me/lists` e
`GET /lists/{listId}` (`UserListController.java:33,70`). Como o bug está na fábrica central de
paginação, qualquer outro service que futuramente use o overload com `sortBy`/`sortDirection` herda o
mesmo problema.

**Corrigido:** `PageRequestFactory.build` agora rejeita `sortDirection` fora de `asc`/`desc`
(case-sensitive, igual ao `enum` já documentado em `openapi.yaml`) com `400`. Como
`UserListServiceImpl` tem dois caminhos que usam `sortDirection` sem passar pelo overload de 4
argumentos (a query nativa agregada de `itemsCount`/`commentsCount`, e o sort em memória de
`filterAndSortItems`), um `assertValidSortDirection` novo roda logo no início de
`getUserLists`/`getUserListById`, junto da validação de `sortBy` já existente, cobrindo os dois ramos
que herdariam o bug mesmo com a fábrica corrigida. Testes novos em `PageRequestFactoryTest` e
`UserListServiceImplTest` (`getUserLists`/`getUserListById`); `openapi.yaml` ganhou a nota explícita
"Valor desconhecido é 400" que `sortBy` já tinha.

### 2. ✅ CORRIGIDO (2026-09-06) — `Content` tipo `EPISODE` aceita `isSeriesFinale=true` sem `isSeasonFinale=true`

**Arquivo:** `content/service/impl/ContentServiceImpl.java`, método `validate()`, bloco `case EPISODE`
(~linhas 419-438)

O bloco de validação de `EPISODE` cobre `seriesTmdbId`/`seasonNumber`/`episodeNumber` obrigatórios,
`tmdbId` proibido, `genres`/`releaseYear`/`countries` proibidos e `runtimeMinutes` condicional — mas
nunca checa a relação entre `isSeasonFinale` e `isSeriesFinale`. Pela própria regra documentada em
`business-rules.md` §Content, `isSeriesFinale` num `EPISODE` só faz sentido como hint condicionado a
ele também ser o finale da temporada. Um `EPISODE` com `isSeriesFinale=true` e
`isSeasonFinale=null`/`false` é um estado logicamente contraditório — mas `POST /contents/reference`
(o único ponto de entrada onde essas duas flags ainda são client-supplied) aceita essa combinação com
`200`, sem checagem nenhuma, persistindo a linha `Content` inconsistente. Não há rede de segurança em
outra camada: a constraint de banco `ck_contents_finale_flags_by_type`
(`V14__add-content-finale-flags-by-type-check.sql`) só restringe **em qual `type`** cada flag pode ser
não-nula, não a relação entre as duas. O dado fica inerte (`maybeCompleteSeries` só olha episódios com
`isSeasonFinale=true`), mas ainda assim é um `400` que deveria ter acontecido e não aconteceu.

**Corrigido:** `validate()` agora rejeita `Boolean.TRUE.equals(dto.isSeriesFinale()) &&
!Boolean.TRUE.equals(dto.isSeasonFinale())` com `400` no bloco `case EPISODE`. Confirmado que nenhum
caminho server-derivado (`DiaryEntryServiceImpl.withDerivedEpisodeFinaleFlags`/`bulkLogEpisode`)
consegue produzir essa combinação — os dois já computam `seriesFinaleFlag` condicionado a
`isSeasonFinale` ser `true` — então a checagem só rejeita entrada de fato inconsistente vinda de
`POST /contents/reference` direto ou do fallback client-supplied de `POST /diary`. Testes novos em
`ContentServiceImplTest` (ausente e `false` explícito).

### 3. ✅ CORRIGIDO (2026-09-06) — `POST /users/me/follow-people/{personTmdbId}` aceita qualquer id numérico sem verificar existência no TMDB — sempre 204

**Arquivos:** `followedperson/service/impl/FollowedPersonServiceImpl.java:35-58` (`followPerson`) +
`validatePersonTmdbId` (linhas 68-72)

`validatePersonTmdbId` só checa formato (`personTmdbId.matches("\\d{1,20}")`) — nunca chama o TMDB pra
confirmar que a pessoa existe. Qualquer string de 1 a 20 dígitos (`"0"`, `"999999999999"` etc.) passa e
o endpoint devolve `204 No Content` como se tivesse funcionado. A única verificação real acontece dias
depois, dentro do job agendado `FollowedPersonTrackingServiceImpl.processPerson`
(`notification/service/impl/FollowedPersonTrackingServiceImpl.java:53-58`), que chama
`tmdbClient.getPersonCombinedCredits(personTmdbId)` — método antigo de `TmdbClient`
(`common/tmdb/TmdbClient.java:37-43`) que devolve `Optional<TmdbPersonCredits>`, colapsando "pessoa não
existe" (404) e "TMDB fora do ar" no mesmo `Optional.empty()`. Quando vazio, `processPerson` só faz
`if (fresh.isEmpty()) return;` — sem log, sem notificação, sem qualquer sinal visível. O usuário nunca
descobre que seguiu um id inválido. É exatamente a mesma lacuna que o time já identificou e fechou pra
`Content` (`ContentServiceImpl.getOrCreateReference` passou a verificar existência no TMDB antes de
criar referência nova, mudança de 2026-09-03 documentada no `CLAUDE.md`, justamente porque "nenhum
caminho de escrita da aplicação verificava se um `tmdbId` fornecido pelo cliente correspondia a algo
real") — o mesmo raciocínio nunca foi replicado para `personTmdbId`.

**Corrigido:** novo `TmdbClient.getPersonDetails` (`/person/{id}`, sem `append_to_response` — endpoint
leve, só existência) devolve `TmdbLookupResult<TmdbPersonDetails>`, mesmo padrão sealed
Found/NotFound/Unavailable de `getMovieFullDetails`/`getTvFullDetails`. `FollowedPersonServiceImpl.
followPerson` chama `assertPersonExistsOnTmdb` depois do early-return de idempotência (re-seguir
alguém já seguido nunca paga a chamada extra) e antes de criar a linha: `NotFound` → `404`,
`Unavailable` → `502`. Testes novos em `FollowedPersonServiceImplTest` (unitário, mock de
`TmdbClient`) e `FollowedPersonControllerIntegrationTest` (`@MockitoBean TmdbClient`, cenários 404 e
502 fim a fim).

### 4. ✅ CORRIGIDO (2026-09-06) — `preferredLanguage`/`preferredRegion` só validam formato — código inexistente falha silenciosamente em outro endpoint, dias depois

**Arquivo:** `user/dto/PatchUserDTO.java:23-26` (mesmos regexes em `PostUserDTO`, se aplicável)

```java
@Pattern(regexp = "^[a-z]{2}-[A-Z]{2}$", message = "preferredLanguage must be in the format en-US")
String preferredLanguage,
@Pattern(regexp = "^[A-Z]{2}$", message = "preferredRegion must be an ISO 3166-1 alpha-2 code, e.g. US")
String preferredRegion
```

O regex garante só o *formato* (`xx-XX` / `XX`), não que o código exista de verdade — `"zz-ZZ"`/`"ZZ"`
passam a validação e `PATCH /users/me` retorna `200`, persistindo o valor. O problema só aparece depois,
num endpoint completamente diferente: `ContentDetailsServiceImpl.watchProviders`
(`content/service/impl/ContentDetailsServiceImpl.java:534-541`) faz
`watchProviders.results().get(region)` — se `region` não existe no mapa devolvido pelo TMDB,
`regionProviders` é `null` e o método devolve `List.of()` silenciosamente, sem erro. O usuário passa a
ver "nenhum provedor de streaming disponível" pra todo conteúdo, pra sempre, sem indício de que a causa
é o código de região salvo no próprio perfil. O mesmo vale pra `preferredLanguage` usado como parâmetro
`language` nas chamadas TMDB — o TMDB não rejeita idioma desconhecido, só devolve dados no idioma
padrão, então o "erro" nunca chega como erro nenhum.

**Corrigido:** `UserServiceImpl.validatePreferredLanguage`/`validatePreferredRegion`, chamados de
`applyPatch` só quando o valor muda, validam a existência real do código via
`java.util.Locale.getISOLanguages()`/`getISOCountries()` (populados pela própria JVM, sem chamada de
rede) — um valor bem formado mas inexistente (`"zz-ZZ"`/`"ZZ"`) agora é `400`. `PostUserDTO` não tem
esses campos (não aplicável no registro). Testes novos em `UserServiceImplTest`; `openapi.yaml`
atualizado com a nota de validação em duas camadas.

### 5. ✅ CORRIGIDO (2026-09-06) — `SummaryServiceImpl.getEpisodeRatingsGrid` — `seriesTmdbId` só-espaço passa pelo `isEmpty` sem trim

**Arquivo:** `summary/service/impl/SummaryServiceImpl.java:379-381`

```java
if (StringUtils.isEmpty(seriesTmdbId)) {
    throw new BadRequestException("seriesTmdbId must be provided");
}
```

`io.micrometer.common.util.StringUtils.isEmpty` só checa `null`/`length()==0`, sem trim. Um
`seriesTmdbId` composto só de espaços passa nessa checagem, chega em
`diaryEntryRepository.findEpisodeEntriesBySeriesForUser(userId, " ")`, não encontra nada, e o endpoint
devolve `200` com `episodes: []` em vez de rejeitar com 400. Outros lugares do código
(`ContentServiceImpl.normalize`/`trimOrNull`) já fazem `trim()` antes de checar vazio — aqui não segue
o mesmo padrão. Severidade baixa (edge case de entrada só-espaço via URL), mas é exatamente o padrão
pedido.

**Corrigido:** `trim()` aplicado em `seriesTmdbId` antes da checagem `StringUtils.isEmpty`, mesmo
padrão de `ContentServiceImpl.normalize`/`trimOrNull` e `UserServiceImpl.getUsersByUsername`. Teste
novo em `SummaryServiceImplTest` (só-espaço) além do já existente (vazio).

---

## Achado adjacente (categoria oposta — sinalizado porque apareceu no caminho)

### ✅ CORRIGIDO (2026-09-06) — `@Max(100)` em `DiaryEntryBulkCreationDTO` contradiz `MAX_BULK_EPISODES=2000` do service

**Arquivo:** `diaryentry/dto/DiaryEntryBulkCreationDTO.java:18-20`

Não é "erro engolido" — é o oposto: `finaleEpisodeNumber`, `finaleSeasonNumber` e as
chaves/valores de `seasonFinaleEpisodeNumbers` continuam anotados `@Min(1) @Max(100)`, mas
`business-rules.md` §DiaryEntry documenta que `MAX_BULK_EPISODES` foi elevado para 2000 em 2026-09-03
justamente pra permitir override explícito em séries longas quando o TMDB ainda não sabe responder.
Bean Validation roda antes do service, então qualquer override acima de 100 é rejeitado com 400 de
validação genérica, nunca alcançando a lógica que validaria contra a contagem real do TMDB até 2000.
Parece um resquício esquecido da mudança 100→2000. Sinalizado à parte por não se encaixar no critério
desta busca (aqui o cliente *recebe* um 400 — só que um 400 indevido).

**Corrigido:** `finaleEpisodeNumber` e os **valores** de `seasonFinaleEpisodeNumbers` (números de
episódio, os que de fato somam contra `MAX_BULK_EPISODES`) elevados pra `@Max(2000)`.
`finaleSeasonNumber` e as **chaves** do mapa (números de *temporada*) ficaram em `@Max(100)`
deliberadamente — não entram na soma, só limitam o laço de temporadas, e nenhuma série real chega
perto de 100 temporadas. `openapi.yaml` ganhou `maximum` explícito nos três campos (nunca tinha sido
documentado antes). Dois testes de integração existentes que fixavam o limite antigo em 101
(`DiaryEntryControllerIntegrationTest`) atualizados pra 2001, provando o novo teto continua rejeitado
acima dele.

---

## Sem achados novos (conferido sob este critério, correto)

- **Auth/segurança** (`UserServiceImpl` login/patch/register, `RefreshTokenServiceImpl`,
  `GlobalExceptionHandler`): todos os campos sensíveis com Bean Validation completa e `@Valid` nos
  controllers; `RefreshTokenServiceImpl.revokeRefreshToken` retornar silenciosamente sem cookie é
  logout idempotente por design, não bug; troca de senha/email pro mesmo valor já existente virar no-op
  sem exigir `currentPassword` é comportamento documentado, não bug.
- **`comment`/`like`**: alvo resolvido pelo path do endpoint, não por DTO — não há campo que possa
  "vencer" silenciosamente sobre outro.
- **`UserListItem`**: regra "exatamente um de `content`/`childListId`", travas de aninhamento (1 nível,
  sem mixing com `Content`) e limites de `position` — todos rejeitados explicitamente com
  `BadRequestException`, nada é sobrescrito ou clampado silenciosamente.
- **`bulkLogSeason`/`bulkLogSeries`**: nenhum episódio problemático é pulado silenciosamente — qualquer
  falha propaga e aborta a transação inteira, sem sucesso parcial mascarado.
- **`NotificationController`/`SummaryController`** (fora do item 5): `isRead`/`type` inválidos já
  rejeitados via binding do Spring ou `BadRequestException` explícito; `month`/`year`
  ausente/malformado já validado. (`year` tecnicamente-válido-mas-sem-sentido — 0, negativo, gigante —
  não lança nada em lugar nenhum do código, inclusive em `DiaryEntryServiceImpl.startOfYear`; pra
  valores fora do range de `LocalDate` vira `DateTimeException` não capturado → 500. Fica de fora
  daqui por ser categoria de 500, não de "sucesso silencioso".)
