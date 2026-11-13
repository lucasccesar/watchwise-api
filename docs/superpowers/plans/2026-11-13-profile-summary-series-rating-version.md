# Versão da nota de série no Profile

> **Para execução:** use a skill `executing-plans` e siga os passos na ordem.

**Objetivo.** Permitir que o usuário edite a nota de uma série a partir do próprio Profile. O `GET /users/{userId}/summary?type=SERIES` deve devolver `card.viewerState.myRating` e `card.viewerState.currentRatingVersion` para os cards de série já retornados no Summary.

**Arquitetura.** Reunir os IDs locais dos cards de série que entram na resposta e ler apenas `contentId`, `score` e `version` de `ContentRating`, em uma única consulta. Essa leitura substitui a consulta de notas do Top 5 dentro do Summary do próprio usuário e alimenta também a atividade recente e as prévias de reviews cujo card é da série. O montador atual continua resolvendo os metadados visuais em um único lote.

**Stack.** Java 21, Spring Boot, Spring Data JPA, PostgreSQL e Maven Wrapper.

## Decisão de implementação

| Opção | Custo e implicação |
| --- | --- |
| Reutilizar `VIEWER_STATE` completo | Aproveita o montador comum, mas também consulta diário, conclusões, reviews, watchlist e outros dados que a edição da nota dispensa. |
| Consultar a nota depois de montar cada lista | Adiciona leituras redundantes ao Top 5 e à atividade e pode produzir pares diferentes se uma série aparecer em ambas. |
| Compartilhar uma leitura de ratings no Summary | Reusa o acesso por `(user_id, content_id)`, lê somente três campos e usa o mesmo par em todos os cards do título. É a opção escolhida. |

O Top 5 de séries já retorna `top5.score` a partir de `ContentRating`. A consulta em `ProfileSummaryQueryRepository.findLatestScoresByUserIdAndContentIdsAndContentType` usa o diário para os outros tipos. A atividade `SERIES_COMPLETION` também já lê a nota atual no `LEFT JOIN` com `content_ratings`. Esses dois caminhos justificam centralizar a leitura no Summary do próprio perfil.

## Contrato e custo

- Ativar o preenchimento quando `type == SERIES` e `userId.equals(viewerId)`, após as verificações atuais de acesso. `viewerId` é o ID autenticado. Preservar `viewerState: null` nos cards ao visitar outro perfil, inclusive quando a visita é autorizada, e na aba de filmes.
- Usar o `ContentRefDTO.id()` local dos cards reais de `SERIES` em `top5`, `recentActivity` e `recentReviews`. Deduplicar os IDs após os cortes das listas. São até 16 IDs distintos pelos limites atuais de 5, 6 e 5. Um card de episódio ou temporada pode usar o pôster da série e continua sendo card de episódio ou temporada.
- Para uma série avaliada, devolver a nota e a versão da mesma linha de `ContentRating`. A versão `0` é válida. Em card de série elegível sem nota, retornar ambos os campos como `null`. `reviewsCount` e `reviewIds` ficam nulos porque esse fluxo não carrega reviews.
- Preservar o significado dos `score` existentes. Top 5 de série e atividade `SERIES_COMPLETION` continuam refletindo a nota atual. Uma atividade `DROPPED_ENTRY` continua com `score: null` no item, mas o card traz o rating atual se ele existir. A nota publicada em `recentReviews[].score` continua histórica. Uma série no Top 5 com nota e sem conclusão atual continua com `hasWatched: false`.
- Custo de ratings no próprio perfil: zero consultas quando não há IDs elegíveis, uma consulta em lote quando há. Com Top 5 preenchido, ela substitui a leitura de notas do Top 5. Sem Top 5, ela pode adicionar um acesso ao banco. Nesse caminho, a consulta de conclusões deixa de buscar a nota. A consulta usa a chave única já existente em `content_ratings`, sem migração, nova rota ou chamadas adicionais ao TMDB.

## 1. Preparar a leitura compartilhada

**Arquivos:** `ContentRatingRepository.java`, `Top5EntryReader.java`, `Top5EntryReaderImpl.java`, `ProfileSummaryQueryRepository.java`.

- [x] Acrescentar uma projeção de leitura (somente os campos da consulta) ao `ContentRatingRepository`. Usar aliases iguais aos getters e chamar o método somente quando houver IDs.

```java
@Query("""
        select r.contentId as contentId, r.score as score, r.version as version
        from ContentRating r
        where r.userId = :userId and r.contentType = :contentType
          and r.contentId in :contentIds
        """)
List<RatingState> findRatingStates(
        @Param("userId") UUID userId,
        @Param("contentType") ContentType contentType,
        @Param("contentIds") Collection<UUID> contentIds);

interface RatingState {
    UUID getContentId();
    Integer getScore();
    Long getVersion();
}
```

- [x] Em `Top5EntryReader`, acrescentar `List<Top5EntryResponseDTO> readEntries(UUID ownerId, ContentType type)`. Implementar em `Top5EntryReaderImpl` com `@Transactional(readOnly = true)`, consulta atual das entradas ordenadas e mapeamento com `top5EntryMapper`. O método `read` existente continua transacional e usa `readEntries` seguido da consulta atual de notas. Indexar e aplicar as notas pelos IDs em `entry.content().id()` e preservar o retorno antecipado para lista vazia. A rota dedicada continua usando `read`.
- [x] Em `ProfileSummaryQueryRepository.findRecentSeriesCompletionActivities`, acrescentar o parâmetro `@Param("includeRatingScore") boolean includeRatingScore` entre `userId` e `Pageable`. Acrescentar `:includeRatingScore = TRUE` à condição `ON` do `LEFT JOIN content_ratings rating`, preservando `rating.score AS score`, os filtros de conclusão, a contagem e a ordenação. Atualizar o chamador da consulta, os mocks e a chamada direta em `DiaryEntryRepositoryTest`. O leitor passa `false` somente quando usa a leitura compartilhada.

## 2. Montar a resposta do próprio perfil

**Arquivos:** `ProfileSummaryDataReader.java`, `ProfileSummaryDataReaderImpl.java`, `ProfileSummaryReaderImpl.java`, `ContentCardDTO.java`.

- [x] Em `ProfileSummaryDataReader`, declarar `record SeriesRating(Integer score, Long version) {}` e acrescentar `Map<UUID, SeriesRating> seriesRatings` ao `Snapshot`. Atualizar os dois construtores existentes de oito e nove argumentos para delegar com `Map.of()` e copiar o mapa com `Map.copyOf` no construtor principal. O mapa é interno ao Summary e não entra no DTO da resposta.
- [x] Em `ProfileSummaryDataReaderImpl.read`, calcular `boolean includeSeriesRatings = type == ContentType.SERIES && userId.equals(viewerId)`. Usar `top5EntryReader.readEntries(userId, type)` quando verdadeiro e `top5EntryReader.read(userId, type)` no caminho atual. Passar `!includeSeriesRatings` na consulta de conclusões. Manter a contagem compartilhada de visualizações e montar a lista final de atividade com o limite atual.
- [x] Depois de montar `recentActivity`, combinar as referências de `top5Entries`, `recentActivity` e `recentReviews`. Aceitar somente `ContentRefDTO` com `type() == SERIES` e `id() != null`. Usar `distinct()` antes de chamar `findRatingStates(userId, ContentType.SERIES, ids)`. Retornar `Map.of()` imediatamente quando `includeSeriesRatings` for falso ou os IDs estiverem vazios. Dentro da transação do leitor, iniciar o mapa com `new SeriesRating(null, null)` para os IDs elegíveis e sobrescrever os IDs retornados com o par persistido. A presença do ID distingue um card próprio sem nota de um card fora do escopo.
- [x] Usar esse mapa para aplicar `entry.withScore(rating == null ? null : rating.score())` às entradas do Top 5 do próprio perfil, antes de `ProfileTop5EntryDTO.from(...)`. Para `SERIES_COMPLETION`, copiar cada `RecentActivityItemDTO` com `score` vindo do mesmo mapa e manter `id`, `source`, `content`, `status`, `timesWatched`, `comment`, `activityDate` e `card`. Manter os demais itens e as prévias de review com seus `score` originais. Incluir o mapa ao retornar o `Snapshot`.
- [x] Em `ContentCardDTO`, acrescentar `public ContentCardDTO withViewerState(ContentCardViewerStateDTO state)` que retorna uma cópia com todos os componentes do construtor canônico e substitui apenas `viewerState`. Preservar `backdropPath`, `tmdbVoteAverage`, `customPosterUrl` e `previewStatus`.
- [x] Em `ProfileSummaryReaderImpl.enrichVisualCards`, manter `PROFILE_CARD_FIELDS` e o `ContentCardContext` atual para a montagem visual. Após a única chamada ao montador, se `snapshot.seriesRatings()` estiver preenchido, copiar o mapa de cards e percorrer as referências de série diretamente usadas nas listas, deduplicadas por `ContentCoordinate`. Usar o ID local da referência para localizar o par e a coordenada para localizar o card. Copiar o card somente quando ambos existirem. Assim o mesmo objeto enriquecido chega a cada lista, inclusive com metadados parciais. As coordenadas auxiliares usadas só para resolver pôsteres não entram nessa etapa. Preservar `seriesRatings` no novo `Snapshot`.

```java
// rating é a entrada não nula do Snapshot para o ID local do item.
var state = new ContentCardViewerStateDTO(
        null, rating.score(),
        null, null, null, null, null, null, null, null,
        rating.version(), null, null);
return card.withViewerState(state);
```

## 3. Confirmar o contrato e preparar o uso no frontend

- [x] Em `docs/context/openapi.yaml`, documentar no GET de Summary que o próprio perfil de séries preenche `myRating` e `currentRatingVersion` dos cards de série. `reviewsCount` e `reviewIds` são nulos nesse estado parcial. Na descrição de `PUT /contents/{contentId}/my-rating`, incluir o Summary como origem válida da versão.
- [x] Em `docs/context/business-rules.md`, registrar a regra de próprio perfil, a fonte comum de nota e versão e a consulta em lote. Atualizar `docs/context/progress.md` no dia em que o código for implementado. Manter as alterações de documentação na árvore de trabalho.
- [x] O frontend já declara `currentRatingVersion` em `ProfileContentCard.viewerState`, escolhe o estado do card de Summary e envia essa versão em `ContentApi.setContentRating`. A verificação `currentRatingVersion == null` já bloqueia somente quando tenta editar uma nota existente. Não foi necessária mudança de código no frontend.

## 4. Verificar antes do commit de código

- [x] Em `ProfileSummaryDataReaderImplTest`, usar uma série presente no Top 5, numa conclusão e em `DROPPED_ENTRY`. Verificar uma única chamada a `findRatingStates` com IDs distintos, o mesmo par para a série repetida, score atual no Top 5/conclusão e score nulo no abandono. A consulta do teste confirma que o `LEFT JOIN` de nota é desligado no caminho compartilhado.
- [x] Em `ProfileSummaryReaderImplTest`, verificar no JSON o mesmo par em Top 5, `recentActivity` e uma review SERIES. Visitantes mantêm o estado nulo. A resposta de rating parcial deixa `reviewsCount` e `reviewIds` nulos, conforme o contrato.
- [x] Em `SummaryControllerIntegrationTest`, persistir uma série no Top 5 com rating, uma sem rating e outro usuário com uma nota diferente. Verificar versão `0`, ausência de nota com campos nulos, `hasWatched: false` sem projeção completa, score do dono e `viewerState` nulo para o visitante.
- [x] Rodar os testes direcionados. `DiaryEntryRepositoryTest` executou 81 testes sem falhas. O conjunto focado executou 26 testes sem falhas e a integração final focada executou 1 teste sem falhas. Em testes fora desse escopo, `ContentPageDTOTest` e `ContentCardAssemblerTest` falharam também na branch base. A suíte completa de Summary tem um teste que persiste `LocalDate.now()` e consulta outubro de 2026. `OpinionReadConsumersIntegrationTest` falha ao procurar configuração Spring porque encontra duas classes `@SpringBootConfiguration`.

```powershell
.\mvnw.cmd test '-Dtest=Top5EntryReaderImplTest,ProfileSummaryDataReaderImplTest,ProfileSummaryReaderImplTest,ProfileSummaryContractTest'
.\mvnw.cmd test '-Dtest=SummaryControllerIntegrationTest,DiaryEntryRepositoryTest'
```

- [x] Verificar o custo pelo ponto de chamada e pelo teste de serviço: uma chamada em lote a `findRatingStates` para IDs distintos, nenhuma quando o dono não está consultando seu próprio Summary ou não há séries elegíveis, e nenhuma chamada por card. A integração confirma que o caminho normal de rating continua recebendo `version` da entidade persistida.
- [x] Commit local `17696025` (`fix(summary): expose series rating versions`). A mensagem não contém `Co-Authored-By` nem atribuição a IA/ferramenta.
