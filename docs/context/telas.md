# Telas

> **Legenda**
> ✅ tela especificada · ⚠️ conflito ou lacuna no backend atual · 🆕 endpoint/campo novo necessário

---

## Dashboard

*(ainda não especificado)*

---

## Users

### 👤 Perfil (dividido entre Filme e Série) — estilo Trakt V2 ✅

- Informações do usuário: foto, nome, contagem de seguidores/seguindo, banner
  - ✅ resolvido — `User.banner` (nullable, mesmo formato de `profilePicture`), aceito em `POST /auth/register` e `PATCH /users/me`
  - ✅ resolvido — `followersCount`/`followingCount` agora vêm prontos em `UserResponseDTO`/`PublicUserProfileDTO` (`COUNT` sobre `Follower` com status `ACCEPTED`, calculado a cada request, não desnormalizado)
- Top 5 de Filmes e Séries
  - ✅ já existe (`Top5Entry`)
- *(aba Série)* Últimos 4 episódios assistidos
  - ✅ resolvido — `recentEpisodes` no endpoint de resumo (`GET /users/{userId}/summary?type=SERIES`), reaproveitando `type=EPISODE`+`size=4` no diário
- 6 séries/filmes recentes (completos ou dropped)
  - ✅ resolvido — `recentActivity` no endpoint de resumo, mesclando `DiaryEntry` (nível MOVIE/SERIES) e `DroppedEntry`, ordenado por data e cortado em 6
- Tempo de tela assistido **últimos 30 dias** e desde sempre *(redação corrigida — decidido manter janela rolante de 30 dias, não mês calendário)*
  - ✅ resolvido
- Gêneros mais assistidos (mostra a **quantidade** de títulos MOVIE/SERIES distintos por gênero, all-time)
  - ✅ resolvido — `genreCountsMovies` (conta cada `DiaryEntry` MOVIE, rewatch soma de novo) e `genreCountsSeries` (conta séries distintas iniciadas, rewatch de série já iniciada não soma de novo)
- Gráfico de Ratings por Nota
  - ✅ resolvido — `ratingsDistribution` no endpoint de resumo (histograma por `score`, 1–10)
- Últimas 5 Reviews
  - ✅ resolvido — `recentReviews` no endpoint de resumo (`hasReview=true`, `size=5`)

### 🕰️ History (Diary entries sem review) — estilo Trakt V2 ✅

- Informações do usuário (foto, nome, follower/following count, banner) — mesmas informações do Perfil acima, já resolvidas (banner, followersCount/followingCount)
- Cada diary entry de filme, série, temporada e episódio (tudo junto, com nota, sem review)
  - Ordenada da mais recente pra mais antiga (pode inverter)
  - Pode filtrar por tipo de conteúdo — ✅ resolvido — `type` no diário
  - Pode colocar um range de datas — ✅ resolvido — `dateFrom`/`dateTo` (`year` continua como atalho)

### ⭐ Reviews (Diary entries com review) — estilo Letterboxd ✅

- Informações do usuário — mesmas informações do Perfil acima, já resolvidas (banner, followersCount/followingCount)
- Cada diary entry com review (tudo junto, com nota) — só mostra se tiver review
  - ✅ resolvido — `hasReview=true` no diário
- Comentários na review
  - ✅ resolvido — `GET`/`POST /diary/{diaryEntryId}/comments`, com respostas em thread (`parentCommentId`) e flag de spoiler (`containsSpoiler`); segue a mesma regra de visibilidade padrão (perfil privado bloqueia quem não segue com status aceito)

### 📺 Progress (De Séries) — estilo Trakt V2 ✅

- Informações do usuário — mesmas informações do Perfil acima, já resolvidas (banner, followersCount/followingCount)
- Séries que o usuário está assistindo (não terminou) e seu progresso
  - Ordenar por: último episódio assistido, % completo, episódios faltantes, tempo faltante, data de lançamento
  - ✅ resolvido (parcial) — `GET /users/{userId}/series-in-progress` existe, derivado 100% de `DiaryEntry`/`Content` (sem entidade nova): série entra na lista quando há `DiaryEntry` de `EPISODE` sem `DiaryEntry` de `SERIES` correspondente; devolve até onde o usuário assistiu (`maxSeasonNumber`/`maxEpisodeNumber`) e `lastWatchedDate`
  - 🚫 **limitação de arquitetura, permanece**: dos 5 critérios de ordenação pedidos, só "último episódio assistido" (`lastWatchedDate`) é possível no backend. `%`/tempo/episódios faltantes/data de lançamento dependem do total de episódios da série — dado só do TMDB, que o backend nunca guarda. Trabalho do cliente, cruzando com o TMDB

### 📋 Listas e Listas Curtidas (páginas diferentes) ✅

- Informações do usuário das listas — mesmas informações do Perfil acima, já resolvidas (banner, followersCount/followingCount)
- Preview das listas
  - Ordenar por: rank, atualizado mais recente, alfabética, likes, comentários (quantidade), quantidade de itens
  - ✅ resolvido — todos os seis: `rank`/`updatedAt`/`likesCount` já eram/passaram a ser colunas reais; `name` (alfabética) já era coluna real da própria `UserList` (diferente de item — ver Lista detalhe); `itemsCount`/`commentsCount` via query nativa própria
- **Listas Curtidas** como página própria
  - ✅ resolvido — `GET /users/me/liked-lists`, sempre auto-visão (não resolve visibilidade de terceiro), mais recente curtida primeiro, reaproveitando o mesmo formato/batching de `GET /users/{userId}/lists`

### 📄 Lista (detalhe) ✅

- Informações do usuário da lista — mesmas informações do Perfil acima, já resolvidas (banner, followersCount/followingCount)
- Informações da lista: quantidade de itens, tempo total dos itens, likes, comentários (quantidade)
  - ✅ resolvido — `itemsCount`/`totalRuntimeMinutes`/`commentsCount`
- Itens da lista
  - Filtrar por tipo (filme/série) ou por gênero
    - ✅ resolvido — `type`/`genre` em `GET /lists/{listId}`
  - Ordenar (asc/desc) por: rank, data de adição, alfabética, data de lançamento, duração
    - ✅ resolvido — `position`/`dateAdded`/`duration` (aplicado em memória, sem paginação)
    - 🚫 **alfabética**: diferente do "alfabética" da lista de listas (que ordena por `UserList.name`, coluna real) — aqui seria o **título do item** (filme/série), e `Content` nunca guarda título (dado só do TMDB). Trabalho do cliente
    - 🚫 **data de lançamento**: não dá pra fazer no backend — `Content` nunca guarda data de lançamento (dado só do TMDB). Trabalho do cliente, não uma pendência de backend
  - Pesquisar dentro da lista
    - 🚫 mesma limitação: `Content` não guarda título — busca por nome só pode ser feita no cliente
- Comentários na lista
  - ✅ resolvido — `GET`/`POST /lists/{listId}/comments`, com respostas em thread (`parentCommentId`) e flag de spoiler (`containsSpoiler`); segue a mesma visibilidade de `GET /lists/{listId}` (dono, `PUBLIC`, ou `FOLLOWERS` com status aceito); lista travada como "de listas" não aceita comentário (`400`)

### 🗓️ Month in Review (dividido em Tv Edition e Movie Edition) — estilo Trakt V2 ✅

- Informações do usuário — mesmas informações do Perfil acima, já resolvidas (banner, followersCount/followingCount)
- Últimos 6 filmes/séries assistidos
  - ✅ resolvido — `recentWatched` em `GET /users/{userId}/summary/month`, escopado por `type` (tab), não precisa mesclar MOVIE+SERIES já que a tela é por aba
- 6 filmes/séries mais bem avaliados pelo usuário no mês (Top5 primeiro, se houver)
  - ✅ resolvido — `topRated`, quem já está no Top5 do usuário aparece primeiro dentro do grupo já ordenado por nota
- 6 filmes/séries piores avaliados pelo usuário no mês
  - ✅ resolvido — `bottomRated`, mesma regra de promoção do Top5
- Gráfico de quantidade de notas dadas pelo usuário no mês
  - ✅ resolvido — `ratingsDistribution`
- Quantidade de filmes / quantidade de episódios assistidos e quantidade de horas assistidas no mês
  - ✅ resolvido — `watchCount`/`minutesWatched`
- Primeiro filme/episódio assistido no mês
  - ✅ resolvido — `firstWatchedDate`
- Último filme/episódio assistido no mês
  - ✅ resolvido — `lastWatchedDate`
- Tempo assistido por dia
  - ✅ resolvido — `minutesPerDay`
- Quantidade de filme/episódios assistidos por dia da semana
  - ✅ resolvido — `watchCountByDayOfWeek` (ISO 8601, 1=segunda...7=domingo)
- Quantidade de filme/episódios assistidos por gênero
  - ✅ resolvido — `genreCounts` (MOVIE conta entradas de diário no mês, não títulos distintos; SERIES conta títulos distintos iniciados no mês, não entradas)
- *(aba Tv Edition)* Top 3 séries mais assistidas por tempo
  - ✅ resolvido — `topSeriesByWatchTime`, vazio quando `type=MOVIE`
- *(aba Movie Edition)* Top 3 filmes mais longos assistidos
  - ✅ resolvido — `topLongestMovies`, vazio quando `type=SERIES`

### 📆 Year in Review (dividido em Tv Edition e Movie Edition) — estilo Trakt V2 ✅

- Informações do usuário — mesmas informações do Perfil acima, já resolvidas (banner, followersCount/followingCount)
- Quantidade de filmes/episódios assistidos e quantidade de horas assistidas no ano
  - ✅ resolvido — `watchCount`/`minutesWatched` em `GET /users/{userId}/summary/year`
- Média de horas assistidas de filmes/séries por mês, por semana e por dia
  - ✅ resolvido — `averageMinutesPerMonth`/`averageMinutesPerWeek`/`averageMinutesPerDay`
- Gráfico de mês (x) por quantidade de filme/episódios assistidos (y)
  - ✅ resolvido — `watchCountByMonth`
- Gráfico de quantidade de filme/episódios assistidos (y) por dia da semana (x)
  - ✅ resolvido — `watchCountByDayOfWeek`
- Primeiro filme/episódio assistido no ano
  - ✅ resolvido — `firstWatchedDate`
- Último filme/episódio assistido no ano
  - ✅ resolvido — `lastWatchedDate`
- 10 filmes/séries mais longos (duração total) assistidos
  - ✅ resolvido — `longestWatched`, filme usa `runtimeMinutes` próprio, série soma `runtimeMinutes` dos episódios assistidos no ano por `seriesTmdbId`
- Quantidade de filme/episódios assistidos por gênero
  - ✅ resolvido — `genreCounts`
- 10 filmes/séries mais bem avaliados pelo usuário no ano (Top5 primeiro, se houver)
  - ✅ resolvido — `topRated`
- 10 filmes/séries piores avaliados pelo usuário no ano
  - ✅ resolvido — `bottomRated`
- Gráfico de quantidade de notas dadas pelo usuário no ano
  - ✅ resolvido — `ratingsDistribution`

### 📊 All Time Stats — estilo Trakt V2 ✅

- Informações do usuário — mesmas informações do Perfil acima, já resolvidas (banner, followersCount/followingCount)
- Quantidade de horas assistidas all-time
  - ✅ resolvido — `totalMinutesWatched` em `GET /users/{userId}/summary/all-time`
- Quantidade de filmes / quantidade de episódios assistidos (contagem, all-time)
  - ✅ resolvido — `totalMoviesWatched`/`totalEpisodesWatched`
- Média de horas assistidas de filmes/séries por mês, por semana e por dia
  - ✅ resolvido — `averageMinutesPerMonth`/`averageMinutesPerWeek`/`averageMinutesPerDay`, denominador é a data do primeiro `DiaryEntry` MOVIE/EPISODE do usuário até hoje
- Gráfico de ano (x) por quantidade de filme/episódios assistidos (y)
  - ✅ resolvido — `watchCountByYearMovies`/`watchCountByYearEpisodes`
- Quantidade de filme/série assistida por década (série com base no lançamento)
  - ✅ resolvido — `watchCountByDecade`, combina MOVIE+SERIES por título distinto usando `Content.releaseYear` (novo campo, ver seção Content/`business-rules.md`); vazio pra quem nunca informou `releaseYear`. Não resolve o 🚫 de "ordenar por data de lançamento" na seção "Lista (detalhe)" acima, que precisaria de dia/mês, não só o ano
- Quantidade de filme/série assistida por país
  - ✅ resolvido — `watchCountByCountry`, mesma lógica da década usando `Content.countries` (novo campo)
- 10 filmes/séries mais assistidos completos (quantidade loggada)
  - ✅ resolvido — `mostLoggedContent`
- Quantidade de filme/episódios assistidos por gênero
  - ✅ resolvido — `genreCountsMovies`/`genreCountsSeries`
- 10 filmes/séries mais bem avaliados pelo usuário (Top5 primeiro, se houver)
  - ✅ resolvido — `topRated`, combina MOVIE+SERIES num único ranking
- 10 filmes/séries piores avaliados pelo usuário
  - ✅ resolvido — `bottomRated`
- Gráfico de quantidade de notas dadas pelo usuário
  - ✅ resolvido — `ratingsDistribution` já é all-time, sem mudança necessária pra essa tela

### 📈 Notas de Episódios de uma Série (por usuário) — estilo SeriesGraph ✅

- Informações do usuário — mesmas informações do Perfil acima, já resolvidas (banner, followersCount/followingCount)
- Informações da série
  - trabalho do cliente via TMDB (título/pôster/temporadas nunca são salvos, mesma regra do resto do doc)
- Tabela de temporada x episódio com as notas dadas pelo usuário
  - ✅ resolvido — `GET /users/{userId}/series/{seriesTmdbId}/episode-ratings`; em rewatch, usa a nota do `watchNumber` mais alto

## Home

### 🏠 Home — estilo Trakt V2 ✅

- Informações do usuário (foto, nome, follower/following count, banner)
  - ✅ resolvido — mesmas informações do Perfil, já resolvidas (banner, followersCount/followingCount)
- Tempo e quantidade total de episódios/filmes assistidos pelo usuário desde sempre
  - ✅ resolvido — reaproveita `totalMinutesWatched`/`totalMoviesWatched`/`totalEpisodesWatched` de `GET /users/{userId}/summary/all-time`
- Próximos episódios a ser assistidos das últimas 6 séries que o usuário assistiu um episódio e não terminou
  - ✅ resolvido — reaproveita `GET /users/{userId}/series-in-progress` (já ordenado por `lastWatchedDate`, bastando `size=6`); "próximo episódio" é `maxEpisodeNumber+1` dentro de `maxSeasonNumber`, calculado pelo cliente a partir dos dois campos que o endpoint já devolve
- Preview de 7 dias do calendário (episódios de séries em andamento + filmes da watchlist que vão ser lançados)
  - 🚫 mesma limitação de "Progress"/"Lista (detalhe)" — datas de lançamento são dado só do TMDB, `Content` nunca guarda. O backend já expõe as listas cruas necessárias (`series-in-progress` completo, `GET /users/me/watchlist/{type}`); cruzar com o calendário do TMDB é trabalho do cliente
- Gráfico dos últimos 30 dias com a quantidade de episódios/filmes assistidos (y) por dia (x)
  - ✅ resolvido — `watchCountByDayLast30Days` em `GET /users/{userId}/summary/home`, janela rolante de 30 dias corridos, não mês calendário
- Quantidade de filme/quantidade de episódios assistidos por gênero nos últimos 30 dias
  - ✅ resolvido — `genreCountsMoviesLast30Days`/`genreCountsSeriesLast30Days`, mesmo endpoint
- 4 últimas coisas (filme/episódio) assistidas pelo usuário
  - ✅ resolvido — `recentlyWatched`, mesmo endpoint

## Calendário

### 📅 Calendário (por mês) 🚫

- Mostra os episódios (das séries que o usuário assistiu e não terminou) que vão ser lançados
  - 🚫 mesma limitação já registrada em "Preview de 7 dias" (Home) e "Progress" — datas de lançamento são dado só do TMDB, `Content` nunca guarda. Backend já expõe `GET /users/{userId}/series-in-progress` (lista completa das séries em andamento, com `maxSeasonNumber`/`maxEpisodeNumber`); cruzar com o calendário de lançamentos do TMDB é trabalho do cliente
- Mostra os filmes/episódios (de séries) que estão na watchlist do usuário que vão ser lançados
  - 🚫 mesma limitação — backend já expõe `GET /users/me/watchlist/{type}` (watchlist crua); cruzar com datas de lançamento do TMDB é trabalho do cliente
- Agrupado por mês
  - 🚫 agrupamento é puramente do lado do cliente, já que data de lançamento nunca passa pelo backend
- Essa tela é essencialmente a versão completa/por mês do "Preview de 7 dias" já listado na Home — mesmos dois endpoints crus (`series-in-progress` + `watchlist`), sem endpoint novo necessário

## Social

### 📱 Feed de Atividades (pessoas seguidas) — estilo Twitter ✅

- Mostra as "atualizações" das pessoas que o usuário segue: assistiu um episódio/filme, completou uma temporada, completou uma série, dropou um filme/série, e (se der) trocou o Top 5
  - ✅ resolvido — `GET /feed`, cursor/keyset paginado (`CursorPageMeta`). "post" não é uma entidade nova, é uma view derivada de `DiaryEntry` (MOVIE/EPISODE/SEASON/SERIES), `DroppedEntry` e `Top5Entry` das pessoas seguidas (`Follower` com status `ACCEPTED`), mesclada em tempo de leitura (`FeedServiceImpl.getFeed`)
  - **Decisão adicional**: episódios/temporadas criados só como degrau mecânico de um `POST /diary/bulk` de nível superior (marcar uma temporada/série inteira como assistida) não aparecem no feed — campo novo `DiaryEntry.ignore`, ortogonal a `autoGenerated`, filtrado em `findFeedCandidates`. Sem isso, um bulk log de série spammaria um post por episódio; ver `business-rules.md` § DiaryEntry para a regra completa (hierarquia EPISODE<SEASON<SERIES vs. o tipo pedido na chamada)
  - **Decisão de arquitetura**: agregação pull (query em tempo de leitura), não push (fan-out no write com uma tabela `FeedEvent` própria) — busca `DiaryEntry`/`DroppedEntry`/`Top5Entry` pelos `userId`s seguidos, ordenado por `createdAt`, mesmo padrão já usado em `recentActivity` (`GET /users/{userId}/summary/home`). Escolhido por não exigir tabela nova, escrita duplicada em todo lugar onde esses três tipos são criados, nem lógica extra pra refletir follow/unfollow e mudança de privacidade — o pull já resolve isso lendo a lista de seguidos na hora. Fan-out no write só compensaria em escala de contas com milhares de seguidores, que não é o caso do Watchwise
  - **Decisão de paginação**: cursor/keyset (`createdAt`+`id` do último item visto), não o padrão de página numerada (`PageRequestFactory`/`PageResponseDTO`) usado no resto da API. Offset é literalmente incorreto aqui, não só menos otimizado — como o feed recebe inserts constantes (qualquer pessoa seguida postando algo), paginação por número de página desloca o offset entre requests e causa item duplicado ou pulado ao rolar. A resposta perde `totalElements`/`totalPages`, fica só `hasNext` + `nextCursor` — divergência intencional do envelope padrão, documentada em `business-rules.md` § Feed em vez de forçar o feed a se encaixar no contrato genérico
  - **Decisão de granularidade**: evento genérico "atualizou o Top 5 de {type}", sem tentar expressar qual item entrou/saiu. Isso também resolve a pendência de `updatedAt` levantada antes — checando `Top5EntryServiceImpl`, `shiftUpFrom`/o shift de `removeEntry` só alteram `position` dos vizinhos, nunca `updatedAt` (hoje só existem `insertEntry`/`removeEntry`, não há update-em-lugar), então `createdAt` sozinho já é um sinal limpo, sem risco de um shift de posição virar post falso — mesmo campo usado em `DiaryEntry`/`DroppedEntry`. Efeito colateral aceito: uma remoção sem inserção correspondente não gera post (não cria linha nova), o que é razoável pra um feed social — ninguém precisa ver "removeu algo do Top5"
- Cada "post" mostra as informações de quem postou
  - ✅ resolvido — `FeedItem.user` (`UserPreviewDTO`: foto, username), reaproveitado sem mudança
- Likes e comentários no "post"
  - ✅ resolvido — deliberadamente só pra `eventType=DIARY_ENTRY`: `FeedItem.id` é o `diaryEntryId`, reaproveita `POST /diary/{id}/like` e `GET`/`POST /diary/{id}/comments` já existentes, sem endpoint novo, já que `DiaryEntry` já é alvo válido de `Like`/`Comment`
  - **Decidido (não pendência)**: `DROPPED`/`TOP5_UPDATE` não devem ter curtida/comentário — `DroppedEntry`/`Top5Entry` continuam de propósito fora do `CHECK` de exclusividade de `Like`/`Comment` (`Content`/`UserList`/`DiaryEntry` só)

## Content

### 🎬 Filme/Série ✅

- Informações do filme/série pelo TMDB (nome, data de lançamento, duração de filme ou duração média dos episódios + duração total da série, criadores, país, gênero, descrição, plataforma de stream, últimos 3 episódios lançados, atores regulares + atores convidados, temporadas)
  - 🚫 trabalho do cliente via TMDB — mesma regra do resto do documento, `Content` nunca guarda metadado de título/elenco/temporadas
- Média das notas dos usuários para o conteúdo, quantidade de plays (todos usuários), quantidade de review (diary com texto), quantidade de comentários
  - ✅ resolvido — `GET /contents/{contentId}/stats` (`averageScore`/`playsCount`/`reviewsCount`/`commentsCount`); `averageScore`/`playsCount`/`reviewsCount` só somam `DiaryEntry` de perfil público (métrica agregada e anônima, sem "dono" pra checar segue-aceito)
- Botão que marca como visto (manda todos os episódios em bulk para o banco)
  - ✅ resolvido — `POST /diary/bulk` já existe e cobre exatamente isso (registra uma temporada ou série inteira de uma vez)
- Botão que mostra as listas do usuário e pode adicionar o conteúdo
  - ✅ resolvido — reaproveita `GET /users/me/lists` + `POST /lists/{listId}/items`
- Botão que adiciona o conteúdo na watchlist
  - ✅ resolvido — reaproveita `POST /users/me/watchlist/{type}` já existente
- Botão que marca o conteúdo como dropped
  - ✅ resolvido — reaproveita `POST /users/me/dropped/{type}/{tmdbId}` já existente
- Reviews (com informações do usuário que postou, comentários e likes)
  - ✅ resolvido — `GET /contents/{contentId}/reviews`, paginado, mesma regra de visibilidade de perfil privado já usada em likes/comentários de diário
- Comentários (com informações do usuário que postou, comentários e likes)
  - ✅ resolvido — `GET`/`POST /contents/{contentId}/comments` já existe

### 📀 Temporada ✅

- Informações da temporada pelo TMDB (nome, data de lançamento, duração média dos episódios, criadores, país, gênero, descrição, plataforma de stream, últimos 3 episódios lançados, atores regulares + atores convidados, temporadas)
  - 🚫 trabalho do cliente via TMDB, mesma razão de Filme/Série acima
- Média das notas dos usuários para o conteúdo, quantidade de plays (todos usuários), quantidade de review (diary com texto), quantidade de comentários
  - ✅ resolvido — mesmo `GET /contents/{contentId}/stats` de Filme/Série acima, aplicado a um `Content` `type=SEASON`
- Botão que marca como visto (manda todos os episódios em bulk para o banco)
  - ✅ resolvido — `POST /diary/bulk`
- Botão que mostra as listas do usuário e pode adicionar o conteúdo
  - ✅ resolvido
- Mostra todos os episódios da temporada (com a média das notas, quantidade de plays, quantidade de reviews e quantidade de comentários)
  - ✅ resolvido — `GET /contents/stats?ids=` (batch, até 100 ids), evita N chamadas de `/contents/{contentId}/stats`
- Reviews (com informações do usuário que postou, comentários e likes)
  - ✅ resolvido — mesmo `GET /contents/{contentId}/reviews` de Filme/Série acima
- Comentários (com informações do usuário que postou, comentários e likes)
  - ✅ resolvido

### 🎞️ Episódio ✅

- Informações do episódio pelo TMDB (nome, data de lançamento com horário, duração do episódio, criadores, país, gênero, descrição, plataforma de stream, atores regulares + atores convidados)
  - 🚫 trabalho do cliente via TMDB, mesma razão de Filme/Série acima
- Botão que leva pra cada episódio da série (só precisa da numeração)
  - ✅ resolvido — navegação pura do cliente, não depende de dado novo do backend (a numeração já é a própria chave de `ContentRefCreation` pra EPISODE)
- Média das notas dos usuários para o conteúdo, quantidade de plays (todos usuários), quantidade de review (diary com texto), quantidade de comentários
  - ✅ resolvido — mesmo `GET /contents/{contentId}/stats` de Filme/Série acima
- Botão que mostra as listas do usuário e pode adicionar o conteúdo
  - ✅ resolvido
- Reviews (com informações do usuário que postou, comentários e likes)
  - ✅ resolvido — mesmo `GET /contents/{contentId}/reviews` de Filme/Série acima
- Comentários (com informações do usuário que postou, comentários e likes)
  - ✅ resolvido