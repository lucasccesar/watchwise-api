package com.watchwise.watchwise_api.search.service.impl;

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
import com.watchwise.watchwise_api.search.dto.SearchContentViewDTO;
import com.watchwise.watchwise_api.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchContentViewAssemblerTest {

    private static final UUID VIEWER_ID = UUID.randomUUID();

    @Mock
    private ContentCardAssembler contentCardAssembler;

    private SearchContentViewAssembler assembler;
    private User viewer;

    @BeforeEach
    void setUp() {
        assembler = new SearchContentViewAssembler(contentCardAssembler);
        viewer = User.builder()
                .id(VIEWER_ID)
                .preferredLanguage("pt-BR")
                .preferredRegion("BR")
                .build();
    }

    @Test
    @DisplayName("[assemble] Should Assemble Ordered Cards With One Batched Card Request - When Movie And Series Candidates Are Provided")
    void shouldAssembleOrderedCardsWithOneBatchedCardRequestWhenMovieAndSeriesCandidatesAreProvided() {
        ContentCoordinate movie = movie("101");
        ContentCoordinate series = series("202");
        ContentCardViewerStateDTO movieViewerState = viewerState(WatchStatus.PARTIALLY_WATCHED, true);
        ContentCardViewerStateDTO seriesViewerState = viewerState(WatchStatus.UNWATCHED, false);
        SearchContentCandidate movieCandidate = candidate(
                movie, "Raw movie", "/raw-movie.jpg", 8.1d);
        SearchContentCandidate seriesCandidate = candidate(
                series, "Raw series", "/raw-series.jpg", 7.4d);
        when(contentCardAssembler.assemble(anyCollection(), any(ContentCardContext.class), anySet()))
                .thenReturn(Map.of(
                        movie, card(movie, "Movie", "/movie.jpg", null, movieViewerState,
                                ContentPreviewStatus.AVAILABLE),
                        series, card(series, "Series", "/series.jpg", 3, seriesViewerState,
                                ContentPreviewStatus.AVAILABLE)));

        List<SearchContentViewDTO> cards = assembler.assemble(viewer, List.of(movieCandidate, seriesCandidate));

        assertThat(cards).extracting(SearchContentViewDTO::posterUrl)
                .containsExactly(
                        "https://image.tmdb.org/t/p/w500/movie.jpg",
                        "https://image.tmdb.org/t/p/w500/series.jpg");
        assertThat(cards).extracting(SearchContentViewDTO::voteAverage).containsExactly(8.1d, 7.4d);
        assertThat(cards).extracting(SearchContentViewDTO::type)
                .containsExactly(MovieOrSeriesType.MOVIE, MovieOrSeriesType.SERIES);
        assertThat(cards).extracting(SearchContentViewDTO::viewerState)
                .containsExactly(movieViewerState, seriesViewerState);
        assertThat(cards).extracting(SearchContentViewDTO::numberOfSeasons)
                .containsExactly(null, 3);

        ArgumentCaptor<Collection<ContentCardSpec>> specs = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<ContentCardContext> context = ArgumentCaptor.forClass(ContentCardContext.class);
        ArgumentCaptor<Set<ContentCardFieldSet>> fields = ArgumentCaptor.forClass(Set.class);
        verify(contentCardAssembler).assemble(specs.capture(), context.capture(), fields.capture());
        assertThat(specs.getValue()).extracting(ContentCardSpec::coordinate)
                .containsExactly(movie, series);
        assertThat(context.getValue()).isEqualTo(
                new ContentCardContext("pt-BR", "BR", null, VIEWER_ID));
        assertThat(fields.getValue()).containsExactlyInAnyOrder(
                ContentCardFieldSet.BASIC_METADATA,
                ContentCardFieldSet.VIEWER_STATE,
                ContentCardFieldSet.WATCHLIST_PROGRESS);
    }

    @Test
    @DisplayName("[assemble] Should Keep Raw TMDB Fallbacks - When Card Enrichment Is Unavailable")
    void shouldKeepRawTmdbFallbacksWhenCardEnrichmentIsUnavailable() {
        ContentCoordinate movie = movie("303");
        SearchContentCandidate movieCandidate = new SearchContentCandidate(
                movie,
                "Night Shift",
                "/movie.jpg",
                LocalDate.of(2024, 1, 2),
                105,
                8.1d,
                List.of("Drama"),
                5);
        when(contentCardAssembler.assemble(anyCollection(), any(ContentCardContext.class), anySet()))
                .thenReturn(Map.of());

        assertThat(assembler.assemble(viewer, List.of(movieCandidate))).singleElement()
                .satisfies(card -> {
                    assertThat(card.tmdbId()).isEqualTo("303");
                    assertThat(card.title()).isEqualTo("Night Shift");
                    assertThat(card.type()).isEqualTo(MovieOrSeriesType.MOVIE);
                    assertThat(card.posterUrl())
                            .isEqualTo("https://image.tmdb.org/t/p/w500/movie.jpg");
                    assertThat(card.releaseYear()).isEqualTo(2024);
                    assertThat(card.genres()).containsExactly("Drama");
                    assertThat(card.runtimeMinutes()).isEqualTo(105);
                    assertThat(card.voteAverage()).isEqualTo(8.1d);
                    assertThat(card.numberOfSeasons()).isEqualTo(5);
                    assertThat(card.previewStatus()).isEqualTo(ContentPreviewStatus.PARTIAL);
                });
    }

    @Test
    @DisplayName("[assemble] Should Request Each Distinct Coordinate Once While Preserving Candidate Order - When Candidates Contain Duplicates")
    void shouldRequestEachDistinctCoordinateOnceWhilePreservingCandidateOrderWhenCandidatesContainDuplicates() {
        ContentCoordinate movie = movie("404");
        ContentCoordinate series = series("505");
        SearchContentCandidate first = candidate(movie, "First", "/first.jpg", 8.1d);
        SearchContentCandidate duplicate = candidate(movie, "Duplicate fallback", "/duplicate.jpg", 7.1d);
        SearchContentCandidate third = candidate(series, "Third", "/third.jpg", 7.4d);
        when(contentCardAssembler.assemble(anyCollection(), any(ContentCardContext.class), anySet()))
                .thenReturn(Map.of());

        List<SearchContentViewDTO> cards = assembler.assemble(viewer, List.of(first, duplicate, third));

        assertThat(cards).extracting(SearchContentViewDTO::title)
                .containsExactly("First", "Duplicate fallback", "Third");
        ArgumentCaptor<Collection<ContentCardSpec>> specs = ArgumentCaptor.forClass(Collection.class);
        verify(contentCardAssembler).assemble(specs.capture(), any(ContentCardContext.class), anySet());
        assertThat(specs.getValue()).extracting(ContentCardSpec::coordinate)
                .containsExactly(movie, series);
    }

    private SearchContentCandidate candidate(
            ContentCoordinate coordinate, String title, String posterPath, double voteAverage) {
        return new SearchContentCandidate(
                coordinate, title, posterPath, LocalDate.of(2024, 1, 2), 100, voteAverage,
                List.of("Drama"), null);
    }

    private ContentCardDTO card(
            ContentCoordinate coordinate,
            String title,
            String posterPath,
            Integer numberOfSeasons,
            ContentCardViewerStateDTO viewerState,
            ContentPreviewStatus previewStatus) {
        return new ContentCardDTO(
                null,
                coordinate.type(),
                coordinate.tmdbId(),
                coordinate.seriesTmdbId(),
                coordinate.seasonNumber(),
                coordinate.episodeNumber(),
                title,
                posterPath,
                null,
                LocalDate.of(2024, 1, 2),
                2024,
                100,
                null,
                numberOfSeasons,
                null,
                List.of("Drama"),
                null,
                viewerState,
                previewStatus);
    }

    private ContentCardViewerStateDTO viewerState(WatchStatus watchStatus, boolean inWatchlist) {
        return new ContentCardViewerStateDTO(
                watchStatus, 8, LocalDate.of(2026, 1, 2), 1, false, 1,
                inWatchlist, false, null, null);
    }

    private ContentCoordinate movie(String tmdbId) {
        return new ContentCoordinate(ContentType.MOVIE, tmdbId, null, null, null);
    }

    private ContentCoordinate series(String tmdbId) {
        return new ContentCoordinate(ContentType.SERIES, tmdbId, null, null, null);
    }
}
