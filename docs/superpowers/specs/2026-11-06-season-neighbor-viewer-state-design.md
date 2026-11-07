# Estado das temporadas vizinhas na página Season

**Data:** 2026-11-06  
**Status:** aprovado; complemento de contagem de episódios aprovado em 2026-11-06

## Problema

A tela Season recebe metadados das temporadas por `GET /contents/selection/series/{seriesTmdbId}/seasons`, mas essa resposta não contém o estado do viewer. `GET /contents/{contentId}/page` já é o agregador da página e já expõe `sections.seasons`, porém a seção de uma Season depende atualmente de existir uma referência `Content` local para a série pai.

A montagem atual também pode carregar os detalhes completos da série pai quando essa referência existe. O carregamento de Season já busca `TmdbTvFullDetails` para obter dados da série, então a lista de temporadas pode ser reaproveitada sem uma segunda leitura de detalhes da série.

## Objetivo

Expor na resposta da página Season os cartões das temporadas regulares da série, com `viewerState.watchStatus`, `viewerState.lastWatchedDate` e `viewerState.myRating`, sem adicionar rota nem criar referências `Content` durante uma leitura.

## Contrato e escopo

- Alterar somente `GET /contents/{contentId}/page`: para raiz `SEASON`, preencher `sections.seasons` mesmo quando não há `Content` local para a série pai. Os cartões equivalentes da raiz `SERIES` também recebem a mesma resolução de estado do viewer.
- Manter os cartões no formato `ContentChildCardDTO`; seus metadados continuam usando a referência TMDB por série e número da temporada.
- Incluir `airedEpisodeCount` nos cartões de temporada. Usar `SeasonSummaryDTO.airedEpisodeCount` quando presente; para resumos de temporadas vizinhas que não contêm essa contagem, usar snapshots locais do calendário apenas quando completos, atuais e consistentes com o total de episódios TMDB. Contar episódios distintos com data de lançamento conhecida até hoje; usar null sem fonte confiável e não fazer uma busca TMDB adicional apenas para calcular a contagem.
- Manter `GET /contents/selection/series/{seriesTmdbId}/seasons` como proxy de metadados para fluxos de seleção. A resposta de `page` permitirá ao cliente deixar de buscar a rota de seleção apenas para exibir temporadas; mudanças no cliente estão fora do escopo deste repositório.
- Manter `ContentDetailsDTO.seasons` preenchido somente para `SERIES`; os resumos irmãos usados na composição da página são dados internos.
- Resolver o viewerState de cards de temporada em páginas `SERIES` e `SEASON` com a mesma lógica agrupada.
- Continuar omitindo Specials (`seasonNumber = 0`) da seção, como faz o assembler atual.
- Atualizar a descrição da rota e dos schemas afetados em `docs/context/openapi.yaml`, registrar o comportamento implementado em `docs/context/progress.md` e atualizar `docs/context/business-rules.md` para documentar a resolução de estado por temporada.

## Desenho

### Metadados das temporadas

Refatorar a montagem de detalhes da Season para disponibilizar ao compositor da página os `SeasonSummaryDTO` criados a partir de `TmdbTvFullDetails.seasons`, na mesma resolução TMDB que já ocorre para a Season aberta. Esses resumos não serão adicionados ao JSON público de `details.seasons`.

O `ContentPageServiceImpl` deixará de carregar `ContentDetailsDTO` completo da série pai para montar `sections.seasons`. O `ContentChildCardAssembler` usará os resumos irmãos fornecidos pela composição da Season. Isso remove a dependência de uma linha `SERIES` e evita o caminho de runtime agregado da série pai nessa página.

### Estado do viewer

Montar o estado das temporadas com leituras agrupadas por série:

- Consultar primeiro o histórico do viewer em lote. Temporadas sem eventos de episódio são `UNWATCHED`, com data nula, e não exigem busca de calendário para determinar assistência.
- Para temporadas com histórico, aproveitar projeções atuais de conclusão e os snapshots já persistidos do calendário por temporada, respeitando sua validade e completude.
- Agregar episódios distintos do `DiaryEntry` do viewer por `seriesTmdbId` e `seasonNumber`; a data exibida é o maior valor efetivo, `watchedDate` ou, quando ausente, a data de `createdAt`.
- Resolver ratings em lote pelas referências locais `SEASON` existentes. Sem uma referência local não há rating associado à temporada, portanto `myRating` é `null`; isso não impede o estado de visualização derivado dos episódios.
- Para temporadas com histórico e calendário válido, comparar as coordenadas dos episódios assistidos com as coordenadas regulares já lançadas. Não decidir conclusão apenas comparando duas contagens: episódios extras ou sem data conhecida não podem compensar episódios lançados ainda não assistidos.
- Com calendário confiável e pelo menos um episódio lançado, a interseção vazia resulta em `UNWATCHED`, a interseção parcial em `PARTIALLY_WATCHED` e a cobertura integral em `WATCHED`.
- Para temporadas com histórico, calendário ausente, vencido ou incompleto resulta em `UNKNOWN` e contagens derivadas nulas, salvo quando uma projeção atual de conclusão já fornece um estado autoritativo.
- Não assumir que a lista de episódios do TMDB contém somente episódios já lançados.

### Cache e falhas

O caminho da página fará leituras agrupadas do histórico, das projeções atuais e dos snapshots existentes. Apenas temporadas com histórico e calendário ausente, vencido ou incompleto exigem atualização em segundo plano. Enquanto ela ocorre, retornar `UNKNOWN`, preservando uma projeção atual de conclusão quando existir. Um snapshot vencido não basta para afirmar `WATCHED`, pois um episódio novo pode ter sido lançado. Falha opcional no refresh não falha a página nem marca a temporada como completa.

Reaproveitar o calendário existente e sua cadência de validade, sem criar outro cache persistido nem acionar o refresh de progresso da série inteira. Usar a operação existente `CalendarScheduleProvider.loadSeason` e o sincronizador de calendário para atualizar somente as temporadas necessárias. Agrupar atualizações concorrentes pela mesma série, temporada, região e idioma e usar o executor limitado existente. Uma tentativa sem resultado remoto recebe um cooldown curto e limitado em memória, sem marcar o calendário como completo nem apagar snapshots; isso impede que visitas repetidas repitam imediatamente uma chamada indisponível. A lista de cartões usa os resumos do `getTvFullDetails` já necessário para a Season aberta, sem executar a rota de seleção nem carregar o agregado completo da série.

## Segurança e invariantes

- As consultas de eventos e ratings são sempre escopadas ao `viewerId` autenticado.
- A leitura não cria referências `Content`, ratings ou eventos de diário.
- A nota de outro usuário nunca aparece no `viewerState`.
- Um calendário ausente/incompleto produz estado desconhecido, sem inferir conclusão de contagens TMDB não confiáveis.
- Eventos repetidos do mesmo episódio contam uma vez para o estado global de temporada; a data exibida continua sendo a data efetiva mais recente de um evento.
- O estado da temporada com referência local continua usando a projeção atual de conclusão quando ela existir, mesmo enquanto os metadados de progresso são atualizados.

## Fluxo de dados

```text
GET /contents/{contentId}/page
  -> detalhes da Season + resumos irmãos da mesma consulta TMDB
  -> histórico, ratings e projeções atuais do viewer em lote
  -> calendários válidos das temporadas com histórico
  -> atualização assíncrona apenas das temporadas necessárias
  -> sections.seasons com metadados + viewerState
```

## Efeitos esperados

- A página não faz uma segunda leitura completa da série pai para obter temporadas.
- A resposta permite ao cliente dispensar a rota de seleção apenas para montar cartões de temporadas.
- Um cache aquecido permite responder sem chamadas TMDB extras para a seção. Em cache frio, temporadas com histórico podem ficar `UNKNOWN` até a atualização; temporadas sem histórico não exigem chamadas de calendário.
- A exatidão imediata do estado em cache frio é trocada por carregamento rápido; o client pode atualizar a página depois ou refletir o estado quando a próxima leitura encontrar o snapshot.

## Critérios de revisão

- A página Season inclui temporadas vizinhas sem `Content` pai `SERIES` e não cria referências durante a leitura.
- Estado conhecido distingue zero, progresso parcial e temporada completa com base em episódios regulares lançados.
- Calendário ausente/vencido/incompleto não produz falso `WATCHED`; uma projeção atual existente continua válida. Coordenadas extras não compensam episódios lançados ausentes no histórico.
- Data mais recente considera `watchedDate` e recorre à data de criação quando ela não existe; ratings permanecem privados e nulos sem referência local.
- Requisições repetidas não iniciam refreshes concorrentes para a mesma série/temporada/região/idioma nem bloqueiam o carregamento da página em cache frio. Temporadas sem histórico não disparam refresh de calendário.
- `ContentDetailsDTO.seasons` e a rota de seleção mantêm seus contratos.
