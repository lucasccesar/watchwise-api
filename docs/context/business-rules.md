# Regras de negócio implementadas

Levantamento das regras de negócio "especiais" já implementadas no código — decisões de domínio
não-óbvias que vão além de validação genérica de campo (tamanho, formato, obrigatoriedade). Não inclui
regras de features ainda não construídas (`Like`, `Notification`, `Summary`, `Search`) nem simples
constraints de tamanho/formato de DTO.

## Content

- **Campos obrigatórios dependem do `type`** (`ContentServiceImpl.validate`):
  - `MOVIE`/`SERIES`: exigem `tmdbId`; `seriesTmdbId`, `seasonNumber` e `episodeNumber` devem vir nulos.
  - `SEASON`: exige `seriesTmdbId` + `seasonNumber`; `tmdbId` e `episodeNumber` devem vir nulos.
  - `EPISODE`: exige `seriesTmdbId` + `seasonNumber` + `episodeNumber`; `tmdbId` deve vir nulo.
  - Motivo: TMDB não tem lookup direto por id para season/episode (só a rota composta
    `/tv/{seriesId}/season/{n}`), então o `id` próprio deles não serve como referência isolada.
- **Get-or-create idempotente e resistente a corrida** (`ContentServiceImpl.getOrCreateReference`/
  `resolveConcurrentCreation`): busca por `tmdbId`+`type` (ou `seriesTmdbId`+`seasonNumber`+`episodeNumber`+`type`);
  se dois requests concorrentes tentam criar a mesma referência inexistente, o `insert` que perder a
  corrida da `DataIntegrityViolationException` sempre busca de novo **primeiro** — só depois de a
  busca não achar nada é que o nome da constraint violada é mapeado para um `409` específico
  (`uq_contents_season_finale`/`uq_contents_series_finale`). Essa ordem importa: quando duas
  requisições concorrentes criam o mesmo episódio/temporada já marcado como finale, o Postgres pode
  reportar a constraint de unicidade de finale em vez da de chave natural (ordem de checagem não é
  garantida quando o mesmo `insert` viola as duas); mapear o nome antes de buscar de novo rejeitaria
  como `409` uma criação concorrente legítima e idêntica à que já existe, em vez de devolvê-la.
- **No máximo um `Content` pode reivindicar cada flag de finale** — `uq_contents_season_finale` (um `EPISODE` por temporada com `is_season_finale = true`) e `uq_contents_series_finale` (uma `SEASON` por série com `is_series_finale = true`), ambos índices únicos parciais adicionados em `V11__add-finale-flag-uniqueness-constraints.sql`. Violação vira `409` (`ContentServiceImpl.resolveConcurrentCreation`/`extractConstraintName`). Exceção: `isSeriesFinale` numa `SEASON` e `isSeasonFinale` num `EPISODE` têm cada um uma única via de mutação, espelhadas uma na outra — `ContentServiceImpl.clearPreviousSeriesFinale` transfere `isSeriesFinale` da temporada-finale antiga para a nova apenas quando a temporada nova tem número maior que o da finale já registrada (cenário de série revivida com uma temporada posterior), e `ContentServiceImpl.clearPreviousSeasonFinale` faz o mesmo pra `isSeasonFinale` entre episódios da mesma temporada (cenário de temporada que ganha um episódio extra/atrasado depois do finale antigo já ter sido logado). Em ambos os casos, se o novo número for igual ou anterior ao da finale já registrada, a criação é rejeitada com `409` em vez de transferir. Junto do backfill de `ContentServiceImpl.reconcileExisting` (ver abaixo), são os três únicos lugares em todo o código onde uma linha de `Content` já existente é alterada.
- **`clearPreviousSeasonFinale` também limpa `isSeriesFinale` do episódio demovido, não só `isSeasonFinale`**
  (`ContentServiceImpl.clearPreviousSeasonFinale`) — um `EPISODE` pode acumular as duas flags juntas
  (ele é o finale da temporada **e** o hint de que essa temporada é o finale da série). Quando um
  episódio posterior assume `isSeasonFinale`, o antigo perde as duas, não só `isSeasonFinale`: como
  `isSeriesFinale` num `EPISODE` é um hint condicionado a ele ser o finale da temporada (ver
  `CLAUDE.md` § Avoid), um episódio que deixou de ser finale de temporada não tem mais legitimidade pra
  também reivindicar finale de série — deixar a flag antiga presa nele seria dado órfão, inconsistente
  (`isSeasonFinale=false` com `isSeriesFinale=true` no mesmo episódio). Só limpa se de fato estava
  `true` (não sobrescreve um `null` já ausente). Diferente de `clearPreviousSeriesFinale`, isso não
  transfere `isSeriesFinale` pro episódio novo — o cliente precisa reenviar o hint explicitamente ali se
  quiser que ele também carregue esse status.
- **Limpar a finale antiga e criar o novo `Content` commitam atomicamente** (`ContentServiceImpl.getOrCreateReference`):
  `clearPreviousSeriesFinale`/`clearPreviousSeasonFinale` rodam dentro da mesma lambda `REQUIRES_NEW`
  (`NewTransactionExecutor`) que cria e salva o novo `Content`, não numa transação separada anterior.
  Isso evita que uma falha entre as duas escritas deixe a série/temporada sem nenhuma temporada/episódio
  marcado como finale (o que pararia `maybeCompleteSeries`/`maybeCompleteSeason` silenciosamente pra
  todo mundo que assiste esse conteúdo) — as duas escritas commitam ou dão rollback juntas,
  independentemente de `getOrCreateReference` ter sido chamado com ou sem transação ambiente
  (`POST /contents/reference` direto, ou de dentro de `POST /diary`).
- **Reenviar um `Content` já existente com um valor diferente do já salvo é rejeitado, não ignorado
  silenciosamente** (`ContentServiceImpl.assertNoMetadataMismatch`, chamado tanto no caminho normal
  quanto na recuperação de corrida em `resolveConcurrentCreation`) — se o request novo manda um valor
  não-nulo de `isSeasonFinale`/`isSeriesFinale` (os únicos dois campos ainda client-supplied — ver
  abaixo) que diverge de um valor **também não-nulo** já persistido pra aquela mesma chave natural,
  `409` em vez de devolver silenciosamente a referência antiga sem aplicar a correção. Um request que
  omite o campo (`null`) nunca conflita — só valores explicitamente diferentes. A mesma checagem ainda
  existe pra `runtimeMinutes`/`genres`/`releaseYear`/`countries`, mas como nenhum deles é mais
  client-supplied (ver regra abaixo), só dispara na prática se duas derivações do TMDB pra mesma chave
  natural discordarem entre si (o dado mudou no próprio TMDB entre duas chamadas) — um cenário raro,
  não um vetor de ataque de cliente.
- **Quando o valor já persistido é `null` (nunca foi setado), um valor novo não-nulo é gravado na
  `Content` existente em vez de gerar conflito ou ser descartado** (`ContentServiceImpl.reconcileExisting`,
  chamado nos dois mesmos pontos de `assertNoMetadataMismatch` acima) — vale pros seis campos
  (`isSeasonFinale`, `isSeriesFinale`, `runtimeMinutes`, `genres`, `releaseYear`, `countries`), cada um
  checado e preenchido independentemente dos outros na mesma chamada. Bug corrigido em 2026-08-31 pras
  duas flags de finale (sem isso, `maybeCompleteSeason`/`maybeCompleteSeries` do `DiaryEntryServiceImpl`
  nunca completam a temporada/série cujo `Content` finale foi criado antes de o cliente informar a flag).
  `isSeasonFinale`/`isSeriesFinale` reaproveitam a lógica de transferência-pra-frente
  (`clearPreviousSeasonFinale`/`clearPreviousSeriesFinale`) dentro da mesma transação `REQUIRES_NEW`, pra
  não deixar duas linhas reivindicando a mesma flag ao mesmo tempo; se a gravação perder uma corrida
  concorrente (`DataIntegrityViolationException` na constraint de unicidade de finale), devolve a
  referência existente sem aplicar o backfill em vez de propagar erro — mesmo padrão de fallback
  idempotente usado em `resolveConcurrentCreation`. Os outros quatro campos não têm constraint de
  unicidade própria, então o `catch` ali é só defensivo.
- **`runtimeMinutes`, `genres`, `releaseYear` e `countries` deixaram de ser client-supplied em
  2026-09-03 — o backend sempre os deriva do TMDB sozinho, e um cliente que os enviar recebe `400`**
  (`ContentServiceImpl.validate`, `resolveNewContentMetadata`, `backfillMissingTmdbMetadata`). Ainda
  são a 3ª, 4ª, 5ª e 6ª exceções à imutabilidade de `Content` (ver `CLAUDE.md` § Avoid), só que agora
  nenhum dos quatro chega por input de cliente — fecha um gap onde qualquer usuário autenticado podia
  gravar um valor incorreto (inflando `totalMinutesWatched`/`genreCounts` pra sempre, já que `Content`
  é compartilhado entre todos que referenciam o mesmo `tmdbId`).
  - **`MOVIE`/`SERIES`**: `genres`, `releaseYear`, `countries` (e `runtimeMinutes`, só pra `MOVIE` —
    `SEASON`/`SERIES` não têm uma duração própria única) são extraídos da mesma resposta
    `getMovieFullDetails`/`getTvFullDetails` que já é chamada pra verificar a existência do `tmdbId` no
    TMDB (`resolveNewContentMetadata`) — nenhuma chamada TMDB extra em relação ao que já existia antes
    dessa mudança. `genres` alimenta `genreCounts` (contagem de títulos, não soma de minutos);
    `releaseYear`/`countries` alimentam as agregações por década/país de Month/Year in Review e All
    Time Stats (adicionados em 2026-08-28, ver `docs/context/telas.md`); `runtimeMinutes` (`MOVIE`)
    alimenta `totalMinutesWatched`/`minutesWatchedLast30Days` (ver § User / Auth).
  - **`EPISODE`**: `runtimeMinutes` é derivado de duas formas, dependendo de quem chama
    `ContentService.getOrCreateReference(dto, trustedRuntimeMinutes)`. Chamadores internos que já
    buscaram os dados da temporada no TMDB por outro motivo (`DiaryEntryServiceImpl.bulkLogSeason`/
    `bulkLogSeries`, via `POST /diary/bulk`; e, desde 2026-09-04,
    `withDerivedEpisodeFinaleFlags`/`resolveContentRefForCreation` em `POST /diary` de uma entrada
    individual, que já busca a temporada pra derivar `isSeasonFinale`/`isSeriesFinale`) passam
    `trustedRuntimeMinutes = true` com o valor já calculado — nenhuma chamada TMDB extra. `POST
    /contents/reference` direto (sem passar por `DiaryEntryServiceImpl`), e o próprio `POST /diary`
    individual quando a temporada retornada pelo TMDB ainda não tem o `runtime` daquele episódio
    específico (episódio anunciado mas ainda sem dados completos), passam `trustedRuntimeMinutes =
    false`; nesse caso `ContentServiceImpl` faz uma chamada nova `TmdbClient.getEpisodeFullDetails`
    (cacheada, então só custa de verdade na primeira vez que aquele episódio específico é referenciado
    por qualquer usuário) — que, como efeito colateral, também verifica que aquele número de episódio
    realmente existe na temporada (`NotFoundException` se não existir), fechando um gap que antes só
    verificava a série-mãe. `genres`/`releaseYear`/`countries` continuam não aceitos em `EPISODE`
    (rejeitado com `400`) — resolvidos pelo `Content` tipo `SERIES` do mesmo `seriesTmdbId`, nunca
    próprios do episódio.
  - **`SEASON`**: nenhum dos quatro campos se aplica — continuam rejeitados com `400` se enviados
    (`SEASON` não tem duração/gênero/ano/país próprio no TMDB).
  - **Referência já existente com o campo faltando é preenchida best-effort** (`backfillMissingTmdbMetadata`)
    — se um `Content` `MOVIE`/`SERIES` já existente ainda não tem `genres`/`releaseYear`/`countries`
    (ou `runtimeMinutes`, pra `MOVIE`), ou um `EPISODE` ainda não tem `runtimeMinutes`, qualquer chamada
    posterior de `getOrCreateReference` pra essa mesma chave natural tenta buscar e preencher — mas
    silenciosamente ignora falha do TMDB (indisponibilidade) em vez de propagar erro, já que isso nunca
    deveria bloquear a ação que disparou a chamada (logar um diário, adicionar a uma lista, etc.) só
    porque um backfill de metadata deu errado.
  - `normalizeGenres`/`normalizeCountries` (trim + ordenação alfabética, `countries` também maiúsculo)
    seguem aplicados ao valor derivado do TMDB antes de salvar, mesmo motivo de antes (evitar
    falso-conflito por ordem de lista).
- **Posse das flags de finale: risco aceito, não construída proteção dedicada.** Qualquer usuário
  autenticado pode setar `isSeasonFinale`/`isSeriesFinale` via `POST /contents/reference` (direto ou
  embutido em `POST /diary`), sem verificação nem registro de quem criou a referência. Decisão
  consciente (2026-08-17): não rastrear autor nem restringir quem pode setar — não há hoje superfície de
  moderação (`Comment`/`Like`/papel de admin são fases futuras) que consumiria essa informação, e a
  flag não é um alvo com incentivo real de má-fé (não beneficia quem a define errado). O caso legítimo
  mais comum (conteúdo "cresceu" — temporada/série ganhou episódio/temporada extra) já é coberto pelas
  duas vias de transferência acima; um valor genuinamente errado colado na primeira referência continua
  sem correção, mesma limitação já aceita para qualquer campo client-supplied de `Content`.
- **Estatísticas agregadas de um conteúdo (`GET /contents/{contentId}/stats`, `GET /contents/stats`)
  só somam `DiaryEntry` de usuários com perfil público** (`ContentStatsServiceImpl.getStatsBatch`,
  `DiaryEntryRepository.findContentStatsByContentIdIn`, filtro `d.user.isProfilePublic = true`) —
  decisão deliberada: diferente da visibilidade linha-a-linha usada em `GET /contents/{contentId}/reviews`
  (dono, público, ou seguidor aceito — ver regra em DiaryEntry), uma métrica agregada e anônima
  (`averageScore`/`playsCount`/`reviewsCount`) não tem um único "dono" contra quem checar "o viewer
  segue com status aceito", então a saída mais conservadora é excluir perfis privados inteiramente da
  soma, em vez de vazar sua contribuição num número agregado. `commentsCount` não sofre esse filtro —
  `Comment` não tem visibilidade própria. Um `contentId` sem nenhuma `DiaryEntry`/`Comment` (nunca
  referenciado, ou só referenciado por perfis privados) devolve `averageScore = null` e os três
  contadores em `0`, nunca `404` — mesmo idempotente-por-ausência-de-dado usado em outras leituras
  agregadas deste projeto. `GET /contents/stats` (batch) devolve um item por id pedido, na mesma
  ordem do parâmetro `ids`, com o mesmo default zerado pra qualquer id sem estatística — capado em
  100 ids por chamada (`ContentStatsServiceImpl.MAX_BATCH_IDS`), `400` acima disso.
- **`GET /contents/{contentId}/details`/`GET /contents/details` proxyam o TMDB pelo backend — o
  cliente nunca chama o TMDB direto** (`ContentDetailsServiceImpl`, decisão de 2026-08-30): resolve
  `tmdbId` (MOVIE/SERIES) ou `seriesTmdbId`+`seasonNumber`(+`episodeNumber`) (SEASON/EPISODE) do
  `Content`, chama `TmdbClient.getMovieFullDetails`/`getTvFullDetails`/`getSeasonFullDetails`/
  `getEpisodeFullDetails` (cada um 1 única chamada HTTP, usando `append_to_response` do TMDB pra
  trazer `credits`/`aggregate_credits`, `watch/providers` e `alternative_titles` — MOVIE/SERIES —
  ou `aggregate_credits`+`watch/providers` — SEASON (2026-08-31: `aggregate_credits` também
  existe por temporada no TMDB, não só no nível da série — ver abaixo) — junto do corpo base, em
  vez de N chamadas separadas), cacheado via
  Caffeine diretamente em `TmdbClient` (`TmdbClient.cachedLookup`, TTL
  `app.tmdb.details-cache-ttl-hours` — não é mais `@Cacheable`/`CacheManager` do Spring desde
  2026-09-04, ver os dois bullets logo abaixo). Motivo: a
  `api-key` do TMDB nunca deveria estar embutida no cliente (extraível de um bundle web ou
  engenharia reversa de app); ver `docs/context/tmdb-proxy-design.md`.
  - **Chave de cache é `(tipo, tmdbId|seriesTmdbId+seasonNumber+episodeNumber, language)` —
    `region` nunca entra na chave.** O TMDB devolve `watch/providers`/`alternative_titles` com
    todas as regiões numa resposta só; o backend filtra pela `preferredRegion` do usuário **depois**
    de ler o cache, então usuários com regiões diferentes e mesmo idioma compartilham a mesma
    entrada — só falha do TMDB (`Optional.empty()`) não é cacheada, pra não travar um erro
    transitório até o TTL expirar.
  - **SEASON/EPISODE reaproveitam gêneros/países/criadores da `SERIES` do mesmo `seriesTmdbId`**
    (chamada extra a `getTvFullDetails`, também cacheada e compartilhada entre todas as
    temporadas/episódios daquela série) — TMDB não tem esses dados por temporada/episódio, mesmo
    padrão que `Content.genres`/`countries` já usa pra `EPISODE` (ver acima). **Elenco (`cast`) é a
    exceção**: SEASON usa o `aggregate_credits` da própria temporada (não da série — ver campo
    `episodeCount` abaixo), só EPISODE reaproveita o `aggregate_credits` da SERIES (TMDB não expõe
    `aggregate_credits` no nível de episódio).
  - **`CastMemberDTO.episodeCount` (adicionado 2026-08-31, a pedido do usuário) mostra em quantos
    episódios aquela pessoa aparece, com escopo diferente por tela** — na tela de Série
    (`aggregate_credits` da SERIES) é o total na série inteira (`TmdbAggregateCastMember.
    totalEpisodeCount`, campo `total_episode_count` do TMDB); na tela de Temporada
    (`aggregate_credits` da própria SEASON, adicionado ao `append_to_response` de
    `getSeasonFullDetails`) é só nessa temporada — **sem nenhuma chamada TMDB extra**, já que o
    TMDB expõe `aggregate_credits` por temporada com `episode_count` já reescopado pra ela; não
    precisou (e não usa) a alternativa mais cara de buscar créditos episódio por episódio. `null`
    em MOVIE (`castFromCredits`, sem conceito de episódio) e nos `guestStars` de EPISODE (convidado
    já é implicitamente daquele único episódio). `CastMemberDTO` também ganhou `id` (TMDB person
    id, já existia em `TmdbCastMember`/`TmdbAggregateCastMember`/`TmdbGuestStar`, só não estava
    mapeado) em todos os quatro pontos de montagem de elenco (`castFromCredits`,
    `castFromAggregateCredits`, `guestStars`).
  - **`runtimeMinutes`/`totalRuntimeMinutes`/`numberOfEpisodes`/`creators`/`guestStars` de SEASON
    são calculados sem nenhuma chamada TMDB extra** (adicionado 2026-08-31, a pedido do usuário) —
    `buildSeasonDetails` já busca `season.episodes()` (corpo base de
    `GET /tv/{id}/season/{n}`) e a `SERIES` completa (pra genres/countries/cast fallback), então:
    `runtimeMinutes` (média) e `totalRuntimeMinutes` (soma) reaproveitam os mesmos helpers
    `averageRuntime`/`totalRuntimeMinutes` já usados em SERIES, só que aplicados aos runtimes dos
    episódios daquela única temporada (`ContentDetailsServiceImpl.runtimesOf`); `numberOfEpisodes`
    é só `season.episodes().size()` — ao contrário do `numberOfEpisodes` de SERIES (passthrough do
    `number_of_episodes` do TMDB), esse sempre inclui episódio ainda não exibido, já que é uma
    contagem de lista, não um filtro por data (ver `seasons[].airedEpisodeCount` pra isso);
    `creators` reaproveita `series.createdBy()`, já buscado no mesmo request.
  - **`guestStars` de SEASON agrega os `guest_stars` de cada episódio dentro de
    `episodes[]` — TMDB embute isso no corpo de `GET /tv/{id}/season/{n}` sem precisar de
    `append_to_response` nem chamada por episódio** (`ContentDetailsServiceImpl.seasonGuestStars`,
    2026-08-31) — diferente de `aggregate_credits` (que só cobre elenco regular), o TMDB já devolve
    `guest_stars` por episódio dentro da própria resposta de temporada. Deduplicado por
    `TmdbGuestStar.id`: a mesma pessoa aparecendo em mais de um episódio daquela temporada vira uma
    única entrada em `guestStars`, com `CastMemberDTO.episodeCount` contando em quantos episódios
    da temporada ela apareceu e `character`/`profilePath` vindos da primeira aparição encontrada
    (ordem de `episodes[]`). **`guestStars` de SERIES continua sempre vazio** — TMDB só expõe
    `guest_stars` por episódio; agregar pra série inteira exigiria uma chamada TMDB extra por
    episódio de cada temporada (N+1), contra o orçamento de 1 chamada por temporada que
    `fetchAllSeasonsInParallel` já paga pra `totalRuntimeMinutes`/`recentEpisodes` — o usuário
    confirmou explicitamente que SERIES não precisa desse campo.
  - **Cadeia de fallback de título** (`ContentDetailsServiceImpl.resolveMovieTitle`/
    `resolveTvTitle`, só MOVIE/SERIES): título traduzido pro `preferredLanguage` do usuário → se
    vazio, título alternativo do TMDB (`alternative_titles`, já vem junto no mesmo
    `append_to_response`) cujo `iso_3166_1` bate com a `preferredRegion` do usuário → se nenhum
    bater, `original_title`/`original_name`. SEASON/EPISODE não têm essa cadeia — TMDB não tem
    `alternative_titles` pra esses tipos.
  - **TMDB indisponível vira `502`, não `500` nem resposta parcial** (`TmdbUnavailableException` →
    `GlobalExceptionHandler`) — só depois de `TmdbClient` já ter tentado a chamada duas vezes
    (retry existente).
  - **`totalRuntimeMinutes`/`recentEpisodes` (só SERIES) buscam todas as temporadas da série em
    paralelo** (`ContentDetailsServiceImpl.fetchAllSeasonsInParallel`, `ExecutorService` dedicado
    de 8 threads — `TmdbCacheConfig.tmdbSeasonFetchExecutor`, não o `ForkJoinPool.commonPool()`
    compartilhado, pra não competir com outro código paralelo do app por esse pool) — decisão de
    2026-08-30. Cada temporada é buscada via `getSeasonFullDetails` (mesmo método já cacheado
    individualmente), então o custo de N chamadas simultâneas só acontece de verdade na primeira
    vez que alguém pede aquela série depois do cache expirar (TTL); chamadas seguintes reaproveitam
    o cache de cada temporada, sem nenhuma chamada nova ao TMDB. Buscar em paralelo em vez de
    sequencial evita que a pessoa que bate primeiro numa série de muitas temporadas espere N
    round-trips somados — o tempo vira só o da temporada mais lenta, não a soma de todas.
    `totalRuntimeMinutes` soma `episodes[].runtime` de todas as temporadas encontradas (episódio
    sem `runtime` conhecido contribui 0, `null` se nenhum episódio da série tiver `runtime`
    conhecido — mesmo padrão de undercount aceito usado em outros campos opcionais do TMDB).
    `recentEpisodes` pega os episódios com `airDate` já passado (nunca um episódio futuro/ainda não
    lançado), ordena do mais recente pro mais antigo e corta em 3 — sem chamada TMDB extra além das
    já feitas pra `totalRuntimeMinutes` (mesmas respostas de temporada reaproveitadas em memória).
    A ordenação usa `airDate` como chave primária e `seasonNumber`/`episodeNumber` (ambos
    decrescentes) como desempate (`ContentDetailsServiceImpl.recentlyAiredEpisodes`, fix
    2026-09-02) — sem esse desempate, uma temporada que lança todos os episódios no mesmo dia
    ficava com empate total em `airDate`, e como `Stream.sorted` é estável, o desempate caía pra
    ordem de chegada do `flatMap` (episódios em ordem crescente dentro da temporada), fazendo o
    corte em 3 devolver os *primeiros* episódios daquela leva em vez dos *últimos* — o oposto do
    que "mais recente" deveria significar num empate de data.
    **Temporada 0 (Specials) do TMDB é excluída antes mesmo de ser buscada**
    (`fetchAllSeasonsInParallel` filtra `seasonNumber == 0` de `details.seasons()` antes de disparar
    as chamadas paralelas — fix 2026-08-31) — episódios especiais/bônus não contam pra
    `totalRuntimeMinutes`/`runtimeMinutes` (runtime atípico distorceria soma/média) nem aparecem em
    `recentEpisodes` (não são "próximo episódio da série" no sentido que a tela quer). A temporada 0
    continua listada normalmente em `seasons` (`seasonSummaries`, que lê `details.seasons()` sem
    esse filtro) e continua acessível via `GET /contents/{seasonContentId}/details` direto — só fica
    de fora dos agregados calculados a partir de `allSeasons`.
  - **`numberOfSeasons`/`numberOfEpisodes` (só SERIES) são passthrough direto de
    `number_of_seasons`/`number_of_episodes` do `/tv/{id}` do TMDB** (adicionados 2026-08-31) — sem
    chamada extra, o TMDB já devolve os dois contadores no corpo base. Ao contrário de
    `totalRuntimeMinutes`/`runtimeMinutes`/`recentEpisodes` (calculados por este backend a partir de
    `allSeasons`, que exclui a temporada 0), esses dois campos refletem a contagem que o próprio
    TMDB mantém — inclui a temporada 0 se a série tiver especiais, já que o TMDB não distingue
    "Specials" ao contar `number_of_seasons`/`number_of_episodes`.
  - **`seasons[].airedEpisodeCount` (só SERIES) é a contagem certa pra montar
    `finaleEpisodeNumber`/`seasonFinaleEpisodeNumbers` de `POST /diary/bulk` sem incluir episódio
    futuro** (adicionado 2026-08-31, a pedido do usuário ao notar que o fluxo sugerido de "marcar
    série inteira como assistida" não podia incluir episódios ainda não lançados) —
    `seasonSummaries` agora recebe `allSeasons` (mesmos dados de episódio já buscados pra
    `totalRuntimeMinutes`/`recentEpisodes`) e calcula, por temporada, quantos episódios têm
    `airDate <= hoje` (`ContentDetailsServiceImpl.airedEpisodeCount`). Diferente de
    `episodeCount` (contagem crua do TMDB, que inclui episódio agendado/ainda não exibido numa
    temporada em exibição), `airedEpisodeCount` nunca conta episódio futuro. `POST /diary/bulk` em
    si **não valida datas** (mesmo padrão de `DiaryEntryUpdate.watchedDate` — validação de data
    contra dado do TMDB é sempre responsabilidade do cliente, nunca do servidor); esse campo existe
    justamente pra dar ao cliente o número certo sem precisar buscar cada temporada separadamente.
    `null` quando a busca daquela temporada específica falhou no TMDB (`fetchAllSeasonsInParallel`
    descarta silenciosamente season que falhou) — nesse caso o cliente não deve assumir `0`, e sim
    tratar como indisponível. Nota: marcar como assistido até o último episódio já lançado de uma
    temporada ainda em exibição força esse episódio a virar `isSeasonFinale`/`isSeriesFinale`
    (bulk sempre trata o último episódio informado assim) mesmo a temporada não tendo realmente
    terminado — recuperável depois porque a flag de finale só transfere pra frente (ver acima), mas
    o cliente deve evitar oferecer "marcar série inteira" pra séries com status TMDB "em exibição"
    sem deixar claro que vai precisar logar os episódios novos manualmente quando saírem.
  - **`runtimeMinutes` de SERIES (média por episódio) é calculado a partir dos mesmos
    `episodes[].runtime` de `totalRuntimeMinutes`, não do campo `episode_run_time` de `/tv/{id}`**
    (fix 2026-08-31) — `episode_run_time` é um campo legado do TMDB que a API praticamente nunca
    mais popula, então usá-lo direto deixava `runtimeMinutes` sempre `null` pra toda série. Como
    `totalRuntimeMinutes` já busca todas as temporadas em paralelo, a média reaproveita a mesma
    lista de runtimes sem nenhuma chamada TMDB extra; fica `null` pela mesma regra de
    `totalRuntimeMinutes` (nenhum episódio com `runtime` conhecido).
  - **Histórico (superado em 2026-09-04, ver abaixo): `unless` do `@Cacheable` usava `#result ==
    null`, não `#result.isEmpty()`** — bug real encontrado em teste manual (2026-08-30): pra método
    que retornava `Optional<T>`, o Spring Cache **desembrulha o `Optional` antes de avaliar
    `key`/`unless`/`condition`** — `#result` dentro do SpEL já era o `T` puro (ou `null` quando o
    método devolvia `Optional.empty()`), nunca o `Optional` em si. `unless = "#result.isEmpty()"`
    quebrava com `SpelEvaluationException` porque `T` não tem método `isEmpty()`. Nenhum teste
    unitário pegava isso porque `@Cacheable` só é tecido via proxy AOP com contexto Spring de
    verdade — `ContentDetailsServiceImplTest`/`TmdbClientTest` chamam `new TmdbClient(...)` direto
    (sem contexto) e `ContentControllerIntegrationTest` substitui o bean inteiro via
    `@MockitoBean`, então nenhum dos dois exercitava o interceptor de cache real.
    `TmdbClientCachingTest` foi criado pra subir um contexto Spring mínimo com `TmdbCacheConfig`
    real + `TmdbClient` real + `MockRestServiceServer` ligado no `RestClient.Builder` antes do bean
    ser construído (`@DependsOn`), pra cobrir exatamente essa classe de bug — continua existindo
    com esse mesmo papel depois de 2026-09-04, só que exercitando o cache do Caffeine chamado
    diretamente em vez do proxy `@Cacheable` (que não existe mais nessas 4 chamadas).
  - **`@Cacheable`/`CacheManager` do Spring foram removidos das 4 chamadas de detalhes do TMDB —
    substituídos por `com.github.benmanes.caffeine.cache.Cache<String, TmdbLookupResult<X>>`
    injetado direto em `TmdbClient` (2026-09-04)** — motivado por uma auditoria de performance que
    apontou cache stampede (duas requisições concorrentes pra mesma chave ainda fria disparavam
    duas chamadas reais ao TMDB) e propôs `sync = true` como correção. Testado na prática: Spring
    recusa em runtime com `IllegalStateException: A sync=true operation does not support the
    unless attribute` — as 4 chamadas dependem de `unless = "#result.isUnavailable()"` (regra de
    2026-09-03 que impede cachear uma indisponibilidade transitória do TMDB por 24h) e as duas
    opções são mutuamente exclusivas no Spring Cache. Solução: `TmdbClient.cachedLookup` usa
    `Cache.get(key, mappingFunction)` do Caffeine, que já é atômico por chave (mesma proteção que
    `sync = true` daria) e permite retornar `null` da função de carga pra sinalizar "não cachear" —
    usado exatamente pro caso `isUnavailable()`, preservando a regra de 2026-09-03 sem precisar do
    `unless` do Spring. As 4 chaves de cache (`tmdbId|idioma`, `seriesTmdbId|seasonNumber|idioma`,
    etc.) e o TTL (`app.tmdb.details-cache-ttl-hours`, injetado via 4 `@Bean` em `TmdbCacheConfig`,
    um por tipo) não mudaram. `@EnableCaching` removido — não sobrou nenhum outro uso de cache do
    Spring no projeto.
  - Capado em 100 ids por chamada em `GET /contents/details`, mesmo limite/mensagem de
    `GET /contents/stats` (`ContentDetailsServiceImpl.MAX_BATCH_IDS`).
  - **`crew` é filtrado por uma lista fixa de jobs, não o crew inteiro do TMDB**
    (`ContentDetailsServiceImpl.ALLOWED_CREW_JOBS`, adicionado 2026-09-01, a pedido do usuário):
    `Director`, `Screenplay`, `Executive Producer`, `Production Manager`, `First Assistant Director`,
    `Director of Photography`, `Supervising Art Director` — strings exatas do campo `job` do TMDB.
    Pessoa sem nenhum job dessa lista não aparece em `crew`. MOVIE lê `credits.crew` (1 linha por
    job no TMDB — agrupado por `id`, jobs batidos juntados numa lista por pessoa,
    `ContentDetailsServiceImpl.crewFromCredits`); SERIES lê `aggregate_credits.crew` (já vem com
    `jobs[]` por pessoa no TMDB, só filtra os que batem, descarta a pessoa se nenhum bater,
    `crewFromAggregateCredits`). Um registro por pessoa em ambos os casos, nunca um por job —
    decisão deliberada pra não duplicar `id`/`name`/`profilePath` de quem tem múltiplos jobs
    filtrados (ex: Director e Executive Producer na mesma pessoa).
  - **`budget`/`revenue` só existem em MOVIE — o endpoint `/tv/{id}` do TMDB não retorna esses
    campos** (confirmado com o usuário 2026-09-01) — ficam sempre `null` em SERIES/SEASON/EPISODE.
    Em MOVIE, `0` do TMDB é tratado como "não informado" e vira `null`
    (`ContentDetailsServiceImpl.nullIfZero`) — convenção do próprio TMDB, não um filme que
    literalmente arrecadou/custou `$0`.
  - **`productionCompanies`/`crew`/`videos` de SEASON/EPISODE são herdados da `SERIES` do mesmo
    `seriesTmdbId`, mesmo padrão já usado por `genres`/`countries`/`creators`** — sem chamada TMDB
    extra, reaproveitam o mesmo `TmdbTvFullDetails` já buscado pra esses outros campos.
  - **`videos` é filtrado a `site=YouTube`, sem filtro de `type`/`official`** (revisado 2026-09-02 —
    antes devolvia todo `videos.results` do TMDB sem filtro nenhum; decisão revertida porque o app só
    tem suporte a tocar vídeo do YouTube) — `ContentDetailsServiceImpl.videos` descarta qualquer
    resultado cujo `site` não seja `YouTube` (ex. Vimeo) antes de mapear; dentro do que sobra, o
    cliente ainda decide o que exibir entre trailers/teasers/clipes etc. `url` é montado no backend
    (`https://www.youtube.com/watch?v={key}`) pra o cliente não precisar remontar a URL a partir de
    `key`; `site` continua no DTO (sempre `"YouTube"` agora) por compatibilidade, mas deixou de ser
    informativo. `publishedAt` usa `Instant` (não `LocalDate` como `releaseDate`/`airDate`) porque o
    TMDB retorna timestamp completo, não só data.
  - **`videos`/`credits`/`aggregate_credits` continuam custando 1 única chamada HTTP por
    MOVIE/SERIES** — `videos` foi só mais um valor em `append_to_response` de `getMovieFullDetails`/
    `getTvFullDetails` (já existentes), e `crew` já vinha embutido nas respostas de `credits`/
    `aggregate_credits` que o backend já buscava — só não estava mapeado nos DTOs antes de
    2026-09-01.
- **`totalRuntimeMinutes`/`runtimeMinutesEpisodeCount` (só `SERIES`, adicionado 2026-09-05) são
  mantidos exclusivamente pelo backend, fora do pipeline de metadado client-verificado descrito
  acima** — ao contrário de `genres`/`releaseYear`/`countries`/`runtimeMinutes` (imutáveis, escritos
  uma vez, com `assertNoMetadataMismatch`/`reconcileExisting`), o runtime total de uma série cresce ao
  longo do tempo (novo episódio = novo total) e nunca é aceito do cliente — não fazem parte de
  `ContentRefCreationDTO`/`ContentRefDTO`, só existem como colunas em `Content`, escritas direto por
  `ContentDetailsServiceImpl`/`ContentTrackingServiceImpl`. `runtimeMinutesEpisodeCount` guarda quantos
  episódios entraram na soma (só os com `runtime` conhecido no TMDB); a média exposta em
  `ContentDetailsDTO.runtimeMinutes` é sempre `round(total / count)` calculada na leitura, nunca
  armazenada separadamente, pra não haver dois valores que podem divergir entre si.
  - **`ContentDetailsServiceImpl.buildSeriesDetails` só pula a busca de todas as temporadas quando o
    status fresco da própria chamada TMDB já é terminal (`Ended`/`Canceled`) e já existe um baseline
    salvo** — usa `content.getTotalRuntimeMinutes()`/`runtimeMinutesEpisodeCount` direto, e busca só as
    2 temporadas de maior número (`latestSeasons`) pra `recentEpisodes`/`seasons[].airedEpisodeCount`
    (correto pra uma série encerrada: os episódios mais recentes só podem estar nas últimas
    temporadas). Em qualquer outro caso (série ainda `Returning Series`, ou terminal mas sem baseline
    ainda) busca todas as temporadas como antes e persiste o total/contagem calculados
    (`persistRuntimeAggregate`) pra próximas chamadas. A decisão é baseada no status **fresco**, não no
    `TrackedContentState` — um revival (`Ended`→`Returning Series`) se autocorrige sozinho na próxima
    chamada, sem lógica de detecção dedicada.
  - **`ContentTrackingServiceImpl.processSeries` mantém o total incrementalmente**: quando
    `detectTvChange` emite `NEW_EPISODE` e já existe baseline (`content.getTotalRuntimeMinutes() !=
    null`), busca só aquele episódio (`getEpisodeFullDetails`, idioma
    `TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE`) e soma o runtime dele ao total já salvo — nunca
    recalcula tudo por causa de um único episódio novo.
  - **Reconciliação completa ao virar terminal**: na transição de um status não-terminal pra
    `Ended`/`Canceled` (comparando o `lastKnownStatus` anterior, capturado como `String` **antes** de
    `saveSeriesState` mutar o mesmo objeto `TrackedContentState` — ver nota de implementação abaixo —
    com o status fresco), `reconcileRuntimeBeforeFreezing` refaz a soma do zero a partir de todas as
    temporadas, substituindo (não somando) o que foi acumulado incrementalmente — corrige qualquer
    drift de um episódio cujo runtime foi revisado retroativamente no TMDB, antes do valor virar
    definitivo.
  - **Nota de implementação (bug real encontrado durante o desenvolvimento):** `saveSeriesState` muta
    o mesmo objeto `TrackedContentState` recebido como "estado anterior" (chama `setLastKnownStatus`
    nele). Guardar uma referência a esse objeto para comparar "status anterior vs. status fresco"
    *depois* de `saveSeriesState` já ter rodado sempre compara o status fresco contra ele mesmo — o
    `previousStatus` precisa ser lido e copiado pra uma `String` **antes** de `saveSeriesState` ser
    chamado.
  - Fora de escopo (intencional): persistir um snapshot de `recentEpisodes` (nome/overview/still de
    episódio) para eliminar também as 2 chamadas de temporada de uma série já congelada — mais próximo
    do que o "Avoid" de metadado realmente restringe; ver
    `docs/pending/tmdb-tracking-runtime-optimization-proposal-2026-09-05.md`.

## User / Auth

- **`GET /users/{userId}` bloqueia até o próprio dono se o perfil for privado** (`UserServiceImpl.getUserById`
  não recebe `viewerId`) — diferente de todo outro endpoint com regra de privacidade (seguidores, diário,
  top5, pessoas seguidas), que sempre liberam o próprio dono ver seus dados mesmo com perfil privado. Para
  o dono ver seu próprio perfil privado é preciso usar `GET /users/me`, não `GET /users/{userId}`.
- **Trocar `email` ou `password` exige `currentPassword`** (`UserServiceImpl.applyPatch` /
  `requireCurrentPassword`) — `400` se `currentPassword` não vier, `401` se vier errada. Trocar
  `username`/`description`/`profilePicture`/`isProfilePublic`/`preferredLanguage`/`preferredRegion`
  não exige senha atual.
- **`preferredLanguage`/`preferredRegion` só são setados via `PATCH /users/me`, nunca em
  `POST /auth/register`** (`UserMapper.postUserDtoToUser` ignora os dois campos; `User` os
  inicializa via `@Builder.Default` como `"en-US"`/`"US"`) — decisão deliberada (2026-08-30): são
  preferência de conta ("configurações"), não campo de cadastro. Usados só por
  `GET /contents/{contentId}/details`/`GET /contents/details` (ver § Content) — `preferredLanguage`
  decide o idioma pedido ao TMDB, `preferredRegion` decide qual linha de `watchProviders`/qual
  título alternativo usar, nunca é enviado como parâmetro pro TMDB.
- **Login exige e-mail verificado** (`UserServiceImpl.login`) — credenciais corretas mas
  `isEmailVerified = false` retorna `403` (`Email not verified`), não autentica.
- **Registro/login rejeitam quem já tem sessão válida** (`AuthController.isAuthenticated()`) —
  `POST /auth/register` e `POST /auth/login` devolvem `409` se o `access_token` já é válido, em vez de
  criar uma segunda sessão por cima.
- **Rotação de refresh token com detecção de reuso** (`RefreshTokenServiceImpl.rotateRefreshToken`) —
  cada `/auth/refresh` marca o refresh token usado como `revoked` e emite um novo par. Se um refresh
  token já marcado como `revoked` for reapresentado (sinal de token roubado/reusado), a resposta é
  `401` **e** `invalidateAllSessions` é chamado pro usuário. A mesma detecção também dispara quando duas
  requisições concorrentes tentam rotacionar o **mesmo** token válido ao mesmo tempo: a coluna `version`
  (lock otimista) faz a segunda `saveAndFlush` falhar com `ObjectOptimisticLockingFailureException`,
  tratada exatamente como reuso — `401` + `invalidateAllSessions` — em vez de deixar as duas chamadas
  emitirem pares de token válidos em paralelo.
- **`invalidateAllSessions` também invalida o access token já emitido, não só os refresh tokens**
  (`RefreshTokenServiceImpl.invalidateAllSessions`, `User.sessionsInvalidatedAt`, migration `V16`) —
  além de revogar todos os refresh tokens no banco, grava `sessionsInvalidatedAt = now()` (truncado pro
  segundo, igual à precisão do claim `iat` do JWT) no usuário. `JwtCookieAuthenticationFilter` compara o
  `iat` de todo `access_token` recebido contra esse timestamp: um token emitido **antes ou no mesmo
  segundo** da invalidação é tratado como não-autenticado (empate resolve pra rejeitar — a direção mais
  segura), mesmo ainda dentro da janela de expiração natural de 60 minutos. Chamado tanto por
  `/auth/logout-all` quanto por `updateUser` quando a troca envolve `password`/`email` (ver regra
  abaixo) — nos dois casos, sessões antigas (access **e** refresh) param de funcionar imediatamente, não
  só na próxima renovação.
- **Um `access_token` cujo `userId` não existe mais nunca autentica** (bug corrigido em 2026-08-20 —
  antes, `JwtCookieAuthenticationFilter.isSessionStillValid` tratava `userRepository.findById(userId)`
  vazio como sessão válida, então o token de uma conta já deletada continuava autenticando até expirar
  naturalmente, mesmo sem nenhum `User` por trás do `SecurityContextHolder`). Agora a ausência do
  usuário é tratada com a mesma direção "empate resolve pra rejeitar" já usada na comparação de
  `sessionsInvalidatedAt` logo abaixo — sem usuário, sem sessão válida, ponto.
- **`isSessionStillValid` não carrega mais o `User` inteiro a cada requisição autenticada** (corrigido
  em 2026-08-24) — antes, toda requisição autenticada disparava um `userRepository.findById(userId)`
  completo só pra ler `sessionsInvalidatedAt`. Agora usa `UserRepository.findSessionsInvalidatedAtById`,
  uma projeção fechada (`SessionsInvalidatedAtView`, interface com `getId()` **e**
  `getSessionsInvalidatedAt()`) que seleciona só essa coluna. A projeção inclui `getId()` mesmo sem uso
  algum no filtro — armadilha descoberta ao implementar: com só `getSessionsInvalidatedAt()` na
  interface, uma projeção fechada de uma única coluna anulável faz o Hibernate devolver o valor `null`
  cru (não uma tupla) quando essa coluna é `null` no banco, e o Spring Data não consegue criar o proxy
  da projeção em cima de `null` — então `Optional<SessionsInvalidatedAtView>` virava `Optional.empty()`
  tanto pra "usuário não existe" quanto pra "usuário existe mas nunca invalidou sessões", quebrando
  exatamente a distinção do bullet acima. Selecionar uma segunda coluna sempre não-nula (`id`) força o
  Hibernate a devolver uma tupla de verdade, e o proxy da projeção se forma corretamente mesmo com
  `sessionsInvalidatedAt = null`.
- **Limpeza noturna só apaga tokens de fato expirados, nunca um revogado ainda dentro do prazo**
  (`RefreshTokenRepository.deleteExpired`) — um token revogado continua na tabela até `expiresAt`
  passar, porque a detecção de reuso acima depende da linha revogada ainda existir pra ser encontrada;
  apagar na hora da revogação quebraria essa detecção pra qualquer reuso que aconteça antes da
  expiração natural do token.
- **Login resolve por username antes de e-mail, nunca os dois na mesma consulta**
  (`UserServiceImpl.login`) — `findByUsernameIgnoreCase` primeiro, só cai pra `findByEmailIgnoreCase` se
  não achar. Evita que um identifier que colide com o username de um usuário e o e-mail de outro (nada
  impede registrar um username igual a um e-mail alheio, já que `uq_users_username`/`uq_users_email`
  são constraints independentes) derrube o login com `IncorrectResultSizeDataAccessException` — a
  consulta antiga (`findByUsernameIgnoreCaseOrEmailIgnoreCase`, ainda usada em outros lugares) podia
  casar duas linhas ao mesmo tempo.
- **Trocar `password` ou `email` revoga todas as sessões existentes, access token incluso**
  (`UserServiceImpl.updateUser`) — depois de um `PATCH /users/me` que muda credencial,
  `invalidateAllSessions` é chamado pro próprio usuário; útil tanto pra higiene normal quanto pro caso de
  alguém trocando a senha por suspeita de invasão. Não dispara pra troca de
  `username`/`description`/`profilePicture`/`isProfilePublic`.
- **Tornar o perfil público e o `save` do usuário são atômicos entre si; `invalidateAllSessions` é
  adiado pra depois do commit, nunca chamado de dentro da mesma transação** (`UserServiceImpl.updateUser`)
  — `updateUser` é `@Transactional`, então `applyPatch` chamando
  `followerService.acceptAllPendingFollowRequestsFor` (ao tornar o perfil público) passa a rodar dentro
  da mesma transação do `saveAndFlush(user)` em vez de comitar isoladamente antes dele; se o `save` falhar
  depois (ex.: conflito de unicidade de email/username → `409`), o aceite dos follow requests reverte
  junto, em vez de ficar permanentemente aplicado com a API respondendo erro ao cliente (bug corrigido em
  2026-08-24). **Armadilha descoberta ao implementar isso:** chamar `refreshTokenService.invalidateAllSessions`
  (que abre sua própria transação `REQUIRES_NEW`, ver regra acima) de dentro dessa mesma transação
  travava indefinidamente — a linha do `users` já teria um lock não commitado da transação ambiente
  (do `saveAndFlush`), e a transação `REQUIRES_NEW` do `invalidateAllSessions`, numa conexão física
  diferente, ficava bloqueada esperando esse lock liberar, que só liberaria quando o método retornasse —
  que por sua vez esperava o `invalidateAllSessions` retornar primeiro. Resolvido registrando
  `invalidateAllSessions` como uma `TransactionSynchronization.afterCommit()`
  (`UserServiceImpl.invalidateSessionsAfterCommit`), que só dispara depois que a transação ambiente já
  comitou (lock já liberado) — fora de um contexto transacional real (ex.: chamada direta em teste
  unitário, sem `TransactionSynchronizationManager.isSynchronizationActive()`), cai de volta pra chamada
  síncrona direta, preservando o comportamento observável nos testes de unidade existentes.
- **Busca por username exige texto não-vazio após `trim`, e escapa coringas do LIKE**
  (`UserServiceImpl.getUsersByUsername`) — `"   "` (só espaço) é rejeitado com `400`, não tratado como
  ausente (guard antigo só pegava string literalmente vazia). `%` e `_` no termo de busca são escapados
  antes de entrar na query (`ESCAPE '\\'`), então `?username=%` busca literalmente por um username que
  começa com `%`, não "todo mundo". A busca sempre retorna perfis públicos e privados juntos — não existe
  filtro de visibilidade nela (o único caller, `GET /users`, nunca expôs um parâmetro pra isso).
- **Username exige pelo menos 3 caracteres depois do `trim`, não só na validação bruta do DTO**
  (`UserServiceImpl.validateUsernameLength`, chamado por `saveNewUser` e `applyPatch`) — o `@Size(min=3)`
  do bean validation roda antes do `trim()` do service, então `"  ab  "` (6 caracteres brutos) passava
  ali mas virava `"ab"` (2 caracteres) depois de trimado; agora o service rejeita com `400` qualquer
  username cujo comprimento trimado fique abaixo de 3.
- **Deletar conta exige a senha atual, não apenas estar autenticado** (`UserServiceImpl.deleteAccount`).
- **`totalMinutesWatched`/`minutesWatchedLast30Days`/`genreCountsMovies`/`genreCountsSeries`/**
  **`followersCount`/`followingCount`** (`UserServiceImpl.computeProfileStats`, em
  `UserResponseDTO`/`PublicUserProfileDTO`) — os dois primeiros somam `runtimeMinutes` só de
  `DiaryEntry` `MOVIE`/`EPISODE` (`SEASON`/`SERIES` são marcadores sintéticos de conclusão, contariam
  o mesmo tempo em dobro); `genreCountsMovies` (`DiaryEntryRepository.countEntriesByGenreAndUserIdForMovies`) conta cada
  `DiaryEntry` `MOVIE` por gênero, all-time — um rewatch soma de novo. `genreCountsSeries`
  (renomeado de `genreCountsEpisodes` em 2026-09-03; `DiaryEntryRepository.
  countDistinctTitlesByGenreAndUserIdForSeries`) conta `SERIES` distintas **iniciadas** por gênero,
  all-time — não precisa ter sido concluída, mas um rewatch de uma série já iniciada não soma de
  novo; gênero de um `EPISODE` é resolvido via o `Content` `SERIES` do mesmo `seriesTmdbId`. Uma
  `DiaryEntry` logada diretamente sobre um `Content` tipo `SERIES` (via `POST /diary` com
  `content.type=SERIES`, sem passar por episódio nenhum) também conta em `genreCountsSeries`, lendo
  o gênero do próprio `Content` `SERIES`; dedupe entre o log direto e os `EPISODE`s da mesma série
  usa a `tmdbId` da `SERIES`/`seriesTmdbId` do episódio como a mesma chave, então a série nunca é
  contada duas vezes mesmo logada dos dois jeitos. Antes de 2026-09-03, ambos os campos deduplicavam
  por título distinto (um rewatch de filme nunca somava de novo no perfil); a mudança unificou a
  semântica de filme com a já usada por `AllTimeStats`/`MonthInReview`/`YearInReview`/`HomeSummary`
  (ver § Summary abaixo), que já contavam filme por entrada mas ainda contavam série por episódio
  individual em vez de por série iniciada — as duas inconsistências foram corrigidas juntas.
  `followersCount`/`followingCount` (`FollowerRepository.countByFollowedIdAndStatus`/
  `countByFollowerIdAndStatus`) contam só `Follower` com `status = ACCEPTED` — solicitações `PENDING`
  não entram na contagem de nenhum dos dois lados; calculado a cada request, não desnormalizado (mesma
  escolha de `commentsCount` em `UserList`, diferente do `likesCount` desnormalizado de
  `Comment`/`DiaryEntry`/`UserList`). `saveNewUser` pula as consultas (usuário novo não pode ter
  `DiaryEntry` nem `Follower`); os demais métodos que devolvem `UserResponseDTO` computam de verdade.
  `PublicUserDTO` (listas paginadas — seguidores/seguindo/busca) nunca carrega esses campos, só
  `PublicUserProfileDTO` (`GET /users/{userId}`, item único) — evita uma consulta de agregação por item
  de uma página.
- **`totalTheaterVisits`** (`UserServiceImpl.computeProfileStats`, `DiaryEntryRepository.countByUserIdAndWatchedInTheaterTrue`,
  em `UserResponseDTO`/`PublicUserProfileDTO` e também em `AllTimeStatsResponseDTO` — ver § Summary) —
  `COUNT` de `DiaryEntry` do usuário com `watchedInTheater = true`, all-time. Como `watchedInTheater` só
  pode ser setado em `Content` do tipo `MOVIE` (ver § DiaryEntry), a contagem já fica implicitamente
  restrita a filmes sem precisar filtrar por `content.type` explicitamente.
- **`banner` é um campo simples, sem regra própria além do formato** (`User.banner`, nullable, mesmo
  padrão `@Size(max=2048) @URL` de `profilePicture`, mas sem valor default — um usuário sem banner
  definido simplesmente não tem um, diferente de `profilePicture` que sempre tem uma imagem padrão).
  Aceito em `POST /auth/register` e `PATCH /users/me`, exposto em `UserResponseDTO`/`PublicUserDTO`/
  `PublicUserProfileDTO`.

## Follower

- **Não é possível seguir a si mesmo** (`FollowerServiceImpl.followUser`) — `400` se `followerId == followedId`.
- **Perfil público segue instantaneamente; perfil privado gera solicitação pendente**
  (`FollowerServiceImpl.followUser`): se `isProfilePublic = true` a relação já nasce `ACCEPTED`; se
  `false`, nasce `PENDING` e só vira `ACCEPTED` quando o dono aceitar via
  `POST /users/me/follow-requests/{requesterId}/accept`.
- **`DELETE /users/{userId}/follow` cancela tanto uma solicitação pendente quanto uma relação já aceita**
  (`FollowerServiceImpl.unfollowUser` não filtra por `status`) — é o mesmo endpoint usado tanto para
  desistir de um pedido de seguir quanto para deixar de seguir de fato.
- **Rejeitar uma solicitação a remove**, não deixa em estado "rejeitada" (`rejectFollowRequest` faz
  `delete`, não um update de status) — quem foi rejeitado pode pedir para seguir de novo do zero.
- **Tornar o perfil público aceita em cascata todas as solicitações `PENDING` recebidas**
  (`UserServiceImpl.applyPatch` chama `FollowerService.acceptAllPendingFollowRequestsFor` só na
  transição `false → true` de `isProfilePublic`, nunca quando já era `true`) — sem isso, pedidos feitos
  enquanto o perfil era privado ficariam presos em `PENDING` para sempre, já que só existência de
  solicitação nova nasce `ACCEPTED` direto quando o perfil já é público (regra acima); nada re-avaliava
  as pendentes antigas quando o dono trocava a visibilidade depois.
- **Regra de visibilidade padrão** (repetida em `Follower`, `FollowedPerson`, `Top5Entry`,
  `WatchlistEntry`, `DroppedEntry`, `DiaryEntry` e, desde 2026-08-19, na listagem de `UserList` —
  `GET /users/{userId}/lists`, não em `GET /lists/{listId}`, que checa só a visibilidade da própria
  lista): dados de um usuário só são visíveis para terceiros se o perfil for público **ou** o viewer
  for o próprio dono **ou** o viewer seguir o dono com `status = ACCEPTED`; caso contrário, `403`.

## FollowedPerson

- **Seguir/deixar de seguir uma pessoa do TMDB é idempotente** (`FollowedPersonServiceImpl.followPerson`/
  `unfollowPerson`) — seguir quem já é seguido não dá erro (só retorna sem fazer nada), e deixar de
  seguir quem não era seguido também não dá erro. Diferente do `Follower` (seguir usuário duas vezes é
  `409`).
- **`personTmdbId` só aceita dígitos, até 20 caracteres** (`FollowedPersonServiceImpl.validatePersonTmdbId`)
  — `400` caso contrário. IDs de pessoa do TMDB são sempre numéricos; a checagem também evita que um
  valor não-numérico mas curto grude permanentemente como "pessoa seguida" e que um valor longo demais
  estoure a coluna `VARCHAR(20)` (que antes virava `500` sem handler).

## Top5Entry

- **`type` só aceita `MOVIE` ou `SERIES`** — reforçado em duas camadas: o `@PathVariable` dos endpoints
  já usa `MovieOrSeriesType` (não `ContentType`), então um valor sintaticamente inválido (ex.:
  `movie` minúsculo, ou um enum válido de `ContentType` mas fora do subconjunto, como `SEASON`) já
  falha no binding do Spring com a mensagem de erro correta (`Accepted values: MOVIE, SERIES`, sem
  `SEASON`/`EPISODE` — bug corrigido em 2026-08-20, antes a mensagem genérica de `TypeMismatchException`
  listava as 4 constantes de `ContentType` mesmo esses dois nunca sendo aceitos aqui); `Top5EntryServiceImpl.
  validateType` continua existindo como segunda camada de defesa (o service ainda recebe `ContentType`
  internamente, então protege qualquer chamador direto que não passe pelo controller).
- **Máximo de 5 entradas por usuário+tipo** (`MAX_ENTRIES = 5`).
- **`position` é opcional só enquanto houver menos de 5 entradas** (`resolvePosition`) — nesse caso a
  entrada nova vai para a próxima posição livre (`count + 1`). Com o Top5 já cheio, `position` passa a
  ser obrigatório (`400` se ausente).
- **`position` nunca pode ser maior que a próxima posição livre (`count + 1`)** (`resolvePosition`) —
  inserir além do necessário (ex. `position = 5` com só 1 entrada) é rejeitado com `400` em vez de
  aceito. Sem essa checagem, um insert seguinte sem `position` explícita (auto-atribuída a `count + 1`)
  interpretava a entrada isolada em `position = 5` como "o Top5 está cheio" e apagava a mais antiga
  silenciosamente — a invariante de posições contíguas é o que garante que `shiftUpFrom` só evict a
  posição 5 quando o Top5 de fato tem 5 entradas reais.
- **Inserir numa posição ocupada empurra as posições seguintes uma casa para baixo, e quem estava na
  posição 5 é removido do Top5** (`shiftUpFrom`) — não existe operação de "mover", só inserir/remover; ao
  inserir, a piora de posição das demais entradas é automática e, se o Top5 já está cheio, a última
  entrada é descartada.
- **Remover uma entrada fecha o buraco**: as posições depois da removida são decrementadas em 1
  (`removeEntry`), então o Top5 nunca fica com posições "furadas" (ex.: 1, 2, 4, 5 depois de remover 3).
- **Mesmo conteúdo não pode entrar duas vezes no Top5 do mesmo tipo** (constraint
  `uq_top5_entries_user_id_type_content_id` → `409`).
- **`shiftUpFrom`/`removeEntry` deslocam posição item a item (loop com `save`/`flush` por linha), e essa
  é a implementação certa aqui, não um "jeito ainda não otimizado"** — tentativa deliberada de trocar
  pelo mesmo truque de duas queries em massa (`parkPositionsInRange`/`settleParkedPositions`) usado em
  `WatchlistEntry`/`UserListItem` foi revertida depois de quebrar contra o Postgres real: `top5_entries.
  position` tem `CHECK (position BETWEEN 1 AND 5)` (`ck_top5_entries_position`), e a técnica de
  "estacionar" posições num offset gigante (`position + 1_000_000_000`) viola esse `CHECK` imediatamente,
  mesmo a segunda query corrigindo o valor logo em seguida na mesma transação — Postgres valida `CHECK`
  por linha, não no fim da transação. Diferente de `WatchlistEntry`/`UserListItem`, cujo `position` só
  tem piso (`>= 1`), sem teto, o truque de estacionamento é estruturalmente incompatível com qualquer
  coluna de posição com `CHECK` de teto. Como o Top5 é capado em `MAX_ENTRIES = 5`, o loop nunca passa de
  5 iterações de qualquer forma — não há problema de escala real aqui pra justificar a complexidade extra
  de uma técnica que, além disso, não funciona.
- **`customPosterUrl` é o único campo editável numa entrada já existente** — igual a `DiaryEntry.
  customPosterUrl` (mesma validação `@Size(max = 2048) @URL`), aceito tanto no `POST` (`insertEntry`)
  quanto num novo `PATCH /users/me/top5/{type}/{top5EntryId}` (`updateEntry`, adicionado em
  2026-08-29) — antes desse endpoint, Top5Entry só tinha inserir/remover, sem nenhuma operação de
  update; `null` no patch significa "não alterar", sem forma de limpar um poster já definido de volta
  pra `null`, mesma limitação que `DiaryEntry` já tem.

## WatchlistEntry

- **`type` só aceita `MOVIE` ou `SERIES`** — mesmas duas camadas de defesa do Top5Entry: `@PathVariable
  MovieOrSeriesType` nos endpoints (erro de binding já correto, sem `SEASON`/`EPISODE` na lista de
  valores aceitos) mais `WatchlistEntryServiceImpl.validateType` como segunda camada pra chamadas
  diretas ao service.
- **Sem teto de entradas** — diferente do Top5 (máximo 5), a watchlist não tem limite superior; a
  constraint `ck_watchlist_entries_position` só exige `position >= 1`, sem `BETWEEN`.
- **Inserir sempre entra na última posição** (`WatchlistEntryServiceImpl.insertEntry`) — diferente do
  Top5, o `POST` não aceita `position` no corpo; a entrada nova sempre recebe `count + 1`. Não existe
  shift nem eviction no insert (não há o que deslocar, já que nada nunca entra no meio).
- **A única forma de uma entrada avançar na watchlist é reordenar explicitamente**
  (`WatchlistEntryServiceImpl.moveEntry`, `PATCH /users/me/watchlist/{type}/{watchlistEntryId}`) — move
  a entrada para a `position` informada (deve estar entre `1` e o total de entradas atuais; pedir uma
  posição além do total é rejeitado com `400`, já que "mover para o fim" não é uma operação válida
  aqui — pra isso, remove e insere de novo). Se `position` for igual à posição atual, não faz nada (não
  salva nem desloca). Diferente do Top5, que não tem operação de mover.
- **`moveEntry` usa uma posição temporária para nunca colidir com a constraint de posição única**
  (`WatchlistEntryServiceImpl.performMove`) — antes de deslocar qualquer entrada intermediária, a
  entrada sendo movida é salva numa posição temporária (`count + 1`, fora da faixa válida usada por
  qualquer entrada real), liberando seu slot original. A entrada movida só recebe a posição final
  depois que todo o deslocamento intermediário termina.
- **O deslocamento de posições (`moveEntry` e `removeEntry`/`removeEntryIfPresent`) é feito em duas
  queries `UPDATE` em massa via `WatchlistEntryRepository.parkPositionsInRange`/`settleParkedPositions`,
  não num loop item a item** — mesma técnica adotada por `UserListItem` (que também não tem teto de
  itens). Diferente do Top5 (capado em 5 itens, onde o loop continua sendo a implementação certa — ver
  nota em Top5Entry), a watchlist não tem teto de entradas, então um loop salvando uma linha por vez
  custaria `O(itens deslocados)` round-trips numa única requisição. A técnica
  usada (`POSITION_PARK_OFFSET = 1_000_000_000`) desloca todo o intervalo afetado primeiro para um
  espaço de valores (`position + offset`) astronomicamente maior que qualquer posição real possível,
  depois reduz pro valor final (`position - offset + delta`) — como `offset` nunca colide com nenhuma
  posição real, viva ou ainda não processada dentro da mesma instrução, o resultado é correto
  independentemente da ordem interna em que o Postgres processa as linhas da instrução `UPDATE`
  (diferente de um `UPDATE ... SET position = position ± 1` direto sobre o intervalo, que pode disparar
  `duplicate key` dependendo dessa ordem, já que a constraint `uq_watchlist_entries_user_id_type_position`
  não é `DEFERRABLE`). Remover uma entrada fecha o buraco da mesma forma: as posições depois da removida
  são decrementadas em 1 (`removeEntry`), mesmo comportamento do Top5, só que via essa técnica em massa
  em vez de um loop.
- **Mesmo conteúdo não pode entrar duas vezes na watchlist do mesmo tipo** (constraint
  `uq_watchlist_entries_user_id_type_content_id` → `409`).
- **`removeEntryIfPresent` é a variante best-effort de `removeEntry`** (`WatchlistEntryServiceImpl`,
  ambos compartilham `deleteAndCloseGap`) — não lança `NotFoundException` quando não há entrada pra
  remover, e é resolvida por `userId`+`type`+`contentId` (não por id da entrada), já que quem chama
  não sabe se existe nada pra remover. Único consumidor hoje: `DiaryEntryServiceImpl`, ao logar um
  conteúdo (ver regra em DiaryEntry sobre remoção automática da watchlist/dropped).

## DroppedEntry

- **`type` só aceita `MOVIE` ou `SERIES`** — mesmas duas camadas de defesa do Top5Entry/WatchlistEntry:
  `@PathVariable MovieOrSeriesType` nos endpoints (erro de binding já correto, sem `SEASON`/`EPISODE`
  na lista de valores aceitos) mais `DroppedEntryServiceImpl.validateType` como segunda camada pra
  chamadas diretas ao service.
- **Marcar como abandonado é idempotente e faz upsert do `comment`, não um create-only**
  (`DroppedEntryServiceImpl.markAsDropped`) — marcar um conteúdo já marcado não é erro; se já existe uma
  marcação para esse `userId`+`type`+`contentId`, um `comment` informado (não-nulo) sobrescreve o valor
  salvo, e um `comment` omitido/nulo deixa a marcação existente intacta (`applyCommentIfProvided`). Só
  quando não existe nenhuma marcação é que uma linha nova é criada, com o `comment` do request (podendo
  ser nulo).
- **Marcar como abandonado remove automaticamente a entrada correspondente da watchlist, se existir**
  (`DroppedEntryServiceImpl.markAsDropped`, via `WatchlistEntryService.removeEntryIfPresent`) — chamado
  toda vez que `markAsDropped` roda, tanto na criação de uma marcação nova quanto na remarcação
  idempotente de uma já existente (não só na primeira vez), já que a intenção do usuário é sempre "não
  quero mais isso na fila de quero assistir". Espelha o mesmo efeito colateral que `POST /diary` já tem
  sobre a watchlist (ver regra em DiaryEntry) — `unmarkAsDropped` não tem o efeito inverso, desmarcar
  como abandonado não reinsere de volta na watchlist.
- **A remoção da watchlist e a marcação como abandonado são atômicas entre si** —
  `markAsDropped` é `@Transactional`, então o `removeEntryIfPresent` (também `@Transactional`, sem
  propagação própria) roda dentro dessa mesma transação ambiente em vez de comitar isoladamente. Antes
  dessa correção (2026-08-24), as duas operações comitavam de forma independente; se a criação/upsert do
  `DroppedEntry` falhasse por qualquer motivo além da corrida de constraint já tratada (erro transitório
  de BD, etc.), o item saía da watchlist sem nunca virar "dropped" — sem estar em nenhum dos dois
  lugares, sem rollback possível. Ver ressalva abaixo sobre o `REQUIRES_NEW` da criação em si.
- **Criação idempotente e resistente a corrida, mesmo padrão de `FollowedPersonServiceImpl.followPerson`/
  `ContentServiceImpl.getOrCreateReference`** (`DroppedEntryServiceImpl.markAsDropped`) — a checagem de
  existência e o `saveAndFlush` de uma marcação nova rodam numa transação própria
  (`NewTransactionExecutor.runInNewTransaction`), pra uma falha de constraint não contaminar a
  transação do chamador; se dois requests concorrentes tentam marcar o mesmo `userId`+`type`+`contentId`
  ainda inexistente, quem perde a corrida busca de novo e aplica o `comment` (se informado) na linha que
  o outro request já criou, em vez de propagar erro — só relança a exceção original se a busca de
  recuperação também não achar nada. Essa transação continua sendo fisicamente separada da transação
  ambiente de `markAsDropped` (por design — ver CLAUDE.md sobre `NewTransactionExecutor`), então ela
  comita de forma independente assim que tem sucesso; a janela de não-atomicidade que resta é só entre
  esse commit bem-sucedido e o commit final da transação ambiente (que só cobre a remoção da watchlist,
  já sem mais nenhuma operação depois dela) — não a falha ampla que existia antes.
- **Desmarcar é idempotente e nunca cria um `Content` novo** (`DroppedEntryServiceImpl.unmarkAsDropped`)
  — diferente de `markAsDropped` (que chama `ContentService.getOrCreateReference`), desmarcar só busca
  um `Content` já existente por `tmdbId`+`type` (`ContentRepository.findByTmdbIdAndType`); se essa linha
  não existe, ou existe mas não há marcação pra esse usuário, a chamada não faz nada — nunca é erro.
- **Mesmo conteúdo não pode ter duas marcações pro mesmo usuário+tipo** (constraint
  `uq_dropped_entries_user_id_type_content_id`, mapeada pela recuperação de corrida acima — nunca vira
  `409` de fato pro cliente, já que o caminho normal sempre acha a linha existente antes de tentar
  inserir).

## DiaryEntry

- **Criar uma `DiaryEntry` remove automaticamente a entrada correspondente da watchlist e a marcação
  de abandonado (`DroppedEntry`)** (`DiaryEntryServiceImpl.removeFromWatchlistAndDropped`, chamado por
  `createDiaryEntry` e `createDiaryEntriesInBulk`) — logar volta a ser "estou assistindo/assisti", então
  não faz sentido continuar marcado como "quero assistir" ou "abandonei". Se o conteúdo logado é
  `MOVIE`, remove (se existirem) a entrada de watchlist e o `DroppedEntry` desse filme
  (`type = MOVIE`) direto pelo `contentId` já resolvido. Se é `EPISODE`, `SEASON` ou `SERIES`, resolve
  a série correspondente — pelo próprio `tmdbId` se já for `SERIES` (sem round-trip extra ao banco,
  usa o id já conhecido), ou por `seriesTmdbId` via `ContentRepository.findByTmdbIdAndType` se for
  `EPISODE`/`SEASON` — e remove a entrada de watchlist e o `DroppedEntry` da série
  (`type = SERIES`); se essa linha de `Content` da série ainda não existe (nunca foi referenciada),
  não há nada a resolver e a remoção é pulada. No bulk (sempre `SEASON` ou `SERIES`), a remoção roda
  uma única vez por chamada, não por episódio — resolvida direto a partir do `tmdbId`/`seriesTmdbId` do
  próprio `DiaryEntryBulkCreationDTO.content()`, sem depender de qual episódio específico foi logado.
  A remoção da watchlist usa `WatchlistEntryService.removeEntryIfPresent` (variante idempotente de
  `removeEntry` que não lança `404` quando não há nada pra remover — ver regra em WatchlistEntry) para
  preservar a invariante de fechar o buraco nas posições; a remoção do `DroppedEntry` é um delete direto
  via repositório, já que essa entidade não tem posição nem invariante pra proteger. Em ambos os casos a
  remoção é best-effort/idempotente e definitiva — apagar essa `DiaryEntry` depois não reinsere o
  conteúdo de volta na watchlist nem no `DroppedEntry`.
- **`watchedInTheater` só pode ser definido (não-nulo) se o `Content` for do tipo `MOVIE`**
  (`DiaryEntryServiceImpl.assertWatchedInTheaterAllowed`) — `400` caso contrário, validado tanto na
  criação (contra o tipo do `Content` resolvido) quanto na atualização (contra o tipo do `Content` já
  associado à entrada, já que `content` não é mais editável).
- **`watchNumber` é calculado pelo sistema, nunca aceito na resposta** (`DiaryEntryServiceImpl.persistDiaryEntry`)
  — `MAX(watchNumber existente pro usuário+conteúdo) + 1`, com o campo `isRewatch` do `DiaryEntryCreationDTO`
  só influenciando o **primeiro** log de um conteúdo (empurra o número de 1 para 2, "já tinha assistido
  antes de logar no app"); em todo log seguinte o campo é ignorado, o `MAX + 1` já sai `≥ 2` sozinho.
  Imutável depois de criado — não existe em `DiaryEntryUpdateDTO`.
- **`isRewatch` no primeiro log só é honrado pra `MOVIE`/`SERIES`; pra `EPISODE`/`SEASON` é sempre
  ignorado no primeiro log, forçando `watchNumber = 1`** (`DiaryEntryServiceImpl.persistDiaryEntry`/
  `participatesInCompletionTracking`, corrigido em 2026-08-24, decisão consultada com o usuário) — bug
  encontrado: antes, `isRewatch=true` no primeiro log de **qualquer** tipo pulava direto pro
  `watchNumber=2`, sem nunca existir `watchNumber=1` pra aquele par usuário+conteúdo. Pra `MOVIE`/
  `SERIES` (que não alimentam nenhuma contagem de completude de nível superior) isso é inofensivo e é
  o uso pretendido do campo. Pra `EPISODE`/`SEASON`, porém, isso corrompe silenciosamente o
  alinhamento numérico entre a passada de um episódio/temporada específico e a passada da
  temporada/série que os agrega: `minEpisodeWatchCount`/`minSeasonWatchMax` contam **quantidade** de
  entradas por episódio/temporada (não o valor de `watchNumber`), então a conclusão automática de
  temporada/série ainda dispara certo, mas o `watchNumber` do episódio/temporada mal-rotulado fica
  permanentemente deslocado de +1 em relação aos irmãos — quebrando especificamente o `DELETE` em
  massa escopado por igualdade de `watchNumber` de `wipeSeriesHistory` (ver regra de `DiaryEntry`
  logo abaixo): apagar a entrada de série da "passada 1" não encontraria o episódio mal-rotulado
  (`watchNumber=2`), mesmo ele logicamente pertencendo a essa mesma passada. Por isso `isRewatch` é
  ignorado nesse caso específico (primeiro log, tipo hierárquico) — em qualquer outro log seguinte do
  mesmo `EPISODE`/`SEASON` (`maxWatchNumber > 0`), o campo já era ignorado mesmo antes da correção,
  sem mudança de comportamento.
- **`ignore` marca entradas que só existem como degrau mecânico de um nível superior explicitamente
  pedido — usado por `GET /feed` pra não mostrar cada episódio de um bulk log; ortogonal a
  `autoGenerated`** (`DiaryEntryServiceImpl.isBelowRequestedLevel`, campo `ignore` em `DiaryEntry`,
  decidido em conversa antes de implementar) — cada chamada de criação (`POST /diary` ou
  `POST /diary/bulk`) tem um `requestedType` (o `content.type()` pedido no topo daquela chamada,
  sempre um só). Numa hierarquia EPISODE(0) < SEASON(1) < SERIES(2), `ignore = true` sse o nível da
  entrada sendo criada é **menor** que o nível de `requestedType`; `MOVIE` fica fora da hierarquia
  (sempre `ignore = false`). Isso cobre tanto a entrada logada diretamente (`persistDiaryEntry` em
  `createDiaryEntry`/`bulkLogEpisode`) quanto qualquer entrada de completude gerada pela cascata
  (`maybeCompleteSeason`/`maybeCompleteSeries`, via `persistAutoGeneratedEntry`) — o `requestedType`
  é propagado por `triggerCompletionCascade` até lá. Resultado: um `POST /diary` individual de
  episódio nunca gera `ignore = true` em nada (a própria entrada e qualquer temporada/série que ela
  complete estão no nível pedido ou acima); um `POST /diary/bulk` de temporada marca os episódios
  criados como `ignore = true` mas a própria entrada de temporada (o nível pedido) como
  `ignore = false`; um `POST /diary/bulk` de série marca episódios **e** temporadas intermediárias
  como `ignore = true`, só a entrada de série final fica `ignore = false`. `PATCH /diary/{id}` zera
  `ignore` junto com `autoGenerated` (`DiaryEntryServiceImpl.updateDiaryEntry`) — editar uma entrada
  bulk-child é o usuário tornando aquele registro deliberado, então ele passa a valer a pena mostrar.
  `ignore` só afeta `GET /feed` (`DiaryEntryRepository.findFeedCandidates`, filtro `d.ignore = false`)
  — todo outro endpoint que lista `DiaryEntry` (`GET /users/{id}/diary`, grade de notas de episódios,
  `recentActivity`/`recentlyWatched` do Summary, stats/reviews de `Content`) continua mostrando tudo,
  já que `ignore` é especificamente sobre spam de timeline social, não sobre o histórico do próprio
  usuário. Não precisou de filtro adicional em `recentActivity`/`recentlyWatched`: as duas só
  consultam `DiaryEntry` de nível `MOVIE`/`SERIES`, e `SERIES` nunca fica `ignore = true` (é o maior
  nível da hierarquia, não existe `requestedType` acima dele que o torne "abaixo").
- **`runtimeMinutes` no `POST /diary/bulk` é sempre resolvido pelo backend via TMDB, pros dois tipos
  (`SEASON` e `SERIES`) — nunca mais client-supplied em nenhum dos dois, desde 2026-09-03**
  (`DiaryEntryServiceImpl.bulkLogSeason`/`bulkLogSeries`/`bulkLogEpisode`/
  `episodeRuntimeMinutesFromTmdb`). Passou por quatro versões ao longo de 2026-09-01/02/03: 1ª aceitava
  `episodeRuntimeMinutes` (`SEASON`) / mapa temporada→episódio (`SERIES`) vindos do cliente pros dois
  tipos; 2ª trocou os dois por resolução automática via TMDB; 3ª reverteu só a metade `SEASON` de volta
  pra client-supplied (o cliente numa tela de temporada específica já tem o `runtime` de cada episódio
  em mãos); 4ª (final, 2026-09-03) reverteu a reversão de novo — `SEASON` também passou a derivar do
  TMDB, porque a temporada é a mesma `TmdbSeasonFullDetails` que `bulkLogSeason` **já busca**
  incondicionalmente por outro motivo (`finaleEpisodeNumber`, validação de `watchedDate`), então
  reaproveitar essa mesma resposta pra `runtimeMinutes` não paga nenhuma chamada TMDB extra — o
  argumento de custo que motivou a 3ª versão não se sustentava. Isso fechou, pro tipo `EPISODE`, o
  mesmo gap de "cliente pode gravar `runtimeMinutes` incorreto num `Content` compartilhado" que já
  tinha sido fechado pra `MOVIE`/`SERIES` (ver `genres`/`releaseYear`/`countries`/`runtimeMinutes` na
  seção `Content`).
  - **`SEASON`**: `bulkLogSeason` chama `episodeRuntimeMinutesFromTmdb(seasonDetails)` sobre a mesma
    `TmdbSeasonFullDetails` já buscada, e passa `trustedRuntimeMinutes = true` pra
    `bulkLogEpisode`/`getOrCreateReference` — mesmo tratamento que `SERIES` já tinha. O campo
    `DiaryEntryBulkCreationDTO.episodeRuntimeMinutes` foi removido do contrato (cliente não envia mais
    nada de runtime nesse request).
  - **`SERIES`**: inalterado — busca, uma vez por temporada envolvida no bulk (não por episódio — a
    resposta de `/tv/{seriesId}/season/{seasonNumber}` já traz o `runtime` de todos os episódios
    daquela temporada de uma vez, `TmdbEpisodeSummary.runtime`), e alimenta
    `ContentRefCreation.runtimeMinutes` de cada `EPISODE` criado. Decisão consciente de **não** usar
    uma média (total da série ÷ número de episódios) escrita direto no `Content` — teria dois
    problemas: o valor aproximado ficaria permanente e compartilhado entre todos os usuários, e não
    haveria como reverter/corrigir sem uma segunda estrutura de dados. Episódio sem `runtime` na
    resposta do TMDB simplesmente não recebe `runtimeMinutes`. Se o TMDB estiver indisponível ao
    buscar uma dessas temporadas, o bulk inteiro falha com `502` em vez de logar sem `runtimeMinutes`.
- **Fora do bulk, `EPISODE.runtimeMinutes` também deixou de ser client-supplied — o backend faz uma
  chamada nova `getEpisodeFullDetails` quando cria um `EPISODE` novo e não recebeu um valor confiável
  de um chamador interno** (`ContentServiceImpl.resolveNewContentMetadata`/`backfillMissingTmdbMetadata`,
  overload `getOrCreateReference(dto, trustedRuntimeMinutes)` — `trustedRuntimeMinutes = true` vem de
  `bulkLogSeason`/`bulkLogSeries` e, desde 2026-09-04, também do `POST /diary` de uma entrada
  individual de `EPISODE` (`DiaryEntryServiceImpl.withDerivedEpisodeFinaleFlags`, que já busca a
  temporada pra derivar `isSeasonFinale`/`isSeriesFinale` e extrai o `runtime` do episódio sendo
  logado dessa mesma resposta — nenhuma chamada TMDB extra); `POST /contents/reference` direto, e o
  próprio `POST /diary` individual quando a temporada retornada pelo TMDB ainda não tem o `runtime`
  daquele episódio específico, usam `trustedRuntimeMinutes = false` e pagam essa chamada nova —
  cacheada, então só custa de verdade na primeira vez que aquele episódio específico é referenciado
  por qualquer usuário). Como efeito colateral, essa chamada também verifica que aquele número de
  episódio existe de verdade na temporada — fecha, só pra esse caminho, o gap que `assertExistsOnTmdb`
  (ver abaixo) deixava aberto (verificava só a série-mãe, nunca o número da temporada/episódio em si).
- **`POST /diary` de uma entrada individual não faz mais duas chamadas TMDB pro mesmo `tmdbId`/
  `seriesTmdbId` quando o conteúdo ainda não existe como `Content`** (auditoria
  `docs/pending/tmdb-request-audit-2026-09-04.md`, corrigido em 2026-09-04). Antes, `resolveReleaseDate`
  (validação de `watchedDate` contra a data de lançamento) consultava o TMDB no idioma preferido do
  usuário, e `getOrCreateReference` consultava de novo em `en-US` — como o cache do `TmdbClient` é
  chaveado por `(id, idioma)`, as duas só reaproveitavam uma da outra se o usuário usasse `en-US`.
  `DiaryEntryServiceImpl.resolveReleaseDate` (usado tanto na criação quanto em `updateDiaryEntry`)
  passou a sempre consultar `TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE` (a mesma constante
  `en-US` que `getOrCreateReference` já usava — ver `assertExistsOnTmdb` abaixo), já que `releaseDate`
  não varia por idioma no TMDB — a segunda chamada agora bate na mesma entrada de cache da primeira.
  Fecha a duplicidade pra `MOVIE`; pra `EPISODE`, a mesma auditoria motivou o item logo acima
  (`trustedRuntimeMinutes = true` reaproveitando a busca de temporada), já que ali a duplicidade era
  de endpoint (`getSeasonFullDetails` vs. `getEpisodeFullDetails`), não de idioma.
- **`genres`/`releaseYear`/`countries`/`runtimeMinutes` (`MOVIE`) do `content` de nível superior de
  `POST /diary/bulk` são sempre rejeitados com `400` — nunca aceitos, nem pra `SEASON` nem pra
  `SERIES`** (`ContentServiceImpl.validate`, aplicado uniformemente já que `MOVIE`/`SERIES` também
  passaram a rejeitar esses campos — ver seção `Content`). Antes de 2026-09-03, `SERIES` os aceitava
  silenciosamente sem validar (nem rejeitava, nem usava); agora o comportamento é o mesmo pros dois
  tipos: `400` explícito.
- **Pra `type = SERIES`, `genres`/`releaseYear`/`countries` continuam derivados sozinhos do TMDB pelo
  backend** — só que desde 2026-09-03 essa derivação não é mais um método específico de
  `DiaryEntryServiceImpl` (`backfillSeriesMetadataIfNeeded`, removido), e sim o comportamento padrão de
  `ContentServiceImpl.getOrCreateReference` pra **qualquer** criação/referência de `Content` tipo
  `SERIES`, não só a que passa pelo bulk (ver seção `Content` — mesma lógica agora vale pra
  `Top5Entry`/`Watchlist`/`UserListItem`/`POST /contents/reference` direto). `bulkLogSeries` continua
  chamando `getOrCreateReference` com uma referência nua (`ContentRefCreationDTO` sem nenhum dos três
  campos) logo no início, antes do loop de episódios, só que agora puramente pelo efeito colateral de
  garantir que o `Content` `SERIES` existe e tem a metadata preenchida — a derivação em si já é
  responsabilidade do `ContentServiceImpl` chamado, best-effort quando a referência já existe (ver
  `backfillMissingTmdbMetadata` na seção `Content`), hard-fail (`404`/`502`) só na primeira vez que
  aquele `tmdbId` de série é referenciado por qualquer usuário, mesma regra de qualquer `MOVIE`/`SERIES`
  novo.
- **`ContentService.getOrCreateReference` verifica a existência do `tmdbId`/`seriesTmdbId` no TMDB
  antes de criar uma referência nova — só na primeira vez que essa referência é criada, nunca pra
  uma já existente** (`ContentServiceImpl.resolveNewContentMetadata`/`requireFound`, decidido
  2026-09-03, depois de uma auditoria mostrar que nenhum caminho de escrita do app verificava se o
  `tmdbId` informado pelo cliente correspondia a algo real — qualquer string virava uma linha de
  `Content` permanente e compartilhada). Chamado logo depois de `findExisting(normalized)` devolver
  vazio, antes do bloco de criação:
  - `MOVIE` → `TmdbClient.getMovieFullDetails(tmdbId, "en-US")`; `SERIES` →
    `TmdbClient.getTvFullDetails(tmdbId, "en-US")` (a mesma resposta também alimenta
    `genres`/`releaseYear`/`countries`/`runtimeMinutes`, ver seção `Content`); `SEASON`/`EPISODE` →
    também `getTvFullDetails`, mas com `seriesTmdbId` — só confirma que a série existe, **não**
    verifica se aquele `seasonNumber` é real (`EPISODE` fecha isso pro número do episódio, via a
    chamada `getEpisodeFullDetails` do item acima, quando `trustedRuntimeMinutes = false`; `SEASON`
    continua sem checagem própria do `seasonNumber`).
  - Idioma fixo `en-US` pra essa checagem (constante pública `TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE`,
    antes uma constante privada de `ContentServiceImpl` chamada `EXISTENCE_CHECK_LANGUAGE` — promovida
    a compartilhada em 2026-09-04 pra que `DiaryEntryServiceImpl.resolveReleaseDate` também pudesse usar
    a mesma chave de cache, ver logo abaixo), independente do `preferredLanguage` de quem chama —
    `getOrCreateReference` não tem acesso a esse contexto (é chamado por
    `Top5EntryServiceImpl`/`WatchlistEntryServiceImpl`/`DroppedEntryServiceImpl`/
    `UserListItemServiceImpl`/`DiaryEntryServiceImpl`/`ContentTrackingServiceImpl`/
    `FollowedPersonTrackingServiceImpl` sem nenhum deles passar idioma) e, como só o *status* da
    resposta importa aqui (existe ou não), o idioma da checagem é irrelevante pro resultado —
    fica numa entrada de cache própria, sem "esquentar" o cache do idioma real do usuário.
  - `TmdbLookupResult.NotFound` → `NotFoundException` (`404`, "No movie/series/episode found on TMDB
    for the given id" — pra `SEASON`/o caminho série-mãe de `EPISODE` a mensagem sempre fala
    "series", já que é o `seriesTmdbId` que foi verificado). `TmdbLookupResult.Unavailable` →
    `TmdbUnavailableException` (`502`), igual a qualquer outra chamada TMDB do app.
  - `TmdbClient` foi refatorado pra isso: os quatro métodos `get*FullDetails` passaram de
    `Optional<T>` pra `TmdbLookupResult<T>` (`Found`/`NotFound`/`Unavailable`, `sealed interface`),
    já que um 404 confirmado do TMDB e uma indisponibilidade real (timeout, 5xx, rede) antes eram
    indistinguíveis (os dois viravam `Optional.empty()`). `callWithRetry` agora nunca reten­ta um
    404 (não tem por quê — o TMDB não vai mudar de ideia na mesma requisição), só retenta falhas
    genuinamente transitórias. Todo caller pré-existente (`ContentDetailsServiceImpl`,
    `DiaryEntryServiceImpl`) ganhou um `.toOptional()` mecânico pra manter o comportamento antigo
    inalterado (`NotFound`/`Unavailable` ainda colapsam pra `Optional.empty()` → `502` nesses
    pontos, sem distinção — só `ContentServiceImpl.requireFound` usa a distinção de verdade). O cache
    (`@Cacheable`, `unless = "#result.isUnavailable()"` nessa época — trocado por Caffeine chamado
    direto em `TmdbClient.cachedLookup` em 2026-09-04, ver seção `Content`, mesma regra preservada
    com outro mecanismo) agora nunca guarda uma falha transitória — diferente de antes, quando
    `Optional.empty()` por indisponibilidade ficava cacheado pela TTL inteira de 24h por acidente
    (Spring desembrulha `Optional` antes de avaliar `unless`, então `#result == null` funcionava
    certo antes só por coincidência; `TmdbLookupResult` não é desembrulhado, então
    `#result.isUnavailable()` funcionava do jeito certo, de propósito, enquanto o mecanismo ainda
    era `@Cacheable`).
  - Efeito colateral em `POST /diary/bulk`: como `bulkLogEpisode` cria cada `EPISODE` via
    `getOrCreateReference`, todo episódio novo de um bulk de `SEASON`/`SERIES` passa a chamar
    `getTvFullDetails` pela primeira vez nesse caminho (antes, bulk de `SEASON` só usava
    `getSeasonFullDetails` — nunca precisava de `getTvFullDetails`). Como é a mesma chave de cache
    (`seriesTmdbId`+idioma) pra todos os episódios da mesma série numa mesma chamada, só o primeiro
    episódio paga uma requisição real ao TMDB — os demais reaproveitam o cache. Como
    `trustedRuntimeMinutes = true` em ambos os bulks, `getEpisodeFullDetails` nunca é chamado ali —
    a verificação por-episódio (ver item acima) só se aplica fora do bulk.
- **`finaleEpisodeNumber`/`finaleSeasonNumber`/`seasonFinaleEpisodeNumbers`, em `POST /diary/bulk`,
  deixaram de ser obrigatórios quando não há finale conhecido no banco — o backend deriva do TMDB
  como fallback, mantendo o valor explícito como override opcional**
  (`DiaryEntryServiceImpl.resolveSeasonFinaleEpisodeNumber`/`resolveSeriesFinaleSeasonNumber`/
  `airedEpisodeCount`/`latestAiredSeasonNumber`, decidido em conversa antes de implementar, no mesmo
  dia da mudança de `runtimeMinutes` acima). Antes, omitir esses campos sem já existir um finale
  conhecido no banco (`Content.isSeasonFinale`/`isSeriesFinale`) sempre resultava em `400` — a
  documentação orientava o cliente a derivar o valor sozinho de
  `ContentDetailsDTO.seasons[].airedEpisodeCount`. Como o bulk já busca `TmdbClient.getSeasonFullDetails`
  de toda temporada envolvida, incondicionalmente, por causa do `runtimeMinutes` acima, calcular
  `airedEpisodeCount` (quantos `episodes[]` têm `airDate` não-futuro) a partir dessa mesma resposta é
  gratuito pra `finaleEpisodeNumber` (`SEASON`) e pra cada entrada de `seasonFinaleEpisodeNumbers`
  (`SERIES`) — nenhuma chamada nova ao TMDB. Já `finaleSeasonNumber` (`SERIES`) precisa de uma
  chamada nova, `TmdbClient.getTvFullDetails`, que o bulk não fazia antes — resolvida como a maior
  temporada (excluindo a 0/especiais) com `airDate` não-futuro em `seasons[]`. Diferente do
  `runtimeMinutes` (removido do request), esses três campos continuam aceitos como valor explícito.
  Se a derivação do TMDB também não encontrar nenhum episódio/temporada lançado (série anunciada mas
  ainda não estreada) e nenhum valor explícito foi enviado, continua `400`, mesma mensagem de antes.
  `bulkLogSeries` foi reestruturado pra buscar cada temporada do TMDB uma única vez (guardada num mapa
  local reaproveitado pelas duas passadas — contagem total e criação — do método), em vez de arriscar
  buscar a mesma temporada duas vezes.
  - **A derivação automática do TMDB passou a ter prioridade sobre o valor explícito do cliente, e um
    valor explícito usado como fallback é validado contra a contagem real que o TMDB já retornou na
    mesma chamada** (invertido em 2026-09-03, no mesmo dia de `ContentServiceImpl.assertExistsOnTmdb`).
    Na versão original acima, "qual episódio/temporada é o finale" era tratado como declaração de
    intenção do cliente — o valor explícito vencia mesmo quando o TMDB já sabia responder sozinho, e
    nunca era comparado contra a contagem real de `episodes[]`/`seasons[]` que a mesma resposta do TMDB
    já trazia. Isso permitia, por exemplo, `finaleEpisodeNumber: 100` numa temporada real de 10
    episódios — o backend criava 100 `Content` do tipo `EPISODE`, a maioria fake, sem reclamar. A ordem
    de resolução agora é: (1) finale já confirmado no banco (`Content.isSeasonFinale`/`isSeriesFinale`)
    sempre vence, sem consultar cliente nem TMDB; (2) senão, tenta derivar do TMDB
    (`airedEpisodeCount`/`latestAiredSeasonNumber`) — se o TMDB souber responder (`>= 1`), esse valor é
    usado e o valor explícito do cliente é ignorado; (3) só quando o TMDB não souber responder (série
    anunciada mas ainda não estreada, ou indisponível) o valor explícito entra em jogo — e, quando
    usado, é validado contra `realSeasonEpisodeCount`/`realSeasonCount` (contagem de
    `episodes[]`/`seasons[]` que o TMDB retornou na mesma chamada, independente de `airDate`),
    rejeitando com `400` um valor que ultrapasse essa contagem quando ela for conhecida (`> 0`). Pra
    `SEASON`, isso não custa nenhuma chamada nova ao TMDB (`seasonDetails` já é buscado
    incondicionalmente antes de `resolveSeasonFinaleEpisodeNumber` ser chamado). Pra `SERIES`
    (`resolveSeriesFinaleSeasonNumber`), `TmdbClient.getTvFullDetails` passou a ser chamado
    incondicionalmente (antes só quando o valor vinha omitido) — mas, deliberadamente, só bloqueia com
    `TmdbUnavailableException` (`502`) quando o TMDB está indisponível **e** nenhum valor explícito foi
    enviado; se o cliente mandou um explícito, o bulk segue confiando nele (sem checagem de teto, já
    que não há contagem real disponível pra comparar) mesmo com o TMDB fora do ar — preserva a
    resiliência que a versão anterior já tinha pra esse caso, evitando transformar um caminho que antes
    não precisava do TMDB numa dependência rígida dele.
- **`MAX_BULK_EPISODES` (`POST /diary/bulk`) é um teto flexível de 2000 episódios, não mais um limite
  rígido de 100** (`DiaryEntryServiceImpl.MAX_BULK_EPISODES`/`bulkLogSeason`/`bulkLogSeries`, alterado
  em 2026-09-03). O valor original de 100 rejeitava até séries comuns de porte médio (uma série com 5
  temporadas de ~24 episódios já soma mais de 100 no total). Acima de 2000, em vez de rejeitar
  incondicionalmente, o backend verifica a contagem real de episódios no TMDB antes de decidir: pra
  `SEASON`, compara com `TmdbSeasonFullDetails.episodes().size()` (já buscado para outras validações
  do bulk); pra `SERIES`, faz uma chamada adicional (cacheada) a `TmdbClient.getTvFullDetails` e
  compara com `number_of_episodes`. Se a contagem pedida bate com o TMDB (ou é menor), o bulk
  prossegue sem teto superior algum — cobre séries genuinamente longas (novelas diárias, animes de
  centenas/milhares de episódios). Se não bate (ou o TMDB não informa `number_of_episodes`), `400`
  com mensagem indicando que a contagem não pôde ser verificada — fecha a brecha de um cliente
  inflar `finaleEpisodeNumber`/`seasonFinaleEpisodeNumbers` explicitamente muito além do que a série
  realmente tem. Esse caminho de verificação só é acionado quando o valor pedido vem de um override
  explícito acima do teto — o caminho auto-derivado do TMDB (sem override) já é sempre baseado em
  dado real do TMDB, então nunca ultrapassa o que o próprio TMDB reportaria.
- **`watchedDate` passou a ser validado no servidor — nunca no futuro (`POST /diary`,
  `POST /diary/bulk`, `PATCH /diary/{id}`), e nunca antes da data de lançamento do último episódio
  logado (só `POST /diary/bulk`)** (`DiaryEntryServiceImpl.assertWatchedDateNotInFuture`/
  `assertWatchedDateNotBeforeRelease`, decidido em conversa antes de implementar, mesmo dia das duas
  mudanças acima). Antes, os dois eram documentados como responsabilidade só do cliente ("neither rule
  is validated server-side"), já que `Content` nunca guarda data de lançamento e validar exigiria
  consultar o TMDB a cada escrita. A checagem "não no futuro" não depende de TMDB (só
  `LocalDate.now()`), então virou grátis nos três endpoints de escrita de `watchedDate`, aplicada o
  quanto antes em cada um (antes de qualquer chamada ao `ContentService`/TMDB, inclusive antes da
  checagem de tipo em `POST /diary/bulk`). Já "não antes do lançamento" só ficou possível pra
  `POST /diary/bulk` (`SEASON`/`SERIES`) — reaproveita a mesma resposta de
  `TmdbClient.getSeasonFullDetails` já buscada pro `runtimeMinutes`/derivação de finale acima, sem
  chamada nova; comparado só contra o último episódio efetivamente logado no lote
  (`DiaryEntryServiceImpl.episodeAirDate` no `finaleEpisodeNumber` da `SEASON`, ou no `finaleEpisodeNumber`
  da última temporada da `SERIES`), não contra todo episódio individualmente — como episódios de uma
  temporada/série vão ao ar em ordem cronológica, o último cobre a checagem de todos os anteriores.
  `POST /diary`/`PATCH /diary/{id}` (filme, episódio avulso fora do bulk) passaram a ter a mesma
  checagem em 2026-09-03 (`DiaryEntryServiceImpl.resolveReleaseDate`, fecha o gap documentado acima) —
  `MOVIE` via `TmdbClient.getMovieFullDetails`, `EPISODE` via `TmdbClient.getSeasonFullDetails` (contra
  o `airDate` do episódio específico sendo logado, não do finale do lote). Diferente do bulk, aqui a
  chamada TMDB passou a ser feita **sempre**, mesmo quando o `Content` já existe e não precisaria de
  nenhuma chamada por outro motivo — troca deliberada de custo (uma chamada TMDB cacheada a mais por
  log de filme/episódio avulso, cara só na primeira vez por chave+idioma) por essa validação existir
  nesses dois caminhos, que antes nunca pagavam nenhum custo de TMDB pra conteúdo já referenciado.
  `SEASON`/`SERIES` não passam por essa checagem em `PATCH /diary/{id}` (`resolveReleaseDate` devolve
  `null` pra esses dois tipos) — são marcadores sintéticos de conclusão, sem uma data de lançamento
  própria que faça sentido validar. Episódio/filme sem `airDate`/`releaseDate` parseável no TMDB (dado
  ausente/malformado) simplesmente não é checado, mesma filosofia de degradar sem bloquear usada em
  `airedEpisodeCount`/`ContentDetailsServiceImpl.parseDate`.
- **`isSeasonFinale`/`isSeriesFinale` deixaram de ser client-supplied em `POST /diary` (episódio avulso
  fora do bulk) em 2026-09-03 — o backend deriva as duas do TMDB reaproveitando a mesma chamada
  `getSeasonFullDetails` da regra acima** (`DiaryEntryServiceImpl.withDerivedEpisodeFinaleFlags`/
  `deriveSeriesFinaleFlag`) — fechando, pra esse endpoint, a exceção nº1 documentada em `CLAUDE.md` §
  Avoid ("ainda client-supplied — as únicas duas das seis que são"). Mesma filosofia já usada em
  `POST /diary/bulk` (`resolveSeasonFinaleEpisodeNumber`/`resolveSeriesFinaleSeasonNumber`, ver regra
  acima): se o TMDB souber quantos episódios da temporada já foram ao ar (`airedEpisodeCount >= 1`), o
  valor derivado (`episódio logado == último episódio exibido`) sempre vence e qualquer valor enviado
  pelo cliente é descartado — inclusive um `true` explícito que o TMDB contradiga. Só quando o TMDB não
  souber responder (`airedEpisodeCount == 0` — temporada anunciada mas sem episódios exibidos ainda, ou
  indisponível) o valor enviado pelo cliente é preservado sem alteração, mesmo fallback do bulk pro
  mesmo cenário. `isSeriesFinale` só é derivado quando `isSeasonFinale` também é verdadeiro (mesma
  temporada precisa ser, ao mesmo tempo, a finale da temporada **e** a última temporada já exibida da
  série, via `TmdbClient.getTvFullDetails`/`latestAiredSeasonNumber` — chamada TMDB adicional, feita só
  nesse caso). `POST /contents/reference` (registro direto de referência, fora do fluxo de diário) não
  foi alterado — continua aceitando as duas flags como client-supplied, é o único ponto de entrada
  genuinamente "client-supplied" que sobra pras duas. `POST /diary/bulk` e `PATCH /diary/{id}` também
  não foram alterados por essa mudança — o primeiro já derivava as duas flags por conta própria desde
  antes; o segundo não recebe conteúdo novo (só edita uma `DiaryEntry` já existente), então não há flag
  de finale pra (re)derivar ali.
- **"Assistido com" (`WatchCompanion`, tabela nova `watch_companions`) marca pessoas que o usuário
  assistiu aquele conteúdo junto — escopado por passada, não por usuário+conteúdo** (`DiaryEntry.watchedWith`
  em `POST /diary`, `POST /diary/bulk` e `PATCH /diary/{id}`, `DiaryEntryServiceImpl.validateCompanions`/
  `saveCompanions`/`loadWatchedWith`) — feature nova, fora do escopo original de `openapi.yaml`/
  `database-schema.html`/`development-stages.md`, desenhada em conversa antes de implementar. Cada
  `DiaryEntry` (cada `watchNumber`) tem seu próprio conjunto de companions; reassistir sozinho depois
  não herda nada da passada anterior, mesmo padrão de todo o resto de `DiaryEntry` (score, comment,
  watchNumber já são todos por-passada).
  - **Só pode marcar quem o próprio dono segue, com status `ACCEPTED`** — `400` caso contrário
    (`"watchedWith can only include users you follow"`), checado contra `FollowerRepository`, mesma
    regra padrão usada em toda visibilidade do projeto. Marcar a si mesmo também é `400`
    (`"watchedWith cannot include yourself"`). Não importa se a pessoa marcada segue o dono de volta —
    a checagem é unidirecional. Ids duplicados na lista são deduplicados silenciosamente, não rejeitados.
  - **Sem consentimento, sem notificação, sem visibilidade reversa** — decidido em conversa
    conscientemente: a pessoa marcada nunca é avisada, não precisa aceitar, e a marcação nunca aparece
    em nenhum lugar do perfil/diário dela — só existe do lado de quem marcou, visível a quem já pode ver
    aquela `DiaryEntry` (mesma regra padrão de visibilidade, sem checagem extra).
  - **Bulk (`POST /diary/bulk`) aplica a mesma lista, validada uma única vez, a todo episódio criado
    naquela chamada** (`createDiaryEntriesInBulk` valida antes do loop, propaga por
    `bulkLogSeason`/`bulkLogSeries`/`bulkLogEpisode`) — não existe personalização por episódio dentro
    de um mesmo bulk.
  - **Temporada/série autogerada por completude herda companion só por unanimidade dos filhos diretos
    daquele nível — calculado uma vez, no momento da completude, nunca revisitado depois**
    (`DiaryEntryServiceImpl.computeUnanimousCompanions`, chamado de dentro de `maybeCompleteSeason`/
    `maybeCompleteSeries` antes de `persistAutoGeneratedEntry`) — temporada olha só os `EPISODE`
    diretos dela (`findEpisodeEntriesInSeasonByWatchNumber`); série olha só as `SEASON` diretas dela
    (`findSeasonEntriesInSeriesByWatchNumber`, já existente, reaproveitada) — nunca os episódios crus
    de todas as temporadas de uma vez. Só herda a marcação quando **todos** os filhos diretos daquela
    passada têm exatamente o mesmo conjunto de companions (nenhum filho sem marcação, nenhum filho com
    um conjunto diferente); qualquer divergência (incluindo um filho sem nenhuma marcação) deixa a
    entrada autogerada sem companion, sem tentar interseção parcial. Isso faz o mecanismo funcionar
    igual tanto pra bulk (todo episódio já nasce com a mesma lista, então a unanimidade é trivial)
    quanto pra passada orgânica episódio-a-episódio (só herda se o usuário realmente assistiu tudo com
    a(s) mesma(s) pessoa(s)) — um único mecanismo cobre os dois casos, sem precisar saber se a origem
    foi bulk ou individual.
  - **A gravação do companion roda dentro da mesma transação `REQUIRES_NEW` da entrada autogerada**
    (`persistAutoGeneratedEntry`) — mesmo motivo já documentado pra `getOrCreateReference`/
    `followPerson`: constrói e salva o `WatchCompanion` no mesmo lambda que persiste a `DiaryEntry`,
    pra manter as duas escritas atômicas e não associar uma entidade obtida por `getReferenceById` a
    duas sessões Hibernate diferentes.
  - **`PATCH /diary/{id}` substitui a lista inteira, não faz merge — `null` não mexe, qualquer lista
    (mesmo vazia) apaga tudo e regrava** (`DiaryEntryServiceImpl.updateDiaryEntry`, delete-then-insert
    via `WatchCompanionRepository.deleteByDiaryEntryId` seguido de `saveCompanions`) — mesma semântica
    de "campo nulo = não mexe" já usada pelos outros campos de `DiaryEntryUpdateDTO`, mas como
    `watchedWith` é uma lista, "não mexe" e "lista vazia" precisam ser estados distintos (`null` vs
    `List.of()`), diferente de `comment`/`score` onde não há essa ambiguidade.
  - **`GET /feed` reaproveita `watchedWith` sem query nova** — `FeedServiceImpl` já faz o mesmo batch
    load (`WatchCompanionRepository.findByDiaryEntryIdIn`) usado em `getDiaryEntries`/
    `getReviewsForContent`, só que sobre a página de `DiaryEntry` que o feed já buscou; `DROPPED`/
    `TOP5_UPDATE` nunca carregam `watchedWith` (`null`), já que a marcação é exclusiva de `DiaryEntry`.
  - **`ON DELETE CASCADE` no FK de `diary_entry_id`, sem código de limpeza explícito** — apagar uma
    `DiaryEntry` por qualquer um dos vários caminhos existentes (`deleteDiaryEntry`, `wipeSeriesHistory`,
    `retractSeasonIfIncomplete`/`retractSeriesIfIncomplete`, `deleteAllDiaryEntriesForSeries`) já limpa
    os `WatchCompanion` dela automaticamente no banco, mesmo padrão de cascata via constraint já usado
    em `likes`.
- **`content` é imutável depois de criada a entrada** — `PATCH /diary/{id}` (`DiaryEntryUpdateDTO`) não
  aceita `content`; para logar um conteúdo diferente é preciso criar uma nova entrada, não editar a
  existente.
- **Não existe entidade `Rating`/review separada**: logar, avaliar (`score` 1–10) e comentar
  (`comment`) um conteúdo são a mesma ação — `score` e `comment` são opcionais na própria `DiaryEntry`,
  então dá pra logar um "assisti" sem nota nem review. A nota "atual" de um usuário para um conteúdo é a
  da `DiaryEntry` mais recente daquele par, não uma média — um rewatch com nota diferente simplesmente
  substitui a anterior para efeitos de exibição.
- **Reassistir não é bloqueado**: não há checagem de unicidade usuário+conteúdo em `DiaryEntry` — o
  mesmo par pode ter quantas entradas quiser (cada rewatch é uma linha nova).
- **`findByUserIdWithFilters` precisa de `CAST(... AS date)` explícito na checagem `IS NULL` de
  `watchedDateStart`/`watchedDateEnd`, senão o Postgres rejeita a query inteira** — descoberto em
  2026-08-27 ao rodar a suíte pela primeira vez contra um Postgres real (Docker indisponível até então
  nesta sessão). `(:watchedDateStart IS NULL OR d.watchedDate >= :watchedDateStart)` gera dois
  parâmetros JDBC posicionais distintos pro mesmo parâmetro nomeado (um por ocorrência no texto da
  query); o que aparece **só** dentro de `IS NULL` (sem nenhuma outra ocorrência tipada na mesma query)
  não dá ao Postgres contexto suficiente pra inferir o tipo na fase de `Parse` do protocolo estendido,
  e a query falha com `could not determine data type of parameter $N` — mesmo com um valor `LocalDate`
  não-nulo de verdade sendo passado em runtime (o erro é de preparação da query, não do valor). Afeta
  todo `GET /users/{id}/diary` com `year`/`dateFrom`/`dateTo` informado — quebrado desde o commit que
  introduziu esses filtros até esta correção. `:type`/`:hasReview` não sofrem do mesmo problema (String/
  Boolean resolvem sem erro nesse mesmo padrão); só os dois parâmetros `LocalDate` precisaram do `CAST`.
- **Filtros de `getDiaryEntries` usam `watchedDate`, não `createdAt`** — uma entrada sem `watchedDate`
  preenchido nunca aparece em nenhum filtro de data (`year`, `dateFrom`/`dateTo`), mesmo que tenha sido
  criada naquele período. `year` é um atalho pra `dateFrom=01/01`/`dateTo=31/12` do mesmo ano, resolvido
  antes de decidir a query — combinar `year` com `dateFrom`/`dateTo` explícitos é `400`
  (`DiaryEntryServiceImpl.getDiaryEntries`). `type` e `hasReview` são filtros independentes, aplicáveis
  junto com qualquer combinação de data; `hasReview` compara `comment IS NOT NULL`/`IS NULL`. Sem
  nenhum filtro, a query original (`findByUserIdOrderByCreatedAtDesc`) continua sendo usada — a nova
  query flexível (`findByUserIdWithFilters`, com `AND (:param IS NULL OR ...)` por filtro) só entra
  quando pelo menos um filtro é passado, pra não pagar o custo de uma query maior no caso comum.
- **Dono do recurso em `PATCH`/`DELETE` é resolvido por `userId` batendo, não por dono explícito na
  URL** (`findOwnedEntry`) — tentar editar/apagar a entrada de outro usuário devolve `404` ("Diary entry
  not found"), não `403`, para não revelar que o recurso existe e pertence a outra pessoa (mesmo padrão
  usado em `Top5EntryServiceImpl.removeEntry`).
- **Completar todos os episódios de uma temporada cria automaticamente a `DiaryEntry` da temporada; completar todas as temporadas cria a da série** (`DiaryEntryServiceImpl.maybeCompleteSeason`/`maybeCompleteSeries`, chamados de `createDiaryEntry` via `triggerCompletionCascade`) — depende do cliente marcar `isSeasonFinale` (em `EPISODE`) e `isSeriesFinale` (em `EPISODE` ou `SEASON`) ao logar, já que o backend nunca chama o TMDB; a checagem roda em todo episódio/temporada logado, não só quando o finale é logado (o usuário pode assistir fora de ordem). A `DiaryEntry` auto-criada tem `comment`/`score` nulos, `watchedDate` igual ao da entrada que completou o conjunto, e `autoGenerated = true`.
- **Rewatches completos também disparam auto-completude, uma passada por vez** (`DiaryEntryServiceImpl.minEpisodeWatchCount`/`minSeasonWatchMax`, laço `while` em `maybeCompleteSeason`/`maybeCompleteSeries`) — o número de passadas completas já cobertas de uma temporada/série é o mínimo de vezes que cada episódio/temporada do conjunto foi logado (`countEntriesByEpisodeNumberInSeason`/`maxWatchNumberBySeasonInSeries`); se esse mínimo é maior que o `watchNumber` mais alto já existente para a temporada/série, uma nova `DiaryEntry` auto-gerada é criada para cada passada em aberto (o laço roda uma vez por passada faltante, disparando `triggerCompletionCascade` recursivamente a cada uma). Reduz ao comportamento de passada única quando só há uma passada completa.
- **Criação de `DiaryEntry` auto-gerada é idempotente e resistente a corrida** (`DiaryEntryServiceImpl.persistAutoGeneratedEntry`, mesmo padrão de `ContentServiceImpl.getOrCreateReference`) — roda numa transação nova (`NewTransactionExecutor.runInNewTransaction`) para não deixar uma falha de `saveAndFlush` envenenar a transação ambiente de `createDiaryEntry`; se dois requests concorrentes tentam criar a mesma passada auto-gerada (mesmo `userId`+`contentId`+`watchNumber`, colidindo com `uq_diary_entries_user_content_watch_number`), quem perde a corrida busca de novo por `findFirstByUserIdAndContentIdAndWatchNumber` e usa a entrada que o outro request já criou, em vez de propagar erro.
- **Apagar um episódio/temporada retrai só as passadas de auto-completude que perderam sustento, não sempre "a" entrada mais recente** (`DiaryEntryServiceImpl.retractSeasonIfIncomplete`/`retractSeriesIfIncomplete`, apoiados em `computeSeasonRetractionCandidates`/`computeSeriesRetractionCandidates`) — após o delete, recalcula o número mínimo de passadas completas (`minEpisodeWatchCount`/`minSeasonWatchMax`) e busca, via `findByUserIdAndContentIdAndWatchNumberGreaterThan`, toda `DiaryEntry` da temporada/série cujo `watchNumber` ficou acima desse novo mínimo; só as candidatas com `autoGenerated = true` são de fato apagadas. Isso é multi-passada: com múltiplos rewatches completos, cada um vira sua própria `DiaryEntry` auto-gerada (`watchNumber` 1, 2, 3...), e apagar o suporte de uma passada específica retrai só essa passada, preservando as passadas anteriores que ainda têm sustento. Uma temporada/série criada ou editada manualmente (`autoGenerated = false`) não é removida por essa lógica por padrão, mesmo que fique acima do threshold — salvo quando o cliente passa `overrideProtectedEntries = true` (ver bullet abaixo sobre a flag de override).
- **Apagar uma `DiaryEntry` de série (`ContentType = SERIES`) diretamente limpa só a própria passada (`watchNumber`) de auto-completude da série** (`DiaryEntryServiceImpl.wipeSeriesHistory`) — busca as `DiaryEntry` da série (tipo `EPISODE`, `SEASON` e `SERIES`) cujo `watchNumber` seja **igual** ao da entrada de série apagada (`findEpisodeEntriesInSeriesByWatchNumber`/`findSeasonEntriesInSeriesByWatchNumber`/`findSeriesEntriesByWatchNumber`), filtra apenas as com `autoGenerated = true` e as deleta em uma única operação. Entradas criadas ou editadas manualmente (`autoGenerated = false`) são preservadas por padrão — salvo com `overrideProtectedEntries = true`. A proteção `autoGenerated` é a mesma usada em `retractSeasonIfIncomplete`/`retractSeriesIfIncomplete` (mesma flag de override, ver bullet abaixo), garantindo que um usuário não apague acidentalmente uma série inteira se tiver modificado alguma entrada manualmente. **Corrigido em 2026-08-24:** antes disso, a busca ignorava `watchNumber` e trazia **todas** as `DiaryEntry` da série, de qualquer passada — apagar uma única entrada de nível SERIES (ex.: um registro duplicado de rewatch) arrastava consigo episódios/temporadas de passadas completamente não relacionadas (inclusive a primeira passada, "intocável", enquanto só a passada duplicada deveria sumir), ao contrário do padrão de escopo por `watchNumber` já usado em `retractSeasonIfIncomplete`/`retractSeriesIfIncomplete`. Como o `watchNumber` é imutável e cada passada de rewatch é uma `DiaryEntry` própria (ver bullet acima sobre `watchNumber`), escopar a busca pela mesma passada do que foi apagado é proporcional: remove exatamente o que aquele registro específico sustentava, preserva as demais passadas.
- **`DELETE /diary/series/{seriesTmdbId}` apaga TODAS as passadas de uma série de uma vez, sem
  proteção de `autoGenerated`** (`DiaryEntryServiceImpl.deleteAllDiaryEntriesForSeries`) — diferente de
  `wipeSeriesHistory`/`DELETE /diary/{id}` acima, que escopam por `watchNumber` igual ao da entrada
  apagada (uma passada por vez), este endpoint busca toda `DiaryEntry` `EPISODE`/`SEASON`/`SERIES` do
  usuário para aquele `seriesTmdbId` sem filtrar `watchNumber` — todos os rewatches somem numa única
  chamada. Também não passa por `deleteRespectingProtection`: como é um pedido direto e explícito de
  apagar tudo (não um efeito colateral de cascata sobre entradas que o usuário não pediu diretamente
  pra apagar), entradas manuais/editadas (`autoGenerated = false`) são removidas também, sem parâmetro
  `overrideProtectedEntries` — a distinção entre "efeito colateral" (protegido por padrão) e "alvo
  direto" (sempre removido) é a mesma já usada em `deleteDiaryEntry`: a entrada apontada por
  `diaryEntryId` também é sempre apagada incondicionalmente, só a cascata é que respeita a proteção.
  Idempotente: nenhuma entrada encontrada não é erro, só um no-op (`204` do mesmo jeito, sem `deleteAll`
  chamado). Reaproveita o mesmo bucket de rate limit de `POST /diary/bulk`
  (`diaryBulkActionKey`), não o de `DELETE /diary/{id}`.
- **Apagamento cascata respeita proteção de `autoGenerated` com flag de override opcional** (`DiaryEntryServiceImpl.deleteDiaryEntry`, `retractSeasonIfIncomplete`, `retractSeriesIfIncomplete`, `wipeSeriesHistory`, todos passando pelo helper único `deleteRespectingProtection`) — quando um usuário deleta um episódio/temporada/série, as entradas da temporada/série relacionadas que perderiam sustento são automaticamente retraídas; por padrão, só as entradas com `autoGenerated = true` são deletadas, preservando entradas criadas ou editadas manualmente (`autoGenerated = false`). O parâmetro `overrideProtectedEntries` (query parameter `?overrideProtectedEntries=true` em `DELETE /diary/{id}`, default `false`) permite, quando ativado, incluir também entradas com `autoGenerated = false` na retirada — essa é uma "confirmação explícita" de que o usuário quer mesmo deletar o histórico manual. Recomenda-se chamar `GET /diary/{id}/deletion-impact` primeiro para pré-visualizar quais entradas seriam impactadas.
- **Editar uma `DiaryEntry` (mesmo que nenhum campo mude de fato) desliga `autoGenerated`** (`DiaryEntryServiceImpl.updateDiaryEntry`) — uma vez que o usuário chama `PATCH` numa entrada, ela deixa de poder ser retraída automaticamente por `retractSeasonIfIncomplete`/`retractSeriesIfIncomplete`, mesmo que os episódios/temporadas que a sustentavam sejam apagados depois.
- **Bulk logging de temporada/série é restrito a 100 episódios no total** (`DiaryEntryServiceImpl.createDiaryEntriesInBulk`, constante `MAX_BULK_EPISODES`) — tentar logar uma temporada/série com mais de 100 episódios no total devolve `400`. Bulk logging só aceita tipo `SEASON` ou `SERIES` (`400` para outros tipos); para `SEASON` exige o número do último episódio (via `finaleEpisodeNumber` no DTO ou buscando um `EPISODE` existente com `isSeasonFinale = true`); para `SERIES` exige o número da última temporada (via `finaleSeasonNumber` no DTO ou buscando uma `SEASON` existente com `isSeriesFinale = true`) **e**, para cada temporada de `1` até essa última, o número do episódio final daquela temporada — resolvido, nessa ordem, por um `EPISODE` já existente com `isSeasonFinale = true` ou por uma entrada explícita em `seasonFinaleEpisodeNumbers` (`DiaryEntryBulkCreationDTO`, mapa `seasonNumber -> finaleEpisodeNumber`, `DiaryEntryServiceImpl.explicitFinaleEpisodeNumberFor`); sem nenhuma das duas fontes para alguma temporada intermediária, o bulk inteiro falha com `400` antes de criar qualquer entrada. Isso permite logar uma série multi-temporada inteira numa única chamada mesmo que nenhuma temporada tenha sido logada antes (bastando informar o mapa completo), sem depender de chamadas `SEASON` prévias para estabelecer os finais no banco. Todos os episódios logados em um único bulk call recebem a mesma `watchedDate` (o campo único de data fornecido na requisição se aplica a toda a passada). Cada episódio ganha uma passada "fresca" (`watchNumber = MAX + 1` para aquele `userId`+`contentId`), idêntico ao comportamento de um re-log individual. A resposta inclui, além das entradas de episódio, qualquer `DiaryEntry` de temporada/série auto-gerada que a cascata de completude criar durante o batch (`triggerCompletionCascade` retorna o `CompletionSignal` do que criou, e `bulkLogEpisode` adiciona `completedSeason`/`completedSeries` à lista retornada quando não-nulos); se uma única chamada de `bulkLogEpisode` disparar múltiplas passadas de rewatch completo (catch-up), só a última entrada auto-gerada de cada nível (temporada/série) é incluída na resposta — as passadas intermediárias são persistidas normalmente, só não aparecem nessa resposta específica.
- **Pré-visualização de impacto de exclusão (dry-run) executando o delete real numa transação que nunca é commitada** (`GET /diary/{diaryEntryId}/deletion-impact`, `DiaryEntryServiceImpl.computeDeletionImpact`) — retorna a lista de `DiaryEntry`s que seriam deletadas como efeito cascata se a entrada consultada fosse de fato apagada, sem persistir nada. Em vez de recalcular "o que aconteceria" com uma fórmula derivada à mão, o método chama literalmente o caminho de delete real (`deleteDiaryEntry`, na sobrecarga privada que recebe um acumulador `List<DiaryEntry>`) e depois chama `TransactionAspectSupport.currentTransactionStatus().setRollbackOnly()`, garantindo que a transação inteira seja revertida ao final da requisição. O acumulador é preenchido por `deleteRespectingProtection` — o único ponto onde a cascata de fato deleta — então a lista devolvida é exatamente o que foi apagado dentro da transação revertida, na ordem em que foi apagado; a entrada consultada em si sempre é apagada e não é repetida na lista. Isso torna estruturalmente impossível o preview divergir do delete (é um código só, não dois), corrigindo dois bugs anteriores: a primeira versão simulava a deleção calculando `minEpisodeWatchCount(...) - 1` na mão, e a segunda aplicava o delete do episódio mas **não** aplicava as retrações de temporada que ela própria previa antes de calcular as candidatas de série — então a `DiaryEntry` de série sumia do preview (`minSeasonWatchMax` computado alto demais) e o usuário confirmava um delete com `overrideProtectedEntries = true` sem nunca ver que uma review de série seria destruída junto.
- **O preview usa o mesmo `overrideProtectedEntries` do delete** (query parameter em `GET /diary/{diaryEntryId}/deletion-impact`, default `false`, igual ao `DELETE`) — como o preview roda o delete de verdade, o filtro de proteção por `autoGenerated` também vale ali: com `false` (default) a lista traz só o que o delete padrão apagaria; com `true` traz também as entradas manuais que seriam destruídas. Cada item carrega `id` (para o cliente mapear de volta para a entrada) e `autoGenerated` (a flag de proteção de fato). O campo `hasReview` é `true` quando a entrada tem `comment` ou `score` não-nulo — é uma dica de severidade para a UI (quanto conteúdo escrito pelo usuário está em jogo), **não** a flag de proteção: `updateDiaryEntry` zera `autoGenerated` em qualquer `PATCH`, então existe entrada protegida (`autoGenerated = false`) sem nenhum `comment` nem `score`.
- **`computeDeletionImpact` roda sempre numa transação física própria** (`@Transactional(propagation = Propagation.REQUIRES_NEW)`) — como o método marca a transação corrente como rollback-only por construção, rodar dentro da transação de um chamador envenenaria essa transação (`UnexpectedRollbackException` no commit, ou perda silenciosa das escritas do chamador). Com `REQUIRES_NEW` o dry-run sempre ganha e descarta a sua própria transação, independente de quem chamar. O `setRollbackOnly()` só é chamado quando `TransactionSynchronizationManager.isActualTransactionActive()` é verdadeiro, para permanecer chamável de testes unitários com Mockito puro (sem proxy/transação real do Spring).
- **Auto-completude nunca dispara quando o conjunto a completar é vazio** (`DiaryEntryServiceImpl.minEpisodeWatchCount`/`minSeasonWatchMax`) — os dois helpers devolvem `0` ("nada completo") quando o número do finale é menor que 1, em vez do sentinela `Integer.MAX_VALUE` com que o laço de mínimo começa. Sem essa guarda, um `Content` de episódio com `episodeNumber < 1` marcado como finale (ou uma `SEASON` 0 do TMDB marcada como finale da série) fazia o `while` de catch-up de `maybeCompleteSeason`/`maybeCompleteSeries` rodar ~2^31 vezes, inserindo uma `DiaryEntry` auto-gerada por iteração. Na entrada, `ContentRefCreationDTO` também rejeita `episodeNumber < 1` (`@Positive`) e `seasonNumber < 0` (`@PositiveOrZero`, já que a temporada 0 "Specials" é legítima), e o banco repete as duas regras em `ck_contents_episode_number`/`ck_contents_season_number` (`V13__add-content-episode-season-number-checks.sql`). Consequência aceita: como a temporada 0 é uma entrada válida (`@PositiveOrZero`), uma série cuja `SEASON` marcada `isSeriesFinale = true` seja a temporada 0 nunca auto-completa via `maybeCompleteSeries` (`minSeasonWatchMax` sempre devolve `0` para essa temporada) — recuperável apenas re-marcando uma temporada real como finale (`ContentServiceImpl.clearPreviousSeriesFinale` transfere a flag automaticamente). Os dois laços também saem antecipadamente (`break`) assim que o mínimo corrente chega a `0`, já que não há como um mínimo de contagens não-negativas cair depois disso — sem isso, um `episodeNumber`/`seasonNumber` de finale muito grande (mas válido, ex. 2 bilhões) ainda percorreria o intervalo inteiro à toa antes de retornar `0`.
- **Limitação conhecida: entrada auto-gerada commitada por `REQUIRES_NEW` sobrevive ao rollback do batch** (`DiaryEntryServiceImpl.persistAutoGeneratedEntry` chamado de `createDiaryEntriesInBulk`/`bulkLogEpisode`) — a entrada auto-gerada de temporada/série é persistida na sua própria transação física e commitada na hora; se a transação ambiente do bulk falhar depois disso (por exemplo o `ConflictException` de corrida em `bulkLogEpisode`), os episódios do batch somem no rollback mas a temporada/série já commitada permanece, órfã (sem os episódios que a sustentavam). Nada limpa isso automaticamente — a retração só roda em delete. É uma limitação aceita, não um caso impossível: o preço do padrão `REQUIRES_NEW` que protege a transação ambiente do erro de constraint. Uma compensação (`TransactionSynchronization.afterCompletion` com `STATUS_ROLLED_BACK`) fica para um trabalho futuro.
- **"Série em andamento" não tem entidade própria — é uma condição derivada de `DiaryEntry`+`Content` calculada a cada request** (`GET /users/{userId}/series-in-progress`, `DiaryEntryRepository.findSeriesInProgressByUserId`, query nativa) — uma série entra na lista quando o usuário tem pelo menos uma `DiaryEntry` de `EPISODE` daquele `seriesTmdbId` e **nenhuma** `DiaryEntry` de `SERIES` com `tmdbId` igual a esse `seriesTmdbId` (checagem via `NOT EXISTS`, mesma semântica de "completou" que `maybeCompleteSeries` teria fechado) **e nenhuma `DroppedEntry` do tipo `SERIES` para esse `tmdbId`** (segundo `NOT EXISTS`, join com `contents` pra resolver `tmdb_id` a partir de `dropped_entries.content_id`) — uma série abandonada some da lista mesmo com episódios assistidos antes do drop. `maxSeasonNumber` é o maior `season_number` entre os episódios assistidos daquela série; `maxEpisodeNumber` é o maior `episode_number` **dentro** desse `maxSeasonNumber` (não o maior episódio isolado — evita reportar um número de episódio de uma temporada anterior como se fosse o progresso mais avançado). `lastWatchedDate` usa `COALESCE(watched_date, created_at::date)`, mesmo fallback usado pra ordenação padrão do diário (`createdAt` quando `watchedDate` não foi informado), e é o único critério de ordenação que o backend consegue calcular — reusa a mesma checagem de visibilidade de perfil (`assertCanViewDiary`) de `getDiaryEntries`.
- **Reviews de um conteúdo (`GET /contents/{contentId}/reviews`) filtram visibilidade linha a linha, direto na query, não em memória** (`DiaryEntryRepository.findReviewsByContentId`) — diferente das estatísticas agregadas de `GET /contents/{contentId}/stats` (que excluem perfil privado inteiramente, ver regra em Content), aqui cada `DiaryEntry` com `comment` preenchido daquele `contentId` é avaliada individualmente contra o mesmo critério de `CommentServiceImpl.assertDiaryEntryIsVisibleTo`/`assertCanViewDiary`: autor da review, perfil público, ou perfil privado que o `viewerId` segue com status `ACCEPTED` (`EXISTS` contra `Follower` na própria query). O filtro precisa estar na query (não um `.filter()` depois de paginar) porque a página teria menos itens do que `size` pedido e a contagem de `PageMeta` mentiria — mesmo motivo já documentado pra `UserList.findByUserIdAndVisibilityIn`. `GET /contents/{contentId}/reviews` devolve `404` se o `contentId` não existir (`DiaryEntryServiceImpl.getReviewsForContent`, via `ContentRepository.existsById`), diferente de `GET /contents/{contentId}/stats`, que idempotentemente devolve zerado.

## UserList

- **Visibilidade em três estados por lista, combinada com uma barreira de perfil na listagem** —
  decisão tomada com o usuário em 2026-08-19, substituindo o antigo `isPublic` booleano: `visibility`
  agora é `PUBLIC`/`FOLLOWERS`/`PRIVATE` (`UserListVisibility`, migration `V21`). `GET /users/{userId}/lists`
  (`UserListServiceImpl.getUserLists`) passou a ter duas camadas: primeiro `assertCanViewLists` aplica
  a mesma regra padrão repetida em `Follower`/`FollowedPerson`/`Top5Entry`/`WatchlistEntry`/`DroppedEntry`/
  `DiaryEntry` — perfil do dono privado e viewer não é o dono nem o segue com status aceito → `403`
  pra requisição inteira, antes de olhar qualquer lista. Passada essa barreira, cada lista filtra pela
  própria `visibility`: dono vê tudo (`findByUserId`); qualquer outro viewer vê só `PUBLIC`, mais
  `FOLLOWERS` se de fato seguir o dono com status aceito (`findByUserIdAndVisibilityIn`), nunca
  `PRIVATE`. Isso é diferente do design anterior (só filtro por lista, nunca `403`) — ver `GET /lists/{listId}`
  abaixo pra a outra metade da decisão.
- **`GET /lists/{listId}` (implementado em 2026-08-19) checa a visibilidade só pela própria lista,
  nunca pelo perfil do dono** (`UserListServiceImpl.getUserListById`/`assertListIsVisibleTo`) —
  complementar à barreira de perfil em `GET /users/{userId}/lists`: uma lista `PUBLIC` é acessível
  diretamente por qualquer usuário autenticado mesmo que o perfil do dono seja privado e o viewer não
  o siga (decisão explícita do usuário: "se a lista for pública, ele consegue acessar ela
  diretamente"). `FOLLOWERS` exige seguir o dono com status aceito (ou ser o próprio dono); `PRIVATE`
  só o dono — em ambos os casos negados, `403` ("This list is private"), nunca `404` (a lista existe,
  só não está visível a esse viewer — mesmo espírito de `WatchlistEntryServiceImpl.assertCanViewWatchlist`,
  diferente do `404` usado por `findOwnedList` em `PATCH`/`DELETE`, que protege contra revelar posse).
- **`visibility` default `PUBLIC` quando omitido na criação** (`UserListServiceImpl.createUserList`/
  `createUserListWithItems`) — mesma constante usada nos dois métodos, não delegada a um
  `@AfterMapping` no mapper (diferente de `User.isProfilePublic`/`profilePicture` em `UserMapper`),
  porque a entidade tem uma relação `user` que o service já monta direto via builder — ver
  `CLAUDE.md` sobre esse padrão de criação para entidades com FK de dono/alvo.
- **`PATCH /lists/{listId}` é um patch parcial de verdade, `null = não mexe` nesse campo**
  (`UserListServiceImpl.updateUserList`/`applyPatch`, `UserListPatchDTO`) — igual ao padrão de
  `PatchUserDTO`/`UserServiceImpl.applyPatch`: `name`, `description` e `visibility` são todos
  opcionais, só o campo enviado como não-`null` é alterado. Trocado em 2026-08-19 do comportamento
  anterior (reescrita completa reaproveitando `UserListCreationDTO`, que exigia `name` sempre e não
  permitia atualizar só `description`). `name`, se enviado, é `trim()`ado e não pode resultar em
  string vazia (`BadRequestException`) — sem `@NotBlank` no DTO porque `null` (campo omitido) precisa
  passar; a validação de vazio é manual no service, mesmo padrão de `UserServiceImpl.
  validateUsernameLength`. Não existe forma de limpar `description` para `null` via este endpoint uma
  vez definida — mesma limitação de `PatchUserDTO`. `visibility` omitido mantém o valor atual, não
  volta para `PUBLIC` (isso só acontece na criação).
- **Dono do recurso em `PATCH`/`DELETE` resolvido por `userId` batendo, devolve `404` não `403`**
  (`UserListServiceImpl.findOwnedList`) — mesmo padrão de `DiaryEntryServiceImpl.findOwnedEntry`,
  pra não revelar que a lista existe e pertence a outra pessoa.
- **`watchedPercentage` é calculado de verdade, cruzando `UserListItem` com `DiaryEntry`** (bug
  corrigido em 2026-08-20 — antes era um placeholder fixo em `0.0`, a pedido do usuário, que reportou
  o campo não refletir um log recém-criado). `UserListItemServiceImpl.getWatchedPercentage(listId,
  viewerId)`: denominador é a contagem de itens de `content` da lista (ignora itens de lista
  aninhada); se zero, retorna `0.0` sem consultar `DiaryEntry`. Numerador é uma query
  `COUNT(DISTINCT uli.content.id)` com `EXISTS` contra `DiaryEntry` — `DISTINCT` pra um rewatch
  (várias `DiaryEntry` pro mesmo `content`) não inflar a contagem. **É a porcentagem do usuário
  autenticado que faz a requisição (o viewer), não do dono da lista** — decisão explícita do usuário:
  `GET /users/{userId}/lists` passa `viewerId` (não o `userId` do path) e `GET /lists/{listId}` passa
  o próprio `viewerId` do método; só em `POST /users/me/lists/bulk` dono e requisitante são a mesma
  pessoa por construção, então usa `userId` diretamente. Batch em `GET /users/{userId}/lists` (ver
  entrada `previewItems`/`nestedListsCount` abaixo) — `getWatchedPercentagesByListIds` reduz a duas
  queries fixas pra página inteira, em vez de duas por lista.
- **Sem constraint de unicidade em `name`** — um usuário pode ter duas listas com o mesmo nome; não
  documentado em `database-schema.html` nem sugerido pelo `openapi.yaml`, diferente de
  `Top5Entry`/`WatchlistEntry`/`DroppedEntry`, que têm `UNIQUE` explícito envolvendo conteúdo.
- **`previewItems`/`nestedListsCount` calculados por lista, não um placeholder** (`UserListServiceImpl.
  toResponseDto`, `UserListItemService.getPreviewItems`/`countNestedLists`, adicionados em 2026-08-19
  a pedido do usuário) — diferente de `watchedPercentage` acima, esses dois são computados de verdade
  a cada resposta. `previewItems` traz os até 5 primeiros itens de `Content` da lista, ordenados por
  `position`; `nestedListsCount` conta quantos itens são listas aninhadas. Como uma `UserList` trava
  como "de conteúdo" ou "de listas" a partir do primeiro item (nunca mistura), na prática só um dos
  dois campos é não-vazio por vez: uma lista-de-listas sempre reporta `previewItems: []` e
  `nestedListsCount` só mostra a quantidade, sem preview do conteúdo das listas aninhadas em si —
  decisão explícita do usuário, pra não precisar resolver visibilidade recursiva de cada lista
  aninhada só pra montar uma listagem. `POST /users/me/lists` (lista nova, sem itens) usa
  `List.of()`/`0` direto, sem consultar o repositório; `PATCH /lists/{listId}` sempre reconsulta os
  itens reais da lista (que pode já ter itens adicionados via `POST /lists/{listId}/items` antes do
  patch).
- **`GET /users/{userId}/lists` batching de `previewItems`/`nestedListsCount`/`watchedPercentage`
  entre as listas da mesma página** (otimização feita em 2026-08-20, a pedido do usuário, que apontou
  o N+1 introduzido em 2026-08-19/2026-08-20 — antes, cada lista da página disparava suas próprias
  queries de preview/contagem/percentual, então uma página de N listas custava até `1 + 4N` queries).
  `UserListServiceImpl.getUserLists` agora extrai os ids de todas as listas da página e chama três
  métodos em lote — `UserListItemService.getPreviewItemsByListIds`/`countNestedListsByListIds`/
  `getWatchedPercentagesByListIds` (`Collection<UUID> -> Map<UUID, ...>`) — reduzindo o custo pra 4
  queries fixas por página, independente de quantas listas ela contém. Os métodos por-lista
  (`getPreviewItems`/`countNestedLists`/`getWatchedPercentage`, usados por `getUserListById`,
  `updateUserList` e `createUserListWithItems`, que sempre operam sobre uma única lista) passaram a
  delegar pros métodos em lote com uma coleção de um elemento, sem duplicar a lógica de query.
  `UserListItemRepository.findContentItemsByUserListIdInOrderByPosition` também ganhou `JOIN FETCH
  uli.content`, eliminando um N+1 secundário que já existia dentro do próprio preview (cada item
  batendo o proxy lazy de `Content` on-demand pra montar o `ContentRefDTO`). As antigas queries
  por-lista (`findTop5ByUserListIdAndContentIdIsNotNullOrderByPositionAsc`,
  `countByUserListIdAndChildListIdIsNotNull`, `countByUserListIdAndContentIdIsNotNull`,
  `countWatchedContentItems`) foram removidas do repositório, substituídas pelas versões em lote
  (`countNestedListsByUserListIdIn`, `countContentItemsByUserListIdIn`,
  `countWatchedContentItemsByUserListIdIn`, todas devolvendo `List<UserListCount>`, uma projection
  `userListId`/`count` agrupada por `GROUP BY`).
- **`containsContent` só é resolvido quando `GET /users/{userId}/lists` recebe o parâmetro opcional
  `contentId`, `null` em qualquer outra resposta com `UserListResponseDTO`** (`UserListServiceImpl.
  getUserLists`/`mapToResponseDtoPage`, `UserListItemService.getListIdsContainingContent`,
  `UserListItemRepository.findUserListIdsContainingContent`) — pensado pra tela de "adicionar a uma
  lista" a partir da tela de um content, marcando as listas do usuário que já o contêm. Mesmo padrão
  de batching dos outros contadores acima: uma única query (`userListId IN :listIds AND content.id =
  :contentId`) resolve o conjunto de listas que contêm o content pra página inteira, e cada lista
  mapeia pra `true`/`false` conforme pertence a esse conjunto — nunca dispara quando `contentId` não é
  passado (`contentId != null` guarda a chamada em `mapToResponseDtoPage`). Um `contentId` que não
  existe simplesmente resolve `false` pra todas as listas, sem `404` — mesma filosofia de
  `GET /contents/{contentId}/stats`.
- **`UserListResponseDTO`/`UserListDetailedResponseDTO` não embutem o dono** — decisão tomada com o
  usuário em 2026-08-19: diferente de `UserListPreviewDTO` (usado só pra representar uma lista
  aninhada dentro de `UserListItem.childList`, que pode pertencer a qualquer usuário), toda rota que
  retorna `UserListResponseDTO`/`UserListDetailedResponseDTO` hoje já tem o dono implícito no
  contexto da chamada (`userId` no path de `GET /users/{userId}/lists`, ou o usuário autenticado em
  `POST /users/me/lists(/bulk)` e `PATCH /lists/{listId}`) — igual ao padrão já seguido por
  `Top5EntryResponseDTO`/`WatchlistEntryResponseDTO`/`DiaryEntryResponseDTO`/`DroppedEntryResponseDTO`,
  nenhum dos quais embute `user`. Quando a `Search` (Fase 8) precisar listar `UserList` de vários
  usuários diferentes, esse dado deve vir num DTO de resultado de busca próprio, não reaproveitando
  este.
- **`POST /users/me/lists/bulk` cria a lista e insere todos os itens na mesma transação: tudo ou
  nada** (`UserListServiceImpl.createUserListWithItems`) — delega a inserção inteira pra
  `UserListItemService.addItems` (ver regra abaixo), passando o array `items` de uma vez, na ordem em
  que vieram; se qualquer item falhar — por exemplo dois itens do próprio payload apontando pro mesmo
  `tmdbId`+`type`, o que dispara a mesma constraint única `uq_user_list_items_user_list_id_content_id`
  usada por um insert avulso — a exceção propaga e desfaz a criação da lista inteira junto com os
  itens já inseridos, não deixa uma lista parcialmente populada. Só aceita `content` por item
  (filme/série/temporada/episódio, sem restrição de `type` — mesma ausência de validação de tipo que
  `POST /lists/{listId}/items` já tinha); não é possível popular uma lista-de-listas por esse
  endpoint, só filmes/séries (e demais tipos de `Content`) diretamente — para itens de lista aninhada,
  usar `POST /lists/{listId}/items` depois de criar a lista.
- **`POST /lists/{listId}/items/bulk` insere vários itens numa lista já existente, tudo ou nada, num
  único round-trip de escrita pra toda a chamada** (`UserListItemServiceImpl.addItems`, reescrito em
  2026-08-24 — mesma implementação reaproveitada por `createUserListWithItems` acima) — `findOwnedList`
  e a checagem de travamento de tipo (`assertListIsNotLockedAsListOfLists`) rodam **uma vez** antes do
  loop, não por item; `countByUserListId` também roda uma única vez pra achar a posição inicial, e
  todas as entidades `UserListItem` são montadas em memória com posições incrementais pré-calculadas
  antes de qualquer escrita. A lista inteira é persistida com um único `saveAll(...)` + `flush()`, em
  vez de um `save`+`flush` por item — get-or-create de `Content` continua rodando por item (via
  `ContentServiceImpl.getOrCreateReference`), já que cada item pode referenciar um `tmdbId` distinto e
  isso é inevitável, mas deixou de disparar um `INSERT`+`flush` síncrono por item na tabela
  `user_list_items`. Qualquer falha (item de `content` inválido, duplicata dentro do próprio payload,
  item já presente na lista, ou a lista já travada como lista-de-listas) propaga e desfaz todos os
  itens da chamada, sem deixar a lista parcialmente populada — a mesma constraint única
  `uq_user_list_items_user_list_id_content_id` cobre tanto a duplicata intra-payload quanto a
  duplicata contra itens já existentes na lista, sem checagem dedicada; como não há mais um `flush`
  por item, uma duplicata intra-payload só se manifesta no `flush` final do lote inteiro, não no meio
  do loop — mesmo resultado observável (`409`, nada persistido), só muda quando o banco detecta o
  conflito. Como `addItems` não aceita `position` por item, todo item cai sempre na última posição
  livre a partir da posição inicial pré-calculada (mesmo comportamento de antes); para inserir em
  posição explícita, usar o endpoint avulso (`POST /lists/{listId}/items`). Só aceita `content` por
  item, igual a `POST /users/me/lists/bulk` — não é possível popular uma lista-de-listas por esse
  endpoint.
- **`itemsCount`/`commentsCount`/`totalRuntimeMinutes`** — três números novos em `UserList`/
  `UserListDetailed`. `itemsCount` (`UserListItemRepository.countAllItemsByUserListIdIn`, content +
  lista aninhada somados) e `totalRuntimeMinutes` (`sumRuntimeMinutesByUserListIdIn`, só itens de
  `content`, `Content` sem `runtimeMinutes` contribui 0) reaproveitam o mesmo padrão de batching em
  lote já usado por `previewItems`/`nestedListsCount`/`watchedPercentage` em
  `UserListServiceImpl.getUserLists`. `commentsCount` (`CommentRepository.countByListId`/
  `countByListIdIn`) é uma `COUNT` de verdade a cada request, não desnormalizado como `likesCount` —
  não compensa manter mais uma coluna sincronizada só pra isso. Em `GET /lists/{listId}` (detalhe),
  `itemsCount` não gera query própria — é só `items.size()`, já que a lista de itens inteira já foi
  carregada de qualquer forma.
- **`rank` (reordenação manual entre as listas do mesmo dono)** — coluna nullable
  (`V31__add-rank-to-user-lists.sql`), com `UNIQUE (user_id, rank)` (Postgres trata múltiplos `NULL`
  como distintos entre si, então listas ainda não rankeadas não colidem). Atribuída automaticamente
  como `N+1` (`N` = quantidade de listas já rankeadas do dono) em `createUserList`/
  `createUserListWithItems` — toda lista nova a partir de agora entra rankeada; listas anteriores à
  migration ficam com `rank = null` até serem movidas pela primeira vez. Reordenar é feito via
  `PATCH /lists/{listId}` com `rank` no corpo (`UserListServiceImpl.applyRankChange`), reaproveitando
  a mesma técnica de posição temporária + deslocamento em massa de `WatchlistEntry.moveEntry`
  (`parkRanksInRange`/`settleParkedRanks`), só que escopada por `userId` sozinho (rank é do dono, não
  por tipo). Uma lista com `rank = null` é **adotada** no fim da sequência (`N+1`) antes de aplicar o
  movimento pedido — só depois disso ela passa a competir por posição com as demais. `rank` maior que
  a quantidade de listas rankeadas é `400`; pedir o mesmo rank já atual é no-op; a escrita final usa
  `saveAndFlush` dentro de um `try/catch DataIntegrityViolationException` → `409`, mesmo padrão de
  corrida do `WatchlistEntry`.
- **Ordenação de `GET /users/{userId}/lists` por `sortBy`** — `rank`/`updatedAt`/`name`/`likesCount`
  são colunas reais de `UserList`, then reaproveitam o `Sort` genérico já suportado por
  `PageRequestFactory.build(pageNumber, pageSize, sortBy, sortDirection)` (nenhuma query nova).
  `itemsCount`/`commentsCount` **não** são colunas — pedir ordenação por eles cai em duas queries
  nativas próprias (`UserListRepository.findByUserIdOrderByItemsCount`/`...CommentsCount`, `LEFT JOIN`
  + `GROUP BY` + `COUNT`), já que o mecanismo de `Sort` do Spring Data não alcança uma coluna
  agregada. A direção (asc/desc) é resolvida via `CASE WHEN :sortDirection = 'ASC' THEN ... END ASC,
  CASE WHEN ... = 'DESC' THEN ... END DESC` na própria query nativa — necessário porque bind
  parameter não pode substituir a palavra-chave `ASC`/`DESC` diretamente em SQL. `sortBy` fora dessa
  lista de seis valores é `400` (`UserListServiceImpl.getUserLists`).
- **Filtro/ordenação de itens em `GET /lists/{listId}`** — aplicado inteiramente em memória sobre a
  lista de itens já carregada (`UserListServiceImpl.filterAndSortItems`), já que essa rota nunca foi
  paginada e a lista inteira é buscada de qualquer forma. `type` filtra por `content.type`; `genre`
  filtra por `content.genres().contains(...)`; `sortBy` aceita `position` (default)/`dateAdded`
  (`createdAt` do item)/`duration` (`content.runtimeMinutes`, `0` quando ausente) — valor fora dessa
  lista é `400`. `itemsCount`/`totalRuntimeMinutes` da resposta continuam refletindo a lista **inteira**,
  não o resultado filtrado — são estatísticas do recurso, não da view atual. Ordenar por "alfabética"
  ou "data de lançamento" não é suportado — `Content` nunca guarda título nem data de lançamento
  (dados só existem no TMDB), então isso é sempre trabalho do cliente.
- **`GET /users/me/liked-lists` é sempre auto-visão, sem checagem de visibilidade de terceiro**
  (`UserListServiceImpl.getLikedLists`) — diferente de `getUserLists` (que resolve perfil privado do
  dono + visibilidade por lista PUBLIC/FOLLOWERS/PRIVATE), aqui o viewer só pode estar pedindo as
  próprias curtidas (`userId` vem sempre do usuário autenticado, nunca de um path variable), então não
  há "outro usuário" cuja visibilidade precise ser resolvida. `LikeRepository.findLikedListsByUserId`
  devolve as `UserList` curtidas mais recentemente primeiro (`ORDER BY l.createdAt DESC` na própria
  `Like`, não na lista). Reaproveita o mesmo batching de preview/itemsCount/commentsCount/
  totalRuntimeMinutes/likedByMe de `getUserLists` via um método privado comum
  (`mapToResponseDtoPage`), extraído nesta mudança para não duplicar a lógica entre os dois métodos.

## UserListItem

- **Referência polimórfica entre `Content` e outra `UserList`, exatamente uma das duas** —
  `content_id`/`child_list_id` são ambos nullable, com `ck_user_list_items_target` garantindo que
  toda linha preenche exatamente um dos dois, nunca os dois nem nenhum. Decisão tomada com o usuário
  em 2026-08-18 (ver `development-stages.md` § Fase 4): uma `UserList` pode conter outra `UserList`
  como item ("lista de lista"), não só `Content` — mesmo espírito da referência polimórfica de
  `Comentario` (`Conteudo`/`Lista`/`Log`), só que com dois alvos aqui.
- **Sem auto-referência** (`ck_user_list_items_no_self_reference`) — `child_list_id` nunca pode ser
  igual ao `user_list_id` da própria linha, bloqueando o caso trivial de ciclo (uma lista se
  referenciando via um item seu).
- **Sem `score`/nota própria** — diferente de `DiaryEntry`, `UserListItem` não tem campo de nota; a
  avaliação de um conteúdo é sempre a de `DiaryEntry.score` (a nota "atual" de um usuário pra um
  conteúdo, não uma por lista). `description` (`VARCHAR(400)`) é a única anotação livre aceita num
  item, e vale tanto pra item de conteúdo quanto pra item de lista aninhada.
- **Profundidade máxima de um nível e trava de tipo, implementadas em `UserListItemServiceImpl.addItem`**
  (as duas regras de "lista de lista" que a migration não conseguia expressar num `CHECK` simples):
  `resolveChildList` rejeita (`400`) tanto (a) uma lista-pai que já está aninhada como item dentro de
  qualquer outra lista (`existsByChildListId(parentListId)`) quanto (b) um `childListId` cuja própria
  lista já contém outras listas como item (`existsByUserListIdAndChildListIdIsNotNull(childListId)`);
  `assertListIsNotLockedAsListOfLists`/`assertListIsNotLockedAsContentList` travam a lista-alvo como "de
  conteúdo" ou "de listas" a partir do seu primeiro item (`400` ao tentar inserir um item do tipo oposto
  ao já presente). Checagem (a) foi adicionada em 2026-08-24 depois de a checagem (b) sozinha se mostrar
  insuficiente contra profundidade ilimitada: encadear `X → A → B` (cada lista nova, sem filhos próprios
  ainda) passava por (b) em cada etapa, já que (b) só olha se o *filho* que está sendo aninhado já
  contém netos — nunca se o *pai* já é ele mesmo um filho em outra lista; sem (a), a checagem de "um
  nível" só bloqueava aninhar `B` de novo dentro de `B` (ou de algo que já contivesse `B`), não bloqueava
  aninhar um `B` novinho dentro de um `A` que já é filho de `X`. As duas checagens de profundidade,
  combinadas, também bloqueiam ciclos indiretos (`A` aninha `B`, depois `B` tenta aninhar `A`): pra `B`
  aninhar `A`, (b) rejeita porque `A` já é uma lista-de-listas (contém `B`), e agora (a) também rejeita
  porque `A` já está aninhado dentro de `X`/de qualquer lista-pai — duas barreiras independentes em vez
  de uma coincidência de uma única checagem.
- **Trava de grupo de tipo de conteúdo, no mesmo espírito da trava de conteúdo-vs-lista acima**
  (`UserListItemServiceImpl.assertContentTypeGroupMatches`/`resolveExistingContentScope`, adicionada em
  2026-08-31) — além de travar entre "conteúdo" e "lista aninhada", uma lista de conteúdo também trava
  entre três grupos fechados e sem sobreposição: `{MOVIE, SERIES}` juntos, `SEASON` sozinho, `EPISODE`
  sozinho (`UserListItemScope.forContentType`/`resolve`, única fonte de verdade reutilizada tanto por
  `UserListItemServiceImpl` quanto por `UserListServiceImpl`). O grupo é inferido do primeiro item de conteúdo, sem coluna nova —
  mesma técnica da trava de lista-de-listas. `addItems` (bulk) precisa rastrear esse grupo como um valor
  em memória, não só consultar o banco por item: como o método monta todos os `UserListItem` antes de um
  `saveAll` único, um conflito **entre dois itens do mesmo payload** nunca apareceria numa consulta ao
  banco (nenhum dos dois ainda está persistido). `getItemScope`/`getItemScopeByListIds`
  (`UserListItemService`) expõem o grupo atual como `itemScope` em `UserListResponseDTO`/
  `UserListDetailedResponseDTO`, pro front avisar antes de tentar inserir algo que violaria a trava.
  `UserListServiceImpl.getUserListById` resolve esse campo com uma consulta própria e não-filtrada
  (`getItemScope`), nunca a partir dos itens já retornados por `getItems` — esses passam por
  `toVisibilityScopedResponseDto`, que zera `childList` de itens aninhados que o viewer não pode ver;
  calcular o escopo a partir deles fazia uma lista-de-listas cujas listas filhas fossem todas privadas
  pro viewer reportar `itemScope: null` em vez de `LIST`, divergindo do valor correto que
  `GET /users/{userId}/lists` já retornava pra mesma lista (esse endpoint consulta a contagem de listas
  aninhadas direto no banco, sem esse filtro de visibilidade por item).
  Uma lista criada antes dessa regra que já misturava grupos (nunca existiu essa restrição antes) resolve
  para `MIXED` em vez de reportar um grupo qualquer arbitrário — e fica, na prática, travada contra
  qualquer novo item de conteúdo, já que nenhum tipo candidato jamais bate com um grupo já misto; não há
  migração retroativa para essas listas. `addItem`/`addItems` adquirem um lock pessimista na linha da
  `UserList` (`UserListRepository.findByIdForUpdate`, `PESSIMISTIC_WRITE`) antes de checar o grupo — sem
  isso, duas inserções concorrentes na mesma lista ainda vazia liam `resolveExistingContentScope` como
  `null` nas duas e ambas passavam pela checagem; a corrupção de dado nunca chegava a acontecer de fato,
  porque as duas sempre calculam a mesma próxima `position` e colidem em `uq_user_list_items_user_list_id_position`
  (uma das duas sempre falha com `409`), mas a que perdia recebia um `409` genérico de "posição já
  ocupada" em vez do `400` real de "grupo de tipo incompatível" — o lock corrige essa resposta enganosa
  e deixa de depender de um efeito colateral acidental de outra constraint pra manter o invariante.
  Verificado sob concorrência real com `CyclicBarrier`/`ExecutorService`
  (`UserListItemControllerIntegrationTest.shouldOnlyLetOneContentTypeGroupWinWhenTwoDifferentGroupsRaceOnTheSameEmptyList`).
- **`childListId` pode apontar para uma lista de outro usuário, desde que visível a quem está
  adicionando** (decisão tomada com o usuário em 2026-08-18, atualizada em 2026-08-19 para o modelo de
  três estados) — uso de curadoria: uma lista-de-listas pode agregar listas de terceiros ("minhas
  listas favoritas de outros perfis"), não só listas do próprio dono. `UserListItemServiceImpl.resolveChildList`
  chama o mesmo `assertListIsVisibleTo` usado por `UserListServiceImpl.getUserListById` (dono, ou
  `visibility = PUBLIC`, ou `visibility = FOLLOWERS` com o usuário seguindo o dono da lista referenciada
  com status aceito); caso contrário `403` ("This list is private"). Diferente do padrão `404` usado
  em `findOwnedList` (que existe pra não revelar que um recurso pertence a outra pessoa), aqui a lista
  referenciada não é o recurso sendo editado — ela só precisa ser *visível*, então `403` é a resposta
  correta (mesmo espírito de `WatchlistEntryServiceImpl.assertCanViewWatchlist`).
- **Apagar uma lista referenciada como item aninhado em outra lista fecha o buraco de posição na lista
  pai, em vez de depender do `ON DELETE CASCADE` silencioso do banco**
  (`UserListServiceImpl.deleteUserList` → `UserListItemServiceImpl.removeItemsReferencingChildList`,
  corrigido em 2026-08-24) — a FK `fk_user_list_items_child_list` (migration `V20`) é `ON DELETE
  CASCADE`: sem essa correção, apagar uma `UserList` que estava aninhada como `childList` em outra
  lista fazia o banco remover a linha correspondente na lista pai silenciosamente, sem passar pelo
  fluxo `parkPositionsInRange`/`settleParkedPositions`, deixando a lista pai com um buraco de posição
  (ex.: 1,2,4,5 em vez de 1,2,3,4) — o que depois podia gerar falsos "conflito de posição" em inserções
  futuras. Como `childListId` pode apontar pra lista de outro usuário (regra acima), a mesma lista pode
  em teoria estar referenciada em várias listas-pai de donos diferentes ao mesmo tempo; `deleteUserList`
  agora busca **todos** os `UserListItem` que referenciam a lista sendo apagada
  (`UserListItemRepository.findByChildListId`) e remove cada um pelo mesmo caminho de
  `removeItem`/`deleteAndCloseGap` (fechando o buraco na lista pai correspondente a cada um) antes de
  apagar a lista em si — sem checagem de dono nesse passo, já que é um efeito colateral interno de
  limpeza, não uma ação solicitada pelo dono da lista pai. **Nota:** o mesmo padrão de cascade silencioso
  existe em teoria em `watchlist_entries` via `fk_watchlist_entries_content ON DELETE CASCADE`
  (migration `V17`), mas nenhum caminho de código apaga um `Content` hoje — `Content` é imutável por
  design (ver seção Content) — então esse lado do problema é inatingível na prática, não corrigido aqui
  por não haver nada pra corrigir.
- **`position` continua opcional no `POST`, com shift dos itens seguintes** (`UserListItemServiceImpl.
  insertAtPosition`) — decisão tomada com o usuário: diferente de `WatchlistEntry`/`Top5Entry` (que
  sempre inserem no fim e reordenam só via endpoint separado), `UserListItem` aceita `position` já na
  criação; sem ele, insere na última posição (`currentCount + 1`, via `countByUserListId`, não mais
  carregando a lista inteira só pra saber o tamanho). Com `position` explícito maior que a próxima
  posição livre, `400`.
- **`PATCH /lists/{listId}/items/{itemId}` edita `position` e `description` de forma independente,
  ambos opcionais** (`UserListItemServiceImpl.updateItem`, adicionado em 2026-08-21) — enviar só
  `description` reescreve a anotação sem tocar na posição; enviar só `position` reordena sem tocar na
  descrição; os dois juntos aplicam ambos na mesma chamada. Se `position` for igual à posição atual,
  não desloca nada (mesmo comportamento de no-op de `WatchlistEntryServiceImpl.moveEntry`); se maior
  que o total de itens atuais da lista, `400` (não é possível "mover para o fim" além do que já existe
  — pra isso, remova e insira de novo). Preserva `id`/`createdAt` do item, ao contrário de remover e
  recriar. Reaproveita o mesmo algoritmo de deslocamento em duas queries em massa
  (`parkPositionsInRange`/`settleParkedPositions`) e a mesma técnica de posição temporária
  (`performMove`, espelhando `WatchlistEntryServiceImpl.performMove` — ver regra em WatchlistEntry
  para o porquê da "estacionada" em offset gigante) usados por `insertAtPosition`/`deleteAndCloseGap`;
  uma falha de concorrência no `save` durante o deslocamento vira `409` (`ConflictException`), não
  propaga a exceção do Postgres.
- **`insertAtPosition`/`deleteAndCloseGap` deslocam posição via duas queries `UPDATE` em massa
  (`UserListItemRepository.parkPositionsInRange`/`settleParkedPositions`, escopadas por `userListId`),
  não num loop item a item** — mesma técnica de `WatchlistEntry` (ver nota lá para o porquê da
  "estacionada" em offset gigante antes do valor final), adotada aqui porque uma lista de usuário não
  tem teto de itens (diferente do Top5, capado em 5, onde a mesma técnica quebra contra um `CHECK` de
  teto — ver nota em Top5Entry): antes dessa mudança, remover ou inserir no meio de uma lista com N itens
  custava `O(N)` round-trips só pra fechar o buraco/abrir espaço; `addItems` (inserção em lote) chamava
  `addItem` uma vez por item e cada chamada recarregava a lista inteira, tornando um lote de N itens
  `O(N²)`. Com `countByUserListId` substituindo o carregamento completo e o deslocamento indo pra duas
  queries em massa, inserir/remover passou a ser `O(1)` round-trips (fora o insert/delete da própria
  linha), e `addItems` voltou a ser `O(N)` — mas ainda `O(N)` round-trips de escrita de verdade (um
  `save`+`flush` por item), não só `O(N)` chamadas de método. Batching de escrita de fato (`saveAll` +
  um único `flush`, ver regra acima) só veio em 2026-08-24; até lá, um lote de 20 itens ainda gerava
  ~80-100 round-trips bloqueantes numa única requisição HTTP (ownership redundante, get-or-create de
  content, `countByUserListId`, insert e flush, por item).
- **Sem posição duplicada nem item duplicado na mesma lista** — `uq_user_list_items_user_list_id_position`
  bloqueia dois itens com a mesma `position` na mesma lista; `uq_user_list_items_user_list_id_content_id`/
  `uq_user_list_items_user_list_id_child_list_id` bloqueiam o mesmo `Content`/`UserList` aparecendo
  duas vezes na mesma lista (a coluna oposta, sempre `NULL` na linha, não colide em `UNIQUE` no
  Postgres — mesma técnica já usada em `Top5Entry`/`WatchlistEntry` pra combinar unicidade com campo
  opcional).
- **`customPosterUrl` só é permitido em item de `content`, nunca em item de `childList`** —
  `validateExactlyOneTarget` rejeita (`400`) `customPosterUrl` informado junto com `childListId` na
  criação; `updateItem` rejeita (`400`) `customPosterUrl` informado contra um item cujo
  `getChildList() != null` no patch. Uma lista aninhada não tem poster próprio (adicionado em
  2026-08-29, mesma validação `@Size(max = 2048) @URL` de `DiaryEntry.customPosterUrl`).
- **`sortBy=episodeAvgRating` em `GET /lists/{listId}` ordena pela nota média de episódios só do
  dono da lista, nunca do viewer** (`UserListServiceImpl.computeEpisodeAverageRatings`, adicionado
  em 2026-08-29) — `UserListItem` não tem nota própria (ver regra acima), então esse valor não é uma
  coluna, é calculado em memória a partir de `DiaryEntry.score` do dono: pra item `SERIES`, média de
  todo `DiaryEntry` tipo `EPISODE` do dono com `seriesTmdbId` igual ao `tmdbId` da série; pra item
  `SEASON`, mesma coisa restrita ao `seasonNumber`; pra item `EPISODE`, média das notas do dono pra
  aquele episódio exato (cobre rewatch com notas diferentes). Item `MOVIE` e item de lista aninhada
  nunca têm esse valor (não fazem sentido pro conceito de "episódio"). Uma única query
  (`DiaryEntryRepository.findScoredEpisodeEntriesByUserIdAndSeriesTmdbIdIn`, filtrando `score IS NOT
  NULL`) busca todos os `DiaryEntry` de episódio relevantes de uma vez, agrupados em Java por série/
  temporada/episódio, em vez de uma query por item. Item sem nenhuma nota do dono sempre ordena por
  último, em `asc` e em `desc` — não é um "nulls first" que a mera inversão do comparator geraria;
  a direção (`asc`/`desc`) só se aplica entre os itens que têm nota.

## Comment

- **Referência polimórfica entre `Content`, `UserList` e `DiaryEntry`, exatamente um dos três**
  (`ck_comments_target`, migration `V23`) — mesmo espírito da referência polimórfica de
  `UserListItem` (`Content`/`UserList` aninhada), só que com três alvos aqui em vez de dois. Cada um
  dos três métodos de criação (`createCommentOnContent`/`createCommentOnList`/
  `createCommentOnDiaryEntry`, `CommentServiceImpl`) resolve o alvo pelo path do endpoint chamado, não
  por um campo no corpo — o cliente nunca escolhe o alvo via payload.
- **`parentCommentId` é independente do alvo, mas precisa apontar pra um comentário do mesmo alvo**
  (`resolveParentCommentOnContent`/`resolveParentCommentOnList`/`resolveParentCommentOnDiaryEntry`) —
  validado em nível de aplicação (não expressável como `CHECK` simples): `404` ("Parent comment not
  found") se o `parentCommentId` não existe; `400` ("Parent comment must target the same
  content/list/diary entry") se existe mas aponta pra um alvo diferente do informado na própria
  chamada. Sem limite de profundidade de resposta (diferente de `UserListItem`, que trava lista-de-
  listas em um nível) — uma resposta pode responder a outra resposta indefinidamente.
- **Uma lista travada como "de listas" nunca recebe comentário** (`assertListAcceptsComments`, só em
  `createCommentOnList`) — reaproveita `UserListItemRepository.existsByUserListIdAndChildListIdIsNotNull`,
  a mesma query que `UserListItemServiceImpl.assertListIsNotLockedAsListOfLists` usa pra travar a
  própria lista contra itens de conteúdo. Uma lista comum (incluindo uma lista filha aninhada dentro de
  uma lista-de-listas) aceita comentário normalmente; só a lista-de-listas em si, nunca. `400` se
  violado. A checagem só roda no `POST` — `GET /lists/{listId}/comments` numa lista-de-listas
  simplesmente devolve uma página vazia (estruturalmente nunca pode ter comentários), sem custo extra
  de validação nem erro.
- **Visibilidade de `UserList` reaproveitada de `UserListServiceImpl`/`UserListItemServiceImpl`, mas
  duplicada em vez de compartilhada** (`CommentServiceImpl.assertListIsVisibleTo`) — mesma decisão de
  design já tomada nesses dois services (o método não é exposto pela interface `UserListService`), então
  `Comment` implementa sua própria cópia em vez de introduzir uma dependência nova entre packages.
  Aplicada tanto no `GET` quanto no `POST` de comentários de lista: dono sempre vê/comenta; `PUBLIC`
  libera qualquer autenticado; `FOLLOWERS` exige seguir o dono com status aceito; `PRIVATE` só o dono.
  `403` ("This list is private") caso contrário — igual ao padrão usado por `GET /lists/{listId}`.
- **Visibilidade de `DiaryEntry` decidida na implementação de `Comment` — regra de visibilidade padrão
  aplicada por linha, não só por listagem** (`CommentServiceImpl.assertDiaryEntryIsVisibleTo`) —
  decisão nova: até `Comment`, a única checagem de visibilidade de `DiaryEntry` existente era
  `DiaryEntryServiceImpl.assertCanViewDiary`, usada exclusivamente pela listagem `GET /users/{id}/diary`
  (perfil do dono público, ou viewer é o dono, ou viewer segue o dono com status aceito). Não havia
  nenhum endpoint que buscasse um `DiaryEntry` individual por id fora do próprio dono (`PATCH`/`DELETE`
  já são só-dono via `findOwnedEntry`). Como `GET/POST /diary/{diaryEntryId}/comments` agora permite
  que qualquer usuário autenticado referencie o registro de outra pessoa diretamente pelo id, a mesma
  regra de visibilidade padrão (repetida em `Follower`/`FollowedPerson`/`Top5Entry`/`WatchlistEntry`/
  `DroppedEntry`/`DiaryEntry`/`UserList`) foi replicada aqui pra não abrir uma forma nova de acessar/
  comentar o diário de um perfil privado sem seguir o dono. `403` ("This diary entry is private") caso
  contrário.
- **`DiaryEntryRepository.findByIdWithUser`, novo `@Query` com `JOIN FETCH d.user`** — usado só por
  `CommentServiceImpl` (listagem e criação de comentário em `DiaryEntry`), evita que
  `assertDiaryEntryIsVisibleTo` dispare uma query lazy adicional só pra ler `isProfilePublic` do dono;
  diferente da checagem de visibilidade de `UserList`, que nunca precisa carregar o `User` completo
  (a visibilidade é uma propriedade da própria lista, não do perfil do dono).
- **`containsSpoiler` default `false` quando omitido, resolvido no service via builder** — mesmo padrão
  documentado em `UserList.visibility`/`CLAUDE.md` (entidade com FK de alvo/dono, então a construção é
  toda no service, sem `@AfterMapping` no `CommentMapper`).
- **`DELETE /comments/{commentId}` só remove comentário próprio, dono resolvido por `userId` batendo —
  404, não 403** (`CommentServiceImpl.findOwnedComment`) — mesmo padrão de `findOwnedEntry`/
  `findOwnedList`, pra não revelar que o comentário existe e pertence a outra pessoa. A subárvore de
  respostas é apagada em cascata pelo banco (`fk_comments_parent_comment ... ON DELETE CASCADE`), sem
  lógica adicional no service.

## Like

- **Curtir é idempotente, não um `ConflictException`** (`LikeServiceImpl.likeComment`/`likeDiaryEntry`) —
  mesma decisão já usada em `FollowedPersonServiceImpl.followPerson`: curtir algo já curtido pelo mesmo
  usuário simplesmente não faz nada (checagem `existsByUserIdAndCommentId`/`existsByUserIdAndDiaryEntryId`
  antes de tentar salvar), em vez de devolver erro. Segue o mesmo padrão de get-or-create idempotente
  contra corrida documentado em `CLAUDE.md`: a tentativa de `saveAndFlush` roda dentro de
  `NewTransactionExecutor.runInNewTransaction` (transação própria via `REQUIRES_NEW`), e a entidade
  `Like` é construída inteira dentro do mesmo lambda (proxies de `User`/`Comment`/`DiaryEntry` via
  `getReferenceById` nunca atravessam duas transações); se o `saveAndFlush` falhar com
  `DataIntegrityViolationException`, o método reconsulta a existência da linha — some se outra chamada
  concorrente venceu a corrida, propaga a exceção original só se a linha continuar realmente ausente.
- **Descurtir (`unlikeComment`) é idempotente, sem checar se o comentário alvo existe** — mesmo espírito
  de `FollowedPersonServiceImpl.unfollowPerson`: nenhuma `NotFoundException` se não havia curtida pra
  remover. Implementado como um `DELETE` em massa (`LikeRepository.deleteByUserIdAndCommentId`, ver
  regra de corrida abaixo), não mais um `find` seguido de `delete` de entidade.
- **Sem `LikeResponseDTO`/`LikeMapper`** — decisão consultada com o usuário: todos os endpoints de
  `Likes` no `openapi.yaml` (`POST`/`DELETE` em `/comments/{commentId}/like`, `/diary/{diaryEntryId}/like`
  e `/lists/{listId}/like`) respondem `204` sem corpo, então não existe nenhuma superfície que
  precisaria de um DTO de resposta — mesma situação (e mesma escolha) já aplicada a `Follower`/
  `FollowedPerson`, que também não têm mapper/DTOs.
- **Visibilidade de alvo reaproveitada de `CommentServiceImpl`, duplicada em vez de compartilhada**
  (`LikeServiceImpl.assertCommentIsVisibleTo`/`assertListIsVisibleTo`/`assertDiaryEntryIsVisibleTo`) —
  mesma decisão de design já tomada em `Comment`/`UserList`/`UserListItem` (métodos não expostos pelas
  interfaces de service, cada consumidor tem sua própria cópia): curtir um comentário cujo alvo é
  `Content` é sempre permitido (conteúdo não tem dono); se o alvo é `UserList`, aplica a mesma regra de
  `PUBLIC`/`FOLLOWERS`/`PRIVATE` já usada em `Comment`; se o alvo é `DiaryEntry` (direto, via
  `likeDiaryEntry`, ou indireto, via um comentário que responde a um registro de diário), aplica a mesma
  regra padrão de perfil público/dono/segue-aceito. `403` nos dois casos ("This list is private"/"This
  diary entry is private") — a checagem só roda enquanto a curtida ainda não existe; uma curtida já
  registrada nunca é revogada por uma mudança de visibilidade posterior.
- **Curtida direta em `UserList` (`likeList`/`unlikeList`), acrescentada a pedido do usuário** — inclui
  `list_id` em `likes` (migration `V25`, terceiro alvo do `ck_likes_target`, mesmo espírito do
  polimorfismo de três alvos de `Comment`, mais `uq_likes_user_id_list_id`). Reaproveita
  `LikeServiceImpl.assertListIsVisibleTo` (a mesma regra `PUBLIC`/`FOLLOWERS`/`PRIVATE` já usada por
  `likeComment` quando o alvo do comentário é uma lista) e o mesmo padrão idempotente de
  `likeComment`/`likeDiaryEntry` (`NewTransactionExecutor` + `REQUIRES_NEW`). Decisão consultada com o
  usuário: uma lista travada como "de listas" não pode ser curtida diretamente — mesma trava de
  `assertListAcceptsComments` (`LikeServiceImpl.assertListAcceptsLikes`, reaproveitando
  `UserListItemRepository.existsByUserListIdAndChildListIdIsNotNull`), `400` ("This list is a list of
  lists and cannot receive likes") caso violado. `unlikeList` é idempotente, mesmo padrão de
  `unlikeComment`/`unlikeDiaryEntry` (`DELETE` em massa, não `find`+`delete` de entidade).
- **`CommentRepository.findByIdWithTargets`, novo `@Query` com `LEFT JOIN FETCH` em `list`/`list.user`/
  `diaryEntry`/`diaryEntry.user`** — usado só por `LikeServiceImpl.likeComment`, evita
  `LazyInitializationException` ao ler o dono do alvo fora de uma transação aberta explicitamente
  (diferente de `CommentServiceImpl`, que roda a própria checagem dentro de um método `@Transactional`).
- **Gap do `openapi.yaml` resolvido na implementação do `Controller`**: `POST /diary/{diaryEntryId}/like`
  não tinha `DELETE` correspondente documentado (assimetria com o par `POST`/`DELETE` de
  `/comments/{commentId}/like`, sinalizada quando o `Service` foi implementado). Consultado com o
  usuário ao implementar `LikeController`: adicionado `DELETE /diary/{diaryEntryId}/like` ao
  `openapi.yaml`, e `LikeService.unlikeDiaryEntry` (mesmo padrão idempotente de `unlikeComment`/
  `unfollowPerson`) — os quatro endpoints de `Likes` agora são todos simétricos (curtir/descurtir nos
  dois alvos).
- **`LikeController` sem `RequestThrottler`** — mesma escolha já feita para `Comment`/`UserList`/
  `UserListItem` (domínios mais recentes sem rate limit dedicado), diferente de `Diary`/`Dropped`/
  `Follow*`/`Content`.
- **`likesCount` desnormalizado em `Comment`/`DiaryEntry`/`UserList` (Nível 1), mantido via `UPDATE` em
  massa, não uma `COUNT(*)` por requisição** — coluna `likes_count` (migration
  `V26__add-likes-count-to-likeable-tables.sql`, `NOT NULL DEFAULT 0`, backfill via subquery contra
  `likes` na própria migration) espelhada como `Integer likesCount` (`@Builder.Default = 0`) nas três
  entidades. `LikeServiceImpl.likeComment`/`likeDiaryEntry`/`likeList` incrementam via
  `CommentRepository`/`DiaryEntryRepository`/`UserListRepository.incrementLikesCount` (`@Modifying
  @Query("UPDATE ... SET likesCount = likesCount + 1 WHERE id = :id")`) dentro do mesmo
  `NewTransactionExecutor.runInNewTransaction` que já envolve o `saveAndFlush` do `Like` — mesma
  transação física, então uma reversão da curtida (corrida detectada via
  `DataIntegrityViolationException`) nunca incrementa sem o `Like` correspondente existir. `unlikeComment`/
  `unlikeDiaryEntry`/`unlikeList` ganharam `@Transactional` (antes rodavam sem transação própria) e chamam
  o `decrementLikesCount` correspondente (`WHERE ... AND likesCount > 0`, piso defensivo contra ficar
  negativo) só quando o `DELETE` em massa correspondente afetou pelo menos uma linha (ver regra de corrida
  logo abaixo) — descurtir um alvo sem curtida prévia não decrementa nada, mesmo padrão de idempotência de
  `unlikeComment` já documentado acima. Mesma técnica de `UPDATE` em massa já usada por
  `UserListItemRepository.parkPositionsInRange`/`settleParkedPositions`, só que aqui como
  incremento/decremento pontual por id, não reposicionamento em faixa.
- **Armadilha real encontrada e corrigida (já superada pela correção de corrida abaixo): `@Modifying(clearAutomatically = true)`
  nas seis queries de incremento/decremento apagava silenciosamente a curtida removida em
  `unlikeComment`/`unlikeDiaryEntry`/`unlikeList`** — na implementação original (find-then-delete: buscar
  o `Like`, chamar `likeRepository.delete(like)` na entidade, depois rodar o `UPDATE` em massa de
  decremento), `entityManager.clear()` (disparado pelo `clearAutomatically`, depois do `executeUpdate` do
  decremento) descartava qualquer alteração ainda não sincronizada com o banco na sessão corrente; como o
  `delete(like)` não tinha nenhum `flush()` explícito antes do `decrementLikesCount`, e o `UPDATE` em massa
  não pertence à mesma tabela de `likes` (Hibernate só auto-sincroniza entidades cuja tabela se sobrepõe à
  da query, então não sincroniza a remoção pendente por conta própria), a remoção do `Like` era descartada
  da sessão antes de chegar ao banco — o `DELETE` nunca era executado, mesmo a requisição HTTP devolvendo
  `204`. Pego pelo `LikeControllerIntegrationTest` existente (não alterado naquela sessão), que falhou nas
  três variantes de `unlike*` mesmo sem nenhuma mudança nele. Corrigido removendo `clearAutomatically = true`
  das seis queries (`@Modifying` puro). **Nota histórica:** essa armadilha inteira deixou de se aplicar a
  `unlikeComment`/`unlikeDiaryEntry`/`unlikeList` em 2026-08-24, quando o `find`+`delete` de entidade foi
  substituído por um `DELETE` em massa (ver regra de corrida abaixo) — sem entidade `Like` carregada na
  sessão, não há nada pro `clear()` descartar nesse caminho. A remoção do `clearAutomatically` nas queries
  de incremento/decremento continua valendo por consistência (nenhum consumidor futuro deveria depender de
  um `clear()` automático aqui), mas o cenário original do bug (delete de entidade seguido de `UPDATE` em
  massa sem flush) não existe mais nesses três métodos especificamente.
- **Corrida corrigida: `unlike*` concorrente podia decrementar `likesCount` duas vezes** (corrigido em
  2026-08-24) — a implementação original fazia `findByUserIdAndCommentId(...).ifPresent(like -> { delete;
  decrementLikesCount; })`: duas chamadas concorrentes de unlike pro mesmo par usuário+alvo podiam passar
  pelo `SELECT` antes de qualquer uma comitar o `DELETE`; quando a primeira comitava, o `DELETE` da segunda
  afetava `0` linhas, mas o `decrementLikesCount` rodava incondicionalmente mesmo assim, já que nada
  checava quantas linhas foram de fato apagadas — `Like` não tem `@Version`, então não havia exceção de
  lock otimista pra pegar essa corrida também. Efeito líquido: o contador ia silenciosamente ficando abaixo
  da contagem real com o tempo. Corrigido trocando o `find`+`delete` de entidade por um `DELETE` em massa
  que devolve quantas linhas afetou (`LikeRepository.deleteByUserIdAndCommentId`/
  `deleteByUserIdAndDiaryEntryId`/`deleteByUserIdAndListId`, `@Modifying @Query("DELETE FROM Like l
  WHERE ...")`, retorno `int`), e só chamando `decrementLikesCount` quando esse retorno é maior que zero.
  Isso é seguro contra a corrida porque o próprio `DELETE` já serializa o acesso à linha no Postgres: a
  segunda chamada concorrente bloqueia no lock de linha do `DELETE` até a primeira comitar, e ao ser
  liberada reavalia o `WHERE` e não encontra mais nada pra apagar (retorna `0`) — só uma das duas chamadas
  concorrentes de fato decrementa, não importa a ordem de chegada. As antigas `findByUserIdAndCommentId`/
  `findByUserIdAndDiaryEntryId`/`findByUserIdAndListId` (`Optional<Like>`) foram removidas do
  `LikeRepository` por ficarem sem uso — os testes que dependiam delas passaram a usar
  `existsByUserIdAndCommentId`/etc. (já existentes) pra verificar estado no banco.
- **Estado "curtido por mim" (`likedByMe`) resolvido em lote, não denormalizado nem checado por item** —
  três novas queries indexadas em `LikeRepository` (`findLikedCommentIds`/`findLikedDiaryEntryIds`/
  `findLikedListIds`, `SELECT alvo.id FROM Like WHERE user.id = :userId AND alvo.id IN :ids`), cobertas
  pelos mesmos índices únicos `uq_likes_user_id_comment_id`/`uq_likes_user_id_diary_entry_id`/
  `uq_likes_user_id_list_id` já existentes (o Postgres cria automaticamente o índice de suporte da
  `UNIQUE` composta, com `user_id` como coluna líder — casa exatamente com o predicado `user_id = ? AND
  alvo_id IN (...)`), sem precisar de índice novo. `LikeService.getLikedCommentIds`/
  `getLikedDiaryEntryIds`/`getLikedListIds` retornam `Set<UUID>` (vazio sem tocar o banco quando a
  coleção de ids de entrada já está vazia, mesmo guard-clause de
  `UserListItemServiceImpl.getPreviewItemsByListIds`/`countNestedListsByListIds`). `CommentServiceImpl`
  (as três variantes de `getCommentsFor*`, incluindo `getCommentsForContent`, que ganhou um parâmetro
  `viewerId` que não existia — `Content` não tem dono, mas curtir ainda é por usuário autenticado),
  `DiaryEntryServiceImpl.getDiaryEntries` e `UserListServiceImpl.getUserLists` buscam a página normalmente
  e então resolvem os ids curtidos da página inteira numa única query batched, evitando o N+1 de uma
  checagem de existência por item. Endpoints de criação (`createCommentOn*`, `createDiaryEntry`,
  `createDiaryEntriesInBulk`, `createUserList`, `createUserListWithItems`) passam `likedByMe = false`
  como literal, sem consultar `LikeRepository` — impossível ter curtido algo que acabou de ser criado na
  mesma chamada. Endpoints que retornam um recurso já existente e não paginado
  (`UserListServiceImpl.getUserListById`/`updateUserList`, `DiaryEntryServiceImpl.updateDiaryEntry`)
  reaproveitam o mesmo método batched do `LikeService` com uma coleção de um único id, em vez de um método
  dedicado a "checar um só" — evita duplicar a leitura em `LikeRepository`.

## Summary

- **`type` é obrigatório e escopa toda a resposta, sem variante agregada** (`GET
  /users/{userId}/summary?type=MOVIE|SERIES`, `SummaryServiceImpl.getSummary`) — mesmo padrão de
  `Top5Entry`/`WatchlistEntry`/`DroppedEntry`: o cliente troca de aba (Filme/Série) e chama de novo, em
  vez do backend devolver os dois tipos numa única resposta maior. `type` fora de `{MOVIE, SERIES}`
  (incluindo `null`, ausente) é `400` — validado no service, não via `@RequestParam(required = true)`,
  pra devolver o `ApiError` padrão do projeto em vez do erro de parâmetro ausente default do Spring (ver
  `CLAUDE.md` § "Don't let Spring's default error bodies leak through").
- **Sem `SummaryService` de domínio próprio — é só orquestração sobre consultas que já existem** —
  `SummaryServiceImpl` não introduz regra de negócio nova; cada campo da resposta reaproveita uma query
  ou serviço de `Diary`/`Dropped` já existente, só adicionando o filtro por `type`.
- **`totalTheaterVisits` em `AllTimeStatsResponseDTO` reaproveita a mesma contagem do perfil, sem
  filtro extra** (`SummaryServiceImpl.getAllTimeStats`, `DiaryEntryRepository.countByUserIdAndWatchedInTheaterTrue`
  — ver § User / Auth) — `GET /users/{userId}/summary/all-time` não escopa por `type`, então essa
  contagem é a mesma all-time de `UserResponseDTO`/`PublicUserProfileDTO`, só duplicada aqui porque a
  tela de All Time Stats não faz uma chamada extra a `GET /users/{userId}` só por esse campo.
- **`watchTime`/`ratingsDistribution` usam `EPISODE` como o "tipo interno" de SERIES, não `SERIES`**
  (`DiaryEntryRepository.sumRuntimeMinutesByUserIdAndContentType`/`countByUserIdAndContentTypeGroupByScore`,
  chamados via `SummaryServiceImpl.watchedContentTypeFor(SERIES) = EPISODE`) — mesma convenção já usada
  pelo stats de perfil (`UserServiceImpl.computeWatchStats`, ver `CLAUDE.md` § Avoid): o tempo assistido
  de uma série vem da soma dos episódios (`Content.runtimeMinutes` só existe em `MOVIE`/`EPISODE`), e a
  `DiaryEntry` de nível `SERIES` é só um marcador sintético de conclusão (`maybeCompleteSeries`) que
  contaria o mesmo tempo em dobro se fosse somado também. Pra `watchTime` isso não tem lacuna (`SERIES`
  nunca carrega `runtimeMinutes` próprio, então incluí-la não somaria nada de qualquer forma). **Pra
  `ratingsDistribution` isso é uma limitação conhecida e deliberada, não corrigida**: se o usuário dá
  nota (`score`) direto numa `DiaryEntry` de nível `SERIES` — seja logando a série inteira de uma vez
  (`POST /diary` com `content.type=SERIES`), seja editando depois a entrada de conclusão automática que
  `maybeCompleteSeries` criou (`PATCH /diary/{id}`) — essa nota **não entra** na distribuição de
  `GET /users/{userId}/summary?type=SERIES`, que só soma `score` de `DiaryEntry` `EPISODE`; só nota dada
  episódio a episódio aparece ali (decisão confirmada com o usuário em 2026-09-02, ao corrigir o bug
  correspondente em `genreCounts` abaixo — ficou fora de escopo por enquanto).
- **`genreCounts` (Summary, MonthInReview, YearInReview) e `genreCountsMovies`/`genreCountsSeries`
  (AllTimeStats, HomeSummary's `...Last30Days` variants) share one rule since 2026-09-03**: MOVIE
  counts every `DiaryEntry` (rewatch sums), SERIES counts distinct series *started* — `EPISODE` or a
  direct `SERIES`-type log both count, a rewatch of an already-started series never sums again.
  Windowed variants (`MonthInReview`/`YearInReview`/`...Last30Days`) apply the same distinct-series
  rule scoped to `watchedDate BETWEEN` the window — any activity inside the window counts the series
  once, regardless of when it was actually first started. Before this change, the windowed and
  all-time "Episodes" fields counted every individual `EPISODE` `DiaryEntry` (a rewatched 10-episode
  season added +10 to a genre instead of +1), while the profile/`/summary` fields deduplicated by
  distinct title for both MOVIE and SERIES (a movie rewatch never added anything) — two different,
  undocumented-as-different behaviors under similarly named fields. Dedupe between a direct `SERIES`
  log and its `EPISODE`s uses the `tmdbId` of the `SERIES`/`seriesTmdbId` of the episode as the same
  key, so a series is never counted twice even when logged both ways.
  `countDistinctTitlesByGenreAndUserIdForSeries`/`...AndWatchedDateBetween` and
  `countEntriesByGenreAndUserIdForMovies`/`...AndWatchedDateBetween` remain four separate native
  queries (not parameterized into one) because the `JOIN` to resolve genre differs: movie reads
  `c.genres` directly, series resolves via the `Content` `SERIES` row sharing `seriesTmdbId` (an
  episode never carries `genres` of its own) or, for a direct `SERIES` log, from that `Content` row
  itself.
- **`recentActivity`, ao contrário dos três campos acima, usa `MOVIE`/`SERIES` (não `EPISODE`)** —
  `DiaryEntryRepository.findTopByUserIdAndContentTypeOrderByCreatedAtDesc(userId, type, ...)` busca a
  `DiaryEntry` de nível `type` (o marcador de "filme assistido"/"série concluída"), não episódios
  individuais, já que "6 recentes, completos ou dropped" é sobre títulos inteiros, não progresso
  episódio a episódio (isso já é coberto por `GET /users/{userId}/series-in-progress`).
- **`recentActivity` mescla duas fontes heterogêneas em memória, sem query única** — busca os top-6 de
  `DiaryEntry` (`findTopByUserIdAndContentTypeOrderByCreatedAtDesc`) e os top-6 de `DroppedEntry`
  (`DroppedEntryRepository.findByUserIdAndTypeOrderByCreatedAtDesc`, já existente) separadamente,
  mapeia os dois pro mesmo formato (`RecentActivityItemDTO`, com um `status` `COMPLETED`/`DROPPED`),
  concatena, ordena por `activityDate` decrescente e corta em 6 no service — simples o bastante pra não
  justificar uma query SQL `UNION` entre duas tabelas com esquemas diferentes, já que o volume por
  usuário é sempre pequeno (no máximo 12 linhas antes do corte).
- **`recentEpisodes`/`recentReviews` são chamadas diretas de `DiaryEntryService.getDiaryEntries`, não
  query própria** — `recentEpisodes` só é preenchido quando `type=SERIES` (`type=EPISODE`, `size=4`,
  vazio para `type=MOVIE`); `recentReviews` sempre roda, escopado pelo mesmo `type` interno de
  `watchTime` (`hasReview=true`, `size=5`). Nenhuma checagem de visibilidade extra além da já feita no
  início de `getSummary` — `getDiaryEntries` não reaplica a checagem porque o `viewerId` já foi validado.
- **Checagem de visibilidade duplicada de `DiaryEntryServiceImpl.assertCanViewDiary`, não
  compartilhada** (`SummaryServiceImpl.assertCanViewSummary`) — mesma decisão de design já usada
  entre `Comment`/`UserList`/`UserListItem`/`Like` (cada consumidor mantém sua própria cópia da regra
  padrão de perfil público/dono/segue-aceito em vez de extrair um helper comum).
- **`GET /users/{userId}/summary/home` usa janela rolante de 30 dias corridos a partir de hoje, não mês
  calendário** (`SummaryServiceImpl.getHomeSummary`, `windowStart = LocalDate.now().minusDays(30)`) —
  diferente de `watchCountByDayLast30Days`/`genreCountsMoviesLast30Days`/`genreCountsSeriesLast30Days`
  do Month in Review, que são escopados por `YearMonth` (dia 1 até o último dia do mês).
  A tela Home reaproveita `countEntriesByGenreAndUserIdForMoviesAndWatchedDateBetween` (movies) e
  `countDistinctTitlesByGenreAndUserIdForSeriesAndWatchedDateBetween` (series) — mesmas queries do
  Month in Review — só trocando o range de datas; nenhuma query nova pros dois campos de gênero da
  Home especificamente (a query distinct-series windowed em si é nova em 2026-09-03, mas
  compartilhada com Month/Year in Review, não exclusiva da Home). `watchCountByDayLast30Days` é a única query nova (`countByUserIdAndWatchedDateBetween`,
  `COUNT` por dia agrupando `MOVIE`+`EPISODE`, diferente de `minutesPerDay` do Month in Review, que soma
  minutos e é escopado a um único `type` por vez).
- **`recentlyWatched` da Home mescla `MOVIE`+`EPISODE` em memória, mesmo padrão de merge de
  `recentActivity`** (`SummaryServiceImpl.computeRecentlyWatched`) — duas chamadas de
  `findTopByUserIdAndContentTypeOrderByCreatedAtDesc` (uma por tipo, top 4 cada), concatenadas,
  ordenadas por `createdAt` decrescente e cortadas em 4. Diferente de `recentActivity` do Perfil,
  não mescla `DroppedEntry` — "coisas assistidas" da Home é só o que foi de fato assistido, sem o
  lado abandonado.
- **`nextEpisodes` reaproveita a query de `series-in-progress` sem nenhuma mudança, só `size=6` fixo**
  (`SummaryServiceImpl.getHomeSummary`, `DiaryEntryRepository.findSeriesInProgressByUserId`) — "próximo
  episódio" (`maxEpisodeNumber + 1` dentro de `maxSeasonNumber`) não é calculado no backend, é
  responsabilidade do cliente a partir dos dois campos que a query já devolve (mesma limitação de
  `GET /users/{userId}/series-in-progress` — o backend não sabe quantos episódios uma temporada tem).
- **`topWatchCompanions` (Month/Year/All Time) reaproveita as tags de `WatchCompanion` já
  existentes, não infere overlap entre diários** (`SummaryServiceImpl.computeTopWatchCompanions`/
  `computeTopWatchCompanionsAllTime`, `WatchCompanionRepository.
  countGroupedByCompanionUserIdAndContentTypeAndWatchedDateBetween`/
  `countGroupedByCompanionUserIdAndContentTypeIn`) — "seguidores" aqui significa pessoas que o
  dono segue, não quem o segue, porque `WatchCompanion` só permite marcar a primeira direção (ver
  § DiaryEntry, "Assistido com"). Top 3, contando cada tag (rewatches incluídos), sem desempate
  explícito no 3º lugar (mesmo padrão de `mostLoggedContent`/`topSeriesByWatchTime`, só `ORDER BY
  count DESC`). Month/Year escopam por `type` (`MOVIE`/`EPISODE`, mesma conversão SERIES→EPISODE
  do resto da resposta) e pela janela de data já calculada pro resto do endpoint; All Time combina
  `MOVIE`+`EPISODE` sem filtro de data, mesmo padrão de `watchCountByDecade`/`watchCountByCountry`.
  SEASON/SERIES (marcadores automáticos de conclusão) nunca entram, porque o filtro de tipo usado é
  sempre `MOVIE`/`EPISODE`, nunca `SEASON`/`SERIES`. Companion que o dono deixou de seguir depois de
  marcar continua contando — a regra de "só quem você segue" vale só na criação da tag
  (`DiaryEntryServiceImpl.validateCompanions`), não retroativamente. Sem checagem de privacidade
  nova — quem já pode ver o summary já podia ver cada `DiaryEntry.watchedWith` individualmente.

## Feed

- **Sem entidade `Post`/`FeedEvent` própria — três fontes existentes mescladas em tempo de leitura**
  (`FeedServiceImpl.getFeed`) — um "post" é `DiaryEntry` (assistiu/completou), `DroppedEntry` (abandonou)
  ou `Top5Entry` (atualizou o Top5) de quem o usuário segue (`Follower` status `ACCEPTED`), nunca
  persistido separadamente. Mesmo espírito de `SummaryServiceImpl.computeRecentActivity`/
  `computeRecentlyWatched`, só que agregando vários usuários seguidos em vez de um só.
- **`DiaryEntry` com `ignore = true` nunca aparece no feed** (`DiaryEntryRepository.findFeedCandidates`,
  filtro `d.ignore = false`) — episódios/temporadas criados só como degrau mecânico de um
  `POST /diary/bulk` de nível superior (ver `DiaryEntry.ignore` na seção `DiaryEntry`) ficam de fora,
  evitando que marcar uma temporada/série inteira como assistida spamme o feed de quem segue o
  usuário com um post por episódio; a entrada do nível efetivamente pedido (a temporada de um bulk de
  temporada, a série de um bulk de série) continua aparecendo normalmente.
- **`FeedItem.watchedWith` reaproveita o mesmo batch load de `getDiaryEntries`, sem query nova** —
  `FeedServiceImpl.getFeed` chama `WatchCompanionRepository.findByDiaryEntryIdIn` sobre a página de
  `DiaryEntry` que já buscou; só populado pra `eventType=DIARY_ENTRY` (`DROPPED`/`TOP5_UPDATE` sempre
  `null`, já que "assistido com" é conceito exclusivo de `DiaryEntry`). Ver `WatchCompanion` na seção
  `DiaryEntry`.
- **Decisão de arquitetura: pull (query em tempo de leitura), não push (fan-out no write)** — decidido em
  conversa antes da implementação. Fan-out no write exigiria uma tabela `FeedEvent` própria, escrita
  duplicada em todo lugar onde `DiaryEntry`/`DroppedEntry`/`Top5Entry` são criados, e lógica extra pra
  refletir follow/unfollow e mudança de privacidade retroativamente. Pull resolve tudo isso de graça —
  a lista de seguidos é sempre lida na hora — e só compensaria trocar pra push em escala de contas com
  milhares de seguidores, que não é o caso do Watchwise.
- **Paginação por cursor (keyset), não página numerada** (`FeedServiceImpl.decodeCursor`/`encodeCursor`,
  schema `CursorPageMeta` em `openapi.yaml`) — diferente de todo outro endpoint paginado do projeto
  (`PageRequestFactory`/`PageResponseDTO`). Offset é incorreto aqui, não só menos otimizado: como o feed
  recebe inserts constantes, paginação por número de página desloca o offset entre requests e duplica ou
  pula item ao rolar. Cursor é `base64(createdAt|id)` do último item da página anterior; cada fonte é
  filtrada por `createdAt < cursor.createdAt OR (createdAt = cursor.createdAt AND id < cursor.id)`,
  usando sempre o **próprio** `id` daquela tabela no desempate (nunca compara `id` entre tabelas
  diferentes). Sem `totalElements`/`totalPages` — só `hasNext` + `nextCursor`.
- **Limitação aceita: empate exato de `createdAt` entre fontes diferentes na borda da página** — o
  desempate por `id` só é semanticamente válido dentro da mesma tabela (o cursor guarda o `id` de uma
  única fonte). Um evento de outra fonte com o exato mesmo `createdAt` (até o microssegundo, resolução
  do `TIMESTAMP` do Postgres) do item de corte pode, na borda, ficar de fora ou duplicado numa próxima
  página. Colisão entre chamadas de serviço diferentes é praticamente impossível; a única colisão
  plausível é dentro da mesma fonte (ex.: `POST /diary/bulk` compartilhando um único `now` entre várias
  linhas), caso já coberto corretamente pelo desempate por `id` da própria tabela.
- **`hasNext` via fetch de `size + 1` por fonte, não `COUNT`** (`FeedServiceImpl.getFeed`) — cada uma das
  três queries (`DiaryEntryRepository`/`DroppedEntryRepository`/`Top5EntryRepository.findFeedCandidates`)
  busca `size + 1` linhas; a linha extra (se vier) é descartada antes do merge e vira um flag
  `xHasMore`. `hasNext` final é `true` se a lista mesclada (antes do corte em `size`) tiver sobra **ou**
  se qualquer fonte sozinha tiver o flag — o segundo caso cobre quando uma fonte tem mais itens além do
  buscado mas, depois de mesclada com as outras (menores), o total mesclado não ultrapassa `size`.
- **Evento de Top5 é genérico ("atualizou o Top 5 de {type}"), sem detalhar qual item entrou/saiu**
  (`FeedServiceImpl.toTop5FeedItem`) — decidido em conversa depois de checar `Top5EntryServiceImpl`:
  `shiftUpFrom` (insert) e o shift dentro de `removeEntry` só alteram `position` dos vizinhos, nunca
  `updatedAt` (só existem `insertEntry`/`removeEntry`, sem update-em-lugar), então `createdAt` sozinho já
  é um sinal limpo de "postou" — sem risco de um shift de posição virar post falso. Efeito colateral
  aceito: uma remoção sem inserção correspondente não gera post (não cria linha nova), razoável pra um
  feed social.
- **Curtida/comentário é deliberadamente só para `eventType=DIARY_ENTRY`, por design — não uma
  pendência** (`FeedItemDTO.id`) — reaproveita `POST /diary/{id}/like` e `GET`/`POST
  /diary/{id}/comments` já existentes, sem nenhum código novo, porque `DiaryEntry` já é um alvo válido
  de `Like`/`Comment`. `DROPPED`/`TOP5_UPDATE` não devem ter curtida/comentário (decidido em conversa) —
  `DroppedEntry`/`Top5Entry` continuam de propósito fora do `CHECK` de `Like`/`Comment`
  (`Content`/`UserList`/`DiaryEntry` só), sem trabalho pendente aqui (ver `telas.md` § Social).
- **`likedByMe` computado em lote só para os itens `DIARY_ENTRY` da página, não hardcoded `false`**
  (`FeedServiceImpl.getFeed`, `likeService.getLikedDiaryEntryIds`) — diferente do atalho já usado em
  `SummaryServiceImpl` (`diaryEntryMapper.diaryEntryToResponseDto(entry, false)` nas agregações de
  Summary), o feed reaproveita o mesmo batch check já usado em `DiaryEntryServiceImpl.getDiaryEntries`
  em vez de fixar `false`, já que aqui o campo é exibido lado a lado com o botão de curtir.
- **Visibilidade: seguir com status `ACCEPTED` já é permissão suficiente, sem checagem extra por item**
  (`FeedServiceImpl.getFeed`, via `FollowerRepository.findFollowedIdsByFollowerIdAndStatus`) — mesma
  regra já usada em `assertCanViewSummary`/`assertCanViewTop5`: um `Follower` aceito dá acesso total ao
  que o seguido posta, público ou privado, então o feed não reaplica a checagem de `isProfilePublic` por
  evento.

## Notification

- **`RENEWED` é uma heurística, não um status literal do TMDB** (`ContentChangeDetector.detectTvChange`)
  — o TMDB não tem um status "Renewed" próprio; o sinal usado é o status anterior conhecido ser
  `Ended` ou `Canceled` e o status atual vir como `Returning Series`
  (`ContentChangeDetector.isEndedOrCancelled` + comparação com `"Returning Series"`). `CANCELLED` tem
  prioridade sobre `RENEWED` no mesmo diff: se o status atual é `Canceled`, gera `CANCELLED`
  independentemente do status anterior, e só então (no branch `else if`) a série é avaliada para
  `RENEWED`.
- **Tabela de diff, uma chamada ao TMDB por título rastreado por execução do job**
  (`ContentChangeDetector.detectMovieChange`/`detectTvChange`, chamado a partir de
  `ContentTrackingServiceImpl.processMovie`/`processSeries`):
  - `ANNOUNCED_DATE`: não havia `lastKnownReleaseDate` registrado e o TMDB agora traz uma data de
    lançamento futura (`freshReleaseDate.isAfter(today)`).
  - `RELEASE`: havia um `lastKnownReleaseDate` registrado e essa data já chegou ou passou
    (`!today.isBefore(previousReleaseDate)`) — dispara uma vez, no primeiro run em que a data vira
    presente/passado.
  - `CANCELLED` (filme ou série): status anterior diferente de `Canceled` e status atual é `Canceled`.
  - `RENEWED` (só série): ver heurística acima.
  - `NEW_EPISODE` (só série): havia um `nextEpisodeAirDate` registrado no estado anterior e essa data já
    chegou ou passou; usa a temporada/episódio já salvos em `TrackedContentState` (não o novo
    `nextEpisodeToAir` da resposta fresca), então o episódio notificado é sempre o que já tinha sido
    anunciado numa execução anterior do job, nunca o mais recente da mesma chamada.
  - `FOLLOWED_PERSON_NEW_CREDIT`: não é um diff sobre um `Content` rastreado — é gerado por
    `FollowedPersonTrackingJob.processCredit` quando um `credit.id()` retornado pelo TMDB para uma
    pessoa seguida não existe ainda em `TrackedPersonCredit` para aquele `TrackedPersonState`.
- **Uma única chamada ao TMDB alimenta vários tipos de notificação de uma vez** — `TmdbClient
  .getMovieDetails`/`getTvDetails` (uma chamada por filme/série rastreado por execução do
  `ContentTrackingJob`) retorna status, data de lançamento e próximo episódio a anunciar num único
  payload; `ContentChangeDetector.detectMovieChange` pode gerar `CANCELLED` ou `RELEASE`/
  `ANNOUNCED_DATE` a partir da mesma resposta, e `detectTvChange` pode gerar `CANCELLED`/`RENEWED` **e**
  `NEW_EPISODE` na mesma chamada (retorna `List<ContentChangeEvent>`, não um único evento) — desenhado
  assim de propósito para não multiplicar chamadas ao TMDB por tipo de notificação.
- **Deduplicação: um `Content` rastreado por mais de uma fonte só é checado uma vez por execução**
  (`ContentTrackingServiceImpl.trackContentChanges`) — o conjunto rastreado é a união de
  `WatchlistEntryRepository.findDistinctTrackedContent` (watchlist) e `DiaryEntryRepository
  .findDistinctInProgressSeriesTmdbIds` (série em andamento no diário), mesclada num
  `LinkedHashMap<UUID, Content>` chaveado por `content.getId()` antes de processar — uma série que está
  tanto na watchlist quanto sendo assistida gera só uma chamada ao TMDB por execução, nunca duas.
- **Conteúdo com status terminal para de ser rechecado diariamente (adicionado 2026-09-05)** —
  `ContentTrackingServiceImpl.processTrackedContent` consulta só o status (`TrackedContentStateRepository
  .findLastKnownStatusByContentId`, projeção leve, sem carregar a entidade inteira) antes de decidir se
  chama o TMDB; se o último status conhecido já é terminal pro tipo (`Released`/`Canceled` pra `MOVIE`,
  `Ended`/`Canceled` pra `SERIES` — constantes públicas em `ContentChangeDetector`), pula o item sem
  nenhuma chamada TMDB. Motivação: esses estados nunca mudam sozinhos (um filme já lançado não some, uma
  série encerrada não solta episódio novo), então rechecar todo dia era desperdício puro. Conteúdo sem
  `TrackedContentState` ainda (baseline nunca estabelecido) sempre é checado normalmente. A única forma
  de "descongelar" um item pulado é o caminho de revival descrito abaixo.
- **Estreia de série e nova temporada anunciada reaproveitam `RELEASE`/`ANNOUNCED_DATE`, sem
  `NotificationType` novo (adicionado 2026-09-05)** — `ContentChangeDetector.detectTvChange`:
  - Emite `RELEASE` quando o status anterior era de pré-lançamento (`Planned`/`In Production`/`Pilot`)
    e o status fresco vira `Returning Series` ou `Ended` — fecha a lacuna de "série estreou" que antes
    só disparava incidentalmente via `NEW_EPISODE`, e só se já houvesse uma data conhecida numa rodada
    anterior.
  - Emite `ANNOUNCED_DATE` (com `seasonNumber` preenchido, diferente do uso em filme) quando a
    temporada de maior número em `fresh.seasons()` (`TmdbSeasonSummary`, agora também exposto em
    `TmdbTvDetails` — mesmo endpoint `/tv/{id}`, sem custo extra de chamada) passa de sem data conhecida
    pra uma data futura conhecida (`ContentChangeDetector.latestSeason`, exclui a temporada `0`/
    Specials). É o sinal mais cedo disponível — `seasons[].air_date` fica conhecido antes de
    `next_episode_to_air` populado, que depende de dado no nível de episódio. `TrackedContentState`
    guarda a temporada/data mais recente já vista (`lastKnownSeasonNumber`/`lastKnownSeasonAirDate`)
    pra essa comparação.
  - `ContentTrackingServiceImpl.buildMessage` distingue as duas origens de `ANNOUNCED_DATE` pela
    presença de `seasonNumber` no evento (mensagem de temporada vs. mensagem de filme).
- **Público de cada tipo é resolvido de fontes diferentes, por design** (`ContentTrackingServiceImpl
  .notifyWatchers`) — `NEW_EPISODE` notifica quem está assistindo a série no diário
  (`DiaryEntryRepository.findUserIdsWatchingSeries`); os demais tipos gerados pelo diff de conteúdo
  (`RELEASE`, `ANNOUNCED_DATE`, `CANCELLED`, `RENEWED`) notificam quem tem o título na watchlist
  (`WatchlistEntryRepository.findUserIdsByContentId`). Não é uma lacuna: só faz sentido avisar "saiu
  novo episódio" pra quem já está acompanhando a série, e "foi cancelada"/"foi renovada"/"lançou"/"ganhou
  data" pra quem está esperando pra assistir. `FOLLOWED_PERSON_NEW_CREDIT`
  (`FollowedPersonTrackingJob.notifyFollowers`) tem sua própria fonte, independente das duas acima: quem
  segue aquela pessoa (`FollowedPersonRepository.findUserIdsByPersonTmdbId`).
- **Resiliência por item: uma transação física isolada por `Content`/pessoa rastreada, mais um
  catch-and-continue por cima** — `ContentTrackingServiceImpl.processMovie`/`processSeries` e
  `FollowedPersonTrackingJob.processPerson` embrulham a leitura do estado anterior, o diff e o save do
  novo estado num único `NewTransactionExecutor.runInNewTransaction`, para que uma falha ao processar um
  título/pessoa não deixe a transação ambiente do job inteiro em estado de rollback (mesmo padrão
  documentado em `CLAUDE.md` para get-or-create idempotente). Por cima disso,
  `ContentTrackingServiceImpl.processTrackedContent`/`FollowedPersonTrackingJob.processPerson` capturam
  `RuntimeException` por item (`log.warn` e segue para o próximo), então uma falha isolada (TMDB fora do
  ar para um título, erro de rede) não aborta o restante da execução agendada. Em `processSeries`, o
  status anterior é copiado pra uma `String` **antes** de `saveSeriesState` rodar — `saveSeriesState`
  muta o mesmo objeto `TrackedContentState` recebido, então guardar a referência ao objeto (em vez do
  valor) faria qualquer comparação "status anterior vs. fresco" feita depois comparar o status fresco
  contra ele mesmo (bug real encontrado e corrigido durante o desenvolvimento de 2026-09-05).
- **Limitação aceita: sem proteção contra corrida em múltiplas instâncias** —
  `TrackedContentState`/`TrackedPersonState`/`TrackedPersonCredit` têm constraints `UNIQUE` no banco mas
  seus `save` (em `ContentTrackingJob`/`FollowedPersonTrackingJob`) não capturam
  `DataIntegrityViolationException` como o padrão idempotente get-or-create documentado em `CLAUDE.md`
  exige normalmente. Decisão deliberada, confirmada com o dono do projeto: hoje a aplicação roda como
  instância única, sem escalonamento horizontal planejado, então duas execuções concorrentes do mesmo
  `@Scheduled` job nunca competem pela mesma linha de cache. Se a aplicação passar a rodar em múltiplas
  instâncias sem um lock distribuído sobre os jobs agendados, isso precisará do mesmo padrão
  `NewTransactionExecutor` + catch-and-requery já usado em outros get-or-create do projeto.
- **Revival de série congelada é detectado de forma lazy, fora do job diário (adicionado 2026-09-05)**
  — como uma série `Ended`/`Canceled` sai do rastreio diário (ver acima), a única forma de notar que
  ela voltou a ser produzida é via `ContentTrackingService.reactivateAfterRevival(Content, String
  freshStatus)`, chamado por `ContentDetailsServiceImpl.buildSeriesDetails` toda vez que alguém pede os
  detalhes daquele título e o status fresco não é mais terminal. Internamente: se o status **anterior**
  salvo (`TrackedContentStateRepository.findLastKnownStatusByContentId`) também não era terminal, não
  faz nada (não é revival, é só uma série ativa normal); se era terminal, atualiza o
  `TrackedContentState` (tira do estado congelado, reativando o rastreio diário) e dispara `RENEWED`
  pra quem tem o título na watchlist. **Trade-off aceito, não uma lacuna:** troca a garantia de "aviso
  proativo mesmo sem ninguém olhar" por "aviso só quando alguém visitar os detalhes de novo" — só pra
  esse caso raro de revival. Isso introduz a única dependência do pacote `content` sobre o pacote
  `notification` no projeto (inverte a direção usual, já que `notification` é "satélite" e depende dos
  outros) — decisão deliberada, discutida e aprovada com o usuário, ver
  `docs/pending/tmdb-tracking-runtime-optimization-proposal-2026-09-05.md`.
