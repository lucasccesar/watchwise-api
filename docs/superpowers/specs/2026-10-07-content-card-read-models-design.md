# Projeções de leitura para cards e telas de conteúdo

## Objetivo

Substituir o uso client-side de `GET /contents/details?ids=...` nas telas de coleção por projeções de leitura (read models) montadas no backend, sem transformar `ContentDetailsDTO` em um contrato universal e sem persistir título, pôster ou o registro completo do TMDB na tabela `contents`.

O núcleo obrigatório desta iniciativa é: resolução interna de cards, chamadas TMDB leves com cache, consultas locais em lote, contratos visuais específicos por tela e migração dos batches de detalhes do frontend. Horário de lançamento, rede de exibição e outras informações presentes no mockup só entram quando houver fonte confiável; não são motivo para inventar dados ou ampliar o schema sem necessidade.

O escopo inclui a migração de Home, Profile, Diary History, Diary Reviews, Month in Review, Year in Review e All Time Stats, além da implementação completa de Watchlist, Calendar, Trending e Social/Feed. Progress, Episode Ratings Map, Episode Ratings Map (Show) e Content Detail mantêm seus contratos específicos atuais, com os ajustes de integração necessários ao módulo compartilhado.

## Evidências usadas

- `D:/Users/Lucas C/Downloads/Watchlist.dc.html`
- `D:/Users/Lucas C/Downloads/Calendar.dc.html`
- `D:/Users/Lucas C/Downloads/Trending.dc.html`
- `D:/Users/Lucas C/Downloads/Social.dc.html`
- `D:/Users/Lucas C/Downloads/Home-DS.dc.html`
- `D:/Users/Lucas C/Downloads/Progress-u3kp.dc.html`
- `D:/Users/Lucas C/Downloads/Profile-w0up.dc.html`
- `D:/Users/Lucas C/Downloads/Episode-Ratings-Map.dc.html`
- `D:/Users/Lucas C/Downloads/Episode-Ratings-Map-Breaking-Bad.dc.html`
- `D:/Users/Lucas C/Downloads/Month-in-Review.dc.html`
- `D:/Users/Lucas C/Downloads/Year-in-Review.dc.html`
- `D:/Users/Lucas C/Downloads/All-Time-Stats.dc.html`
- `docs/telas-reqs-definitivo.md`
- `docs/context/telas-reqs.md`
- `docs/context/openapi.yaml`
- `docs/context/tmdb-reqs-by-screen.md`

`telas-reqs-definitivo.md` atualmente não cobre Watchlist, Calendar, Trending e Social/Feed. Ele ainda prescreve batches de `contents/details` para Home, Profile, Diary e telas de estatísticas. `telas-reqs.md` já documenta o Calendar como uma projeção própria e o Feed como uma resposta que ainda exige enriquecimento de conteúdo.

## Decisão arquitetural

O frontend deve consumir os endpoints de leitura da tela sem chamar `contents/details` para completar cada card. A tela pode continuar usando mais de um endpoint de domínio para cabeçalho, resumo ou filtros; o que deixa de existir é a composição visual client-side baseada em detalhes completos. A lógica de resolução de conteúdo fica em um módulo interno reutilizável:

```text
Endpoint da tela
    -> Service de leitura da tela
        -> ContentCardAssembler
            -> ContentViewerStateService em lote
            -> ContentStatsService em lote
            -> UserContentPosterService em lote
            -> metadata TMDB canônica com cache
                -> DTO específico da tela
```

Não será criado um endpoint público genérico `/contents/previews` apenas para o frontend buscar a tela e depois fazer um join. O módulo de cards será uma interface interna do backend; cada tela terá um DTO próprio, com os campos e invariantes que realmente controla. Uma rota visual específica, como `/watchlist/view`, só será criada quando for necessária para preservar o contrato de domínio existente; ela não substitui o módulo interno.

Quando o endpoint existente já for claramente específico de uma tela, a projeção visual será adicionada de forma compatível ao contrato atual. Endpoints de domínio genéricos não serão remodelados para carregar dados de apresentação sem necessidade; nesses casos, uma rota de leitura visual separada será preferida para evitar acoplamento e quebra semântica.

### Contratos preservados

- `GET /contents/{contentId}/details` continua sendo o detalhe completo de um conteúdo.
- `GET /contents/details?ids=...` continua disponível para consumidores legados durante a migração, mas deixa de ser requisito do frontend para as telas de coleção.
- `GET /contents/{contentId}/page` continua sendo o BFF (resposta agregada) da página completa de conteúdo.
- `TrendingResponseDTO` continua sendo o envelope público de Trending; seus itens podem ganhar campos TMDB adicionais sem trocar `movies` e `series` por outro formato JSON.
- `ContentRefDTO` continua representando identidade e referência persistida; não será transformado em DTO visual.
- `ContentDetailsDTO` não receberá campos exclusivos de Watchlist, Feed, Trending ou Profile.

### Nova interface interna

Extrair de `ContentChildCardAssembler` um assembler reutilizável no módulo `content`, usando o `ContentCoordinate` já existente e os serviços de estado/stat existentes. A interface equivalente é:

```java
Map<ContentCoordinate, ContentCardDTO> assemble(
        Collection<ContentCardSpec> specs,
        ContentCardContext context,
        ContentCardFieldSet fields);
```

`ContentCardSpec` contém o `ContentCoordinate` e, quando uma tela já possuir dados de apresentação confiáveis, título, poster, data e runtime conhecidos. O assembler só consulta metadata TMDB para campos ausentes.

`ContentCardContext` deve carregar idioma, região, usuário que terá o pôster customizado resolvido e, quando autorizado, o usuário cujo estado de visualização será calculado.

`ContentCardFieldSet` deve informar quais facetas o consumidor realmente precisa:

- `BASIC_METADATA`;
- `STATS`;
- `VIEWER_STATE`;
- `WATCHLIST_PROGRESS`;
- `SOCIAL_METADATA`.

O resolver não deve buscar stats ou estado pessoal quando a tela não os solicitar.

### DTO interno de card

O DTO compartilhado de card é uma projeção limitada, não uma nova entidade e não um substituto de `ContentDetailsDTO`:

```java
public record ContentCardDTO(
        UUID contentId,
        ContentType type,
        String tmdbId,
        String seriesTmdbId,
        Integer seasonNumber,
        Integer episodeNumber,
        String title,
        String posterPath,
        String customPosterUrl,
        LocalDate releaseDate,
        Integer releaseYear,
        Integer runtimeMinutes,
        Integer totalRuntimeMinutes,
        Integer numberOfSeasons,
        Integer numberOfEpisodes,
        List<String> genres,
        ContentCardStatsDTO stats,
        ContentCardViewerStateDTO viewerState,
        ContentPreviewStatus previewStatus
) {}
```

O DTO não inclui elenco, créditos, vídeos, temporadas completas, reviews ou comentários.

## Implementação do módulo de cards

### Componentes

Criar:

- `ContentCardDTO`;
- `ContentCardStatsDTO`;
- `ContentCardViewerStateDTO`;
- `ContentCardSpec`;
- `ContentCardContext`;
- `ContentCardFieldSet`;
- `ContentPreviewStatus`;
- `ContentCardAssembler`, extraído do assembler de cards filhos atual;
- `TmdbCardMetadataResolver`;
- testes unitários e de integração do módulo.

O assembler deve deduplicar coordenadas antes de chamar qualquer dependência e só montar os itens da página final de uma tela. `ContentViewerStateService`, `ContentStatsService` e os lookups por coordenada já existentes são reutilizados; não será criada uma segunda família de readers que repita essas responsabilidades.

### Metadata TMDB canônica de card

Adicionar uma única metadata normalizada de card no `TmdbClient`, indexada por `TmdbCardMetadataKey` e idioma. `TmdbCardMetadataKey` pertence ao pacote `common.tmdb`; `TmdbCardMetadataResolver`, no pacote `content`, converte `ContentCoordinate` nessa chave antes de chamar o client. Uma chamada leve alimenta a cache quando ela estiver vazia; uma chamada de detalhe completo também deve popular essa mesma cache normalizada ao concluir. Assim, abrir um detalhe completo evita uma nova chamada leve futura para o mesmo card, e abrir um card não obriga o fluxo de detalhe a buscar créditos, vídeos ou temporadas completas.

Sucessos continuam usando a Caffeine de metadata. Para preservar o resultado exato de `NotFound` e `Unavailable` entre chamadores concorrentes sem persistir falhas, o client deve manter uma tabela single-flight (uma future compartilhada por chave) somente durante a requisição em andamento; a entrada é removida depois que todos os chamadores daquela carga recebem o mesmo resultado. Não remover uma falha da cache persistente em um callback independente, pois isso abre uma corrida de recarga.

Os campos TMDB ausentes devem continuar nulos. O backend não deve fabricar título, pôster, runtime ou data.

### Falhas parciais

Uma falha de metadata de um único card não deve transformar uma tela inteira de lista em erro 502. O card retorna sua referência mínima e `previewStatus`:

- `AVAILABLE`: dados suficientes para o card;
- `PARTIAL`: alguns dados opcionais indisponíveis;
- `UNAVAILABLE`: o item externo não pôde ser enriquecido.

O frontend usa placeholder nesses casos. Falhas do endpoint principal, falhas de autenticação, privacidade ou parâmetros continuam sendo erros normais da tela.

### Segurança e privacidade

- Pôster customizado só é resolvido para um `posterUserId` que o viewer possa visualizar.
- `viewerState` sempre é calculado para o usuário autenticado, nunca para um usuário arbitrário enviado pelo cliente.
- Estado pessoal não entra em caches globais de conteúdo.
- Cards de perfil privado respeitam a mesma regra de visibilidade do endpoint principal.
- Nenhuma leitura cria linha em `contents`.

## Watchlist

### Contrato de leitura

Adicionar uma leitura visual específica, sem quebrar o endpoint de domínio existente:

```text
GET /users/{userId}/watchlist/view
```

Parâmetros:

```text
type=MOVIE|SERIES
genre=<genre>
status=ALL|NEW|IN_PROGRESS|UPCOMING
sort=DATE_ADDED|RELEASE_DATE|RUNTIME|RATING|TITLE
direction=ASC|DESC
page=1
size=30
```

Resposta:

```java
public record WatchlistViewResponseDTO(
        List<WatchlistCardDTO> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext,
        WatchlistAggregateDTO aggregate
) {}
```

`WatchlistCardDTO` contém a entrada da watchlist, o `ContentCardDTO`, a posição, a data de adição, a data contextual de lançamento e o status do dono.

`WatchlistAggregateDTO` deve ser calculado depois dos filtros e incluir:

- quantidade total;
- quantidade de filmes;
- quantidade de séries;
- runtime total;
- quantidade de upcoming.

Isso corrige o atual `upcomingCount`, que representa apenas séries futuras e não atende ao agregado visual do HTML.

### Regras de ordenação

Ordenações por posição, data de adição, release date e runtime usam dados locais sempre que possível. Ordenações por título e rating exigem a materialização dos cards candidatos e devem ser executadas no backend antes da paginação, nunca somente na página atual no frontend.

O serviço deve preservar a consistência da ordenação quando dois itens têm a mesma chave usando `watchlistEntryId` como desempate.

Ordenações que exigem materializar muitos cards (`TITLE` e `RATING`) devem ocorrer no backend antes da paginação, usando a metadata TMDB canônica e stats atuais em lote. A primeira versão não cacheia a resposta materializada da watchlist: uma cache user-scoped ficaria obsoleta quando a média comunitária mudasse por uma ação de qualquer usuário. Para proteger o caminho de leitura, essas duas ordenações aceitam no máximo `app.watchlist.view.max-materialized-sort-candidates` candidatos, com default `1000`; acima do limite o backend retorna `400` orientando o cliente a restringir `type`, `genre` ou `status`. Ordenações locais (`POSITION`, `DATE_ADDED`, `RELEASE_DATE`, `RUNTIME`) continuam usando paginação direta quando possível.

### Ações

Os endpoints atuais de inserção, remoção e reordenação continuam válidos. A remoção deve permitir atualização otimista apenas depois de o frontend manter uma estratégia de rollback para erro de concorrência.

## Calendar

O Calendar continua sendo uma projeção própria baseada em snapshots. Ele não deve passar pelo resolver de cards nem pelo `contents/details`.

### Extensões opcionais de fidelidade visual

Adicionar somente quando a fonte externa fornecer o dado e a tela realmente precisar dele:

- `releaseTime`;
- `releaseTimezone`;
- `networkName`;
- `releaseContext` (`THEATRICAL`, `AIRING`, `WATCHLIST`, `STREAMING`, `UNKNOWN`);
- uma chave de navegação externa ou `contentId` opcional.

Não interpretar `sources.WATCHLIST` como uma plataforma de streaming. `sources` indica por que o evento está no calendário.

Se a grade precisar controlar o agrupamento, pode ser adicionado ao request uma granularidade explícita:

```text
granularity=AUTO|EPISODE
```

`AUTO` mantém agrupamentos `SEASON` e `SERIES`; `EPISODE` permite reproduzir a grade detalhada do HTML quando houver dados suficientes.

Um `CalendarSummaryDTO` pode ser adicionado como campo complementar para evitar contagens repetidas no frontend, mas não é requisito para o Calendar funcionar. A resposta atual de eventos continua sendo suficiente para a primeira versão da tela.

Se o TMDB não fornecer horário ou rede, o backend retorna nulo e o frontend usa `All day` ou omite a badge. Nenhum horário visualmente conveniente será inventado. A ausência desses campos não bloqueia a implementação do Calendar.

## Trending

Trending permanece baseado no endpoint externo do TMDB. O HTML deve identificar a nota e a popularidade como dados do TMDB, não como métricas da comunidade Watchwise. A tela pode continuar mostrando o estado pessoal do viewer separadamente quando esse conteúdo já tiver referência local ou puder ser resolvido por chave natural sem escrita.

Criar `TrendingCardDTO` e usar `TrendingResponseDTO` como envelope público existente. Cada item deve retornar, quando disponível no TMDB ou em cache card-level:

- `tmdbId`;
- `type`;
- `title`;
- `posterUrl`;
- `year`;
- gêneros mapeados do TMDB;
- `tmdbVoteAverage`;
- `runtimeMinutes` para filmes;
- `numberOfSeasons` para séries;
- `popularity` ou outro indicador externo claramente identificado;
- estado do viewer autenticado, sem criar `Content` durante a leitura.

`GET /trending` mantém o carregamento inicial dos arrays `movies` e `series`. A continuação de cada seção deve usar `GET /trending/{type}?timeWindow=day|week&page=&size=`, pois filmes e séries avançam independentemente no HTML. Busca textual não entra no contrato de Trending: o endpoint externo do TMDB não combina busca por título com ranking Trending. O frontend pode filtrar localmente os itens já carregados sem prometer paginação global de busca.

A lista base do Trending deve reutilizar as caches curtas já existentes por `(type, timeWindow, language, page)`. Os detalhes leves de cada item devem usar a metadata canônica de card. `rankDelta` e o texto “people watching” não serão retornados como métricas Watchwise; o design deve usar uma indicação de popularidade do TMDB ou remover esses elementos.

## Social / Feed

O Feed mantém a agregação pull e a paginação por cursor. A resposta pública deve ser uma projeção visual do feed, com os dados necessários para renderizar cada card.

Adicionar ao envelope do feed:

- `followingCount`;
- `followedUsersPreview`, quando o cabeçalho precisar das bolhas de pessoas seguidas;
- `content` já enriquecido;
- previews visuais para Top 5 e targets de Picks.

`FeedPageViewDTO` preserva no JSON os campos existentes `content`, `size`, `nextCursor` e `hasNext`, acrescentando apenas dados de cabeçalho do Feed. A migração é aditiva para consumidores atuais.

O enriquecimento ocorre somente depois da seleção da página final pelo cursor. O fluxo é:

1. buscar candidatos das fontes;
2. mesclar e ordenar;
3. selecionar a página e calcular o cursor;
4. extrair e deduplicar as referências visuais;
5. resolver cards em lote;
6. montar a resposta final.

Os tipos existentes devem ser tratados:

- `DIARY_ENTRY`;
- `DROPPED`;
- `TOP5_UPDATE`;
- `PICK_CREATED`;
- `PICKS_TEMPLATE_CREATED`;
- `DAILY_GAME_RESULT`.

O HTML atual não possui card para `DAILY_GAME_RESULT`; a implementação deve adicionar esse estado, não eliminar silenciosamente eventos retornados pelo backend.

Likes, comentários recentes, spoilers, `watchedWith`, visibilidade de Picks e contadores permanecem no contrato social da tela.

## Migração das telas existentes

### Home

O endpoint `summary/home` passa a retornar previews visuais em `recentlyWatched` e `socialActivities`. O frontend não chama mais `contents/details` para esses blocos.

### Profile

Os blocos de recentes, reviews, Top 5 e episódios devem retornar cards visuais próprios. O `summary` continua responsável pelos agregados, não pelos dados TMDB completos.

### Diary History e Diary Reviews

Os endpoints paginados retornam o card visual de cada entrada, com título, poster, runtime, data e rating comunitário quando disponível. Filtros e agrupamento continuam no backend; a página não precisa fazer join com outra resposta.

### Month in Review, Year in Review e All Time Stats

Os rankings retornam cards visuais junto com os agregados da edição. O frontend não faz batches adicionais para `recentWatched`, `topRated`, `bottomRated`, `longestWatched` ou `mostLoggedContent`.

### Progress e Episode Ratings Map

Manter os contratos atuais enriquecidos. Eles já demonstram o padrão correto de projeção específica da tela.

### Content Detail

Manter `/contents/{contentId}/page` como contrato completo. Ele não deve ser usado para renderizar grids ou listas.

## Necessidade, não-escopo e etapas

### Mudanças obrigatórias

- criar o módulo interno de cards;
- usar metadata TMDB canônica de card, populada tanto por leituras leves quanto por detalhes completos;
- executar consultas locais em lote;
- carregar somente as facetas solicitadas por cada tela;
- migrar Home, Profile, Diary History, Diary Reviews, Month in Review, Year in Review e All Time Stats;
- implementar os read models de Watchlist, Trending TMDB e Feed;
- manter Calendar como projeção própria e completar somente os dados confiáveis;
- atualizar os contratos, testes e documentação correspondentes.

### Mudanças condicionais

- horário de lançamento e nome de rede no Calendar;
- uma rota visual separada para Watchlist, caso o contrato de domínio atual precise permanecer imutável;
- campos adicionais de apresentação que não sejam necessários para os estados funcionais da tela.

As mudanças obrigatórias pertencem à mesma iniciativa, mas serão implementadas em etapas e commits independentes por módulo. Isso permite testar e revisar cada projeção sem criar uma alteração monolítica.

### Fora do escopo

- remover `GET /contents/{contentId}/details`;
- remover imediatamente `GET /contents/details?ids=...` enquanto houver consumidores legados;
- persistir título, pôster, elenco ou o registro completo do TMDB em `contents`;
- criar um endpoint público genérico de previews para o frontend fazer composição;
- alterar Progress, Episode Ratings Map ou Content Detail além da integração com o resolver compartilhado.

## Testes e critérios de aceitação

### Módulo de cards

- deduplicação de chaves;
- uma query em lote por fonte local;
- ausência de N+1;
- cache por idioma e coordenada;
- coalescing de chamadas concorrentes;
- pôster customizado e privacidade;
- conteúdo não referenciado sem escrita;
- falha parcial com `previewStatus`;
- fallback de idioma/região.

### Cada tela

- contrato JSON completo;
- filtros e ordenação;
- paginação correta;
- agregados independentes da página atual;
- estados vazios, loading e falha parcial;
- autenticação e privacidade;
- navegação para conteúdo;
- ausência de chamadas client-side a `contents/details` para completar cards;
- possibilidade de carregar cabeçalho e resumo em endpoints separados quando o contrato da tela exigir isso.

### Performance

Os testes devem comprovar:

- nenhuma consulta por card;
- nenhuma chamada de detalhe completo disparada exclusivamente pelo caminho de card;
- nenhum `POST /contents/reference` durante leitura;
- resolução de cards somente para itens da página final;
- cache reutilizado entre telas;
- resultados de sort/aggregate calculados sobre o conjunto filtrado correto.

## Critério final de sucesso

As telas de coleção devem receber cards prontos dos endpoints de leitura da tela. `GET /contents/details?ids=...` continua disponível para compatibilidade e detalhe técnico, mas deixa de ser uma dependência do frontend para Home, Profile, Diary, Review, Month, Year, All Time, Watchlist, Trending e Feed. A tela ainda pode usar mais de uma chamada de domínio para seus blocos não visuais; o critério é não usar chamadas de detalhe completo por card.

Calendar permanece uma projeção especializada e não usa detalhes por `contentId` para montar seus eventos.
