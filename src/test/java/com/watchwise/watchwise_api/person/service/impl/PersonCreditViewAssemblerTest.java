package com.watchwise.watchwise_api.person.service.impl;

import com.watchwise.watchwise_api.content.dto.ContentCardStatsDTO;
import com.watchwise.watchwise_api.content.dto.ContentCardViewerStateDTO;
import com.watchwise.watchwise_api.content.dto.ContentListMembershipDTO;
import com.watchwise.watchwise_api.content.dto.ContentStatsResponseDTO;
import com.watchwise.watchwise_api.content.dto.ContentViewerStateDTO;
import com.watchwise.watchwise_api.content.dto.WatchStatus;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.service.ContentCoordinate;
import com.watchwise.watchwise_api.content.service.ContentStatsService;
import com.watchwise.watchwise_api.content.service.ContentViewerStateService;
import com.watchwise.watchwise_api.person.entity.PersonParticipation;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PersonCreditViewAssemblerTest {

    @Test
    void shouldEnrichOnlyExistingContentAndRetainAggregateFieldsWithoutPersistingAggregateOnlyCredit() {
        UUID contentId = UUID.randomUUID();
        UUID viewerId = UUID.randomUUID();
        var resolver = mock(PersonCreditViewerStateResolver.class);
        var statsService = mock(ContentStatsService.class);
        var credit = new PersonFilmographyCredit(com.watchwise.watchwise_api.content.entity.MovieOrSeriesType.MOVIE,
                "7", "Aggregate movie", "/poster.jpg", "/backdrop.jpg", LocalDate.of(2020, 1, 2),
                14.0, null, List.of("Role"), List.of(), PersonParticipation.CAST);
        var aggregateOnly = new PersonFilmographyCredit(com.watchwise.watchwise_api.content.entity.MovieOrSeriesType.MOVIE,
                "8", "No local row", null, null, null, null, null, List.of(), List.of(), PersonParticipation.CREW);
        ContentCoordinate coordinate = new ContentCoordinate(ContentType.MOVIE, "7", null, null, null);
        ContentCoordinate absentCoordinate = new ContentCoordinate(ContentType.MOVIE, "8", null, null, null);
        ContentViewerStateDTO state = new ContentViewerStateDTO(WatchStatus.WATCHED, 9, null,
                LocalDate.of(2026, 9, 20), 1, false, 1, null, true, null, false, null,
                List.of(new ContentListMembershipDTO(UUID.randomUUID(), "Favorites", null)), null, null);
        var resolution = new ContentViewerStateService.Resolution(
                Map.of(coordinate, state, absentCoordinate, emptyState()), Map.of(coordinate, contentId));
        when(resolver.resolve(viewerId, List.of(credit, aggregateOnly))).thenReturn(resolution);
        when(statsService.getStatsBatch(List.of(contentId))).thenReturn(List.of(
                new ContentStatsResponseDTO(contentId, 8.5d, 12, 3, 4)));

        var views = new PersonCreditViewAssembler(resolver, statsService).assemble(viewerId, List.of(credit, aggregateOnly));

        assertThat(views).hasSize(2);
        assertThat(views.getFirst()).extracting("title", "posterUrl", "releaseDate", "stats", "viewerState", "isInAnyList")
                .containsExactly("Aggregate movie", "https://image.tmdb.org/t/p/w500/poster.jpg", LocalDate.of(2020, 1, 2),
                        new ContentCardStatsDTO(8.5d, 12L, 3L, 4L),
                        new ContentCardViewerStateDTO(WatchStatus.WATCHED, 9, LocalDate.of(2026, 9, 20),
                                1, false, 1, true, false, null, null), true);
        assertThat(views.get(1).stats()).isEqualTo(new ContentCardStatsDTO(null, 0L, 0L, 0L));
        assertThat(views.get(1).previewStatus()).isEqualTo(com.watchwise.watchwise_api.content.dto.ContentPreviewStatus.PARTIAL);
        org.mockito.Mockito.verify(statsService).getStatsBatch(List.of(contentId));
    }

    @Test
    void shouldRequestStatsForExistingContentInBatchesOfAtMostOneHundred() {
        UUID viewerId = UUID.randomUUID();
        var resolver = mock(PersonCreditViewerStateResolver.class);
        var statsService = mock(ContentStatsService.class);
        var credits = java.util.stream.IntStream.range(0, 101).mapToObj(index ->
                new PersonFilmographyCredit(com.watchwise.watchwise_api.content.entity.MovieOrSeriesType.MOVIE,
                        Integer.toString(index + 1), "Movie " + index, "/poster.jpg", null,
                        LocalDate.of(2020, 1, 1), 1.0, null, List.of(), List.of(), PersonParticipation.CAST))
                .toList();
        var ids = java.util.stream.IntStream.range(0, 101).mapToObj(index -> UUID.randomUUID()).toList();
        var states = new java.util.LinkedHashMap<ContentCoordinate, ContentViewerStateDTO>();
        var existingIds = new java.util.LinkedHashMap<ContentCoordinate, UUID>();
        for (int index = 0; index < credits.size(); index++) {
            ContentCoordinate coordinate = new ContentCoordinate(ContentType.MOVIE, credits.get(index).tmdbId(), null, null, null);
            states.put(coordinate, emptyState());
            existingIds.put(coordinate, ids.get(index));
        }
        when(resolver.resolve(viewerId, credits)).thenReturn(new ContentViewerStateService.Resolution(states, existingIds));
        when(statsService.getStatsBatch(org.mockito.ArgumentMatchers.anyList())).thenReturn(List.of());

        new PersonCreditViewAssembler(resolver, statsService).assemble(viewerId, credits);

        verify(statsService).getStatsBatch(org.mockito.ArgumentMatchers.argThat(batch -> batch.size() == 100));
        verify(statsService).getStatsBatch(org.mockito.ArgumentMatchers.argThat(batch -> batch.size() == 1));
    }

    private static ContentViewerStateDTO emptyState() {
        return new ContentViewerStateDTO(WatchStatus.UNWATCHED, null, null, null, null, null, 0,
                null, false, null, false, null, List.of(), null, null);
    }
}
