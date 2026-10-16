package com.watchwise.watchwise_api.person.service.impl;

import com.watchwise.watchwise_api.content.dto.ContentViewerStateDTO;
import com.watchwise.watchwise_api.content.dto.WatchStatus;
import com.watchwise.watchwise_api.content.service.ContentCoordinate;
import com.watchwise.watchwise_api.content.service.ContentViewerStateService;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.seriesprogress.repository.SeriesProgressMetadataRepository;
import com.watchwise.watchwise_api.person.entity.PersonParticipation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PersonCreditViewerStateResolverTest {

    @Test
    void shouldKeepSeriesUnknownWhenEpisodeActivityHasNoReleasedMetadata() {
        UUID viewerId = UUID.randomUUID();
        var viewerStateService = mock(ContentViewerStateService.class);
        var diaryRepository = mock(DiaryEntryRepository.class);
        var metadataRepository = mock(SeriesProgressMetadataRepository.class);
        var credit = series("42");
        ContentCoordinate coordinate = seriesCoordinate("42");
        when(viewerStateService.resolve(viewerId, List.of(coordinate), Map.of()))
                .thenReturn(new ContentViewerStateService.Resolution(Map.of(coordinate, emptyState()), Map.of()));
        when(diaryRepository.findWatchedMediaForPersonCredits(viewerId, List.of("42")))
                .thenReturn(List.of(media(ContentType.EPISODE, "42", "42")));
        when(diaryRepository.findEpisodeSeriesCountsByUserIdAndSeriesTmdbIdIn(viewerId, List.of("42")))
                .thenReturn(List.of(seriesCount("42", 3L)));
        when(metadataRepository.findAllBySeriesTmdbIdIn(List.of("42"))).thenReturn(List.of());

        var result = new PersonCreditViewerStateResolver(viewerStateService, diaryRepository, metadataRepository)
                .resolve(viewerId, List.of(credit));

        var state = result.statesByCoordinate().get(coordinate);
        assertThat(state.watchStatus()).isEqualTo(WatchStatus.UNKNOWN);
        assertThat(state.watchedEpisodeCount()).isNull();
        assertThat(state.releasedEpisodeCount()).isNull();
        verify(viewerStateService).resolve(viewerId, List.of(coordinate), Map.of());
    }

    @Test
    void shouldPreferDirectSeriesDiaryActivityToIncompleteEpisodeProgress() {
        UUID viewerId = UUID.randomUUID();
        var viewerStateService = mock(ContentViewerStateService.class);
        var diaryRepository = mock(DiaryEntryRepository.class);
        var metadataRepository = mock(SeriesProgressMetadataRepository.class);
        var credit = series("42");
        ContentCoordinate coordinate = seriesCoordinate("42");
        when(viewerStateService.resolve(viewerId, List.of(coordinate), Map.of()))
                .thenReturn(new ContentViewerStateService.Resolution(Map.of(coordinate, emptyState()), Map.of()));
        when(diaryRepository.findWatchedMediaForPersonCredits(viewerId, List.of("42")))
                .thenReturn(List.of(media(ContentType.SERIES, "42", "42"), media(ContentType.EPISODE, null, "42")));
        when(diaryRepository.findEpisodeSeriesCountsByUserIdAndSeriesTmdbIdIn(viewerId, List.of("42")))
                .thenReturn(List.of(seriesCount("42", 1L)));
        when(metadataRepository.findAllBySeriesTmdbIdIn(List.of("42"))).thenReturn(List.of());

        var result = new PersonCreditViewerStateResolver(viewerStateService, diaryRepository, metadataRepository)
                .resolve(viewerId, List.of(credit));

        assertThat(result.statesByCoordinate().get(coordinate).watchStatus()).isEqualTo(WatchStatus.WATCHED);
    }

    @Test
    void shouldResolveEpisodeProgressAgainstRegularReleasedEpisodeCount() {
        UUID viewerId = UUID.randomUUID();
        var viewerStateService = mock(ContentViewerStateService.class);
        var diaryRepository = mock(DiaryEntryRepository.class);
        var metadataRepository = mock(SeriesProgressMetadataRepository.class);
        var credit = series("42");
        ContentCoordinate coordinate = seriesCoordinate("42");
        when(viewerStateService.resolve(viewerId, List.of(coordinate), Map.of()))
                .thenReturn(new ContentViewerStateService.Resolution(Map.of(coordinate, emptyState()), Map.of()));
        when(diaryRepository.findWatchedMediaForPersonCredits(viewerId, List.of("42")))
                .thenReturn(List.of(media(ContentType.EPISODE, null, "42")));
        when(diaryRepository.findEpisodeSeriesCountsByUserIdAndSeriesTmdbIdIn(viewerId, List.of("42")))
                .thenReturn(List.of(seriesCount("42", 2L)));
        when(metadataRepository.findAllBySeriesTmdbIdIn(List.of("42"))).thenReturn(List.of(metadata("42", 3)));

        var result = new PersonCreditViewerStateResolver(viewerStateService, diaryRepository, metadataRepository)
                .resolve(viewerId, List.of(credit));

        assertThat(result.statesByCoordinate().get(coordinate).watchStatus()).isEqualTo(WatchStatus.PARTIALLY_WATCHED);
        assertThat(result.statesByCoordinate().get(coordinate).watchedEpisodeCount()).isEqualTo(2);
        assertThat(result.statesByCoordinate().get(coordinate).releasedEpisodeCount()).isEqualTo(3);
    }

    @Test
    void shouldMarkSeriesWatchedWhenEpisodeCountReachesReleasedCount() {
        UUID viewerId = UUID.randomUUID();
        var viewerStateService = mock(ContentViewerStateService.class);
        var diaryRepository = mock(DiaryEntryRepository.class);
        var metadataRepository = mock(SeriesProgressMetadataRepository.class);
        var credit = series("42");
        ContentCoordinate coordinate = seriesCoordinate("42");
        when(viewerStateService.resolve(viewerId, List.of(coordinate), Map.of()))
                .thenReturn(new ContentViewerStateService.Resolution(Map.of(coordinate, emptyState()), Map.of()));
        when(diaryRepository.findWatchedMediaForPersonCredits(viewerId, List.of("42")))
                .thenReturn(List.of(media(ContentType.EPISODE, null, "42")));
        when(diaryRepository.findEpisodeSeriesCountsByUserIdAndSeriesTmdbIdIn(viewerId, List.of("42")))
                .thenReturn(List.of(seriesCount("42", 3L)));
        when(metadataRepository.findAllBySeriesTmdbIdIn(List.of("42"))).thenReturn(List.of(metadata("42", 3)));

        var result = new PersonCreditViewerStateResolver(viewerStateService, diaryRepository, metadataRepository)
                .resolve(viewerId, List.of(credit));

        assertThat(result.statesByCoordinate().get(coordinate).watchStatus()).isEqualTo(WatchStatus.WATCHED);
        assertThat(result.statesByCoordinate().get(coordinate).watchedEpisodeCount()).isEqualTo(3);
        assertThat(result.statesByCoordinate().get(coordinate).releasedEpisodeCount()).isEqualTo(3);
    }

    private static PersonFilmographyCredit series(String id) {
        return new PersonFilmographyCredit(com.watchwise.watchwise_api.content.entity.MovieOrSeriesType.SERIES,
                id, "Series " + id, null, null, null, null, null, List.of(), List.of(), PersonParticipation.CAST);
    }

    private static ContentCoordinate seriesCoordinate(String id) {
        return new ContentCoordinate(ContentType.SERIES, id, null, null, null);
    }

    private static ContentViewerStateDTO emptyState() {
        return new ContentViewerStateDTO(WatchStatus.UNWATCHED, null, null, null, null, null, 0,
                null, false, null, false, null, List.of(), null, null);
    }

    private static DiaryEntryRepository.PersonCreditMedia media(ContentType type, String tmdbId, String seriesId) {
        return new DiaryEntryRepository.PersonCreditMedia() {
            @Override public ContentType getType() { return type; }
            @Override public String getTmdbId() { return tmdbId; }
            @Override public String getSeriesTmdbId() { return seriesId; }
            @Override public ContentType getSourceType() { return type; }
        };
    }

    private static DiaryEntryRepository.SeriesEpisodeCount seriesCount(String seriesId, long count) {
        return new DiaryEntryRepository.SeriesEpisodeCount() {
            @Override public String getSeriesTmdbId() { return seriesId; }
            @Override public Long getWatchedEpisodeCount() { return count; }
        };
    }

    private static SeriesProgressMetadataRepository.SeriesProgressMetadataProjection metadata(String seriesId, int count) {
        return new SeriesProgressMetadataRepository.SeriesProgressMetadataProjection() {
            @Override public String getSeriesTmdbId() { return seriesId; }
            @Override public Integer getRegularReleasedEpisodeCount() { return count; }
            @Override public Integer getTotalKnownRuntime() { return null; }
            @Override public Integer getKnownRuntimeEpisodeCount() { return 0; }
            @Override public LocalDate getLastReleasedEpisodeDate() { return null; }
            @Override public java.time.LocalDateTime getRefreshedAt() { return null; }
            @Override public java.time.LocalDateTime getRuntimeVerifiedAt() { return null; }
        };
    }
}
