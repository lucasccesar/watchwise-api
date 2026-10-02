package com.watchwise.watchwise_api.trending.service.impl;

import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.exception.TmdbUnavailableException;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbSearchPage;
import com.watchwise.watchwise_api.common.tmdb.TmdbTrendingMovieResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbTrendingTvResult;
import com.watchwise.watchwise_api.content.entity.MovieOrSeriesType;
import com.watchwise.watchwise_api.search.dto.SearchContentDTO;
import com.watchwise.watchwise_api.trending.dto.TrendingResponseDTO;
import com.watchwise.watchwise_api.trending.service.TrendingTimeWindow;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TrendingServiceImplTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private TmdbClient tmdbClient;

    private TrendingServiceImpl service;
    private UUID viewerId;

    @BeforeEach
    void setUp() {
        service = new TrendingServiceImpl(userRepository, tmdbClient);
        viewerId = UUID.randomUUID();
    }

    private void stubViewer() {
        when(userRepository.findById(viewerId)).thenReturn(Optional.of(
                User.builder().id(viewerId).preferredLanguage("pt-BR").build()));
    }

    @Test
    void shouldMapTrendingMovieAndSeriesUsingViewersPreferredLanguage() {
        stubViewer();
        when(tmdbClient.getTrendingMovies("day", "pt-BR")).thenReturn(new TmdbLookupResult.Found<>(
                new TmdbSearchPage<>(1,
                        List.of(new TmdbTrendingMovieResult("603", "The Matrix", "/matrix.jpg", "1999-03-30")),
                        1, 1)));
        when(tmdbClient.getTrendingSeries("day", "pt-BR")).thenReturn(new TmdbLookupResult.Found<>(
                new TmdbSearchPage<>(1,
                        List.of(new TmdbTrendingTvResult("1396", "Breaking Bad", "/breaking-bad.jpg", "2008-01-20")),
                        1, 1)));

        TrendingResponseDTO result = service.getTrending(viewerId, TrendingTimeWindow.DAY, 12);

        assertThat(result.movies()).containsExactly(
                new SearchContentDTO("603", MovieOrSeriesType.MOVIE, "The Matrix",
                        "https://image.tmdb.org/t/p/w500/matrix.jpg", 1999));
        assertThat(result.series()).containsExactly(
                new SearchContentDTO("1396", MovieOrSeriesType.SERIES, "Breaking Bad",
                        "https://image.tmdb.org/t/p/w500/breaking-bad.jpg", 2008));
        verify(tmdbClient).getTrendingMovies("day", "pt-BR");
        verify(tmdbClient).getTrendingSeries("day", "pt-BR");
    }

    @Test
    void shouldLimitEachTrendingSectionToSizeInTmdbOrder() {
        stubViewer();
        List<TmdbTrendingMovieResult> movies = IntStream.rangeClosed(1, 22)
                .mapToObj(index -> new TmdbTrendingMovieResult("movie-" + index, "Movie " + index, null, null))
                .toList();
        List<TmdbTrendingTvResult> series = IntStream.rangeClosed(1, 22)
                .mapToObj(index -> new TmdbTrendingTvResult("series-" + index, "Series " + index, null, null))
                .toList();
        when(tmdbClient.getTrendingMovies("week", "pt-BR")).thenReturn(new TmdbLookupResult.Found<>(
                new TmdbSearchPage<>(1, movies, 1, movies.size())));
        when(tmdbClient.getTrendingSeries("week", "pt-BR")).thenReturn(new TmdbLookupResult.Found<>(
                new TmdbSearchPage<>(1, series, 1, series.size())));

        TrendingResponseDTO result = service.getTrending(viewerId, TrendingTimeWindow.WEEK, 21);

        assertThat(result.movies()).extracting(SearchContentDTO::tmdbId)
                .containsExactlyElementsOf(IntStream.rangeClosed(1, 21).mapToObj(i -> "movie-" + i).toList());
        assertThat(result.series()).extracting(SearchContentDTO::tmdbId)
                .containsExactlyElementsOf(IntStream.rangeClosed(1, 21).mapToObj(i -> "series-" + i).toList());
    }

    @Test
    void shouldReturnNullReleaseYearsForMissingOrInvalidDates() {
        stubViewer();
        when(tmdbClient.getTrendingMovies("day", "pt-BR")).thenReturn(new TmdbLookupResult.Found<>(
                new TmdbSearchPage<>(1, List.of(
                        new TmdbTrendingMovieResult("movie-invalid", "Invalid", null, "not-a-date"),
                        new TmdbTrendingMovieResult("movie-missing", "Missing", null, null)), 1, 2)));
        when(tmdbClient.getTrendingSeries("day", "pt-BR")).thenReturn(new TmdbLookupResult.Found<>(
                new TmdbSearchPage<>(1, List.of(
                        new TmdbTrendingTvResult("series-invalid", "Invalid", null, "2020"),
                        new TmdbTrendingTvResult("series-missing", "Missing", null, null)), 1, 2)));

        TrendingResponseDTO result = service.getTrending(viewerId, TrendingTimeWindow.DAY, 12);

        assertThat(result.movies()).allSatisfy(movie -> assertThat(movie.year()).isNull());
        assertThat(result.series()).allSatisfy(series -> assertThat(series.year()).isNull());
    }

    @Test
    void shouldThrowWhenTrendingMoviesAreUnavailable() {
        stubViewer();
        when(tmdbClient.getTrendingMovies("day", "pt-BR"))
                .thenReturn(new TmdbLookupResult.Unavailable<>());

        assertThatThrownBy(() -> service.getTrending(viewerId, TrendingTimeWindow.DAY, 12))
                .isInstanceOf(TmdbUnavailableException.class)
                .hasMessage("TMDB is currently unavailable");
        verify(tmdbClient, never()).getTrendingSeries("day", "pt-BR");
    }

    @Test
    void shouldThrowWhenTrendingSeriesAreUnavailable() {
        stubViewer();
        when(tmdbClient.getTrendingMovies("day", "pt-BR"))
                .thenReturn(new TmdbLookupResult.Found<>(new TmdbSearchPage<>(1, List.of(), 1, 0)));
        when(tmdbClient.getTrendingSeries("day", "pt-BR"))
                .thenReturn(new TmdbLookupResult.Unavailable<>());

        assertThatThrownBy(() -> service.getTrending(viewerId, TrendingTimeWindow.DAY, 12))
                .isInstanceOf(TmdbUnavailableException.class)
                .hasMessage("TMDB is currently unavailable");
    }

    @Test
    void shouldReturnEmptySectionsWhenTmdbReportsNotFound() {
        stubViewer();
        when(tmdbClient.getTrendingMovies("day", "pt-BR"))
                .thenReturn(new TmdbLookupResult.NotFound<>());
        when(tmdbClient.getTrendingSeries("day", "pt-BR"))
                .thenReturn(new TmdbLookupResult.NotFound<>());

        TrendingResponseDTO result = service.getTrending(viewerId, TrendingTimeWindow.DAY, 12);

        assertThat(result.movies()).isEmpty();
        assertThat(result.series()).isEmpty();
    }

    @Test
    void shouldReturnEmptySectionsWhenTmdbPageIsNull() {
        stubViewer();
        when(tmdbClient.getTrendingMovies("day", "pt-BR"))
                .thenReturn(new TmdbLookupResult.Found<>(null));
        when(tmdbClient.getTrendingSeries("day", "pt-BR"))
                .thenReturn(new TmdbLookupResult.Found<>(null));

        TrendingResponseDTO result = service.getTrending(viewerId, TrendingTimeWindow.DAY, 12);

        assertThat(result.movies()).isEmpty();
        assertThat(result.series()).isEmpty();
    }

    @Test
    void shouldThrowWhenViewerDoesNotExistWithoutCallingTmdb() {
        when(userRepository.findById(viewerId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getTrending(viewerId, TrendingTimeWindow.DAY, 12))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("User not found");
        verifyNoInteractions(tmdbClient);
    }
}
