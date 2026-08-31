package com.watchwise.watchwise_api.common.tmdb;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TmdbClientTest {

    private MockRestServiceServer mockServer;
    private TmdbClient tmdbClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.themoviedb.org/3");
        mockServer = MockRestServiceServer.bindTo(builder).build();
        tmdbClient = new TmdbClient(builder.build());
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
                        "https://api.themoviedb.org/3/movie/603?append_to_response=credits,watch/providers,alternative_titles&language=en-US"))
                .andRespond(withSuccess("""
                        {"id": "603", "title": "The Matrix", "release_date": "1999-03-31", "runtime": 136}
                        """, MediaType.APPLICATION_JSON));

        Optional<TmdbMovieFullDetails> result = tmdbClient.getMovieFullDetails("603", "en-US");

        assertThat(result).isPresent();
        assertThat(result.get().title()).isEqualTo("The Matrix");
        assertThat(result.get().runtime()).isEqualTo(136);
    }

    @Test
    @DisplayName("[getMovieFullDetails] Should Return Empty - When TMDB Fails Twice In A Row")
    void shouldReturnEmptyWhenMovieFullDetailsFailsTwiceInARow() {
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
    @DisplayName("[getTvFullDetails] Should Request Aggregate Credits Watch Providers And Alternative Titles Appended - When Called")
    void shouldRequestAggregateCreditsWatchProvidersAndAlternativeTitlesAppendedWhenCalled() {
        mockServer.expect(requestTo(
                        "https://api.themoviedb.org/3/tv/1396?append_to_response=aggregate_credits,watch/providers,alternative_titles&language=pt-BR"))
                .andRespond(withSuccess("""
                        {"id": "1396", "name": "Breaking Bad", "first_air_date": "2008-01-20"}
                        """, MediaType.APPLICATION_JSON));

        Optional<TmdbTvFullDetails> result = tmdbClient.getTvFullDetails("1396", "pt-BR");

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

        Optional<TmdbSeasonFullDetails> result = tmdbClient.getSeasonFullDetails("1396", 1, "en-US");

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

        Optional<TmdbEpisodeFullDetails> result = tmdbClient.getEpisodeFullDetails("1396", 1, 1, "en-US");

        assertThat(result).isPresent();
        assertThat(result.get().name()).isEqualTo("Pilot");
        assertThat(result.get().guestStars()).extracting(TmdbGuestStar::name).containsExactly("John Doe");
    }
}
