# Requisições por Tela

> Os modelos de leitura de tela entregam os cards no próprio endpoint da tela. O cliente nunca chama
> o TMDB diretamente e não deve transformar uma leitura em escrita. A montagem comum usa
> `ContentCardDTO`, com metadata básica, `previewStatus` (`AVAILABLE`, `PARTIAL` ou `UNAVAILABLE`) e
> facetas opcionais de `stats`, `viewerState` e estado de watchlist/progresso. `null` é um valor de
> contrato: não inventar título, pôster, runtime, horário ou estado ausente.

## Política de cards e compatibilidade

- Home, Profile, Diary History, Diary Reviews, Review, Month in Review, Year in Review, All Time Stats,
  Watchlist, Trending e Feed consomem o campo `card` ou o modelo de card específico retornado pelo
  próprio endpoint. A resposta textual/legada, `ContentRef`, paginação, contadores, likes, comentários,
  spoilers e relações de usuário continuam presentes.
- `GET /contents/details?ids=...` permanece disponível como mecanismo de compatibilidade para clientes
  antigos e como leitura de detalhe sob demanda. Ele não é um requisito de montagem de cards dessas telas.
  `GET /contents/{contentId}/details` continua sendo o contrato genérico de detalhe quando o usuário abre
  uma página completa de conteúdo; não deve ser chamado por card.
- `POST /contents/reference` é uma operação de criação/idempotência para ações que precisam persistir uma
  referência. Nenhum fluxo de leitura de card deve chamá-lo. Um card pode ter `contentId: null` quando a
  coordenada TMDB ainda não existe localmente.
- O cache compartilhado contém somente metadata externa sem estado de viewer. `viewerState` e posters
  customizados são resolvidos fora desse cache, no contexto do usuário apropriado. Calendário e datas da
  watchlist usam snapshots compartilhados separados, com chave de conteúdo/região/idioma, e podem fazer
  refresh read-through sem criar uma referência `Content`.

## Facetas de `ContentCardDTO`

| Faceta | Campos principais | Uso |
|---|---|---|
| `BASIC_METADATA` | identidade, título, poster, data/ano, runtime, gêneros, `previewStatus` | cards básicos |
| `STATS` | média, plays, reviews, comentários públicos | Diary, rankings e Watchlist |
| `VIEWER_STATE` | status, nota, última assistência, theater, plays | Trending e cards que mostram estado pessoal |
| `WATCHLIST_PROGRESS` | watchlist, dropped, episódios vistos/lançados | telas que mostram progresso de fila |
| `SOCIAL_METADATA` | poster customizado do dono/contexto | Profile, Home e Watchlist |

`ContentCardAssembler` deduplica coordenadas, faz as leituras em lote e mantém a linha mesmo quando o
TMDB está parcial ou indisponível. A tela deve usar `previewStatus` para decidir o placeholder sem remover
o item, e nunca preencher campos faltantes com valores inventados.

---

## Users

### Perfil (estilo Trakt V2)

**Watchwise API**
- `GET /users/{userId}` — dados base, `followersCount`/`followingCount` e relação do viewer
  (`SELF`, `FOLLOWING` ou `NOT_FOLLOWING`)
- `GET /users/{userId}/top5/{type}` — Top 5 com a nota mais recente do usuário quando existir
- `GET /users/{userId}/summary?type=MOVIE|SERIES` — `recentEpisodes` e `recentReviews` como prévias
  compactas sem texto, likes ou comentários; `recentActivity` com status, score e `timesWatched`;
  tempo e quantidade assistida; destaques; gêneros e ratings por unidades distintas, sem inflação por
  rewatch. Na aba Series, `recentReviews` combina eventos REVIEW de EPISODE e ContentReview de
  SERIES/SEASON; `source` informa qual tipo de ID cada prévia carrega.
- O próprio `GET /users/{userId}/summary` e `GET /users/{userId}/top5/{type}` já entregam `card` nos
  itens visuais. O backend resolve o poster customizado com o dono do perfil e mantém `content` como
  referência legada; não fazer batch de `/contents/details` para montar esses cards.

### Pôster customizado por usuário

O pôster customizado é um override canônico de **um usuário para um `Content`**, não um atributo da
entrada de diário, Top5 ou item de lista que o recebeu. `PUT /users/me/content-posters/{contentId}`
cria/substitui o override do principal autenticado; `DELETE` é idempotente. Os campos
`customPosterUrl` aceitos ao criar/editar diário, Top5 ou item de lista são atalhos para o mesmo
upsert, portanto alterar o pôster de um conteúdo reflete em todos os cards daquele usuário para o
mesmo conteúdo. O URL deve ter no máximo 2048 caracteres e ser exatamente um URL de pôster TMDB
`https://image.tmdb.org/t/p/w342/<arquivo>`: o sufixo é obrigatório, não pode ser vazio, só espaços,
ter espaços nas extremidades ou qualquer whitespace.

Em **contexto de perfil** — Top5, diário, listas, Summary, Progress e mapas de notas de
`/users/{userId}` — o backend resolve o override do dono do perfil (`userId`), depois de aplicar a
autorização/visibilidade do recurso. Em **contexto direto de conteúdo**,
`GET /contents/{contentId}/reviews` resolve cada card com o override do autor daquela review, nunca
com o override do viewer. A ausência do override devolve `customPosterUrl: null`; a interface então
usa o pôster do TMDB. Apagar uma entrada de diário, Top5 ou lista que originou um write não apaga o
override canônico; somente o `DELETE` próprio, ou a cascata ao apagar usuário ou conteúdo, o remove.

### History (diário sem review)

**Watchwise API**
- `GET /users/{userId}` (cabeçalho)
- `GET /users/{userId}/summary/home` (totais do cabeçalho, incluindo filmes, episódios, plays,
  minutos e `distinctSeriesWatched`)
- `GET /users/{userId}/diary/view` — fonte da tela unificada: linhas MOVIE/EPISODE persistidas e
  conclusões SERIES/SEASON derivadas de projeções atuais. Conclusões não têm `diaryEntryId`; usar
  `completionKey`/`kind` para a identidade visual. `GET /users/{userId}/diary` continua sendo a
  leitura raw de eventos MOVIE/EPISODE.
- `GET /users/{userId}/diary?type=&dateFrom=&dateTo=&hasReview=false&seriesTmdbId=&scoreFrom=&scoreTo=&sortBy=&page=&size=`
- `GET /users/{userId}/diary/series` — opções de série (`seriesTmdbId`, `title`, `entriesCount`)
- `GET /users/{userId}/diary/daily-summary?type=&dateFrom=&dateTo=&hasReview=false&seriesTmdbId=&scoreFrom=&scoreTo=` — plays e minutos agrupados por dia
- `GET /users/{userId}/diary` já devolve `card` opcional em cada entrada da página. Usar `card` para
  título, poster, runtime, data e stats; manter a entrada quando `previewStatus` for `PARTIAL` ou
  `UNAVAILABLE`. Não fazer batch de `/contents/details` para a listagem.

`dateFrom`/`dateTo` são inclusivos e substituem o filtro `year` para esta tela. O endpoint de
listagem mantém `year` por compatibilidade, mas a interface não precisa utilizá-lo. `sortBy` aceita
`NEWEST`, `OLDEST`, `RATING_DESC` e `RATING_ASC`; ordenação por título não é suportada pelo backend.

### Reviews (diário com review)

**Watchwise API**
- `GET /users/{userId}` (cabeçalho)
- `GET /users/{userId}/diary/view?hasReview=true&page=&size=` — inclui reviews de eventos MOVIE/EPISODE e
  reviews escritas de SERIES/SEASON correspondentes às conclusões atuais.
- `GET /users/{userId}/diary?hasReview=true&page=&size=` — somente reviews inline de eventos MOVIE/EPISODE.
- `GET /diary/{diaryEntryId}` — leitura direta da review ao abrir/recarregar o detalhe; devolve também
  `card` opcional com metadata canônica, poster customizado do dono e facetas do viewer autenticado
  quando autorizado.
- `GET /diary/{diaryEntryId}/comments` — listar comentários de uma review
- **Comentar em uma review** — `POST /diary/{diaryEntryId}/comments`
  ```json
  { "text": "comentário", "parentCommentId": null /* [opcional] */, "containsSpoiler": false /* [opcional] */ }
  ```
- Para uma review `CONTENT_REVIEW` (série/temporada), abrir `GET /reviews/{reviewId}` e usar
  `/reviews/{reviewId}/like` e `/reviews/{reviewId}/comments`; um ID de ContentReview não é um
  `diaryEntryId`. `GET /users/{userId}/diary/view?hasReview=true` traz a origem para escolher a rota.
- As prévias do summary retornam `card` opcional e `source`; usar `source` antes de encaminhar o ID.

### Progress (séries em andamento)

**Watchwise API**
- `GET /users/{userId}` (cabeçalho)
- `GET /users/{userId}/series-in-progress?page=&size=&sortBy=&direction=` — ordena no backend antes da
  paginação. `sortBy` aceita `LAST_WATCHED`, `LAST_RELEASED`, `REMAINING_EPISODES` e
  `REMAINING_RUNTIME`; `direction` aceita `ASC` e `DESC`, com desempate estável por `seriesTmdbId`.
  Retorna `watchedEpisodeCount`, `totalEpisodeCount`,
  `watchedPercentage` e `seasonProgress`. A contagem assistida usa pares distintos
  `(seasonNumber, episodeNumber)` somente em temporadas regulares (`seasonNumber > 0`),
  ignorando rewatches e Specials; o total soma os episódios regulares do TMDB com `air_date`
  válida até hoje, sem episódios futuros, sem data ou da temporada 0. `seasonProgress` detalha
  cada temporada regular lançada com sua contagem, total e percentual; o percentual é
  `min(100, watchedEpisodeCount * 100 / totalEpisodeCount)`, ou `null` sem total positivo.
  O percentual pode ser exibido pela interface, mas não é um valor de `sortBy`; runtime desconhecido
  permanece `null`. Cada item também pode trazer `customPosterUrl`, resolvido pelo dono do perfil; os
  campos de detalhe da série continuam sendo resolvidos pelo próprio endpoint ou pelos snapshots
  compartilhados descritos no contrato detalhado abaixo. Não fazer `/contents/details` para montar
  linhas de Progress.

Em uma chave fria do cache Caffeine de 24 horas, indisponibilidade do TMDB ao
buscar o total devolve `502`; a falha transitória não é cacheada. `totalEpisodeCount`
e `watchedPercentage` podem ser `null` quando não houver episódios regulares lançados com data válida.

### Contrato detalhado do Progress

`GET /users/{userId}/series-in-progress?page=&size=&sortBy=&direction=` retorna envelope paginado com
`content`, `aggregate`, `totalElements`, `totalPages` e `hasNext`. `sortBy` aceita `LAST_WATCHED`,
`LAST_RELEASED`, `REMAINING_EPISODES` e `REMAINING_RUNTIME`; `direction` aceita `ASC`/`DESC`, com
desempate estável por `seriesTmdbId`. O agregado inclui séries fora da página. Cada item expõe também
o último episódio assistido e runtime assistido/conhecido/restante. Snapshots frescos não chamam o TMDB;
  snapshot ausente ou stale é hidratado, falha sem snapshot válido retorna `502` e runtime incompleto fica
  `null`. Cada item também pode trazer `customPosterUrl`, resolvido pelo dono do perfil consultado;
  Specials e temporadas sem episódios lançados ficam fora de `seasonProgress`.

### Listas e Listas Curtidas

**Watchwise API**
- `GET /users/{userId}` (cabeçalho)
- `GET /users/{userId}/lists?sortBy=&sortDirection=&page=&size=&contentId=` — `contentId` é
  opcional; quando informado, cada lista da página vem com `containsContent` (`true`/`false`),
  resolvido em lote, pra tela de "adicionar a uma lista" já marcar de cara quais listas já contêm
  aquele conteúdo
- `GET /users/me/liked-lists?page=&size=`
- **Criar uma lista nova (vazia)** — `POST /users/me/lists`
  ```json
  {
    "name": "Melhores de 2026",
    "description": "Uma lista com os melhores títulos do ano", // [opcional]
    "visibility": "PUBLIC" // [opcional] — default PUBLIC se omitido
  }
  ```
  `name` é o único campo obrigatório. Sempre em nome do usuário autenticado — não existe
  `POST /users/{userId}/lists` pra criar em nome de terceiro.
- **Criar uma lista já populada com itens** — `POST /users/me/lists/bulk`, atalho que evita
  `POST /users/me/lists` seguido de N chamadas a `POST /lists/{listId}/items`
  ```json
  {
    "name": "Melhores de 2026",
    "description": "Uma lista com os melhores títulos do ano", // [opcional]
    "visibility": "PUBLIC", // [opcional] — default PUBLIC se omitido
    "items": [
      {
        "type": "MOVIE",
        "tmdbId": "550",
        "runtimeMinutes": 139, // [opcional]
        "genres": ["Drama"], // [opcional]
        "releaseYear": 1999, // [opcional]
        "countries": ["US", "DE"] // [opcional]
      },
      { "type": "SERIES", "tmdbId": "1396" }
    ]
  }
  ```
  `name` e `items` são obrigatórios. `items` é um array de `ContentRefCreation` puro (mesmo
  formato do `content` de "marcar como visto" em Filme/Série/Temporada/Episódio abaixo — cada
  entrada aceita os mesmos campos opcionais de metadado daquele tipo), entre 1 e 100 entradas,
  sempre inseridos na última posição livre, na ordem enviada; só aceita conteúdo
  (filme/série/temporada/episódio), nunca lista aninhada. Tudo ou nada — um item inválido ou
  duplicado reverte a lista inteira.
- `GET /contents/details?ids=<uuid,uuid,...>` (batch, máx 100 ids, mesma ordem) — detalhe dos
  `previewItems` de cada lista

### Lista (detalhe)

**Watchwise API**
- `GET /users/{userId}` (cabeçalho)
- `GET /lists/{listId}?type=&genre=&sortBy=position|dateAdded|duration|episodeAvgRating&sortDirection=`
- **Editar a lista** (nome/descrição/visibilidade/ordem entre as demais listas do dono) —
  `PATCH /lists/{listId}`
  ```json
  {
    "name": "Novo nome", // [opcional]
    "description": "Descrição atualizada", // [opcional]
    "visibility": "FOLLOWERS", // [opcional]
    "rank": 2 // [opcional]
  }
  ```
  Todos os campos são opcionais e independentes — só o enviado como não-`null` é alterado, o
  resto permanece como estava (mesmo padrão de `PATCH /users/me`). Só o dono pode editar.
- **Remover a lista** — `DELETE /lists/{listId}` — só o dono pode remover
- **Curtir / descurtir a lista** — `POST` / `DELETE /lists/{listId}/like` — só listas de conteúdo
  (não listas-de-listas) podem ser curtidas
- **Adicionar um item (conteúdo, ou outra lista aninhada)** — `POST /lists/{listId}/items` —
  mesmo endpoint usado pelas seções Filme/Série/Temporada/Episódio abaixo (com `content`
  preenchido); aqui, o shape genérico do corpo, incluindo a variante de lista aninhada que
  aquelas seções não cobrem
  ```json
  {
    "content": { "type": "MOVIE", "tmdbId": "550" }, // [opcional] — ver ContentRefCreation completo abaixo, em Filme/Série/Temporada/Episódio
    "childListId": "<uuid de outra lista do mesmo dono>", // [opcional]
    "position": null, // [opcional]
    "description": null, // [opcional]
    "customPosterUrl": null // [opcional]
  }
  ```
  Exatamente um entre `content` e `childListId` é obrigatório — nunca os dois juntos, nunca
  nenhum dos dois (`400`). `childListId` aninha outra lista do mesmo dono dentro desta (lista de
  listas), no máximo 1 nível de profundidade, sem misturar com item de conteúdo na mesma lista;
  uma lista-de-listas não aceita curtida/comentário nem `customPosterUrl` por item (só o item de
  conteúdo aceita). Para conteúdo, `customPosterUrl` faz upsert do override canônico do dono da lista
  para aquele `Content`, não um pôster isolado do item. `position`/`description`/`customPosterUrl` são
  opcionais nos dois casos (`customPosterUrl` sempre `null` se o item for lista aninhada).
- **Adicionar vários itens de conteúdo de uma vez** — `POST /lists/{listId}/items/bulk`
  ```json
  {
    "items": [
      {
        "type": "MOVIE",
        "tmdbId": "550",
        "runtimeMinutes": 139, // [opcional]
        "genres": ["Drama"], // [opcional]
        "releaseYear": 1999, // [opcional]
        "countries": ["US", "DE"] // [opcional]
      },
      { "type": "SERIES", "tmdbId": "1396" }
    ]
  }
  ```
  `items` é obrigatório. Mesmo formato de `items` do "Criar uma lista já populada com itens"
  acima — array de `ContentRefCreation`, entre 1 e 100 entradas, sempre inseridos na última
  posição livre (não aceita `position` explícito nem `description`/`customPosterUrl` por item —
  pra isso, use `PATCH /lists/{listId}/items/{itemId}` depois de inserido). Só aceita conteúdo,
  nunca lista aninhada; 400 se a lista já estiver travada como lista-de-listas.
- **Editar posição e/ou descrição de um item** — `PATCH /lists/{listId}/items/{itemId}`
  ```json
  {
    "position": 3, // [opcional]
    "description": "Minha nota sobre esse item", // [opcional]
    "customPosterUrl": null // [opcional]
  }
  ```
  `position`, `description` e `customPosterUrl` são independentes e opcionais — envie só o que
  quer alterar; `customPosterUrl` só é aceito se o item for de conteúdo (400 se o item for lista
  aninhada) e atualiza o override canônico do dono da lista para esse conteúdo.
- **Remover um item da lista** — `DELETE /lists/{listId}/items/{itemId}`
- `GET /lists/{listId}/comments` — listar comentários da lista
- **Comentar na lista** — `POST /lists/{listId}/comments`
  ```json
  { "text": "comentário", "parentCommentId": null /* [opcional] */, "containsSpoiler": false /* [opcional] */ }
  ```
  400 se a lista estiver travada como lista-de-listas — essas não aceitam comentário.
- `GET /contents/details?ids=<uuid,uuid,...>` (batch, máx 100 ids, mesma ordem) — detalhe de
  cada item da lista (inclui os itens de uma lista aninhada); `ContentDetailsDTO.title`/
  `releaseDate` já vêm de graça nessa mesma chamada, sem endpoint extra

🚫 **Ainda cliente, mesmo com o proxy**: "alfabética" e "data de lançamento"
como critério de **ordenação/busca** de `GET /lists/{listId}` continuam sem
suporte no backend — o endpoint de detalhe resolve o *dado* (título, data),
não a *ordenação/busca* da listagem em si, que é sobre `UserListItem`, sem
noção de conteúdo. Ordenar/filtrar por esses campos continua sendo o cliente
buscando os detalhes de cada item e reordenando em memória.

### Month in Review

**Watchwise API**
- `GET /users/{userId}` (cabeçalho)
- `GET /users/{userId}/summary/month?type=MOVIE|SERIES&month=YYYY-MM`
- `GET /users/{userId}/summary/month` já devolve `card` em `DiaryEntry`, em `topSeriesByWatchTime`
  e o bloco aditivo `topLongestMovieCards`; `topLongestMovies` permanece como referência legada.
- `topWatchCompanions` já vem pronto do backend (`WatchCompanionCountDTO` embute
  `UserPreviewDTO` do companheiro) — sem chamada extra, mesmo padrão de
  `FeedItem.user` na seção Feed

### Year in Review

**Watchwise API**
- `GET /users/{userId}` (cabeçalho)
- `GET /users/{userId}/summary/year?type=MOVIE|SERIES&year=YYYY`
- `GET /users/{userId}/summary/year` já devolve `card` nos itens visuais de `longestWatched`,
  `topRated` e `bottomRated`; nenhum batch de detalhe é necessário para a tela.
- `topWatchCompanions` já vem pronto do backend, sem chamada extra (mesmo
  motivo de Month in Review acima)

### All Time Stats

**Watchwise API**
- `GET /users/{userId}` (cabeçalho)
- `GET /users/{userId}/summary/all-time` (sem `type`, combina os dois e mantém os campos legados)
- `GET /users/{userId}/summary/all-time/edition?type=MOVIE|SERIES` — resposta específica da aba selecionada;
  a rota combinada legada permanece disponível com seus campos preservados e cards aditivos
- `GET /users/{userId}/summary/all-time/edition` já devolve `card` em `mostLoggedContent` e nos
  rankings baseados em `DiaryEntry`. O endpoint combinado legado também devolve cards aditivos em
  `mostLoggedContent`, `topRated` e `bottomRated`, preservando referências, totais, gráficos e
  ordenação legados.
- Ao trocar entre Movie Edition e TV Edition, o cliente chama novamente o endpoint `/edition` com o novo `type`.

### Notas de Episódios de uma Série (estilo SeriesGraph)

**Watchwise API**
- `GET /users/{userId}` (cabeçalho)
- `GET /users/{userId}/series/{seriesTmdbId}/episode-ratings` — retorna title,
  releaseYear, posterPath, backdropPath, customPosterUrl e uma entrada por coordenada
  de episódio regular já lançado, com score pessoal, averageScore público e ratingCount.
  Também retorna overallAverageScore e overallRatingCount da série.

O backend resolve metadata e coordenadas lançadas com TMDB/snapshots. A tela não precisa
fazer chamadas adicionais ao TMDB ou a `/contents/{contentId}/details` para preencher a grade.
`score` e `averageScore` permanecem null quando não há nota correspondente; a
contagem comunitária fica em zero. Perfil privado sem follow aceito retorna 403; usuário ou
série inexistente retorna 404; indisponibilidade do TMDB/snapshot necessário retorna 502.

---

### Episode Ratings Map (estilo SeriesGraph)

**Watchwise API**
- `GET /users/{userId}` (cabeçalho)
- `GET /users/{userId}/episode-ratings-map?page=1&size=12` — lista paginada das séries em que
  o usuário registrou pelo menos um episódio regular, incluindo séries concluídas. `page` é
  1-based e opcional (padrão 1); `size` é opcional (padrão 12, intervalo 1–100); valores fora
  dos limites retornam 400. Cada item inclui
  `seriesTmdbId`, `watchedEpisodeCount`, `totalEpisodeCount`, `title`, `releaseYear`,
  `posterPath` e `customPosterUrl`.

A contagem assistida considera pares distintos `(seasonNumber, episodeNumber)` e ignora
rewatches e Specials/temporada 0. O total vem dos snapshots compartilhados de progresso e
considera somente episódios regulares com `air_date` válida até hoje. A resposta inclui
`page`, `size`, `totalElements`, `totalPages` e `hasNext`, além de `totalWatchedEpisodeCount`
e `totalEpisodeCount` globais, calculados sobre a lista completa independentemente da página.
O backend resolve title/ano/pôster dos itens da página via TMDB; a tela não consulta o TMDB
diretamente. `totalEpisodeCount` pode ser null quando o snapshot não fornece o total, e metadata
pode ser null quando o dado correspondente não está disponível no TMDB/cache. Falha ao carregar
metadata ou snapshot necessário retorna 502; série inexistente retorna 404 e perfil privado sem
follow aceito retorna 403.

---

## Home

**Watchwise API**
- `GET /users/{userId}/summary/home` — totais históricos, quatro `nextEpisodes` enriquecidos,
  até seis `upcomingReleases` personalizados para o viewer, gráfico de 30 dias, gêneros por
  filme/episódio, quatro itens `recentlyWatched` compactos, `viewer`, `hasUnreadNotifications` e
  três `socialActivities`.
- `GET /users/me/calendar?month=YYYY-MM` — calendário mensal completo, usado quando o usuário abre
  a tela de calendário; a Home não precisa consultar esse endpoint para montar a seção Upcoming.

O cliente não precisa consultar o TMDB diretamente. Os dados do próximo episódio e dos cards recentes
que a Home usa são resolvidos pelo backend; o calendário já fornece título, pôster, data, status e origem.

`recentlyWatched[].card` e `socialActivities[].card` são opcionais e aditivos. A Home mantém a linha
quando o card está `PARTIAL` ou `UNAVAILABLE`, usando placeholder apenas para a parte visual ausente.

## Watchlist

**Watchwise API**

- `GET /users/{userId}/watchlist/view?type=&genre=&status=&sort=&direction=&page=&size=` é o contrato
  visual da tela. O filtro `status` aceita `ALL`, `NEW`, `IN_PROGRESS` e `UPCOMING`; `sort` aceita
  `DATE_ADDED`, `RELEASE_DATE`, `RUNTIME`, `RATING` e `TITLE`; `direction` assume `DESC`.
- Cada `content[]` traz `card`, `position`, `createdAt`, `releaseDate` e um status emitido entre
  `NEW`, `IN_PROGRESS` e `UPCOMING` — `ALL` só é um filtro e nunca aparece em uma linha. `aggregate`
  é calculado sobre todo o conjunto filtrado antes da paginação e contém contagens por tipo, runtime e
  upcoming; runtime ausente no `Content` é resolvido pela metadata canônica em lote, sem criar referência.
- `type`/`genre` são aplicados antes de status e ordenação. `TITLE`/`RATING` podem retornar `400` acima
  do limite configurado de candidatos materializados. O endpoint usa a visibilidade do dono e não
  chama `POST /contents/reference`, não chama `/contents/details` e não cacheia o envelope.

## TMDB Trending

**Watchwise API**

- `GET /trending?timeWindow=day|week&size=12|21` retorna arrays `movies` e `series` na ordem do
  TMDB, com `TrendingCard` e metadados de seção.
- `GET /trending/{type}?timeWindow=day|week&page=&size=` continua somente a seção escolhida;
  `page` é a página externa, 1-based e limitada a 500. `moviesPage` e `seriesPage` são continuações
  independentes.
- TMDB é a fonte do ranking. `popularity` e `tmdbVoteAverage` continuam identificados como métricas
  TMDB; não são estatísticas Watchwise e não representam atividade da comunidade. `viewerState` é
  opcional e nunca entra no cache compartilhado.

---

## Calendário

**Watchwise API**
- `GET /users/me/calendar?month=YYYY-MM` — calendário mensal do usuário autenticado; `month` é obrigatório e deve seguir exatamente `YYYY-MM`

O endpoint é de auto-visão: não recebe `userId`, usa `preferredRegion` e `preferredLanguage` do usuário autenticado e combina automaticamente:

- filmes (`MOVIE`) da watchlist;
- séries (`SERIES`) da watchlist;
- episódios de séries em andamento (séries que o usuário começou e ainda não concluiu).

Uma série presente nas duas fontes é deduplicada e o evento recebe as duas entradas em `sources` (`WATCHLIST` e `IN_PROGRESS`). O calendário é uma agenda de lançamentos, não uma lista de pendências: eventos já assistidos continuam no retorno e são identificados por `watchStatus`.

Cada item de `events` traz `date`, `eventType`, `releaseStatus`, `watchStatus`, `sources`, `content` e
o objeto opcional `releaseContext`:

- `MOVIE`: `tmdbId`, `title`, `posterPath`;
- `EPISODE`: `seriesTmdbId`, `seasonNumber`, `episodeNumber`, `title`, `seriesTitle`, `posterPath`, `stillPath`;
- `SEASON`: `seriesTmdbId`, `seasonNumber`, `episodeCount`, `title`, `posterPath`;
- `SERIES`: `seriesTmdbId`, `episodeCount`, `title`, `posterPath`.

`releaseStatus` é `RELEASED` ou `UPCOMING`; `watchStatus` é `WATCHED`, `UNWATCHED` ou `PARTIALLY_WATCHED`; `sources` pode conter `WATCHLIST`, `IN_PROGRESS` ou ambas. O cliente monta a grade mensal e decide se itens lançados/assistidos ficam visíveis, esmaecidos ou ocultos.

`releaseContext.releaseTime` só aparece quando o TMDB fornece um horário de filme parseável na região
preferida. Episódios e temporadas normalmente têm somente `air_date`, portanto não recebem horário
inventado. `releaseContext.network` só aparece quando a fonte de série fornece um nome não vazio e
unânime. Conflitos entre snapshots que formam um evento agrupado tornam o campo nulo.

Não existe parâmetro `granularity`: `eventType` e o conteúdo discriminado já informam a granularidade.
O backend agrupa em `SEASON`/`SERIES` apenas com snapshot completo e datas consensuais; caso contrário,
retorna eventos `EPISODE`. Snapshots são compartilhados por conteúdo/região/idioma, não por usuário;
um refresh read-through pode ocorrer sem criar `Content`.

O backend ignora itens sem data de lançamento e não inclui a temporada especial `0`. Quando possui evidência completa e todos os episódios compartilham a mesma data, pode devolver um evento agrupado de `SEASON` ou `SERIES`; caso contrário, devolve os episódios individualmente. A resposta já traz os dados de exibição do evento, portanto não exige `GET /contents/details` adicional.

Erros esperados: `400` para `month` ausente ou inválido, `401` sem autenticação e `502` quando o TMDB estiver indisponível e não houver snapshot válido (cache persistido) para responder.

---

## Social

### Feed de Atividades

**Watchwise API**
- `GET /feed?cursor=&size=` (paginação por cursor, não por página)
- **Curtir / descurtir um evento** — `POST` / `DELETE /diary/{id}/like` — em eventos
  `DIARY_ENTRY` (`DROPPED` usa `/dropped/{id}/like`; Pick e Template usam seus endpoints próprios);
  `TOP5_UPDATE` não tem curtida/comentário. `DIARY_ENTRY`/`DROPPED` trazem `commentsCount` e até 3
  `recentComments` no próprio item (`containsSpoiler` deve ser respeitado pelo cliente)
- `GET /diary/{id}/comments` — listar comentários de um evento `DIARY_ENTRY`
- **Comentar em um evento** — `POST /diary/{id}/comments`
  ```json
  { "text": "comentário", "parentCommentId": null /* [opcional] */, "containsSpoiler": false /* [opcional] */ }
  ```
- O próprio `GET /feed` devolve `FeedItemView`: mantém os campos legados e adiciona `card` para o
  conteúdo principal, `top5Preview.cards`, `pickTargetCards`, `followingCount` e
  `followedUsersPreview`. `DAILY_GAME_RESULT` continua sem card de conteúdo.
- O cursor é calculado depois de mesclar e selecionar a página final, antes de likes, comentários,
  previews sociais ou cards. Candidatos descartados não acionam resolução de card; coordenadas
  repetidas na página são resolvidas uma vez e placeholders nulos preservam posições aninhadas.

---

## Content

Os quatro tipos (`MOVIE`/`SERIES`/`SEASON`/`EPISODE`) seguem o mesmo *padrão*
de tela (detalhe TMDB, estatísticas, marcar como visto, adicionar à lista,
reviews, comentários), mas cada ação bate em endpoints/bodies diferentes
dependendo do tipo — por isso cada um está listado à parte abaixo, com o
método HTTP e o body de toda chamada que não é `GET`. "Reviews" não tem
endpoint de criação próprio: postar uma review é o mesmo `POST /diary` (ou
`POST /diary/bulk`) usado por "marcar como visto", só com `comment`
preenchido — não existe `Rating`/review como entidade separada.

⚠️ **`contentId` não existe até uma primeira referência.** É o UUID interno
de `Content`, nunca o `tmdbId` do TMDB — só existe depois que *alguém*
(não necessariamente o usuário atual) referenciou esse filme/série/
temporada/episódio pelo menos uma vez, seja via `POST /contents/reference`
seja como efeito colateral de `POST /diary`/`POST /lists/{listId}/items`/etc.
Chegando de um card já existente dentro do Watchwise (diário, lista, Top5,
watchlist, feed, ranking de summary) o `contentId` já vem pronto no próprio
item, e o fluxo de cada tipo abaixo começa direto no "Padrão de detalhe".
Chegando de uma busca/descoberta direto no TMDB — cliente só tem `tmdbId`
(ou `seriesTmdbId`+`seasonNumber`[+`episodeNumber`]), nunca um `contentId` —
é preciso chamar `POST /contents/reference` **primeiro**, só com os campos
identificadores (`type`+`tmdbId`, ou `seriesTmdbId`+`seasonNumber`
[+`episodeNumber`]), **sem** `runtimeMinutes`/`genres`/`releaseYear`/
`countries` ainda: nesse ponto o cliente não tem de onde tirar esses
metadados — só existem depois de `GET /contents/{contentId}/details`, que
por sua vez só pode ser chamado depois que esse `POST /contents/reference`
devolver o `contentId`. Preencher os metadados fica pra depois, seja na
própria ação que o usuário tomar em seguida (`POST /diary`/
`POST /lists/{listId}/items`, já depois de ter visto o detalhe), seja
reenviando `POST /contents/reference` de novo (idempotente, aceita
preencher um campo que ainda está `null` — só rejeita com `409` um valor
diferente de algo já registrado, ver `ContentServiceImpl.reconcileExisting`/
`assertNoMetadataMismatch`). Essa primeira chamada não é opcional nesse
cenário — sem ela não existe `contentId` nenhum pra usar; ela só é
dispensável quando o `contentId` já chegou pronto de um card existente.

### Página agregada de conteúdo

**Uma chamada autenticada na Watchwise API**

`GET /contents/{contentId}/page` é o BFF (backend que compõe a resposta da tela) para o detalhe
direto de filme, série, temporada ou episódio. A chamada exige a sessão autenticada, não exige
CSRF por ser `GET` e devolve `details`, `metadata`, `stats`, `visibleReviewsCount`, `viewerState`,
`navigation` e `sections`. `details` e os stats genéricos mantêm os contratos existentes; `metadata`
e `stats` acrescentam somente os campos próprios da página, incluindo certificação/URLs/providers,
`presentationPosterPath`, `presentationCrew`, `crewInherited`, `ratingsCount` e a distribuição de
ratings de 1 a 10.

As seções dependem do tipo raiz:

- `MOVIE`: `sections.seasons`, `sections.episodes` e `sections.recentEpisodes` vazios; `navigation` nula.
- `SERIES`: `sections.seasons` e `sections.recentEpisodes` preenchidos a partir do detalhe; `sections.episodes` vazia.
- `SEASON`: `sections.episodes` preenchida; `sections.seasons` só aparece quando a série pai já tem
  referência local; `sections.recentEpisodes` fica vazia e não há navegação de episódio.
- `EPISODE`: as três seções ficam vazias; `navigation` traz anterior/próximo somente quando a
  temporada pai tem referência local suficiente para resolver os limites.

Cards de filhos são montados por coordenada TMDB. Quando uma coordenada ainda não foi referenciada
no banco, o card mantém `contentId: null`, mas pode trazer título, poster, data e runtime; seu
`viewerState` fica vazio e suas estatísticas ficam zeradas. A leitura não cria referências para
filhos ou pais ausentes e não faz uma chamada TMDB por filho; se a referência parent não existir,
a página raiz continua retornando `200` com as seções/navegação vazias ou nulas.

`viewerState` é do usuário autenticado. Para MOVIE/EPISODE, nota, review, data e `latestDiaryEntryId`
vêm do evento mais recente. Para SERIES/SEASON, nota atual e reviews vêm de ContentRating/ContentReview;
data e passada vêm da conclusão derivada mais recente. `latestDiaryEntryId`, quando preenchido nesse
caso, aponta para o evento EPISODE âncora — nunca é uma DiaryEntry de série/temporada nem um alvo para
editar a nota ou apagar a conclusão. `playsCount` inclui passadas completas. O estado também traz
membership direto em watchlist, dropped e listas. Para séries e temporadas, `watchStatus` pode ser
`PARTIALLY_WATCHED`, com `watchedEpisodeCount` e `releasedEpisodeCount` baseados em coordenadas
distintas de episódios lançados; rewatches e Specials não aumentam a contagem.

Reviews continuam em `GET /contents/{contentId}/reviews?page=&size=&sort=`. O padrão é `RECENT`;
`POPULAR` ordena por likes e recência, e `FOLLOWING` mantém somente autores seguidos com relação
`ACCEPTED`. O resultado pode misturar reviews de diário e dropped, inclui `reviewer` compacto e
usa o pôster customizado do autor da review. A visibilidade é por autor: perfil público, próprio
viewer ou perfil privado seguido com `ACCEPTED`.

Erros da página e das reviews usam `ApiError`: `400` para UUID, enum ou paginação inválidos,
`401` sem autenticação, `404` para conteúdo/usuário inexistente e `502` quando o TMDB fica
indisponível durante a composição.

### Filme

**Watchwise API**
- Página completa — `GET /contents/{contentId}/page`; o cliente usa `details`, `metadata`, `stats` e
  `viewerState` da resposta agregada para a tela de filme.
- **(Só se ainda não tem `contentId` — chegando de busca/descoberta no TMDB, não de um card
  já existente)** `POST /contents/reference` primeiro — `{ "type": "MOVIE", "tmdbId": "550" }`,
  **sem** `runtimeMinutes`/`genres`/`releaseYear`/`countries` ainda: nesse ponto o cliente só tem o
  `tmdbId`, esses metadados só existem depois do padrão de detalhe abaixo (ver nota no início
  desta seção). Devolve o `contentId`. Preencher os metadados fica pra "Marcar como visto" mais
  abaixo, que só acontece depois de o usuário já ter visto essa tela — a mesma chamada idempotente
  também aceita ser refeita de novo, isolada, se quiser registrar os metadados antes de qualquer
  ação (mesmo padrão da nota de backfill em Série)
- Padrão de detalhe — `GET /contents/{contentId}/details`
- `GET /contents/{contentId}/stats` — média de nota, plays, reviews, comentários
- **Marcar como visto / avaliar / revisar** — `POST /diary`
  ```json
  {
    "content": {
      "type": "MOVIE",
      "tmdbId": "550"
    },
    "score": 9, // [opcional]
    "comment": "texto da review", // [opcional]
    "watchedDate": "2026-09-01", // [opcional]
    "watchedInTheater": false, // [opcional]
    "isRewatch": false, // [opcional]
    "customPosterUrl": null, // [opcional]
    "watchedWith": ["<uuid do amigo>"] // [opcional]
  }
  ```
  `content.type`/`content.tmdbId` são os únicos campos obrigatórios do corpo inteiro.
  Metadados do filme (`runtimeMinutes`, `genres`, `releaseYear`, `countries`)
  são derivados pelo backend a partir do TMDB e não devem ser enviados no
  request. `watchedDate` no futuro
  é `400` (desde 2026-09-02, validado no servidor); antes da data de
  lançamento do filme continua só responsabilidade do cliente — o backend não
  valida isso aqui (só no bulk de SEASON/SERIES, ver Série/Temporada abaixo).
- **Editar o registro** (nota/review/data já existentes) — `PATCH /diary/{diaryEntryId}`
  ```json
  {
    "comment": "texto atualizado", // [opcional]
    "score": 8, // [opcional]
    "watchedDate": "2026-09-01", // [opcional]
    "watchedInTheater": true, // [opcional]
    "customPosterUrl": null, // [opcional]
    "watchedWith": ["<uuid do amigo>"] // [opcional]
  }
  ```
  Todos os campos são opcionais e independentes. Um campo `null`/ausente significa "não mexe" — exceto `watchedWith`, onde
  `null` também é "não mexe" mas uma lista (mesmo vazia, `[]`) substitui os
  acompanhantes por completo. `content` é imutável depois de criado, não tem
  como editar aqui.
- **Remover o evento** — `DELETE /diary/{diaryEntryId}` apaga apenas esse evento MOVIE; não há
  prévia de cascata nem remoção de eventos irmãos.
- **Adicionar à lista** — `GET /users/{userId}/lists?contentId=` do próprio usuário autenticado
  (escolher a lista, já marcada via `containsContent` se já contém este conteúdo) então
  `POST /lists/{listId}/items`
  ```json
  {
    "content": {
      "type": "MOVIE",
      "tmdbId": "550",
      "runtimeMinutes": 139, // [opcional]
      "genres": ["Drama"], // [opcional]
      "releaseYear": 1999, // [opcional]
      "countries": ["US", "DE"] // [opcional]
    },
    "position": null, // [opcional]
    "description": null, // [opcional]
    "customPosterUrl": null // [opcional]
  }
  ```
  Mesmo `content` de "marcar como visto" acima — se esse filme ainda não
  tinha sido referenciado por ninguém, essa é a chance de já preencher
  `runtimeMinutes`/`genres`/`releaseYear`/`countries` também por aqui.
- **Remover da lista** — `DELETE /lists/{listId}/items/{itemId}`
- **Adicionar à watchlist** — `POST /users/me/watchlist/MOVIE`
  ```json
  { "tmdbId": "550" }
  ```
  Insere sempre no fim da watchlist de filmes do usuário autenticado; `409` se esse filme já
  estiver lá.
- **Remover da watchlist** — `DELETE /users/me/watchlist/MOVIE/{watchlistEntryId}`
- **Marcar como dropped** — `POST /users/me/dropped/MOVIE/{tmdbId}`
  ```json
  { "comment": "Não me prendeu" } // [opcional]
  ```
  Idempotente; remove (se existir) esse filme da watchlist como efeito colateral.
- **Desmarcar como dropped** — `DELETE /users/me/dropped/MOVIE/{tmdbId}`
- `GET /contents/{contentId}/reviews?page=&size=` — reviews de todos os usuários; cada resposta usa o
  `customPosterUrl` do autor da review para esse conteúdo, nunca o do viewer
  - **Curtir / descurtir uma review** — `POST` / `DELETE /diary/{diaryEntryId}/like`
  - **Comentar em uma review** — `GET /diary/{diaryEntryId}/comments` /
    `POST /diary/{diaryEntryId}/comments`
    ```json
    { "text": "comentário", "parentCommentId": null /* [opcional] */, "containsSpoiler": false /* [opcional] */ }
    ```
- `GET /contents/{contentId}/comments` — listar comentários do filme (fora de uma review)
- **Comentar no filme (fora de uma review)** — `POST /contents/{contentId}/comments`
  ```json
  { "text": "comentário", "parentCommentId": null /* [opcional] */, "containsSpoiler": false /* [opcional] */ }
  ```
  - **Curtir / descurtir um comentário** — `POST` / `DELETE /comments/{commentId}/like`
  - **Remover um comentário próprio** — `DELETE /comments/{commentId}`

### Série

**Watchwise API**
- Página completa — `GET /contents/{contentId}/page`; `sections.seasons` lista temporadas e
  `sections.recentEpisodes` lista até os episódios recentes compostos pelo backend.
- **(Só se ainda não tem `contentId` — chegando de busca/descoberta no TMDB, não de um card
  já existente)** `POST /contents/reference` primeiro — `{ "type": "SERIES", "tmdbId": "1396" }`,
  **sem** `genres`/`releaseYear`/`countries` ainda: nesse ponto o cliente só tem o `tmdbId`, esses
  metadados só existem depois do padrão de detalhe abaixo (ver nota no início da seção Content).
  Devolve o `contentId`.
- Padrão de detalhe — `GET /contents/{contentId}/details` — inclui `totalRuntimeMinutes`
  e `recentEpisodes` (últimos 3 episódios lançados), só pra `SERIES`
- `GET /contents/{contentId}/stats`
- **Marcar como visto (série inteira, todas as temporadas)** — `POST /diary/bulk`
  ```json
  {
    "content": {
      "type": "SERIES",
      "tmdbId": "1396"
    },
    "watchedDate": "2026-09-01", // [opcional]
    "finaleSeasonNumber": 5, // [opcional] — override; sem isso o backend deriva do TMDB
    "seasonFinaleEpisodeNumbers": { "1": 7, "2": 13, "3": 13, "4": 13 }, // [opcional] — override por temporada; sem isso o backend deriva do TMDB
    "watchedWith": ["<uuid do amigo>"] // [opcional]
  }
  ```
  **Chamadas automáticas ao TMDB por requisição** (todas cacheadas 24h, então repetir a mesma
  série/temporada logo em seguida não paga de novo): até **1** `getTvFullDetails` real (mesma
  chave `seriesTmdbId`+idioma reaproveitada entre `resolveSeriesFinaleSeasonNumber` — só quando
  necessário — e a checagem do teto de 2000 — só se excedido —, então até 2 pontos do código chamam,
  mas no máximo 1
  vira requisição HTTP real) **+ 1 `getSeasonFullDetails` por temporada** da série (sempre, sequencial,
  não paralelo — uma série de 5 temporadas soma até 6 chamadas reais no total, 1 + 5).
  `content.type`/`content.tmdbId` são os únicos campos aceitos do `content`
  aqui. O cliente **não precisa** informar `finaleSeasonNumber`/`seasonFinaleEpisodeNumbers`
  nem `runtimeMinutes` por episódio — desde 2026-09-02 o backend deriva tudo
  sozinho no TMDB quando omitido e ainda não existe finale conhecido no banco:
  `finaleSeasonNumber` vira a última temporada com episódio já lançado (uma
  chamada nova a `getTvFullDetails`), `seasonFinaleEpisodeNumbers` por
  temporada vira a contagem de episódios lançados daquela temporada, e
  `runtimeMinutes` de cada episódio vem junto (reaproveitando a mesma busca de
  temporada, sem custo extra) — tudo alimentando `totalMinutesWatched`/etc.
  automaticamente. **Desde 2026-09-03, o mesmo vale pra `genres`/`releaseYear`/`countries`** — o
  cliente não precisa (e não consegue) mandar esses três campos aqui, o backend deriva sozinho do
  TMDB (`getTvFullDetails`, checando antes se a série já tem esses campos preenchidos, pra nunca
  chamar o TMDB mais de uma vez pela mesma série) e alimenta `genreCounts`/stats por década/país
  sozinho — não é mais necessário chamar `POST /contents/reference` à parte pra isso. Diferente do
  `finaleSeasonNumber`/`runtimeMinutes` (que fazem o bulk inteiro falhar com `502` se o TMDB estiver
  indisponível), essa derivação de `genres`/`releaseYear`/`countries` é best-effort — falha
  silenciosamente sem afetar o log das entradas de diário. Se o TMDB estiver indisponível na busca
  de finale/runtime, o bulk inteiro falha com `502`; se nem o TMDB tiver nada lançado
  ainda (série anunciada mas não estreada), continua `400`.
  `watchedDate` no futuro, ou anterior ao lançamento do último episódio da
  última temporada logada, é `400` (desde 2026-09-02, checado no servidor
  contra o mesmo dado do TMDB acima). Teto flexível de 2000 episódios no total
  (`MAX_BULK_EPISODES`, desde 2026-09-03) — acima disso, se a contagem pedida
  não bater com `number_of_episodes` do TMDB (chamada extra cacheada), `400`
  em vez de prosseguir; bate ou fica abaixo, sem teto superior algum (cobre
  séries genuinamente longas).
- A conclusão SERIES exibida por `/diary/view` é derivada e não tem `diaryEntryId`; não ofereça
  `PATCH` nem `DELETE` diretamente para essa linha. A nota atual usa `PUT /contents/{contentId}/my-rating`;
  reviews escritas usam `POST /contents/{contentId}/reviews` e, para editar/remover/interagir,
  `/reviews/{reviewId}` e seus sub-recursos. Para alterar/remover uma visualização específica,
  navegue até o evento EPISODE real no histórico.
- **Apagar todo o histórico de diário da série (todos os watchNumber, todas
  as passadas)** — `DELETE /diary/series/{seriesTmdbId}`
- **Adicionar à lista** — `GET /users/{userId}/lists?contentId=` do próprio usuário autenticado
  (já marcada via `containsContent` se já contém este conteúdo) + `POST /lists/{listId}/items`
  ```json
  {
    "content": {
      "type": "SERIES",
      "tmdbId": "1396",
      "genres": ["Crime", "Drama", "Thriller"], // [opcional]
      "releaseYear": 2008, // [opcional]
      "countries": ["US"] // [opcional]
    },
    "position": null, // [opcional]
    "description": null, // [opcional]
    "customPosterUrl": null // [opcional]
  }
  ```
- **Remover da lista** — `DELETE /lists/{listId}/items/{itemId}`
- **Adicionar à watchlist** — `POST /users/me/watchlist/SERIES`
  ```json
  { "tmdbId": "1396" }
  ```
  Insere sempre no fim da watchlist de séries do usuário autenticado; `409` se essa série já
  estiver lá.
- **Remover da watchlist** — `DELETE /users/me/watchlist/SERIES/{watchlistEntryId}`
- **Marcar como dropped** — `POST /users/me/dropped/SERIES/{tmdbId}`
  ```json
  { "comment": "Perdi o interesse na 3ª temporada" } // [opcional]
  ```
  Idempotente; remove (se existir) essa série da watchlist como efeito colateral.
- **Desmarcar como dropped** — `DELETE /users/me/dropped/SERIES/{tmdbId}`
- `GET /contents/{contentId}/reviews?page=&size=`
  - **Curtir / descurtir uma review escrita de série** — `POST` / `DELETE /reviews/{reviewId}/like`
  - **Comentar em uma review escrita de série** — `GET /reviews/{reviewId}/comments` /
    `POST /reviews/{reviewId}/comments`
    ```json
    { "text": "comentário", "parentCommentId": null /* [opcional] */, "containsSpoiler": false /* [opcional] */ }
    ```
- `GET /contents/{contentId}/comments` — listar comentários da série (fora de uma review)
- **Comentar na série** — `POST /contents/{contentId}/comments`
  ```json
  { "text": "comentário", "parentCommentId": null /* [opcional] */, "containsSpoiler": false /* [opcional] */ }
  ```
  - **Curtir / descurtir um comentário** — `POST` / `DELETE /comments/{commentId}/like`
  - **Remover um comentário próprio** — `DELETE /comments/{commentId}`

### Temporada

**Watchwise API**
- Página completa — `GET /contents/{contentId}/page`; `sections.episodes` traz os cartões de
  episódios e `sections.seasons` só é preenchida quando a série pai está referenciada localmente.
- **(Só se ainda não tem `contentId` — chegando de busca/descoberta no TMDB, não de um card
  já existente)** `POST /contents/reference` primeiro — `{ "type": "SEASON", "seriesTmdbId":
  "1396", "seasonNumber": 5 }` (`SEASON` nunca aceita `genres`/`releaseYear`/`countries`/
  `runtimeMinutes`, então não há metadado TMDB pra esperar aqui); `isSeriesFinale` só entra se o
  cliente já souber nesse ponto (ex. vindo da tela de Série, que já viu o detalhe da série) — ver
  nota no início da seção Content
- Padrão de detalhe — `GET /contents/{contentId}/details` — lista de `episodes`
  (numeração, nome, data, `runtime`) vem daqui
- `GET /contents/{contentId}/stats`
- `GET /contents/stats?ids=<uuid dos episódios>` — stats em batch de todos os
  episódios da temporada (evita N chamadas a `/contents/{contentId}/stats`)
- `GET /contents/details?ids=<uuid dos episódios>` — pôster/sinopse por
  episódio na lista, só dos episódios já logados por alguém (episódio nunca
  logado não tem `contentId`, mesma limitação de sempre)
- **Marcar como visto (temporada inteira)** — `POST /diary/bulk`
  ```json
  {
    "content": {
      "type": "SEASON",
      "seriesTmdbId": "1396",
      "seasonNumber": 5,
      "isSeriesFinale": true // [opcional]
    },
    "watchedDate": "2026-09-01", // [opcional]
    "finaleEpisodeNumber": 13, // [opcional] — override; sem isso o backend deriva do TMDB
    "episodeRuntimeMinutes": { "1": 42, "2": 55, "3": 48 }, // [opcional] — mapa episodeNumber → runtimeMinutes
    "watchedWith": ["<uuid do amigo>"] // [opcional]
  }
  ```
  **Chamadas automáticas ao TMDB por requisição**: sempre exatamente **1** `getSeasonFullDetails`
  (cacheada 24h) — busca a própria temporada, incondicional, mesmo se `finaleEpisodeNumber` vier
  explícito (precisa pra validar `watchedDate` contra a data de lançamento do último episódio, ver
  abaixo). Nunca chama `getTvFullDetails` — diferente do bulk de Série, `SEASON` não deriva
  `genres`/`releaseYear`/`countries` nenhum (não existem pra esse tipo).
  `content.type`/`content.seriesTmdbId`/`content.seasonNumber` são os únicos campos
  obrigatórios do corpo inteiro. `content.isSeriesFinale` é opcional — `true` se essa é a última
  temporada da série (o cliente sabe pelo próprio detalhe da série); sem isso a completude
  automática de série não detecta o fim (ver `isSeasonFinale`/`isSeriesFinale` em
  `ContentRefCreation`). `SEASON` não aceita `runtimeMinutes`/`genres`/`releaseYear`/`countries`
  no `content` — uma temporada não tem duração/gênero/país próprios no TMDB, só a `SERIES` e os
  `EPISODE` individuais têm; `runtimeMinutes` por episódio entra pelo `episodeRuntimeMinutes` de
  nível superior, não pelo `content`. Diferente do bulk de Série (abaixo), aqui o cliente informa
  o `runtimeMinutes` manualmente — o backend **não** busca isso no TMDB pra `SEASON` (a tela de
  temporada já teria buscado `GET /contents/{contentId}/details` antes, que já traz o `runtime` de
  cada episódio em `episodes[]`); episódio ausente do mapa simplesmente não recebe
  `runtimeMinutes` (mesmo efeito de omitir o campo em `POST /diary`), e um valor divergente de um
  já registrado antes dá `409`, igual ao log individual. O cliente **não precisa** informar
  `finaleEpisodeNumber` — se omitido e ainda não existe finale conhecido no banco, o backend
  deriva sozinho do TMDB (mesma regra de Série acima) — mas essa é a única coisa que o backend
  ainda busca no TMDB aqui: só pra derivar o finale e validar `watchedDate` contra a data de
  lançamento do último episódio, nunca pro `runtimeMinutes`. Se o TMDB estiver indisponível nessa
  busca, o bulk inteiro falha com `502` mesmo assim; se nem o TMDB tiver nenhum episódio lançado
  ainda, continua `400`. `watchedDate` no futuro, ou anterior ao lançamento do último episódio
  logado (`finaleEpisodeNumber`), é `400` (desde 2026-09-02, checado no servidor contra o mesmo
  dado do TMDB acima). Mesmo teto flexível de 2000 episódios de Série acima (`MAX_BULK_EPISODES`),
  verificado aqui contra `TmdbSeasonFullDetails.episodes().size()` em vez de `getTvFullDetails`.
- A conclusão SEASON mostrada em `/diary/view` é derivada e não tem `diaryEntryId`; não oferecer
  `PATCH`/`DELETE` de DiaryEntry para essa linha. A nota atual usa `PUT /contents/{contentId}/my-rating`;
  reviews escritas usam `POST /contents/{contentId}/reviews`, com interações em `/reviews/{reviewId}`.
  Para apagar uma visualização específica, remova o DiaryEntry do episódio real no histórico.
- **Adicionar à lista** — `GET /users/{userId}/lists?contentId=` do próprio usuário autenticado
  (já marcada via `containsContent` se já contém este conteúdo) + `POST /lists/{listId}/items`
  ```json
  {
    "content": {
      "type": "SEASON",
      "seriesTmdbId": "1396",
      "seasonNumber": 5,
      "isSeriesFinale": true // [opcional]
    },
    "position": null, // [opcional]
    "description": null, // [opcional]
    "customPosterUrl": null // [opcional]
  }
  ```
- **Remover da lista** — `DELETE /lists/{listId}/items/{itemId}`
- `GET /contents/{contentId}/reviews?page=&size=`
  - **Curtir / descurtir uma review escrita de temporada** — `POST` / `DELETE /reviews/{reviewId}/like`
  - **Comentar em uma review escrita de temporada** — `GET /reviews/{reviewId}/comments` /
    `POST /reviews/{reviewId}/comments`
    ```json
    { "text": "comentário", "parentCommentId": null /* [opcional] */, "containsSpoiler": false /* [opcional] */ }
    ```
- `GET /contents/{contentId}/comments` — listar comentários da temporada (fora de uma review)
- **Comentar na temporada** — `POST /contents/{contentId}/comments`
  ```json
  { "text": "comentário", "parentCommentId": null /* [opcional] */, "containsSpoiler": false /* [opcional] */ }
  ```
  - **Curtir / descurtir um comentário** — `POST` / `DELETE /comments/{commentId}/like`
  - **Remover um comentário próprio** — `DELETE /comments/{commentId}`

### Episódio

**Watchwise API**
- Página completa — `GET /contents/{contentId}/page`; `navigation` traz os cartões anterior/próximo
  quando a temporada pai está disponível, enquanto `details` preserva o poster/still genérico.
- **(Só se ainda não tem `contentId` — chegando de busca/descoberta no TMDB, não de um card
  já existente)** `POST /contents/reference` primeiro — `{ "type": "EPISODE", "seriesTmdbId":
  "1396", "seasonNumber": 5, "episodeNumber": 16 }` (`EPISODE` nunca aceita
  `genres`/`releaseYear`/`countries`); `runtimeMinutes`/`isSeasonFinale`/`isSeriesFinale` só
  entram se o cliente já souber nesse ponto (ex. vindo da tela de Temporada, que já viu
  `episodes[].runtime` no detalhe da temporada) — sem isso, ficam pra "Marcar como visto" abaixo,
  que já roda depois do padrão de detalhe — ver nota no início da seção Content
- Padrão de detalhe — `GET /contents/{contentId}/details` — inclui
  `guestStars`, além do elenco regular herdado da série
- `GET /contents/{contentId}/stats`
- **Marcar como visto** — `POST /diary`
  ```json
  {
    "content": {
      "type": "EPISODE",
      "seriesTmdbId": "1396",
      "seasonNumber": 5,
      "episodeNumber": 16,
      "isSeasonFinale": true, // [opcional]
      "isSeriesFinale": true // [opcional]
    },
    "score": 10, // [opcional]
    "comment": "texto da review", // [opcional]
    "watchedDate": "2026-09-01", // [opcional]
    "isRewatch": false, // [opcional]
    "customPosterUrl": null, // [opcional]
    "watchedWith": ["<uuid do amigo>"] // [opcional]
  }
  ```
  `content.type`/`content.seriesTmdbId`/`content.seasonNumber`/`content.episodeNumber` são os
  únicos campos obrigatórios do corpo inteiro. `isSeasonFinale`/`isSeriesFinale` são opcionais e
  funcionam como fallback quando o TMDB ainda não tem dados de episódios/temporadas lançados; quando
  o TMDB sabe responder, o backend deriva essas flags. `runtimeMinutes` é derivado pelo backend e não
  deve ser enviado. `isRewatch` é ignorado para episódios. Não existe `watchedInTheater` aqui — só é aceito pra
  `Content` do tipo `MOVIE`.
- **Editar o registro** (nota/review/data) — `PATCH /diary/{diaryEntryId}`
  ```json
  {
    "comment": "texto atualizado", // [opcional]
    "score": 8, // [opcional]
    "watchedDate": "2026-09-01", // [opcional]
    "customPosterUrl": null, // [opcional]
    "watchedWith": ["<uuid do amigo>"] // [opcional]
  }
  ```
  Todos os campos são opcionais e independentes; mesmas regras de "não mexe" de Filme acima —
  sem `watchedInTheater` aqui, já que só é aceito pra `Content` do tipo `MOVIE` (`400` se enviado
  pra uma entrada de `EPISODE`).
- **Remover o evento** — `DELETE /diary/{diaryEntryId}` apaga somente este evento EPISODE; os demais
  episódios e passadas da série permanecem. O histórico inteiro só é apagado pela ação explícita
  `DELETE /diary/series/{seriesTmdbId}`.
- **Adicionar à lista** — `GET /users/{userId}/lists?contentId=` do próprio usuário autenticado
  (já marcada via `containsContent` se já contém este conteúdo) + `POST /lists/{listId}/items`
  ```json
  {
    "content": {
      "type": "EPISODE",
      "seriesTmdbId": "1396",
      "seasonNumber": 5,
      "episodeNumber": 16,
      "runtimeMinutes": 55, // [opcional]
      "isSeasonFinale": true, // [opcional]
      "isSeriesFinale": true // [opcional]
    },
    "position": null, // [opcional]
    "description": null, // [opcional]
    "customPosterUrl": null // [opcional]
  }
  ```
- **Remover da lista** — `DELETE /lists/{listId}/items/{itemId}`
- **Botão que leva pra cada episódio da série** — navegação pura do cliente,
  não depende de chamada nova (a numeração já é a chave de `ContentRefCreation`)
- `GET /contents/{contentId}/reviews?page=&size=`
  - **Curtir / descurtir uma review** — `POST` / `DELETE /diary/{diaryEntryId}/like`
  - **Comentar em uma review** — `GET /diary/{diaryEntryId}/comments` /
    `POST /diary/{diaryEntryId}/comments`
    ```json
    { "text": "comentário", "parentCommentId": null /* [opcional] */, "containsSpoiler": false /* [opcional] */ }
    ```
- `GET /contents/{contentId}/comments` — listar comentários do episódio (fora de uma review)
- **Comentar no episódio** — `POST /contents/{contentId}/comments`
  ```json
  { "text": "comentário", "parentCommentId": null /* [opcional] */, "containsSpoiler": false /* [opcional] */ }
  ```
  - **Curtir / descurtir um comentário** — `POST` / `DELETE /comments/{commentId}/like`
  - **Remover um comentário próprio** — `DELETE /comments/{commentId}`

⚠️ **Gaps que o proxy não fecha** (herdados do TMDB em si, não resolvidos por
nenhum desenho de backend, valem pros quatro tipos acima):
- **Horário de lançamento do episódio**: TMDB só devolve `air_date` (dia), sem
  hora, em nenhum endpoint — não dá pra fechar nem no proxy nem no cliente
- **Elenco/gênero/país de SEASON/EPISODE**: resolvidos internamente pelo
  backend puxando a `SERIES` do mesmo `seriesTmdbId` (cacheada) — nenhuma
  chamada extra do cliente, mas é bom saber que o dado "pertence" à série, não
  à temporada/episódio específico no TMDB

---

## Person

### Pessoa

**Uma chamada da Watchwise API**

- `GET /people/{personTmdbId}?participation=ALL|CAST|CREW&page=&size=` retorna em um único contrato
  os dados da pessoa do TMDB, `isFollowing` do viewer, o agregado `progress` e a página de créditos.
  `participation` assume `ALL`; `page` assume 1 (0 também é o alias da primeira página) e `size`
  assume 20, limitado a 1000.
- `credits.content` traz somente filmes e séries distintos, já com `characters`, `jobs`,
  `participation`, `isWatched` e `isInList`. Um crédito com cast e crew tem `participation=ALL` e
  continua presente em ambos os filtros específicos.
- `progress.watchedPercentage` é da filmografia completa normalizada, não muda ao alternar o filtro
  nem ao navegar entre páginas. O cliente aplica hide/fade a partir de `isWatched` e `isInList`; não
  há parâmetros de ocultação ou esmaecimento no endpoint.
- `400` cobre ID TMDB, enum ou paginação inválidos; `401` exige a sessão autenticada; `404` indica
  pessoa TMDB ou viewer inexistente; `502` indica indisponibilidade do TMDB.

## Picks

### Lista de Picks Templates e Community Picks

**Watchwise API**
- `GET /picks-templates?origin=OFFICIAL|COMMUNITY&page=&size=` — lista paginada
  de templates da origem selecionada; cada item já é um `PicksTemplatePreview`, com
  nome, descrição, capa, contagens, categorias e estado de curtida do viewer
- **5 templates mais populares na semana** —
  `GET /picks-templates?sort=POPULAR_WEEK&page=1&size=5`
- **5 templates com mais Picks realizados** —
  `GET /picks-templates?sort=MOST_PICKED&page=1&size=5`
- **5 templates mais recentes** —
  `GET /picks-templates?sort=RECENT&page=1&size=5`

`origin` pode ser combinado com qualquer `sort`. Se a tela exibir as duas
origens, são duas consultas ou uma consulta sem `origin`; a paginação deve usar
`page` e `size` na própria listagem, sem buscar todos os templates para ordenar
no cliente.

### Pick Template

**Watchwise API**
- **Informações do template e estatísticas** —
  `GET /picks-templates/{templateId}` — retorna creator, origin, nome,
  descrição, `coverImage`, instruções, período de elegibilidade, categorias,
  opções, `likesCount`, `commentsCount` e `picksCount`
- **5 previews de Picks com mais likes** —
  `GET /picks-templates/{templateId}/picks?sort=POPULAR&page=1&size=5`
- **5 previews de Picks mais recentes** —
  `GET /picks-templates/{templateId}/picks?sort=RECENT&page=1&size=5`
- **Curtir / descurtir o template** — `POST` / `DELETE`
  `/picks-templates/{templateId}/like`
- **Comentários do template** — `GET /picks-templates/{templateId}/comments?page=&size=`
- **Comentar no template** — `POST /picks-templates/{templateId}/comments`
  ```json
  {
    "text": "comentário",
    "parentCommentId": null,
    "containsSpoiler": false
  }
  ```
- **Curtir / descurtir um comentário** — `POST` / `DELETE`
  `/comments/{commentId}/like`
- **Remover um comentário próprio** — `DELETE /comments/{commentId}`

Os previews retornam até cinco categorias respondidas de cada Pick. Ao abrir
um preview, a tela usa o `id` retornado para buscar as selections completas.

### Picks

**Watchwise API**
- **Listar Picks de um usuário** —
  `GET /users/{userId}/picks?templateId=&page=&size=` — cada preview inclui
  `UserPreviewDTO`, visibilidade, data de criação, `likesCount`,
  `commentsCount`, `isLikedByViewer` e até cinco categorias respondidas
- **Informações completas de um Pick** — `GET /picks/{pickId}` — retorna o
  `userId`, o resumo do template (`picksTemplate`, incluindo creator, nome,
  capa, descrição, data de criação e `picksCount`), estatísticas e todas as
  `selections`, com categoria, alvo, validade e progresso do Pick
- **Perfil completo do usuário** — `GET /users/{userId}`, somente quando a
  tela precisar de dados além do `UserPreviewDTO` já entregue na listagem
- **Curtir / descurtir um Pick** — `POST` / `DELETE` `/picks/{pickId}/like`
- **Comentários do Pick** — `GET /picks/{pickId}/comments?page=&size=`
- **Comentar em um Pick** — `POST /picks/{pickId}/comments`
  ```json
  {
    "text": "comentário",
    "parentCommentId": null,
    "containsSpoiler": false
  }
  ```
- **Curtir / descurtir um comentário** — `POST` / `DELETE`
  `/comments/{commentId}/like`
- **Remover um comentário próprio** — `DELETE /comments/{commentId}`
