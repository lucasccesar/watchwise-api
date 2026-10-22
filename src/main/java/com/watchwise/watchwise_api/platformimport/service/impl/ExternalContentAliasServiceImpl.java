package com.watchwise.watchwise_api.platformimport.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieSearchResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbPersonSearchResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbSearchPage;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvSearchResult;
import com.watchwise.watchwise_api.content.dto.MediaReference;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.platformimport.entity.ExternalContentAlias;
import com.watchwise.watchwise_api.platformimport.model.AliasQuery;
import com.watchwise.watchwise_api.platformimport.model.AliasResolution;
import com.watchwise.watchwise_api.platformimport.model.AliasTrust;
import com.watchwise.watchwise_api.platformimport.model.ImportTarget;
import com.watchwise.watchwise_api.platformimport.repository.ExternalContentAliasRepository;
import com.watchwise.watchwise_api.platformimport.service.ExternalContentAliasService;
import com.watchwise.watchwise_api.platformimport.service.ImportIdentityNormalizer;
import com.watchwise.watchwise_api.platformimport.service.ImportLimits;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ExternalContentAliasServiceImpl implements ExternalContentAliasService {
    private static final int MAX_BATCH_SIZE = 200;
    private static final String LANGUAGE = "en-US";
    private static final String INSERT_ALIAS_SQL = """
            insert into external_content_aliases(id, user_id, scope_key, target_kind, original_title,
                normalized_title, release_year, target_key, target_type, target_tmdb_id, series_tmdb_id,
                season_number, episode_number, person_tmdb_id, provenance, trust, seed_version, created_at, updated_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, null, ?, ?)
            on conflict do nothing
            """;

    private final ExternalContentAliasRepository repository;
    private final TmdbClient tmdbClient;
    private final ImportIdentityNormalizer normalizer;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final Semaphore tmdbSearchSlots;

    public ExternalContentAliasServiceImpl(
            ExternalContentAliasRepository repository,
            TmdbClient tmdbClient,
            ImportIdentityNormalizer normalizer,
            ImportLimits limits,
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.repository = repository;
        this.tmdbClient = tmdbClient;
        this.normalizer = normalizer;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
        if (limits.maxConcurrentTmdbSearchesPerProcess() < 1) {
            throw new IllegalArgumentException("TMDB search concurrency limit must be positive");
        }
        this.tmdbSearchSlots = new Semaphore(limits.maxConcurrentTmdbSearchesPerProcess());
    }

    @Override
    public AliasResolution resolve(UUID owner, AliasQuery query) {
        return resolveBatch(owner, List.of(query)).getFirst();
    }

    @Override
    public List<AliasResolution> resolveBatch(UUID owner, List<AliasQuery> queries) {
        if (owner == null) throw new BadRequestException("Alias lookup owner is required");
        if (queries == null) throw new BadRequestException("Alias query list is required");
        if (queries.isEmpty()) return List.of();

        LinkedHashMap<QueryKey, AliasQuery> uniqueQueries = new LinkedHashMap<>();
        List<QueryKey> orderedKeys = new ArrayList<>(queries.size());
        for (AliasQuery query : queries) {
            if (query == null) throw new BadRequestException("Alias query is required");
            QueryKey key = key(query);
            orderedKeys.add(key);
            uniqueQueries.putIfAbsent(key, query);
            if (uniqueQueries.size() > MAX_BATCH_SIZE) {
                throw new BadRequestException("Alias batch cannot contain more than 200 distinct queries");
            }
        }

        Map<QueryKey, AliasResolution> resolved = resolveExisting(owner, uniqueQueries);
        for (Map.Entry<QueryKey, AliasQuery> entry : uniqueQueries.entrySet()) {
            if (resolved.containsKey(entry.getKey())) continue;
            AliasResolution result = searchAndClassify(entry.getValue());
            resolved.put(entry.getKey(), result);
        }
        return orderedKeys.stream().map(resolved::get).toList();
    }

    @Override
    public void remember(UUID owner, AliasQuery query, ImportTarget target, AliasTrust trust) {
        if (owner == null) throw new BadRequestException("Alias lookup owner is required");
        validateTarget(query, target);
        if (trust != AliasTrust.USER_CONFIRMED) {
            throw new BadRequestException("Only explicit user choices can be remembered in user scope");
        }
        persist(owner, query, target, AliasTrust.USER_CONFIRMED, "USER:" + owner, "user-confirmed");
    }

    private Map<QueryKey, AliasResolution> resolveExisting(UUID owner, Map<QueryKey, AliasQuery> queries) {
        Set<String> contentTitles = queries.entrySet().stream()
                .filter(entry -> entry.getKey().kind() == AliasQuery.Kind.CONTENT)
                .map(entry -> entry.getKey().normalizedTitle())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> personTitles = queries.entrySet().stream()
                .filter(entry -> entry.getKey().kind() == AliasQuery.Kind.PERSON)
                .map(entry -> entry.getKey().normalizedTitle())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Map<QueryKey, AliasResolution> resolutions = new LinkedHashMap<>();
        if (!contentTitles.isEmpty()) {
            List<ExternalContentAlias> aliases = repository.findApplicable("CONTENT", contentTitles, "USER:" + owner);
            resolveAliasGroup(queries, resolutions, aliases, AliasQuery.Kind.CONTENT, owner);
        }
        if (!personTitles.isEmpty()) {
            List<ExternalContentAlias> aliases = repository.findApplicable("PERSON", personTitles, "USER:" + owner);
            resolveAliasGroup(queries, resolutions, aliases, AliasQuery.Kind.PERSON, owner);
        }
        return resolutions;
    }

    private void resolveAliasGroup(
            Map<QueryKey, AliasQuery> queries,
            Map<QueryKey, AliasResolution> resolutions,
            List<ExternalContentAlias> aliases,
            AliasQuery.Kind kind,
            UUID owner) {
        Map<String, List<ExternalContentAlias>> byTitle = aliases.stream()
                .collect(Collectors.groupingBy(ExternalContentAlias::getNormalizedTitle));
        String ownerScope = "USER:" + owner;
        queries.forEach((key, query) -> {
            if (key.kind() != kind) return;
            List<ExternalContentAlias> titleAliases = byTitle.getOrDefault(key.normalizedTitle(), List.of());
            List<ExternalContentAlias> ownerAliases = titleAliases.stream()
                    .filter(alias -> ownerScope.equals(alias.getScopeKey()))
                    .filter(alias -> alias.getTrust() == AliasTrust.USER_CONFIRMED)
                    .filter(alias -> matches(alias, query, true))
                    .toList();
            List<ExternalContentAlias> applicable = ownerAliases.isEmpty()
                    ? titleAliases.stream()
                            .filter(alias -> "GLOBAL".equals(alias.getScopeKey()))
                            .filter(alias -> alias.getTrust() == AliasTrust.CURATED_SEED
                                    || alias.getTrust() == AliasTrust.BACKEND_VERIFIED)
                            .filter(alias -> matches(alias, query, false))
                            .toList()
                    : ownerAliases;
            if (applicable.isEmpty()) return;

            List<ImportTarget> candidates = applicable.stream()
                    .map(ExternalContentAlias::asImportTarget)
                    .distinct()
                    .toList();
            if (candidates.size() == 1 && (!ownerAliases.isEmpty() || query.year() != null)) {
                resolutions.put(key, AliasResolution.matched(candidates.getFirst()));
            } else {
                resolutions.put(key, AliasResolution.ambiguous(candidates));
            }
        });
    }

    private boolean matches(ExternalContentAlias alias, AliasQuery query, boolean ownerScoped) {
        if (query.year() != null && !Objects.equals(query.year(), alias.getReleaseYear())) return false;
        if (ownerScoped && query.year() == null && alias.getReleaseYear() != null) return false;
        if (query.kind() == AliasQuery.Kind.PERSON) return "PERSON".equals(alias.getTargetKind());
        if (!"CONTENT".equals(alias.getTargetKind())) return false;
        return query.contentType() == null || query.contentType().name().equals(alias.getTargetType());
    }

    private AliasResolution searchAndClassify(AliasQuery query) {
        if (query.kind() == AliasQuery.Kind.PERSON) {
            SearchOutcome outcome = searchPeople(query);
            return classify(query, outcome, true);
        }
        if (query.contentType() == ContentType.SEASON || query.contentType() == ContentType.EPISODE) {
            return AliasResolution.notFound();
        }

        List<SearchCandidate> candidates = new ArrayList<>();
        boolean unavailable = false;
        boolean truncated = false;
        if (query.contentType() == null || query.contentType() == ContentType.MOVIE) {
            SearchOutcome movies = searchMovies(query);
            candidates.addAll(movies.candidates());
            unavailable |= movies.unavailable();
            truncated |= movies.truncated();
        }
        if (query.contentType() == null || query.contentType() == ContentType.SERIES) {
            SearchOutcome series = searchTv(query);
            candidates.addAll(series.candidates());
            unavailable |= series.unavailable();
            truncated |= series.truncated();
        }
        SearchOutcome outcome = new SearchOutcome(dedupe(candidates), unavailable, truncated);
        return classify(query, outcome, false);
    }

    private SearchOutcome searchMovies(AliasQuery query) {
        TmdbLookupResult<TmdbSearchPage<TmdbMovieSearchResult>> lookup = withSearchPermit(
                () -> tmdbClient.searchMovies(query.title(), LANGUAGE, 1));
        if (lookup instanceof TmdbLookupResult.Unavailable<?>) return new SearchOutcome(List.of(), true, false);
        if (lookup instanceof TmdbLookupResult.NotFound<?>) return SearchOutcome.empty();
        TmdbSearchPage<TmdbMovieSearchResult> page = ((TmdbLookupResult.Found<TmdbSearchPage<TmdbMovieSearchResult>>) lookup).value();
        if (page == null || page.results() == null) return new SearchOutcome(List.of(), true, false);
        List<SearchCandidate> candidates = page.results().stream()
                .filter(result -> present(result.id()) && present(result.title()))
                .map(result -> new SearchCandidate(
                        new ImportTarget.ContentTarget(MediaReference.movie(result.id())),
                        normalizer.normalizeTitle(result.title()), parseYear(result.releaseDate())))
                .toList();
        return new SearchOutcome(candidates, false, isTruncated(page));
    }

    private SearchOutcome searchTv(AliasQuery query) {
        TmdbLookupResult<TmdbSearchPage<TmdbTvSearchResult>> lookup = withSearchPermit(
                () -> tmdbClient.searchTv(query.title(), LANGUAGE, 1));
        if (lookup instanceof TmdbLookupResult.Unavailable<?>) return new SearchOutcome(List.of(), true, false);
        if (lookup instanceof TmdbLookupResult.NotFound<?>) return SearchOutcome.empty();
        TmdbSearchPage<TmdbTvSearchResult> page = ((TmdbLookupResult.Found<TmdbSearchPage<TmdbTvSearchResult>>) lookup).value();
        if (page == null || page.results() == null) return new SearchOutcome(List.of(), true, false);
        List<SearchCandidate> candidates = page.results().stream()
                .filter(result -> present(result.id()) && present(result.name()))
                .map(result -> new SearchCandidate(
                        new ImportTarget.ContentTarget(MediaReference.series(result.id())),
                        normalizer.normalizeTitle(result.name()), parseYear(result.firstAirDate())))
                .toList();
        return new SearchOutcome(candidates, false, isTruncated(page));
    }

    private SearchOutcome searchPeople(AliasQuery query) {
        TmdbLookupResult<TmdbSearchPage<TmdbPersonSearchResult>> lookup = withSearchPermit(
                () -> tmdbClient.searchPeople(query.title(), LANGUAGE, 1));
        if (lookup instanceof TmdbLookupResult.Unavailable<?>) return new SearchOutcome(List.of(), true, false);
        if (lookup instanceof TmdbLookupResult.NotFound<?>) return SearchOutcome.empty();
        TmdbSearchPage<TmdbPersonSearchResult> page = ((TmdbLookupResult.Found<TmdbSearchPage<TmdbPersonSearchResult>>) lookup).value();
        if (page == null || page.results() == null) return new SearchOutcome(List.of(), true, false);
        List<SearchCandidate> candidates = page.results().stream()
                .filter(result -> present(result.id()) && present(result.name()))
                .map(result -> new SearchCandidate(
                        new ImportTarget.PersonTarget(result.id(), null), normalizer.normalizeTitle(result.name()), null))
                .toList();
        return new SearchOutcome(candidates, false, isTruncated(page));
    }

    private boolean isTruncated(TmdbSearchPage<?> page) {
        return page.totalPages() > 1 || page.totalResults() > page.results().size();
    }

    private <T> TmdbLookupResult<T> withSearchPermit(java.util.function.Supplier<TmdbLookupResult<T>> search) {
        if (!tmdbSearchSlots.tryAcquire()) return new TmdbLookupResult.Unavailable<>();
        try {
            TmdbLookupResult<T> result = search.get();
            return result == null ? new TmdbLookupResult.NotFound<>() : result;
        } finally {
            tmdbSearchSlots.release();
        }
    }

    private AliasResolution classify(AliasQuery query, SearchOutcome outcome, boolean person) {
        if (outcome.unavailable()) return AliasResolution.unavailable(toTargets(outcome.candidates()));
        List<SearchCandidate> exact = outcome.candidates().stream()
                .filter(candidate -> Objects.equals(candidate.normalizedTitle(), normalizer.normalizeTitle(query.title())))
                .filter(candidate -> person || query.year() == null || Objects.equals(candidate.year(), query.year()))
                .filter(candidate -> person || query.contentType() == null
                        || candidate.target() instanceof ImportTarget.ContentTarget content
                        && content.content().type() == query.contentType())
                .toList();
        List<ImportTarget> exactTargets = toTargets(exact);
        if (query.kind() == AliasQuery.Kind.CONTENT && query.year() == null) {
            return outcome.candidates().isEmpty() && !outcome.truncated() ? AliasResolution.notFound()
                    : AliasResolution.ambiguous(toTargets(outcome.candidates()));
        }
        if (exactTargets.size() == 1 && !outcome.truncated()) {
            ImportTarget target = exactTargets.getFirst();
            persist(null, query, target, AliasTrust.BACKEND_VERIFIED, "GLOBAL", "tmdb-search-exact");
            return AliasResolution.matched(target);
        }
        if (!exactTargets.isEmpty()) return AliasResolution.ambiguous(exactTargets);
        if (!outcome.candidates().isEmpty() || outcome.truncated()) {
            return AliasResolution.ambiguous(toTargets(outcome.candidates()));
        }
        return AliasResolution.notFound();
    }

    private List<ImportTarget> toTargets(Collection<SearchCandidate> candidates) {
        return candidates.stream().map(SearchCandidate::target).distinct().toList();
    }

    private List<SearchCandidate> dedupe(Collection<SearchCandidate> candidates) {
        return new ArrayList<>(candidates.stream().collect(Collectors.toMap(
                SearchCandidate::target,
                Function.identity(),
                (first, ignored) -> first,
                LinkedHashMap::new)).values());
    }

    private void persist(UUID owner, AliasQuery query, ImportTarget target, AliasTrust trust, String scopeKey, String provenance) {
        validateTarget(query, target);
        AliasFields fields = fields(target);
        String normalizedTitle = normalizer.normalizeTitle(query.title());
        LocalDateTime now = LocalDateTime.now(clock);
        transactionTemplate.executeWithoutResult(status -> jdbcTemplate.update(INSERT_ALIAS_SQL,
                UUID.randomUUID(), owner, scopeKey, query.kind().name(), query.title(), normalizedTitle,
                query.year(), fields.targetKey(), fields.targetType(), fields.targetTmdbId(), fields.seriesTmdbId(),
                fields.seasonNumber(), fields.episodeNumber(), fields.personTmdbId(), provenance, trust.name(),
                Timestamp.valueOf(now), Timestamp.valueOf(now)));
    }

    private void validateTarget(AliasQuery query, ImportTarget target) {
        if (query == null || target == null) throw new BadRequestException("Alias query and target are required");
        if (query.kind() == AliasQuery.Kind.PERSON) {
            if (!(target instanceof ImportTarget.PersonTarget)) throw new BadRequestException("Person alias requires a person target");
            return;
        }
        if (!(target instanceof ImportTarget.ContentTarget content)) {
            throw new BadRequestException("Content alias requires a content target");
        }
        if (query.contentType() != null && content.content().type() != query.contentType()) {
            throw new BadRequestException("Alias target type does not match the query");
        }
    }

    private AliasFields fields(ImportTarget target) {
        if (target instanceof ImportTarget.PersonTarget person) {
            return new AliasFields("PERSON|" + person.personTmdbId(), "PERSON", null, null, null, null, person.personTmdbId());
        }
        MediaReference reference = ((ImportTarget.ContentTarget) target).content();
        return switch (reference.type()) {
            case MOVIE, SERIES -> new AliasFields(
                    "CONTENT|" + reference.type().name() + "|" + reference.tmdbId(), reference.type().name(),
                    reference.tmdbId(), null, null, null, null);
            case SEASON -> new AliasFields(
                    "CONTENT|SEASON|" + reference.seriesTmdbId() + "|" + reference.seasonNumber(), "SEASON",
                    null, reference.seriesTmdbId(), reference.seasonNumber(), null, null);
            case EPISODE -> new AliasFields(
                    "CONTENT|EPISODE|" + reference.seriesTmdbId() + "|" + reference.seasonNumber() + "|" + reference.episodeNumber(), "EPISODE",
                    null, reference.seriesTmdbId(), reference.seasonNumber(), reference.episodeNumber(), null);
        };
    }

    private QueryKey key(AliasQuery query) {
        return new QueryKey(query.kind(), normalizer.normalizeTitle(query.title()), query.year(), query.contentType());
    }

    private Integer parseYear(String date) {
        if (!present(date)) return null;
        try {
            return LocalDate.parse(date).getYear();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private record QueryKey(AliasQuery.Kind kind, String normalizedTitle, Integer year, ContentType contentType) { }
    private record SearchCandidate(ImportTarget target, String normalizedTitle, Integer year) { }
    private record SearchOutcome(List<SearchCandidate> candidates, boolean unavailable, boolean truncated) {
        private static SearchOutcome empty() {
            return new SearchOutcome(List.of(), false, false);
        }
    }
    private record AliasFields(String targetKey, String targetType, String targetTmdbId, String seriesTmdbId,
                               Integer seasonNumber, Integer episodeNumber, String personTmdbId) { }
}
