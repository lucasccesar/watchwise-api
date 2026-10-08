package com.watchwise.watchwise_api.trending.service.impl;

import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.exception.TmdbUnavailableException;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbSearchPage;
import com.watchwise.watchwise_api.common.tmdb.TmdbTrendingMovieResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbTrendingTvResult;
import com.watchwise.watchwise_api.content.dto.ContentCardDTO;
import com.watchwise.watchwise_api.content.dto.ContentCardFieldSet;
import com.watchwise.watchwise_api.content.dto.ContentCardViewerStateDTO;
import com.watchwise.watchwise_api.content.dto.ContentPreviewStatus;
import com.watchwise.watchwise_api.content.dto.WatchStatus;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.entity.MovieOrSeriesType;
import com.watchwise.watchwise_api.content.service.ContentCardContext;
import com.watchwise.watchwise_api.content.service.ContentCardSpec;
import com.watchwise.watchwise_api.content.service.ContentCoordinate;
import com.watchwise.watchwise_api.content.service.impl.ContentCardAssembler;
import com.watchwise.watchwise_api.trending.dto.TrendingCardDTO;
import com.watchwise.watchwise_api.trending.dto.TrendingResponseDTO;
import com.watchwise.watchwise_api.trending.service.TrendingTimeWindow;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
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

    @Mock
    private ContentCardAssembler contentCardAssembler;

    private TrendingServiceImpl service;
    private UUID viewerId;

    @BeforeEach
    void setUp() {
        service = new TrendingServiceImpl(userRepository, tmdbClient, contentCardAssembler);
        viewerId = UUID.randomUUID();
    }

    private void stubViewer() {
        when(userRepository.findById(viewerId)).thenReturn(Optional.of(
                User.builder().id(viewerId).preferredLanguage("pt-BR").preferredRegion("BR").build()));
    }

    @Test
    void shouldMapTmdbFieldsAndResolveOptionalCardMetadataAndViewerState() {
        stubViewer();
        when(tmdbClient.getTrendingMovies("day", "pt-BR", 1)).thenReturn(new TmdbLookupResult.Found<>(
                new TmdbSearchPage<>(1,
                        List.of(new TmdbTrendingMovieResult(
                                "603", "The Matrix", "/matrix.jpg", "1999-03-30",
                                List.of(28, 878), 8.7, 123.4)),
                        2, 21)));
        when(tmdbClient.getTrendingSeries("day", "pt-BR", 1)).thenReturn(new TmdbLookupResult.Found<>(
                new TmdbSearchPage<>(1,
                        List.of(new TmdbTrendingTvResult(
                                "1396", "Breaking Bad", "/breaking-bad.jpg", "2008-01-20",
                                List.of(18, 80), 9.1, 456.7)),
                        3, 42)));
        stubCards(Map.of(
                new ContentCoordinate(ContentType.MOVIE, "603", null, null, null),
                card(ContentType.MOVIE, "603", "The Matrix", "/matrix.jpg", 136),
                new ContentCoordinate(ContentType.SERIES, "1396", null, null, null),
                card(ContentType.SERIES, "1396", "Breaking Bad", "/breaking-bad.jpg", 47)));

        TrendingResponseDTO result = service.getTrending(viewerId, TrendingTimeWindow.DAY, 12);

        assertThat(result.movies()).containsExactly(new TrendingCardDTO(
                "603", MovieOrSeriesType.MOVIE, "The Matrix",
                "https://image.tmdb.org/t/p/w500/matrix.jpg", 1999,
                List.of("Science Fiction", "Action"), 8.7, 123.4, 136, null,
                card(ContentType.MOVIE, "603", "The Matrix", "/matrix.jpg", 136).viewerState(),
                ContentPreviewStatus.AVAILABLE));
        assertThat(result.series()).containsExactly(new TrendingCardDTO(
                "1396", MovieOrSeriesType.SERIES, "Breaking Bad",
                "https://image.tmdb.org/t/p/w500/breaking-bad.jpg", 2008,
                List.of("Drama", "Crime"), 9.1, 456.7, 47, 5,
                card(ContentType.SERIES, "1396", "Breaking Bad", "/breaking-bad.jpg", 47).viewerState(),
                ContentPreviewStatus.AVAILABLE));
        assertThat(result.moviesPage()).isEqualTo(new TrendingResponseDTO.SectionPage(1, 12, 2, 21, true));
        assertThat(result.seriesPage()).isEqualTo(new TrendingResponseDTO.SectionPage(1, 12, 3, 42, true));

        ArgumentCaptor<Collection<ContentCardSpec>> specs = ArgumentCaptor.forClass(Collection.class);
        verify(contentCardAssembler).assemble(
                specs.capture(),
                eq(new ContentCardContext("pt-BR", "BR", null, viewerId)),
                eq(Set.of(ContentCardFieldSet.BASIC_METADATA, ContentCardFieldSet.VIEWER_STATE)));
        assertThat(specs.getValue()).extracting(ContentCardSpec::coordinate)
                .containsExactly(
                        new ContentCoordinate(ContentType.MOVIE, "603", null, null, null),
                        new ContentCoordinate(ContentType.SERIES, "1396", null, null, null));
        verify(tmdbClient).getTrendingMovies("day", "pt-BR", 1);
        verify(tmdbClient).getTrendingSeries("day", "pt-BR", 1);
    }

    @Test
    void shouldLimitEachTrendingSectionToSizeInTmdbOrder() {
        stubViewer();
        List<TmdbTrendingMovieResult> movies = IntStream.rangeClosed(1, 22)
                .mapToObj(index -> new TmdbTrendingMovieResult(
                        "movie-" + index, "Movie " + index, null, null, null, null, null))
                .toList();
        List<TmdbTrendingTvResult> series = IntStream.rangeClosed(1, 22)
                .mapToObj(index -> new TmdbTrendingTvResult(
                        "series-" + index, "Series " + index, null, null, null, null, null))
                .toList();
        when(tmdbClient.getTrendingMovies("week", "pt-BR", 1)).thenReturn(new TmdbLookupResult.Found<>(
                new TmdbSearchPage<>(1, movies, 1, movies.size())));
        when(tmdbClient.getTrendingSeries("week", "pt-BR", 1)).thenReturn(new TmdbLookupResult.Found<>(
                new TmdbSearchPage<>(1, series, 1, series.size())));
        stubCards(Map.of());

        TrendingResponseDTO result = service.getTrending(viewerId, TrendingTimeWindow.WEEK, 21);

        assertThat(result.movies()).extracting(TrendingCardDTO::tmdbId)
                .containsExactlyElementsOf(IntStream.rangeClosed(1, 21).mapToObj(i -> "movie-" + i).toList());
        assertThat(result.series()).extracting(TrendingCardDTO::tmdbId)
                .containsExactlyElementsOf(IntStream.rangeClosed(1, 21).mapToObj(i -> "series-" + i).toList());
    }

    @Test
    void shouldReturnIndependentContinuationForRequestedSectionAndPage() {
        stubViewer();
        when(tmdbClient.getTrendingMovies("day", "pt-BR", 2)).thenReturn(new TmdbLookupResult.Found<>(
                new TmdbSearchPage<>(2, List.of(new TmdbTrendingMovieResult(
                        "604", "The Matrix Reloaded", null, "2003-05-07", List.of(28), 7.2, 90.0)), 4, 80)));
        stubCards(Map.of());

        TrendingResponseDTO result = service.getTrendingSection(
                viewerId, MovieOrSeriesType.MOVIE, TrendingTimeWindow.DAY, 2, 12);

        assertThat(result.movies()).extracting(TrendingCardDTO::tmdbId).containsExactly("604");
        assertThat(result.series()).isEmpty();
        assertThat(result.moviesPage()).isEqualTo(new TrendingResponseDTO.SectionPage(2, 12, 4, 80, true));
        assertThat(result.seriesPage()).isNull();
        verify(tmdbClient).getTrendingMovies("day", "pt-BR", 2);
        verify(tmdbClient, never()).getTrendingSeries(any(), any(), anyInt());
    }

    @Test
    void shouldPreserveMissingPosterAndNullOptionalFields() {
        stubViewer();
        when(tmdbClient.getTrendingMovies("day", "pt-BR", 1)).thenReturn(new TmdbLookupResult.Found<>(
                new TmdbSearchPage<>(1, List.of(new TmdbTrendingMovieResult(
                        "999", "No Poster", null, null, null, null, null)), 1, 1)));
        when(tmdbClient.getTrendingSeries("day", "pt-BR", 1)).thenReturn(new TmdbLookupResult.Found<>(
                new TmdbSearchPage<>(1, List.of(), 1, 0)));
        stubCards(Map.of());

        TrendingCardDTO result = service.getTrending(viewerId, TrendingTimeWindow.DAY, 12).movies().get(0);

        assertThat(result.posterUrl()).isNull();
        assertThat(result.year()).isNull();
        assertThat(result.genres()).isNull();
        assertThat(result.tmdbVoteAverage()).isNull();
        assertThat(result.popularity()).isNull();
        assertThat(result.runtimeMinutes()).isNull();
        assertThat(result.numberOfSeasons()).isNull();
    }

    @Test
    void shouldThrowWhenTrendingMoviesAreUnavailable() {
        stubViewer();
        when(tmdbClient.getTrendingMovies("day", "pt-BR", 1))
                .thenReturn(new TmdbLookupResult.Unavailable<>());

        assertThatThrownBy(() -> service.getTrending(viewerId, TrendingTimeWindow.DAY, 12))
                .isInstanceOf(TmdbUnavailableException.class)
                .hasMessage("TMDB is currently unavailable");
        verify(tmdbClient, never()).getTrendingSeries(any(), any(), anyInt());
        verifyNoInteractions(contentCardAssembler);
    }

    @Test
    void shouldThrowWhenTrendingSeriesAreUnavailable() {
        stubViewer();
        when(tmdbClient.getTrendingMovies("day", "pt-BR", 1))
                .thenReturn(new TmdbLookupResult.Found<>(new TmdbSearchPage<>(1, List.of(), 1, 0)));
        when(tmdbClient.getTrendingSeries("day", "pt-BR", 1))
                .thenReturn(new TmdbLookupResult.Unavailable<>());

        assertThatThrownBy(() -> service.getTrending(viewerId, TrendingTimeWindow.DAY, 12))
                .isInstanceOf(TmdbUnavailableException.class)
                .hasMessage("TMDB is currently unavailable");
        verifyNoInteractions(contentCardAssembler);
    }

    @Test
    void shouldReturnEmptySectionsWhenTmdbReportsNotFound() {
        stubViewer();
        when(tmdbClient.getTrendingMovies("day", "pt-BR", 1))
                .thenReturn(new TmdbLookupResult.NotFound<>());
        when(tmdbClient.getTrendingSeries("day", "pt-BR", 1))
                .thenReturn(new TmdbLookupResult.NotFound<>());
        TrendingResponseDTO result = service.getTrending(viewerId, TrendingTimeWindow.DAY, 12);

        assertThat(result.movies()).isEmpty();
        assertThat(result.series()).isEmpty();
        assertThat(result.moviesPage()).isEqualTo(new TrendingResponseDTO.SectionPage(1, 12, 0, 0, false));
        assertThat(result.seriesPage()).isEqualTo(new TrendingResponseDTO.SectionPage(1, 12, 0, 0, false));
    }

    @Test
    void shouldReturnEmptySectionsWhenTmdbPageIsNull() {
        stubViewer();
        when(tmdbClient.getTrendingMovies("day", "pt-BR", 1))
                .thenReturn(new TmdbLookupResult.Found<>(null));
        when(tmdbClient.getTrendingSeries("day", "pt-BR", 1))
                .thenReturn(new TmdbLookupResult.Found<>(null));
        TrendingResponseDTO result = service.getTrending(viewerId, TrendingTimeWindow.DAY, 12);

        assertThat(result.movies()).isEmpty();
        assertThat(result.series()).isEmpty();
        assertThat(result.moviesPage()).isEqualTo(new TrendingResponseDTO.SectionPage(1, 12, 0, 0, false));
        assertThat(result.seriesPage()).isEqualTo(new TrendingResponseDTO.SectionPage(1, 12, 0, 0, false));
    }

    @Test
    void shouldThrowWhenViewerDoesNotExistWithoutCallingTmdb() {
        when(userRepository.findById(viewerId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getTrending(viewerId, TrendingTimeWindow.DAY, 12))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("User not found");
        verifyNoInteractions(tmdbClient, contentCardAssembler);
    }

    private void stubCards(Map<ContentCoordinate, ContentCardDTO> cards) {
        when(contentCardAssembler.assemble(anyCollection(), any(ContentCardContext.class), anySet()))
                .thenAnswer(invocation -> cards);
    }

    private ContentCardDTO card(
            ContentType type, String tmdbId, String title, String posterPath, Integer runtimeMinutes) {
        LocalDate releaseDate = type == ContentType.SERIES
                ? LocalDate.of(2008, 1, 20)
                : LocalDate.of(1999, 3, 30);
        Integer releaseYear = releaseDate.getYear();
        return new ContentCardDTO(
                UUID.randomUUID(), type, tmdbId, null, null, null,
                title, posterPath, null, releaseDate, releaseYear,
                runtimeMinutes, null, type == ContentType.SERIES ? 5 : null, null,
                type == ContentType.SERIES ? List.of("Drama", "Crime") : List.of("Science Fiction", "Action"),
                null,
                new ContentCardViewerStateDTO(
                        WatchStatus.WATCHED, 9, LocalDate.of(2026, 10, 1), 2,
                        false, 2, true, false, null, null),
                ContentPreviewStatus.AVAILABLE);
    }
}
