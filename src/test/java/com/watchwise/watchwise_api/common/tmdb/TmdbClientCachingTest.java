package com.watchwise.watchwise_api.common.tmdb;

import com.github.benmanes.caffeine.cache.Cache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Exercises {@link TmdbClient} wired with the real {@link TmdbCacheConfig} caches — {@link TmdbClientTest}
 * constructs {@code TmdbClient} with disposable, per-test Caffeine caches that are never reused across
 * calls, so it never exercises the actual cache-hit / never-cache-a-failure behavior these caches exist
 * for (see {@code TmdbClient.cachedLookup} — a computed {@code Unavailable} result must never be cached).
 */
@SpringJUnitConfig
@ContextConfiguration(classes = {TmdbCacheConfig.class, TmdbClientCachingTest.Config.class})
@TestPropertySource(properties = {
        "app.tmdb.details-cache-ttl-hours=24",
        "app.tmdb.search-cache-ttl-minutes=10",
        "app.tmdb.search-cache-max-size=10000"
})
@Import(TmdbCacheConfig.class)
class TmdbClientCachingTest {

    @TestConfiguration
    static class Config {
        @Bean
        RestClient.Builder tmdbRestClientBuilder() {
            return RestClient.builder().baseUrl("https://api.themoviedb.org/3");
        }

        @Bean
        MockRestServiceServer mockRestServiceServer(RestClient.Builder builder) {
            return MockRestServiceServer.bindTo(builder).build();
        }

        @Bean
        @DependsOn("mockRestServiceServer")
        RestClient tmdbRestClient(RestClient.Builder builder) {
            return builder.build();
        }

        @Bean
        TmdbClient tmdbClient(RestClient tmdbRestClient,
                Cache<String, TmdbLookupResult<TmdbMovieFullDetails>> tmdbMovieFullDetailsCache,
                Cache<String, TmdbLookupResult<TmdbTvFullDetails>> tmdbTvFullDetailsCache,
                Cache<String, TmdbLookupResult<TmdbSeasonFullDetails>> tmdbSeasonFullDetailsCache,
                Cache<String, TmdbLookupResult<TmdbEpisodeFullDetails>> tmdbEpisodeFullDetailsCache,
                Cache<TmdbSearchCacheKey, TmdbLookupResult<TmdbSearchPage<TmdbMovieSearchResult>>> tmdbMovieSearchCache,
                Cache<TmdbSearchCacheKey, TmdbLookupResult<TmdbSearchPage<TmdbTvSearchResult>>> tmdbTvSearchCache,
                Cache<TmdbSearchCacheKey, TmdbLookupResult<TmdbSearchPage<TmdbPersonSearchResult>>> tmdbPersonSearchCache,
                Cache<TmdbSearchCacheKey, TmdbLookupResult<TmdbSearchPage<TmdbMultiSearchResult>>> tmdbMultiSearchCache) {
            return new TmdbClient(tmdbRestClient, tmdbMovieFullDetailsCache, tmdbTvFullDetailsCache,
                    tmdbSeasonFullDetailsCache, tmdbEpisodeFullDetailsCache,
                    tmdbMovieSearchCache, tmdbTvSearchCache, tmdbPersonSearchCache, tmdbMultiSearchCache);
        }
    }

    @Autowired
    private TmdbClient tmdbClient;

    @Autowired
    private MockRestServiceServer mockServer;

    @Autowired
    private Cache<String, TmdbLookupResult<TmdbMovieFullDetails>> tmdbMovieFullDetailsCache;

    @Autowired
    private Cache<String, TmdbLookupResult<TmdbTvFullDetails>> tmdbTvFullDetailsCache;

    @Autowired
    private Cache<String, TmdbLookupResult<TmdbSeasonFullDetails>> tmdbSeasonFullDetailsCache;

    @Autowired
    private Cache<String, TmdbLookupResult<TmdbEpisodeFullDetails>> tmdbEpisodeFullDetailsCache;

    @Autowired
    private Cache<TmdbSearchCacheKey, TmdbLookupResult<TmdbSearchPage<TmdbMovieSearchResult>>> tmdbMovieSearchCache;

    @Autowired
    private Cache<TmdbSearchCacheKey, TmdbLookupResult<TmdbSearchPage<TmdbTvSearchResult>>> tmdbTvSearchCache;

    @Autowired
    private Cache<TmdbSearchCacheKey, TmdbLookupResult<TmdbSearchPage<TmdbPersonSearchResult>>> tmdbPersonSearchCache;

    @Autowired
    private Cache<TmdbSearchCacheKey, TmdbLookupResult<TmdbSearchPage<TmdbMultiSearchResult>>> tmdbMultiSearchCache;

    @BeforeEach
    void resetExpectationsAndCache() {
        mockServer.reset();
        tmdbMovieFullDetailsCache.invalidateAll();
        tmdbTvFullDetailsCache.invalidateAll();
        tmdbSeasonFullDetailsCache.invalidateAll();
        tmdbEpisodeFullDetailsCache.invalidateAll();
        tmdbMovieSearchCache.invalidateAll();
        tmdbTvSearchCache.invalidateAll();
        tmdbPersonSearchCache.invalidateAll();
        tmdbMultiSearchCache.invalidateAll();
    }

    @Test
    @DisplayName("[searchMovies] Should Share Cached Page - When Query Case And Surrounding Spaces Differ")
    void shouldShareCachedPageWhenQueryCaseAndSurroundingSpacesDiffer() {
        mockServer.expect(requestTo(startsWith("https://api.themoviedb.org/3/search/movie?")))
                .andExpect(queryParam("query", "Matrix"))
                .andRespond(movieSearchSuccess(1, "The Matrix"));

        var first = tmdbClient.searchMovies(" Matrix ", "en-US", 1).toOptional().orElseThrow();
        var second = tmdbClient.searchMovies("matrix", "en-US", 1).toOptional().orElseThrow();

        assertThat(first.results()).extracting(TmdbMovieSearchResult::title).containsExactly("The Matrix");
        assertThat(second.results()).extracting(TmdbMovieSearchResult::title).containsExactly("The Matrix");
        mockServer.verify();
    }

    @Test
    @DisplayName("[searchMovies] Should Fetch Separate Pages - When Language Changes")
    void shouldFetchSeparatePagesWhenSearchLanguageChanges() {
        mockServer.expect(requestTo(startsWith("https://api.themoviedb.org/3/search/movie?")))
                .andExpect(queryParam("language", "en-US"))
                .andRespond(movieSearchSuccess(1, "The Matrix"));
        mockServer.expect(requestTo(startsWith("https://api.themoviedb.org/3/search/movie?")))
                .andExpect(queryParam("language", "pt-BR"))
                .andRespond(movieSearchSuccess(1, "Matrix"));

        var english = tmdbClient.searchMovies("Matrix", "en-US", 1).toOptional().orElseThrow();
        var portuguese = tmdbClient.searchMovies("Matrix", "pt-BR", 1).toOptional().orElseThrow();

        assertThat(english.results()).extracting(TmdbMovieSearchResult::title).containsExactly("The Matrix");
        assertThat(portuguese.results()).extracting(TmdbMovieSearchResult::title).containsExactly("Matrix");
        mockServer.verify();
    }

    @Test
    @DisplayName("[searchMovies] Should Fetch Separate Pages - When Page Changes")
    void shouldFetchSeparatePagesWhenSearchPageChanges() {
        mockServer.expect(requestTo(startsWith("https://api.themoviedb.org/3/search/movie?")))
                .andExpect(queryParam("page", "1"))
                .andRespond(movieSearchSuccess(1, "The Matrix"));
        mockServer.expect(requestTo(startsWith("https://api.themoviedb.org/3/search/movie?")))
                .andExpect(queryParam("page", "2"))
                .andRespond(movieSearchSuccess(2, "The Matrix Reloaded"));

        var first = tmdbClient.searchMovies("Matrix", "en-US", 1).toOptional().orElseThrow();
        var second = tmdbClient.searchMovies("Matrix", "en-US", 2).toOptional().orElseThrow();

        assertThat(first.page()).isEqualTo(1);
        assertThat(first.results()).extracting(TmdbMovieSearchResult::title).containsExactly("The Matrix");
        assertThat(second.page()).isEqualTo(2);
        assertThat(second.results()).extracting(TmdbMovieSearchResult::title).containsExactly("The Matrix Reloaded");
        mockServer.verify();
    }

    @Test
    @DisplayName("[searchMulti] Should Keep Separate Cached Pages - When Movie Search Uses The Same Query")
    void shouldKeepSeparateCachedPagesWhenMovieAndMultiSearchUseTheSameQuery() {
        mockServer.expect(requestTo(startsWith("https://api.themoviedb.org/3/search/movie?")))
                .andRespond(movieSearchSuccess(1, "The Matrix"));
        mockServer.expect(requestTo(startsWith("https://api.themoviedb.org/3/search/multi?")))
                .andRespond(withSuccess("""
                        {"page":1,"total_pages":1,"total_results":1,"results":[
                          {"id":6384,"media_type":"person","name":"Keanu Reeves","profile_path":"/keanu.jpg"}]}
                        """, MediaType.APPLICATION_JSON));

        var movie = tmdbClient.searchMovies("Matrix", "en-US", 1).toOptional().orElseThrow();
        var multi = tmdbClient.searchMulti("Matrix", "en-US", 1).toOptional().orElseThrow();
        var repeated = tmdbClient.searchMulti("Matrix", "en-US", 1).toOptional().orElseThrow();

        assertThat(movie.results()).extracting(TmdbMovieSearchResult::title).containsExactly("The Matrix");
        assertThat(multi.results()).extracting(TmdbMultiSearchResult::mediaType).containsExactly("person");
        assertThat(repeated.results()).extracting(TmdbMultiSearchResult::name).containsExactly("Keanu Reeves");
        mockServer.verify();
    }

    @Test
    @DisplayName("[searchMovies] Should Make One TMDB Call - When Eight Concurrent Searches Use The Same Key")
    void shouldMakeOneTmdbCallWhenEightConcurrentSearchesUseTheSameKey() throws Exception {
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch requestStarted = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        mockServer.expect(requestTo(startsWith("https://api.themoviedb.org/3/search/movie?")))
                .andRespond(request -> {
                    requestStarted.countDown();
                    try {
                        assertThat(releaseResponse.await(5, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(e);
                    }
                    return movieSearchSuccess(1, "The Matrix").createResponse(request);
                });

        ExecutorService executor = Executors.newFixedThreadPool(8);
        List<Future<TmdbLookupResult<TmdbSearchPage<TmdbMovieSearchResult>>>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 8; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return tmdbClient.searchMovies("Matrix", "en-US", 1);
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(requestStarted.await(5, TimeUnit.SECONDS)).isTrue();
            releaseResponse.countDown();
            for (var future : futures) {
                assertThat(future.get(5, TimeUnit.SECONDS).toOptional().orElseThrow().results())
                        .extracting(TmdbMovieSearchResult::title).containsExactly("The Matrix");
            }
        } finally {
            releaseResponse.countDown();
            executor.shutdownNow();
        }
        mockServer.verify();
    }

    @Test
    @DisplayName("[searchMovies] Should Retry On Next Search - When Previous Search Was Unavailable")
    void shouldRetryOnNextSearchWhenPreviousSearchWasUnavailable() {
        mockServer.expect(requestTo(startsWith("https://api.themoviedb.org/3/search/movie?")))
                .andRespond(withServerError());
        mockServer.expect(requestTo(startsWith("https://api.themoviedb.org/3/search/movie?")))
                .andRespond(withServerError());
        mockServer.expect(requestTo(startsWith("https://api.themoviedb.org/3/search/movie?")))
                .andRespond(movieSearchSuccess(1, "The Matrix"));

        assertThat(tmdbClient.searchMovies("Matrix", "en-US", 1).isUnavailable()).isTrue();
        var recovered = tmdbClient.searchMovies("Matrix", "en-US", 1).toOptional().orElseThrow();

        assertThat(recovered.results()).extracting(TmdbMovieSearchResult::title).containsExactly("The Matrix");
        mockServer.verify();
    }

    private ResponseCreator movieSearchSuccess(int page, String title) {
        return withSuccess("""
                {"page":%d,"total_pages":2,"total_results":21,"results":[
                  {"id":603,"title":"%s","poster_path":"/matrix.jpg","release_date":"1999-03-31"}]}
                """.formatted(page, title), MediaType.APPLICATION_JSON);
    }

    @Test
    @DisplayName("[getMovieFullDetails] Should Make Only One Real TMDB Call - When Concurrent Requests Race For The Same Uncached Key")
    void shouldMakeOnlyOneRealTmdbCallWhenConcurrentRequestsRaceForTheSameUncachedKey() throws InterruptedException {
        ResponseCreator slowSuccess = request -> {
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return withSuccess("""
                    {"id": "603", "title": "The Matrix"}
                    """, MediaType.APPLICATION_JSON).createResponse(request);
        };
        mockServer.expect(requestTo(
                        "https://api.themoviedb.org/3/movie/603?append_to_response=credits,watch/providers,alternative_titles,videos&language=en-US"))
                .andRespond(slowSuccess);

        int concurrentCallers = 8;
        ExecutorService executor = Executors.newFixedThreadPool(concurrentCallers);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<TmdbLookupResult<TmdbMovieFullDetails>>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < concurrentCallers; i++) {
                futures.add(executor.submit(() -> {
                    startLatch.await();
                    return tmdbClient.getMovieFullDetails("603", "en-US");
                }));
            }
            startLatch.countDown();

            for (Future<TmdbLookupResult<TmdbMovieFullDetails>> future : futures) {
                assertThat(future.get(5, TimeUnit.SECONDS).toOptional()).isPresent();
            }
        } catch (ExecutionException | java.util.concurrent.TimeoutException e) {
            throw new AssertionError(e);
        } finally {
            executor.shutdownNow();
        }

        mockServer.verify();
    }

    @Test
    @DisplayName("[getMovieFullDetails] Should Not Throw And Should Cache - When TMDB Responds Successfully")
    void shouldNotThrowAndShouldCacheWhenMovieFullDetailsRespondsSuccessfully() {
        mockServer.expect(requestTo(
                        "https://api.themoviedb.org/3/movie/603?append_to_response=credits,watch/providers,alternative_titles,videos&language=en-US"))
                .andRespond(withSuccess("""
                        {"id": "603", "title": "The Matrix"}
                        """, MediaType.APPLICATION_JSON));

        Optional<TmdbMovieFullDetails> first = tmdbClient.getMovieFullDetails("603", "en-US").toOptional();
        Optional<TmdbMovieFullDetails> second = tmdbClient.getMovieFullDetails("603", "en-US").toOptional();

        assertThat(first).isPresent();
        assertThat(second).isPresent();
        mockServer.verify();
    }

    @Test
    @DisplayName("[getMovieFullDetails] Should Cache A Confirmed NotFound - When TMDB Responds With 404")
    void shouldCacheAConfirmedNotFoundWhenMovieFullDetailsRespondsWith404() {
        mockServer.expect(requestTo(
                        "https://api.themoviedb.org/3/movie/999999999?append_to_response=credits,watch/providers,alternative_titles,videos&language=en-US"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"status_message\": \"The resource you requested could not be found.\"}"));

        TmdbLookupResult<TmdbMovieFullDetails> first = tmdbClient.getMovieFullDetails("999999999", "en-US");
        TmdbLookupResult<TmdbMovieFullDetails> second = tmdbClient.getMovieFullDetails("999999999", "en-US");

        assertThat(first.isNotFound()).isTrue();
        assertThat(second.isNotFound()).isTrue();
        mockServer.verify();
    }

    @Test
    @DisplayName("[getMovieFullDetails] Should Not Cache - When TMDB Fails Twice In A Row")
    void shouldNotCacheWhenMovieFullDetailsFailsTwiceInARow() {
        mockServer.expect(requestTo(
                        "https://api.themoviedb.org/3/movie/603?append_to_response=credits,watch/providers,alternative_titles,videos&language=en-US"))
                .andRespond(withServerError());
        mockServer.expect(requestTo(
                        "https://api.themoviedb.org/3/movie/603?append_to_response=credits,watch/providers,alternative_titles,videos&language=en-US"))
                .andRespond(withServerError());
        mockServer.expect(requestTo(
                        "https://api.themoviedb.org/3/movie/603?append_to_response=credits,watch/providers,alternative_titles,videos&language=en-US"))
                .andRespond(withSuccess("""
                        {"id": "603", "title": "The Matrix"}
                        """, MediaType.APPLICATION_JSON));

        TmdbLookupResult<TmdbMovieFullDetails> firstAttempt = tmdbClient.getMovieFullDetails("603", "en-US");
        TmdbLookupResult<TmdbMovieFullDetails> secondAttempt = tmdbClient.getMovieFullDetails("603", "en-US");

        assertThat(firstAttempt.isUnavailable()).isTrue();
        assertThat(secondAttempt.toOptional()).isPresent();
        mockServer.verify();
    }

    @Test
    @DisplayName("[getTvFullDetails] Should Not Throw And Should Cache - When TMDB Responds Successfully")
    void shouldNotThrowAndShouldCacheWhenTvFullDetailsRespondsSuccessfully() {
        mockServer.expect(requestTo(
                        "https://api.themoviedb.org/3/tv/1396?append_to_response=aggregate_credits,watch/providers,alternative_titles,videos&language=en-US"))
                .andRespond(withSuccess("""
                        {"id": "1396", "name": "Breaking Bad"}
                        """, MediaType.APPLICATION_JSON));

        Optional<TmdbTvFullDetails> first = tmdbClient.getTvFullDetails("1396", "en-US").toOptional();
        Optional<TmdbTvFullDetails> second = tmdbClient.getTvFullDetails("1396", "en-US").toOptional();

        assertThat(first).isPresent();
        assertThat(second).isPresent();
        mockServer.verify();
    }

    @Test
    @DisplayName("[getTvFullDetails] Should Not Cache - When TMDB Fails Twice In A Row")
    void shouldNotCacheWhenTvFullDetailsFailsTwiceInARow() {
        mockServer.expect(requestTo(
                        "https://api.themoviedb.org/3/tv/1396?append_to_response=aggregate_credits,watch/providers,alternative_titles,videos&language=en-US"))
                .andRespond(withServerError());
        mockServer.expect(requestTo(
                        "https://api.themoviedb.org/3/tv/1396?append_to_response=aggregate_credits,watch/providers,alternative_titles,videos&language=en-US"))
                .andRespond(withServerError());
        mockServer.expect(requestTo(
                        "https://api.themoviedb.org/3/tv/1396?append_to_response=aggregate_credits,watch/providers,alternative_titles,videos&language=en-US"))
                .andRespond(withSuccess("""
                        {"id": "1396", "name": "Breaking Bad"}
                        """, MediaType.APPLICATION_JSON));

        TmdbLookupResult<TmdbTvFullDetails> firstAttempt = tmdbClient.getTvFullDetails("1396", "en-US");
        TmdbLookupResult<TmdbTvFullDetails> secondAttempt = tmdbClient.getTvFullDetails("1396", "en-US");

        assertThat(firstAttempt.isUnavailable()).isTrue();
        assertThat(secondAttempt.toOptional()).isPresent();
        mockServer.verify();
    }

    @Test
    @DisplayName("[getSeasonFullDetails] Should Not Throw And Should Cache - When TMDB Responds Successfully")
    void shouldNotThrowAndShouldCacheWhenSeasonFullDetailsRespondsSuccessfully() {
        mockServer.expect(requestTo(
                        "https://api.themoviedb.org/3/tv/1396/season/1?append_to_response=aggregate_credits,watch/providers&language=en-US"))
                .andRespond(withSuccess("""
                        {"id": 3572, "name": "Season 1", "season_number": 1, "episodes": []}
                        """, MediaType.APPLICATION_JSON));

        Optional<TmdbSeasonFullDetails> first = tmdbClient.getSeasonFullDetails("1396", 1, "en-US").toOptional();
        Optional<TmdbSeasonFullDetails> second = tmdbClient.getSeasonFullDetails("1396", 1, "en-US").toOptional();

        assertThat(first).isPresent();
        assertThat(second).isPresent();
        mockServer.verify();
    }

    @Test
    @DisplayName("[getSeasonFullDetails] Should Not Cache - When TMDB Fails Twice In A Row")
    void shouldNotCacheWhenSeasonFullDetailsFailsTwiceInARow() {
        mockServer.expect(requestTo(
                        "https://api.themoviedb.org/3/tv/1396/season/1?append_to_response=aggregate_credits,watch/providers&language=en-US"))
                .andRespond(withServerError());
        mockServer.expect(requestTo(
                        "https://api.themoviedb.org/3/tv/1396/season/1?append_to_response=aggregate_credits,watch/providers&language=en-US"))
                .andRespond(withServerError());
        mockServer.expect(requestTo(
                        "https://api.themoviedb.org/3/tv/1396/season/1?append_to_response=aggregate_credits,watch/providers&language=en-US"))
                .andRespond(withSuccess("""
                        {"id": 3572, "name": "Season 1", "season_number": 1, "episodes": []}
                        """, MediaType.APPLICATION_JSON));

        TmdbLookupResult<TmdbSeasonFullDetails> firstAttempt = tmdbClient.getSeasonFullDetails("1396", 1, "en-US");
        TmdbLookupResult<TmdbSeasonFullDetails> secondAttempt = tmdbClient.getSeasonFullDetails("1396", 1, "en-US");

        assertThat(firstAttempt.isUnavailable()).isTrue();
        assertThat(secondAttempt.toOptional()).isPresent();
        mockServer.verify();
    }

    @Test
    @DisplayName("[getEpisodeFullDetails] Should Not Throw And Should Cache - When TMDB Responds Successfully")
    void shouldNotThrowAndShouldCacheWhenEpisodeFullDetailsRespondsSuccessfully() {
        mockServer.expect(requestTo(
                        "https://api.themoviedb.org/3/tv/1396/season/1/episode/1?language=en-US"))
                .andRespond(withSuccess("""
                        {"id": 62085, "name": "Pilot", "episode_number": 1, "season_number": 1}
                        """, MediaType.APPLICATION_JSON));

        Optional<TmdbEpisodeFullDetails> first = tmdbClient.getEpisodeFullDetails("1396", 1, 1, "en-US").toOptional();
        Optional<TmdbEpisodeFullDetails> second = tmdbClient.getEpisodeFullDetails("1396", 1, 1, "en-US").toOptional();

        assertThat(first).isPresent();
        assertThat(second).isPresent();
        mockServer.verify();
    }

    @Test
    @DisplayName("[getEpisodeFullDetails] Should Not Cache - When TMDB Fails Twice In A Row")
    void shouldNotCacheWhenEpisodeFullDetailsFailsTwiceInARow() {
        mockServer.expect(requestTo(
                        "https://api.themoviedb.org/3/tv/1396/season/1/episode/1?language=en-US"))
                .andRespond(withServerError());
        mockServer.expect(requestTo(
                        "https://api.themoviedb.org/3/tv/1396/season/1/episode/1?language=en-US"))
                .andRespond(withServerError());
        mockServer.expect(requestTo(
                        "https://api.themoviedb.org/3/tv/1396/season/1/episode/1?language=en-US"))
                .andRespond(withSuccess("""
                        {"id": 62085, "name": "Pilot", "episode_number": 1, "season_number": 1}
                        """, MediaType.APPLICATION_JSON));

        TmdbLookupResult<TmdbEpisodeFullDetails> firstAttempt = tmdbClient.getEpisodeFullDetails("1396", 1, 1, "en-US");
        TmdbLookupResult<TmdbEpisodeFullDetails> secondAttempt = tmdbClient.getEpisodeFullDetails("1396", 1, 1, "en-US");

        assertThat(firstAttempt.isUnavailable()).isTrue();
        assertThat(secondAttempt.toOptional()).isPresent();
        mockServer.verify();
    }
}
