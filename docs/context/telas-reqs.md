# Requisições por Tela

> Cruza `telas.md` com as requisições necessárias pra montar cada tela — todas
> pra Watchwise API. Reflete o desenho de `tmdb-proxy-design.md` (**implementado
> em 2026-08-30** — commit `003e0db`): o cliente não fala mais direto com o
> TMDB em nenhuma tela; o backend proxya, cacheia e resolve
> `preferredLanguage`/`preferredRegion` do usuário. Pra detalhe campo a campo
> de cada endpoint, ver `openapi.yaml` (todas as rotas, incluindo
> `GET /contents/{contentId}/details` e `GET /contents/details?ids=`).

## Padrão de detalhe de conteúdo (repete em toda tela que lista `Content`)

Qualquer card de filme/série/temporada/episódio (diário, listas, Top5,
watchlist, feed, rankings de summary) precisa de um detalhe por item, **um
GET só na Watchwise API** — nunca no TMDB diretamente:

```
GET /contents/{contentId}/details          → ContentDetailsDTO (um item)
GET /contents/details?ids=<uuid,uuid,...>  → ContentDetailsDTO[] (batch, máx 100, mesma ordem)
```

O backend resolve a chave TMDB internamente (`tmdbId` pra MOVIE/SERIES,
`seriesTmdbId`+`seasonNumber`[+`episodeNumber`] pra SEASON/EPISODE), chama o
TMDB com o idioma do usuário, cacheia, e devolve título (já com fallback
idioma→título alternativo da região→título original), pôster, sinopse, data
de lançamento, elenco, watch providers já filtrados pra região do usuário
etc. — ver `tmdb-proxy-design.md` §5 pro shape completo do `ContentDetailsDTO`.

**Quantas chamadas reais ao TMDB cada `GET /contents/{contentId}/details` dispara**
(`ContentDetailsServiceImpl`, cada uma cacheada 24h por `tmdbId`/chave+idioma —
uma chamada repetida com a mesma chave dentro da janela do cache não vira uma
nova requisição HTTP real ao TMDB):
- `MOVIE` — **1**: `getMovieFullDetails`.
- `SERIES` — **1 + 1 por temporada não-especial** (excluindo `season 0`): `getTvFullDetails`
  da série, mais `getSeasonFullDetails` de cada temporada, todas em paralelo
  (`fetchAllSeasonsInParallel`) — uma série com 5 temporadas dispara 6 chamadas.
- `SEASON` — **2**: `getSeasonFullDetails` da própria temporada + `getTvFullDetails` da série
  (só pra herdar gênero/país/elenco regular/criadores, que `SEASON` não tem sozinha no TMDB).
- `EPISODE` — **2**: `getEpisodeFullDetails` do próprio episódio + `getTvFullDetails` da série
  (mesmo motivo de `SEASON`).
- `GET /contents/details?ids=` (batch) soma o padrão acima por item da lista (sequencial, não
  paralelo entre itens) — mesmo cache por item, então um `contentId` repetido em duas chamadas
  batch diferentes (ou já visto por `GET /contents/{contentId}/details` antes) não paga de novo.

Abaixo, cada tela lista só o que precisa **além** desse padrão — quando a
tela só teria isso, o padrão é citado sem chamada extra. `GET /contents/.../stats` nunca chama o
TMDB (agregação pura sobre o banco). Fora o padrão de detalhe acima, a única ação que chama o TMDB
automaticamente é `POST /diary/bulk` (`SEASON`/`SERIES`, anotado em cada seção abaixo) — toda outra
escrita (curtir, comentar, adicionar a lista/Top5/watchlist/dropped, `POST /contents/reference`,
`POST /diary` de `MOVIE`/`EPISODE`) **nunca** chama o TMDB, mesmo aceitando
`runtimeMinutes`/`genres`/`releaseYear`/`countries` no corpo — são só gravados como vieram, sem
checagem contra o TMDB.

---

## Dashboard

*(ainda não especificado em `telas.md`)*

---

## Users

### Perfil (estilo Trakt V2)

**Watchwise API**
- `GET /users/{userId}` — dados base, `followersCount`/`followingCount`
- `GET /users/{userId}/top5/{type}` — Top 5
- `GET /users/{userId}/summary?type=MOVIE|SERIES` — `recentEpisodes`,
  `recentActivity`, tempo assistido, `genreCounts`, `ratingsDistribution`,
  `recentReviews`
- `GET /contents/details?ids=<uuid,uuid,...>` (batch, máx 100 ids, mesma ordem) — detalhe
  (título, pôster, etc., ver `ContentDetailsDTO`) de cada item do Top5/`recentEpisodes`/
  `recentActivity`/`recentReviews`

### History (diário sem review)

**Watchwise API**
- `GET /users/{userId}` (cabeçalho)
- `GET /users/{userId}/diary?type=&dateFrom=&dateTo=&hasReview=false&page=&size=`
- `GET /contents/details?ids=<uuid,uuid,...>` (batch, máx 100 ids, mesma ordem) — detalhe de
  cada item do diário retornado na página

### Reviews (diário com review)

**Watchwise API**
- `GET /users/{userId}` (cabeçalho)
- `GET /users/{userId}/diary?hasReview=true&page=&size=`
- `GET /diary/{diaryEntryId}/comments` — listar comentários de uma review
- **Comentar em uma review** — `POST /diary/{diaryEntryId}/comments`
  ```json
  { "text": "comentário", "parentCommentId": null /* [opcional] */, "containsSpoiler": false /* [opcional] */ }
  ```
- `GET /contents/details?ids=<uuid,uuid,...>` (batch, máx 100 ids, mesma ordem) — detalhe de
  cada item do diário retornado na página

### Progress (séries em andamento)

**Watchwise API**
- `GET /users/{userId}` (cabeçalho)
- `GET /users/{userId}/series-in-progress?page=&size=` — só ordena por
  `lastWatchedDate`; os outros 4 critérios pedidos (%, episódios/tempo
  faltantes, data de lançamento) continuam sendo trabalho do cliente —
  `ContentDetailsDTO` não devolve contagem de episódios por temporada (não é
  um `Content` que o backend tem referência própria; ver limitação abaixo)
- `GET /contents/details?ids=<uuid,uuid,...>` (batch, máx 100 ids, mesma ordem) — nome/pôster
  (e demais campos de `ContentDetailsDTO`) de cada série em progresso

⚠️ **Limitação que o proxy não resolve**: pra calcular % completo/episódios
faltantes o cliente precisa do total de episódios de cada temporada, que só
existe se o cliente souber o `seriesTmdbId`+`seasonNumber` de uma temporada
que ainda não virou `Content` (o usuário não logou nenhum episódio dela
ainda) — o backend só tem endpoint de detalhe por `contentId` já existente.
Segue sendo uma lacuna real, não fechada por este desenho.

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
  conteúdo aceita). `position`/`description`/`customPosterUrl` são opcionais nos dois casos
  (`customPosterUrl` sempre `null` se o item for lista aninhada).
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
  aninhada).
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
- `GET /contents/details?ids=<uuid,uuid,...>` (batch, máx 100 ids, mesma ordem) — detalhe de
  cada item de `recentWatched`/`topRated`/`bottomRated`/`topSeriesByWatchTime`/`topLongestMovies`
- `topWatchCompanions` já vem pronto do backend (`WatchCompanionCountDTO` embute
  `UserPreviewDTO` do companheiro) — sem chamada extra, mesmo padrão de
  `FeedItem.user` na seção Feed

### Year in Review

**Watchwise API**
- `GET /users/{userId}` (cabeçalho)
- `GET /users/{userId}/summary/year?type=MOVIE|SERIES&year=YYYY`
- `GET /contents/details?ids=<uuid,uuid,...>` (batch, máx 100 ids, mesma ordem) — detalhe de
  cada item de `longestWatched`/`topRated`/`bottomRated`
- `topWatchCompanions` já vem pronto do backend, sem chamada extra (mesmo
  motivo de Month in Review acima)

### All Time Stats

**Watchwise API**
- `GET /users/{userId}` (cabeçalho)
- `GET /users/{userId}/summary/all-time` (sem `type`, combina os dois)
- `GET /contents/details?ids=<uuid,uuid,...>` (batch, máx 100 ids, mesma ordem) — detalhe de
  cada item de `mostLoggedContent`/`topRated`/`bottomRated`
- `topWatchCompanions` já vem pronto do backend, sem chamada extra (mesmo
  motivo de Month in Review acima)

### Notas de Episódios de uma Série (estilo SeriesGraph)

**Watchwise API**
- `GET /users/{userId}` (cabeçalho)
- `GET /users/{userId}/series/{seriesTmdbId}/episode-ratings`
- `GET /contents/{contentId}/details` do `Content` `type=SERIES` — nome,
  pôster, lista de `seasons` (colunas da tabela)
- `GET /contents/details?ids=` dos `Content` `type=SEASON` de cada temporada
  exibida (ou `type=EPISODE` se a tela preferir número/nome por episódio
  direto) — linhas da tabela

⚠️ Pra usar o detalhe de uma temporada/episódio é preciso já ter o
`contentId` dela — só existe se aquele episódio já foi logado por alguém
(`Content` só é criado via `POST /contents/reference` ou como efeito colateral
de um diário). Uma temporada inteira nunca assistida por ninguém não tem
`Content` nenhum pra pedir detalhe — mesma limitação estrutural de antes,
independente do proxy.

---

## Home

**Watchwise API**
- `GET /users/{userId}` (cabeçalho)
- `GET /users/{userId}/summary/home` — total assistido histórico
  (`totalMinutesWatched`/`totalMoviesWatched`/`totalEpisodesWatched`), próximos episódios
  (`nextEpisodes`, já limitado a 6), gráfico 30 dias, gêneros 30 dias, 4 últimos assistidos —
  não chamar `GET /users/{userId}/summary/all-time` nem
  `GET /users/{userId}/series-in-progress?size=6` à parte aqui, são os mesmos dados recalculados
  (`SummaryServiceImpl.getHomeSummary`/`getAllTimeStats` usam a mesma query pros totais;
  `HOME_NEXT_EPISODES_LIMIT = 6` é o mesmo número e a mesma fonte de `nextEpisodes`)
- `GET /users/me/watchlist/{type}` — pro preview de calendário de 7 dias
- `GET /contents/details?ids=<uuid,uuid,...>` (batch, máx 100 ids, mesma ordem) — detalhe de
  cada item de `recentlyWatched`, de `nextEpisodes` e dos itens da watchlist

🚫 **Ainda cliente**: preview de calendário de 7 dias (quais episódios/filmes
"vão ser lançados") — `ContentDetailsDTO` devolve a data de lançamento de um
`Content` que já existe, mas não "próximo episódio ainda não anunciado como
`Content`" nem faz o agrupamento por data. O cliente ainda cruza
`series-in-progress`/`watchlist` com o calendário TMDB por conta própria (ou
isso vira trabalho de uma iteração futura do proxy, fora do escopo atual).

---

## Calendário

**Watchwise API**
- `GET /users/{userId}/series-in-progress` (lista completa, sem `size`)
- `GET /users/me/watchlist/{type}`
- Mesma limitação da Home — sem endpoint de proxy pra isso ainda

---

## Social

### Feed de Atividades

**Watchwise API**
- `GET /feed?cursor=&size=` (paginação por cursor, não por página)
- **Curtir / descurtir um evento** — `POST` / `DELETE /diary/{id}/like` — só em eventos
  `DIARY_ENTRY`; `DROPPED`/`TOP5_UPDATE` não têm curtida/comentário
- `GET /diary/{id}/comments` — listar comentários de um evento `DIARY_ENTRY`
- **Comentar em um evento** — `POST /diary/{id}/comments`
  ```json
  { "text": "comentário", "parentCommentId": null /* [opcional] */, "containsSpoiler": false /* [opcional] */ }
  ```
- `GET /contents/details?ids=<uuid,uuid,...>` (batch, máx 100 ids, mesma ordem) — detalhe do
  conteúdo referenciado em cada `FeedItem`. Quem postou já vem pronto do backend
  (`FeedItem.user`)

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

### Filme

**Watchwise API**
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
      "tmdbId": "550",
      "runtimeMinutes": 139, // [opcional]
      "genres": ["Drama"], // [opcional]
      "releaseYear": 1999, // [opcional]
      "countries": ["US", "DE"] // [opcional]
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
  `content.runtimeMinutes`/`genres`/`releaseYear`/`countries` são opcionais,
  mas sem eles esse filme nunca contribui pra `totalMinutesWatched`/
  `genreCounts`/stats por década/país (o backend nunca busca esses dados no
  TMDB sozinho — ver `ContentRefCreation`) — preencher com o que
  `GET /contents/{contentId}/details` já devolveu na mesma tela
  (`runtimeMinutes`, `genres`, ano de `releaseDate`, `countries`). Setados uma
  única vez por conteúdo (compartilhado entre todos os usuários) — reenviar
  um valor diferente depois de já registrado dá `409`. `watchedDate` no futuro
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
- **Remover o registro** — `DELETE /diary/{diaryEntryId}?overrideProtectedEntries=false` — `MOVIE`
  não participa de cascata de auto-completude (só `EPISODE`/`SEASON`/`SERIES`), então
  `GET /diary/{diaryEntryId}/deletion-impact` (ver Série/Temporada/Episódio abaixo) sempre devolve
  `wouldDelete: []` aqui — não vale a pena chamar antes de remover um filme.
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
- `GET /contents/{contentId}/reviews?page=&size=` — reviews de todos os usuários
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
  chave `seriesTmdbId`+idioma reaproveitada entre `backfillSeriesMetadataIfNeeded`,
  `resolveSeriesFinaleSeasonNumber` — só se não houver finale conhecido no banco nem override — e a
  checagem do teto de 2000 — só se excedido —, então até 3 pontos do código chamam, mas no máximo 1
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
- **Editar um registro específico já criado pelo bulk** — `PATCH /diary/{diaryEntryId}`
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
  pra uma entrada de `SERIES`).
- **(Recomendado antes de remover) Pré-visualizar impacto da remoção** —
  `GET /diary/{diaryEntryId}/deletion-impact?overrideProtectedEntries=false`
  ```json
  { "wouldDelete": [ { "id": "<uuid>", "type": "SERIES", "watchedDate": "2026-09-01", "watchNumber": 1, "autoGenerated": true, "hasReview": false } ] }
  ```
  Roda o delete de verdade numa transação que nunca é commitada (dry-run) — devolve exatamente o
  que o `DELETE` abaixo apagaria em cascata, sem apagar nada de fato; a entrada consultada em si
  nunca aparece na lista (ela sempre é apagada, sem precisar repetir). Mesmo
  `overrideProtectedEntries` do `DELETE`: com `false` (default) só lista o que seria apagado
  automaticamente (`autoGenerated = true`); com `true` também lista entradas editadas manualmente
  que entrariam na remoção. `hasReview` (`comment`/`score` não-nulo) é só uma dica de severidade
  pra UI, não a flag de proteção de fato (`autoGenerated`).
- **Remover um registro específico já criado pelo bulk** —
  `DELETE /diary/{diaryEntryId}?overrideProtectedEntries=false`
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
  - **Curtir / descurtir uma review** — `POST` / `DELETE /diary/{diaryEntryId}/like`
  - **Comentar em uma review** — `GET /diary/{diaryEntryId}/comments` /
    `POST /diary/{diaryEntryId}/comments`
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
- **Editar um registro específico já criado pelo bulk** — `PATCH /diary/{diaryEntryId}`
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
  pra uma entrada de `SEASON`).
- **(Recomendado antes de remover) Pré-visualizar impacto da remoção** —
  `GET /diary/{diaryEntryId}/deletion-impact?overrideProtectedEntries=false` — mesmo dry-run de
  Série acima (apagar o último episódio restante de uma temporada pode retrair a entrada de
  `SERIES` de auto-completude que dependia dela).
- **Remover um registro específico já criado pelo bulk** —
  `DELETE /diary/{diaryEntryId}?overrideProtectedEntries=false`
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
  - **Curtir / descurtir uma review** — `POST` / `DELETE /diary/{diaryEntryId}/like`
  - **Comentar em uma review** — `GET /diary/{diaryEntryId}/comments` /
    `POST /diary/{diaryEntryId}/comments`
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
      "runtimeMinutes": 55, // [opcional]
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
  únicos campos obrigatórios do corpo inteiro. `runtimeMinutes`/`isSeasonFinale`/`isSeriesFinale`
  são opcionais — `runtimeMinutes`/`isSeasonFinale` só são aceitos em `EPISODE`, `isSeriesFinale`
  também em `SEASON` — e vêm do próprio detalhe do episódio (`runtime`) e do cliente sabendo se
  é o último episódio da temporada/série. Não existe `watchedInTheater` aqui — só é aceito pra
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
- **(Recomendado antes de remover) Pré-visualizar impacto da remoção** —
  `GET /diary/{diaryEntryId}/deletion-impact?overrideProtectedEntries=false` — mesmo dry-run de
  Série acima (apagar um episódio pode retrair `SEASON`/`SERIES` de auto-completude que dependiam
  dele).
- **Remover o registro** — `DELETE /diary/{diaryEntryId}?overrideProtectedEntries=false`
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
