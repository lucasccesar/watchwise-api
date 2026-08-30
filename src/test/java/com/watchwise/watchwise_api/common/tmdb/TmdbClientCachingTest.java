package com.watchwise.watchwise_api.common.tmdb;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Exercises the real {@code @Cacheable}-woven proxy — {@link TmdbClientTest} uses a bare
 * {@code new TmdbClient(...)} with no Spring context, so it never runs the caching AOP interceptor
 * and would not catch a broken {@code unless}/{@code key} SpEL expression (see the {@code unless =
 * "#result.isEmpty()"} bug caught manually via Postman: Spring unwraps an {@code Optional} return
 * value before evaluating {@code unless}, so {@code #result} is the unwrapped type, not the Optional).
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
        TmdbClient tmdbClient(RestClient tmdbRestClient) {
            return new TmdbClient(tmdbRestClient);
        }
    }

    @Autowired
    private TmdbClient tmdbClient;

    @Autowired
    private MockRestServiceServer mockServer;

    @Autowired
    private CacheManager cacheManager;

    @BeforeEach
    void resetExpectationsAndCache() {
        mockServer.reset();
        cacheManager.getCacheNames().forEach(name -> cacheManager.getCache(name).clear());
    }

    @Test
    @DisplayName("[getMovieFullDetails] Should Not Throw And Should Cache - When TMDB Responds Successfully")
    void shouldNotThrowAndShouldCacheWhenMovieFullDetailsRespondsSuccessfully() {
        mockServer.expect(requestTo(
                        "https://api.themoviedb.org/3/movie/603?append_to_response=credits,watch/providers,alternative_titles&language=en-US"))
                .andRespond(withSuccess("""
                        {"id": "603", "title": "The Matrix"}
                        """, MediaType.APPLICATION_JSON));

        Optional<TmdbMovieFullDetails> first = tmdbClient.getMovieFullDetails("603", "en-US");
        Optional<TmdbMovieFullDetails> second = tmdbClient.getMovieFullDetails("603", "en-US");

        assertThat(first).isPresent();
        assertThat(second).isPresent();
        mockServer.verify();
    }

    @Test
    @DisplayName("[getMovieFullDetails] Should Not Throw And Should Not Cache - When TMDB Fails Twice In A Row")
    void shouldNotThrowAndShouldNotCacheWhenMovieFullDetailsFailsTwiceInARow() {
        mockServer.expect(requestTo(
                        "https://api.themoviedb.org/3/movie/603?append_to_response=credits,watch/providers,alternative_titles&language=en-US"))
                .andRespond(withServerError());
        mockServer.expect(requestTo(
                        "https://api.themoviedb.org/3/movie/603?append_to_response=credits,watch/providers,alternative_titles&language=en-US"))
                .andRespond(withServerError());

        Optional<TmdbMovieFullDetails> result = tmdbClient.getMovieFullDetails("603", "en-US");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("[getTvFullDetails] Should Not Throw And Should Cache - When TMDB Responds Successfully")
    void shouldNotThrowAndShouldCacheWhenTvFullDetailsRespondsSuccessfully() {
        mockServer.expect(requestTo(
                        "https://api.themoviedb.org/3/tv/1396?append_to_response=aggregate_credits,watch/providers,alternative_titles&language=en-US"))
                .andRespond(withSuccess("""
                        {"id": "1396", "name": "Breaking Bad"}
                        """, MediaType.APPLICATION_JSON));

        Optional<TmdbTvFullDetails> first = tmdbClient.getTvFullDetails("1396", "en-US");
        Optional<TmdbTvFullDetails> second = tmdbClient.getTvFullDetails("1396", "en-US");

        assertThat(first).isPresent();
        assertThat(second).isPresent();
        mockServer.verify();
    }

    @Test
    @DisplayName("[getTvFullDetails] Should Not Throw And Should Not Cache - When TMDB Fails Twice In A Row")
    void shouldNotThrowAndShouldNotCacheWhenTvFullDetailsFailsTwiceInARow() {
        mockServer.expect(requestTo(
                        "https://api.themoviedb.org/3/tv/1396?append_to_response=aggregate_credits,watch/providers,alternative_titles&language=en-US"))
                .andRespond(withServerError());
        mockServer.expect(requestTo(
                        "https://api.themoviedb.org/3/tv/1396?append_to_response=aggregate_credits,watch/providers,alternative_titles&language=en-US"))
                .andRespond(withServerError());

        Optional<TmdbTvFullDetails> result = tmdbClient.getTvFullDetails("1396", "en-US");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("[getSeasonFullDetails] Should Not Throw And Should Cache - When TMDB Responds Successfully")
    void shouldNotThrowAndShouldCacheWhenSeasonFullDetailsRespondsSuccessfully() {
        mockServer.expect(requestTo(
                        "https://api.themoviedb.org/3/tv/1396/season/1?append_to_response=watch/providers&language=en-US"))
                .andRespond(withSuccess("""
                        {"id": 3572, "name": "Season 1", "season_number": 1, "episodes": []}
                        """, MediaType.APPLICATION_JSON));

        Optional<TmdbSeasonFullDetails> first = tmdbClient.getSeasonFullDetails("1396", 1, "en-US");
        Optional<TmdbSeasonFullDetails> second = tmdbClient.getSeasonFullDetails("1396", 1, "en-US");

        assertThat(first).isPresent();
        assertThat(second).isPresent();
        mockServer.verify();
    }

    @Test
    @DisplayName("[getSeasonFullDetails] Should Not Throw And Should Not Cache - When TMDB Fails Twice In A Row")
    void shouldNotThrowAndShouldNotCacheWhenSeasonFullDetailsFailsTwiceInARow() {
        mockServer.expect(requestTo(
                        "https://api.themoviedb.org/3/tv/1396/season/1?append_to_response=watch/providers&language=en-US"))
                .andRespond(withServerError());
        mockServer.expect(requestTo(
                        "https://api.themoviedb.org/3/tv/1396/season/1?append_to_response=watch/providers&language=en-US"))
                .andRespond(withServerError());

        Optional<TmdbSeasonFullDetails> result = tmdbClient.getSeasonFullDetails("1396", 1, "en-US");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("[getEpisodeFullDetails] Should Not Throw And Should Cache - When TMDB Responds Successfully")
    void shouldNotThrowAndShouldCacheWhenEpisodeFullDetailsRespondsSuccessfully() {
        mockServer.expect(requestTo(
                        "https://api.themoviedb.org/3/tv/1396/season/1/episode/1?language=en-US"))
                .andRespond(withSuccess("""
                        {"id": 62085, "name": "Pilot", "episode_number": 1, "season_number": 1}
                        """, MediaType.APPLICATION_JSON));

        Optional<TmdbEpisodeFullDetails> first = tmdbClient.getEpisodeFullDetails("1396", 1, 1, "en-US");
        Optional<TmdbEpisodeFullDetails> second = tmdbClient.getEpisodeFullDetails("1396", 1, 1, "en-US");

        assertThat(first).isPresent();
        assertThat(second).isPresent();
        mockServer.verify();
    }

    @Test
    @DisplayName("[getEpisodeFullDetails] Should Not Throw And Should Not Cache - When TMDB Fails Twice In A Row")
    void shouldNotThrowAndShouldNotCacheWhenEpisodeFullDetailsFailsTwiceInARow() {
        mockServer.expect(requestTo(
                        "https://api.themoviedb.org/3/tv/1396/season/1/episode/1?language=en-US"))
                .andRespond(withServerError());
        mockServer.expect(requestTo(
                        "https://api.themoviedb.org/3/tv/1396/season/1/episode/1?language=en-US"))
                .andRespond(withServerError());

        Optional<TmdbEpisodeFullDetails> result = tmdbClient.getEpisodeFullDetails("1396", 1, 1, "en-US");

        assertThat(result).isEmpty();
    }
}
