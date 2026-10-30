# Desenho: proxy de TMDB pelo backend

> Documento de design pra revisão antes de codar o restante. Já existem no working
> tree (sem commit): a dependência `caffeine`/`spring-boot-starter-cache` no
> `pom.xml` e os 21 DTOs de resposta do TMDB em `common/tmdb/` descritos na seção 2.
> O resto (entidade, migration, service, controller, exceção, testes, docs) ainda
> não foi escrito.

## 1. Motivação

Hoje o cliente (app/web) chama o TMDB direto pra montar as telas de `telas.md`,
o que exige embutir a `api-key` do TMDB no cliente — extraível de um bundle web
ou de engenharia reversa de um app mobile. Passar essas chamadas pelo backend
mantém a chave só no servidor (`app.tmdb.api-key`, já usada hoje só pelo
`TmdbClient` interno dos jobs de notificação) e permite cachear entre usuários.

## 2. `TmdbClient` — 4 métodos novos, 1 chamada HTTP cada

Usando `append_to_response` do TMDB pra evitar N chamadas por tela (confirmado
em `tmdb-api-reduced.json`: disponível em `/movie/{id}`, `/tv/{id}`,
`/tv/{id}/season/{n}`, `/tv/{id}/season/{n}/episode/{e}`; o valor aceito é
exatamente o nome do sub-endpoint):

| Método | Chamada TMDB |
|---|---|
| `getMovieFullDetails(tmdbId, language)` | `GET /movie/{id}?append_to_response=credits,watch/providers,alternative_titles&language={language}` |
| `getTvFullDetails(tmdbId, language)` | `GET /tv/{id}?append_to_response=aggregate_credits,watch/providers,alternative_titles&language={language}` |
| `getSeasonFullDetails(seriesTmdbId, seasonNumber, language)` | `GET /tv/{id}/season/{n}?append_to_response=watch/providers&language={language}` |
| `getEpisodeFullDetails(seriesTmdbId, seasonNumber, episodeNumber, language)` | `GET /tv/{id}/season/{n}/episode/{e}?language={language}` (sem append — `guest_stars` já vem no corpo base; não existe `watch/providers` por episódio no TMDB) |

`SEASON`/`EPISODE` **não** buscam elenco regular próprio — reaproveitam
`aggregate_credits` da chamada de `SERIES` do mesmo `seriesTmdbId` (mesma
lógica que `Content.genres`/`countries` já usa pra `EPISODE`, resolvendo via a
`SERIES`-type `Content`). `SEASON` mantém `watch/providers` próprio porque
direitos de streaming podem mudar por temporada.

### DTOs de resposta TMDB já criados (`common/tmdb/`)

`TmdbMovieFullDetails`, `TmdbTvFullDetails`, `TmdbSeasonFullDetails`,
`TmdbEpisodeFullDetails` — cada um com os campos de detalhe base mais os
appends (`credits`/`aggregate_credits`, `watch/providers` mapeado como
`@JsonProperty("watch/providers")`, `alternative_titles`). Tipos de apoio:
`TmdbGenre`, `TmdbProductionCountry`, `TmdbCastMember`/`TmdbCredits` (movie),
`TmdbAggregateCastMember`/`TmdbAggregateRole`/`TmdbAggregateCredits` (tv —
formato diferente: `character` vem dentro de `roles[]`, não direto),
`TmdbProvider`/`TmdbRegionProviders`/`TmdbWatchProviders` (mapa por país,
`Map<String, TmdbRegionProviders>` — sem filtro de região na chamada, a
região é escolhida depois, em memória), `TmdbAlternativeTitleEntry` +
`TmdbMovieAlternativeTitles`(`titles`)/`TmdbTvAlternativeTitles`(`results` —
chave diferente entre movie e tv), `TmdbCreator`, `TmdbSeasonSummary` (dentro
de `tv.seasons`), `TmdbEpisodeSummary` (dentro de `season.episodes`),
`TmdbGuestStar`. Reaproveita o `TmdbNextEpisode` já existente.

Os DTOs de tracking (`TmdbMovieDetails`/`TmdbTvDetails`, usados pelos jobs de
notificação) **não mudam** — ficam separados, sem acoplar o job a um contrato
maior do que ele precisa.

## 3. Preferências de idioma/região no `User`

Duas colunas novas (`PATCH /users/me`, não no registro — é configuração de
conta, não campo de cadastro):

- `preferred_language` `VARCHAR(10) NOT NULL DEFAULT 'en-US'` — formato TMDB
  (`ISO-639-1_ISO-3166-1`), ex. `en-US`, `pt-BR`
- `preferred_region` `VARCHAR(2) NOT NULL DEFAULT 'US'` — ISO 3166-1 alpha-2

Migration `V41`, back-fill implícito pelo `DEFAULT` da coluna pros usuários já
existentes. Entram em `User` (`@Builder.Default`), `PatchUserDTO`/
`UserResponseDTO` (com overloads de compatibilidade pros construtores
menores já existentes), `UserServiceImpl.applyPatch` (mesmo padrão de
`description`/`banner`: só altera se não-null e diferente do atual, sem
`ConflictException` — não são campos únicos). `PublicUserProfileDTO` **não**
ganha esses campos — são preferência privada do dono, não parte do perfil
público.

`UserMapper.postUserDtoToUser` ignora os dois campos (igual `isEmailVerified`/
`createdAt`) — o `@Builder.Default` do `User` já aplica `en-US`/`US` sozinho
quando não mapeado, sem precisar de `@AfterMapping`.

### Cadeia de resolução de título (fallback)

Resolvida inteira a partir da mesma resposta já cacheada, sem chamada TMDB
extra:

1. `title`/`name` traduzido pro `preferredLanguage` do usuário — se vier
   preenchido, usa
2. senão, `alternative_titles` com `isoCode == preferredRegion` do usuário
3. senão, `original_title`/`original_name` (já vem no corpo base)

Só existe pra `MOVIE`/`SERIES` — TMDB não tem `alternative_titles` pra
`SEASON`/`EPISODE`; se o `name` desses vier vazio no idioma escolhido, não há
fallback melhor disponível (limitação aceita, não resolvida).

## 4. Cache

Caffeine (`spring-boot-starter-cache` + `caffeine`, já adicionados ao
`pom.xml`), não Redis — sem infra nova a provisionar, adequado ao estágio
atual do projeto (single-instance, sem load balancer documentado). Se o
projeto for pra múltiplas instâncias no futuro, troca por Redis sem mudar o
resto do desenho (`TmdbClient`/`ContentDetailsService` ficam isolados atrás
da mesma interface de cache).

Chave = `(tipo, tmdbId | seriesTmdbId+seasonNumber[+episodeNumber], language)`.
`region` **não** entra na chave — é só seleção em memória sobre
`watchProviders.results`/`alternativeTitles` já cacheados, então usuários com
regiões diferentes e mesmo idioma compartilham a mesma entrada de cache.

TTL configurável (`app.tmdb.details-cache-ttl-hours`, proposta: 24h — detalhe
de filme/série muda pouco). Cache guarda só respostas presentes — uma falha
do TMDB (`Optional.empty()`) não fica cacheada, tentativa seguinte tenta de
novo.

## 5. Endpoints novos na Watchwise API

Reaproveitando o padrão singular+batch de `/contents/{contentId}/stats` +
`/contents/stats?ids=`:

```
GET /contents/{contentId}/details          → ContentDetailsDTO
GET /contents/details?ids=<uuid,uuid,...>  → ContentDetailsDTO[]  (máx 100, mesma ordem)
```

`contentId` resolve pra `tmdbId`/`seriesTmdbId+seasonNumber[+episodeNumber]`
internamente — cliente não precisa saber a chave composta.

### `ContentDetailsDTO` (campos condicionais por `type`, mesmo padrão de
`MonthInReview`/`YearInReview`)

```java
record ContentDetailsDTO(
    UUID contentId, ContentType type,
    String title, String overview, String posterPath, String backdropPath,
    LocalDate releaseDate, Integer runtimeMinutes,
    List<String> genres, List<String> countries,
    List<CastMemberDTO> cast,          // MOVIE/SERIES: elenco próprio; SEASON/EPISODE: elenco da SERIES
    List<CastMemberDTO> guestStars,    // só EPISODE
    List<CreatorDTO> creators,         // só SERIES
    List<WatchProviderDTO> watchProviders,  // já filtrado pra preferredRegion do usuário
    List<SeasonSummaryDTO> seasons,    // só SERIES
    List<EpisodeSummaryDTO> episodes   // só SEASON
)
```

`CastMemberDTO(String name, String character, String profilePath)`,
`WatchProviderDTO(String providerName, String logoPath, String type)`
(`flatrate`/`rent`/`buy`), `CreatorDTO(String name, String profilePath)`,
`SeasonSummaryDTO(Integer seasonNumber, String name, String posterPath,
LocalDate airDate, Integer episodeCount)`, `EpisodeSummaryDTO(Integer
episodeNumber, String name, LocalDate airDate, Integer runtime, String
stillPath)`.

`ContentDetailsServiceImpl` resolve o `Content` por `contentId`
(`ContentRepository`), resolve `preferredLanguage`/`preferredRegion` do
usuário autenticado, chama o método certo do `TmdbClient` conforme
`Content.type` (com fallback pra `SERIES` quando `type` é `SEASON`/`EPISODE`,
pra elenco/gênero/país/criadores/título), monta o DTO. Sem `MapStruct` —
mapeamento manual, mesmo padrão de `ContentStatsServiceImpl` (fonte não é uma
entidade JPA única).

## 6. Tratamento de falha do TMDB

`TmdbUnavailableException` nova (`common/exception`) → `502 ApiError` via
`GlobalExceptionHandler`, lançada quando `TmdbClient` devolve
`Optional.empty()` (TMDB fora do ar após retry) pro fluxo do endpoint público
— diferente dos jobs de notificação, que só pulam o item silenciosamente.

## 7. Docs a atualizar junto (fora deste arquivo de design)

`openapi.yaml` (2 paths novos + schemas), `database-schema.md`/`.html` (2
colunas em `usuarios`/`users`), `business-rules.md` (cadeia de fallback de
título, TTL do cache, região não afeta a chave), `CLAUDE.md` (nova seção
descrevendo o proxy — deixar claro que não muda a regra de "nunca guardar
metadado no banco", já que é cache em memória, não persistência),
`progress.md` (entrada do dia quando implementado).

## 8. O que falta implementar (nada disso foi escrito ainda)

- `User` (colunas + migration V41), `PatchUserDTO`/`UserResponseDTO`/
  `UserMapper`/`UserServiceImpl.applyPatch`
- 4 métodos novos em `TmdbClient` (os DTOs de resposta já existem)
- `CacheConfig` (Caffeine)
- `ContentDetailsDTO` + DTOs aninhados, `ContentDetailsService`/`Impl`
- `TmdbUnavailableException` + entrada em `GlobalExceptionHandler`
- 2 endpoints em `ContentController`
- Testes (unit de service/controller, `TmdbClientTest` novo por método via
  `MockRestServiceServer`, integração)
- Os 5 docs da seção 7
