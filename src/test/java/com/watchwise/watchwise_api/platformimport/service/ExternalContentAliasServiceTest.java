package com.watchwise.watchwise_api.platformimport.service;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieSearchResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbPersonSearchResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbSearchPage;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvSearchResult;
import com.watchwise.watchwise_api.content.dto.MediaReference;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.platformimport.model.AliasQuery;
import com.watchwise.watchwise_api.platformimport.model.AliasResolution;
import com.watchwise.watchwise_api.platformimport.model.AliasTrust;
import com.watchwise.watchwise_api.platformimport.model.ImportTarget;
import com.watchwise.watchwise_api.platformimport.service.impl.ExternalContentAliasServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({ExternalContentAliasServiceImpl.class, ImportIdentityNormalizer.class, ImportServiceTestConfiguration.class})
class ExternalContentAliasServiceTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired ExternalContentAliasService aliasService;
    @MockitoBean TmdbClient tmdbClient;

    @BeforeEach
    void clearAliases() {
        jdbc.execute("TRUNCATE external_content_aliases CASCADE");
        clearInvocations(tmdbClient);
    }

    @Test
    @DisplayName("[resolve] Should Reuse Owner Confirmed Alias - When The Global Mapping Differs")
    void shouldReuseOwnerConfirmedAliasWithoutChangingGlobalMapping() {
        UUID owner = user("confirmed-owner");
        UUID otherOwner = user("confirmed-other");
        AliasQuery query = new AliasQuery("Example", 2001, ContentType.MOVIE);
        insertGlobalMovieAlias("Example", "example", 2001, "123");
        ImportTarget selected = new ImportTarget.ContentTarget(MediaReference.movie("456"));

        aliasService.remember(owner, query, selected, AliasTrust.USER_CONFIRMED);

        assertThat(aliasService.resolve(owner, query).candidates()).containsExactly(selected);
        assertThat(aliasService.resolve(otherOwner, query).candidates())
                .containsExactly(new ImportTarget.ContentTarget(MediaReference.movie("123")));
        assertThat(jdbc.queryForObject("select target_tmdb_id from external_content_aliases where scope_key = 'GLOBAL'", String.class))
                .isEqualTo("123");
        verifyNoInteractions(tmdbClient);
    }

    @Test
    @DisplayName("[resolve] Should Not Reuse Year-Specific User Alias - When Query Omits Year")
    void shouldNotReuseYearSpecificUserAliasWhenQueryOmitsYear() {
        UUID owner = user("yearless-owner");
        AliasQuery datedQuery = new AliasQuery("Example", 2001, ContentType.MOVIE);
        AliasQuery undatedQuery = new AliasQuery("Example", null, ContentType.MOVIE);
        insertGlobalMovieAlias("Example", "example", 2001, "123");
        insertGlobalMovieAlias("Example", "example", 2005, "789");
        aliasService.remember(owner, datedQuery,
                new ImportTarget.ContentTarget(MediaReference.movie("456")), AliasTrust.USER_CONFIRMED);

        AliasResolution result = aliasService.resolve(owner, undatedQuery);

        assertThat(result.status()).isEqualTo(AliasResolution.Status.AMBIGUOUS);
        assertThat(result.candidates()).containsExactlyInAnyOrder(
                new ImportTarget.ContentTarget(MediaReference.movie("123")),
                new ImportTarget.ContentTarget(MediaReference.movie("789")));
        verifyNoInteractions(tmdbClient);
    }

    @Test
    @DisplayName("[resolve] Should Learn Unique Exact TMDB Result - When Complete Search Has One Match")
    void shouldLearnUniqueExactTmdbResultWhenCompleteSearchHasOneMatch() {
        UUID owner = user("learned-owner");
        AliasQuery query = new AliasQuery("Example Film", 2001, ContentType.MOVIE);
        when(tmdbClient.searchMovies("Example Film", "en-US", 1)).thenReturn(foundMovies(
                List.of(new TmdbMovieSearchResult("123", "Example Film", null, "2001-05-03")), 1, 1));

        AliasResolution first = aliasService.resolve(owner, query);
        AliasResolution second = aliasService.resolve(owner, query);

        assertThat(first.status()).isEqualTo(AliasResolution.Status.MATCHED);
        assertThat(first.candidates()).containsExactly(new ImportTarget.ContentTarget(MediaReference.movie("123")));
        assertThat(second).isEqualTo(first);
        assertThat(jdbc.queryForObject("select count(*) from external_content_aliases where scope_key = 'GLOBAL' and trust = 'BACKEND_VERIFIED'", Integer.class))
                .isEqualTo(1);
        verify(tmdbClient, times(1)).searchMovies("Example Film", "en-US", 1);
    }

    @Test
    @DisplayName("[resolve] Should Keep Multiple Exact Results Ambiguous - When Search Returns Multiple Exact Matches")
    void shouldKeepMultipleExactResultsAmbiguousWithoutPersistingGlobalAlias() {
        UUID owner = user("ambiguous-owner");
        AliasQuery query = new AliasQuery("The Example", 2001, ContentType.MOVIE);
        when(tmdbClient.searchMovies("The Example", "en-US", 1)).thenReturn(foundMovies(List.of(
                new TmdbMovieSearchResult("123", "The Example", null, "2001-01-01"),
                new TmdbMovieSearchResult("456", "The Example", null, "2001-12-31")), 1, 2));

        AliasResolution result = aliasService.resolve(owner, query);

        assertThat(result.status()).isEqualTo(AliasResolution.Status.AMBIGUOUS);
        assertThat(result.candidates()).containsExactlyInAnyOrder(
                new ImportTarget.ContentTarget(MediaReference.movie("123")),
                new ImportTarget.ContentTarget(MediaReference.movie("456")));
        assertThat(jdbc.queryForObject("select count(*) from external_content_aliases where trust = 'BACKEND_VERIFIED'", Integer.class))
                .isZero();
    }

    @Test
    @DisplayName("[resolve] Should Require Review Without Year - When Content Search Finds A Single Exact Title")
    void shouldRequireReviewWithoutYearWhenContentSearchFindsSingleExactTitle() {
        UUID owner = user("missing-year-owner");
        AliasQuery query = new AliasQuery(AliasQuery.Kind.CONTENT, "Example Film", null, ContentType.MOVIE);
        when(tmdbClient.searchMovies("Example Film", "en-US", 1)).thenReturn(foundMovies(
                List.of(new TmdbMovieSearchResult("123", "Example Film", null, "2001-05-03")), 1, 1));

        AliasResolution result = aliasService.resolve(owner, query);

        assertThat(result.status()).isEqualTo(AliasResolution.Status.AMBIGUOUS);
        assertThat(result.candidates()).containsExactly(new ImportTarget.ContentTarget(MediaReference.movie("123")));
        assertThat(jdbc.queryForObject("select count(*) from external_content_aliases where scope_key = 'GLOBAL'", Integer.class))
                .isZero();
    }

    @Test
    @DisplayName("[resolve] Should Keep Truncated Yearless Search Ambiguous - When First Page Has No Results")
    void shouldKeepTruncatedYearlessSearchAmbiguousWhenFirstPageHasNoResults() {
        UUID owner = user("truncated-yearless-owner");
        AliasQuery query = new AliasQuery(AliasQuery.Kind.CONTENT, "Example Film", null, ContentType.MOVIE);
        when(tmdbClient.searchMovies("Example Film", "en-US", 1)).thenReturn(foundMovies(List.of(), 2, 21));

        AliasResolution result = aliasService.resolve(owner, query);

        assertThat(result.status()).isEqualTo(AliasResolution.Status.AMBIGUOUS);
        assertThat(result.candidates()).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from external_content_aliases where scope_key = 'GLOBAL'", Integer.class))
                .isZero();
    }

    @Test
    @DisplayName("[resolve] Should Require Review For Truncated Search - When An Exact Result Is On The First Page")
    void shouldRequireReviewForTruncatedSearchWhenExactResultIsOnFirstPage() {
        UUID owner = user("truncated-owner");
        AliasQuery query = new AliasQuery("Example Film", 2001, ContentType.MOVIE);
        when(tmdbClient.searchMovies("Example Film", "en-US", 1)).thenReturn(foundMovies(
                List.of(new TmdbMovieSearchResult("123", "Example Film", null, "2001-05-03")), 2, 21));

        AliasResolution result = aliasService.resolve(owner, query);

        assertThat(result.status()).isEqualTo(AliasResolution.Status.AMBIGUOUS);
        assertThat(result.candidates()).containsExactly(new ImportTarget.ContentTarget(MediaReference.movie("123")));
        assertThat(jdbc.queryForObject("select count(*) from external_content_aliases where trust = 'BACKEND_VERIFIED'", Integer.class))
                .isZero();
    }

    @Test
    @DisplayName("[resolve] Should Require Review For Incomplete Search Page - When Total Results Exceed Returned Results")
    void shouldRequireReviewForIncompleteSearchPageWhenTotalResultsExceedReturnedResults() {
        UUID owner = user("incomplete-page-owner");
        AliasQuery query = new AliasQuery("Example Film", 2001, ContentType.MOVIE);
        when(tmdbClient.searchMovies("Example Film", "en-US", 1)).thenReturn(foundMovies(
                List.of(new TmdbMovieSearchResult("123", "Example Film", null, "2001-05-03")), 1, 21));

        AliasResolution result = aliasService.resolve(owner, query);

        assertThat(result.status()).isEqualTo(AliasResolution.Status.AMBIGUOUS);
        assertThat(result.candidates()).containsExactly(new ImportTarget.ContentTarget(MediaReference.movie("123")));
        assertThat(jdbc.queryForObject("select count(*) from external_content_aliases where trust = 'BACKEND_VERIFIED'", Integer.class))
                .isZero();
    }

    @Test
    @DisplayName("[resolve] Should Not AutoMatch Wrong Year - When Search Returns Same Title From Another Year")
    void shouldNotAutoMatchWrongYearWhenSearchReturnsSameTitleFromAnotherYear() {
        UUID owner = user("year-check-owner");
        AliasQuery query = new AliasQuery("Example Film", 2001, ContentType.MOVIE);
        when(tmdbClient.searchMovies("Example Film", "en-US", 1)).thenReturn(foundMovies(
                List.of(new TmdbMovieSearchResult("123", "Example Film", null, "2000-05-03")), 1, 1));

        AliasResolution result = aliasService.resolve(owner, query);

        assertThat(result.status()).isEqualTo(AliasResolution.Status.AMBIGUOUS);
        assertThat(result.candidates()).containsExactly(new ImportTarget.ContentTarget(MediaReference.movie("123")));
        assertThat(jdbc.queryForObject("select count(*) from external_content_aliases where trust = 'BACKEND_VERIFIED'", Integer.class))
                .isZero();
    }

    @Test
    @DisplayName("[resolve] Should Not Reuse Wrong Media Type Alias - When Movie Query Matches A Series Title")
    void shouldNotReuseWrongMediaTypeAliasWhenMovieQueryMatchesSeriesTitle() {
        UUID owner = user("type-check-owner");
        AliasQuery query = new AliasQuery("Example Show", 2001, ContentType.MOVIE);
        jdbc.update("""
                insert into external_content_aliases(id, scope_key, target_kind, original_title, normalized_title,
                    release_year, target_key, target_type, target_tmdb_id, provenance, trust, created_at, updated_at)
                values (?, 'GLOBAL', 'CONTENT', 'Example Show', 'example show', 2001, 'CONTENT|SERIES|700',
                    'SERIES', '700', 'test', 'CURATED_SEED', now(), now())
                """, UUID.randomUUID());
        when(tmdbClient.searchMovies("Example Show", "en-US", 1)).thenReturn(new TmdbLookupResult.NotFound<>());

        assertThat(aliasService.resolve(owner, query).status()).isEqualTo(AliasResolution.Status.NOT_FOUND);
        verify(tmdbClient, times(1)).searchMovies("Example Show", "en-US", 1);
    }

    @Test
    @DisplayName("[resolve] Should Search Movie And TV Catalogs - When Content Type Is Unknown")
    void shouldSearchMovieAndTvCatalogsWhenContentTypeIsUnknown() {
        UUID owner = user("unknown-type-owner");
        AliasQuery query = new AliasQuery(AliasQuery.Kind.CONTENT, "Example Work", 2001, null);
        when(tmdbClient.searchMovies("Example Work", "en-US", 1)).thenReturn(foundMovies(
                List.of(new TmdbMovieSearchResult("123", "Example Work", null, "2001-05-03")), 1, 1));
        when(tmdbClient.searchTv("Example Work", "en-US", 1)).thenReturn(new TmdbLookupResult.NotFound<>());

        AliasResolution result = aliasService.resolve(owner, query);

        assertThat(result.status()).isEqualTo(AliasResolution.Status.MATCHED);
        assertThat(result.candidates()).containsExactly(new ImportTarget.ContentTarget(MediaReference.movie("123")));
        verify(tmdbClient, times(1)).searchMovies("Example Work", "en-US", 1);
        verify(tmdbClient, times(1)).searchTv("Example Work", "en-US", 1);
    }

    @Test
    @DisplayName("[resolve] Should Distinguish NotFound From Unavailable - When TMDB Search Has Different Outcomes")
    void shouldDistinguishNotFoundFromUnavailableWhenTmdbSearchHasDifferentOutcomes() {
        UUID owner = user("search-status-owner");
        AliasQuery notFound = new AliasQuery("Missing Film", 2001, ContentType.MOVIE);
        AliasQuery unavailable = new AliasQuery("Offline Film", 2001, ContentType.MOVIE);
        when(tmdbClient.searchMovies("Missing Film", "en-US", 1)).thenReturn(new TmdbLookupResult.NotFound<>());
        when(tmdbClient.searchMovies("Offline Film", "en-US", 1)).thenReturn(new TmdbLookupResult.Unavailable<>());

        assertThat(aliasService.resolve(owner, notFound).status()).isEqualTo(AliasResolution.Status.NOT_FOUND);
        assertThat(aliasService.resolve(owner, unavailable).status()).isEqualTo(AliasResolution.Status.UNAVAILABLE);
    }

    @Test
    @DisplayName("[resolve] Should Return Unavailable - When Movie Search Page Is Null")
    void shouldReturnUnavailableWhenMovieSearchPageIsNull() {
        UUID owner = user("null-movie-page-owner");
        AliasQuery query = new AliasQuery("Malformed Film", 2001, ContentType.MOVIE);
        when(tmdbClient.searchMovies("Malformed Film", "en-US", 1)).thenReturn(new TmdbLookupResult.Found<>(null));

        AliasResolution result = aliasService.resolve(owner, query);

        assertThat(result.status()).isEqualTo(AliasResolution.Status.UNAVAILABLE);
        verify(tmdbClient, times(1)).searchMovies("Malformed Film", "en-US", 1);
    }

    @Test
    @DisplayName("[resolve] Should Return Unavailable - When Movie Search Results Are Null")
    void shouldReturnUnavailableWhenMovieSearchResultsAreNull() {
        UUID owner = user("null-movie-results-owner");
        AliasQuery query = new AliasQuery("Malformed Film", 2001, ContentType.MOVIE);
        when(tmdbClient.searchMovies("Malformed Film", "en-US", 1)).thenReturn(new TmdbLookupResult.Found<>(
                new TmdbSearchPage<>(1, null, 1, 0)));

        AliasResolution result = aliasService.resolve(owner, query);

        assertThat(result.status()).isEqualTo(AliasResolution.Status.UNAVAILABLE);
        verify(tmdbClient, times(1)).searchMovies("Malformed Film", "en-US", 1);
    }

    @Test
    @DisplayName("[resolve] Should Return Unavailable - When TV Search Page Is Null")
    void shouldReturnUnavailableWhenTvSearchPageIsNull() {
        UUID owner = user("null-tv-page-owner");
        AliasQuery query = new AliasQuery("Malformed Series", 2001, ContentType.SERIES);
        when(tmdbClient.searchTv("Malformed Series", "en-US", 1)).thenReturn(new TmdbLookupResult.Found<>(null));

        AliasResolution result = aliasService.resolve(owner, query);

        assertThat(result.status()).isEqualTo(AliasResolution.Status.UNAVAILABLE);
        verify(tmdbClient, times(1)).searchTv("Malformed Series", "en-US", 1);
    }

    @Test
    @DisplayName("[resolve] Should Return Unavailable - When TV Search Results Are Null")
    void shouldReturnUnavailableWhenTvSearchResultsAreNull() {
        UUID owner = user("null-tv-results-owner");
        AliasQuery query = new AliasQuery("Malformed Series", 2001, ContentType.SERIES);
        when(tmdbClient.searchTv("Malformed Series", "en-US", 1)).thenReturn(new TmdbLookupResult.Found<>(
                new TmdbSearchPage<>(1, null, 1, 0)));

        AliasResolution result = aliasService.resolve(owner, query);

        assertThat(result.status()).isEqualTo(AliasResolution.Status.UNAVAILABLE);
        verify(tmdbClient, times(1)).searchTv("Malformed Series", "en-US", 1);
    }

    @Test
    @DisplayName("[resolve] Should Return Unavailable - When Person Search Page Is Null")
    void shouldReturnUnavailableWhenPersonSearchPageIsNull() {
        UUID owner = user("null-person-page-owner");
        AliasQuery query = AliasQuery.person("Malformed Person");
        when(tmdbClient.searchPeople("Malformed Person", "en-US", 1)).thenReturn(new TmdbLookupResult.Found<>(null));

        AliasResolution result = aliasService.resolve(owner, query);

        assertThat(result.status()).isEqualTo(AliasResolution.Status.UNAVAILABLE);
        verify(tmdbClient, times(1)).searchPeople("Malformed Person", "en-US", 1);
    }

    @Test
    @DisplayName("[resolve] Should Return Unavailable - When Person Search Results Are Null")
    void shouldReturnUnavailableWhenPersonSearchResultsAreNull() {
        UUID owner = user("null-person-results-owner");
        AliasQuery query = AliasQuery.person("Malformed Person");
        when(tmdbClient.searchPeople("Malformed Person", "en-US", 1)).thenReturn(new TmdbLookupResult.Found<>(
                new TmdbSearchPage<>(1, null, 1, 0)));

        AliasResolution result = aliasService.resolve(owner, query);

        assertThat(result.status()).isEqualTo(AliasResolution.Status.UNAVAILABLE);
        verify(tmdbClient, times(1)).searchPeople("Malformed Person", "en-US", 1);
    }

    @Test
    @DisplayName("[resolve] Should Search People By Exact Name - When Query Kind Is Person")
    void shouldSearchPeopleByExactNameWhenQueryKindIsPerson() {
        UUID owner = user("person-owner");
        AliasQuery query = AliasQuery.person("Ada Example");
        when(tmdbClient.searchPeople("Ada Example", "en-US", 1)).thenReturn(new TmdbLookupResult.Found<>(
                new TmdbSearchPage<>(1, List.of(new TmdbPersonSearchResult("9001", "Ada Example", null)), 1, 1)));

        AliasResolution result = aliasService.resolve(owner, query);

        assertThat(result.status()).isEqualTo(AliasResolution.Status.MATCHED);
        assertThat(result.candidates()).containsExactly(new ImportTarget.PersonTarget("9001", null));
        verify(tmdbClient, times(1)).searchPeople("Ada Example", "en-US", 1);
        verifyNoMoreInteractions(tmdbClient);
    }

    @Test
    @DisplayName("[resolve] Should Keep Same-Name People Ambiguous - When Multiple Exact People Match")
    void shouldKeepSameNamePeopleAmbiguousWhenMultipleExactPeopleMatch() {
        UUID owner = user("duplicate-person-owner");
        AliasQuery query = AliasQuery.person("Alex Example");
        when(tmdbClient.searchPeople("Alex Example", "en-US", 1)).thenReturn(new TmdbLookupResult.Found<>(
                new TmdbSearchPage<>(1, List.of(
                        new TmdbPersonSearchResult("9001", "Alex Example", null),
                        new TmdbPersonSearchResult("9002", "Alex Example", null)), 1, 2)));

        AliasResolution result = aliasService.resolve(owner, query);

        assertThat(result.status()).isEqualTo(AliasResolution.Status.AMBIGUOUS);
        assertThat(result.candidates()).containsExactlyInAnyOrder(
                new ImportTarget.PersonTarget("9001", null), new ImportTarget.PersonTarget("9002", null));
        assertThat(jdbc.queryForObject("select count(*) from external_content_aliases where target_kind = 'PERSON'", Integer.class))
                .isZero();
    }

    @Test
    @DisplayName("[resolve] Should Avoid Flat Search - When An Episode Alias Is Missing")
    void shouldAvoidFlatSearchWhenEpisodeAliasIsMissing() {
        UUID owner = user("episode-owner");
        AliasQuery query = new AliasQuery(AliasQuery.Kind.CONTENT, "Example Episode", 2001, ContentType.EPISODE);

        assertThat(aliasService.resolve(owner, query).status()).isEqualTo(AliasResolution.Status.NOT_FOUND);
        verifyNoInteractions(tmdbClient);
    }

    @Test
    @DisplayName("[resolveBatch] Should Deduplicate Normalized Queries - When Same Title Is Repeated")
    void shouldDeduplicateNormalizedQueriesWhenSameTitleIsRepeated() {
        UUID owner = user("batch-owner");
        when(tmdbClient.searchMovies("Example Film", "en-US", 1)).thenReturn(foundMovies(
                List.of(new TmdbMovieSearchResult("123", "Example Film", null, "2001-05-03")), 1, 1));
        List<AliasQuery> queries = List.of(
                new AliasQuery("Example Film", 2001, ContentType.MOVIE),
                new AliasQuery(" example   film ", 2001, ContentType.MOVIE),
                new AliasQuery("Example Film", 2001, ContentType.MOVIE));

        List<AliasResolution> results = aliasService.resolveBatch(owner, queries);

        assertThat(results).hasSize(3).allMatch(result -> result.status() == AliasResolution.Status.MATCHED);
        verify(tmdbClient, times(1)).searchMovies("Example Film", "en-US", 1);
    }

    @Test
    @DisplayName("[resolveBatch] Should Reject Too Many Distinct Queries - When Batch Exceeds The Limit")
    void shouldRejectTooManyDistinctQueriesBeforeCallingTmdb() {
        UUID owner = user("large-batch-owner");
        List<AliasQuery> queries = new ArrayList<>();
        for (int i = 0; i < 201; i++) queries.add(new AliasQuery("Title " + i, 2001, ContentType.MOVIE));

        assertThatThrownBy(() -> aliasService.resolveBatch(owner, queries)).isInstanceOf(BadRequestException.class);
        verifyNoInteractions(tmdbClient);
    }

    private TmdbLookupResult<TmdbSearchPage<TmdbMovieSearchResult>> foundMovies(
            List<TmdbMovieSearchResult> results, int totalPages, long totalResults) {
        return new TmdbLookupResult.Found<>(new TmdbSearchPage<>(1, results, totalPages, totalResults));
    }

    private UUID user(String suffix) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users(id, username, name, email, password, profile_picture, created_at, updated_at) values (?, ?, ?, ?, 'x', 'x', now(), now())",
                id, suffix, suffix, suffix + "@test.local");
        return id;
    }

    private void insertGlobalMovieAlias(String title, String normalizedTitle, int year, String tmdbId) {
        jdbc.update("""
                insert into external_content_aliases(id, scope_key, target_kind, original_title, normalized_title,
                    release_year, target_key, target_type, target_tmdb_id, provenance, trust, created_at, updated_at)
                values (?, 'GLOBAL', 'CONTENT', ?, ?, ?, ?, 'MOVIE', ?, 'test', 'CURATED_SEED', now(), now())
                """, UUID.randomUUID(), title, normalizedTitle, year, "CONTENT|MOVIE|" + tmdbId, tmdbId);
    }
}
