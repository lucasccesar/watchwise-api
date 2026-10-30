# Telas

> **Legenda**
> ✅ tela especificada · ⚠️ conflito ou lacuna no backend atual · 🆕 endpoint/campo novo necessário

---

## Users

### 👤 Perfil (dividido entre Filme e Série) — estilo Trakt V2 ✅

- Informações do usuário: foto, nome, contagem de seguidores/seguindo, banner
  - ✅ resolvido — `User.banner` (nullable, mesmo formato de `profilePicture`), aceito em `POST /auth/register` e `PATCH /users/me`
  - ✅ resolvido — `followersCount`/`followingCount` agora vêm prontos em `UserResponseDTO`/`PublicUserProfileDTO` (`COUNT` sobre `Follower` com status `ACCEPTED`, calculado a cada request, não desnormalizado)
- Top 5 de Filmes e Séries
  - ✅ já existe (`Top5Entry`); em contexto de perfil, cada card usa o `customPosterUrl` do dono do
    perfil para aquele conteúdo quando houver override, com fallback ao pôster TMDB
  - Para complementar cards com detalhe TMDB, o batch usa
    `GET /contents/details?ids=<uuid,uuid,...>&posterUserId={userId}`; a página direta de conteúdo omite
    `posterUserId` para usar o pôster do usuário autenticado, não o contexto de perfil anterior
- *(aba Série)* Últimos 4 episódios assistidos
  - ✅ resolvido — `recentEpisodes` no endpoint de resumo (`GET /users/{userId}/summary?type=SERIES`), reaproveitando `type=EPISODE`+`size=4` no diário
- 6 séries/filmes recentes (completos ou dropped)
  - ✅ resolvido — `recentActivity` no resumo mescla DiaryEntry MOVIE, projeções atuais de conclusão SERIES e DroppedEntry, ordenado por atividade e cortado em 6
- Tempo de tela assistido **últimos 30 dias** e desde sempre *(redação corrigida — decidido manter janela rolante de 30 dias, não mês calendário)*
  - ✅ resolvido
- Gêneros mais assistidos (mostra a quantidade de unidades distintas por gênero, all-time)
  - ✅ resolvido no resumo do Perfil — Movies conta filmes distintos e Series conta episódios distintos por coordenada `(seriesTmdbId, seasonNumber, episodeNumber)`; rewatches não somam novamente
- Gráfico de Ratings por Nota
  - ✅ resolvido — `ratingsDistribution` no endpoint de resumo (histograma por `score`, 1–10), contando uma única nota mais recente por filme/episódio distinto; rewatches não duplicam a distribuição
- Últimas 5 Reviews
  - ✅ resolvido — `recentReviews` no endpoint de resumo (`size=5`): Movies usa `MOVIE`; Series mistura `SERIES`, `SEASON` e `EPISODE`. A prévia não expõe o texto da review, likes ou comentários.

### 🕰️ History (Diary e conclusões sem review) — estilo Trakt V2 ✅

- Informações do usuário (foto, nome, follower/following count, banner) — mesmas informações do Perfil acima, já resolvidas (banner, followersCount/followingCount)
- Eventos de DiaryEntry de filme/episódio e conclusões derivadas de série/temporada (tudo junto)
  - Nota atual e reviews de série/temporada são opiniões separadas; não são campos de uma DiaryEntry de série/temporada
  - Ordenada da mais recente pra mais antiga (pode inverter)
  - Pode filtrar por tipo de conteúdo — ✅ resolvido — `type` no diário
  - Pode colocar um range de datas — ✅ resolvido — `dateFrom`/`dateTo` (`year` continua como atalho)

### ⭐ Reviews (Diary e reviews de conteúdo) — estilo Letterboxd ✅

- Informações do usuário — mesmas informações do Perfil acima, já resolvidas (banner, followersCount/followingCount)
- DiaryEntry de filme/episódio com review e reviews publicadas de série/temporada — cada uma só aparece quando tem texto
  - ✅ resolvido — `hasReview=true` no diário
- Comentários na review
  - ✅ resolvido — `GET`/`POST /diary/{diaryEntryId}/comments`, com respostas em thread (`parentCommentId`) e flag de spoiler (`containsSpoiler`); segue a mesma regra de visibilidade padrão (perfil privado bloqueia quem não segue com status aceito)

### 📺 Progress (De Séries) — estilo Trakt V2 ✅

- Informações do usuário — mesmas informações do Perfil acima, já resolvidas (banner, followersCount/followingCount)
- Séries que o usuário está assistindo (não terminou) e seu progresso
  - Ordenar por: último episódio assistido, % completo, episódios faltantes, tempo faltante, data de lançamento
  - ✅ resolvido — `GET /users/{userId}/series-in-progress` deriva de eventos EPISODE e projeções SERIES atuais: uma projeção só oculta uma série quando coincide com revisão do histórico, versão da agenda preferida e validade. Projeções stale não escondem séries em andamento. A resposta mantém progresso por coordenadas distintas de temporada/episódio, runtime, `seasonProgress`, poster do perfil, paginação, agregados e ordenação por progresso/último evento; Specials e episódios futuros não aumentam contagens.
  - ✅ **ordenação no backend** — `sortBy` aceita `LAST_WATCHED`, `LAST_RELEASED`, `REMAINING_EPISODES` e `REMAINING_RUNTIME`; `direction` aceita `ASC` ou `DESC` (padrão `LAST_WATCHED`/`DESC`). A ordenação é global antes da paginação e usa `seriesTmdbId` como desempate estável. O percentual pode ser exibido pela interface, mas não é um valor de `sortBy`; runtime desconhecido permanece nulo.

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

### 📈 Progresso da lista ✅

- Informações do usuário e da lista — mesmas informações da tela Lista (detalhe)
- Progresso dos itens da lista, respeitando o tipo de cada conteúdo
  - Filmes: mostrar somente a quantidade de filmes assistidos em relação à quantidade total de filmes da lista; não informar quais foram assistidos ou não
  - Episódios: mostrar somente a quantidade de episódios assistidos em relação à quantidade total de episódios da lista; não informar quais foram assistidos ou não
  - Temporadas e séries: mostrar o progresso no mesmo formato da tela Progress do usuário, incluindo a quantidade de episódios assistidos, a quantidade total, o percentual e o detalhamento de progresso por temporada quando aplicável
  - ✅ resolvido — `GET /lists/{listId}/progress` retorna os agregados da lista e todos os itens em ordem; filmes/episódios não recebem estado individual, enquanto séries/temporadas recebem o progresso detalhado no escopo do viewer autenticado. O leitor deduplica séries e reutiliza snapshots de progresso para reduzir chamadas ao TMDB

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
  - ✅ resolvido — `ratingsDistribution` em `GET /users/{userId}/summary/all-time/edition?type=MOVIE|SERIES`, escopado à aba selecionada
- A tela chama a rota `/edition` novamente ao trocar entre Movie Edition e TV Edition; a rota `/summary/all-time` continua combinada para consumidores legados

### 📈 Notas de Episódios de uma Série (por usuário) — estilo SeriesGraph ✅

- Informações do usuário — mesmas informações do Perfil acima, já resolvidas (banner, followersCount/followingCount)
- Informações da série
  - ✅ resolvido — `GET /users/{userId}/series/{seriesTmdbId}/episode-ratings` retorna title, releaseYear,
    posterPath, backdropPath e `customPosterUrl` do dono do perfil; a tela usa o pôster customizado quando
    presente e o posterPath TMDB como fallback
- Grade de temporada x episódio
  - ✅ resolvido — o backend resolve metadata e todas as coordenadas regulares já lançadas via TMDB/snapshots;
    cada entrada traz `score` pessoal, `averageScore` e `ratingCount` de perfis públicos, e a resposta traz
    `overallAverageScore`/`overallRatingCount`. A interface não busca série, temporadas ou episódios
    diretamente no TMDB
  - `score` e `averageScore` são null quando não há nota; `ratingCount` fica em 0

### 🗺️ Episode Ratings Map (por usuário) — estilo SeriesGraph ✅

- Informações do usuário — mesmas informações do Perfil acima, já resolvidas (foto, nome, followersCount/followingCount e banner)
- Todas as séries nas quais o usuário registrou pelo menos um episódio, incluindo séries já concluídas
- A lista é paginada por `GET /users/{userId}/episode-ratings-map?page={page}&size={size}`; `page` começa em 1,
  padrão 1; `size` padrão 12 e aceita de 1 a 100; valores fora desses limites retornam 400
- Para cada série, mostra a quantidade de episódios assistidos e a quantidade total de episódios da série
  - A contagem de episódios assistidos considera pares distintos de temporada/episódio e ignora rewatches
  - A contagem total considera episódios regulares lançados até hoje e exclui Specials/temporada 0, seguindo a regra de `Progress`
  - ✅ resolvido — cada item inclui `seriesTmdbId`, `watchedEpisodeCount`, `totalEpisodeCount`, `title`,
    `releaseYear`, `posterPath` e `customPosterUrl` do dono do perfil; o backend resolve os metadados via TMDB
- A resposta inclui `page`, `size`, `totalElements`, `totalPages` e `hasNext`, mais os totais globais
  `totalWatchedEpisodeCount` e `totalEpisodeCount`; os totais não são limitados à página
- `totalEpisodeCount` pode ser null quando algum snapshot não fornece o total; campos de metadata podem ser
  null quando o dado correspondente não está disponível no TMDB/cache
- A interface não consulta o TMDB diretamente para enriquecer a lista

## Home

### 🏠 Home — estilo Trakt V2 ✅

- Informações do usuário (foto, nome e username)
  - ✅ resolvido — a projeção Home retorna somente `viewer.name`, `viewer.username` e `viewer.profilePicture`
- Tempo e quantidade total de episódios/filmes assistidos pelo usuário desde sempre
  - ✅ resolvido — `summary/home` retorna minutos, episódios, filmes reproduzidos, filmes distintos e séries distintas
- Próximos episódios a ser assistidos das últimas 4 séries que o usuário assistiu um episódio e não terminou
  - ✅ resolvido — `nextEpisodes` vem limitado a 4 e enriquecido pelo backend com progresso percentual, título, still, duração, data e média comunitária quando disponível; score do usuário e rating não aparecem porque o episódio ainda não foi assistido
- Próximos 6 lançamentos personalizados para o usuário que visualiza a Home
  - ✅ resolvido — `summary/home.upcomingReleases` retorna até 6 eventos a partir de hoje, usando as mesmas fontes, snapshots, agrupamentos (`MOVIE`, `EPISODE`, `SEASON`, `SERIES`) e metadados de `GET /users/me/calendar`; `releaseStatus`/`watchStatus` controlam o estado visual e `sources` exibe `Watchlist`/`In progress`
- Gráfico dos últimos 30 dias com a quantidade de episódios/filmes assistidos (y) por dia (x)
  - ✅ resolvido — `watchCountByDayLast30Days` em `GET /users/{userId}/summary/home`, exatamente 30 datas inclusivas
- Quantidade de filmes/episódios assistidos por gênero nos últimos 30 dias
  - ✅ resolvido — `genreCountsMoviesLast30Days` e `genreCountsEpisodesLast30Days`; episódios contam individualmente, inclusive rewatches
- 4 últimas coisas (filme/episódio) assistidas pelo usuário
  - ✅ resolvido — `recentlyWatched` compacto, com `watchedDate`, score, conteúdo e fotos dos companheiros
- Resumo Social
  - ✅ resolvido — `socialActivities` contém somente as 3 atividades mais recentes, com `name`, `username`, horário e contadores de likes/comentários

## Calendário

### 📅 Calendário (por mês) ✅

- Mostra os episódios das séries em andamento e os filmes/séries da watchlist que têm lançamento
  futuro — ✅ resolvido por `GET /users/me/calendar?month=YYYY-MM`, que usa snapshots compartilhados
  e a região/idioma preferidos do usuário.
- Agrupado por mês — ✅ resolvido no backend; `eventType` preserva `MOVIE`, `EPISODE`, `SEASON` ou
  `SERIES` conforme a completude dos snapshots.
- A Home usa a projeção limitada `summary/home.upcomingReleases`, enquanto esta tela continua sendo
  a leitura completa por mês do calendário.

## Social

### 📱 Feed de Atividades (pessoas seguidas) — estilo Twitter ✅

- Mostra as "atualizações" das pessoas que o usuário segue: assistiu um filme/episódio, publicou uma review, dropou um filme/série, trocou o Top 5, criou um Pick ou criou um Picks Template. Conclusões de série/temporada são exibidas no Diary, não como eventos sociais separados.
  - ✅ resolvido — `GET /feed`, cursor/keyset paginado (`CursorPageMeta`). "post" não é uma entidade nova, é uma view derivada de `DiaryEntry` (eventos MOVIE/EPISODE), `ContentReview`, `DroppedEntry`, `Top5Entry`, `Pick` e `PicksTemplate` das pessoas seguidas (`Follower` com status `ACCEPTED`), mesclada em tempo de leitura (`FeedServiceImpl.getFeed`). Conclusões SERIES/SEASON são projeções do diário, sem publicação social própria.
  - ✅ resolvido — `PICK_CREATED` retorna `PickPreviewDTO` e o `PicksTemplatePreviewDTO` relacionado; `PICKS_TEMPLATE_CREATED` retorna o preview do template. O card usa o `eventType` para direcionar curtidas e comentários aos endpoints sociais do recurso correto
  - **Decisão adicional**: bulk SERIES/SEASON grava eventos EPISODE reais e os marca `ignore=true` quando estão abaixo do nível pedido; permanecem no histórico e nas projeções, mas não geram um post por episódio no feed. A conclusão derivada aparece em `/diary/view`, sem DiaryEntry ou post separado para SERIES/SEASON. Curtidas/comentários pertencem apenas a reviews persistidas.
  - **Decisão de arquitetura**: agregação pull (query em tempo de leitura), não push (fan-out no write com uma tabela `FeedEvent` própria) — busca `DiaryEntry`/`DroppedEntry`/`Top5Entry`/`Pick`/`PicksTemplate` pelos `userId`s seguidos, ordenado por `createdAt`, mesmo padrão já usado em `recentActivity` (`GET /users/{userId}/summary/home`). Escolhido por não exigir tabela nova, escrita duplicada em todo lugar onde esses tipos são criados, nem lógica extra pra refletir follow/unfollow e mudança de privacidade — o pull já resolve isso lendo a lista de seguidos na hora. Fan-out no write só compensaria em escala de contas com milhares de seguidores, que não é o caso do Watchwise
  - **Decisão de paginação**: cursor/keyset (`createdAt`+`id` do último item visto), não o padrão de página numerada (`PageRequestFactory`/`PageResponseDTO`) usado no resto da API. Offset é literalmente incorreto aqui, não só menos otimizado — como o feed recebe inserts constantes (qualquer pessoa seguida postando algo), paginação por número de página desloca o offset entre requests e causa item duplicado ou pulado ao rolar. A resposta perde `totalElements`/`totalPages`, fica só `hasNext` + `nextCursor` — divergência intencional do envelope padrão, documentada em `business-rules.md` § Feed em vez de forçar o feed a se encaixar no contrato genérico
  - **Decisão de granularidade**: evento genérico "atualizou o Top 5 de {type}", sem tentar expressar qual item entrou/saiu. Isso também resolve a pendência de `updatedAt` levantada antes — checando `Top5EntryServiceImpl`, `shiftUpFrom`/o shift de `removeEntry` só alteram `position` dos vizinhos, nunca `updatedAt` (hoje só existem `insertEntry`/`removeEntry`, não há update-em-lugar), então `createdAt` sozinho já é um sinal limpo, sem risco de um shift de posição virar post falso — mesmo campo usado em `DiaryEntry`/`DroppedEntry`. Efeito colateral aceito: uma remoção sem inserção correspondente não gera post (não cria linha nova), o que é razoável pra um feed social — ninguém precisa ver "removeu algo do Top5"
- Cada "post" mostra as informações de quem postou
  - ✅ resolvido — `FeedItem.user` (`UserPreviewDTO`: foto, username), reaproveitado sem mudança
- Likes e comentários no "post"
  - ✅ resolvido — `DIARY_ENTRY`, `DROPPED`, `PICK_CREATED` e `PICKS_TEMPLATE_CREATED` reutilizam os endpoints sociais existentes; `FeedItem.id` aponta para o recurso correspondente e o `eventType` define qual rota o cliente deve usar
  - ✅ resolvido (2026-09-30) — cada um desses itens já vem com `commentsCount` e até 3 `recentComments` do próprio alvo (`DIARY_ENTRY`/`DROPPED` no topo do item; Pick e Template dentro de `pick`/`picksTemplate`), no formato de `Comment`, com `containsSpoiler` — o cliente deve ocultar o texto quando `true`
  - **Decidido (não pendência)**: `TOP5_UPDATE` não tem curtida/comentário — `Top5Entry` continua de propósito fora dos alvos de `Like`/`Comment`

### Top 5 no feed

- `TOP5_UPDATE` mostra a prévia atual ordenada do Top 5 em `FeedItem.top5`. A prévia é carregada em lote por tipo e autor, não representa um snapshot histórico; eventos antigos podem refletir a lista atual no momento da leitura.

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

## Person

### 👤 Pessoa ✅

- Informações pessoais da pessoa pelo TMDB: nome, foto, biografia, data e local de nascimento
  - ✅ resolvido — `GET /people/{personTmdbId}` retorna os dados pessoais, aliases, departamento conhecido e `isFollowing` do viewer autenticado
- Todos os filmes e séries em que a pessoa participou como cast ou crew
  - Mostrar o nome do personagem em participações de cast e os jobs em participações de crew
  - Retorno paginado, com filtro por tipo de participação (`CAST` ou `CREW`)
  - ✅ resolvido — `GET /people/{personTmdbId}?participation=ALL|CAST|CREW&page=&size=` retorna créditos fundidos e paginados
  - Na visão conjunta, um mesmo filme ou série deve aparecer uma única vez, reunindo todas as participações da pessoa
  - ✅ resolvido — um crédito com cast e crew retorna `participation = ALL`, com `characters` e `jobs` reunidos
- Percentual do que o usuário já assistiu da filmografia da pessoa
  - Para filmes, contar como assistido quando houver um `DiaryEntry` de `MOVIE`
  - Para séries, contar quando o usuário tiver iniciado — ao menos um evento EPISODE assistido; não existe DiaryEntry persistida de série/temporada.
  - ✅ resolvido — `progress.totalCredits`, `watchedCredits` e `watchedPercentage` são calculados sobre toda a filmografia normalizada, antes do filtro `participation` e da paginação
- Filtros e estado visual dos créditos, no estilo de hide/fade usado nas listas
  - Filtrar ou ocultar/esmaecer conteúdos assistidos e não assistidos
  - Filtrar ou ocultar/esmaecer conteúdos que estão ou não estão em alguma lista do usuário
  - ✅ resolvido — cada crédito retorna `isWatched` e `isInList`; ocultar/esmaecer continua sendo comportamento de apresentação do cliente sobre esses flags
- Botão para começar ou deixar de seguir a pessoa
  - ✅ resolvido — `POST`/`DELETE /users/me/follow-people/{personTmdbId}` são idempotentes
  - ✅ resolvido — o detalhe retorna `isFollowing`, sem baixar a lista inteira de pessoas seguidas

## Picks

- Lista de Picks Templates e Community Picks
  - ✅ resolvido — `GET /picks-templates` aceita `origin=OFFICIAL` ou `origin=COMMUNITY`, paginação e retorna `PicksTemplatePreviewDTO` para os previews
  - 5 previews de templates mais populares na semana (com base em likes, comentários e picks realizados)
    - ✅ resolvido — `GET /picks-templates?sort=POPULAR_WEEK&page=1&size=5`; usa atividade dos últimos sete dias, com Picks contados conforme a visibilidade do viewer
  - 5 previews de templates mais picks realizados de todos os tempos
    - ✅ resolvido — `GET /picks-templates?sort=MOST_PICKED&page=1&size=5`; ordena pela quantidade de Picks visíveis para o viewer, preservando essa ressalva em `picksCount`
  - 5 previews templates mais recentes
    - ✅ resolvido — `GET /picks-templates?sort=RECENT&page=1&size=5`
- Pick Template
  - Informações do template
    - ✅ resolvido — `GET /picks-templates/{templateId}` retorna creator, origin, nome, descrição, `coverImage`, instruções, datas, categorias e opções
  - Estatísticas do template (quantidade de likes, comentários e respostas)
    - ✅ resolvido — a resposta retorna `likesCount`, `commentsCount` e `picksCount` (respostas/Picks visíveis ao viewer)
  - 5 previews de picks com mais likes
    - ✅ resolvido — `GET /picks-templates/{templateId}/picks?sort=POPULAR&page=1&size=5`; aplica a visibilidade do viewer e ordena por likes
  - 5 previews de picks mais recentes
    - ✅ resolvido — `GET /picks-templates/{templateId}/picks?sort=RECENT&page=1&size=5`; aplica a visibilidade do viewer e ordena por `createdAt`
  - Comentários
    - ✅ resolvido — `GET`/`POST /picks-templates/{templateId}/comments`, paginado e com respostas por `parentCommentId`
- Picks
  - Informações do usuário
    - ✅ resolvido — `GET /users/{userId}/picks` inclui `UserPreviewDTO` em cada preview (`id`, `username`, `profilePicture` e `isProfilePublic`); `GET /picks/{pickId}` também expõe `userId` para obter o perfil completo
  - Informações do template (como nome do criador, nome do template, coverImage, descrição, data de criação, número de picks feitos)
    - ✅ resolvido — `PickResponseDTO.picksTemplate` inclui creator, nome, `coverImage`, descrição, `createdAt` e `picksCount`
  - Estatísticas da picks do usuário (likes e comentários)
    - ✅ resolvido — `PickPreviewDTO` e `PickResponseDTO` incluem `likesCount`, `commentsCount` e `isLikedByViewer`
  - Respostas das picks do usuário
    - ✅ resolvido — `GET /picks/{pickId}` retorna as `selections` completas, com categoria, alvo, validade e progresso do Pick; os previews carregam até cinco categorias respondidas
  - Comentários
    - ✅ resolvido — `GET`/`POST /picks/{pickId}/comments`, paginado e com respostas por `parentCommentId`
