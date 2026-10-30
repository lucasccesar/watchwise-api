# Chamadas ao TMDB por tela

> Levantamento feito em 2026-08-31 direto do código (`TmdbClient`, `ContentDetailsServiceImpl`,
> `ContentTrackingServiceImpl`, `FollowedPersonTrackingServiceImpl`) e cruzado com as telas descritas
> em `telas.md`. Não é um documento de arquitetura nem de regra de negócio (isso continua em
> `business-rules.md`) — é só o mapa de "essa tela → esses dados → essas chamadas TMDB acontecem no
> backend".

## Como ler este documento

**O cliente nunca chama o TMDB direto.** A `api-key` do TMDB fica só no backend. A partir dos modelos
de cards das Tasks 1–10, as telas client-facing recebem metadata no próprio endpoint da tela:

1. Home, Profile, Diary, Summary, Watchlist, Trending e Feed usam `ContentCardDTO` ou um modelo visual
   equivalente; não chamam `/contents/details` para cada card e não chamam `POST /contents/reference`
   durante uma leitura.
2. `GET /contents/{contentId}/details` e `GET /contents/details?ids=` continuam como contratos de
   detalhe completo/compatibilidade para clientes antigos e para a tela Content Detail. O uso deles
   não deve ser propagado para os novos caminhos de card.
3. **De forma assíncrona, via `Notification`** — os dois jobs `@Scheduled` (`ContentTrackingJob`,
   `FollowedPersonTrackingJob`) chamam o TMDB em background e gravam `Notification` no banco. Quando
   o cliente abre a tela de Notificações, ele só lê `Notification` já salva.

`ContentCardAssembler` deduplica coordenadas e pede somente as facetas necessárias. O cache canônico
de metadata não contém estado do viewer; `viewerState` e posters customizados são resolvidos depois,
no escopo do usuário. `previewStatus` permite manter uma linha com metadata `PARTIAL` ou `UNAVAILABLE`
sem fabricar valores.

## Inventário dos métodos de `TmdbClient`

| Método | Endpoint TMDB | Cache | Quem chama |
|---|---|---|---|
| `getMovieDetails(tmdbId)` | `GET /movie/{id}` | não cacheado | `ContentTrackingServiceImpl` (job) |
| `getTvDetails(tmdbId)` | `GET /tv/{id}` | não cacheado | `ContentTrackingServiceImpl` (job) |
| `getPersonCombinedCredits(personTmdbId)` | `GET /person/{id}/combined_credits` | não cacheado | `FollowedPersonTrackingServiceImpl` (job) |
| `getMovieFullDetails(tmdbId, language)` | `GET /movie/{id}?append_to_response=credits,watch/providers,alternative_titles&language=` | Caffeine, chave `(tmdbId, language)` | `ContentDetailsServiceImpl.buildMovieDetails` |
| `getTvFullDetails(tmdbId, language)` | `GET /tv/{id}?append_to_response=aggregate_credits,watch/providers,alternative_titles&language=` | Caffeine, chave `(tmdbId, language)` | `ContentDetailsServiceImpl.buildSeriesDetails`/`buildSeasonDetails`/`buildEpisodeDetails` |
| `getSeasonFullDetails(seriesTmdbId, seasonNumber, language)` | `GET /tv/{seriesId}/season/{n}?append_to_response=aggregate_credits,watch/providers&language=` | Caffeine, chave `(seriesTmdbId, seasonNumber, language)` | `ContentDetailsServiceImpl.buildSeasonDetails` + `fetchAllSeasonsInParallel` |
| `getEpisodeFullDetails(seriesTmdbId, seasonNumber, episodeNumber, language)` | `GET /tv/{seriesId}/season/{n}/episode/{e}?language=` | Caffeine, chave `(seriesTmdbId, seasonNumber, episodeNumber, language)` | `ContentDetailsServiceImpl.buildEpisodeDetails` |

Os três primeiros (`getMovieDetails`/`getTvDetails`/`getPersonCombinedCredits`) são chamadas mais
simples (sem `append_to_response`, sem cache) usadas só pelos jobs de tracking — nunca aparecem no
caminho de uma tela sendo aberta. TTL do cache: `app.tmdb.details-cache-ttl-hours`; base URL/timeout:
`app.tmdb.base-url`/`app.tmdb.timeout-ms`. Chave de cache nunca inclui `region` — o TMDB devolve
todas as regiões numa resposta só, o backend filtra depois de ler o cache.

## Quantas chamadas `GET /contents/{contentId}/details` dispara, por `type`

| `Content.type` | Chamadas TMDB disparadas |
|---|---|
| `MOVIE` | 1× `getMovieFullDetails` |
| `SERIES` | 1× `getTvFullDetails` + até N× `getSeasonFullDetails` em paralelo (uma por temporada, exceto a temporada 0) — o custo de N só acontece de verdade na primeira vez que a série é pedida depois do cache expirar; chamadas seguintes reaproveitam o cache de cada temporada |
| `SEASON` | 1× `getSeasonFullDetails` da própria temporada + 1× `getTvFullDetails` da série (pra genres/countries/creators) |
| `EPISODE` | 1× `getEpisodeFullDetails` do próprio episódio + 1× `getTvFullDetails` da série (pra genres/countries/cast) |

`GET /contents/details` (batch) faz isso pra cada id pedido, na mesma lógica — sem chamada TMDB
extra além da tabela acima por item, e reaproveitando cache entre itens que compartilham a mesma
série/temporada.

## Por tela

### Users → Perfil

- `summary` e `top5` já retornam `card` nas linhas visuais; Profile pede `BASIC_METADATA` e
  `SOCIAL_METADATA` em uma resolução deduplicada no backend. Não há chamada de detalhe por item.
- `card.customPosterUrl` usa o dono do perfil, não o viewer; indisponibilidade do TMDB preserva a
  linha com `previewStatus=UNAVAILABLE`.

### Users → History / Reviews

- Cada `DiaryEntry` da página atual já traz `card` opcional. Diary History/Reviews pedem metadata
  básica e stats depois da paginação, usando o dono consultado para poster customizado; não chamam
  `ContentDetailsService` nem criam referências durante a leitura.

### Users → Progress (séries em andamento)

- `GET /users/{userId}/series-in-progress` agora enriquece cada item da página com `seriesTitle`,
  `seriesPosterPath`, `lastWatchedEpisodeTitle` e `nextEpisode`, usando o idioma preferido do usuário.
- O cliente não precisa fazer `GET /contents/{contentId}/details` por série para montar a lista. O
  poster customizado já retornado pelo endpoint tem precedência sobre `seriesPosterPath`.
- `nextEpisode` é resolvido no backend como o primeiro episódio regular não assistido em ordem de
  temporada/episódio. Specials são ignorados; episódios futuros podem ser retornados com
  `availableToWatch=false`.
- O backend faz uma consulta em lote das coordenadas assistidas da página e reutiliza o cache Caffeine
  do `TmdbClient`; não cria referências `Content` nem persiste metadados do TMDB durante o GET.

### Users → Listas e Listas Curtidas (preview)

- O preview de lista (`GET /users/{userId}/lists`, `GET /users/me/liked-lists`) só devolve metadados
  agregados (`name`, `itemsCount`, `likesCount`, `commentsCount`) — **sem chamada TMDB nessa tela**,
  a não ser que a UI decida mostrar capa/pôster de item (não especificado em `telas.md`).

### Users → Lista (detalhe)

- Cada item da lista (incluindo os itens de uma lista aninhada) é um `Content` → `GET /contents/details`
  batch pra título/pôster/duração/gênero — usado tanto pra exibir quanto pros filtros/ordenação que
  `telas.md` marca como 🚫 (alfabética, data de lançamento, busca por nome): esses já dependem do
  mesmo dado batch, só que processado no cliente depois de recebido.

### Users → Month in Review / Year in Review / All Time Stats

- Os endpoints de Summary já retornam `card` nos itens selecionados. Month acrescenta
  `topLongestMovieCards` e preserva `topLongestMovies`; Year e All Time carregam cards nos rankings
  aditivos. A resolução é deduplicada por coordenada e pede somente `BASIC_METADATA` ou `STATS`, sem
  `/contents/details` e sem escrita.

### Users → Notas de Episódios de uma Série

- `GET /users/{userId}/series/{seriesTmdbId}/episode-ratings` e
  `GET /users/{userId}/episode-ratings-map` resolvem metadata e coordenadas pelo backend. O cliente
  não precisa criar `Content` nem obter um `contentId` apenas para montar a grade.

### Home

- `GET /users/{userId}/summary/home` já entrega `recentlyWatched[].card` e
  `socialActivities[].card`; `nextEpisodes` já vem enriquecido pelo backend.
- Próximos 6 lançamentos da Home → `GET /users/{userId}/summary/home`, no campo
  `upcomingReleases`; a Home não consulta o calendário mensal para montar esse bloco.

### Calendário

- `GET /users/me/calendar` é o contrato completo da tela. Ele lê/atualiza snapshots compartilhados
  de agenda por conteúdo/região/idioma e devolve data, conteúdo, status, sources e `releaseContext`.
  O calendário não usa `ContentCardAssembler`, `/contents/details` nem cria referências `Content`.
- `releaseTime` e `network` são opcionais e só aparecem quando a fonte é suficiente e consensual;
  episódios não recebem horário fabricado. Não existe parâmetro `granularity`; `eventType` informa a
  granularidade agrupada ou episódica.

### Feed

- `GET /feed` retorna `FeedItemView` depois de selecionar a página final pelo cursor. Cards primários,
  Top 5 e alvos de Pick usam metadata leve deduplicada; `DAILY_GAME_RESULT` não recebe card de Content.
- `followingCount` e `followedUsersPreview` são dados locais; estado de viewer não entra no cache de
  metadata. Candidatos descartados antes do corte não acionam enriquecimento.

### Watchlist

- `GET /users/{userId}/watchlist/view` carrega candidatos locais, aplica filtros e ordenação e só
  resolve cards da página selecionada. `TITLE` e `RATING` podem pedir metadata básica para ordenar o
  conjunto completo, com limite de candidatos materializados; o card final também pode incluir stats e
  poster social do dono.
- Datas e status usam `ContentReleaseDateSnapshotService` e progresso local em lote. Snapshot ausente
  pode causar uma resolução TMDB e persistência do snapshot compartilhado; isso não é criação de
  `Content` e o envelope da watchlist não é cacheado.

### TMDB Trending

- `GET /trending` chama `/trending/movie/{day|week}` e `/trending/tv/{day|week}` no TMDB, preservando
  a ordem externa. `GET /trending/{type}` chama somente a seção solicitada e aceita `page` externo
  de 1 a 500.
- Cache compartilhado é separado por tipo, janela, página e idioma. Apenas a página retornada é
  enriquecida com metadata canônica; `viewerState` é resolvido depois e não é armazenado no cache.
- O ranking permanece do TMDB. `popularity` e `tmdbVoteAverage` são campos TMDB; Watchwise não
  introduz ranking comunitário nem chama suas estatísticas como origem do Trending.

### Person

- `GET /people/{personTmdbId}` faz uma única chamada backend ao TMDB por pessoa e idioma:
  `/person/{id}?language={preferredLanguage}&append_to_response=combined_credits`. A resposta agregada
  fica em cache limitado a 24 horas, com chave `(personTmdbId, preferredLanguage)`. O backend não faz
  chamadas individuais por filme/série de cada crédito; progresso assistido, percentuais e
  pertencimento a listas são consultados localmente no PostgreSQL.

### Content → Filme/Série, Temporada, Episódio

- A própria tela de detalhe do conteúdo é o uso mais direto: `GET /contents/{contentId}/details`
  single, com `type` `MOVIE`/`SERIES`/`SEASON`/`EPISODE` respectivamente — ver tabela "Quantas
  chamadas... dispara" acima pra saber quantas chamadas TMDB cada uma aciona.

## Quantas chamadas, em números, por tela

Notação: `c(MOVIE)=1`, `c(EPISODE)=2`, `c(SEASON)=2`, `c(SERIES)=1+N` (N = nº de temporadas da série,
exceto a 0 — variável, decidido pelo TMDB, não pelo backend). Os totais abaixo são **pior caso
teórico com cache frio** (cada série/temporada/filme pedido pela primeira vez, TTL expirado, nenhum
item repetido entre si) — na prática costuma ser bem menor, porque: (1) o cache é compartilhado entre
usuários e entre telas pra um mesmo `(tmdbId, language)`, TTL de `app.tmdb.details-cache-ttl-hours`;
(2) é comum vários itens de uma mesma lista serem da mesma série (maratona), aí a chamada de
`getTvFullDetails`/`getSeasonFullDetails` daquela série já sai do cache dentro do mesmo request.

| Tela | Itens de `Content` mostrados | Chamadas TMDB (pior caso, cache frio) |
|---|---|---|
| Perfil — Top 5, recentes e reviews | cards já incluídos na resposta | 0 chamadas de detalhe por card; metadata leve é resolvida no backend |
| History / Reviews | página atual de Diary | 0 chamadas de detalhe por card; metadata leve/stats são resolvidos após a paginação |
| Progress (séries em andamento) | todas as séries em progresso do usuário (sem cap) | usa o contrato próprio de progresso/snapshots; não exige `/contents/details` |
| Lista (detalhe) | todos os itens da lista (sem cap total — só bulk-add limitado a 100 por chamada) | `nº de itens × c(type)` |
| Month in Review (por aba) | rankings e cards da resposta | 0 chamadas de detalhe por card |
| Year in Review (por aba) | rankings e cards da resposta | 0 chamadas de detalhe por card |
| All Time Stats | rankings e cards da resposta | 0 chamadas de detalhe por card |
| Notas de Episódios de uma Série | grade resolvida pelo endpoint | usa metadata/snapshots próprios; 0 chamadas client-side ao TMDB |
| Home | `recentlyWatched`, `socialActivities`, `nextEpisodes` | 0 chamadas de detalhe por card |
| Watchlist | página visual selecionada | snapshots podem consultar TMDB; 0 chamadas de `/contents/details` |
| Calendário | snapshots do mês | snapshot pode consultar TMDB; 0 chamadas de `/contents/details` |
| TMDB Trending | página TMDB selecionada | 1 chamada por seção em cache frio + metadata leve da página |
| Feed | paginado (cursor), 20/página (padrão, máx. 50) | metadata leve somente após a seleção da página |
| Content → Filme | 1 (`MOVIE`) | 1 |
| Content → Série | 1 (`SERIES`) | `1+N` |
| Content → Temporada | 1 (`SEASON`) | 2 |
| Content → Episódio | 1 (`EPISODE`) | 2 |

`watchCountByDecade`/`watchCountByCountry` (All Time Stats) **não entram** nessa conta — são contados
a partir de `Content.releaseYear`/`Content.countries`, já salvos no banco, sem nenhuma chamada TMDB.

## Jobs em background (não amarrados à abertura de uma tela)

Esses dois jobs alimentam a tela de Notificações de forma assíncrona — quando o usuário abre
`GET /notifications`, nada disso é chamado na hora, só lê `Notification` já persistida.

- **`ContentTrackingJob`** (cron `app.content-tracking.cron`, diário) — pra cada `Content` distinto
  rastreado (tudo que está em alguma `WatchlistEntry` + toda série com diário em progresso), chama
  `getMovieDetails`/`getTvDetails` e gera `Notification` (`RELEASE`/`ANNOUNCED_DATE`/`CANCELLED`/
  `RENEWED`/`NEW_EPISODE`) quando detecta mudança.
- **`FollowedPersonTrackingJob`** (cron `app.followed-person-tracking.cron`, semanal) — pra cada
  pessoa do TMDB seguida por algum usuário, chama `getPersonCombinedCredits` e gera `Notification`
  (`FOLLOWED_PERSON_NEW_CREDIT`) quando aparece um crédito novo.

## Compatibilidade com os documentos antigos

As instruções antigas que diziam para montar todo card com `/contents/details` foram substituídas pelos
modelos de leitura específicos descritos acima. O detalhe completo continua correto para Content Detail
e para consumidores legados; não é uma exigência dos novos caminhos de Home, Profile, Diary, Summary,
Watchlist, Trending, Calendar ou Feed. Nenhuma tela deve chamar o TMDB diretamente, criar uma referência
`Content` apenas para obter metadata ou colocar estado de viewer dentro de um cache compartilhado.
