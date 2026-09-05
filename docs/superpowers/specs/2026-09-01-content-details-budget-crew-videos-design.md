# Design: budget/revenue/production_companies, crew filtrado por job, e videos no proxy de detalhes do TMDB

> Spec pra `ContentDetailsDTO` / `ContentDetailsServiceImpl` / `TmdbClient` — ampliação do
> proxy de detalhes do TMDB já existente (`docs/context/tmdb-proxy-design.md`), não uma
> feature nova do zero.

## 1. Motivação

O cliente precisa exibir, na tela de detalhe de filme/série: orçamento, bilheteria, produtoras,
a equipe técnica principal (diretor, roteirista, diretor de fotografia etc — não o elenco, que
já existe) e trailers/vídeos. Hoje `ContentDetailsDTO` não expõe nenhum desses campos.

## 2. `TmdbClient` — sem chamadas HTTP novas

`credits` (filme) e `aggregate_credits` (série) já são buscados hoje via `append_to_response`,
mas os DTOs de resposta só mapeiam `cast`, nunca `crew`. `videos` é um append novo.

| Método | `append_to_response` atual | Novo |
|---|---|---|
| `getMovieFullDetails` | `credits,watch/providers,alternative_titles` | `+videos` |
| `getTvFullDetails` | `aggregate_credits,watch/providers,alternative_titles` | `+videos` |

`getSeasonFullDetails`/`getEpisodeFullDetails` não mudam — SEASON/EPISODE herdam os campos
novos do `TvFullDetails` da série, que essas duas chamadas já buscam hoje como fallback pra
`genres`/`countries`/`creators`/`cast`.

## 3. DTOs novos em `common/tmdb/`

```java
record TmdbCrewMember(Integer id, String name, String job, String profilePath) {}
// TmdbCredits ganha: List<TmdbCrewMember> crew

record TmdbAggregateCrewJob(String job) {}
record TmdbAggregateCrewMember(Integer id, String name, String profilePath, List<TmdbAggregateCrewJob> jobs) {}
// TmdbAggregateCredits ganha: List<TmdbAggregateCrewMember> crew

record TmdbProductionCompany(Integer id, String name, String logoPath, String originCountry) {}
// @JsonProperty("logo_path") / @JsonProperty("origin_country")

record TmdbVideo(String key, String name, String site, String type, Boolean official,
                  String isoCode639_1, String publishedAt) {}
// @JsonProperty("iso_639_1") / @JsonProperty("published_at")
record TmdbVideos(List<TmdbVideo> results) {}
```

`TmdbMovieFullDetails` ganha `budget` (Long), `revenue` (Long), `productionCompanies`
(`@JsonProperty("production_companies")`), `videos`.

`TmdbTvFullDetails` ganha só `productionCompanies` e `videos` — **sem** `budget`/`revenue`:
o endpoint `/tv/{id}` do TMDB não retorna esses campos (só `/movie/{id}` retorna). Decisão
confirmada com o usuário: `budget`/`revenue` ficam `null` pra `SERIES` no DTO final.

Todos os records novos seguem o padrão já usado no pacote: `@JsonIgnoreProperties(ignoreUnknown
= true)`, nomes de campo em `camelCase` com `@JsonProperty` mapeando o `snake_case` do TMDB.

## 4. `ContentDetailsDTO` — 5 campos novos

```java
record ContentDetailsDTO(
    ..., // campos existentes inalterados
    Long budget,
    Long revenue,
    List<ProductionCompanyDTO> productionCompanies,
    List<CrewMemberDTO> crew,
    List<VideoDTO> videos)
```

Novos DTOs em `content/dto/`:

```java
record ProductionCompanyDTO(Integer id, String name, String logoPath, String originCountry) {}
record CrewMemberDTO(Integer id, String name, String profilePath, List<String> jobs) {}
record VideoDTO(String key, String name, String site, String type, Boolean official,
                String language, Instant publishedAt) {}
```

`VideoDTO.publishedAt` usa `java.time.Instant` (não `LocalDate`, diferente de
`releaseDate`/`airDate` existentes) porque `published_at` do TMDB vem com timestamp completo
(`2023-05-01T16:00:03.000Z`) — informação de hora é relevante pra ordenar vídeos por
recência, e o formato TMDB já é um `Instant` ISO-8601 válido (`Instant.parse`, sem parser
customizado).

`VideoDTO.key` é o identificador usado pra montar a URL de reprodução
(`https://www.youtube.com/watch?v={key}` no YouTube, `https://vimeo.com/{key}` no Vimeo) —
campo necessário mas ausente da lista de campos original do pedido; incluído porque sem ele o
vídeo não é reproduzível pelo cliente.

### Resolução por `Content.type`

| Campo | MOVIE | SERIES | SEASON / EPISODE |
|---|---|---|---|
| `budget` / `revenue` | de `TmdbMovieFullDetails`, `0` → `null` | sempre `null` | sempre `null` |
| `productionCompanies` | de `TmdbMovieFullDetails` | de `TmdbTvFullDetails` | herda do `TmdbTvFullDetails` da série (já buscado) |
| `crew` | de `credits.crew` (filtrado) | de `aggregate_credits.crew` (filtrado) | herda do `aggregate_credits.crew` da série |
| `videos` | de `TmdbMovieFullDetails.videos` | de `TmdbTvFullDetails.videos` | herda do `TmdbTvFullDetails.videos` da série |

`budget`/`revenue` tratando `0` como "não informado" segue a convenção do próprio TMDB (campo
numérico sem valor real vem como `0`, não `null`/ausente) — consistente com o "se tiver" do
pedido original.

## 5. Filtro de crew — lista fixa de jobs permitidos

```java
private static final Set<String> ALLOWED_CREW_JOBS = Set.of(
        "Director", "Screenplay", "Executive Producer", "Production Manager",
        "First Assistant Director", "Director of Photography", "Supervising Art Director");
```

Strings exatas do campo `job` do TMDB (`"Production Manager"`, M maiúsculo — diferente do
`"Production manager"` da mensagem original, corrigido porque o filtro compara string exata).

**Filme** (`credits.crew`, formato já é 1 linha por job — a mesma pessoa pode aparecer várias
vezes, uma por job): agrupar por `id`, manter só entradas cujo `job` está no set, juntar os
jobs batidos numa `List<String> jobs` por pessoa. Pessoa sem nenhum job no set não aparece no
resultado.

**Série** (`aggregate_credits.crew[].jobs[].job`): mesma lógica de agrupamento — filtrar os
`jobs[]` de cada membro pelos permitidos, descartar o membro inteiro se a lista filtrada ficar
vazia.

Um registro por pessoa (não um por job) — decisão confirmada: evita duplicar `id`/`name`/
`profilePath` quando a mesma pessoa tem múltiplos jobs que batem no filtro (ex: Director E
Executive Producer).

## 6. Vídeos — sem filtro

Retorna todos os vídeos que o TMDB manda em `videos.results`, sem filtrar por `type`/
`official`/`site` — decisão confirmada, bate com os campos pedidos originalmente; o cliente
decide o que exibir (trailer oficial vs teaser vs clipe etc).

## 7. Cache

Nenhuma mudança na estratégia de cache (`TmdbCacheConfig`, Caffeine, chave `(natural key,
language)`) — os campos novos vêm embutidos nas mesmas respostas já cacheadas
(`tmdbMovieFullDetails`/`tmdbTvFullDetails`), só o corpo da resposta HTTP fica maior.

## 8. Tratamento de falha

Sem mudança — os campos novos fazem parte da mesma resposta já coberta por
`TmdbUnavailableException` (502) quando o TMDB está fora do ar.

## 9. Docs a atualizar junto (fora deste arquivo)

- `openapi.yaml`: schema `ContentDetailsDTO` (5 campos novos) + 3 schemas novos
  (`ProductionCompanyDTO`, `CrewMemberDTO`, `VideoDTO`)
- `business-rules.md` + `business-rules-summary.md`: lista de jobs permitidos pra crew,
  `budget`/`revenue` tratando `0` como ausente, herança de `budget`/`revenue`/
  `productionCompanies`/`crew`/`videos` pra SEASON/EPISODE via `seriesTmdbId`, inclusão de
  `key` em vídeo
- `progress.md`: entrada do dia quando implementado

## 10. Testes

- `TmdbClientTest`: `append_to_response` de `getMovieFullDetails`/`getTvFullDetails` inclui
  `videos`
- `ContentDetailsServiceImplTest`: branch novo em `buildMovieDetails`/`buildSeriesDetails`/
  `buildSeasonDetails`/`buildEpisodeDetails` cobrindo os 5 campos novos; filtro de crew (job
  bate, job não bate, pessoa com múltiplos jobs batendo, crew nulo/vazio, membro sem job
  restante após filtro é descartado); `budget`/`revenue` zero→null e valor real preservado;
  mapeamento de `productionCompanies`/`videos`; herança dos 5 campos em SEASON/EPISODE a
  partir do `TvFullDetails` da série
- `ContentControllerIntegrationTest`: resposta de `/contents/{contentId}/details` inclui os
  campos novos no corpo (sem precisar mockar TMDB de verdade — já usa o padrão existente do
  teste)

## 11. O que não muda

- Nenhum campo novo é persistido em `Content` — tudo fica só na resposta do proxy
  (`ContentDetailsDTO`), igual ao resto do desenho já documentado em
  `docs/context/tmdb-proxy-design.md`. Não conflita com a regra de "nunca guardar
  metadado de filme/série no banco".
- Estratégia de cache, tratamento de erro e endpoints (`GET /contents/{contentId}/details`,
  `GET /contents/details`) inalterados.
