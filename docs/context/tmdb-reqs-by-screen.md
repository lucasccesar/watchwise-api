# Chamadas ao TMDB por tela

> Levantamento feito em 2026-08-31 direto do código (`TmdbClient`, `ContentDetailsServiceImpl`,
> `ContentTrackingServiceImpl`, `FollowedPersonTrackingServiceImpl`) e cruzado com as telas descritas
> em `telas.md`. Não é um documento de arquitetura nem de regra de negócio (isso continua em
> `business-rules.md`) — é só o mapa de "essa tela → esses dados → essas chamadas TMDB acontecem no
> backend".

## Como ler este documento

**O cliente nunca chama o TMDB direto.** A `api-key` do TMDB fica só no backend (ver CLAUDE.md,
decisão de 2026-08-30) — toda chamada ao TMDB listada aqui acontece dentro do Watchwise, nunca no
app/frontend. O cliente só tem dois jeitos de chegar em dado do TMDB:

1. **Sob demanda, ao abrir uma tela** — chamando `GET /contents/{contentId}/details` (um item) ou
   `GET /contents/details?ids=` (batch, até 100 ids por chamada) do próprio Watchwise. Esses dois
   endpoints são a **única** porta de entrada pra dado do TMDB no client-facing da API; por baixo,
   eles chamam `TmdbClient` de acordo com o `type` do `Content` pedido.
2. **De forma assíncrona, via `Notification`** — os dois jobs `@Scheduled` (`ContentTrackingJob`,
   `FollowedPersonTrackingJob`) chamam o TMDB em background e gravam `Notification` no banco. Quando
   o cliente abre a tela de Notificações, ele só lê `Notification` já salva — não dispara nenhuma
   chamada TMDB naquele momento.

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

- Top 5 de Filmes e Séries (até 5 itens, `MOVIE`/`SERIES`) → `GET /contents/details` batch
- *(aba Série)* Últimos 4 episódios assistidos → `EPISODE` → batch (cada `EPISODE` dispara
  `getEpisodeFullDetails` + `getTvFullDetails` da série)
- 6 séries/filmes recentes (completos ou dropped) → `MOVIE`/`SERIES` → batch
- Últimas 5 Reviews → `Content` do que foi avaliado (qualquer `type`) → batch

### Users → History / Reviews

- Cada `DiaryEntry` da página atual referencia um `Content` de `type` variável (`MOVIE`/`SERIES`/
  `SEASON`/`EPISODE`) → `GET /contents/details` batch pra montar título/pôster de cada linha.

### Users → Progress (séries em andamento)

- Cada série em `GET /users/{userId}/series-in-progress` → `GET /contents/{contentId}/details` (ou
  batch) do `Content` `type=SERIES` correspondente, pra título/pôster/`numberOfSeasons`/
  `numberOfEpisodes`/`seasons[].airedEpisodeCount`.
- `numberOfEpisodes`/`seasons[].airedEpisodeCount` (adicionados 2026-08-31) já vêm prontos nessa
  mesma chamada — o cliente ainda calcula o `%`/tempo faltante no lado dele (o backend não expõe
  isso pronto), mas não precisa mais ir atrás de cada temporada manualmente: um único
  `GET /contents/{contentId}/details` da SÉRIE já traz o suficiente.

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

- `recentWatched`/`topRated`/`bottomRated`/`topSeriesByWatchTime`/`topLongestMovies`/
  `longestWatched`/`mostLoggedContent` são todas listas de `Content` → `GET /contents/details` batch
  pra título/pôster/gênero de cada item.

### Users → Notas de Episódios de uma Série

- Precisa de `GET /contents/{contentId}/details` da `SERIES` (nome/pôster/temporadas), mas
  `GET /users/{userId}/series/{seriesTmdbId}/episode-ratings` é indexado por `seriesTmdbId` (id do
  TMDB), não pelo `Content.id` interno — o cliente precisa primeiro resolver o `Content.id` (ex.
  `POST /contents/reference` com esse `seriesTmdbId`, idempotente) antes de poder chamar `/details`.

### Home

- 4 últimas coisas assistidas + próximos episódios das séries em progresso (reaproveita
  `series-in-progress`) → `GET /contents/details` batch.
- Preview de 7 dias do calendário → ver ⚠️ **Calendário** abaixo.

### Calendário

- ⚠️ Essa é a única tela sem solução limpa hoje. Ela precisa de data de lançamento de episódios/
  filmes que o usuário **ainda não assistiu nem referenciou** — muitas vezes um `Content` que nunca
  foi criado no banco (o backend só cria `Content` sob demanda, via `POST /contents/reference` ou
  como efeito colateral de um diário). Sem um `Content.id`, não existe `GET /contents/{contentId}/details`
  pra chamar. `telas.md` marca isso como "trabalho do cliente via TMDB direto" — mas isso contradiz a
  regra atual de que o cliente nunca tem a `api-key` do TMDB (ver seção **Divergência com
  `telas.md`** abaixo). Não há hoje uma solução decidida — as opções seriam o cliente pré-criar a
  referência da próxima temporada via `POST /contents/reference` antes de precisar do calendário, ou
  um endpoint novo dedicado; nenhuma das duas foi implementada.

### Feed

- Post de `eventType=DIARY_ENTRY`/`DROPPED` referencia um `Content` → `GET /contents/details` batch.
- Post de `eventType=TOP5_UPDATE` é um evento genérico ("atualizou o Top 5 de {type}"), sem item
  específico — não dispara chamada TMDB nenhuma.

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
| Perfil — Top 5 Filmes + Séries | ≤10 (≤5 `MOVIE` + ≤5 `SERIES`) | até `5×1 + 5×(1+N)` |
| Perfil — últimos 4 episódios (aba Série) | =4 `EPISODE` | até `4×2=8` (cai pra `4+1=5` se todos da mesma série) |
| Perfil — 6 recentes (completos/dropped) | =6 (`MOVIE`/`SERIES`) | até `6×(1+N)` |
| Perfil — últimas 5 reviews | =5 (qualquer `type`) | até `5×(1+N)` |
| History / Reviews | paginado, 20/página (padrão `PageRequestFactory`, máx. 1000) | até `20×(1+N)` por página |
| Progress (séries em andamento) | todas as séries em progresso do usuário (sem cap) | `nº de séries × (1+N)` |
| Lista (detalhe) | todos os itens da lista (sem cap total — só bulk-add limitado a 100 por chamada) | `nº de itens × c(type)` |
| Month in Review (por aba) | até `6+6+6+3=21` | até `21×(1+N)` |
| Year in Review (por aba) | até `10+10+10=30` | até `30×(1+N)` |
| All Time Stats | até `10+10+10=30` | até `30×(1+N)` |
| Notas de Episódios de uma Série | 1 (`SERIES`) | `1+N` |
| Home | 4 (`recentlyWatched`) + 6 (`series-in-progress`, `size=6`) = 10 | até `10×(1+N)` |
| Calendário | ⚠️ não resolvido (ver seção acima) | — |
| Feed | paginado (cursor), 20/página (padrão, máx. 50) | até `20×(1+N)` por página |
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

## Divergência encontrada com `telas.md`

Várias telas em `telas.md` marcam informação do TMDB como 🚫 "trabalho do cliente via TMDB" — essa
redação é de antes da decisão de 2026-08-30 (documentada no `CLAUDE.md`, seção Architecture → "TMDB
detail proxy") de que **o cliente nunca chama o TMDB direto**, justamente pra `api-key` nunca ficar
embutida no app/frontend. Hoje, em toda tela onde `telas.md` diz "trabalho do cliente via TMDB", na
prática o dado passa por `GET /contents/{contentId}/details`/`GET /contents/details` — o backend é
quem chama o TMDB, o cliente só consome o endpoint interno.

A ressalva "trabalho do cliente" continua válida, só que pro que realmente sobra pro cliente:
cruzar/combinar esse dado (casar pôster com uma entrada de diário, filtrar/ordenar em memória,
comparar data de lançamento com a watchlist) — nunca a chamada de rede em si. A única exceção real,
onde falta mesmo uma solução no backend, é o **Calendário** (ver seção acima) — ali o problema não é
"quem faz a chamada", é que não existe `Content.id` pra itens ainda não referenciados. Vale atualizar
`telas.md` numa próxima passada pra não sugerir chamada TMDB direta do cliente em nenhum outro lugar.
