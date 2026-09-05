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
@TestPropertySource(properties = "app.tmdb.details-cache-ttl-hours=24")
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
                Cache<String, TmdbLookupResult<TmdbEpisodeFullDetails>> tmdbEpisodeFullDetailsCache) {
            return new TmdbClient(tmdbRestClient, tmdbMovieFullDetailsCache, tmdbTvFullDetailsCache,
                    tmdbSeasonFullDetailsCache, tmdbEpisodeFullDetailsCache);
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

    @BeforeEach
    void resetExpectationsAndCache() {
        mockServer.reset();
        tmdbMovieFullDetailsCache.invalidateAll();
        tmdbTvFullDetailsCache.invalidateAll();
        tmdbSeasonFullDetailsCache.invalidateAll();
        tmdbEpisodeFullDetailsCache.invalidateAll();
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
