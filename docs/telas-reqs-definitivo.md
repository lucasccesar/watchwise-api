# Requisitos definitivos — Home, User Progress, Episode Ratings Map, User Profile, Diary History, Diary Reviews, Review, Month in Review, Year in Review, All Time Stats, Watchlist, My Lists, Community Lists, Create List, List, Episodes List, List of Lists, List Progress, Picks Explore, My Picks, Create Picks Template, Calendar, TMDB Trending, Social/Feed, Notifications, Games, Games Ranking, Movie by Poster, Series by Poster, Person by Face, Episode by Frame, Movie by Info, Series by Info, Actor by Movie Filmography, Actor by Series Filmography e Content Detail

Este documento descreve as requisições necessárias para montar as telas
**Home**, **User Progress**, **Episode Ratings Map**, **Episode Ratings Map (Show)**,
**User Profile**, **Diary History**, **Diary Reviews**, **Review**, **Month in Review**,
**Year in Review**, **All Time Stats**, **Watchlist**, **My Lists**, **Community Lists**,
**Create List**, **List**, **Episodes List**, **List of Lists**, **List Progress**, **Picks Explore**,
**My Picks**, **Create Picks Template**, **Calendar**, **TMDB Trending**,
**Social/Feed**, **Notifications**, **Games**, **Games Ranking** e **Content Detail** e substitui, para essas telas, as informações
incompletas de `docs/context/telas-reqs.md`.

## Contrato vigente de modelos de leitura (Task 11)

Os contratos abaixo substituem as instruções antigas deste documento que mandavam buscar
`GET /contents/details?ids=...` para cada card de Home, Profile, Diary, Review ou Summary. O detalhe
batch continua disponível para compatibilidade; não é requisito dos novos fluxos de tela.

- **Home:** `GET /users/{userId}/summary/home` já entrega `nextEpisodes` enriquecido,
  `upcomingReleases` com até seis eventos futuros personalizados para o viewer,
  `recentlyWatched[].card` e `socialActivities[].card`. O calendário mensal completo continua
  disponível por `GET /users/me/calendar`.
- **Profile:** `GET /users/{userId}/summary?type=...` e `GET /users/{userId}/top5/{type}` entregam
  cards opcionais nos itens visuais. O poster customizado usa o dono do perfil. A linha permanece
  quando o card é `PARTIAL` ou `UNAVAILABLE`.
- **Diary History/Reviews/Review:** `GET /users/{userId}/diary` e `GET /diary/{id}` entregam o card
  junto dos campos sociais, texto, score, spoilers e companions. O card não substitui os campos sociais.
- **Month/Year/All Time:** os rankings retornam cards aditivos; Month também retorna
  `topLongestMovieCards` e preserva `topLongestMovies` como referência legada.
- **Watchlist:** `GET /users/{userId}/watchlist/view` é o modelo visual. Filtra `type`/`genre`, deriva
  `status`, ordena e pagina; `aggregate` é global sobre o conjunto filtrado. `ALL` é apenas um
  filtro de consulta; cada linha retorna `NEW`, `IN_PROGRESS` ou `UPCOMING`. Não chama
  `/contents/details` nem cria `Content`.
- **Calendar:** `GET /users/me/calendar?month=YYYY-MM` entrega snapshots de agenda. `releaseContext`
  contém somente `releaseTime`/`network` comprovados; não há `granularity` e nenhum horário é fabricado.
- **TMDB Trending:** `GET /trending` e `GET /trending/{type}` entregam `TrendingCard`; TMDB continua
  sendo a fonte do ranking. `popularity` e `tmdbVoteAverage` não são estatísticas Watchwise. Página,
  janela, tipo e idioma isolam o cache; `viewerState` fica fora dele.
- **Social/Feed:** `GET /feed` entrega `FeedItemView` aditivo, com `card`, `top5Preview.cards`,
  `pickTargetCards`, `followingCount` e `followedUsersPreview`. A página e o cursor são selecionados
  antes do enriquecimento; candidatos descartados não acionam resolução de cards.
- **My Lists:** `GET /users/me/lists/view` retorna uma página de `UserListCardDTO` com a união
  deduplicada das listas próprias e das listas curtidas. O endpoint aplica filtros, ordenação,
  visibilidade dos previews e paginação no servidor.
- **Community Lists:** `GET /lists/discover/view` retorna somente listas públicas; quando a tela
  estiver no contexto de um usuário, `GET /users/{userId}/lists/view` aplica a visibilidade do perfil
  e da lista. Ambos retornam o mesmo modelo visual de cards.
- **Create List:** o formulário usa `POST /users/me/lists` para uma lista vazia ou
  `POST /users/me/lists/bulk` para criar a lista já com conteúdos. A busca de filmes e séries usa
  `/search`; temporadas e episódios usam as rotas de seleção contextual do Content. O tipo escolhido
  no formulário limita a busca e não é salvo como uma propriedade da lista.

Em todos os casos, `POST /contents/reference` é reservado para ações que persistem referências. Uma
leitura pode retornar `contentId: null` e deve usar `previewStatus` para placeholder, sem inventar
metadata. Estado de viewer e posters customizados nunca entram em caches compartilhados de metadata.

## Tela

**Home**

A tela pressupõe que o usuário já esteja autenticado. O `userId` usado na
Home própria é o ID do usuário autenticado.

## Requisições para montar a tela

### 1. Resumo principal

```http
GET /users/{userId}/summary/home
```

Esta é a chamada principal e deve ser feita uma única vez ao abrir a tela.
Ela fornece:

- `viewer.name`, `viewer.username` e `viewer.profilePicture`;
- `hasUnreadNotifications`;
- estatísticas totais de filmes, filmes distintos, episódios e minutos;
- os quatro itens de `nextEpisodes`;
- até seis itens de `upcomingReleases`, já ordenados por data e usando a mesma granularidade do calendário;
- `watchCountByDayLast30Days`;
- `genreCountsMoviesLast30Days`;
- `genreCountsEpisodesLast30Days`;
- os quatro itens de `recentlyWatched`;
- as três atividades de `socialActivities`.

O cliente não deve chamar TMDB para montar esses blocos.

### 2. Próximos lançamentos

Os próximos lançamentos são lidos de `summary/home.upcomingReleases`; nenhuma chamada adicional
ao calendário é necessária para essa seção. A lista tem no máximo seis eventos a partir de hoje,
personalizados para o viewer autenticado.

Essa seção reutiliza a mesma fonte lógica do calendário, mas entrega somente os seis primeiros eventos
independentemente do mês. O calendário mensal completo continua disponível quando o usuário abrir a
tela própria de calendário:

```http
GET /users/me/calendar?month=YYYY-MM
```

Usar nos cards:

- `releaseDate` para agrupar por dia;
- `eventType` para distinguir filme, episódio, temporada e série;
- `content` para título, pôster, still e numeração;
- `releaseStatus` para diferenciar lançado e futuro;
- `watchStatus` para diferenciar assistido, não assistido e parcialmente
  assistido;
- `sources` para exibir `Watchlist` e/ou `In progress`.

Não interpretar `sources.WATCHLIST` como uma plataforma de streaming.

### 3. Cards visuais entregues pelo resumo

`recentlyWatched[].card` e `socialActivities[].card` já contêm a projeção visual
da linha. Usar `card.title`, `card.posterPath`, `card.releaseDate`,
`card.runtimeMinutes`, `card.customPosterUrl` e `card.previewStatus` quando
presentes, mantendo a referência legada em `content`.

Não chamar `/contents/details` ou `/contents/reference` para montar esses cards.
`nextEpisodes` já retorna título da série, título do episódio, still, duração,
data, progresso percentual e média comunitária quando disponível.

## Mapeamento dos blocos da tela

| Bloco | Fonte | Requisição adicional |
|---|---|---|
| Cabeçalho do usuário | `summary/home.viewer` | Nenhuma |
| Indicador de notificações | `summary/home.hasUnreadNotifications` | Nenhuma |
| Estatísticas gerais | campos totais de `summary/home` | Nenhuma |
| Continue watching | `summary/home.nextEpisodes` | Nenhuma |
| Upcoming | `summary/home.upcomingReleases` | Nenhuma chamada adicional |
| Gráfico de 30 dias | `watchCountByDayLast30Days` | Nenhuma; preencher dias ausentes com zero |
| Gêneros de filmes | `genreCountsMoviesLast30Days` | Nenhuma |
| Gêneros de episódios | `genreCountsEpisodesLast30Days` | Nenhuma |
| Recently watched | `summary/home.recentlyWatched[].card` | Nenhuma chamada adicional |
| Social | `summary/home.socialActivities[].card` | Nenhuma chamada adicional |

## Ações iniciadas pela Home

### Marcar próximo episódio como assistido

```http
POST /diary
```

Enviar a referência do episódio selecionado. Não enviar nota de usuário nem
comentário nessa ação. Após sucesso, atualizar o resumo principal e o
calendário para refletir o novo progresso.

### Abrir notificações completas

A Home usa somente `hasUnreadNotifications`. Ao navegar para a tela própria
de notificações, usar:

```http
GET /notifications/view?page=1&size=20
```

A rota raw `GET /notifications` continua disponível para fluxos que precisem somente das referências
estruturais.

### Abrir Social completo

A Home exibe apenas três atividades. Ao navegar para Social, usar:

```http
GET /feed?size=<tamanho>&cursor=<cursor-opcional>
```

### Abrir o detalhe de um conteúdo

Quando o usuário sair da Home para o detalhe de um conteúdo já referenciado,
usar:

```http
GET /contents/{contentId}/details?posterUserId={userId}
```

Essa chamada pertence à tela de detalhe; não deve ser repetida para cada card
da Home quando o carregamento em lote já tiver sido feito.

## Ordem recomendada de carregamento

1. Validar a sessão autenticada.
2. Disparar em paralelo `summary/home` e o(s) mês(es) do calendário.
3. Renderizar imediatamente os blocos que dependem apenas do resumo.
4. Renderizar os cards entregues em `recentlyWatched` e `socialActivities`.
5. Aplicar placeholder somente quando `previewStatus` for `PARTIAL` ou `UNAVAILABLE`.


## Regras de erro

- `401`: renovar a sessão ou redirecionar para login.
- `403`: informar que o resumo não pode ser visualizado.
- `404`: informar que o usuário não existe.
- `400` no calendário: corrigir o parâmetro para `YYYY-MM`.
- Falha de TMDB ao resolver `nextEpisodes`: manter a Home em `200` e omitir
  somente o card afetado; nunca inventar título, imagem ou data.
- Falha de metadata: manter a linha e usar placeholder visual; não criar `Content`
  durante a leitura.

# Notifications

## Dados da tela

Usar `GET /notifications/view?page=1&size=20` como fonte da tela. A rota exige autenticação,
seleciona primeiro as notificações do destinatário e limita o tamanho efetivo a 20. O filtro opcional
`isRead=true|false` altera a página exibida e seus metadados filtrados.

O retorno preserva todos os campos do contrato bruto de `GET /notifications` e acrescenta:

- `contentCard` para notificações de lançamento, data anunciada, cancelamento, renovação e episódio novo;
- `person` para notificações de créditos de pessoas seguidas;
- `targetPreview` para curtidas e comentários, com preview compacto de comentário, review, lista,
  Pick ou template;
- `totalNotifications` e `unreadNotifications`, globais para o destinatário autenticado.

Usar `contentCard` para montar poster/título/metadados do conteúdo, `person` para nome/foto da
pessoa e `targetPreview` para texto, score, capa de template ou card de conteúdo do alvo. A
interface deve agrupar as linhas por data usando `createdAt`; `updatedAt` representa a atualização
de agregados sociais e pode ser usado para o texto relativo da atividade.

## Contrato visual por tipo

`NotificationView` preserva os campos do retorno raw e acrescenta os previews necessários para a
tela. O cliente deve renderizar cada tipo conforme a matriz:

| Tipo | Dados visuais principais | Ação ou navegação |
| --- | --- | --- |
| `RELEASE`, `ANNOUNCED_DATE`, `CANCELLED`, `RENEWED`, `NEW_EPISODE` | `contentCard` | Abrir o conteúdo relacionado |
| `FOLLOWED_PERSON_NEW_CREDIT` | `person` e, quando houver, `contentCard` | Abrir pessoa ou conteúdo |
| `LIKE_RECEIVED`, `COMMENT_RECEIVED` | `latestActor`, `interactionCount`, `targetPreview` | Abrir alvo social |
| `FOLLOW_REQUEST` | `latestActor` | Aceitar ou rejeitar pedido |
| `NEW_FOLLOWER` | `latestActor` | Abrir perfil do novo seguidor |
| `FOLLOW_REQUEST_ACCEPTED` | `latestActor` | Abrir perfil do usuário |

`targetPreview` é compacto e não expande o agregado: comentários retornam texto; reviews retornam
texto/score e `contentCard`; listas retornam nome/descrição; Picks retornam nome/descrição do template;
templates de Picks também podem retornar `coverImageUrl`. Um alvo removido mantém `targetType`/`targetId`
e recebe `previewStatus=UNAVAILABLE`.

O envelope de página usa `page` 1-based. `totalElements`, `totalPages` e `hasNext` correspondem ao
filtro atual; `totalNotifications` e `unreadNotifications` são globais para o usuário autenticado.
A aba All omite `isRead`, Unread usa `isRead=false` e Read usa `isRead=true`.

## Carregamento e ações

1. Carregar a primeira página visual. O envelope retorna `page` 1-based, `totalElements`,
   `totalPages`, `hasNext`, `totalNotifications` e `unreadNotifications`.
2. Para a aba All, omitir `isRead`; para Unread usar `isRead=false`; para Read usar
   `isRead=true`.
3. Carregar mais páginas somente quando `hasNext=true`; não concatenar resultados de filtros
   diferentes.
4. Marcar uma notificação como lida com `PATCH /notifications/{notificationId}/read`.
5. Pedidos de follow usam `latestActor.id` nas rotas existentes de aceitar ou rejeitar; a tela não
   deve inferir o usuário pelo texto da mensagem.
6. Atualizar o contador do cabeçalho a partir de `unreadNotifications`, sem fazer uma segunda
   chamada de contagem.

## Estados visuais e limites

- `AVAILABLE`: renderizar o preview completo;
- `PARTIAL`: renderizar os campos disponíveis e placeholder nos ausentes;
- `UNAVAILABLE`: manter a notificação, sua mensagem e seus IDs, exibindo estado neutro;
- notificações de follow podem não possuir content, targetPreview ou interactionCount;
- não chamar `GET /contents/details?ids=...`, não criar referências de Content durante a leitura e
  não fazer uma requisição adicional por linha;
- a rota raw permanece disponível para fluxos que precisem somente das referências estruturais.

## Regras de erro

---

# User Progress

## Tela

**User Progress**

A tela mostra as séries que o usuário começou, mas ainda não concluiu ou
abandonou. O `userId` é o dono do progresso; a identidade autenticada continua
sendo enviada pelo cookie de sessão.

## Requisições para montar a tela

### 1. Cabeçalho do usuário

```http
GET /users/{userId}
```

Usar para montar o cabeçalho visual do perfil:

- `name`;
- `username`;
- `description`;
- `profilePicture`;
- `banner`;
- `followersCount`;
- `followingCount`.

Para o próprio perfil, o `userId` é o usuário autenticado. Para outro perfil,
as regras de visibilidade continuam valendo normalmente.

### 2. Progresso paginado das séries

```http
GET /users/{userId}/series-in-progress?page=<pagina>&size=<tamanho>&sortBy=<criterio>&direction=<direcao>
```

Essa é a chamada principal da tela. O cliente deve informar `size=6` para
corresponder ao protótipo, ou outro tamanho definido pela interface.

Critérios de ordenação:

| Rótulo da tela | `sortBy` | `direction` inicial |
|---|---|---|
| Watched date | `LAST_WATCHED` | `DESC` |
| Release date | `LAST_RELEASED` | `DESC` |
| Episodes left | `REMAINING_EPISODES` | `DESC` |
| Time left | `REMAINING_RUNTIME` | `DESC` |

`Release date` usa `lastReleasedEpisodeDate`, isto é, a data do último
episódio lançado, não a data de estreia da série.

## Dados usados pela tela

### Resumo superior

Usar `aggregate`:

- `remainingEpisodeCount`: episódios restantes somados entre todas as séries
  da lista;
- `remainingRuntimeMinutes`: tempo restante somado, quando todos os runtimes
  necessários forem conhecidos;
- `totalSeriesCount`: quantidade de séries em andamento.

### Cada linha de série

Usar diretamente:

- `seriesTitle` para o título;
- `customPosterUrl` como poster prioritário;
- `seriesPosterPath` como poster TMDB de fallback;
- `watchedEpisodeCount` e `totalEpisodeCount` para o texto `14 of 16`;
- `watchedPercentage` para a barra de progresso;
- `remainingEpisodeCount` e `remainingRuntimeMinutes` para o tempo restante;
- `lastWatchedSeasonNumber`, `lastWatchedEpisodeNumber`, `lastWatchedDate` e
  `lastWatchedEpisodeTitle` para o bloco `Last watched`;
- `seasonProgress` para as linhas de temporada, incluindo contagem,
  percentual e runtime assistido/restante.

### Card do próximo episódio

Usar `nextEpisode`:

- `seasonNumber`;
- `episodeNumber`;
- `title`;
- `releaseDate`;
- `runtimeMinutes`;
- `stillPath`;
- `availableToWatch`.

`nextEpisode` pode ser `null` quando não houver episódio restante. Episódios
futuros podem ser retornados, mas devem ficar indisponíveis para a ação de
marcar como assistido quando `availableToWatch=false`.

## Requisições que não devem ser feitas no carregamento

O endpoint de progresso já resolve no backend o título, poster, último
episódio e próximo episódio. Portanto, a montagem da lista não deve fazer:

- `GET /contents/{contentId}/details` por série;
- `GET /contents/details` em lote para as séries da página;
- `POST /contents/reference` para criar referências durante a leitura;
- uma consulta por temporada ou por episódio.

O backend faz uma consulta em lote das coordenadas assistidas da página e
reutiliza o cache do TMDB. Se a tela abrir o detalhe de um conteúdo, a
resolução de referência pode ocorrer somente nesse momento, fora do carregamento
da lista.

## Paginação

Usar:

- `page`;
- `size`;
- `totalElements`;
- `totalPages`;
- `hasNext`.

O `aggregate` e `totalElements` representam todas as séries elegíveis, não
somente as séries presentes na página atual.

## Ações opcionais da tela

Estas chamadas não são necessárias para renderizar a tela. Só devem ser feitas
se o modal interativo do protótipo for habilitado.

### Marcar o próximo episódio como assistido

```http
POST /diary
```

Enviar uma referência `EPISODE` com `seriesTmdbId`, `seasonNumber` e
`episodeNumber` obtidos de `nextEpisode`. Após a resposta, recarregar
`series-in-progress`, porque o progresso pode completar uma temporada ou fazer
a série desaparecer da lista.

### Editar ou remover um registro já existente

```http
PATCH /diary/{diaryEntryId}
DELETE /diary/{diaryEntryId}
```

Essas chamadas dependem de um `diaryEntryId` obtido em outro fluxo; o contrato
de leitura do Progress não expõe esse estado.

## Ordem recomendada de carregamento

1. Validar a sessão autenticada.
2. Disparar em paralelo `GET /users/{userId}` e
   `GET /users/{userId}/series-in-progress`.
3. Renderizar o cabeçalho e os agregados assim que as respostas chegarem.
4. Renderizar cada linha usando os dados já enriquecidos pelo backend.
5. Ao trocar ordenação, direção ou página, repetir somente a chamada de
   `series-in-progress` com os novos parâmetros.

## Regras de erro

- `401`: renovar a sessão ou redirecionar para login;
- `403`: informar que o perfil é privado ou não pode ser visualizado;
- `404`: informar que o usuário não existe;
- `400`: corrigir página, tamanho ou enum de ordenação;
- `502`: tratar indisponibilidade do TMDB quando não houver snapshot obrigatório
  para calcular o progresso;
- falha opcional ao resolver título, poster ou episódio: manter a linha
  numérica e usar placeholder ou omitir somente o campo enriquecido.

---

# Episode Ratings Map

## Tela

**Episode Ratings Map**

A tela lista todas as séries nas quais o usuário registrou pelo menos um
episódio regular, incluindo séries concluídas. O `userId` é o dono do mapa; a
identidade autenticada continua sendo enviada pelo cookie de sessão.

## Requisições para montar a tela

### 1. Cabeçalho do usuário

```http
GET /users/{userId}
```

Usar para montar o cabeçalho visual:

- `name`;
- `username`;
- `description`;
- `profilePicture`;
- `banner`;
- `followersCount`;
- `followingCount`.

O endpoint também aplica a visibilidade do perfil. Não tentar montar o
cabeçalho com outra rota se a resposta for `403`.

### 2. Mapa paginado de séries

```http
GET /users/{userId}/episode-ratings-map?page=<pagina>&size=<tamanho>
```

Parâmetros:

- `page`: opcional, 1-based, padrão `1`;
- `size`: opcional, padrão `12`, intervalo permitido de `1` a `100`.

Usar `series` para renderizar os cards da página. Cada item contém:

- `seriesTmdbId`, usado no link para a tela da série;
- `title`;
- `releaseYear`;
- `posterPath` como poster TMDB de fallback;
- `customPosterUrl` como poster prioritário quando não for nulo;
- `watchedEpisodeCount`;
- `totalEpisodeCount`.

O resultado também contém:

- `page`;
- `size`;
- `totalElements`;
- `totalPages`;
- `hasNext`;
- `totalWatchedEpisodeCount`, usado no resumo superior;
- `totalEpisodeCount`, usado como denominador global do resumo superior.

`totalWatchedEpisodeCount` e `totalEpisodeCount` representam todas as séries,
não somente os cards da página atual. `totalEpisodeCount` pode ser `null` quando
algum snapshot não possui total conhecido; nesse caso, não exibir zero como se
fosse um total real.

`watchedEpisodeCount` conta pares distintos
`(seasonNumber, episodeNumber)`, ignora rewatches e exclui a temporada `0`.
`totalEpisodeCount` considera somente episódios regulares lançados até hoje,
com `air_date` válida.

## Requisições que não devem ser feitas no carregamento

O mapa já retorna os metadados necessários para os cards. Não fazer:

- chamada direta ao TMDB;
- `GET /contents/{contentId}/details` por série;
- `GET /contents/details` para enriquecer os cards;
- `POST /contents/reference` durante a leitura;
- uma consulta por série, temporada ou episódio.

## Paginação e ordenação

O backend ordena a lista inteira por:

1. proporção assistida decrescente;
2. quantidade de episódios assistidos decrescente;
3. `seriesTmdbId` crescente.

O cliente deve usar os campos de paginação retornados pelo backend e repetir
somente o endpoint do mapa quando o usuário trocar de página ou tamanho.

## Ordem recomendada de carregamento

1. Validar a sessão autenticada.
2. Disparar em paralelo `GET /users/{userId}` e
   `GET /users/{userId}/episode-ratings-map?page=1&size=12`.
3. Renderizar o cabeçalho e os totais globais assim que as respostas chegarem.
4. Renderizar os cards usando o poster customizado antes do poster TMDB.
5. Ao selecionar um card, navegar para
   `Episode Ratings Map (Show)` usando `userId` e `seriesTmdbId`.

## Regras de erro

- `401`: renovar a sessão ou redirecionar para login;
- `400`: corrigir `page` ou `size`;
- `403`: informar que o perfil é privado ou não pode ser visualizado;
- `404`: informar que o usuário ou uma série não existe;
- `502`: informar indisponibilidade do TMDB/snapshot necessário e não fabricar
  título, poster ou total de episódios.

---

# Episode Ratings Map (Show)

## Tela

**Episode Ratings Map (Show)**

A tela mostra a grade de avaliações de uma série, organizada por temporada e
episódio. O `seriesTmdbId` vem do item selecionado no mapa; o `userId` continua
sendo o dono do perfil consultado.

## Requisições para montar a tela

### 1. Cabeçalho do usuário

```http
GET /users/{userId}
```

Usar os mesmos campos de cabeçalho do mapa: `name`, `username`, `description`,
`profilePicture`, `banner`, `followersCount` e `followingCount`.

### 2. Grade de avaliações da série

```http
GET /users/{userId}/series/{seriesTmdbId}/episode-ratings
```

Essa é a chamada principal da tela. Ela retorna:

- `seriesTmdbId`;
- `title`;
- `releaseYear`;
- `posterPath`;
- `backdropPath`;
- `customPosterUrl`;
- `overallAverageScore`;
- `overallRatingCount`;
- `episodes`.

Cada item de `episodes` contém:

- `seasonNumber`;
- `episodeNumber`;
- `score`, nota pessoal do usuário consultado;
- `averageScore`, média pública do Watchwise para a coordenada;
- `ratingCount`, quantidade de notas públicas usadas na média.

A lista inclui as coordenadas reais dos episódios regulares lançados até hoje,
inclusive quando não existe nota pessoal ou comunitária. Specials, episódios
futuros e episódios sem data válida não entram.

## Abas da grade

### My ratings

Usar `score` de cada episódio. `null` significa que o usuário consultado não
possui uma avaliação para aquela coordenada.

Uma avaliação posterior sem `score` não apaga a última avaliação pontuada. Para
rewatches pontuados, a nota pessoal considera o maior `watchNumber`, depois
`createdAt` mais recente e depois `id` maior.

### Average ratings

Usar `averageScore` e `ratingCount`.

A média comunitária:

- considera somente usuários com perfil público;
- usa no máximo uma nota por usuário e coordenada;
- ignora rewatches depois de escolher a avaliação pontuada mais recente;
- deixa `averageScore` como `null` e `ratingCount` como `0` quando não há notas.

`overallAverageScore` e `overallRatingCount` seguem as mesmas regras, agregando
todas as coordenadas lançadas da série. A média geral deve ser calculada pelo
total de pontos dividido pelo total de avaliações, e não pela média simples das
médias dos episódios.

## Poster, hero e compartilhamento

Usar `customPosterUrl` antes de `posterPath` no poster da série. Usar
`backdropPath` no hero; se estiver nulo, aplicar o placeholder visual da
interface.

O modal de compartilhamento não exige uma requisição adicional. Ele deve usar
os dados já retornados por `episodes`, permitir alternar entre `score` e
`averageScore`, ocultar temporadas e gerar a imagem localmente.

## Requisições que não devem ser feitas no carregamento

Não fazer:

- chamada direta ao TMDB;
- `GET /contents/{contentId}/details` para obter título, temporadas ou episódios;
- `GET /contents/details` por temporada ou episódio;
- `GET /contents/stats` para calcular as médias da grade;
- `POST /contents/reference` para criar referências durante a leitura;
- uma chamada por episódio.

O backend resolve metadata, snapshots e coordenadas lançadas, e calcula as
médias a partir das projeções de `DiaryEntry`.

## Ordem recomendada de carregamento

1. Validar a sessão autenticada.
2. Disparar em paralelo `GET /users/{userId}` e
   `GET /users/{userId}/series/{seriesTmdbId}/episode-ratings`.
3. Renderizar o hero e o bloco de informações da série.
4. Agrupar `episodes` por `seasonNumber` e montar as linhas por `episodeNumber`.
5. Renderizar inicialmente `My ratings` e permitir alternar para
   `Average ratings` sem nova chamada.
6. Abrir o modal de compartilhamento usando somente o payload já carregado.

## Regras de erro

- `401`: renovar a sessão ou redirecionar para login;
- `400`: corrigir `seriesTmdbId` vazio;
- `403`: informar que o perfil é privado ou não pode ser visualizado;
- `404`: informar que o usuário ou a série não existe;
- `502`: informar indisponibilidade do TMDB ou dos snapshots necessários para
  resolver metadata e coordenadas lançadas.

---

# Diary History

## Tela

**Diary History**

A tela mostra o histórico de conteúdos assistidos sem review, agrupado por dia.
O `userId` é o dono do diário consultado; a identidade autenticada continua
sendo enviada pelo cookie de sessão.

## Requisições para montar a tela

### 1. Cabeçalho e totais do diário

```http
GET /users/{userId}
GET /users/{userId}/summary/home
```

Usar `GET /users/{userId}` para os dados de perfil exibidos no cabeçalho,
quando necessários: `name`, `username`, `description`, `profilePicture`,
`banner`, `followersCount` e `followingCount`.

Usar `summary/home` como fonte dos totais do cabeçalho, sem refazer contagens a
partir da lista paginada:

- `totalEpisodesWatched` para episódios logados;
- `distinctSeriesWatched` para séries distintas;
- `totalMoviesWatched` ou `totalDistinctMoviesWatched`, conforme o rótulo do
  card escolhido pela interface;
- `totalMinutesWatchedMovies + totalMinutesWatchedEpisodes` para o tempo total,
  formatado em dias e horas.

`watchCountByDayLast30Days` não substitui o resumo diário desta tela: ele é uma
janela fixa dos últimos 30 dias e não respeita os filtros escolhidos pelo
usuário.

### 2. Opções do filtro de série

```http
GET /users/{userId}/diary/series
```

A resposta é uma lista não paginada de opções normalizadas:

- `seriesTmdbId` para o valor enviado no filtro;
- `title` para o rótulo;
- `entriesCount` para a quantidade de entradas da série.

As entradas `SERIES`, `SEASON` e `EPISODE` são agrupadas pela mesma série e
rewatches aumentam `entriesCount`. O endpoint não recebe o intervalo atual;
portanto, a contagem exibida nas opções é global para o diário consultado.

### 3. Resumo diário agrupado

```http
GET /users/{userId}/diary/daily-summary?type=<type>&dateFrom=<YYYY-MM-DD>&dateTo=<YYYY-MM-DD>&hasReview=false&seriesTmdbId=<seriesTmdbId>&scoreFrom=<1-10>&scoreTo=<1-10>
```

Enviar somente os parâmetros ativos. A resposta contém uma linha por dia:

- `date` é `watchedDate` quando preenchido;
- quando `watchedDate` é nulo, `date` usa a data de criação da entrada;
- `plays` é a quantidade de entradas naquele dia;
- `totalMinutes` é a soma das durações conhecidas.

Este endpoint não aceita `page`, `size`, `sortBy` ou `year`. `dateFrom` e
`dateTo` são inclusivos.

### 4. Entradas paginadas do histórico

```http
GET /users/{userId}/diary?type=<type>&dateFrom=<YYYY-MM-DD>&dateTo=<YYYY-MM-DD>&hasReview=false&seriesTmdbId=<seriesTmdbId>&scoreFrom=<1-10>&scoreTo=<1-10>&sortBy=NEWEST&page=1&size=20
```

Usar `content` para obter os IDs internos dos conteúdos e os campos sociais já
retornados na entrada, incluindo `likesCount`, `likedByMe` e `commentsCount`.
Os filtros aceitos pela tela são:

- `type`: `MOVIE`, `SERIES`, `SEASON` ou `EPISODE`;
- `seriesTmdbId`: identidade normalizada da série;
- `dateFrom` e `dateTo`: intervalo inclusivo;
- `scoreFrom` e `scoreTo`: faixa inclusiva de nota entre 1 e 10;
- `sortBy`: `NEWEST`, `OLDEST`, `RATING_DESC` ou `RATING_ASC`;
- `hasReview=false`: mantém apenas entradas sem texto de review.

O parâmetro `score` também existe para nota exata, mas não deve ser enviado
junto com `scoreFrom` ou `scoreTo`. `year` permanece disponível por
compatibilidade no backend, mas é desnecessário para esta tela.

Aplicar exatamente os mesmos filtros em `daily-summary` e nesta listagem. O
resumo diário não deve ser calculado a partir apenas dos itens da página atual.

### 5. Cards visuais da página

Cada entrada do `GET /users/{userId}/diary` já pode trazer `card`, com metadata
básica e stats públicos. Usar `customPosterUrl` quando disponível, respeitar
`previewStatus` e manter a entrada textual/social mesmo quando o card estiver
parcial ou indisponível. Não fazer `/contents/details` por página e não chamar
`POST /contents/reference` durante a leitura.

## Mapeamento dos blocos da tela

| Bloco | Fonte | Requisição |
|---|---|---|
| Cabeçalho do perfil | `GET /users/{userId}` | Uma chamada |
| Totais de episódios, séries, filmes e tempo | `summary/home` | Uma chamada |
| Filtros de série | `diary/series` | Uma chamada, reutilizada durante a sessão da tela |
| Agrupamento por dia | `diary/daily-summary` | Repetir ao alterar filtros ou intervalo |
| Cards do histórico | `diary[].card` | Nenhuma chamada adicional |
| Curtidas e comentários no card | `likesCount`, `likedByMe`, `commentsCount` | Nenhuma chamada adicional |

## Ordem recomendada de carregamento

1. Validar a sessão autenticada.
2. Disparar em paralelo `GET /users/{userId}`, `summary/home`,
   `diary/series`, `diary/daily-summary` e a primeira página de `diary`.
3. Renderizar o cabeçalho, os totais e os agrupamentos diários assim que
   chegarem.
4. Renderizar `diary[].card` e aplicar placeholder conforme `previewStatus`.
5. Ao alterar filtros, repetir em paralelo o resumo diário e a página do
   histórico, sem refazer o cabeçalho se o `userId` não mudou.

## Regras de erro

- `401`: renovar a sessão ou redirecionar para login;
- `400`: corrigir filtros, intervalo, faixa de nota, paginação ou UUID;
- `403`: informar que o perfil é privado ou não pode ser visualizado;
- `404`: informar que o usuário ou conteúdo não existe;
- `502`: manter o item sem metadados visuais ou exibir placeholder, sem chamar
  o TMDB diretamente.

---

# Diary Reviews

## Tela

**Diary Reviews**

A tela lista as entradas do diário que possuem review, em formato paginado e
ordenadas pela data da review. O `userId` é o dono do diário consultado.

## Requisições para montar a tela

### 1. Cabeçalho e totais

Usar as mesmas chamadas do Diary History:

```http
GET /users/{userId}
GET /users/{userId}/summary/home
```

Os totais são históricos e não devem ser recalculados contando somente as
reviews da página atual.

### 2. Opções de filtro de série

```http
GET /users/{userId}/diary/series
```

Reutilizar `seriesTmdbId`, `title` e `entriesCount` no seletor de série.

### 3. Lista paginada de reviews

```http
GET /users/{userId}/diary?type=<type>&dateFrom=<YYYY-MM-DD>&dateTo=<YYYY-MM-DD>&hasReview=true&seriesTmdbId=<seriesTmdbId>&scoreFrom=<1-10>&scoreTo=<1-10>&sortBy=NEWEST&page=1&size=20
```

Usar `hasReview=true` para excluir entradas sem texto. A tela pode filtrar por
tipo, série, intervalo de data e faixa de nota. `sortBy=NEWEST` corresponde à
ordenação por data da review mostrada no design; `OLDEST`, `RATING_DESC` e
`RATING_ASC` também são suportados. Não há ordenação server-side por título.

Cada item já retorna os dados sociais necessários para o card:
`likesCount`, `likedByMe` e `commentsCount`, além de `score`, `comment`,
`watchedDate`, `watchNumber`, `watchedInTheater` e `customPosterUrl`.

### 4. Cards visuais das reviews

Cada item da página já traz `card` opcional. Ele é aditivo e separado do texto
da review, likes, comentários, spoilers e companions. O cliente não deve chamar
`/contents/details` por review.

## Ações iniciadas pela tela

Ao selecionar uma review, navegar para **Review** com o `diaryEntryId`. A lista
já fornece `commentsCount`; não buscar os comentários de todas as reviews no
carregamento da listagem.

## Ordem recomendada de carregamento

1. Disparar em paralelo o cabeçalho, o resumo/home, as opções de série e a
   primeira página filtrada de `diary`.
2. Renderizar os cards textuais e indicadores sociais.
3. Renderizar os cards e indicadores sociais já entregues.
4. Ao trocar filtros ou página, repetir somente a consulta da listagem.

## Regras de erro

- `400`: corrigir filtros, faixa de nota, intervalo, paginação ou UUID;
- `403`: informar que o perfil é privado ou não pode ser visualizado;
- `404`: informar que o usuário ou conteúdo não existe;
- `502`: usar placeholder para poster/metadados cujo detalhe não pôde ser
  resolvido.

---

# Review

## Tela

**Review**

A tela mostra uma review específica, suas ações sociais e a discussão em
comentários. O identificador de navegação é o `diaryEntryId`.

## Requisições para montar a tela

### 1. Review completa

```http
GET /diary/{diaryEntryId}
```

Esta é a chamada principal. Ela retorna `userId`, `content`, `comment`,
`score`, `watchedDate`, `watchNumber`, `watchedInTheater`, `watchedWith`,
`likesCount`, `likedByMe`, `commentsCount` e `customPosterUrl`. A leitura
respeita a visibilidade do perfil do dono da entrada.

### 2. Perfil do autor

O retorno direto fornece o `userId`, mas não substitui o perfil completo do
autor. Se a tela precisar do nome, avatar, banner ou contadores do autor:

```http
GET /users/{userId}
```

Reutilizar uma resposta já carregada pela tela de Diary Reviews quando ela
estiver disponível.

### 3. Card visual da review

`GET /diary/{diaryEntryId}` já entrega `card` opcional junto de `content`,
texto, score, poster do autor, likes, comentários e companions. Usar o card
quando `previewStatus` permitir; não chamar `/contents/{contentId}/details`
apenas para abrir a review.

### 4. Comentários e respostas

```http
GET /diary/{diaryEntryId}/comments?page=1&size=20
```

O retorno é paginado e cada comentário traz `parentCommentId`. A interface
deve montar a árvore de respostas localmente, sem fazer uma requisição por
reply. `commentsCount` da review serve para o contador inicial; a lista de
comentários deve ser carregada somente quando a seção for exibida ou aberta.

## Ações da tela

### Curtir ou remover curtida da review

```http
POST /diary/{diaryEntryId}/like
DELETE /diary/{diaryEntryId}/like
```

Atualizar `likesCount` e `likedByMe` localmente após a resposta bem-sucedida.

### Comentar ou responder

```http
POST /diary/{diaryEntryId}/comments
```

```json
{
  "text": "comentário",
  "parentCommentId": null,
  "containsSpoiler": false
}
```

Para responder, preencher `parentCommentId` com o comentário-alvo. `text` tem
limite de 280 caracteres.

### Editar a própria review

```http
PATCH /diary/{diaryEntryId}
```

Enviar somente os campos alterados:

```json
{
  "comment": "texto atualizado",
  "score": 8,
  "watchedDate": "2026-09-01",
  "watchedInTheater": false,
  "customPosterUrl": "https://image.tmdb.org/t/p/w342/poster.jpg",
  "watchedWith": []
}
```

Após a atualização, usar a resposta da própria requisição para substituir a
review exibida. Campos ausentes ou `null` não alteram o valor existente;
`watchedWith` aceita uma lista, inclusive vazia, para substituir os
acompanhantes.

## Ordem recomendada de carregamento

1. Buscar `GET /diary/{diaryEntryId}`.
2. Com os IDs retornados, disparar em paralelo o perfil do autor, quando
   necessário, e os detalhes visuais do conteúdo.
3. Renderizar o bloco principal usando `likesCount`, `likedByMe` e
   `commentsCount` da review.
4. Buscar comentários ao abrir a seção ou em paralelo caso ela já esteja
   visível no primeiro viewport.

## Regras de erro

- `401`: renovar a sessão ou redirecionar para login;
- `400`: corrigir UUID, corpo do comentário ou corpo da edição;
- `403`: informar que a review, o perfil ou a ação social não está disponível;
- `404`: informar que a review, o autor ou o conteúdo não existe;
- `502`: manter o bloco textual da review e usar placeholder para os metadados
  visuais indisponíveis.

---

# User Profile

## Tela

**User Profile**

A tela pode exibir o perfil do próprio usuário ou de outro usuário visível.
O `userId` usado nas requisições é sempre o usuário dono do perfil, enquanto a
identidade autenticada é enviada pelo cookie de sessão. O cliente não deve
montar a relação de follow a partir de contadores ou do resultado de uma ação;
ela vem pronta do backend.

## Requisições para montar a tela

### 1. Cabeçalho do perfil

```http
GET /users/{userId}
```

Fazer uma única vez ao abrir o perfil. Usar:

- `id`, `username`, `name`, `description`, `profilePicture` e `banner`;
- `followersCount` e `followingCount`;
- `isProfilePublic`;
- `viewerRelation`, com os valores `SELF`, `FOLLOWING` ou `NOT_FOLLOWING`.

`viewerRelation=SELF` identifica o próprio perfil. `FOLLOWING` representa uma
relação aceita do viewer para o perfil. `NOT_FOLLOWING` não representa uma
solicitação pendente. Em perfil privado sem permissão, o backend responde
`403` antes de expor os dados.

Os campos legados de estatística presentes nessa resposta não substituem os
agregados por aba de `summary`; para a tela, usar `summary.watchTime`,
`summary.genreCounts` e `summary.ratingsSummary`.

### 2. Resumo da aba selecionada

Para a aba Movies:

```http
GET /users/{userId}/summary?type=MOVIE
```

Para a aba Series:

```http
GET /users/{userId}/summary?type=SERIES
```

Fazer somente a chamada da aba inicialmente selecionada. Ao trocar de aba,
fazer a chamada correspondente e permitir cache por `userId + type`.

O resumo fornece:

- `watchTime.totalMinutesWatched` e `watchTime.minutesWatchedLast30Days`;
- `watchTime.totalWatchedCount` e `watchTime.watchedCountLast30Days`;
- `highlights.rewatch` e `highlights.longestWatch`;
- `genreCounts`;
- `ratingsSummary.totalRatings` e `ratingsSummary.averageScore`;
- `ratingsDistribution`;
- `recentActivity`;
- `recentReviews`;
- `recentEpisodes`, preenchido somente na aba Series.

Semântica dos agregados:

- Movies usa entradas `MOVIE` para tempo e quantidade assistida;
- Series usa entradas `EPISODE` para tempo e quantidade assistida;
- gêneros de Movies contam filmes distintos;
- gêneros de Series contam coordenadas distintas de episódio;
- ratings usam uma única nota mais recente por filme ou episódio distinto;
- rewatches não duplicam gêneros nem ratings.

### 3. Top 5 da aba selecionada

Para Movies:

```http
GET /users/{userId}/top5/MOVIE
```

Para Series:

```http
GET /users/{userId}/top5/SERIES
```

Fazer em paralelo com o resumo da mesma aba. Cada item já contém `content`,
`position`, `customPosterUrl` e o `score` pessoal mais recente do usuário.
Score ausente deve ser renderizado como vazio; o cliente não deve procurar
uma nota antiga em outra requisição.

### 4. Cards visuais entregues pelo resumo e pelo Top 5

Os itens de `recentActivity`, `recentReviews`, `recentEpisodes` e os itens
de Top 5 já podem trazer `card`. O backend resolve metadata básica e poster
customizado no contexto do dono do perfil, deduplica coordenadas e mantém os
campos legados. Não fazer batch de `/contents/details` para montar a aba e não
chamar `/contents/reference` durante a leitura.

Os destaques de `highlights` já retornam título, ano, poster TMDB e poster
personalizado quando disponíveis. Não incluir seus IDs em uma chamada de
detalhes apenas para reconstruir esses dados.

## Mapeamento dos blocos da tela

| Bloco | Fonte | Requisição |
|---|---|---|
| Cabeçalho | `GET /users/{userId}` | Uma vez ao abrir |
| Botão seguir/seguindo | `viewerRelation` | Nenhuma adicional |
| Contadores de seguidores | `followersCount` e `followingCount` | Nenhuma adicional |
| Estatísticas da aba | `summary.watchTime` | `/summary?type=...` |
| Destaque de rewatch | `summary.highlights.rewatch` | Nenhuma adicional |
| Destaque de maior duração | `summary.highlights.longestWatch` | Nenhuma adicional |
| Top 5 Movies | `GET /users/{userId}/top5/MOVIE` | Uma vez para Movies |
| Top 5 Series | `GET /users/{userId}/top5/SERIES` | Uma vez para Series |
| Recent Movies | `summary.recentActivity` com `type=MOVIE` | `/summary?type=MOVIE` |
| Recent Series | `summary.recentActivity` com `type=SERIES` | `/summary?type=SERIES` |
| Quantidade assistida | `recentActivity.timesWatched` | Nenhuma adicional |
| Status concluído/abandonado | `recentActivity.status` | Nenhuma adicional |
| Últimos episódios | `summary.recentEpisodes` | Apenas Series |
| Recent reviews | `summary.recentReviews` | Uma lista compacta por aba |
| Gêneros | `summary.genreCounts` | Nenhuma adicional |
| Distribuição de ratings | `summary.ratingsDistribution` | Nenhuma adicional |
| Total e média de ratings | `summary.ratingsSummary` | Nenhuma adicional |
| Título, pôster e metadata dos cards | `item.card` | Nenhuma chamada adicional |

### Recent Movies e Recent Series

Usar `recentActivity`, que contém somente itens de nível superior `MOVIE` ou
`SERIES`. A lista mistura:

- `COMPLETED`, originado de uma conclusão no diário;
- `DROPPED`, originado de um item abandonado.

`timesWatched` representa o número de passadas completas daquele conteúdo.
Para `DROPPED`, representa as passadas completas registradas antes do
abandono. Progresso parcial de episódios não deve ser transformado em uma
atividade de série.

### Recent episodes

Usar somente `recentEpisodes` da aba Series. A lista tem até quatro episódios,
independentemente de existir review. O payload é compacto e não contém texto
de review, likes ou contagem de comentários.

### Recent reviews

Usar `recentReviews` como uma lista única de até cinco itens:

- Movies: somente conteúdos `MOVIE` com comentário não vazio;
- Series: conteúdos `SERIES`, `SEASON` e `EPISODE` com comentário não vazio.

O payload contém a referência do conteúdo, score, data, número da visualização,
poster personalizado e companions. Não contém o texto do comentário, likes ou
contagem de comentários. Para temporadas e episódios, usar `content.type`,
  `seasonNumber` e `episodeNumber`; metadata ausente permanece nula e o
  `previewStatus` orienta o placeholder.

## Ações iniciadas pelo User Profile

### Seguir outro usuário

```http
POST /users/{userId}/follow
```

Após sucesso, atualizar o cabeçalho chamando novamente `GET /users/{userId}`.
Não inferir `FOLLOWING` ou `NOT_FOLLOWING` localmente.

### Deixar de seguir

```http
DELETE /users/{userId}/follow
```

Após sucesso, atualizar o cabeçalho e os blocos que dependem da visibilidade.

### Alterar Top 5 do próprio perfil

Somente quando a tela oferecer edição do próprio usuário:

```http
POST /users/me/top5/{type}
PATCH /users/me/top5/{type}/{top5EntryId}
DELETE /users/me/top5/{type}/{top5EntryId}
```

Após qualquer alteração, recarregar o Top 5 da aba correspondente. O card é
recalculado pelo endpoint e não depende de um batch client-side.

### Abrir o detalhe de um conteúdo

Quando o usuário selecionar um card já carregado:

```http
GET /contents/{contentId}/details?posterUserId={userId}
```

Essa chamada pertence à tela de detalhe e não deve ser repetida no Profile se
o conteúdo já estiver presente no resultado batch.

## Ordem recomendada de carregamento

1. Validar a sessão autenticada.
2. Fazer `GET /users/{userId}`.
3. Se o cabeçalho retornar `403`, interromper o carregamento do perfil.
4. Disparar em paralelo o resumo e o Top 5 da aba selecionada.
5. Renderizar o cabeçalho e os cards entregues nas respostas.
6. Ao trocar de aba, repetir somente o resumo e o Top 5.

## Regras de erro

- `401`: renovar a sessão ou redirecionar para login.
- `403`: informar que o perfil é privado ou não pode ser visualizado; não
  tentar descobrir os dados chamando endpoints internos.
- `404`: informar que o usuário ou conteúdo não existe.
- `400`: corrigir `type` para `MOVIE` ou `SERIES`.
- Falha transitória do TMDB durante a montagem de um destaque: manter o
  resumo válido e omitir somente o destaque afetado.
- Falha de metadata: manter a referência mínima e usar `previewStatus`/placeholder;
  não criar referências de conteúdo durante a leitura.

---

# Month in Review

## Tela

**Month in Review** é dividida em **Movie Edition** e **TV Edition**. A aba
selecionada define o `type` enviado ao backend: `MOVIE` para filmes e `SERIES`
para estatísticas de TV agregadas a partir dos episódios assistidos.

O `userId` é o dono do resumo. A identidade autenticada continua sendo enviada
no cookie de sessão.

## Requisições para montar a tela

### 1. Cabeçalho do usuário

```http
GET /users/{userId}
```

Fazer uma única vez ao abrir a tela. Usar `name`, `username` e
`profilePicture` no cabeçalho.

### 2. Resumo do mês e da edição selecionada

```http
GET /users/{userId}/summary/month?type=MOVIE|SERIES&month=YYYY-MM
```

`type` e `month` são obrigatórios na prática; o backend responde `400` quando
um deles está ausente ou inválido. Ao trocar entre Movie Edition e TV Edition,
repetir a chamada com o novo `type`. Ao navegar para outro mês, repetir com o
novo `month`.

O resumo fornece:

- `watchCount` e `minutesWatched`;
- `ratingsDistribution`;
- `recentWatched`, `firstWatched` e `lastWatched`;
- `minutesPerDay` e `watchCountByDayOfWeek`;
- `genreCounts`;
- `topSeriesByWatchTime`, somente na TV Edition;
- `topLongestMovies`, somente na Movie Edition;
- `topRated`, `bottomRated` e `topWatchCompanions`.

`topWatchCompanions` já contém `UserPreviewDTO` e não exige chamada adicional.
`topSeriesByWatchTime` expõe `contentId`; `topLongestMovies` e os cards
baseados em `DiaryEntry` expõem `content.id`.

### 3. Cards visuais do resumo

`recentWatched`, `firstWatched`, `lastWatched`, `topRated`, `bottomRated`,
`topSeriesByWatchTime` e `topLongestMovieCards` já retornam cards aditivos
quando há uma linha visual. `topLongestMovies` permanece como referência
legada. Usar `previewStatus` para placeholder e não chamar `/contents/details`
ou `/contents/reference` para montar a edição.

## Mapeamento dos blocos da tela

| Bloco | Fonte | Requisição adicional |
|---|---|---|
| Cabeçalho | `GET /users/{userId}` | Nenhuma |
| Total assistido | `watchCount` e `minutesWatched` | Nenhuma |
| Rating distribution | `ratingsDistribution` | Nenhuma |
| Últimos conteúdos | `recentWatched[].card` | Nenhuma chamada adicional |
| Primeiro e último conteúdo | `firstWatched.card` e `lastWatched.card` | Nenhuma chamada adicional |
| Plays per day | `minutesPerDay` | Nenhuma |
| Dias da semana | `watchCountByDayOfWeek` | Nenhuma |
| Gêneros | `genreCounts` | Nenhuma |
| Séries mais assistidas | `topSeriesByWatchTime[].card` | Nenhuma chamada adicional |
| Filmes mais longos | `topLongestMovieCards` + `topLongestMovies` | Nenhuma chamada adicional |
| Maiores e menores notas | `topRated[].card` e `bottomRated[].card` | Nenhuma chamada adicional |
| Companheiros | `topWatchCompanions` | Nenhuma |

## Semântica das edições

- Movie Edition usa `MOVIE` para contagem, runtime, ratings e atividade.
- TV Edition usa `EPISODE` para contagem, runtime, ratings, minutos por dia e
  dias da semana.
- `genreCounts` da TV conta séries distintas com episódios registrados no mês.
- `topSeriesByWatchTime` agrega o runtime dos episódios por série pai.
- `topLongestMovies` só é preenchido na Movie Edition.
- Episódios e temporadas não aparecem em `recentWatched`.

## Ordem recomendada de carregamento

1. Validar a sessão autenticada.
2. Disparar em paralelo `GET /users/{userId}` e
   `GET /users/{userId}/summary/month`.
3. Renderizar números, gráficos e cards entregues pelo resumo.
4. Aplicar placeholder conforme `previewStatus`, sem remover o ranking.

## Regras de erro

- `401`: renovar a sessão ou redirecionar para login;
- `403`: informar que o resumo não pode ser visualizado;
- `404`: informar que o usuário não existe;
- `400`: corrigir `type` para `MOVIE` ou `SERIES` e `month` para `YYYY-MM`;
- falha de metadata: manter os dados estatísticos e usar placeholder no card;
- não criar referências de `Content` durante uma leitura.

---

# Year in Review

## Tela

**Year in Review** é dividida em **Movie Edition** e **TV Edition**. O ano
exibido no cabeçalho e nos gráficos é enviado no parâmetro `year`.

## Requisições para montar a tela

### 1. Cabeçalho do usuário

```http
GET /users/{userId}
```

Usar `name`, `username` e `profilePicture`. Fazer uma única vez ao abrir.

### 2. Resumo do ano e da edição selecionada

```http
GET /users/{userId}/summary/year?type=MOVIE|SERIES&year=YYYY
```

`type` e `year` são obrigatórios na prática. Fazer somente a chamada da aba
inicial e repetir ao trocar a edição ou o ano.

O resumo fornece `watchCount`, `minutesWatched`, as médias de consumo,
`ratingsDistribution`, `watchCountByMonth`, `watchCountByDayOfWeek`,
`firstWatched`, `lastWatched`, `longestWatched`, `genreCounts`, `topRated`,
`bottomRated` e `topWatchCompanions`.

`topWatchCompanions` já vem pronto com `UserPreviewDTO`. Não buscar os usuários
dos cards em outra rota.

### 3. Cards visuais do resumo

`firstWatched`, `lastWatched`, `topRated`, `bottomRated` e `longestWatched`
já podem carregar `card`. Usar os campos aditivos e preservar os rankings
quando `previewStatus` for `PARTIAL` ou `UNAVAILABLE`; não chamar
`/contents/details` para montar a edição.

## Mapeamento dos blocos da tela

| Bloco | Fonte | Requisição adicional |
|---|---|---|
| Cabeçalho | `GET /users/{userId}` | Nenhuma |
| Total e médias | `watchCount`, `minutesWatched`, `averageMinutesPer*` | Nenhuma |
| Gráfico mensal | `watchCountByMonth` | Nenhuma; preencher meses ausentes com zero |
| Dias da semana | `watchCountByDayOfWeek` | Nenhuma |
| Primeiro e último conteúdo | `firstWatched.card` e `lastWatched.card` | Nenhuma chamada adicional |
| Conteúdos de maior duração | `longestWatched[].card` | Nenhuma chamada adicional |
| Gêneros | `genreCounts` | Nenhuma |
| Maiores e menores notas | `topRated[].card` e `bottomRated[].card` | Nenhuma chamada adicional |
| Companheiros | `topWatchCompanions` | Nenhuma |

## Semântica das edições

- Movie Edition usa registros `MOVIE`.
- TV Edition usa registros `EPISODE` para quantidade, runtime, ratings e
  gráficos de consumo.
- `longestWatched` agrega os episódios por série pai na TV Edition.
- `genreCounts` da TV usa séries distintas, sem duplicar uma série por rewatch
  ou por múltiplos episódios.
- `topRated` e `bottomRated` seguem a regra de promoção do Top 5 e retornam
  entradas do tipo solicitado.

## Ordem recomendada de carregamento

1. Validar a sessão autenticada.
2. Disparar em paralelo `GET /users/{userId}` e
   `GET /users/{userId}/summary/year`.
3. Renderizar médias, gráficos, agregados e cards entregues.
4. Aplicar placeholder conforme `previewStatus`, sem remover o ranking.

## Regras de erro

- `401`: renovar a sessão ou redirecionar para login;
- `403`: informar que o resumo não pode ser visualizado;
- `404`: informar que o usuário não existe;
- `400`: corrigir `type` para `MOVIE` ou `SERIES` e `year` para `YYYY`;
- falha de metadata: manter a estatística e usar placeholder visual.

---

# All Time Stats

## Tela

**All Time Stats** possui duas edições: **Movie Edition** e **TV Edition**.
Para montar as abas, usar a rota específica por edição, não o endpoint
combinado legado.

## Requisições para montar a tela

### 1. Cabeçalho do usuário

```http
GET /users/{userId}
```

Usar `name`, `username` e `profilePicture` no cabeçalho.

### 2. Estatísticas da edição selecionada

```http
GET /users/{userId}/summary/all-time/edition?type=MOVIE|SERIES
```

`type` é obrigatório e aceita somente `MOVIE` ou `SERIES`. Fazer uma chamada
ao abrir a edição inicial e repetir ao trocar entre Movie Edition e TV Edition.

A resposta fornece `watchedCount`, `minutesWatched`, `totalTheaterVisits`,
as médias de consumo, `watchCountByYear`, `watchCountByDecade`,
`watchCountByCountry`, `mostLoggedContent`, `genreCounts`,
`ratingsDistribution`, `topRated` e `bottomRated`.

O endpoint combinado continua disponível para consumidores legados e também retorna
cards aditivos, mas a rota por edição é a recomendada para montar as abas:

```http
GET /users/{userId}/summary/all-time
```

### 3. Cards visuais dos conteúdos ranqueados

`mostLoggedContent`, `topRated` e `bottomRated` já podem trazer `card`. Usar
esses campos aditivos, mantendo `content` e os contadores legados. Não chamar
`/contents/details`, TMDB diretamente ou `POST /contents/reference` durante a
leitura.

## Mapeamento dos blocos da tela

| Bloco | Fonte | Requisição adicional |
|---|---|---|
| Cabeçalho | `GET /users/{userId}` | Nenhuma |
| Total assistido | `watchedCount` e `minutesWatched` | Nenhuma |
| Médias de consumo | `averageMinutesPer*` | Nenhuma |
| Assistido por ano | `watchCountByYear` | Nenhuma |
| Assistido por década | `watchCountByDecade` | Nenhuma |
| Assistido por país | `watchCountByCountry` | Nenhuma |
| Conteúdos mais registrados | `mostLoggedContent[].card` | Nenhuma chamada adicional |
| Gêneros | `genreCounts` | Nenhuma |
| Distribuição de ratings | `ratingsDistribution` | Nenhuma |
| Maiores e menores notas | `topRated[].card` e `bottomRated[].card` | Nenhuma chamada adicional |
| Visitas ao cinema | `totalTheaterVisits` | Nenhuma |

## Semântica das edições

- Movie Edition usa registros `MOVIE` para contagem, runtime, anos, ratings,
  gêneros e rankings.
- TV Edition usa registros `EPISODE` para contagem, runtime, anos e ratings.
- Na TV Edition, décadas, países, gêneros e `mostLoggedContent` são agregados
  por série pai; episódios da mesma série não criam vários títulos.
- `ratingsDistribution` usa a nota mais recente por unidade distinta. O
  cliente deve preencher visualmente scores ausentes com zero quando o gráfico
  exigir todas as posições de 1 a 10.
- `totalTheaterVisits` é a contagem all-time de filmes marcados com
  `watchedInTheater=true` e vem como zero na TV Edition.
- `topRated` e `bottomRated` retornam `DiaryEntry` do tipo da edição. Na TV
  Edition, ratings de episódios aparecem no histograma; esses rankings dependem
  de entradas `SERIES` existentes.

## Ordem recomendada de carregamento

1. Validar a sessão autenticada.
2. Disparar em paralelo `GET /users/{userId}` e
   `GET /users/{userId}/summary/all-time/edition`.
3. Renderizar números, gráficos, barras e cards entregues pelo resumo.
4. Ao trocar de edição, repetir a etapa 2 para o novo `type`.

## Regras de erro

- `401`: renovar a sessão ou redirecionar para login;
- `403`: informar que o resumo não pode ser visualizado;
- `404`: informar que o usuário ou algum conteúdo não existe;
- `400`: corrigir `type` para `MOVIE` ou `SERIES`;
- falha de metadata: manter os agregados e usar placeholder visual;
- não chamar o endpoint combinado legado apenas para montar uma das abas;
- não criar referências de `Content` durante uma leitura.

---

# Watchlist

## Requisição principal

```http
GET /users/{userId}/watchlist/view?type=&genre=&status=&sort=&direction=&page=&size=
```

`status` aceita `ALL`, `NEW`, `IN_PROGRESS` e `UPCOMING`. `sort` aceita
`DATE_ADDED`, `RELEASE_DATE`, `RUNTIME`, `RATING` e `TITLE`; `direction` assume
`DESC`. Filtros são aplicados antes da ordenação e da paginação.

Cada linha retorna `card`, `position`, `createdAt`, `releaseDate` e `status`.
`aggregate` reflete o conjunto filtrado completo, não somente a página, e traz
contagens por tipo, runtime total e upcoming. `TITLE`/`RATING` podem responder
`400` acima do limite de candidatos materializados. A leitura respeita a
visibilidade do perfil, não cria `Content`, não chama `/contents/details` e não
cacheia o envelope materializado.

# Calendar

## Requisição principal

```http
GET /users/me/calendar?month=YYYY-MM
```

O backend usa a região e o idioma do usuário autenticado e lê snapshots
compartilhados da agenda. Cada evento traz `releaseContext` opcional:

- `releaseTime`: somente horário de filme parseável na região preferida;
- `network`: somente nome não vazio e consensual da série;
- ausência ou conflito: `null`, sem valor fabricado.

Não existe parâmetro `granularity`. `eventType=SERIES`/`SEASON` significa que
há evidência completa para agrupar; `EPISODE` significa que a resposta precisa
ser granular. Episódios e temporadas normalmente não têm horário porque o TMDB
fornece apenas `air_date`. Snapshots podem ser atualizados durante a leitura,
mas o endpoint não cria referências `Content` nem chama `/contents/details`.

# TMDB Trending

## Requisições principais

```http
GET /trending?timeWindow=day|week&size=12|21
GET /trending/{type}?timeWindow=day|week&page=1&size=12|21
```

`/trending` retorna as duas seções; `/trending/{type}` continua somente uma
seção. `page` é a página externa do TMDB e aceita 1 a 500. Cada item é um
`TrendingCard`; `moviesPage` e `seriesPage` expõem `page`, `size`,
`totalPages`, `totalResults` e `hasNext`.

TMDB é a fonte da ordenação. `popularity` e `tmdbVoteAverage` são métricas
externas e não devem ser chamadas de estatísticas Watchwise. O cache distingue
tipo, janela, página e idioma. `viewerState` é resolvido depois do cache e não
é compartilhado entre viewers.

# Social/Feed

## Requisição principal

```http
GET /feed?cursor=&size=
```

`FeedItemView` preserva todos os campos legados e adiciona `card` para o
conteúdo principal, `top5Preview.cards`, `pickTargetCards`,
`followingCount` e `followedUsersPreview`. `DAILY_GAME_RESULT` continua sem
card de conteúdo.

O backend primeiro mescla as fontes, aplica o cursor, ordena e seleciona a
página final; só depois carrega likes, comentários, previews sociais e cards.
Portanto, candidatos descartados não geram chamada de metadata. Coordenadas
repetidas são resolvidas uma vez; arrays aninhados preservam posições com
placeholders nulos. Estado de viewer não entra no cache compartilhado de cards.

---

# Content Detail — Movie, Series, Season e Episode

As quatro telas seguem o mesmo fluxo visual: detalhe do TMDB, nota e
estatísticas, estado do usuário, ações de acompanhamento, reviews e
comentários. A requisição principal é a página agregada; o cliente não deve
montar a tela fazendo uma chamada de detalhes e uma chamada de stats para cada
card filho.

## Regra do `contentId`

`contentId` é o UUID interno de `Content`, não o ID do TMDB. Quando o usuário
chega de um card do Watchwise, o UUID já vem no item e a tela pode chamar
diretamente a página. Quando chega de busca ou descoberta do TMDB e ainda não
há UUID, criar primeiro a referência mínima:

```http
POST /contents/reference
```

Bodies válidos:

```json
{ "type": "MOVIE", "tmdbId": "550" }
```

```json
{ "type": "SERIES", "tmdbId": "1396" }
```

```json
{ "type": "SEASON", "seriesTmdbId": "1396", "seasonNumber": 5 }
```

```json
{
  "type": "EPISODE",
  "seriesTmdbId": "1396",
  "seasonNumber": 5,
  "episodeNumber": 16
}
```

Não enviar metadados que o cliente ainda não possui nessa primeira chamada.
Guardar o `id` retornado e usá-lo nas requisições da tela. A operação é
idempotente.

## Requisição principal comum

```http
GET /contents/{contentId}/page
```

A chamada é autenticada e substitui, para o carregamento inicial da tela:

```http
GET /contents/{contentId}/details
GET /contents/{contentId}/stats
```

A resposta contém:

- `details`: título, sinopse, imagens, runtime, gêneros, elenco, equipe,
  vídeos, temporadas ou episódios próprios do tipo;
- `metadata`: idioma original, certificação, homepage, URLs do TMDB/IMDb e
  providers filtrados pela região do usuário;
- `stats`: média, quantidade de ratings, distribuição de notas de 1 a 10,
  plays e comentários;
- `visibleReviewsCount`: quantidade de reviews que o viewer pode visualizar;
- `viewerState`: nota/review do viewer, último watch, plays, watchlist,
  dropped, listas e progresso agregado quando aplicável;
- `sections`: cards de temporadas, episódios e episódios recentes;
- `navigation`: anterior/próximo para episódio quando a temporada pai permite
  determinar os limites.

Os cards de `sections` já trazem `contentId` quando a referência local existe,
além de coordenada TMDB, título, poster/still, data, runtime, stats e
`viewerState`. Se o filho ainda não foi referenciado, `contentId` é `null`, as
stats são zeradas e o estado do viewer é vazio. A leitura não cria referências
de pais ou filhos e não faz uma chamada TMDB por card.

### Conteúdo das seções por tipo

| Tela | `sections` | `navigation` |
|---|---|---|
| Movie | Todas as listas vazias | `null` |
| Series | `seasons` e `recentEpisodes`; `episodes` vazia | `null` |
| Season | `episodes`; `seasons` somente se a série pai tiver referência local | `null` |
| Episode | As três listas vazias | Anterior/próximo somente com temporada pai e limite confiável |

Em Episode, usar `metadata.presentationPosterPath` para a imagem principal:
poster da temporada pai quando disponível, com fallback para o still do
episódio. `metadata.presentationCrew` é a equipe herdada da série e
`crewInherited` indica essa origem; `details.posterPath` e `details.crew`
continuam sendo o contrato genérico.

## Reviews e comentários

Reviews são DiaryEntry ou DroppedEntry com texto. Não existe endpoint separado
para criar review: criar ou editar um registro de diário com `comment`.

```http
GET /contents/{contentId}/reviews?page=1&size=20&sort=RECENT
```

`sort` aceita `POPULAR`, `RECENT` e `FOLLOWING`; o padrão é `RECENT`. A
resposta é paginada, pode misturar reviews de diário e dropped, inclui
`reviewer`, contadores sociais e respeita a visibilidade do autor.

```http
GET /contents/{contentId}/comments?page=1&size=20
```

Para criar comentário fora de uma review:

```http
POST /contents/{contentId}/comments
```

```json
{
  "text": "comentário",
  "parentCommentId": null,
  "containsSpoiler": false
}
```

Para uma review específica, usar:

```http
GET /diary/{diaryEntryId}/comments
POST /diary/{diaryEntryId}/comments
POST /diary/{diaryEntryId}/like
DELETE /diary/{diaryEntryId}/like
```

Para comentários da própria tela:

```http
POST /comments/{commentId}/like
DELETE /comments/{commentId}/like
DELETE /comments/{commentId}
```

## Movie

### Leitura

1. Criar `MOVIE` em `/contents/reference` se ainda não houver `contentId`.
2. Chamar `GET /contents/{contentId}/page`.
3. Renderizar `details`, `metadata`, `stats`, `viewerState`, reviews e
   comentários com as requisições comuns acima.

### Marcar como visto, avaliar ou revisar

```http
POST /diary
```

```json
{
  "content": {
    "type": "MOVIE",
    "tmdbId": "550",
    "runtimeMinutes": 139,
    "genres": ["Drama"],
    "releaseYear": 1999,
    "countries": ["US"]
  },
  "score": 9,
  "comment": "texto da review",
  "watchedDate": "2026-09-01",
  "watchedInTheater": false,
  "isRewatch": false,
  "watchedWith": ["<uuid do amigo>"]
}
```

`score`, `comment`, `watchedDate`, `watchedInTheater`, `isRewatch` e
`watchedWith` são opcionais. `runtimeMinutes`, `genres`, `releaseYear` e
`countries` devem ser preenchidos com os valores de `details` quando
disponíveis, pois o backend não busca esses campos automaticamente nessa ação.

```http
PATCH /diary/{diaryEntryId}
DELETE /diary/{diaryEntryId}?overrideProtectedEntries=false
```

### Lista, watchlist e dropped

```http
GET /users/{userId}/lists?contentId={contentId}
POST /lists/{listId}/items
DELETE /lists/{listId}/items/{itemId}
```

```json
{
  "content": { "type": "MOVIE", "tmdbId": "550" },
  "position": null,
  "description": null,
  "customPosterUrl": null
}
```

```http
POST /users/me/watchlist/MOVIE
DELETE /users/me/watchlist/MOVIE/{watchlistEntryId}
POST /users/me/dropped/MOVIE/{tmdbId}
DELETE /users/me/dropped/MOVIE/{tmdbId}
```

Body opcional do dropped:

```json
{ "comment": "Não me prendeu" }
```

## Series

### Leitura

1. Criar `SERIES` em `/contents/reference` se necessário.
2. Chamar `GET /contents/{contentId}/page`.
3. Usar `sections.seasons` para a grade de temporadas e
   `sections.recentEpisodes` para os episódios recentes. Não chamar detalhes
   ou stats individualmente para esses cards.

### Marcar a série inteira como vista

```http
POST /diary/bulk
```

```json
{
  "content": { "type": "SERIES", "tmdbId": "1396" },
  "watchedDate": "2026-09-01",
  "finaleSeasonNumber": 5,
  "seasonFinaleEpisodeNumbers": { "1": 7, "2": 13 },
  "watchedWith": ["<uuid do amigo>"]
}
```

Os overrides de finale são opcionais; quando omitidos, o backend deriva os
limites no TMDB. Para editar/remover um registro gerado:

```http
PATCH /diary/{diaryEntryId}
GET /diary/{diaryEntryId}/deletion-impact?overrideProtectedEntries=false
DELETE /diary/{diaryEntryId}?overrideProtectedEntries=false
DELETE /diary/series/{seriesTmdbId}
```

### Lista, watchlist e dropped

Usar o mesmo fluxo de Movie, trocando o tipo e a rota:

```http
GET /users/{userId}/lists?contentId={contentId}
POST /lists/{listId}/items
DELETE /lists/{listId}/items/{itemId}
POST /users/me/watchlist/SERIES
DELETE /users/me/watchlist/SERIES/{watchlistEntryId}
POST /users/me/dropped/SERIES/{tmdbId}
DELETE /users/me/dropped/SERIES/{tmdbId}
```

## Season

### Leitura

1. Criar a referência composta `SEASON` se necessário.
2. Chamar `GET /contents/{contentId}/page`.
3. Renderizar `sections.episodes`. Cada card já traz poster/still, runtime,
   stats e estado do viewer. `sections.seasons` somente existe quando a série
   pai já está referenciada.

Não chamar `/contents/details` ou `/contents/stats` por episódio no carregamento
da tela. Se o usuário abrir um episódio, navegar para a página desse episódio
usando o `contentId` do card quando ele não for `null`; se for `null`, criar a
referência EPISODE primeiro.

### Marcar a temporada inteira como vista

```http
POST /diary/bulk
```

```json
{
  "content": {
    "type": "SEASON",
    "seriesTmdbId": "1396",
    "seasonNumber": 5,
    "isSeriesFinale": true
  },
  "watchedDate": "2026-09-01",
  "finaleEpisodeNumber": 13,
  "episodeRuntimeMinutes": { "1": 42, "2": 55, "3": 48 },
  "watchedWith": ["<uuid do amigo>"]
}
```

`finaleEpisodeNumber` e `episodeRuntimeMinutes` são opcionais. A temporada
não possui watchlist ou dropped próprios; esses estados pertencem a Movie e
Series. A temporada pode ser adicionada a uma lista:

```http
GET /users/{userId}/lists?contentId={contentId}
POST /lists/{listId}/items
DELETE /lists/{listId}/items/{itemId}
```

## Episode

### Leitura

1. Criar a referência composta `EPISODE` se necessário.
2. Chamar `GET /contents/{contentId}/page`.
3. Renderizar o detalhe raiz, `metadata.presentationPosterPath`,
   `metadata.presentationCrew` e `navigation.previousEpisode`/
   `navigation.nextEpisode` quando existirem.

O botão de trocar de episódio usa as coordenadas retornadas em `navigation`.
Não fazer uma requisição adicional por episódio adjacente; o card já traz
stats e estado do viewer. Se uma navegação levar a um card sem `contentId`,
criar a referência composta correspondente antes de abrir a nova página.

### Marcar como visto, avaliar ou revisar

```http
POST /diary
```

```json
{
  "content": {
    "type": "EPISODE",
    "seriesTmdbId": "1396",
    "seasonNumber": 5,
    "episodeNumber": 16,
    "runtimeMinutes": 55,
    "isSeasonFinale": true,
    "isSeriesFinale": true
  },
  "score": 10,
  "comment": "texto da review",
  "watchedDate": "2026-09-01",
  "isRewatch": false,
  "watchedWith": ["<uuid do amigo>"]
}
```

`runtimeMinutes`, `isSeasonFinale` e `isSeriesFinale` são opcionais. Para
editar/remover o registro, usar as mesmas rotas de Diary:

```http
PATCH /diary/{diaryEntryId}
GET /diary/{diaryEntryId}/deletion-impact?overrideProtectedEntries=false
DELETE /diary/{diaryEntryId}?overrideProtectedEntries=false
```

### Lista

```http
GET /users/{userId}/lists?contentId={contentId}
POST /lists/{listId}/items
DELETE /lists/{listId}/items/{itemId}
```

Season e Episode não usam as rotas de watchlist ou dropped; a tela deve
mostrar essas ações somente para Movie e Series.

## Ordem recomendada de carregamento

1. Validar a sessão autenticada.
2. Se necessário, criar a referência mínima e guardar o `contentId`.
3. Disparar `GET /contents/{contentId}/page`.
4. Renderizar o cabeçalho e as informações de `details`, `metadata`, `stats` e
   `viewerState`.
5. Renderizar as seções e a navegação já enriquecidas pelo backend.
6. Buscar reviews e comentários em paralelo, somente quando suas áreas forem
   exibidas ou quando o carregamento inicial da tela exigir essas listas.
7. Após uma ação de escrita, atualizar o estado com a resposta da ação ou
   recarregar a página agregada; não criar uma referência adicional apenas
   para atualizar a tela.

## Requisições que não devem ser feitas no carregamento

- `GET /contents/{contentId}/details` e `GET /contents/{contentId}/stats`
  junto com `/page` para o mesmo conteúdo;
- uma chamada de TMDB por temporada, episódio ou card filho;
- `/contents/details` ou `/contents/stats` para enriquecer os cards retornados
  em `sections` ou `navigation`;
- `POST /contents/reference` para pais ou filhos durante a leitura;
- watchlist/dropped para Season ou Episode;
- endpoint separado de criação de review.

## Regras de erro

- `400`: UUID, enum `sort`, paginação ou body inválido;
- `401`: sessão ausente ou expirada;
- `403`: ação social ou lista não permitida para o viewer;
- `404`: conteúdo, usuário, lista ou entrada inexistente;
- `409`: referência/ação duplicada ou metadado conflitante;
- `502`: TMDB indisponível durante a composição da página ou uma operação que
  depende de validação externa.

---

# My Lists

## Tela

A tela exibe as listas criadas pelo usuário, as listas curtidas e os filtros de conteúdo da
interface. Cada linha é um card visual de lista, não uma lista de conteúdos montada no cliente.
O card pode mostrar proprietário, avatar, descrição, visibilidade, contadores, estado de curtida,
escopo da lista, até cinco cards de conteúdo e até cinco previews de listas aninhadas.

## Requisição principal

```http
GET /users/me/lists/view?source=ALL&page=1&size=10
```

`source` aceita `OWNED`, `LIKED` ou `ALL` e, quando omitido, usa `ALL`. Os filtros opcionais são:

- `itemType`: `MOVIE`, `SERIES`, `SEASON`, `EPISODE` ou `LIST`;
- `genre`: gênero do conteúdo direto da lista;
- `sortBy`: `name`, `likesCount`, `itemsCount`, `commentsCount`, `updatedAt` ou `rank`;
- `sortDirection`: `asc` ou `desc`.

`updatedAt` e `rank` só são válidos quando `source=OWNED`. Sem `sortBy`, `OWNED` usa rank
crescente, `LIKED` usa a curtida mais recente e `ALL` usa a atualização mais recente. A união
`ALL` é deduplicada quando uma lista é própria e também curtida pelo usuário.

`itemType=LIST` identifica itens com `childListId`. MOVIE/SERIES usam os gêneros da própria
referência; SEASON/EPISODE herdam o gênero da referência SERIES correspondente por `seriesTmdbId`.
LIST não possui gênero e não percorre listas filhas; combinar `itemType=LIST` com `genre` retorna
zero listas. A referência SERIES pai é garantida quando uma temporada ou episódio é registrado pela
primeira vez.

`page` é 1-based (0 também representa a primeira página); `size` omitido ou acima de 10 resulta
em 10, e valores de 1 a 9 retornam 400. A resposta é `PageResponseDTO<UserListCardDTO>`.

## Dados usados no card

`UserListCardDTO` entrega:

- `user` com id, username, nome, avatar e `isProfilePublic`;
- nome, descrição, visibilidade, datas de criação/atualização e `rank`;
- `likesCount`, `commentsCount`, `itemsCount`, `likedByMe`, `ownedByMe` e `itemScope`;
- até cinco `previewCards` de conteúdo, na ordem original dos itens;
- até cinco `nestedListPreviews` visíveis ao viewer;
- `nestedListsCount` para indicar que existem listas aninhadas além do preview.

Os `previewCards` usam `ContentCard` com `previewStatus` `AVAILABLE`, `PARTIAL` ou `UNAVAILABLE`.
Uma falha de metadata do TMDB não remove a lista da página: o card permanece com a coordenada
conhecida e o status correspondente. O endpoint não retorna `watchedPercentage` nem
`totalRuntimeMinutes`; para progresso, abrir `GET /lists/{listId}/progress`.

## Visibilidade e performance

O servidor aplica filtros, visibilidade, ordenação e paginação antes de montar os previews. O
proprietário sempre pode ver suas listas; outros usuários só veem listas conforme `PUBLIC` ou
`FOLLOWERS` e a regra de visibilidade do perfil quando a rota recebe `userId`. Previews de listas
aninhadas privadas ou não acessíveis são omitidos antes do limite de cinco.

O cliente não deve chamar `GET /contents/details?ids=...` para cada lista, nem fazer paginação ou
filtragem local. Os cards são resolvidos em lote, deduplicados por coordenada de conteúdo e montados
com metadata básica. A tela também não deve usar a rota legada apenas para obter título ou pôster.

## Ações iniciadas pela tela

- Criar uma lista: usar a seção Create List abaixo.
- Abrir uma lista: `GET /lists/{listId}`.
- Ver progresso: `GET /lists/{listId}/progress`.
- Curtir ou remover curtida: `POST`/`DELETE /lists/{listId}/like`.
- Abrir comentários: `GET /lists/{listId}/comments`.
- Editar, reordenar ou excluir uma lista própria: `PATCH`/`DELETE /lists/{listId}`.

## Paginação, carregamento e erros

Carregar a primeira página com `source=ALL` e preservar os parâmetros na URL. Alterar source,
filtro ou ordenação reinicia em `page=1`; avançar ou voltar página não deve refazer buscas de
metadata já resolvidas em uma resposta anterior do cliente.

- `400`: source, itemType, genre, sortBy, sortDirection, page, size ou combinação inválida;
- `401`: viewer não autenticado;
- `403`: acesso ao recurso ou ao perfil bloqueado;
- `404`: usuário referenciado não existe.

---

# Community Lists

## Tela

A aba Community Lists permite pesquisar e ordenar listas públicas da comunidade. O resultado usa
os mesmos cards visuais de My Lists, mas mostra o proprietário e mantém apenas listas públicas na
descoberta.

## Requisições principais

```http
GET /lists/discover/view?page=1&size=10
GET /users/{userId}/lists/view?page=1&size=10
```

`/lists/discover/view` aceita:

- `q` opcional para pesquisar nome;
- `itemType` e `genre`;
- `sortBy=name|likesCount|itemsCount|commentsCount`;
- `sortDirection=asc|desc`;
- `page` e `size`.

`q` ausente ou formado apenas por espaços não filtra. Espaços externos são removidos, o limite de
100 caracteres é aplicado depois do trim e `%`, `_` e barra invertida são tratados literalmente.
Sem `sortBy`, a descoberta ordena por curtidas decrescentes, criação mais recente e UUID como
desempate determinístico. Somente listas `PUBLIC` são elegíveis.

`/users/{userId}/lists/view` usa os mesmos filtros e ordenações, sem `source` nem `q`. Primeiro
aplica a barreira de visibilidade do perfil; depois aplica `PUBLIC`, `FOLLOWERS` ou `PRIVATE` em
cada lista. `source` e `owner`, quando enviados, são parâmetros desconhecidos ignorados pelo
Spring MVC, não filtros e não motivos de resposta `400`.

## Dados e ações

Paginação e campos dos cards seguem My Lists, incluindo owner/avatar, contadores, previews limitados,
`likedByMe`, `ownedByMe` e estados `PARTIAL`/`UNAVAILABLE`. A tela pode abrir a lista, curtir,
comentar e, quando o viewer for o dono, editar ou excluir. As rotas `/view` são modelos aditivos:
as rotas legadas `GET /users/{userId}/lists`, `GET /lists/discover` e `GET /lists/{listId}`
mantêm o contrato atual.

## Erros

- `400`: filtro, ordenação ou paginação inválida;
- `401`: viewer não autenticado;
- `403`: perfil privado sem relação de acesso aceita;
- `404`: usuário não encontrado na rota contextual.

---

# Create List

## Tela

A tela permite informar nome, descrição, visibilidade e os itens da lista. O controle de tipo
`Movies & series`, `Seasons` ou `Episodes` serve apenas para limitar a busca e a seleção durante
o preenchimento; não é salvo como propriedade de `UserList`.

## Dados do formulário

- `name`: obrigatório, entre 1 e 255 caracteres;
- `description`: opcional, até 400 caracteres;
- `visibility`: `PUBLIC`, `FOLLOWERS` ou `PRIVATE`; o default é `PUBLIC`;
- itens de conteúdo: entre 1 e 100 no fluxo bulk, na ordem apresentada pelo usuário.

`UserList` não mistura itens de conteúdo com listas aninhadas. O formulário de filmes, séries,
temporadas e episódios cria uma lista de conteúdo; uma lista de listas é montada depois com
`POST /lists/{listId}/items` usando `childListId`.

## Busca e seleção de conteúdos

Para filmes e séries, usar a busca autenticada do TMDB:

```http
GET /search?q={texto}&type=MOVIE&page=1
GET /search?q={texto}&type=SERIES&page=1
```

`q` exige pelo menos três caracteres. A busca retorna resultados de TMDB e não cria referência
local. A tela deve manter a seleção no estado local até o envio do formulário.

Não há busca específica para temporadas e episódios. Depois de escolher uma série, carregar:

```http
GET /contents/selection/series/{seriesTmdbId}/seasons?includeSpecials=false
GET /contents/selection/series/{seriesTmdbId}/seasons/{seasonNumber}/episodes?includeUnreleased=true
```

`seasonNumber=0` é válido para Specials. Specials ficam ocultos por padrão; episódios futuros ou
sem data são incluídos por padrão na seleção e podem ser ocultados com `includeUnreleased=false`.
`posterUrl` e `stillUrl` já são URLs completas ou `null`. Essas rotas são somente leitura e não
criam referências `Content`.

## Persistência

Para criar a lista vazia:

```http
POST /users/me/lists
Content-Type: application/json

{
  "name": "Time Travel Essentials",
  "description": "Paradoxes, loops and DeLoreans.",
  "visibility": "PUBLIC"
}
```

Para criar a lista já com os itens selecionados, enviar tudo em uma única operação:

```http
POST /users/me/lists/bulk
Content-Type: application/json

{
  "name": "Time Travel Essentials",
  "visibility": "PUBLIC",
  "items": [
    { "type": "MOVIE", "tmdbId": "105" },
    { "type": "SERIES", "tmdbId": "1396" },
    { "type": "SEASON", "seriesTmdbId": "1396", "seasonNumber": 1 },
    { "type": "EPISODE", "seriesTmdbId": "1396", "seasonNumber": 1, "episodeNumber": 1 }
  ]
}
```

O bulk preserva a ordem enviada, é tudo-ou-nada e usa o mesmo registro idempotente de
`POST /contents/reference`. Ao registrar uma temporada ou episódio pela primeira vez, o backend
também garante a referência global `SERIES` necessária para filtros de gênero posteriores, sem
adicionar o pai como item da lista.

O retorno `201` de criação é o modelo bruto da lista, não um `UserListCardDTO` completo. Para a
prévia visual da lista criada, o cliente pode manter os resultados de busca/seleção que já possui
ou recarregar `GET /users/me/lists/view?source=OWNED`; não deve chamar `/contents/details` para cada
item.

Importação de listas não possui endpoint implementado e permanece escopo futuro.

## Ações, carregamento e erros

O botão de criação deve enviar uma única requisição bulk quando houver itens. Para cancelar, não
há chamada ao backend. Após `201`, navegar para a lista ou atualizar My Lists; não repetir o bulk
automaticamente em caso de timeout sem antes confirmar o resultado, porque o cadastro de referências
é idempotente, mas a criação da lista não deve ser duplicada pelo cliente.

- `400`: nome, descrição, visibilidade, item ou coordenada de seleção inválidos; lista vazia ou
  com mais de 100 itens;
- `401`: usuário não autenticado;
- `403`: cookie CSRF ausente ou inválido;
- `404`: conteúdo não encontrado no TMDB;
- `409`: conteúdo duplicado no payload ou conflito de integridade;
- `502`: TMDB indisponível ao validar uma referência nova.

---

# List

## Tela

A tela de detalhe exibe o cabeçalho da lista, a descrição, os indicadores de itens, duração,
curtidas e comentários, a barra de filtros/ordenação e uma grade paginada de cards. O cliente deve
tratar a resposta como um read model visual (modelo pronto para apresentação), sem remontar cards a
partir de referências brutas.

O endpoint visual não retorna o proprietário da lista nem uma imagem de hero. Portanto, o nome/avatar
do proprietário e o fundo cinematográfico vistos no protótipo não podem ser preenchidos por
`GET /lists/{listId}/view` no contrato atual; o frontend deve usar um contexto que já conheça o dono
ou esses campos precisam ser adicionados ao backend antes de serem tratados como dados garantidos.

## Requisição principal

O detalhe da lista usa `GET /lists/{listId}/view`, com filtros opcionais `type`, `genre` e
`watchStatus`, ordenação `sortBy`/`sortDirection` e paginação `page`/`size`. `watchStatus` aceita
`WATCHED`, `PARTIALLY_WATCHED`, `UNWATCHED` e `UNKNOWN`; não deve ser enviado para listas de listas.
O backend filtra e ordena antes de paginar. O modo de leitura é interno e não deve ser enviado como
parâmetro. O tamanho padrão da página é 20 e o limite máximo é 30 nos escopos normais; no escopo
`EPISODE`, o limite máximo é 24. O cliente pode aplicar fade como apresentação visual, mas esse efeito
não altera o conjunto retornado; quando `watchStatus` é enviado, o servidor filtra os itens antes da
paginação.

Exemplo para a primeira página da lista:

```http
GET /lists/{listId}/view?type=MOVIE&sortBy=position&sortDirection=asc&page=1&size=20
```

`sortBy` aceita `position`, `dateAdded`, `duration`, `episodeAvgRating`,
`globalEpisodeAvgRating` e `contentAvgRating`. A rota legada `GET /lists/{listId}` continua
disponível para compatibilidade, mas não deve ser usada para buscar títulos e posters em lote.

## Dados da resposta

O objeto de topo retorna `id`, `name`, `description`, `visibility`, `createdAt`, `updatedAt`,
`watchedPercentage`, `likesCount`, `likedByMe`, `itemsCount`, `commentsCount`,
`totalRuntimeMinutes`, `rank`, `itemScope` e a página `items`. Cada item mantém `id`, `position`,
`description`, datas, `customPosterUrl`, `contentState` e as médias de avaliação aplicáveis.

Cada item visual apresenta exatamente um `card` de conteúdo ou uma `nestedList` visível. Usar os
campos do `ContentCard`, inclusive `previewStatus`: manter placeholders para `PARTIAL` e
`UNAVAILABLE`, sem remover ou reordenar o item quando o TMDB falhar.

`nestedList` inclui proprietário, nome, descrição, visibilidade, datas, `itemScope`, contagens e
até cinco `previewCards`. O preview não expande uma segunda geração de listas aninhadas.

## Filtros, paginação e performance

`type`, `genre` e `watchStatus` são aplicados antes da ordenação e da paginação. `genre` de
`SEASON`/`EPISODE` é herdado da série associada no backend. O tamanho padrão é 20, o máximo é 30
para os escopos normais e 24 para `EPISODE`; o cliente deve respeitar `page`, `size`, `totalPages`,
`totalElements` e `hasNext` retornados.

O cliente não deve chamar `GET /contents/details?ids=...`, fazer paginação local ou buscar cada
card individualmente. Os cards da página e dos previews são deduplicados e montados em lote pelo
backend. O fade usado no protótipo é somente apresentação do cliente e não substitui o filtro
`watchStatus`.

## Ações e erros

- Abrir comentários: `GET /lists/{listId}/comments?page=1&size=10`;
- Criar comentário: `POST /lists/{listId}/comments`;
- Curtir/remover curtida: `POST`/`DELETE /lists/{listId}/like`;
- Alterar itens, somente para o dono: `POST`/`PATCH`/`DELETE /lists/{listId}/items...`;
- Editar ou excluir a lista, somente para o dono: `PATCH`/`DELETE /lists/{listId}`.

- `400`: UUID, tipo, filtro, ordenação, página, tamanho ou combinação inválida;
- `401`: usuário não autenticado;
- `403`: lista FOLLOWERS sem seguimento aceito, lista PRIVATE de outro usuário, nested list não
  visível ou nota do dono privada;
- `404`: lista não encontrada.

---

# Episodes List

## Tela

A tela é a variação do detalhe de lista para uma coleção de episódios. Ela usa cards de episódio em
grade, preserva a posição original e pode exibir data de lançamento, título da série, temporada,
episódio, duração, nota e estado de assistência presentes no `ContentCard` e no `contentState`.

Para listas no escopo de episódios, usar o mesmo `GET /lists/{listId}/view`, com `type=EPISODE`,
filtros e paginação. O gênero de episódios e temporadas é herdado da série associada no backend;
não é necessário buscar detalhes de cada item nem chamar `/contents/details` por episódio. Considerar
o tamanho padrão de 20 e o limite efetivo de 24 itens por página nesse escopo, além dos estados de
assistência em `watchStatus`.

## Regras específicas

- aplicar `type=EPISODE` para a aba/escopo de episódios;
- usar `watchStatus` para filtrar antes da paginação;
- manter itens `PARTIAL` e `UNAVAILABLE` como placeholders na posição original;
- usar `genre` herdado da série, sem buscar uma série individualmente por episódio;
- usar o mesmo endpoint de comentários, curtidas e paginação descrito em List.

O backend não entrega um campo de proprietário ou hero específico nessa resposta. Também não há
necessidade de chamar `/contents/details` por episódio.

---

# List of Lists

## Tela

A tela exibe uma lista cujo conteúdo são outras listas, em linhas ou cards de listas. Cada item deve
mostrar o proprietário, nome, descrição, visibilidade, contagens e até cinco posters de preview da
lista filha, preservando a posição do item pai.

Para uma lista que contém outras listas, usar `GET /lists/{listId}/view` sem `watchStatus`. O detalhe
da lista filha já inclui proprietário, visibilidade, contagens e até cinco cards de preview. Exibir
somente filhos retornados pelo servidor; não expandir previews nem solicitar recursivamente o
conteúdo de cada filho como parte da mesma tela. A tentativa de filtrar por estado de assistência
nesse escopo resulta em `400`.

## Visibilidade e ações

O servidor remove filhos inacessíveis antes da montagem visual. Não exibir metadata de uma lista
PRIVATE, de uma lista FOLLOWERS sem seguimento aceito ou de uma lista que deixou de ser visível.
O limite de cinco cards vale por lista filha e não deve ser usado para fazer uma segunda requisição
recursiva.

Comentários e curtidas são carregados separadamente por `/lists/{listId}/comments` e
`/lists/{listId}/like`. O cliente pode abrir uma lista filha com `GET /lists/{childListId}/view`.

---

# List Progress

## Tela

A tela mostra o progresso de uma lista de conteúdo para o viewer autenticado: total de itens,
percentual assistido, duração total e, nas listas de séries, as linhas de série/temporada e o próximo
episódio quando disponível. A resposta não calcula o progresso do dono da lista; sempre usa o usuário
autenticado que está visualizando.

## Requisição principal

Usar `GET /lists/{listId}/progress`, calculado para o viewer autenticado. Exibir os agregados gerais,
`movieProgress` e `seriesProgressAggregate`; o agregado de filmes cobre somente filmes, e os itens
MOVIE/EPISODE não recebem estado individual no payload. `remainingRuntimeMinutes` do agregado de
séries pode ser `null` quando pelo menos uma série não tem runtime restante conhecido, e a interface
deve representar esse valor como indisponível, sem convertê-lo em zero. O endpoint retorna `400`
para listas de listas. A ordenação dos itens permanece a ordem original da lista.

## Dados por tipo

- `totalItems`, `watchedItems` e `watchedPercentage` são os agregados gerais da lista;
- `movieProgress` agrega somente filmes, com itens assistidos, duração assistida e duração restante;
- `seriesProgressAggregate` agrega séries únicas e pode ter `remainingRuntimeMinutes: null`;
- cada `UserListProgressItem` mantém `content`, `position`, descrição, datas e poster customizado;
- `seriesProgress` aparece para itens `SERIES` e pode trazer temporadas, título, poster, próximo
  episódio e runtime;
- `seasonProgress` aparece para itens `SEASON`;
- itens `MOVIE` e `EPISODE` não recebem progresso individual nem `contentState` nesse endpoint.

O backend atual não entrega `ContentCard` para filmes e episódios nessa resposta. Se a interface
precisar de poster/título visual desses itens, isso deve ser tratado como uma evolução do read model;
o cliente não deve fazer um batch de `/contents/details` por conta própria sem uma decisão de contrato.

## Ações, carregamento e erros

Carregar os itens na ordem retornada e usar os agregados sem recalcular o percentual no cliente.
Comentários e curtidas continuam sendo carregados por `/lists/{listId}/comments` e
`/lists/{listId}/like`. Para abrir ou marcar um conteúdo, usar o `contentId` da referência e os
fluxos existentes de Content/Diary, sem alterar a ordem da lista.

- `400`: `listId` inválido ou lista de listas não aceita em progresso;
- `401`: usuário não autenticado;
- `403`: lista FOLLOWERS sem seguimento aceito ou lista PRIVATE de outro usuário;
- `404`: lista não encontrada;
- `502`: TMDB indisponível ao hidratar um snapshot frio de progresso.

---

# Picks Explore, My Picks, Template Detail, Answer Template e Pick Detail

## Tela

A tela de Picks possui as abas Explore picks e My picks. O hero, o título Picks e a imagem de fundo
são apresentação do cliente; as listas abaixo são paginadas pelo backend. A aba Explore apresenta
seções ordenadas por popularidade semanal, quantidade de Picks e recência. A aba My picks apresenta
os Picks do viewer e os templates criados por ele.

## Explore templates

Usar `GET /picks-templates?origin={origin}&name={name}&sort={sort}&page={page}&size={size}` para
explorar templates. `origin` e `name` são filtros opcionais; `sort` aceita `POPULAR_WEEK`,
`MOST_PICKED` ou `RECENT`. Renderizar os metadados de paginação e os previews com criador/avatar,
origem, capa, contagens de Picks/categorias/likes/comentários, estado de like do viewer e quantidade
e ID do Pick mais recente do viewer. `POPULAR_WEEK` considera somente atividade positiva nos últimos
sete dias e pode retornar menos resultados que o tamanho solicitado.

## Create Picks Template

### Tela

O wizard de criação possui quatro etapas client-side: informações gerais, categorias, opções fixas e
revisão. O cliente mantém os resultados visuais de busca no estado local e cria o template em uma
única chamada. Não existe draft persistido.

O template estrutural atual é lido por `GET /picks-templates/{templateId}`. Opções persistidas e
buscas de categorias `OPEN` recebem previews sob demanda por
`GET /picks-templates/{templateId}/categories/{categoryId}/options/view`; essa rota pagina e
enriquece somente a página solicitada. A criação do template continua sendo uma única chamada
`POST /picks-templates`.

O wizard de criação mantém no estado local os alvos selecionados e envia um único
`POST /picks-templates` com os IDs TMDB brutos e as opções aninhadas nas categorias. Para buscar
filmes, séries e pessoas, usar `GET /search` com `type=MOVIE`, `SERIES` ou `PERSON`; depois de escolher
uma série, usar as rotas de seleção contextual de temporadas e episódios descritas em
“Busca e seleção de conteúdos”. Não persistir drafts nem criar uma busca genérica de alvos. O par de
datas de elegibilidade é enviado para categorias de conteúdo e ignorado para `PERSON`. Categorias
`OPEN` podem ter zero opções; categorias `FIXED` precisam de ao menos uma. Mapear TMDB não encontrado
ou indisponível para `ApiError`.

### Dados do formulário

- `name`: obrigatório, entre 1 e 120 caracteres;
- `description`: opcional;
- `coverImage`: URL opcional;
- `instructions`: texto opcional mostrado ao responder o template;
- `eligibilityStartDate` e `eligibilityEndDate`: ambos ausentes ou ambos presentes e ordenados;
- `categories`: pelo menos uma categoria;
- `group`: `PRIMARY` ou `SECONDARY`;
- `allowedType`: `MOVIE`, `SERIES`, `EPISODE` ou `PERSON`;
- `optionMode`: `OPEN` ou `FIXED`;
- `FIXED`: pelo menos uma opção válida;
- `OPEN`: nenhuma opção fixa no payload.

`origin` não é escolhido pelo usuário: o backend deriva `OFFICIAL` ou `COMMUNITY` da role persistida.
O período de elegibilidade é aplicado aos alvos de conteúdo e ignorado para `PERSON`.

### Persistência e erros

```http
POST /picks-templates
```

O retorno `201` contém o template completo e suas categorias. O cliente pode renderizar a revisão
usando os metadados de busca que já possui; não deve fazer chamadas individuais de detalhe para cada
opção.

- `400`: payload inválido, categoria incompatível, FIXED sem opções, OPEN com opções ou alvo fora
  da elegibilidade;
- `401`: usuário não autenticado;
- `403`: cookie CSRF ausente/inválido ou sem permissão;
- `404`: alvo TMDB não encontrado;
- `409`: conflito de alvo/opção ou alteração incompatível;
- `502`: TMDB indisponível.

## Answer Template

### Tela e publicação

O wizard de resposta usa o template estrutural já criado e mantém as respostas no estado local até o
envio final. Não existe draft persistido. Ler a configuração com
`GET /picks-templates/{templateId}` e buscar previews das opções persistidas ou resultados de
categorias `OPEN` sob demanda com
`GET /picks-templates/{templateId}/categories/{categoryId}/options/view`.

Ao publicar, enviar as seleções escolhidas e a visibilidade em uma única chamada:

```http
POST /picks-templates/{templateId}/picks
```

O corpo contém `visibility` e `selections`, com no máximo uma seleção por categoria respondida. O
retorno `201` contém o Pick criado. Para buscar filmes, séries e pessoas, usar `GET /search` com
`type=MOVIE`, `SERIES` ou `PERSON`; para temporadas e episódios, usar as rotas de seleção contextual.
Não criar uma busca genérica de alvos nem persistir drafts. Alvos incompatíveis, seleções vazias e
falhas de elegibilidade retornam os erros do contrato de publicação.

## Pick Detail

### Dados da tela

Para abrir um Pick visível, usar:

```http
GET /picks/{pickId}/view
```

A resposta é o read model visual completo: preview do template, usuário, visibilidade, progresso,
contagens sociais e todas as categorias atuais do template. Categorias não respondidas aparecem com
`isAnswered=false`, `target=null` e `preview=null`; não preencher esses campos no cliente. Respostas
com alvo carregam `PickTargetPreview` para conteúdo, pessoa e contexto de pessoa e preservam
`PARTIAL`/`UNAVAILABLE` quando a metadata visual não está completa ou disponível.

Carregar os comentários separadamente com `GET /picks/{pickId}/comments` e a ação de curtida com
`POST`/`DELETE /picks/{pickId}/like`. O contrato raw `GET /picks/{pickId}` continua referencial e
mantém a revalidação completa, inclusive seu erro `502` quando o TMDB está indisponível; a rota
`/view` não cria um novo `502` para previews parciais ou indisponíveis.

## My Picks

### Tela

A aba My picks mostra separadamente os Picks respondidos pelo usuário e os templates criados por ele.
Cada bloco possui paginação própria e pode exibir somente a primeira página na tela inicial, mantendo
um link para ver todos.

### Cards pessoais de Picks

Usar `GET /users/me/picks/view?page=1&size=5` para a lista de Picks do usuário autenticado. A
resposta é paginada e ordenada por `createdAt DESC` e `id DESC`; respeitar `page`, `size`,
`totalElements`, `totalPages` e `hasNext`. Cada card inclui o preview do template, usuário, visibilidade,
data de criação, progresso (`EMPTY`, `PARTIAL` ou `COMPLETE`), quantidade de categorias válidas
respondidas, total atual de categorias, até cinco respostas ordenadas por categoria e contagens/estado
de likes e comentários. `MAX_VISUAL_PICK_PAGE_SIZE` limita essa leitura visual a 10 itens por página.

`answeredCategoriesCount` e `progress` consideram somente selections estruturalmente válidas nas
categorias atuais. `totalCategoriesCount` considera todas as categorias atuais. Cada resposta do
card pode trazer `target` e `preview` visuais para conteúdo, pessoa e contexto de pessoa; respeitar
`PARTIAL` e `UNAVAILABLE` sem remover a linha. O card mostra no máximo cinco respostas válidas.
As rotas raw `GET /users/{userId}/picks` e `GET /picks-templates/{templateId}/picks` continuam
disponíveis como contratos referenciais; para a interface, usar as rotas `/view`.

## Template Detail

### Dados da tela

Carregar o template atual com `GET /picks-templates/{templateId}`. Esse contrato permanece
estrutural: entrega o cabeçalho, categorias e opções raw, sem hidratar metadata visual de cada opção.
Para as seções de Picks, usar em paralelo:

```http
GET /picks-templates/{templateId}/my-picks/view?page=1&size=10
GET /picks-templates/{templateId}/picks/view?sort=RECENT&page=1&size=10
```

O primeiro retorna os Picks do viewer. O segundo retorna os Picks visíveis pela política do backend,
com `sort=RECENT` ou `POPULAR`. Ambas as respostas são paginadas, aceitam no máximo 10 itens por
página e já entregam usuário, visibilidade, progresso, contagens sociais e até cinco respostas
visuais por Pick. Não fazer paginação local nem chamar `/contents/details?ids=...` para hidratar os
cards.

Likes e comentários continuam sendo carregados por suas rotas sociais próprias do template ou do
Pick. Um Pick privado, ou um Pick `FOLLOWERS` sem relação `ACCEPTED`, não aparece na listagem e
retorna `403` quando acessado diretamente pelo detalhe visual.

## Templates próprios

Usar `GET /users/me/picks-templates/view?page=1&size=5&sort=RECENT` para listar os templates criados
pelo usuário autenticado. A resposta reutiliza `PicksTemplatePreview`, é filtrada pelo creator no
banco e ordenada por `createdAt DESC` e `id DESC`. O único `sort` suportado nessa tela é `RECENT`.

`400` representa paginação inválida ou uma ordenação não suportada; `401` representa ausência de
autenticação. As rotas de detalhe e listagem raw dos templates permanecem inalteradas.

## Busca e recarga de opções

Durante a criação ou edição do template, use `GET /search` para buscar alvos `MOVIE`, `SERIES` e
`PERSON`, e as rotas existentes de seleção contextual para temporadas e episódios. Mantenha os
resultados escolhidos no estado local do wizard até o envio final do template; não persista drafts nem
crie uma rota genérica de busca de alvos.

Ao recarregar opções fixas já persistidas, use
`GET /picks-templates/{templateId}/categories/{categoryId}/options/view` com os mesmos filtros e
parâmetros `page`/`size` de `/options`. A resposta mantém a página e seus metadados de origem, e inclui
um preview básico de conteúdo ou pessoa. Conteúdo de contexto de pessoa também recebe card básico.
`PARTIAL` e `UNAVAILABLE` permanecem na lista com os IDs de origem preservados. Essa leitura aplica
`MAX_OPTION_VIEW_PAGE_SIZE=20`; a rota raw `/options` continua disponível sem esse teto visual e
mantém sua paginação e semântica próprias. O detalhe do template não carrega metadata visual das
opções.

O campo `id` do wrapper pode ser `null` para resultados de busca `OPEN`, pois esses alvos ainda não
possuem uma opção persistida. Para opções `FIXED` persistidas, `id` identifica a opção da categoria.

## Ordem recomendada de carregamento

### Template Detail

1. Carregar `GET /picks-templates/{templateId}` para o cabeçalho e as categorias estruturais.
2. Em paralelo, carregar a primeira página de `my-picks/view` e `picks/view`.
3. Renderizar o cabeçalho e as categorias sem esperar pelos Picks.
4. Renderizar cada Pick usando os previews já entregues pelo backend.
5. Carregar comentários do template e dos Picks somente quando suas seções forem abertas.

### Answer Template

1. Carregar `GET /picks-templates/{templateId}`.
2. Renderizar imediatamente as categorias `OPEN` e `FIXED` com seus metadados.
3. Buscar opções visuais `FIXED` com `options/view` e resultados `OPEN` somente quando necessário.
4. Manter seleções e visibilidade no estado local até o publish.
5. Publicar uma única vez com `POST /picks-templates/{templateId}/picks`.

### Pick Detail

1. Carregar `GET /picks/{pickId}/view`.
2. Renderizar todas as categorias na ordem de `displayOrder`, inclusive as não respondidas.
3. Usar `previewStatus` para placeholders sem remover a resposta nem seu target raw.
4. Carregar comentários com `GET /picks/{pickId}/comments` quando a seção aparecer.
5. Aplicar like com `POST` ou `DELETE /picks/{pickId}/like` e atualizar o estado local.

## Regras de erro

- `401`: usuário não autenticado, inclusive nas três rotas visuais de Picks e em `options/view`;
- `400`: página negativa, tamanho não positivo, ordenação inválida ou payload de publicação inválido;
- `403`: Pick privado ou `FOLLOWERS` sem seguimento aceito;
- `404`: template, Pick, categoria ou target persistido inexistente;
- `PARTIAL` e `UNAVAILABLE`: manter a linha/card e a referência raw, exibindo placeholder visual;
- `502`: permanece no contrato raw `GET /picks/{pickId}` quando a revalidação completa do TMDB falhar;
- nenhuma leitura visual deve chamar `POST /contents/reference` ou `/contents/details?ids=...`.

# Games

## Tela

A tela Games mostra os oito desafios do dia GMT em cards independentes. O resumo visual deve ser carregado
por `GET /games/today/view`, autenticado pelo cookie JWT `access_token`. O valor máximo exibido é 64 pontos,
derivado de quatro modalidades com seis tentativas e quatro com dez; o protótipo não deve usar uma expectativa
de 68 pontos. O contador regressivo usa `nextResetAt`, que chega como a próxima meia-noite UTC, e não deve
recalcular o horário localmente.

## Requisição principal

```http
GET /games/today/view
```

A resposta contém `date`, `nextResetAt`, `totalMaxScore`, `currentScore` e exatamente oito itens em `games`,
ordenados pelo enum `gameType`. Cada card entrega `gameType`, `targetKind`, `maxAttempts`, `attemptsUsed`,
`attemptsRemaining`, `status`, `score`, `imageUrl` e `visibleImageUrls`. A visão é deliberadamente segura:
não carregar `answer`, `hints`, `attempts`, `filmography` ou `imageUrls` completos a partir dela. Para abrir
ou retomar o desafio e obter o estado detalhado, usar a rota raw `GET /games/today`.

## Dados usados pela tela

| Bloco visual | Campos | Uso |
| --- | --- | --- |
| Data e contador | `date`, `nextResetAt` | Mostrar o dia dos desafios e o tempo restante até a próxima virada UTC. |
| Resumo de pontuação | `totalMaxScore`, `currentScore` | Mostrar a pontuação do dia e o máximo de 64 pontos. |
| Identidade do card | `gameType`, `targetKind` | Escolher o título, ícone, rótulo do alvo e a categoria visual. |
| Progresso | `maxAttempts`, `attemptsUsed`, `attemptsRemaining`, `status`, `score` | Mostrar tentativas, pontos, progresso e o estado do desafio. |
| Imagem revelada | `imageUrl`, `visibleImageUrls` | Mostrar a imagem inicial ou as imagens já liberadas sem expor a solução. |

O frontend não deve reconstruir o score máximo, a ordem dos cards ou o contador a partir de regras próprias.
Esses valores devem ser consumidos da resposta para evitar divergência quando novas modalidades forem adicionadas.

### Mapeamento dos cards

| `gameType` | Rótulo visual | Alvo | Tentativas máximas |
| --- | --- | --- | ---: |
| `MOVIE_BY_POSTER` | Filme por pôster | Filme | 6 |
| `SERIES_BY_POSTER` | Série por pôster | Série | 6 |
| `PERSON_BY_FACE` | Pessoa por rosto | Pessoa | 6 |
| `EPISODE_BY_FRAME` | Episódio por frame | Episódio | 6 |
| `MOVIE_BY_INFO` | Filme por informações | Filme | 10 |
| `SERIES_BY_INFO` | Série por informações | Série | 10 |
| `ACTOR_BY_MOVIE_FILMOGRAPHY` | Ator por filmografia de filmes | Pessoa | 10 |
| `ACTOR_BY_SERIES_FILMOGRAPHY` | Ator por filmografia de séries | Pessoa | 10 |

`status` pode ser `NOT_PLAYED`, `IN_PROGRESS`, `COMPLETED` ou `FAILED`. Em modalidades de informação e
filmografia abertas, `imageUrl` é nulo e `visibleImageUrls` é vazio para não revelar o desafio. `PARTIAL`
não é um status de card desta rota; não inventar esse estado nem substituir campos seguros por metadados
parciais. Se o conjunto diário não estiver completo, a tela deve mostrar estado `UNAVAILABLE` a partir do
erro 503 e não fabricar cards incompletos.

## Ordem de carregamento e erros

1. Carregar o resumo visual e renderizar os oito cards, o score atual e o máximo de 64 pontos.
2. Usar `nextResetAt` como instante absoluto para o contador e `status` para o estado de cada card.
3. Ao abrir um card, buscar o estado detalhado pela rota raw correspondente e executar as ações existentes.

## Ações iniciadas pela tela

- Abrir ou continuar um desafio: buscar `GET /games/{gameType}/today` e navegar para a experiência do jogo.
- Atualizar tentativas: usar as rotas raw de tentativa e desistência já existentes; o resumo visual deve ser
  recarregado depois da ação para atualizar score, tentativas e status.
- Abrir o ranking: navegar para a tela Games Ranking, sem tentar montar o ranking a partir dos oito cards.

`401` indica ausência de autenticação. `503` indica que os desafios necessários para o dia ainda não estão
disponíveis. A leitura é GET e não exige CSRF.

# Games Ranking

## Tela

A tela Games Ranking usa `GET /games/rankings/view` para a tabela paginada e para a linha/resumo do usuário
autenticado. Sem filtro, o ranking agrega todas as modalidades; com `gameType`, mostra somente a modalidade
selecionada. O mapeamento de `gameType` e seus rótulos é o mesmo da tela Games.

## Requisição principal

```http
GET /games/rankings/view?gameType=<opcional>&page=1&size=20
```

`page` é 1-based na resposta; `0` também representa a primeira página. `size` assume 20 e tem teto efetivo
de 20 itens. Respeitar `content`, `page`, `size`, `totalElements`, `totalPages` e `hasNext`; não paginar
localmente. Cada linha de `content` entrega `rank`, identidade visual do usuário, `score`, `daysPlayed`,
`gamesPlayed`, `solvedPercentage` e `averageAttempts`.

## Dados usados pela tela

| Bloco visual | Campos | Uso |
| --- | --- | --- |
| Filtro | `gameType` | Alternar entre todos os jogos e uma modalidade específica. |
| Identidade da linha | `rank`, `userId`, `name`, `username`, `profilePicture` | Montar posição, avatar, nome e link para o perfil do jogador. |
| Métricas da tabela | `score`, `daysPlayed`, `gamesPlayed`, `solvedPercentage`, `averageAttempts` | Preencher as colunas de pontos, dias jogados, jogos, aproveitamento e média de tentativas. |
| Resumo do viewer | `viewer` | Mostrar a posição do usuário autenticado mesmo quando ele não estiver na página atual. |
| Distância do Top 200 | `top200CutoffScore`, `pointsToTop200`, `inTop200` | Mostrar a mensagem de pontuação necessária ou o destaque de permanência no Top 200. |

O perfil do usuário deve ser renderizado com os dados de `content` ou `viewer`; não fazer uma busca de usuário por
linha. A resposta já entrega `name`, `username` e `profilePicture` para eliminar esse padrão N+1.

O bloco `viewer` é sempre do usuário autenticado no escopo solicitado, mesmo quando ele não aparece em
`content`. Ele expõe `rank`, score, dias e jogos, percentual resolvido, média de tentativas, `inTop200`,
`top200CutoffScore` e `pointsToTop200`. Quando o usuário não tem resultado terminal, exibir rank nulo,
métricas zeradas e média `0.0`. Quando está fora do Top 200 e o cutoff existe, `pointsToTop200` indica
`top200CutoffScore - viewer.score + 1`; esses campos ficam nulos quando ainda não há 200 usuários ranqueados
ou quando o viewer já está no Top 200.

O ranking é calculado a partir dos resultados terminais (`COMPLETED` e `FAILED`) no momento da leitura.
`daysPlayed` conta datas de desafio distintas, `gamesPlayed` conta resultados terminais,
`solvedPercentage` é `round(100 * COMPLETED / gamesPlayed)` e `averageAttempts` é a média de
`attemptsUsed` arredondada a uma casa decimal. Não esperar uma atualização do ranking à meia-noite e não
criar snapshot diário no cliente. As rotas raw `GET /games/rankings/general` e
`GET /games/{gameType}/ranking` continuam disponíveis para o contrato referencial anterior.

## Paginação e interações

1. Enviar `gameType`, `page` e `size` na requisição, preservando o filtro ao trocar de página.
2. Usar `hasNext` para habilitar o botão de carregamento da próxima página e `totalPages` para a paginação
   numerada; não inferir o fim somente pela quantidade de itens recebidos.
3. Ao mudar `gameType`, voltar para a primeira página e solicitar novamente a tabela e o bloco `viewer`.
4. O link para o perfil usa `userId` e não deve depender do username para identificar o jogador.

## Estados e erros

Um ranking sem resultados é uma resposta válida `200` com `content` vazio e métricas zeradas para o viewer;
não exibir `PARTIAL` para uma linha sem resultados. `UNAVAILABLE` não é um estado de linha do ranking: usar
placeholder de carregamento ou estado vazio enquanto a leitura não termina, e tratar `401` e `400` conforme
o `ApiError` retornado pelo backend. A leitura visual é autenticada pelo cookie JWT e não exige CSRF.

# Games — telas de gameplay visual (oito modalidades)

As oito telas de gameplay — **Movie by poster**, **Series by poster**, **Person by face**, **Episode by frame**,
**Movie by info**, **Series by info**, **Actor by movie filmography** e **Actor by series filmography** — usam
o mesmo read model visual. Use as rotas `/play` para montar essas experiências; `GET /games/{gameType}/today`
permanece como contrato raw e preserva seu próprio formato. A leitura visual usa snapshots e tentativas já
persistidos: não consulta TMDB, não chama `/contents/details?ids=...` e não cria referências locais de Content.

## Rotas comuns

```http
GET  /games/{gameType}/play
POST /games/{gameType}/play/attempt
POST /games/{gameType}/play/give-up
GET  /games/play/media/{grantToken}
```

`gameType` aceita qualquer uma das oito modalidades. Todas as leituras exigem autenticação pelo cookie JWT e
não exigem CSRF. Os dois POSTs exigem cookie/header CSRF,
passam pelo limite de tentativas e retornam `DailyGamePlayActionResponseDTO`, contendo o estado completo da
tela e `currentAttempt` quando aplicável.

As três rotas aceitam `majorRoles` (booleano, padrão `true`). Esse filtro só altera `ACTOR_BY_SERIES_FILMOGRAPHY`;
nos demais jogos o parâmetro é ignorado. Para filmografia de filmes, `filmography.majorRoles` permanece `false`.

`view=true` na busca é aceito somente nos quatro jogos baseados em imagem. As telas de informação e filmografia
usam a busca padrão, que já retorna `title`, `imageUrl` e `date` para os resultados selecionáveis.

O estado visual contém `challengeDate`, `nextResetAt`, `gameType`, `targetKind`, `maxAttempts`,
`attemptsUsed`, `attemptsRemaining`, `status`, `score`, `potentialScore`, `media`, `attempts`, `answer`,
`canAttempt`, `canGiveUp`, `shareOnCompletion` e `sharedToFeed`; também inclui `filmography` somente para jogos
de filmografia. Cada `attempts[]` pode incluir `infoFeedback` nos jogos de informação. `nextResetAt` deve ser
usado como instante absoluto UTC para o contador; não recalcular a virada no frontend.

Enquanto o jogo está aberto, `answer` é nulo, a mídia ainda não liberada possui `url` nula e `attempts`
contém somente os candidatos já submetidos, com metadados visuais seguros e o resultado da comparação.
Em estado terminal, a resposta congelada pode ser exibida; o jogo de episódio sempre expõe exatamente seis
slots `FRAME_0` a `FRAME_5` na projeção visual.

## Mídia protegida

Os campos de mídia não apontam diretamente para URLs públicas do TMDB. O backend emite um `grantToken`
opaco e vinculado ao usuário em `/games/play/media/{grantToken}`. O frontend usa a URL relativa retornada
em `media[].url`, sem tentar reconstruir o caminho original.

O grant expira, é validado contra o desafio e a origem atual antes do cache de bytes transformados, e não
é reutilizável por outro usuário. As variantes `BLUR_26`, `BLUR_20`, `BLUR_14`, `BLUR_9`, `BLUR_5` e
`BLUR_2` revelam progressivamente mais detalhes; `ORIGINAL` só aparece quando permitido pelo estado.
O servidor aceita somente JPEG/PNG dentro dos limites de tamanho, dimensão e pixels. `404` representa grant
inválido/expirado ou imagem inexistente; indisponibilidade temporária do TMDB é `502`.

## Tela Movie by poster

Usar `GET /games/MOVIE_BY_POSTER/play`. Renderizar a imagem `CLUE`, o contador de tentativas, os estados
de `attempts` e o campo de busca. A busca usa:

```http
GET /games/MOVIE_BY_POSTER/search?q=<texto>&page=1&size=20&view=true
```

Com `view=true`, o backend limita a página a seis candidatos e retorna `title`, `imageUrl`, `date` e os
metadados de card disponíveis sem detalhes em lote. O palpite envia somente o identificador validado:

```json
{ "tmdbId": "<id>" }
```

Após `POST /attempt`, substituir o estado local pela resposta do backend. O mesmo filme não pode ser
submetido duas vezes na data atual pelas rotas visuais; a segunda tentativa retorna `409` sem consumir
tentativa.

## Tela Series by poster

Usar `GET /games/SERIES_BY_POSTER/play`, a mesma busca com `view=true` e o payload de tentativa com
`tmdbId`. O card do resultado pode usar `title`, `imageUrl`, `date`, `genres` e `numberOfSeasons` sem
chamar detalhes do conteúdo.

## Tela Person by face

Usar `GET /games/PERSON_BY_FACE/play` e:

```http
GET /games/PERSON_BY_FACE/search?q=<texto>&page=1&size=20&view=true
```

Os resultados são pessoas, com `personTmdbId`, `title` e `imageUrl` quando disponíveis. Não fazer uma
segunda chamada por pessoa para montar o resultado da busca. O palpite envia `personTmdbId` e o servidor
controla a revelação da imagem facial.

## Tela Episode by frame

Usar `GET /games/EPISODE_BY_FRAME/play`. A resposta entrega seis slots de frame em ordem; frames ocultos
permanecem sem URL. A seleção hierárquica usa as rotas existentes:

```http
GET /games/EPISODE_BY_FRAME/search/series?q=<texto>&page=1&size=20&view=true
GET /games/EPISODE_BY_FRAME/series/{seriesTmdbId}/seasons
GET /games/EPISODE_BY_FRAME/series/{seriesTmdbId}/seasons/{seasonNumber}/episodes
```

O palpite envia a identidade composta do episódio, nunca um ID de episódio isolado:

```json
{
  "seriesTmdbId": "<seriesId>",
  "seasonNumber": 1,
  "episodeNumber": 2
}
```

O feedback de cada tentativa informa separadamente `seriesCorrect`, `seasonCorrect`, `episodeCorrect` e
`exactMatch` no contrato raw; a projeção visual resume esses valores em `attempts[].outcome`. Snapshots
legados com mais de seis frames utilizáveis são reduzidos aos seis primeiros caminhos distintos e válidos;
menos de seis continuam indisponíveis.

## Tela Movie by info

Usar `GET /games/MOVIE_BY_INFO/play` e `GET /games/MOVIE_BY_INFO/search?q=<texto>&page=1&size=20`
e `POST /games/MOVIE_BY_INFO/play/attempt`. O estado aberto não contém mídia nem resposta. Cada tentativa traz
o candidato visual e `infoFeedback`, com células de plataforma, gêneros, ano, classificação, diretor, elenco,
produtoras e receita. `displayValue` mostra o valor do candidato; os estados `MATCH`, `PARTIAL`, `NO_MATCH` e
`NO_DATA` dirigem as cores e indicadores da tabela.

## Tela Series by info

Usar `GET /games/SERIES_BY_INFO/play` e `GET /games/SERIES_BY_INFO/search?q=<texto>&page=1&size=20`
e `POST /games/SERIES_BY_INFO/play/attempt`. O feedback usa as mesmas células da tela Movie by info, com
`revenueOrSeasons.displayValue` contendo a quantidade de temporadas do candidato e
`candidateEpisodeCount` contendo a quantidade de episódios. A contagem de episódios é apenas metadado de
exibição; a comparação continua avaliando temporadas. Tentativas antigas sem essa contagem devem exibir
fallback sem número, sem buscar os dados novamente.

## Tela Actor by movie filmography

Usar `GET /games/ACTOR_BY_MOVIE_FILMOGRAPHY/play` e `GET /games/ACTOR_BY_MOVIE_FILMOGRAPHY/search?q=<texto>&page=1&size=20`
e `POST /games/ACTOR_BY_MOVIE_FILMOGRAPHY/play/attempt`. A seção `filmography.guessedActors` fornece nome,
foto e idade do ator na data do desafio, além das contagens de obras compartilhadas. `filmography.entries`
fornece `entryKey` estável e opaco, ano, gêneros, estado de revelação, `period` congelado quando disponível
e `sharedWith` como referências de ator com `personTmdbId`, nome e `profileUrl`, deduplicadas por ID.
Enquanto a obra estiver oculta, `workId` é nulo; use `entryKey` para identificar a linha sem inferir o ID TMDB.
Após sucesso, esgotamento ou desistência, todas as obras elegíveis revelam título, pôster e personagem.
`highlighted` e `sharedWith` continuam indicando somente interseções reais. O parâmetro `majorRoles` não muda
essa modalidade.

## Tela Actor by series filmography

Usar `GET /games/ACTOR_BY_SERIES_FILMOGRAPHY/play` e
`GET /games/ACTOR_BY_SERIES_FILMOGRAPHY/search?q=<texto>&page=1&size=20`, além de
`POST /games/ACTOR_BY_SERIES_FILMOGRAPHY/play/attempt`. A busca padrão retorna nome e foto para selecionar
uma pessoa. O parâmetro
`majorRoles` seleciona todos os papéis ou somente os principais, padrão `true`; as chaves de interseção já
persistidas permitem alternar sem nova chamada TMDB. Cada obra pode incluir ano, gêneros, número de episódios
do papel, período e atores correspondentes em `sharedWith`. `period` é nulo em snapshots legados que não o
armazenaram; nesses casos usar o ano disponível como fallback visual. `sharedWith` contém referências tipadas
com ID, nome e `profileUrl`, deduplicadas por ID. Enquanto a obra estiver oculta, `workId` é nulo e
`entryKey` identifica a linha sem expor uma chave TMDB. Ao encerrar o jogo, a lista revela as obras elegíveis;
`highlighted` e `sharedWith` continuam baseados somente nas interseções efetivas dos palpites.

## Regras comuns para as oito telas

- Enquanto qualquer jogo de informação ou filmografia estiver aberto, `media` é vazio e `answer` é nulo.
  Após conclusão ou desistência, a imagem da resposta é uma referência protegida por grant; usar somente a
  URL retornada pelo backend.
- Em um mesmo dia GMT, `/play/attempt` rejeita uma identidade de candidato já submetida com `409`, sem
  consumir tentativa. O POST raw `/games/{gameType}/attempt` mantém o comportamento anterior de aceitar
  repetições.
- Os campos raw de filmografia mantêm `period` nulo e não expõem fotos de obras nem personagens. Enquanto
  abertas, as obras não reveladas têm `workId` nulo no estado raw e em
  `attempts[].filmographyFeedback.entries`, além de `entryKey` opaco e estável; a mesma chave identifica a
  linha em `/play`. Ao encerrar, todas as obras elegíveis são reveladas e somente as interseções reais ficam
  `highlighted`. `period` e `sharedWith` continuam exclusivos da projeção visual `/play`.
- `majorRoles` é efetivo somente para `ACTOR_BY_SERIES_FILMOGRAPHY`; nos outros tipos de filmografia não
  altera o conjunto de obras.

## Erros e compatibilidade

- `400`: `gameType` ou identidade do candidato inválidos, ou `view=true` usado fora dos quatro jogos baseados em imagem;
- `401`: usuário não autenticado;
- `403`: CSRF inválido em tentativa/desistência;
- `409`: candidato repetido na data atual ou jogo já encerrado;
- `429`: limite de busca, tentativa ou mídia excedido;
- `502`: indisponibilidade do TMDB ao carregar mídia;
- `503`: desafio do dia indisponível.

Snapshots essenciais de filmografia ausentes, vazios ou sem obras utilizáveis retornam `503`. Os POSTs visuais
validam antes da mutação o caminho de imagem do desafio, o conjunto de seis frames de Episode by frame e o
snapshot de filmografia; uma falha não consome tentativa nem grava desistência. A validação usa apenas dados
congelados e não consulta TMDB.

As rotas raw mantêm a identidade canônica e a semântica de envio, tentativa e desistência; a apresentação de
filmografia mudou deliberadamente: `workId` oculto agora é nulo e `entryKey` identifica a linha. As oito telas
de gameplay usam as rotas visuais `/play` acima.
