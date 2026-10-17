# User List Screen Read Models Design

## Objetivo

Preparar o backend para montar as telas My Lists, Community Lists e Create List com respostas paginadas, filtráveis e visuais, sem obrigar o frontend a resolver referências de conteúdo individualmente nem a usar o payload de detalhe completo para uma listagem.

## Escopo

Esta especificação cobre:

- seleção de filmes, séries, temporadas e episódios na criação de listas;
- leitura visual paginada das listas do usuário;
- união server-side entre listas próprias e listas curtidas;
- descoberta de listas públicas com busca, filtros e ordenação;
- dono da lista e avatar na resposta;
- previews de conteúdo usando ContentCardDTO e ContentCardAssembler;
- preservação dos endpoints de domínio existentes durante a migração.

Ficam fora deste escopo:

- importação de arquivos;
- persistência de um tipo declarado na lista;
- alteração da regra de itemScope, que continua sendo inferida pelos itens;
- mudança nas regras de comentários, curtidas, visibilidade ou aninhamento já implementadas;
- alteração do endpoint de detalhe completo de uma lista, que continuará usando seu contrato atual.

## Decisões

### Tipo da tela de criação

O tipo escolhido em Create List é um filtro de seleção. Ele limita as opções exibidas e impede a seleção de referências incompatíveis no frontend, mas não é uma propriedade persistida da UserList.

O backend continua sendo a autoridade para a trava de conteúdo da lista:

- MOVIE e SERIES pertencem ao grupo MOVIE_OR_SERIES;
- SEASON pertence ao grupo SEASON;
- EPISODE pertence ao grupo EPISODE;
- listas aninhadas pertencem ao grupo LIST;
- uma lista não mistura conteúdo e listas aninhadas.

### Seleção de temporadas e episódios

O fluxo de jogos já implementa o formato correto para conteúdo hierárquico:

1. busca de uma série;
2. carregamento das temporadas da série;
3. carregamento dos episódios de uma temporada.

Essa lógica será extraída para um serviço de seleção de conteúdo reutilizável. O domínio de listas não chamará DailyGameSearchService diretamente.

As regras específicas dos jogos permanecerão no adaptador dos jogos. A busca de episódios do jogo filtra episódios lançados; a seleção para listas poderá incluir episódios futuros, pois a lista pode representar uma intenção futura de assistir.

### Modelo visual de conteúdo

Listagens e previews usarão ContentCardDTO montado por ContentCardAssembler.

ContentDetailsDTO não será usado nessas respostas, pois carrega dados de detalhe completo, incluindo elenco, provedores, vídeos, temporadas e episódios. Isso aumentaria o payload e as consultas sem benefício para as telas de listas.

O conjunto padrão de campos para previews será BASIC_METADATA. STATS, VIEWER_STATE e SOCIAL_METADATA só serão carregados quando a tela realmente os exibir.

O ContentCardAssembler resolverá os cards em lote por página. O frontend não fará uma chamada de detalhe por item.

### Leitura visual separada da leitura de domínio

Os endpoints atuais que retornam UserListResponseDTO, UserListDetailedResponseDTO e UserListItemResponseDTO permanecem disponíveis para compatibilidade.

Serão adicionados endpoints /view, seguindo o padrão já adotado por Watchlist. Eles retornarão read models próprios para a interface, sem sobrecarregar os contratos de mutação e de integração já existentes.

O módulo UserListView terá uma única interface de caso de uso: receberá um UserListViewRequest já associado ao viewer e ao modo MY, USER ou DISCOVER, validará as combinações e retornará Page<UserListCardDTO>. Os três controllers apenas constroem o request apropriado. A consulta persistente recebe um UserListViewQuery validado; a montagem de cards permanece interna ao módulo. Essa separação concentra regras de modo, paginação, visibilidade e enriquecimento sem replicá-las por rota.

TmdbSeriesHierarchyService será o módulo de baixo nível que resolve coordenadas, idioma e respostas do TMDB. ContentSelectionService será o adaptador de Create List, resolvendo o idioma preferido do viewer; DailyGameSearchService será o adaptador dos jogos, fixando idioma independente e apenas episódios lançados. Nenhum adaptador chamará o outro.

### Listas próprias e curtidas

O endpoint canônico da tela My Lists será:

~~~
GET /users/me/lists/view
~~~

Parâmetro source:

- OWNED: listas criadas pelo usuário autenticado;
- LIKED: listas curtidas pelo usuário autenticado;
- ALL: união das duas fontes, sem duplicar a mesma lista.

A união, os filtros, a ordenação e a paginação serão executados no backend. O frontend não concatenará páginas de endpoints diferentes.

GET /users/me/liked-lists permanecerá durante a migração com seu contrato atual. O novo endpoint visual terá uma consulta própria e poderá substituir o endpoint antigo depois que os clientes forem migrados.

A consulta visual usará UserListViewQueryMode:

- MY: exige source e usa as fontes OWNED, LIKED ou ALL;
- USER: exige ownerId, não aceita source e aplica a barreira atual de perfil e lista;
- DISCOVER: não aceita ownerId nem source e força visibility=PUBLIC.

OWNED retorna todas as listas do viewer. LIKED preserva a semântica atual de auto-visão das listas curtidas. ALL é a união deduplicada de OWNED e LIKED. A descoberta continua limitada a PUBLIC.

### Dono da lista

Os read models visuais incluirão:

~~~
user: UserPreviewDTO
~~~

Isso é obrigatório para Community Lists e evita que My Lists precise fazer uma chamada adicional para obter avatar e nome.

### Filtros de conteúdo

Os endpoints visuais aceitarão itemType com os valores:

- MOVIE;
- SERIES;
- SEASON;
- EPISODE;
- LIST.

Uma lista corresponde ao filtro quando possui pelo menos um item direto do tipo solicitado. O filtro LIST considera itens aninhados. O filtro genre considera conteúdo direto e não atravessa listas aninhadas recursivamente.

itemType será representado por UserListViewItemType, não por ContentType, porque LIST não é um tipo de Content.

Os filtros serão aplicados antes da paginação. itemScope continuará descrevendo o escopo agregado da lista e não substituirá itemType, pois MOVIE_OR_SERIES não distingue filme de série.

### Ordenação

Community Lists aceitará:

- name;
- likesCount;
- itemsCount;
- commentsCount.

My Lists aceitará esses campos e também updatedAt e rank quando a fonte for OWNED. rank não será usado como posição global da comunidade, pois representa a posição manual entre as listas de um único dono.

Toda ordenação terá desempate determinístico por id.

Os valores públicos de sortBy serão name, likesCount, itemsCount, commentsCount, updatedAt e rank. O enum interno UserListViewSort terá conversão explícita desses valores camelCase por UserListViewSort.fromApiValue; não haverá binding direto de sortBy para o nome da constante Java.

## Contratos propostos

### Seleção contextual de temporadas e episódios

A busca atual de filmes e séries em GET /search continuará sendo usada por Create List.

Serão adicionadas as leituras contextuais:

~~~
GET /contents/selection/series/{seriesTmdbId}/seasons
GET /contents/selection/series/{seriesTmdbId}/seasons/{seasonNumber}/episodes
~~~

Parâmetros:

- temporadas: includeSpecials, default false;
- episódios: includeUnreleased, default true.

As respostas serão DTOs leves, contendo coordenadas do conteúdo, título, URLs completas de poster ou still geradas por TmdbImageUrlBuilder, data, número da temporada e número do episódio. A resposta não incluirá dados de detalhe completo. A seleção usa o idioma preferido do viewer; o adaptador de Daily Games mantém o idioma independente já usado por ele.

O serviço reutilizará TmdbClient, o tratamento de indisponibilidade existente e o cache compartilhado. A busca seguirá o rate limit aplicado às buscas externas.

seriesTmdbId deve ser positivo. seasonNumber aceita zero para Specials e valores positivos para as demais temporadas. episodeNumber deve ser positivo.

### Lista visual

O card resumido de uma lista terá:

~~~
UserListCardDTO(
    id,
    user,
    name,
    description,
    visibility,
    createdAt,
    updatedAt,
    previewCards,
    nestedListPreviews,
    nestedListsCount,
    likesCount,
    likedByMe,
    itemsCount,
    commentsCount,
    rank,
    itemScope,
    ownedByMe
)
~~~

previewCards terá no máximo cinco itens e será vazio para listas de listas. nestedListPreviews será limitado para evitar carregar a árvore inteira.

As listagens retornarão PageResponseDTO<UserListCardDTO>, seguindo o padrão de paginação do projeto. Os previews terão no máximo cinco cards, sempre na ordem original dos itens, e no máximo cinco nestedListPreviews.

### My Lists

~~~
GET /users/me/lists/view
~~~

Parâmetros:

~~~
source=OWNED|LIKED|ALL
itemType=MOVIE|SERIES|SEASON|EPISODE|LIST
genre=...
sortBy=name|likesCount|itemsCount|commentsCount|updatedAt|rank
sortDirection=asc|desc
page=...
size=...
~~~

rank e updatedAt serão aceitos somente para OWNED. Para LIKED, a ordenação padrão será Like.createdAt desc e Like.id desc, preservando o contrato atual. Para ALL, a ordenação padrão será updatedAt desc. Quando sortBy for informado, LIKED e ALL aceitarão apenas name, likesCount, itemsCount e commentsCount.

Para preservar as leituras de perfis de terceiros, também será disponibilizado:

~~~
GET /users/{userId}/lists/view
~~~

Esse endpoint não aceitará source e aplicará as mesmas regras atuais de visibilidade de perfil e de lista.

### Community Lists

~~~
GET /lists/discover/view
~~~

Parâmetros:

~~~
q=...
itemType=MOVIE|SERIES|SEASON|EPISODE|LIST
genre=...
sortBy=name|likesCount|itemsCount|commentsCount
sortDirection=asc|desc
page=...
size=...
~~~

Somente listas PUBLIC serão elegíveis. q é opcional: omitido ou composto somente por espaços significa ausência de filtro; quando informado com conteúdo, será trimado, terá no máximo 100 caracteres e terá escape de barra invertida, percentual e sublinhado. genre será trimado e comparado sem distinção de maiúsculas e minúsculas. A busca global GET /search?type=LIST continuará servindo para resultados gerais e autocomplete, mas não será usada como fonte principal dessa tela.

As listagens usam a convenção de PageRequestFactory: page omitido ou 0 representa a primeira página, valores positivos são 1-based e valores negativos retornam 400. size padrão é 10, valores de 1 a 9 retornam 400 e valores acima de 10 são limitados a 10. source=ALL será o default de My Lists quando omitido. Omitir sortBy aplica os defaults definidos pela fonte; sortDirection é validado independentemente de sortBy e somente asc ou desc são aceitos.

### Detalhe existente

O detalhe de uma lista continuará usando GET /lists/{listId}, com os filtros, paginação de itens, avaliações e regras de privacidade já documentados. A melhoria visual desta especificação está limitada às telas de listagem; não será criado um segundo contrato de detalhe.

## Fluxo de dados

### Listagens

1. O controller valida enums, página, tamanho e combinação de filtros.
2. O serviço monta uma consulta de leitura com viewer, fonte, visibilidade, tipo, gênero, nome e ordenação.
3. O repository aplica filtros e paginação no banco.
4. O serviço carrega os usuários donos da página em lote.
5. O serviço carrega até cinco referências de conteúdo por lista em lote.
6. O serviço deduplica coordenadas de conteúdo da página.
7. ContentCardAssembler resolve os cards com BASIC_METADATA em uma operação lógica em lote.
8. Contadores, progresso, curtidas e escopos são resolvidos em lote.
9. O serviço monta UserListCardDTO e o envelope de página.

### Cards de overview

1. A página de listas é autorizada antes de qualquer enriquecimento.
2. Até cinco referências de conteúdo por lista são carregadas em lote.
3. As coordenadas da página são deduplicadas.
4. ContentCardAssembler resolve os cards com BASIC_METADATA.
5. nestedListPreviews são carregados em lote, preservando a posição e ocultando listas filhas que não sejam visíveis.
6. O response visual é montado sem carregar progresso completo, runtime total ou itens fora dos previews.

## Segurança e casos-limite

- PRIVATE nunca aparece em uma listagem de terceiro ou em descoberta.
- FOLLOWERS exige seguimento aceito, preservando a regra atual.
- ALL não duplica uma lista própria que também foi curtida.
- Uma lista sem itens retorna itemScope nulo e previews vazios.
- Uma lista de listas não tenta resolver cards de conteúdo.
- Listas filhas privadas não expõem nome, dono ou visibilidade em nestedListPreviews.
- genre não atravessa listas aninhadas.
- likedByMe é calculado para o viewer autenticado.
- ownedByMe é calculado pelo servidor, não pelo cliente.
- Falha fria do TMDB não invalida toda a página quando houver referência suficiente para um card parcial; o card deve carregar previewStatus conforme o contrato existente.
- Falha de autorização ocorre antes da resolução de metadados externos.
- includeUnreleased=true na seleção de episódios não altera regras de criação da lista nem de calendário.
- seasonNumber=0 é aceito somente no fluxo de temporadas especiais; episodeNumber continua positivo.
- O frontend não pode enviar ContentDetailsDTO nem título ou pôster como fonte de verdade para criar uma referência; a referência continua sendo identificada por coordenadas TMDB.

## Performance

- Nenhuma chamada de ContentDetailsDTO será feita por item.
- A resolução de cards ocorrerá somente para os itens da página e será deduplicada por coordenada.
- A listagem continua limitada a dez listas por página. Cada lista pode exibir até cinco cards de conteúdo, portanto a resposta pode conter até cinquenta posições de card (dez listas vezes cinco cards). A montagem deduplica essas coordenadas internamente: ContentCardAssembler recebe no máximo cinquenta coordenadas únicas em uma chamada, enquanto a mesma referência pode aparecer em mais de uma lista na resposta. O cache/single-flight existente controla consultas repetidas ao TMDB.
- A consulta não carregará todos os itens de todas as listas antes de paginar.
- watchedPercentage e totalRuntimeMinutes não serão carregados na listagem visual, pois são exibidos por GET /lists/{listId}/progress quando o usuário abre a ação de progresso.
- Filtros de tipo e gênero serão EXISTS ou joins no banco, evitando filtragem em memória depois da paginação.
- Ordenação por contadores será feita no banco.
- A busca por nome usará o índice trigram existente de user_lists.name.
- Será criada uma migração com índices de leitura necessários para visibilidade, ordenação de descoberta e gêneros, validada por teste de repository.
- Os índices existentes para likes por usuário/lista e itens por lista serão reutilizados.

## Compatibilidade

Os endpoints atuais continuarão funcionando:

- GET /users/{userId}/lists continua retornando UserListResponseDTO;
- GET /users/me/liked-lists continuará com seu contrato e sua ordenação atuais durante a migração;
- GET /lists/discover continua sendo o contrato simples de descoberta existente;
- GET /lists/{listId} continua retornando UserListDetailedResponseDTO;
- operações de criação, edição, remoção, comentários, curtidas e itens não serão alteradas.

As telas novas usarão os endpoints /view.

## Critérios de aceitação

- Create List pesquisa filmes e séries pelo /search atual.
- Create List lista temporadas e episódios por série usando o fluxo contextual extraído dos jogos.
- Episódios futuros podem ser selecionados para uma lista.
- My Lists combina listas próprias e curtidas no backend sem duplicatas.
- My Lists filtra por tipo e gênero antes da paginação.
- Community Lists busca pelo nome e ordena por nome, likes, itens ou comentários.
- Community Lists retorna dono, avatar, contadores e previews visuais.
- Nenhuma tela depende de uma chamada contents/details por card.
- A listagem visual retorna cards e mantém os contratos de detalhe e progresso existentes.
- Regras de visibilidade, autorização e CSRF permanecem intactas.
- Testes unitários, de repository e de integração cobrem cada filtro, ordenação, fonte, erro e regra de visibilidade.

## Alternativas rejeitadas

1. Fazer o frontend combinar /users/{userId}/lists e /users/me/liked-lists: rejeitado porque produz paginação e ordenação incorretas na união.
2. Usar ContentDetailsDTO para cada preview: rejeitado pelo payload e pelas chamadas desnecessárias.
3. Fazer listas chamarem DailyGameSearchService: rejeitado porque acopla dois domínios e carregaria regras específicas dos jogos.
4. Adicionar temporadas e episódios como busca plana no /search: rejeitado porque TMDB exige a coordenada composta da série e da temporada.
5. Persistir o tipo escolhido na tela: rejeitado porque o domínio já deriva itemScope dos itens e o design usa o tipo somente para limitar a busca.
6. Alterar imediatamente os contratos antigos: rejeitado para evitar quebra dos clientes atuais; os read models /view são aditivos.

