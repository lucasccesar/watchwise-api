# Auditoria de chamadas ao TMDB e otimização — 2026-09-04

Levantamento pedido pelo usuário: procurar, em toda a aplicação, lugares fazendo mais requisições ao
TMDB do que deveria, e outros pontos mal otimizados. Só investigação até aqui — nada foi implementado.

---

## 1. ✅ (resolvido 2026-09-04) `POST /diary` (entrada única) busca os mesmos dados do TMDB duas vezes, em idiomas diferentes

**Onde:** `DiaryEntryServiceImpl.java` + `ContentServiceImpl.java`

**Filme novo:**
- `resolveContentRefForCreation` → `resolveReleaseDate` (`DiaryEntryServiceImpl.java:383`) chama
  `getMovieFullDetails(tmdbId, language)` no idioma do usuário só para validar a data de lançamento.
- Em seguida, `createDiaryEntry` (linha 178) chama `contentService.getOrCreateReference(...)` — como o
  filme ainda não existe como `Content`, isso cai em `ContentServiceImpl.resolveNewContentMetadata`
  (linha 256), que faz **outro** `getMovieFullDetails(tmdbId, "en-US")`
  (`ContentServiceImpl.EXISTENCE_CHECK_LANGUAGE`), só para confirmar existência e extrair
  gênero/país/duração/ano.

Mesmo endpoint TMDB, mesmo `tmdbId`, duas chamadas de rede reais — o cache do `TmdbClient` é por
`tmdbId + idioma`, então só "casam" se o `preferredLanguage` do usuário for `en-US`.

**Episódio novo**, mesmo padrão:
- `withDerivedEpisodeFinaleFlags` (`DiaryEntryServiceImpl.java:413`) já busca
  `getSeasonFullDetails(series, season, language)` — que **já traz o `runtime` do episódio** (mesmo
  campo lido em `episodeRuntimeMinutesFromTmdb`, linha 636, usado no bulk-log).
- Mesmo assim, `getOrCreateReference` (chamado com `trustedRuntimeMinutes=false` no caminho
  single-entry) refaz `getTvFullDetails(series, "en-US")` **e**
  `getEpisodeFullDetails(series, season, episode, "en-US")` (`ContentServiceImpl.java:264-269`) para
  redescobrir um dado que já estava na resposta anterior.

Pior caso (episódio novo, de temporada nova, que também é final de temporada): até **4 chamadas TMDB**
para logar 1 episódio, quando 1-2 bastariam.

**Importante:** fixar o idioma em `en-US` para **gêneros** é proposital — evita que `Content.genres`
fique em idiomas diferentes dependendo de quem criou o registro primeiro (primeira escrita vence, ver
`assertNoMetadataMismatch`/`backfillMissingTmdbMetadata`). Isso não deveria mudar. Mas `releaseDate` e
`runtime` **não dependem de idioma** no TMDB — só texto (título/overview) muda com o parâmetro
`language`. Dá para eliminar a duplicidade sem tocar na regra de consistência de gêneros:
- fazendo `resolveReleaseDate` usar sempre `EXISTENCE_CHECK_LANGUAGE` (mesma chave de cache que
  `getOrCreateReference` já vai usar de qualquer forma), ou
- fazendo o caminho single-entry se comportar como o bulk: passar `trustedRuntimeMinutes=true` e
  reaproveitar o `TmdbSeasonFullDetails` já buscado em `withDerivedEpisodeFinaleFlags`
  (`bulkLogEpisode` já faz exatamente isso).

O próprio código já contém o comparativo: `bulkLogSeason`/`bulkLogSeries` evitam essa duplicação
(buscam a temporada uma vez e reaproveitam runtime de todos os episódios); só o fluxo single-entry
(`createDiaryEntry`) não reaproveita.

---

## 2. ✅ (resolvido 2026-09-04 — não com `sync = true`, ver nota) `@Cacheable` sem `sync = true` nas 4 chamadas cacheadas → cache stampede

**Onde:** `TmdbClient.java:45,58,71,84` (`getMovieFullDetails`, `getTvFullDetails`,
`getSeasonFullDetails`, `getEpisodeFullDetails`)

Nenhuma usa `sync = true`. Se duas requisições concorrentes pedirem o mesmo conteúdo antes do cache
estar populado — dois usuários abrindo a mesma série ao mesmo tempo, ou um job agendado
(`ContentTrackingJob`/`FollowedPersonTrackingJob`) rodando junto com tráfego de usuário pedindo o mesmo
`tmdbId` — **ambas disparam a chamada real ao TMDB** em vez de uma esperar a outra e reaproveitar o
resultado. ~~Correção é trivial: adicionar `sync = true` em cada uma das 4 anotações.~~

**Nota (2026-09-04):** `sync = true` foi testado na prática e não funciona nesse codebase — Spring
recusa em runtime com `IllegalStateException: A sync=true operation does not support the unless
attribute`, já que as 4 chamadas dependem de `unless = "#result.isUnavailable()"` (regra de 2026-09-03
que impede cachear uma indisponibilidade transitória do TMDB por 24h). As duas opções são mutuamente
exclusivas no Spring Cache. Corrigido de outra forma: as 4 chamadas trocaram `@Cacheable`/`CacheManager`
por `com.github.benmanes.caffeine.cache.Cache<String, TmdbLookupResult<X>>` injetado direto em
`TmdbClient` (`TmdbClient.cachedLookup`) — `Cache.get(key, mappingFunction)` do Caffeine já é atômico
por chave (mesma proteção que `sync = true` daria) e permite retornar `null` da função de carga pra não
cachear o caso `isUnavailable()`, preservando a regra de 2026-09-03 sem depender do `unless` do Spring.
Ver `docs/context/business-rules.md` § Content.

---

## 3. ✅ (resolvido 2026-09-05) `GET /contents/{id}/details` em série pode disparar dezenas de chamadas TMDB por request

**Onde:** `ContentDetailsServiceImpl.buildSeriesDetails` (linha 151) →
`fetchAllSeasonsInParallel` (linha 474)

Busca `getSeasonFullDetails` para **todas as temporadas** da série em paralelo (pool fixo de 8
threads, `TmdbCacheConfig.SEASON_FETCH_THREAD_POOL_SIZE`), só para calcular duração média/total e os
"últimos episódios exibidos" (`recentlyAiredEpisodes`, limitado a 3 no resultado final). Para uma série
longa (20+ temporadas) isso é 20+ chamadas TMDB na primeira vez que alguém pede os detalhes (cache
frio). E como `getDetailsBatch` aceita até `MAX_BATCH_IDS = 100` ids por request, um batch com várias
séries longas pode gerar uma quantidade grande de chamadas na mesma requisição HTTP do usuário. Vale
avaliar se o dado (runtime médio/estreias recentes) realmente precisa de *todas* as temporadas, ou só
das mais recentes.

**Resolvido:** `totalRuntimeMinutes`/`runtimeMinutesEpisodeCount` passaram a ser persistidos em
`Content` (só `SERIES`) e mantidos incrementalmente pelo backend — série `Ended`/`Canceled` com
baseline já salvo pula a busca de todas as temporadas, buscando só as 2 mais recentes pra
`recentlyAiredEpisodes`/`seasons[].airedEpisodeCount`. Ver
`docs/pending/tmdb-tracking-runtime-optimization-proposal-2026-09-05.md` e
`docs/context/business-rules.md` § Content pro design completo. `recentlyAiredEpisodes` continua
buscando temporadas mesmo pra série terminal (só reduzido de N pra 2), não eliminado por completo —
fora de escopo, ver nota de item futuro no documento de proposta.

---

## 4. ✅ (resolvido 2026-09-05) Notificações em massa: `save()` em loop em vez de `saveAll()`

Não é TMDB, mas é ineficiência geral encontrada no caminho:

- `ContentTrackingServiceImpl.notifyWatchers` e `FollowedPersonTrackingServiceImpl.notifyFollowers`
  faziam `userIds.forEach(... notificationRepository.save(...))` — um `INSERT` por usuário notificado,
  em vez de montar a lista e usar `saveAll`. Para um título popular com muitos seguidores/watchers, isso
  era N round-trips ao banco em vez de 1.

**Resolvido:** os dois métodos agora montam a lista de `Notification` via `stream().map(...).toList()` e
chamam `notificationRepository.saveAll(notifications)` uma única vez. Testes atualizados para capturar
`List<Notification>` em vez de uma `Notification` por chamada.

---

## O que já está bem feito (conferido, não precisa mexer)

- `ContentTrackingServiceImpl.trackContentChanges` já deduplica conteúdo rastreado num `LinkedHashMap`
  antes de chamar o TMDB — coberto por teste
  (`shouldOnlyCallTmdbOnceWhenTheSameSeriesIsBothWatchlistedAndInProgress`).
- `FollowedPersonTrackingServiceImpl` só chama `getOrCreateReference` para créditos realmente novos
  (`existsByTrackedPersonStateIdAndCreditTmdbId` checado antes).
- O bulk-log de diário (`bulkLogSeason`/`bulkLogSeries`) já é o padrão correto: busca a temporada uma
  vez, reaproveita para runtime de todos os episódios via `trustedRuntimeMinutes=true`, e a verificação
  de existência não se repete por episódio graças ao cache por `(seriesTmdbId, idioma)`.
- `backfillMissingTmdbMetadata` só chama o TMDB quando o campo realmente está faltando no `Content` já
  existente — não bate no TMDB a cada referência a um conteúdo já cadastrado.
- `getMovieDetails`/`getTvDetails` (não cacheados, usados só pelo job diário de tracking) não sofrem
  com isso porque o job já deduplica antes de chamar.
