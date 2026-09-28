package com.watchwise.watchwise_api.dailygame.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.TmdbUnavailableException;
import com.watchwise.watchwise_api.common.pagination.PageRequestFactory;
import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbEpisodeSummary;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieSearchResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbPersonSearchResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonSummary;
import com.watchwise.watchwise_api.common.tmdb.TmdbSearchPage;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvSearchResult;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameSearchResultDTO;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameTargetKind;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DailyGameSearchServiceImplTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-27T12:00:00Z"), ZoneOffset.UTC);
    private static final String LANGUAGE = TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE;

    @Mock
    private TmdbClient tmdbClient;

    @Mock
    private RequestThrottler requestThrottler;

    @ParameterizedTest
    @EnumSource(value = DailyGameType.class, names = {"MOVIE_BY_POSTER", "MOVIE_BY_INFO"})
    @DisplayName("[search] Should Return Movie Candidates - When The Game Type Targets Movies")
    void shouldReturnMovieCandidatesWhenTheGameTypeTargetsMovies(DailyGameType gameType) {
        when(tmdbClient.searchMovies("fight", LANGUAGE, 2)).thenReturn(new TmdbLookupResult.Found<>(
                new TmdbSearchPage<>(2, List.of(new TmdbMovieSearchResult("550", "Fight Club", "/poster.jpg", "1999-10-15")), 3, 5)));

        Page<DailyGameSearchResultDTO> result = service().search(USER_ID, gameType, "  fight  ", 2, 1);

        assertThat(result.getContent()).containsExactly(new DailyGameSearchResultDTO(
                DailyGameTargetKind.MOVIE, "550", null, null, null, null,
                "Fight Club", "https://image.tmdb.org/t/p/w500/poster.jpg", LocalDate.of(1999, 10, 15)));
        assertThat(result.getNumber()).isEqualTo(1);
        assertThat(result.getSize()).isEqualTo(1);
        assertThat(result.getTotalElements()).isEqualTo(5);
        verify(tmdbClient).searchMovies("fight", LANGUAGE, 2);
        verify(tmdbClient, never()).searchTv(anyString(), anyString(), anyInt());
        verify(tmdbClient, never()).searchPeople(anyString(), anyString(), anyInt());
    }

    @ParameterizedTest
    @EnumSource(value = DailyGameType.class, names = {"SERIES_BY_POSTER", "SERIES_BY_INFO"})
    @DisplayName("[search] Should Return Series Candidates - When The Game Type Targets Series")
    void shouldReturnSeriesCandidatesWhenTheGameTypeTargetsSeries(DailyGameType gameType) {
        when(tmdbClient.searchTv("breaking", LANGUAGE, 1)).thenReturn(new TmdbLookupResult.Found<>(
                new TmdbSearchPage<>(1, List.of(new TmdbTvSearchResult("1399", "Breaking Bad", "/poster.jpg", "2008-01-20")), 1, 1)));

        Page<DailyGameSearchResultDTO> result = service().search(USER_ID, gameType, "breaking", 1, 20);

        assertThat(result.getContent().getFirst()).isEqualTo(new DailyGameSearchResultDTO(
                DailyGameTargetKind.SERIES, "1399", null, null, null, null,
                "Breaking Bad", "https://image.tmdb.org/t/p/w500/poster.jpg", LocalDate.of(2008, 1, 20)));
        verify(tmdbClient).searchTv("breaking", LANGUAGE, 1);
        verify(tmdbClient, never()).searchMovies(anyString(), anyString(), anyInt());
        verify(tmdbClient, never()).searchPeople(anyString(), anyString(), anyInt());
    }

    @ParameterizedTest
    @EnumSource(value = DailyGameType.class, names = {"PERSON_BY_FACE", "ACTOR_BY_MOVIE_FILMOGRAPHY", "ACTOR_BY_SERIES_FILMOGRAPHY"})
    @DisplayName("[search] Should Return Person Candidates - When The Game Type Targets People")
    void shouldReturnPersonCandidatesWhenTheGameTypeTargetsPeople(DailyGameType gameType) {
        when(tmdbClient.searchPeople("fincher", LANGUAGE, 1)).thenReturn(new TmdbLookupResult.Found<>(
                new TmdbSearchPage<>(1, List.of(new TmdbPersonSearchResult("7467", "David Fincher", "/profile.jpg")), 1, 1)));

        Page<DailyGameSearchResultDTO> result = service().search(USER_ID, gameType, "fincher", 1, 20);

        assertThat(result.getContent().getFirst()).isEqualTo(new DailyGameSearchResultDTO(
                DailyGameTargetKind.PERSON, null, "7467", null, null, null,
                "David Fincher", "https://image.tmdb.org/t/p/w185/profile.jpg", null));
        verify(tmdbClient).searchPeople("fincher", LANGUAGE, 1);
        verify(tmdbClient, never()).searchMovies(anyString(), anyString(), anyInt());
        verify(tmdbClient, never()).searchTv(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("[search] Should Reject Generic Episode Search - When The Game Type Is Episode By Frame")
    void shouldRejectGenericEpisodeSearchWhenTheGameTypeIsEpisodeByFrame() {
        assertThatThrownBy(() -> service().search(USER_ID, DailyGameType.EPISODE_BY_FRAME, "pilot", 1, 20))
                .isInstanceOf(BadRequestException.class);

        verifyNoInteractions(tmdbClient, requestThrottler);
    }

    @Test
    @DisplayName("[search] Should Return Empty Page - When TMDB Confirms No Results")
    void shouldReturnEmptyPageWhenTmdbConfirmsNoResults() {
        when(tmdbClient.searchMovies("unknown", LANGUAGE, 1)).thenReturn(new TmdbLookupResult.NotFound<>());

        Page<DailyGameSearchResultDTO> result = service().search(
                USER_ID, DailyGameType.MOVIE_BY_INFO, "unknown", 1, 20);

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isZero();
        assertThat(result.getTotalPages()).isZero();
    }

    @Test
    @DisplayName("[search] Should Reject Blank Query - When The Query Has Only Whitespace")
    void shouldRejectBlankQueryWhenTheQueryHasOnlyWhitespace() {
        assertThatThrownBy(() -> service().search(USER_ID, DailyGameType.MOVIE_BY_INFO, "  ", 1, 20))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("q must be provided");

        verifyNoInteractions(tmdbClient, requestThrottler);
    }

    @Test
    @DisplayName("[search] Should Reject Oversized Query - When The Query Exceeds The Limit")
    void shouldRejectOversizedQueryWhenTheQueryExceedsTheLimit() {
        String query = "x".repeat(101);

        assertThatThrownBy(() -> service().search(USER_ID, DailyGameType.MOVIE_BY_INFO, query, 1, 20))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("q must be at most 100 characters");

        verifyNoInteractions(tmdbClient, requestThrottler);
    }

    @Test
    @DisplayName("[search] Should Clamp Page Size - When The Requested Size Exceeds The Safe Maximum")
    void shouldClampPageSizeWhenTheRequestedSizeExceedsTheSafeMaximum() {
        when(tmdbClient.searchMovies("fight", LANGUAGE, 1)).thenReturn(new TmdbLookupResult.Found<>(
                new TmdbSearchPage<>(1, List.of(new TmdbMovieSearchResult("550", "Fight Club", "/poster.jpg", "1999-10-15")), 1, 1)));

        Page<DailyGameSearchResultDTO> result = service().search(
                USER_ID, DailyGameType.MOVIE_BY_INFO, "fight", 1, 101);

        assertThat(result.getSize()).isEqualTo(100);
    }

    @Test
    @DisplayName("[searchEpisodeSeries] Should Search Only Series - When Finding Episode Sources")
    void shouldSearchOnlySeriesWhenFindingEpisodeSources() {
        when(tmdbClient.searchTv("office", LANGUAGE, 1)).thenReturn(new TmdbLookupResult.Found<>(
                new TmdbSearchPage<>(1, List.of(new TmdbTvSearchResult("2316", "The Office", "/poster.jpg", "2005-03-24")), 1, 1)));

        Page<DailyGameSearchResultDTO> result = service().searchEpisodeSeries(USER_ID, " office ", 1, 20);

        assertThat(result.getContent().getFirst().targetKind()).isEqualTo(DailyGameTargetKind.SERIES);
        assertThat(result.getContent().getFirst().tmdbId()).isEqualTo("2316");
        verify(tmdbClient).searchTv("office", LANGUAGE, 1);
        verify(tmdbClient, never()).searchMovies(anyString(), anyString(), anyInt());
        verify(tmdbClient, never()).searchPeople(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("[searchEpisodes] Should Return Released Matching Episodes With Composite Identity - When The Series Has Regular And Future Episodes")
    void shouldReturnReleasedMatchingEpisodesWithCompositeIdentityWhenTheSeriesHasRegularAndFutureEpisodes() {
        when(tmdbClient.getTvFullDetails("1399", LANGUAGE)).thenReturn(new TmdbLookupResult.Found<>(seriesWithSeasons(
                new TmdbSeasonSummary(0, "Specials", null, "2008-01-01", 1, "/special.jpg"),
                new TmdbSeasonSummary(1, "Season 1", null, "2008-01-20", 4, "/season-1.jpg"),
                new TmdbSeasonSummary(2, "Season 2", null, "2027-01-01", 1, "/season-2.jpg"))));
        when(tmdbClient.getSeasonFullDetails("1399", 1, LANGUAGE)).thenReturn(new TmdbLookupResult.Found<>(season(1,
                new TmdbEpisodeSummary(1, "Pilot", null, "2020-01-01", 45, "/pilot.jpg", null),
                new TmdbEpisodeSummary(2, "Future Fight", null, "2026-09-28", 45, "/future.jpg", null),
                new TmdbEpisodeSummary(3, "No Still Fight", null, "2020-02-01", 45, null, null),
                new TmdbEpisodeSummary(4, "Finale Fight", null, "2020-03-01", 45, "/finale.jpg", null))));
        when(tmdbClient.getSeasonFullDetails("1399", 2, LANGUAGE)).thenReturn(new TmdbLookupResult.Found<>(season(2,
                new TmdbEpisodeSummary(1, "Other Fight", null, "2020-04-01", 45, "/other.jpg", null))));

        Page<DailyGameSearchResultDTO> result = service().searchEpisodes(USER_ID, "1399", "  fIgHt ", 1, 1);

        assertThat(result.getContent()).containsExactly(new DailyGameSearchResultDTO(
                DailyGameTargetKind.EPISODE, null, null, "1399", 1, 4,
                "Finale Fight", "https://image.tmdb.org/t/p/w300/finale.jpg", LocalDate.of(2020, 3, 1)));
        assertThat(result.getTotalElements()).isEqualTo(2);
        assertThat(result.getTotalPages()).isEqualTo(2);
        verify(tmdbClient).getTvFullDetails("1399", LANGUAGE);
        verify(tmdbClient).getSeasonFullDetails("1399", 1, LANGUAGE);
        verify(tmdbClient).getSeasonFullDetails("1399", 2, LANGUAGE);
        verify(tmdbClient, never()).getSeasonFullDetails("1399", 0, LANGUAGE);
    }

    @Test
    @DisplayName("[searchEpisodes] Should Reject Invalid Series Identifier - When The Identifier Is Not Positive Numeric")
    void shouldRejectInvalidSeriesIdentifierWhenTheIdentifierIsNotPositiveNumeric() {
        assertThatThrownBy(() -> service().searchEpisodes(USER_ID, "abc", "pilot", 1, 20))
                .isInstanceOf(BadRequestException.class);

        verifyNoInteractions(tmdbClient, requestThrottler);
    }

    @Test
    @DisplayName("[searchEpisodes] Should Return Empty Page - When The Series Or Season Is Not Found")
    void shouldReturnEmptyPageWhenTheSeriesOrSeasonIsNotFound() {
        when(tmdbClient.getTvFullDetails("1399", LANGUAGE)).thenReturn(new TmdbLookupResult.NotFound<>());

        Page<DailyGameSearchResultDTO> result = service().searchEpisodes(USER_ID, "1399", "pilot", 1, 20);

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isZero();
    }

    @Test
    @DisplayName("[search] Should Propagate TMDB Unavailable - When The External Search Is Unavailable")
    void shouldPropagateTmdbUnavailableWhenTheExternalSearchIsUnavailable() {
        when(tmdbClient.searchMovies("fight", LANGUAGE, 1)).thenReturn(new TmdbLookupResult.Unavailable<>());

        assertThatThrownBy(() -> service().search(USER_ID, DailyGameType.MOVIE_BY_INFO, "fight", 1, 20))
                .isInstanceOf(TmdbUnavailableException.class)
                .hasMessage("TMDB is currently unavailable");
    }

    @Test
    @DisplayName("[searchEpisodes] Should Propagate TMDB Unavailable - When A Season Lookup Is Unavailable")
    void shouldPropagateTmdbUnavailableWhenASeasonLookupIsUnavailable() {
        when(tmdbClient.getTvFullDetails("1399", LANGUAGE)).thenReturn(new TmdbLookupResult.Found<>(seriesWithSeasons(
                new TmdbSeasonSummary(1, "Season 1", null, "2008-01-20", 1, "/season.jpg"))));
        when(tmdbClient.getSeasonFullDetails("1399", 1, LANGUAGE)).thenReturn(new TmdbLookupResult.Unavailable<>());

        assertThatThrownBy(() -> service().searchEpisodes(USER_ID, "1399", "pilot", 1, 20))
                .isInstanceOf(TmdbUnavailableException.class)
                .hasMessage("TMDB is currently unavailable");
    }

    @Test
    @DisplayName("[searchEpisodes] Should Throttle Before Loading TMDB Data - When The Query Is Valid")
    void shouldThrottleBeforeLoadingTmdbDataWhenTheQueryIsValid() {
        when(tmdbClient.getTvFullDetails("1399", LANGUAGE)).thenReturn(new TmdbLookupResult.NotFound<>());

        service().searchEpisodes(USER_ID, "1399", "pilot", 1, 20);

        InOrder order = inOrder(requestThrottler, tmdbClient);
        order.verify(requestThrottler).checkAllowed("daily-game-search|" + USER_ID, 30, Duration.ofMinutes(5));
        order.verify(tmdbClient).getTvFullDetails("1399", LANGUAGE);
    }

    private DailyGameSearchServiceImpl service() {
        return new DailyGameSearchServiceImpl(tmdbClient, new PageRequestFactory(), requestThrottler, CLOCK);
    }

    private TmdbTvFullDetails seriesWithSeasons(TmdbSeasonSummary... seasons) {
        return new TmdbTvFullDetails("1399", "Breaking Bad", "Breaking Bad", null, "/poster.jpg", null,
                "2008-01-20", List.of(), List.of(), List.of(), List.of(), List.of(seasons), null, null,
                null, null, 2, 5, List.of(), null, "Ended");
    }

    private TmdbSeasonFullDetails season(int seasonNumber, TmdbEpisodeSummary... episodes) {
        return new TmdbSeasonFullDetails(seasonNumber, "Season " + seasonNumber, null, "/season.jpg",
                "2020-01-01", seasonNumber, List.of(episodes), null, null);
    }
}
