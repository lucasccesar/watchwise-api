# Agrupamento da timeline do diário

## Status

Abordagem de implementação aprovada; detalhes do contrato neste documento aguardam revisão final.

## Contexto

`GET /users/{userId}/diary/view` combina entradas persistidas de filmes e episódios com conclusões derivadas de temporadas e séries. A timeline atual é filtrada e ordenada no repositório SQL, e depois paginada. O usuário quer um modo opcional que una sequências contíguas do mesmo conteúdo de série e devolva um resumo com tipo, contagens e tempo assistido.

## Objetivo

Adicionar o parâmetro booleano `grouped` à rota de view do diário. Com `grouped=true`, sequências elegíveis de dois ou mais itens viram um resumo agrupado; itens isolados permanecem no formato atual.

Não há mudança de schema nem de comportamento de escrita do diário.

## Contrato e regras de agrupamento

- `grouped` ausente ou `false` preserva exatamente a resposta atual.
- `grouped=true` agrupa os itens depois dos filtros existentes e antes da paginação. O tamanho da página conta grupos e itens isolados como um item cada.
- `size` continua opcional, com padrão 20 e máximo efetivo de 1000; com agrupamento, esses limites contam resumos e itens isolados.
- `hasReview=true` desativa o agrupamento e devolve a timeline individual no formato atual, mesmo com `grouped=true`. `hasReview=false` não desativa o agrupamento.
- A ordem usada para reconhecer vizinhança é a ordenação atual da timeline, da mais recente para a mais antiga. Um item de outra série ou um filme interrompe uma sequência.
- Só formam grupo itens consecutivos que apontem para a mesma série, tenham o mesmo `watchNumber` e a mesma data efetiva. Sequências de um item não são transformadas em resumo.
- `SERIES`, `SEASON` e `EPISODE` participam do agrupamento pela série-mãe. Filmes permanecem itens individuais e interrompem uma sequência.
- O tipo agregado é o maior tipo presente em qualquer item do grupo, pela ordem `SERIES > SEASON > EPISODE`.
- A referência `content` do resumo sempre aponta para a série, independentemente de `groupType`.
- O resumo não contém uma lista dos itens originais nem seus IDs, notas ou reviews.

### Data efetiva

A data efetiva só determina se itens podem pertencer ao mesmo grupo:

- Entrada persistida: usar `watchedDate`; quando nula, usar a data de `createdAt`.
- Conclusão derivada: usar `completedOn`; quando nula, usar a data de `createdAt` da entrada âncora.

Itens com datas efetivas diferentes nunca são agrupados. O resumo pode expor a data comum como `groupDate`.

## Métricas do grupo

- `episodeCount` conta entradas reais de diário do tipo `EPISODE` pertencentes à sequência; não usa a quantidade total cadastrada da temporada/série.
- `seasonCount` conta as temporadas distintas dos episódios considerados na sequência.
- `totalRuntimeMinutes` soma o `runtimeMinutes` dos episódios considerados. Se qualquer episódio considerado não tiver runtime, o total será `null`.
- Conclusões derivadas podem elevar `groupType`, mas não expandem métricas para o escopo completo da temporada/série quando os episódios estão presentes no grupo.
- Para `type=SEASON`, o filtro pode remover os itens `EPISODE` e deixar conclusões derivadas de temporada adjacentes. Nesse caso, buscar as entradas reais `EPISODE` correspondentes às temporadas representadas, à mesma série, ao mesmo `watchNumber` e à mesma data efetiva. Deduplicar qualquer episódio que já esteja no grupo. Calcular as contagens e o runtime desses eventos reais, nunca do catálogo TMDB.
- Essa busca de métricas mantém os demais filtros que se aplicam às entradas de episódio segundo a timeline atual; somente o filtro `type=SEASON` é ampliado para recuperar os episódios ocultos. A seleção dos itens que formam os grupos continua usando todos os filtros originais.
- Quando nenhuma entrada real de episódio corresponder a um grupo de conclusões, `episodeCount` e `seasonCount` serão `0`, e `totalRuntimeMinutes` será `null`.

## Formato da resposta

A resposta mantém o envelope paginado atual. O array `content` pode conter itens individuais existentes e resumos agrupados. Um resumo será identificado por `kind=GROUPED` e terá:

- `content`: referência da série;
- `backdropPath`: caminho cru do backdrop TMDB da série, ou `null` quando indisponível;
- `groupType`: maior tipo do grupo;
- `seasonCount`;
- `episodeCount`;
- `totalRuntimeMinutes`;
- `groupDate`: data efetiva comum;
- `watchNumber`: passada compartilhada pelo grupo;
- `orderedAt`: ordem temporal representativa do grupo, herdada do item mais recente segundo a ordenação atual.

Não serão retornados membros aninhados, IDs de diary entries, scores, ratings ou reviews dos membros agrupados. Itens isolados mantêm o DTO atual sem alterações.

Quando `grouped=true`, `PageMeta.totalElements` conta resumos agrupados e itens isolados, e não a quantidade de linhas individuais antes do agrupamento.

## Arquitetura recomendada

Manter o caminho atual para `grouped=false` e para `hasReview=true`. Para o caminho agrupado, estender a consulta do `DiaryViewRepositoryImpl` com CTEs e funções de janela do PostgreSQL:

1. Construir a timeline de origem existente e aplicar os filtros.
2. Calcular a série normalizada, `watchNumber` e data efetiva de cada linha.
3. Usar `LAG` para detectar inícios de sequência e uma soma acumulada para atribuir um identificador a cada run contíguo.
4. Agregar runs com dois ou mais itens; preservar runs unitários como DTOs individuais.
5. Calcular contagem total e página sobre o conjunto agrupado, mantendo a mesma ordenação representativa da timeline.
6. Para o caso `type=SEASON`, consultar os episódios reais correspondentes às conclusões agrupadas e agregar suas métricas pela mesma data e passada.

Adicionar um DTO de resumo agrupado e uma representação de resposta que permita serializar itens individuais e agrupados no mesmo array. O contrato OpenAPI deve documentar `grouped`, o formato agrupado e a resposta mista.

## Alternativas consideradas

1. Agrupar no service depois de carregar toda a timeline filtrada: implementação direta, mas custo de memória e latência cresce com todo o histórico do usuário.
2. Agrupar no SQL antes de paginação: recomendada. A consulta já possui a união dos tipos de item, filtros e paginação; funções de janela preservam grupos nas bordas de página sem materializar o diário inteiro em Java.
3. Buscar em blocos e continuar lendo até completar cada página agrupada: limita cada leitura, mas torna o cálculo de fronteiras, total e paginação mais difícil de manter.

## Erros, segurança e persistência

Não há novas mutações, autorização ou requisitos de CSRF; a rota continua sendo GET. Boolean inválido permanece erro de validação da requisição. Não é necessária migração de banco. O caminho sem agrupamento e a resposta de `hasReview=true` devem continuar compatíveis com o contrato existente.

## Cenários de aceitação

- `grouped=false` e parâmetro ausente retornam a forma atual.
- Três episódios adjacentes da mesma série, mesma passada e data produzem um resumo; um filme entre dois blocos separa os grupos.
- Itens de passadas ou datas diferentes não se agrupam.
- Um resumo pode ter `groupType=SEASON` ou `SERIES` por causa de uma conclusão derivada, mas métricas de sequência com episódios visíveis contam apenas esses episódios.
- `type=SEASON` com conclusões derivadas agrupadas calcula métricas a partir de entradas reais da passada/data/temporadas representadas, sem usar totais TMDB.
- `hasReview=true` conserva a resposta individual, ainda que `grouped=true`.
- A paginação ocorre depois do agrupamento: uma sequência agrupada ocupa uma posição de `size`, inclusive quando cruza a fronteira de uma página de itens individuais.
- Runtime ausente em qualquer episódio considerado deixa o runtime do resumo nulo.
