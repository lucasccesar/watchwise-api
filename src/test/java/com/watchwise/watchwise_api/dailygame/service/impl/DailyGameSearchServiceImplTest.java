package com.watchwise.watchwise_api.dailygame.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.exception.TmdbUnavailableException;
import com.watchwise.watchwise_api.common.pagination.PageRequestFactory;
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
import com.watchwise.watchwise_api.dailygame.dto.DailyGameEpisodeOptionDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameSeasonOptionDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameSearchResultDTO;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameTargetKind;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

    @ParameterizedTest
    @EnumSource(value = DailyGameType.class, names = {"MOVIE_BY_POSTER", "MOVIE_BY_INFO"})
    @DisplayName("[search] Should Return Movie Candidates - When The Game Type Targets Movies")
    void shouldReturnMovieCandidatesWhenTheGameTypeTargetsMovies(DailyGameType gameType) {
        when(tmdbClient.searchMovies("fight", LANGUAGE, 1)).thenReturn(new TmdbLookupResult.Found<>(
                new TmdbSearchPage<>(1, List.of(
                        new TmdbMovieSearchResult("603", "The Matrix", "/matrix.jpg", "1999-03-31"),
                        new TmdbMovieSearchResult("550", "Fight Club", "/poster.jpg", "1999-10-15")), 5, 5)));

        Page<DailyGameSearchResultDTO> result = service().search(USER_ID, gameType, "  fight  ", 2, 1);

        assertThat(result.getContent()).containsExactly(new DailyGameSearchResultDTO(
                DailyGameTargetKind.MOVIE, "550", null, null, null, null,
                "Fight Club", "https://image.tmdb.org/t/p/w500/poster.jpg", LocalDate.of(1999, 10, 15)));
        assertThat(result.getNumber()).isEqualTo(1);
        assertThat(result.getSize()).isEqualTo(1);
        assertThat(result.getTotalElements()).isEqualTo(5);
        verify(tmdbClient).searchMovies("fight", LANGUAGE, 1);
        verify(tmdbClient, never()).searchTv(anyString(), anyString(), anyInt());
        verify(tmdbClient, never()).searchPeople(anyString(), anyString(), anyInt());
    }

    @ParameterizedTest
    @EnumSource(value = DailyGameType.class, names = {"SERIES_BY_POSTER", "SERIES_BY_INFO"})
    @DisplayName("[search] Should Return Series Candidates - When The Game Type Targets Series")
    void shouldReturnSeriesCandidatesWhenTheGameTypeTargetsSeries(DailyGameType gameType) {
        when(tmdbClient.searchTv("breaking", LANGUAGE, 1)).thenReturn(new TmdbLookupResult.Found<>(
                new TmdbSearchPage<>(1, List.of(
                        new TmdbTvSearchResult("1399", "Breaking Bad", "/poster.jpg", "2008-01-20"),
                        new TmdbTvSearchResult("94997", "House of the Dragon", "/dragon.jpg", "2022-08-21")), 1, 2)));

        Page<DailyGameSearchResultDTO> result = service().search(USER_ID, gameType, "breaking", 2, 1);

        assertThat(result.getContent()).containsExactly(new DailyGameSearchResultDTO(
                DailyGameTargetKind.SERIES, "94997", null, null, null, null,
                "House of the Dragon", "https://image.tmdb.org/t/p/w500/dragon.jpg", LocalDate.of(2022, 8, 21)));
        verify(tmdbClient).searchTv("breaking", LANGUAGE, 1);
        verify(tmdbClient, never()).searchTv("breaking", LANGUAGE, 2);
        verify(tmdbClient, never()).searchMovies(anyString(), anyString(), anyInt());
        verify(tmdbClient, never()).searchPeople(anyString(), anyString(), anyInt());
    }

    @ParameterizedTest
    @EnumSource(value = DailyGameType.class, names = {"PERSON_BY_FACE", "ACTOR_BY_MOVIE_FILMOGRAPHY", "ACTOR_BY_SERIES_FILMOGRAPHY"})
    @DisplayName("[search] Should Return Person Candidates - When The Game Type Targets People")
    void shouldReturnPersonCandidatesWhenTheGameTypeTargetsPeople(DailyGameType gameType) {
        when(tmdbClient.searchPeople("fincher", LANGUAGE, 1)).thenReturn(new TmdbLookupResult.Found<>(
                new TmdbSearchPage<>(1, List.of(
                        new TmdbPersonSearchResult("7467", "David Fincher", "/profile.jpg"),
                        new TmdbPersonSearchResult("500", "Actor", "/actor.jpg")), 1, 2)));

        Page<DailyGameSearchResultDTO> result = service().search(USER_ID, gameType, "fincher", 2, 1);

        assertThat(result.getContent()).containsExactly(new DailyGameSearchResultDTO(
                DailyGameTargetKind.PERSON, null, "500", null, null, null,
                "Actor", "https://image.tmdb.org/t/p/w185/actor.jpg", null));
        verify(tmdbClient).searchPeople("fincher", LANGUAGE, 1);
        verify(tmdbClient, never()).searchPeople("fincher", LANGUAGE, 2);
        verify(tmdbClient, never()).searchMovies(anyString(), anyString(), anyInt());
        verify(tmdbClient, never()).searchTv(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("[search] Should Reject Generic Episode Search - When The Game Type Is Episode By Frame")
    void shouldRejectGenericEpisodeSearchWhenTheGameTypeIsEpisodeByFrame() {
        assertThatThrownBy(() -> service().search(USER_ID, DailyGameType.EPISODE_BY_FRAME, "pilot", 1, 20))
                .isInstanceOf(BadRequestException.class);

        verifyNoInteractions(tmdbClient);
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

        verifyNoInteractions(tmdbClient);
    }

    @Test
    @DisplayName("[search] Should Reject Oversized Query - When The Query Exceeds The Limit")
    void shouldRejectOversizedQueryWhenTheQueryExceedsTheLimit() {
        String query = "x".repeat(101);

        assertThatThrownBy(() -> service().search(USER_ID, DailyGameType.MOVIE_BY_INFO, query, 1, 20))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("q must be at most 100 characters");

        verifyNoInteractions(tmdbClient);
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
    @DisplayName("[search] Should Use The Maximum Page Size - When The Requested Size Is At The Safe Maximum")
    void shouldUseTheMaximumPageSizeWhenTheRequestedSizeIsAtTheSafeMaximum() {
        stubMovieSearchPage("fight", 1, List.of(movie("550", "Fight Club")), 1, 1);

        Page<DailyGameSearchResultDTO> result = service().search(
                USER_ID, DailyGameType.MOVIE_BY_INFO, "fight", 1, 100);

        assertThat(result.getSize()).isEqualTo(100);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = 0)
    @DisplayName("[search] Should Use The First Page - When The Page Number Is Null Or Zero")
    void shouldUseTheFirstPageWhenThePageNumberIsNullOrZero(Integer page) {
        stubMovieSearchPage("fight", 1, List.of(movie("550", "Fight Club")), 1, 1);

        Page<DailyGameSearchResultDTO> result = service().search(
                USER_ID, DailyGameType.MOVIE_BY_INFO, "fight", page, 1);

        assertThat(result.getNumber()).isZero();
        verify(tmdbClient).searchMovies("fight", LANGUAGE, 1);
    }

    @Test
    @DisplayName("[search] Should Reject Negative Page Number - When The Page Number Is Negative")
    void shouldRejectNegativePageNumberWhenThePageNumberIsNegative() {
        assertThatThrownBy(() -> service().search(USER_ID, DailyGameType.MOVIE_BY_INFO, "fight", -1, 20))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Page number must be greater than or equal to 0");

        verifyNoInteractions(tmdbClient);
    }

    @Test
    @DisplayName("[search] Should Use The Default Page Size - When The Page Size Is Null")
    void shouldUseTheDefaultPageSizeWhenThePageSizeIsNull() {
        stubMovieSearchPage("fight", 1, List.of(movie("550", "Fight Club")), 1, 1);

        Page<DailyGameSearchResultDTO> result = service().search(
                USER_ID, DailyGameType.MOVIE_BY_INFO, "fight", 1, null);

        assertThat(result.getSize()).isEqualTo(PageRequestFactory.DEFAULT_PAGE_SIZE);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    @DisplayName("[search] Should Reject Invalid Page Size - When The Page Size Is Zero Or Negative")
    void shouldRejectInvalidPageSizeWhenThePageSizeIsZeroOrNegative(Integer size) {
        assertThatThrownBy(() -> service().search(USER_ID, DailyGameType.MOVIE_BY_INFO, "fight", 1, size))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Page size must be greater than 0");

        verifyNoInteractions(tmdbClient);
    }

    @Test
    @DisplayName("[search] Should Fetch All Remote Pages Covering The Requested Slice - When The Page Size Exceeds Remote Capacity")
    void shouldFetchAllRemotePagesCoveringTheRequestedSliceWhenThePageSizeExceedsRemoteCapacity() {
        List<TmdbMovieSearchResult> firstRemotePage = IntStream.rangeClosed(1, 20)
                .mapToObj(index -> movie(String.valueOf(index), "Movie " + index))
                .toList();
        stubMovieSearchPage("movie", 1, firstRemotePage, 2, 21);
        stubMovieSearchPage("movie", 2, List.of(movie("21", "Movie 21")), 2, 21);

        Page<DailyGameSearchResultDTO> result = service().search(
                USER_ID, DailyGameType.MOVIE_BY_INFO, "movie", 1, 21);

        assertThat(result.getContent()).hasSize(21);
        assertThat(result.getContent().getLast().tmdbId()).isEqualTo("21");
        assertThat(result.getTotalElements()).isEqualTo(21);
        assertThat(result.getTotalPages()).isEqualTo(1);
        verify(tmdbClient).searchMovies("movie", LANGUAGE, 1);
        verify(tmdbClient).searchMovies("movie", LANGUAGE, 2);
    }

    @Test
    @DisplayName("[search] Should Return The Exact Global Slice And Metadata - When Page Two Requests One Result")
    void shouldReturnTheExactGlobalSliceAndMetadataWhenPageTwoRequestsOneResult() {
        stubMovieSearchPage("fight", 1, List.of(
                movie("603", "The Matrix"), movie("550", "Fight Club")), 5, 5);

        Page<DailyGameSearchResultDTO> result = service().search(
                USER_ID, DailyGameType.MOVIE_BY_INFO, "fight", 2, 1);

        assertThat(result.getContent()).extracting(DailyGameSearchResultDTO::tmdbId)
                .containsExactly("550");
        assertThat(result.getNumber()).isEqualTo(1);
        assertThat(result.getSize()).isEqualTo(1);
        assertThat(result.getTotalElements()).isEqualTo(5);
        assertThat(result.getTotalPages()).isEqualTo(5);
        assertThat(result.hasNext()).isTrue();
        verify(tmdbClient).searchMovies("fight", LANGUAGE, 1);
        verify(tmdbClient, never()).searchMovies("fight", LANGUAGE, 2);
    }

    @Test
    @DisplayName("[searchEpisodeSeries] Should Return The Exact Global Slice - When Page Two Requests One Result")
    void shouldReturnTheExactGlobalSliceWhenEpisodeSeriesPageTwoRequestsOneResult() {
        when(tmdbClient.searchTv("office", LANGUAGE, 1)).thenReturn(new TmdbLookupResult.Found<>(
                new TmdbSearchPage<>(1, List.of(
                        new TmdbTvSearchResult("2316", "The Office", "/office.jpg", "2005-03-24"),
                        new TmdbTvSearchResult("400", "The Office UK", "/office-uk.jpg", "2001-07-09")), 2, 2)));

        Page<DailyGameSearchResultDTO> result = service().searchEpisodeSeries(USER_ID, "office", 2, 1);

        assertThat(result.getContent()).extracting(DailyGameSearchResultDTO::tmdbId)
                .containsExactly("400");
        assertThat(result.getNumber()).isEqualTo(1);
        assertThat(result.getTotalElements()).isEqualTo(2);
        verify(tmdbClient).searchTv("office", LANGUAGE, 1);
        verify(tmdbClient, never()).searchTv("office", LANGUAGE, 2);
    }

    @Test
    @DisplayName("[listEpisodeSeasons] Should Accept Long Positive Numeric Series Identifier - When The Identifier Exceeds Twenty Digits")
    void shouldAcceptLongPositiveNumericSeriesIdentifierWhenListingEpisodeSeasons() {
        String seriesTmdbId = "123456789012345678901";
        when(tmdbClient.getTvFullDetails(seriesTmdbId, LANGUAGE)).thenReturn(new TmdbLookupResult.NotFound<>());

        assertThatThrownBy(() -> service().listEpisodeSeasons(USER_ID, seriesTmdbId))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("No series found on TMDB for the given id");
        verify(tmdbClient).getTvFullDetails(seriesTmdbId, LANGUAGE);
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
    @DisplayName("[listEpisodeSeasons] Should Omit Specials And Map Only Dropdown Fields - When The Series Has Regular And Special Seasons")
    void shouldOmitSpecialsAndMapOnlyDropdownFieldsWhenListingEpisodeSeasons() {
        when(tmdbClient.getTvFullDetails("1399", LANGUAGE)).thenReturn(new TmdbLookupResult.Found<>(seriesWithSeasons(
                new TmdbSeasonSummary(0, "Specials", null, "2008-01-01", 1, "/special.jpg"),
                new TmdbSeasonSummary(1, "Season 1", null, "2008-01-20", 4, "/season-1.jpg"),
                new TmdbSeasonSummary(-1, "Other Specials", null, "2007-01-01", 2, "/other-special.jpg"),
                new TmdbSeasonSummary(2, "Season 2", null, "2027-01-01", 1, "/season-2.jpg"))));

        List<DailyGameSeasonOptionDTO> result = service().listEpisodeSeasons(USER_ID, "1399");

        assertThat(result).containsExactly(
                new DailyGameSeasonOptionDTO(1, "Season 1", 4),
                new DailyGameSeasonOptionDTO(2, "Season 2", 1));
        verify(tmdbClient).getTvFullDetails("1399", LANGUAGE);
    }

    @Test
    @DisplayName("[listEpisodeEpisodes] Should Return Released Episodes Without Image Fields - When A Season Has Future And Image-less Episodes")
    void shouldReturnReleasedEpisodesWithoutImageFieldsWhenASeasonHasFutureAndImageLessEpisodes() {
        when(tmdbClient.getSeasonFullDetails("1399", 1, LANGUAGE)).thenReturn(new TmdbLookupResult.Found<>(season(1,
                new TmdbEpisodeSummary(1, "Pilot", null, "2020-01-01", 45, "/pilot.jpg", null),
                new TmdbEpisodeSummary(2, "Future Fight", null, "2026-09-28", 45, "/future.jpg", null),
                new TmdbEpisodeSummary(3, "No Still Fight", null, "2020-02-01", 45, null, null),
                new TmdbEpisodeSummary(4, "Unaired", null, null, 45, "/unaired.jpg", null))));

        List<DailyGameEpisodeOptionDTO> result = service().listEpisodeEpisodes(USER_ID, "1399", 1);

        assertThat(result).containsExactly(
                new DailyGameEpisodeOptionDTO("1399", 1, 1, "Pilot", LocalDate.of(2020, 1, 1)),
                new DailyGameEpisodeOptionDTO("1399", 1, 3, "No Still Fight", LocalDate.of(2020, 2, 1)));
        verify(tmdbClient).getSeasonFullDetails("1399", 1, LANGUAGE);
    }

    @Test
    @DisplayName("[listEpisodeSeasons] Should Reject Invalid Series Identifier - When The Identifier Is Not Positive Numeric")
    void shouldRejectInvalidSeriesIdentifierWhenListingEpisodeSeasons() {
        assertThatThrownBy(() -> service().listEpisodeSeasons(USER_ID, "abc"))
                .isInstanceOf(BadRequestException.class);

        verifyNoInteractions(tmdbClient);
    }

    @Test
    @DisplayName("[listEpisodeEpisodes] Should Reject Invalid Season Coordinate - When The Season Number Is Not Positive")
    void shouldRejectInvalidSeasonCoordinateWhenTheSeasonNumberIsNotPositive() {
        assertThatThrownBy(() -> service().listEpisodeEpisodes(USER_ID, "1399", 0))
                .isInstanceOf(BadRequestException.class);

        verifyNoInteractions(tmdbClient);
    }

    @Test
    @DisplayName("[listEpisodeSeasons] Should Throw NotFoundException - When The Series Is Not Found")
    void shouldThrowNotFoundExceptionWhenTheSeriesIsNotFound() {
        when(tmdbClient.getTvFullDetails("1399", LANGUAGE)).thenReturn(new TmdbLookupResult.NotFound<>());

        assertThatThrownBy(() -> service().listEpisodeSeasons(USER_ID, "1399"))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("No series found on TMDB for the given id");
    }

    @Test
    @DisplayName("[listEpisodeEpisodes] Should Throw NotFoundException - When The Season Is Not Found")
    void shouldThrowNotFoundExceptionWhenTheSeasonIsNotFound() {
        when(tmdbClient.getSeasonFullDetails("1399", 1, LANGUAGE)).thenReturn(new TmdbLookupResult.NotFound<>());

        assertThatThrownBy(() -> service().listEpisodeEpisodes(USER_ID, "1399", 1))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("No season found on TMDB for the given id");
    }

    @Test
    @DisplayName("[listEpisodeSeasons] Should Return Empty List - When A Found Series Has No Regular Seasons")
    void shouldReturnEmptyListWhenAFoundSeriesHasNoRegularSeasons() {
        when(tmdbClient.getTvFullDetails("1399", LANGUAGE))
                .thenReturn(new TmdbLookupResult.Found<>(seriesWithSeasons()));

        assertThat(service().listEpisodeSeasons(USER_ID, "1399")).isEmpty();
    }

    @Test
    @DisplayName("[listEpisodeEpisodes] Should Return Empty List - When A Found Season Has No Episodes")
    void shouldReturnEmptyListWhenAFoundSeasonHasNoEpisodes() {
        when(tmdbClient.getSeasonFullDetails("1399", 1, LANGUAGE))
                .thenReturn(new TmdbLookupResult.Found<>(season(1)));

        assertThat(service().listEpisodeEpisodes(USER_ID, "1399", 1)).isEmpty();
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
    @DisplayName("[listEpisodeEpisodes] Should Propagate TMDB Unavailable - When A Season Lookup Is Unavailable")
    void shouldPropagateTmdbUnavailableWhenASeasonLookupIsUnavailable() {
        when(tmdbClient.getSeasonFullDetails("1399", 1, LANGUAGE)).thenReturn(new TmdbLookupResult.Unavailable<>());

        assertThatThrownBy(() -> service().listEpisodeEpisodes(USER_ID, "1399", 1))
                .isInstanceOf(TmdbUnavailableException.class)
                .hasMessage("TMDB is currently unavailable");
    }

    private DailyGameSearchServiceImpl service() {
        return new DailyGameSearchServiceImpl(tmdbClient, new PageRequestFactory(), CLOCK);
    }

    private TmdbMovieSearchResult movie(String id, String title) {
        return new TmdbMovieSearchResult(id, title, "/poster.jpg", "1999-10-15");
    }

    private void stubMovieSearchPage(String query, int page, List<TmdbMovieSearchResult> results,
                                     int totalPages, long totalResults) {
        when(tmdbClient.searchMovies(query, LANGUAGE, page)).thenReturn(new TmdbLookupResult.Found<>(
                new TmdbSearchPage<>(page, results, totalPages, totalResults)));
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
