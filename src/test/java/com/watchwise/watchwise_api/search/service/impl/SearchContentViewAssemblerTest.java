package com.watchwise.watchwise_api.search.service.impl;

import com.watchwise.watchwise_api.common.tmdb.TmdbImageUrlBuilder;
import com.watchwise.watchwise_api.content.dto.ContentCardDTO;
import com.watchwise.watchwise_api.content.dto.ContentCardFieldSet;
import com.watchwise.watchwise_api.content.dto.ContentPreviewStatus;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.entity.MovieOrSeriesType;
import com.watchwise.watchwise_api.content.service.ContentCardContext;
import com.watchwise.watchwise_api.content.service.ContentCardSpec;
import com.watchwise.watchwise_api.content.service.ContentCoordinate;
import com.watchwise.watchwise_api.content.service.impl.ContentCardAssembler;
import com.watchwise.watchwise_api.search.dto.SearchContentViewDTO;
import com.watchwise.watchwise_api.user.entity.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchContentViewAssemblerTest {

    private static final UUID VIEWER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock
    private ContentCardAssembler contentCardAssembler;

    @Test
    void shouldAssembleOrderedCardsFromOneBatchedCardRequest() {
        User viewer = viewer();
        SearchContentCandidate movieCandidate = movieCandidate("1", "Night Shift", "/movie.jpg", 8.1d);
        SearchContentCandidate seriesCandidate = seriesCandidate("2", "Night Watch", "/series.jpg", 7.4d);
        ContentCoordinate movieCoordinate = movieCandidate.coordinate();
        ContentCoordinate seriesCoordinate = seriesCandidate.coordinate();
        when(contentCardAssembler.assemble(anyCollection(), any(ContentCardContext.class), anySet()))
                .thenReturn(Map.of(
                        movieCoordinate, card(movieCoordinate, "Night Shift", "/movie.jpg", ContentPreviewStatus.AVAILABLE),
                        seriesCoordinate, card(seriesCoordinate, "Night Watch", "/series.jpg", ContentPreviewStatus.AVAILABLE)));

        SearchContentViewAssembler assembler = new SearchContentViewAssembler(contentCardAssembler);

        List<SearchContentViewDTO> cards = assembler.assemble(viewer, List.of(movieCandidate, seriesCandidate));

        assertThat(cards).extracting(SearchContentViewDTO::posterUrl)
                .containsExactly(TmdbImageUrlBuilder.posterUrl("/movie.jpg"),
                        TmdbImageUrlBuilder.posterUrl("/series.jpg"));
        assertThat(cards).extracting(SearchContentViewDTO::voteAverage).containsExactly(8.1d, 7.4d);
        assertThat(cards).extracting(SearchContentViewDTO::type)
                .containsExactly(MovieOrSeriesType.MOVIE, MovieOrSeriesType.SERIES);

        ArgumentCaptor<List<ContentCardSpec>> specs = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<ContentCardContext> context = ArgumentCaptor.forClass(ContentCardContext.class);
        ArgumentCaptor<Set<ContentCardFieldSet>> fields = ArgumentCaptor.forClass(Set.class);
        verify(contentCardAssembler).assemble(specs.capture(), context.capture(), fields.capture());
        assertThat(specs.getValue()).extracting(ContentCardSpec::coordinate)
                .containsExactly(movieCoordinate, seriesCoordinate);
        assertThat(context.getValue()).isEqualTo(new ContentCardContext("pt-BR", "BR", null, VIEWER_ID));
        assertThat(fields.getValue()).containsExactlyInAnyOrder(
                ContentCardFieldSet.BASIC_METADATA,
                ContentCardFieldSet.VIEWER_STATE,
                ContentCardFieldSet.WATCHLIST_PROGRESS);
        verifyNoMoreInteractions(contentCardAssembler);
    }

    @Test
    void shouldRetainFallbackMetadataWhenCardEnrichmentIsUnavailable() {
        User viewer = viewer();
        SearchContentCandidate candidate = movieCandidate("3", "Night Shift", "/movie.jpg", 8.1d);
        when(contentCardAssembler.assemble(anyCollection(), any(ContentCardContext.class), anySet()))
                .thenReturn(Map.of());

        SearchContentViewDTO card = new SearchContentViewAssembler(contentCardAssembler)
                .assemble(viewer, List.of(candidate))
                .getFirst();

        assertThat(card.title()).isEqualTo("Night Shift");
        assertThat(card.type()).isEqualTo(MovieOrSeriesType.MOVIE);
        assertThat(card.posterUrl()).isEqualTo(TmdbImageUrlBuilder.posterUrl("/movie.jpg"));
        assertThat(card.releaseYear()).isEqualTo(2024);
        assertThat(card.voteAverage()).isEqualTo(8.1d);
        assertThat(card.previewStatus()).isEqualTo(ContentPreviewStatus.PARTIAL);
    }

    @Test
    void shouldRequestEachDistinctCoordinateOnceWhilePreservingCandidateOrder() {
        User viewer = viewer();
        SearchContentCandidate first = movieCandidate("1", "First", "/first.jpg", 8.1d);
        SearchContentCandidate duplicate = movieCandidate("1", "Duplicate fallback", "/duplicate.jpg", 7.1d);
        SearchContentCandidate third = seriesCandidate("2", "Third", "/third.jpg", 7.4d);
        when(contentCardAssembler.assemble(anyCollection(), any(ContentCardContext.class), anySet()))
                .thenReturn(Map.of());

        List<SearchContentViewDTO> cards = new SearchContentViewAssembler(contentCardAssembler)
                .assemble(viewer, List.of(first, duplicate, third));

        assertThat(cards).extracting(SearchContentViewDTO::title)
                .containsExactly("First", "Duplicate fallback", "Third");
        ArgumentCaptor<List<ContentCardSpec>> specs = ArgumentCaptor.forClass(List.class);
        verify(contentCardAssembler).assemble(specs.capture(), any(ContentCardContext.class), anySet());
        assertThat(specs.getValue()).extracting(ContentCardSpec::coordinate)
                .containsExactly(first.coordinate(), third.coordinate());
    }

    private User viewer() {
        return User.builder()
                .id(VIEWER_ID)
                .preferredLanguage("pt-BR")
                .preferredRegion("BR")
                .build();
    }

    private SearchContentCandidate movieCandidate(String id, String title, String posterPath, Double voteAverage) {
        return new SearchContentCandidate(
                new ContentCoordinate(ContentType.MOVIE, id, null, null, null),
                title, posterPath, LocalDate.of(2024, 1, 2), null, voteAverage, null);
    }

    private SearchContentCandidate seriesCandidate(String id, String title, String posterPath, Double voteAverage) {
        return new SearchContentCandidate(
                new ContentCoordinate(ContentType.SERIES, id, null, null, null),
                title, posterPath, LocalDate.of(2023, 3, 4), null, voteAverage, null);
    }

    private ContentCardDTO card(ContentCoordinate coordinate, String title, String posterPath,
                                ContentPreviewStatus previewStatus) {
        return new ContentCardDTO(
                null, coordinate.type(), coordinate.tmdbId(), coordinate.seriesTmdbId(),
                coordinate.seasonNumber(), coordinate.episodeNumber(), title, posterPath, null,
                LocalDate.of(2024, 1, 2), 2024, 120, null, null, null, null, null, null,
                previewStatus);
    }
}
