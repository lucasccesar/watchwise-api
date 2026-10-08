package com.watchwise.watchwise_api.content.service.impl;

import com.watchwise.watchwise_api.content.dto.ContentViewerStateDTO;
import com.watchwise.watchwise_api.content.dto.ContentListMembershipDTO;
import com.watchwise.watchwise_api.content.dto.WatchStatus;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.service.ContentCoordinate;
import com.watchwise.watchwise_api.content.service.ContentSchedule;
import com.watchwise.watchwise_api.content.service.ContentScheduleKey;
import com.watchwise.watchwise_api.content.service.ContentStateResolver;
import com.watchwise.watchwise_api.content.service.ContentViewerStateService;
import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepositoryCustom;
import com.watchwise.watchwise_api.diaryentry.repository.WatchedEpisodeCoordinate;
import com.watchwise.watchwise_api.dropped.entity.DroppedEntry;
import com.watchwise.watchwise_api.dropped.repository.DroppedEntryRepository;
import com.watchwise.watchwise_api.userlist.entity.UserList;
import com.watchwise.watchwise_api.userlist.entity.UserListItem;
import com.watchwise.watchwise_api.userlist.entity.UserListVisibility;
import com.watchwise.watchwise_api.userlist.repository.UserListItemRepository;
import com.watchwise.watchwise_api.watchlist.entity.WatchlistEntry;
import com.watchwise.watchwise_api.watchlist.repository.WatchlistEntryRepository;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContentViewerStateServiceImplTest {

    private static final UUID VIEWER_ID = UUID.randomUUID();
    private static final String SERIES_ID = "1396";
    private static final Clock CLOCK = Clock.fixed(LocalDateTime.of(2026, 10, 7, 12, 0)
            .toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

    @Mock
    private DiaryEntryRepository diaryEntryRepository;

    @Mock
    private WatchlistEntryRepository watchlistEntryRepository;

    @Mock
    private DroppedEntryRepository droppedEntryRepository;

    @Mock
    private UserListItemRepository userListItemRepository;

    @Mock
    private ContentRepository contentRepository;

    @Mock
    private ContentStateResolver contentStateResolver;

    private ContentViewerStateServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ContentViewerStateServiceImpl(
                diaryEntryRepository,
                watchlistEntryRepository,
                droppedEntryRepository,
                userListItemRepository,
                contentRepository,
                contentStateResolver,
                CLOCK);
    }

    @Test
    @DisplayName("[resolve] Should Select Latest Diary By Watch Number, Creation Time And ID - While Counting Every Play")
    void shouldSelectLatestDiaryByWatchNumberCreationTimeAndIdWhileCountingEveryPlay() {
        Content movie = content(ContentType.MOVIE, "550", null, null, null);
        ContentCoordinate coordinate = coordinate(movie);
        DiaryEntry olderWatch = diary(movie, UUID.fromString("00000000-0000-0000-0000-000000000001"),
                2, LocalDateTime.of(2026, 10, 1, 12, 0), 6, "Older review", LocalDate.of(2026, 10, 1));
        DiaryEntry latestByCreatedAt = diary(movie, UUID.fromString("00000000-0000-0000-0000-000000000002"),
                2, LocalDateTime.of(2026, 10, 2, 12, 0), 8, "Latest review", LocalDate.of(2026, 10, 2));
        DiaryEntry rewatch = diary(movie, UUID.fromString("00000000-0000-0000-0000-000000000003"),
                1, LocalDateTime.of(2026, 10, 3, 12, 0), 10, "Rewatch", LocalDate.of(2026, 10, 3));

        when(contentRepository.findAllByCoordinates(eq(List.of(coordinate))))
                .thenReturn(List.of(movie));
        when(diaryEntryRepository.findViewerStateEntriesByContentIdIn(
                eq(VIEWER_ID), eq(Set.of(movie.getId()))))
                .thenReturn(List.of(olderWatch, latestByCreatedAt, rewatch));
        when(watchlistEntryRepository.findByUserIdAndContentIdInWithContent(eq(VIEWER_ID), eq(Set.of(movie.getId()))))
                .thenReturn(List.of());
        when(droppedEntryRepository.findByUserIdAndContentIdInWithContent(eq(VIEWER_ID), eq(Set.of(movie.getId()))))
                .thenReturn(List.of());
        when(userListItemRepository.findViewerMembershipsByContentIdIn(eq(VIEWER_ID), eq(Set.of(movie.getId()))))
                .thenReturn(List.of());

        ContentViewerStateServiceImpl.Resolution result = service.resolve(
                VIEWER_ID, List.of(coordinate), Map.of(coordinate, movieSchedule()));

        ContentViewerStateDTO state = result.statesByCoordinate().get(coordinate);
        assertThat(state.watchStatus()).isEqualTo(WatchStatus.WATCHED);
        assertThat(state.myRating()).isEqualTo(8);
        assertThat(state.myReview()).isEqualTo("Latest review");
        assertThat(state.lastWatchedDate()).isEqualTo(LocalDate.of(2026, 10, 2));
        assertThat(state.lastWatchNumber()).isEqualTo(2);
        assertThat(state.playsCount()).isEqualTo(3);
        assertThat(state.latestDiaryEntryId()).isEqualTo(latestByCreatedAt.getId());

        verify(contentRepository).findAllByCoordinates(eq(List.of(coordinate)));
        verify(diaryEntryRepository).findViewerStateEntriesByContentIdIn(
                eq(VIEWER_ID), eq(Set.of(movie.getId())));
    }

    @Test
    @DisplayName("[resolve] Should Reuse Aggregate Progress Resolver And Batch Viewer-Owned Sources")
    void shouldReuseAggregateProgressResolverAndBatchViewerOwnedSources() {
        Content series = content(ContentType.SERIES, SERIES_ID, null, null, null);
        Content season = content(ContentType.SEASON, null, SERIES_ID, 1, null);
        ContentCoordinate seriesCoordinate = coordinate(series);
        ContentCoordinate seasonCoordinate = coordinate(season);
        ContentViewerStateDTO seriesState = state(WatchStatus.PARTIALLY_WATCHED, 2, 5);
        ContentViewerStateDTO seasonState = state(WatchStatus.WATCHED, 2, 2);

        when(contentRepository.findAllByCoordinates(eq(List.of(seriesCoordinate, seasonCoordinate))))
                .thenReturn(List.of(series, season));
        when(diaryEntryRepository.findViewerStateEntriesByContentIdIn(VIEWER_ID, Set.of(series.getId(), season.getId())))
                .thenReturn(List.of());
        when(diaryEntryRepository.findWatchedEpisodeCoordinatesByUserIdAndSeriesIdsOrSeriesSeasonPairs(
                VIEWER_ID,
                Set.of(SERIES_ID),
                Set.of(new DiaryEntryRepositoryCustom.SeriesSeasonPair(SERIES_ID, 1))))
                .thenReturn(List.of());
        Set<UUID> contentIds = Set.of(series.getId(), season.getId());
        when(watchlistEntryRepository.findByUserIdAndContentIdInWithContent(VIEWER_ID, contentIds))
                .thenReturn(List.of());
        when(droppedEntryRepository.findByUserIdAndContentIdInWithContent(VIEWER_ID, contentIds))
                .thenReturn(List.of());
        when(userListItemRepository.findViewerMembershipsByContentIdIn(VIEWER_ID, contentIds))
                .thenReturn(List.of());
        when(contentStateResolver.resolveWatchProgress(
                any(), any(), eq(Set.of()), eq(Set.of()), eq(CLOCK)))
                .thenReturn(new ContentStateResolver.WatchProgress(WatchStatus.PARTIALLY_WATCHED, 2, 5))
                .thenReturn(new ContentStateResolver.WatchProgress(WatchStatus.WATCHED, 2, 2));

        Map<ContentCoordinate, ContentViewerStateDTO> result = service
                .resolve(VIEWER_ID, List.of(seriesCoordinate, seasonCoordinate),
                        Map.of(seriesCoordinate, seriesSchedule(), seasonCoordinate, seasonSchedule()))
                .statesByCoordinate();

        assertThat(result).containsEntry(seriesCoordinate, seriesState).containsEntry(seasonCoordinate, seasonState);
        verify(contentRepository).findAllByCoordinates(eq(List.of(seriesCoordinate, seasonCoordinate)));
        verify(diaryEntryRepository).findWatchedEpisodeCoordinatesByUserIdAndSeriesIdsOrSeriesSeasonPairs(
                VIEWER_ID,
                Set.of(SERIES_ID),
                Set.of(new DiaryEntryRepositoryCustom.SeriesSeasonPair(SERIES_ID, 1)));
        verify(diaryEntryRepository).findViewerStateEntriesByContentIdIn(
                VIEWER_ID, contentIds);
        verify(watchlistEntryRepository).findByUserIdAndContentIdInWithContent(VIEWER_ID, contentIds);
        verify(droppedEntryRepository).findByUserIdAndContentIdInWithContent(VIEWER_ID, contentIds);
        verify(userListItemRepository).findViewerMembershipsByContentIdIn(VIEWER_ID, contentIds);
    }

    @Test
    @DisplayName("[resolve] Should Query Exact Episode Content And Diary IDs - Without Loading Other Series Rows")
    void shouldQueryExactEpisodeContentAndDiaryIdsWithoutLoadingOtherSeriesRows() {
        Content episode = content(ContentType.EPISODE, null, SERIES_ID, 1, 1);
        ContentCoordinate coordinate = coordinate(episode);
        DiaryEntry diaryEntry = diary(episode, UUID.randomUUID(), 1,
                LocalDateTime.of(2026, 10, 1, 12, 0), 8, "Episode review", LocalDate.of(2026, 10, 1));
        Set<UUID> contentIds = Set.of(episode.getId());

        when(contentRepository.findAllByCoordinates(eq(List.of(coordinate))))
                .thenReturn(List.of(episode));
        when(diaryEntryRepository.findViewerStateEntriesByContentIdIn(VIEWER_ID, contentIds))
                .thenReturn(List.of(diaryEntry));
        when(watchlistEntryRepository.findByUserIdAndContentIdInWithContent(VIEWER_ID, contentIds))
                .thenReturn(List.of());
        when(droppedEntryRepository.findByUserIdAndContentIdInWithContent(VIEWER_ID, contentIds))
                .thenReturn(List.of());
        when(userListItemRepository.findViewerMembershipsByContentIdIn(VIEWER_ID, contentIds))
                .thenReturn(List.of());

        ContentViewerStateDTO state = service.resolve(VIEWER_ID, List.of(coordinate), Map.of())
                .statesByCoordinate().get(coordinate);

        assertThat(state.watchStatus()).isEqualTo(WatchStatus.WATCHED);
        assertThat(state.playsCount()).isOne();
        verify(contentRepository).findAllByCoordinates(eq(List.of(coordinate)));
        verify(diaryEntryRepository).findViewerStateEntriesByContentIdIn(VIEWER_ID, contentIds);
        verify(diaryEntryRepository, never())
                .findWatchedEpisodeCoordinatesByUserIdAndSeriesIdsOrSeriesSeasonPairs(any(), any(), any());
    }

    @Test
    @DisplayName("[resolve] Should Query Only Requested Season Episode Coordinates - For Aggregate Progress")
    void shouldQueryOnlyRequestedSeasonEpisodeCoordinatesForAggregateProgress() {
        Content season = content(ContentType.SEASON, null, SERIES_ID, 1, null);
        ContentCoordinate coordinate = coordinate(season);
        Set<UUID> contentIds = Set.of(season.getId());
        WatchedEpisodeCoordinateRow watchedEpisode = new WatchedEpisodeCoordinateRow(SERIES_ID, 1, 1);
        Set<WatchedEpisodeCoordinate> watchedCoordinates = Set.of(
                new WatchedEpisodeCoordinate(SERIES_ID, 1, 1));

        when(contentRepository.findAllByCoordinates(eq(List.of(coordinate))))
                .thenReturn(List.of(season));
        when(diaryEntryRepository.findViewerStateEntriesByContentIdIn(VIEWER_ID, contentIds))
                .thenReturn(List.of());
        when(diaryEntryRepository.findWatchedEpisodeCoordinatesByUserIdAndSeriesIdsOrSeriesSeasonPairs(
                VIEWER_ID,
                Set.of(),
                Set.of(new DiaryEntryRepositoryCustom.SeriesSeasonPair(SERIES_ID, 1))))
                .thenReturn(List.of(watchedEpisode));
        when(watchlistEntryRepository.findByUserIdAndContentIdInWithContent(VIEWER_ID, contentIds))
                .thenReturn(List.of());
        when(droppedEntryRepository.findByUserIdAndContentIdInWithContent(VIEWER_ID, contentIds))
                .thenReturn(List.of());
        when(userListItemRepository.findViewerMembershipsByContentIdIn(VIEWER_ID, contentIds))
                .thenReturn(List.of());
        when(contentStateResolver.resolveWatchProgress(
                eq(season), any(), eq(Set.of()), eq(watchedCoordinates), eq(CLOCK)))
                .thenReturn(new ContentStateResolver.WatchProgress(WatchStatus.WATCHED, 1, 1));

        ContentViewerStateDTO state = service.resolve(
                VIEWER_ID, List.of(coordinate), Map.of(coordinate, seasonSchedule()))
                .statesByCoordinate().get(coordinate);

        assertThat(state.watchStatus()).isEqualTo(WatchStatus.WATCHED);
        assertThat(state.watchedEpisodeCount()).isEqualTo(1);
        assertThat(state.releasedEpisodeCount()).isEqualTo(1);
        verify(diaryEntryRepository).findViewerStateEntriesByContentIdIn(VIEWER_ID, contentIds);
        verify(diaryEntryRepository).findWatchedEpisodeCoordinatesByUserIdAndSeriesIdsOrSeriesSeasonPairs(
                VIEWER_ID,
                Set.of(),
                Set.of(new DiaryEntryRepositoryCustom.SeriesSeasonPair(SERIES_ID, 1)));
        verify(diaryEntryRepository, never())
                .findWatchedEpisodeCoordinatesByUserIdAndSeriesSeasonPairs(any(), any());
    }

    @Test
    @DisplayName("[resolve] Should Return Viewer-Owned Watchlist Drop And List Membership - When Other Viewer Rows Are Absent From Batch Results")
    void shouldReturnViewerOwnedWatchlistDropAndListMembership() {
        Content movie = movieContent();
        ContentCoordinate coordinate = coordinate(movie);
        UUID watchlistEntryId = UUID.fromString("00000000-0000-0000-0000-000000000010");
        UUID droppedEntryId = UUID.fromString("00000000-0000-0000-0000-000000000011");
        UUID listId = UUID.fromString("00000000-0000-0000-0000-000000000012");
        WatchlistEntry watchlistEntry = WatchlistEntry.builder()
                .id(watchlistEntryId).content(movie).type(ContentType.MOVIE).build();
        DroppedEntry droppedEntry = DroppedEntry.builder()
                .id(droppedEntryId).content(movie).type(ContentType.MOVIE).build();
        UserList list = UserList.builder()
                .id(listId).name("Private queue").visibility(UserListVisibility.PRIVATE).build();
        UserListItem listItem = UserListItem.builder()
                .id(UUID.randomUUID()).userList(list).content(movie).position(1).build();

        when(contentRepository.findAllByCoordinates(eq(List.of(coordinate))))
                .thenReturn(List.of(movie));
        when(diaryEntryRepository.findViewerStateEntriesByContentIdIn(VIEWER_ID, Set.of(movie.getId())))
                .thenReturn(List.of());
        when(watchlistEntryRepository.findByUserIdAndContentIdInWithContent(VIEWER_ID, Set.of(movie.getId())))
                .thenReturn(List.of(watchlistEntry));
        when(droppedEntryRepository.findByUserIdAndContentIdInWithContent(VIEWER_ID, Set.of(movie.getId())))
                .thenReturn(List.of(droppedEntry));
        when(userListItemRepository.findViewerMembershipsByContentIdIn(VIEWER_ID, Set.of(movie.getId())))
                .thenReturn(List.of(listItem));

        ContentViewerStateDTO state = service.resolve(
                VIEWER_ID, List.of(coordinate), Map.of(coordinate, movieSchedule()))
                .statesByCoordinate().get(coordinate);

        assertThat(state.inWatchlist()).isTrue();
        assertThat(state.watchlistEntryId()).isEqualTo(watchlistEntryId);
        assertThat(state.dropped()).isTrue();
        assertThat(state.droppedEntryId()).isEqualTo(droppedEntryId);
        assertThat(state.lists()).containsExactly(
                new ContentListMembershipDTO(listId, "Private queue", UserListVisibility.PRIVATE));
        verify(watchlistEntryRepository).findByUserIdAndContentIdInWithContent(VIEWER_ID, Set.of(movie.getId()));
        verify(droppedEntryRepository).findByUserIdAndContentIdInWithContent(VIEWER_ID, Set.of(movie.getId()));
        verify(userListItemRepository).findViewerMembershipsByContentIdIn(VIEWER_ID, Set.of(movie.getId()));
    }

    @Test
    @DisplayName("[resolve] Should Ignore Watchlist Entry With Mismatched Type - When Content ID Matches")
    void shouldIgnoreWatchlistEntryWithMismatchedTypeWhenContentIdMatches() {
        Content movie = movieContent();
        ContentCoordinate coordinate = coordinate(movie);
        WatchlistEntry mismatchedEntry = WatchlistEntry.builder()
                .id(UUID.randomUUID()).content(movie).type(ContentType.SERIES).build();

        stubEmptySources(movie);
        when(watchlistEntryRepository.findByUserIdAndContentIdInWithContent(VIEWER_ID, Set.of(movie.getId())))
                .thenReturn(List.of(mismatchedEntry));

        ContentViewerStateDTO state = service.resolve(
                VIEWER_ID, List.of(coordinate), Map.of(coordinate, movieSchedule()))
                .statesByCoordinate().get(coordinate);

        assertThat(state.inWatchlist()).isFalse();
        assertThat(state.watchlistEntryId()).isNull();
    }

    @Test
    @DisplayName("[resolve] Should Ignore Dropped Entry With Mismatched Type - When Content ID Matches")
    void shouldIgnoreDroppedEntryWithMismatchedTypeWhenContentIdMatches() {
        Content movie = movieContent();
        ContentCoordinate coordinate = coordinate(movie);
        DroppedEntry mismatchedEntry = DroppedEntry.builder()
                .id(UUID.randomUUID()).content(movie).type(ContentType.SERIES).build();

        stubEmptySources(movie);
        when(droppedEntryRepository.findByUserIdAndContentIdInWithContent(VIEWER_ID, Set.of(movie.getId())))
                .thenReturn(List.of(mismatchedEntry));

        ContentViewerStateDTO state = service.resolve(
                VIEWER_ID, List.of(coordinate), Map.of(coordinate, movieSchedule()))
                .statesByCoordinate().get(coordinate);

        assertThat(state.dropped()).isFalse();
        assertThat(state.droppedEntryId()).isNull();
    }

    @Test
    @DisplayName("[resolve] Should Return Empty Read-Only State - When Coordinate Has No Local Content Reference")
    void shouldReturnEmptyReadOnlyStateWhenCoordinateHasNoLocalContentReference() {
        ContentCoordinate coordinate = new ContentCoordinate(ContentType.MOVIE, "550", null, null, null);
        when(contentRepository.findAllByCoordinates(eq(List.of(coordinate))))
                .thenReturn(List.of());

        ContentViewerStateService.Resolution result = service.resolve(
                VIEWER_ID, List.of(coordinate), Map.of(coordinate, movieSchedule()));

        assertThat(result.existingContentIdsByCoordinate()).isEmpty();
        assertThat(result.statesByCoordinate()).containsKey(coordinate);
        assertThat(result.statesByCoordinate().get(coordinate))
                .extracting(ContentViewerStateDTO::watchStatus, ContentViewerStateDTO::playsCount,
                        ContentViewerStateDTO::inWatchlist, ContentViewerStateDTO::dropped)
                .containsExactly(WatchStatus.UNWATCHED, 0, false, false);
        verifyNoInteractions(diaryEntryRepository, watchlistEntryRepository, droppedEntryRepository,
                userListItemRepository, contentStateResolver);
    }

    @Test
    @DisplayName("[resolve] Should Resolve Existing References Without Personal Reads - When Viewer Is Absent")
    void shouldResolveExistingReferencesWithoutPersonalReadsWhenViewerIsAbsent() {
        Content movie = movieContent();
        ContentCoordinate coordinate = coordinate(movie);
        when(contentRepository.findAllByCoordinates(eq(List.of(coordinate)))).thenReturn(List.of(movie));

        ContentViewerStateService.Resolution result = service.resolve(null, List.of(coordinate), Map.of());

        assertThat(result.existingContentIdsByCoordinate()).containsEntry(coordinate, movie.getId());
        assertThat(result.statesByCoordinate()).containsEntry(coordinate, state(WatchStatus.UNWATCHED, null, null));
        verify(contentRepository).findAllByCoordinates(eq(List.of(coordinate)));
        verifyNoInteractions(diaryEntryRepository, watchlistEntryRepository, droppedEntryRepository,
                userListItemRepository, contentStateResolver);
    }

    private ContentViewerStateDTO state(WatchStatus status, Integer watched, Integer released) {
        return new ContentViewerStateDTO(status, null, null, null, null, null, 0, null,
                false, null, false, null, List.of(), watched, released);
    }

    private void stubEmptySources(Content content) {
        ContentCoordinate coordinate = coordinate(content);
        when(contentRepository.findAllByCoordinates(eq(List.of(coordinate))))
                .thenReturn(List.of(content));
        when(diaryEntryRepository.findViewerStateEntriesByContentIdIn(VIEWER_ID, Set.of(content.getId())))
                .thenReturn(List.of());
        when(watchlistEntryRepository.findByUserIdAndContentIdInWithContent(VIEWER_ID, Set.of(content.getId())))
                .thenReturn(List.of());
        when(droppedEntryRepository.findByUserIdAndContentIdInWithContent(VIEWER_ID, Set.of(content.getId())))
                .thenReturn(List.of());
        when(userListItemRepository.findViewerMembershipsByContentIdIn(VIEWER_ID, Set.of(content.getId())))
                .thenReturn(List.of());
    }

    private Content movieContent() {
        return content(ContentType.MOVIE, "550", null, null, null);
    }

    private Content content(ContentType type, String tmdbId, String seriesTmdbId,
                            Integer seasonNumber, Integer episodeNumber) {
        return Content.builder().id(UUID.randomUUID()).type(type).tmdbId(tmdbId)
                .seriesTmdbId(seriesTmdbId).seasonNumber(seasonNumber).episodeNumber(episodeNumber).build();
    }

    private ContentCoordinate coordinate(Content content) {
        return new ContentCoordinate(content.getType(), content.getTmdbId(), content.getSeriesTmdbId(),
                content.getSeasonNumber(), content.getEpisodeNumber());
    }

    private ContentSchedule movieSchedule() {
        return new ContentSchedule(ContentScheduleKey.movie("550"), null, null, List.of(), true, false);
    }

    private ContentSchedule seriesSchedule() {
        return new ContentSchedule(ContentScheduleKey.series(SERIES_ID), null, "Ended", List.of(), true, false);
    }

    private ContentSchedule seasonSchedule() {
        return new ContentSchedule(ContentScheduleKey.season(SERIES_ID, 1), null, "Ended", List.of(), true, false);
    }

    private DiaryEntry diary(Content content, UUID id, int watchNumber, LocalDateTime createdAt,
                             int score, String comment, LocalDate watchedDate) {
        return DiaryEntry.builder().id(id).content(content).watchNumber(watchNumber).createdAt(createdAt)
                .updatedAt(createdAt).score(score).comment(comment).watchedDate(watchedDate).build();
    }

    private static final class WatchedEpisodeCoordinateRow
            implements DiaryEntryRepository.WatchedEpisodeCoordinateProjection {

        private final String seriesTmdbId;
        private final Integer seasonNumber;
        private final Integer episodeNumber;

        private WatchedEpisodeCoordinateRow(String seriesTmdbId, Integer seasonNumber, Integer episodeNumber) {
            this.seriesTmdbId = seriesTmdbId;
            this.seasonNumber = seasonNumber;
            this.episodeNumber = episodeNumber;
        }

        @Override
        public String getSeriesTmdbId() {
            return seriesTmdbId;
        }

        @Override
        public Integer getSeasonNumber() {
            return seasonNumber;
        }

        @Override
        public Integer getEpisodeNumber() {
            return episodeNumber;
        }
    }
}
