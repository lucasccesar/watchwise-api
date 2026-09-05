package com.watchwise.watchwise_api.common.tmdb;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TmdbClientTest {

    private MockRestServiceServer mockServer;
    private TmdbClient tmdbClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.themoviedb.org/3");
        mockServer = MockRestServiceServer.bindTo(builder).build();
        tmdbClient = new TmdbClient(builder.build(), Caffeine.newBuilder().build(), Caffeine.newBuilder().build(),
                Caffeine.newBuilder().build(), Caffeine.newBuilder().build());
    }

    @Test
    @DisplayName("[getMovieDetails] Should Return Parsed Details - When TMDB Responds With A Released Movie")
    void shouldReturnParsedDetailsWhenTmdbRespondsWithAReleasedMovie() {
        mockServer.expect(requestTo("https://api.themoviedb.org/3/movie/603"))
                .andRespond(withSuccess("""
                        {"id": 603, "release_date": "1999-03-31", "status": "Released"}
                        """, MediaType.APPLICATION_JSON));

        Optional<TmdbMovieDetails> result = tmdbClient.getMovieDetails("603");

        assertThat(result).isPresent();
        assertThat(result.get().releaseDate()).isEqualTo("1999-03-31");
        assertThat(result.get().status()).isEqualTo("Released");
    }

    @Test
    @DisplayName("[getMovieDetails] Should Return Empty - When TMDB Fails Twice In A Row")
    void shouldReturnEmptyWhenTmdbFailsTwiceInARow() {
        mockServer.expect(requestTo("https://api.themoviedb.org/3/movie/603")).andRespond(withServerError());
        mockServer.expect(requestTo("https://api.themoviedb.org/3/movie/603")).andRespond(withServerError());

        Optional<TmdbMovieDetails> result = tmdbClient.getMovieDetails("603");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("[getMovieDetails] Should Retry Once And Succeed - When First Call Fails")
    void shouldRetryOnceAndSucceedWhenFirstCallFails() {
        mockServer.expect(requestTo("https://api.themoviedb.org/3/movie/603")).andRespond(withServerError());
        mockServer.expect(requestTo("https://api.themoviedb.org/3/movie/603"))
                .andRespond(withSuccess("""
                        {"id": 603, "release_date": "1999-03-31", "status": "Released"}
                        """, MediaType.APPLICATION_JSON));

        Optional<TmdbMovieDetails> result = tmdbClient.getMovieDetails("603");

        assertThat(result).isPresent();
    }

    @Test
    @DisplayName("[getTvDetails] Should Parse NextEpisodeToAir - When Series Has One Scheduled")
    void shouldParseNextEpisodeToAirWhenSeriesHasOneScheduled() {
        mockServer.expect(requestTo("https://api.themoviedb.org/3/tv/1396"))
                .andRespond(withSuccess("""
                        {"id": 1396, "status": "Returning Series",
                         "next_episode_to_air": {"air_date": "2026-09-01", "season_number": 6, "episode_number": 1}}
                        """, MediaType.APPLICATION_JSON));

        Optional<TmdbTvDetails> result = tmdbClient.getTvDetails("1396");

        assertThat(result).isPresent();
        assertThat(result.get().nextEpisodeToAir().airDate()).isEqualTo("2026-09-01");
        assertThat(result.get().nextEpisodeToAir().seasonNumber()).isEqualTo(6);
    }

    @Test
    @DisplayName("[getPersonCombinedCredits] Should Parse Cast And Crew Credit Ids - When TMDB Responds")
    void shouldParseCastAndCrewCreditIdsWhenTmdbResponds() {
        mockServer.expect(requestTo("https://api.themoviedb.org/3/person/6193/combined_credits"))
                .andRespond(withSuccess("""
                        {"cast": [{"id": 603, "media_type": "movie"}], "crew": [{"id": 1396, "media_type": "tv"}]}
                        """, MediaType.APPLICATION_JSON));

        Optional<TmdbPersonCredits> result = tmdbClient.getPersonCombinedCredits("6193");

        assertThat(result).isPresent();
        assertThat(result.get().cast()).extracting(TmdbCredit::id).containsExactly("603");
        assertThat(result.get().crew()).extracting(TmdbCredit::id).containsExactly("1396");
    }

    @Test
    @DisplayName("[getMovieFullDetails] Should Request Credits Watch Providers And Alternative Titles Appended - When Called")
    void shouldRequestCreditsWatchProvidersAndAlternativeTitlesAppendedWhenCalled() {
        mockServer.expect(requestTo(
                        "https://api.themoviedb.org/3/movie/603?append_to_response=credits,watch/providers,alternative_titles,videos&language=en-US"))
                .andRespond(withSuccess("""
                        {"id": "603", "title": "The Matrix", "release_date": "1999-03-31", "runtime": 136}
                        """, MediaType.APPLICATION_JSON));

        Optional<TmdbMovieFullDetails> result = tmdbClient.getMovieFullDetails("603", "en-US").toOptional();

        assertThat(result).isPresent();
        assertThat(result.get().title()).isEqualTo("The Matrix");
        assertThat(result.get().runtime()).isEqualTo(136);
    }

    @Test
    @DisplayName("[getMovieFullDetails] Should Return Unavailable - When TMDB Fails Twice In A Row")
    void shouldReturnUnavailableWhenMovieFullDetailsFailsTwiceInARow() {
        mockServer.expect(requestTo(
                        "https://api.themoviedb.org/3/movie/603?append_to_response=credits,watch/providers,alternative_titles,videos&language=en-US"))
                .andRespond(withServerError());
        mockServer.expect(requestTo(
                        "https://api.themoviedb.org/3/movie/603?append_to_response=credits,watch/providers,alternative_titles,videos&language=en-US"))
                .andRespond(withServerError());

        TmdbLookupResult<TmdbMovieFullDetails> result = tmdbClient.getMovieFullDetails("603", "en-US");

        assertThat(result.isUnavailable()).isTrue();
        assertThat(result.toOptional()).isEmpty();
    }

    @Test
    @DisplayName("[getMovieFullDetails] Should Return NotFound Without Retrying - When TMDB Responds With 404")
    void shouldReturnNotFoundWithoutRetryingWhenMovieFullDetailsRespondsWith404() {
        mockServer.expect(requestTo(
                        "https://api.themoviedb.org/3/movie/999999999?append_to_response=credits,watch/providers,alternative_titles,videos&language=en-US"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"status_message\": \"The resource you requested could not be found.\"}"));

        TmdbLookupResult<TmdbMovieFullDetails> result = tmdbClient.getMovieFullDetails("999999999", "en-US");

        assertThat(result.isNotFound()).isTrue();
        assertThat(result.toOptional()).isEmpty();
        mockServer.verify();
    }

    @Test
    @DisplayName("[getTvFullDetails] Should Request Aggregate Credits Watch Providers And Alternative Titles Appended - When Called")
    void shouldRequestAggregateCreditsWatchProvidersAndAlternativeTitlesAppendedWhenCalled() {
        mockServer.expect(requestTo(
                        "https://api.themoviedb.org/3/tv/1396?append_to_response=aggregate_credits,watch/providers,alternative_titles,videos&language=pt-BR"))
                .andRespond(withSuccess("""
                        {"id": "1396", "name": "Breaking Bad", "first_air_date": "2008-01-20"}
                        """, MediaType.APPLICATION_JSON));

        Optional<TmdbTvFullDetails> result = tmdbClient.getTvFullDetails("1396", "pt-BR").toOptional();

        assertThat(result).isPresent();
        assertThat(result.get().name()).isEqualTo("Breaking Bad");
    }

    @Test
    @DisplayName("[getSeasonFullDetails] Should Request Aggregate Credits And Watch Providers Appended - When Called")
    void shouldRequestAggregateCreditsAndWatchProvidersAppendedWhenSeasonFullDetailsCalled() {
        mockServer.expect(requestTo(
                        "https://api.themoviedb.org/3/tv/1396/season/1?append_to_response=aggregate_credits,watch/providers&language=en-US"))
                .andRespond(withSuccess("""
                        {"id": 3572, "name": "Season 1", "season_number": 1, "episodes": []}
                        """, MediaType.APPLICATION_JSON));

        Optional<TmdbSeasonFullDetails> result = tmdbClient.getSeasonFullDetails("1396", 1, "en-US").toOptional();

        assertThat(result).isPresent();
        assertThat(result.get().name()).isEqualTo("Season 1");
        assertThat(result.get().episodes()).isEmpty();
    }

    @Test
    @DisplayName("[getEpisodeFullDetails] Should Parse Guest Stars - When Called Without Append")
    void shouldParseGuestStarsWhenEpisodeFullDetailsCalledWithoutAppend() {
        mockServer.expect(requestTo(
                        "https://api.themoviedb.org/3/tv/1396/season/1/episode/1?language=en-US"))
                .andRespond(withSuccess("""
                        {"id": 62085, "name": "Pilot", "episode_number": 1, "season_number": 1,
                         "guest_stars": [{"id": 17419, "name": "John Doe", "character": "Neighbor"}]}
                        """, MediaType.APPLICATION_JSON));

        Optional<TmdbEpisodeFullDetails> result = tmdbClient.getEpisodeFullDetails("1396", 1, 1, "en-US").toOptional();

        assertThat(result).isPresent();
        assertThat(result.get().name()).isEqualTo("Pilot");
        assertThat(result.get().guestStars()).extracting(TmdbGuestStar::name).containsExactly("John Doe");
    }

    @Test
    @DisplayName("[getMovieFullDetails] Should Request Videos Appended - When Called")
    void shouldRequestVideosAppendedWhenMovieFullDetailsCalled() {
        mockServer.expect(requestTo(
                        "https://api.themoviedb.org/3/movie/603?append_to_response=credits,watch/providers,alternative_titles,videos&language=en-US"))
                .andRespond(withSuccess("""
                        {"id": "603", "title": "The Matrix"}
                        """, MediaType.APPLICATION_JSON));

        Optional<TmdbMovieFullDetails> result = tmdbClient.getMovieFullDetails("603", "en-US").toOptional();

        assertThat(result).isPresent();
    }

    @Test
    @DisplayName("[getMovieFullDetails] Should Parse Budget Revenue Production Companies Crew And Videos - When TMDB Responds")
    void shouldParseBudgetRevenueProductionCompaniesCrewAndVideosWhenMovieFullDetailsResponds() {
        mockServer.expect(requestTo(
                        "https://api.themoviedb.org/3/movie/603?append_to_response=credits,watch/providers,alternative_titles,videos&language=en-US"))
                .andRespond(withSuccess("""
                        {"id": "603", "title": "The Matrix", "budget": 63000000, "revenue": 463500000,
                         "production_companies": [{"id": 79, "name": "Village Roadshow Pictures", "logo_path": "/village.png", "origin_country": "US"}],
                         "credits": {"cast": [], "crew": [
                            {"id": 10, "name": "Lana Wachowski", "job": "Director", "profile_path": "/lana.jpg"},
                            {"id": 11, "name": "Best Boy Grip", "job": "Best Boy Grip", "profile_path": null}]},
                         "videos": {"results": [
                            {"key": "vKQi3bBA1y8", "name": "Trailer", "site": "YouTube", "type": "Trailer",
                             "official": true, "iso_639_1": "en", "published_at": "1999-03-01T00:00:00.000Z"}]}}
                        """, MediaType.APPLICATION_JSON));

        Optional<TmdbMovieFullDetails> result = tmdbClient.getMovieFullDetails("603", "en-US").toOptional();

        assertThat(result).isPresent();
        assertThat(result.get().budget()).isEqualTo(63000000L);
        assertThat(result.get().revenue()).isEqualTo(463500000L);
        assertThat(result.get().productionCompanies()).extracting("id", "name", "logoPath", "originCountry")
                .containsExactly(org.assertj.core.groups.Tuple.tuple(79, "Village Roadshow Pictures", "/village.png", "US"));
        assertThat(result.get().credits().crew()).extracting("id", "name", "job")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(10, "Lana Wachowski", "Director"),
                        org.assertj.core.groups.Tuple.tuple(11, "Best Boy Grip", "Best Boy Grip"));
        assertThat(result.get().videos().results()).extracting("key", "name", "site", "type", "official", "isoCode639_1")
                .containsExactly(org.assertj.core.groups.Tuple.tuple("vKQi3bBA1y8", "Trailer", "YouTube", "Trailer", true, "en"));
        assertThat(result.get().videos().results().get(0).publishedAt()).isEqualTo("1999-03-01T00:00:00.000Z");
    }

    @Test
    @DisplayName("[getTvFullDetails] Should Request Videos Appended - When Called")
    void shouldRequestVideosAppendedWhenTvFullDetailsCalled() {
        mockServer.expect(requestTo(
                        "https://api.themoviedb.org/3/tv/1396?append_to_response=aggregate_credits,watch/providers,alternative_titles,videos&language=en-US"))
                .andRespond(withSuccess("""
                        {"id": "1396", "name": "Breaking Bad"}
                        """, MediaType.APPLICATION_JSON));

        Optional<TmdbTvFullDetails> result = tmdbClient.getTvFullDetails("1396", "en-US").toOptional();

        assertThat(result).isPresent();
    }

    @Test
    @DisplayName("[getTvFullDetails] Should Parse Production Companies And Aggregate Crew Jobs - When TMDB Responds")
    void shouldParseProductionCompaniesAndAggregateCrewJobsWhenTvFullDetailsResponds() {
        mockServer.expect(requestTo(
                        "https://api.themoviedb.org/3/tv/1396?append_to_response=aggregate_credits,watch/providers,alternative_titles,videos&language=en-US"))
                .andRespond(withSuccess("""
                        {"id": "1396", "name": "Breaking Bad",
                         "production_companies": [{"id": 11073, "name": "Sony Pictures Television", "logo_path": "/sony.png", "origin_country": "US"}],
                         "aggregate_credits": {"cast": [], "crew": [
                            {"id": 66633, "name": "Vince Gilligan", "profile_path": "/vince.jpg",
                             "jobs": [{"job": "Director"}, {"job": "Executive Producer"}]},
                            {"id": 99999, "name": "Random Grip", "profile_path": null,
                             "jobs": [{"job": "Grip"}]}]}}
                        """, MediaType.APPLICATION_JSON));

        Optional<TmdbTvFullDetails> result = tmdbClient.getTvFullDetails("1396", "en-US").toOptional();

        assertThat(result).isPresent();
        assertThat(result.get().productionCompanies()).extracting("id", "name")
                .containsExactly(org.assertj.core.groups.Tuple.tuple(11073, "Sony Pictures Television"));
        assertThat(result.get().aggregateCredits().crew()).extracting("id", "name")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(66633, "Vince Gilligan"),
                        org.assertj.core.groups.Tuple.tuple(99999, "Random Grip"));
        assertThat(result.get().aggregateCredits().crew().get(0).jobs()).extracting("job")
                .containsExactly("Director", "Executive Producer");
    }
}
