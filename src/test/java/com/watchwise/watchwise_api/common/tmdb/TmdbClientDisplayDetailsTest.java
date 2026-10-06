package com.watchwise.watchwise_api.common.tmdb;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TmdbClientDisplayDetailsTest {

    private MockRestServiceServer mockServer;
    private TmdbClient tmdbClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.themoviedb.org/3");
        mockServer = MockRestServiceServer.bindTo(builder).build();
        tmdbClient = newTmdbClient(builder.build());
    }

    @Test
    void shouldFetchAndCacheOnlyLocalizedTvDisplayFields() {
        mockServer.expect(requestTo("https://api.themoviedb.org/3/tv/1396?language=pt-BR"))
                .andRespond(withSuccess("""
                        {"id":"1396","name":"Breaking Bad","first_air_date":"2008-01-20",
                         "poster_path":"/poster.jpg","backdrop_path":"/backdrop.jpg"}
                        """, MediaType.APPLICATION_JSON));

        var remote = tmdbClient.getTvDisplayDetails("1396", "pt-BR");
        var cached = tmdbClient.getTvDisplayDetails("1396", "pt-BR");

        assertThat(remote).isInstanceOfSatisfying(TmdbLookupResult.Found.class, found -> {
            assertThat(found.origin()).isEqualTo(TmdbLookupOrigin.REMOTE);
            assertThat(found.value()).isEqualTo(
                    new TmdbTvDisplayDetails("1396", "Breaking Bad", "2008-01-20", "/poster.jpg", "/backdrop.jpg"));
        });
        assertThat(cached).isInstanceOfSatisfying(TmdbLookupResult.Found.class,
                found -> assertThat(found.origin()).isEqualTo(TmdbLookupOrigin.CACHE));
        mockServer.verify();
    }

    @Test
    void shouldKeepTvDisplayCacheEntriesSeparateByLanguage() {
        mockServer.expect(requestTo("https://api.themoviedb.org/3/tv/1396?language=pt-BR"))
                .andRespond(withSuccess("""
                        {"id":"1396","name":"Breaking Bad","first_air_date":"2008-01-20"}
                        """, MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo("https://api.themoviedb.org/3/tv/1396?language=en-US"))
                .andRespond(withSuccess("""
                        {"id":"1396","name":"Breaking Bad (English)","first_air_date":"2008-01-20"}
                        """, MediaType.APPLICATION_JSON));

        var portuguese = tmdbClient.getTvDisplayDetails("1396", "pt-BR").toOptional().orElseThrow();
        var english = tmdbClient.getTvDisplayDetails("1396", "en-US").toOptional().orElseThrow();

        assertThat(portuguese.name()).isEqualTo("Breaking Bad");
        assertThat(english.name()).isEqualTo("Breaking Bad (English)");
        mockServer.verify();
    }

    @Test
    void shouldReturnNotFoundWhenTvDisplayEndpointRespondsWith404() {
        mockServer.expect(requestTo("https://api.themoviedb.org/3/tv/999999999?language=pt-BR"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"status_message\":\"The resource could not be found.\"}"));

        var result = tmdbClient.getTvDisplayDetails("999999999", "pt-BR");

        assertThat(result).isInstanceOf(TmdbLookupResult.NotFound.class);
        mockServer.verify();
    }

    @Test
    void shouldNotCacheUnavailableTvDisplayLookups() {
        for (int attempt = 0; attempt < 4; attempt++) {
            mockServer.expect(requestTo("https://api.themoviedb.org/3/tv/1396?language=pt-BR"))
                    .andRespond(withServerError());
        }

        var first = tmdbClient.getTvDisplayDetails("1396", "pt-BR");
        var second = tmdbClient.getTvDisplayDetails("1396", "pt-BR");

        assertThat(first).isInstanceOf(TmdbLookupResult.Unavailable.class);
        assertThat(second).isInstanceOf(TmdbLookupResult.Unavailable.class);
        mockServer.verify();
    }

    private static TmdbClient newTmdbClient(RestClient restClient) {
        return new TmdbClient(restClient,
                cache(), cache(), cache(), cache(), cache(), cache(), cache(), cache(),
                searchCache(), searchCache(), searchCache(), searchCache(),
                cache(), cache(), cache(), cache(), cache(), cache(), cache());
    }

    private static <T> Cache<String, T> cache() {
        return Caffeine.newBuilder().build();
    }

    private static <T> Cache<TmdbSearchCacheKey, T> searchCache() {
        return Caffeine.newBuilder().build();
    }
}
