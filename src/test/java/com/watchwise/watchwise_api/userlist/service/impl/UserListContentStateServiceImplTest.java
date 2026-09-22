package com.watchwise.watchwise_api.userlist.service.impl;

import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.content.dto.ContentProductionStatus;
import com.watchwise.watchwise_api.content.dto.ContentStateDTO;
import com.watchwise.watchwise_api.content.dto.ReleaseStatus;
import com.watchwise.watchwise_api.content.dto.WatchStatus;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.service.ContentSchedule;
import com.watchwise.watchwise_api.content.service.ContentScheduleEpisode;
import com.watchwise.watchwise_api.content.service.ContentScheduleKey;
import com.watchwise.watchwise_api.content.service.ContentScheduleLookup;
import com.watchwise.watchwise_api.content.service.ContentScheduleReader;
import com.watchwise.watchwise_api.content.service.ContentStateResolver;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.diaryentry.repository.WatchedEpisodeCoordinate;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.userlist.entity.UserList;
import com.watchwise.watchwise_api.userlist.entity.UserListItem;
import com.watchwise.watchwise_api.userlist.service.UserListContentStateResult;
import com.watchwise.watchwise_api.userlist.service.UserListContentStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserListContentStateServiceImplTest {

    private static final String MOVIE_TMDB_ID = "550";
    private static final String SERIES_TMDB_ID = "1396";
    private static final int SEASON_NUMBER = 1;

    @Mock
    private UserRepository userRepository;

    @Mock
    private DiaryEntryRepository diaryEntryRepository;

    @Mock
    private ContentScheduleReader contentScheduleReader;

    @Mock
    private ContentStateResolver contentStateResolver;

    @Mock
    private Clock clock;

    private UserListContentStateService service;
    private UUID viewerId;
    private User viewer;

    @BeforeEach
    void setUp() {
        service = new UserListContentStateServiceImpl(
                userRepository, diaryEntryRepository, contentScheduleReader, contentStateResolver, clock);
        viewerId = UUID.randomUUID();
        viewer = User.builder()
                .id(viewerId)
                .username("lucas")
                .email("lucas@email.com")
                .password("hashed_password")
                .preferredRegion("BR")
                .preferredLanguage("pt-BR")
                .build();
    }

    @Test
    @DisplayName("[resolve] Should Batch History And Schedules And Calculate Progress - When Multiple Lists Share Series And Seasons")
    void shouldBatchHistoryAndSchedulesAndCalculateProgressWhenMultipleListsShareSeriesAndSeasons() {
        when(userRepository.findById(viewerId)).thenReturn(Optional.of(viewer));

        UserList firstList = list();
        UserList secondList = list();
        UserList nestedOnlyList = list();

        Content movie = content(ContentType.MOVIE, MOVIE_TMDB_ID, null, null, null);
        Content firstSeries = content(ContentType.SERIES, SERIES_TMDB_ID, null, null, null);
        Content secondSeries = content(ContentType.SERIES, SERIES_TMDB_ID, null, null, null);
        Content season = content(ContentType.SEASON, null, SERIES_TMDB_ID, SEASON_NUMBER, null);
        Content episode = content(ContentType.EPISODE, null, SERIES_TMDB_ID, SEASON_NUMBER, 2);
        UserList childList = list();

        UserListItem movieItem = contentItem(firstList, movie, 1);
        UserListItem seriesItem = contentItem(firstList, firstSeries, 2);
        UserListItem duplicateSeriesItem = contentItem(secondList, secondSeries, 1);
        UserListItem seasonItem = contentItem(secondList, season, 2);
        UserListItem episodeItem = contentItem(secondList, episode, 3);
        UserListItem nestedItem = childListItem(nestedOnlyList, childList, 1);

        ContentSchedule movieSchedule = schedule(ContentScheduleKey.movie(MOVIE_TMDB_ID));
        ContentSchedule seriesSchedule = schedule(ContentScheduleKey.series(SERIES_TMDB_ID));
        ContentSchedule seasonSchedule = schedule(ContentScheduleKey.season(SERIES_TMDB_ID, SEASON_NUMBER));
        when(contentScheduleReader.readMovie(MOVIE_TMDB_ID, "BR", "pt-BR"))
                .thenReturn(new ContentScheduleLookup.Found(movieSchedule));
        when(contentScheduleReader.readSeries(SERIES_TMDB_ID, "BR", "pt-BR"))
                .thenReturn(new ContentScheduleLookup.Found(seriesSchedule));
        when(contentScheduleReader.readSeason(SERIES_TMDB_ID, SEASON_NUMBER, "BR", "pt-BR"))
                .thenReturn(new ContentScheduleLookup.Found(seasonSchedule));

        Set<UUID> contentIds = Set.of(movie.getId(), firstSeries.getId(), secondSeries.getId(), season.getId(), episode.getId());
        Set<String> seriesTmdbIds = Set.of(SERIES_TMDB_ID);
        Set<WatchedEpisodeCoordinate> watchedEpisodes = Set.of(
                new WatchedEpisodeCoordinate(SERIES_TMDB_ID, SEASON_NUMBER, 2));
        when(diaryEntryRepository.findWatchedDirectContentIds(viewerId, contentIds))
                .thenReturn(Set.of(movie.getId(), episode.getId()));
        when(diaryEntryRepository.findWatchedEpisodeCoordinates(viewerId, seriesTmdbIds))
                .thenReturn(watchedEpisodes);

        ContentStateDTO watchedMovie = state(WatchStatus.WATCHED);
        ContentStateDTO partiallyWatchedSeries = state(WatchStatus.PARTIALLY_WATCHED);
        ContentStateDTO watchedSeason = state(WatchStatus.WATCHED);
        ContentStateDTO watchedEpisode = state(WatchStatus.WATCHED);
        Map<UUID, ContentStateDTO> statesByContentId = Map.of(
                movie.getId(), watchedMovie,
                firstSeries.getId(), partiallyWatchedSeries,
                secondSeries.getId(), partiallyWatchedSeries,
                season.getId(), watchedSeason,
                episode.getId(), watchedEpisode);
        when(contentStateResolver.resolve(any(), any(), anySet(), anySet(), same(clock)))
                .thenAnswer(invocation -> statesByContentId.get(invocation.getArgument(0, Content.class).getId()));

        UserListContentStateResult result = service.resolve(viewerId,
                List.of(movieItem, seriesItem, duplicateSeriesItem, seasonItem, episodeItem, nestedItem));

        assertThat(result.stateByItemId()).containsExactlyInAnyOrderEntriesOf(Map.of(
                movieItem.getId(), watchedMovie,
                seriesItem.getId(), partiallyWatchedSeries,
                duplicateSeriesItem.getId(), partiallyWatchedSeries,
                seasonItem.getId(), watchedSeason,
                episodeItem.getId(), watchedEpisode));
        assertThat(result.watchedPercentageByListId())
                .containsEntry(firstList.getId(), 50.0)
                .containsEntry(secondList.getId(), 66.66666666666667)
                .containsEntry(nestedOnlyList.getId(), 0.0);

        verify(diaryEntryRepository).findWatchedDirectContentIds(viewerId, contentIds);
        verify(diaryEntryRepository).findWatchedEpisodeCoordinates(viewerId, seriesTmdbIds);
        verify(contentScheduleReader).readMovie(MOVIE_TMDB_ID, "BR", "pt-BR");
        verify(contentScheduleReader).readSeries(SERIES_TMDB_ID, "BR", "pt-BR");
        verify(contentScheduleReader).readSeason(SERIES_TMDB_ID, SEASON_NUMBER, "BR", "pt-BR");
        verify(contentStateResolver, org.mockito.Mockito.times(5))
                .resolve(any(), any(), eq(Set.of(movie.getId(), episode.getId())), eq(watchedEpisodes), same(clock));
    }

    @Test
    @DisplayName("[resolve] Should Include Series IDs In Episode History Batch - When A List Contains A Series")
    void shouldIncludeSeriesIdsInEpisodeHistoryBatchWhenAListContainsASeries() {
        when(userRepository.findById(viewerId)).thenReturn(Optional.of(viewer));
        UserList list = list();
        Content series = content(ContentType.SERIES, SERIES_TMDB_ID, null, null, null);
        UserListItem item = contentItem(list, series, 1);
        ContentSchedule seriesSchedule = schedule(ContentScheduleKey.series(SERIES_TMDB_ID));
        WatchedEpisodeCoordinate watchedEpisode = new WatchedEpisodeCoordinate(SERIES_TMDB_ID, SEASON_NUMBER, 1);

        when(diaryEntryRepository.findWatchedDirectContentIds(viewerId, Set.of(series.getId())))
                .thenReturn(Set.of());
        when(diaryEntryRepository.findWatchedEpisodeCoordinates(viewerId, Set.of(SERIES_TMDB_ID)))
                .thenReturn(Set.of(watchedEpisode));
        when(contentScheduleReader.readSeries(SERIES_TMDB_ID, "BR", "pt-BR"))
                .thenReturn(new ContentScheduleLookup.Found(seriesSchedule));
        ContentStateDTO state = state(WatchStatus.PARTIALLY_WATCHED);
        when(contentStateResolver.resolve(any(), any(), anySet(), anySet(), same(clock))).thenReturn(state);

        UserListContentStateResult result = service.resolve(viewerId, List.of(item));

        assertThat(result.stateByItemId()).containsEntry(item.getId(), state);
        verify(diaryEntryRepository).findWatchedEpisodeCoordinates(viewerId, Set.of(SERIES_TMDB_ID));
        verify(contentStateResolver).resolve(
                eq(series), eq(seriesSchedule), eq(Set.of()), eq(Set.of(watchedEpisode)), same(clock));
    }

    @Test
    @DisplayName("[resolve] Should Keep A Content Item With Unknown Metadata - When Its Schedule Is Unavailable")
    void shouldKeepAContentItemWithUnknownMetadataWhenItsScheduleIsUnavailable() {
        when(userRepository.findById(viewerId)).thenReturn(Optional.of(viewer));
        UserList list = list();
        Content series = content(ContentType.SERIES, SERIES_TMDB_ID, null, null, null);
        UserListItem item = contentItem(list, series, 1);
        when(diaryEntryRepository.findWatchedDirectContentIds(viewerId, Set.of(series.getId())))
                .thenReturn(Set.of());
        when(diaryEntryRepository.findWatchedEpisodeCoordinates(viewerId, Set.of(SERIES_TMDB_ID)))
                .thenReturn(Set.of());
        when(contentScheduleReader.readSeries(SERIES_TMDB_ID, "BR", "pt-BR"))
                .thenReturn(new ContentScheduleLookup.Unavailable());
        ContentStateDTO unknown = state(WatchStatus.UNKNOWN);
        when(contentStateResolver.resolve(any(), any(), anySet(), anySet(), same(clock))).thenReturn(unknown);

        UserListContentStateResult result = service.resolve(viewerId, List.of(item));

        assertThat(result.stateByItemId()).containsEntry(item.getId(), unknown);
        assertThat(result.watchedPercentageByListId()).containsEntry(list.getId(), 0.0);
        ArgumentCaptor<ContentSchedule> scheduleCaptor = ArgumentCaptor.forClass(ContentSchedule.class);
        verify(contentStateResolver).resolve(eq(series), scheduleCaptor.capture(), eq(Set.of()), eq(Set.of()), same(clock));
        assertThat(scheduleCaptor.getValue().key()).isEqualTo(ContentScheduleKey.series(SERIES_TMDB_ID));
        assertThat(scheduleCaptor.getValue().releaseDate()).isNull();
        assertThat(scheduleCaptor.getValue().externalStatus()).isNull();
        assertThat(scheduleCaptor.getValue().complete()).isFalse();
        verify(diaryEntryRepository).findWatchedEpisodeCoordinates(viewerId, Set.of(SERIES_TMDB_ID));
    }

    @Test
    @DisplayName("[resolve] Should Keep A Content Item With Unknown Metadata - When Its Schedule Is Not Found")
    void shouldKeepAContentItemWithUnknownMetadataWhenItsScheduleIsNotFound() {
        when(userRepository.findById(viewerId)).thenReturn(Optional.of(viewer));
        UserList list = list();
        Content series = content(ContentType.SERIES, SERIES_TMDB_ID, null, null, null);
        UserListItem item = contentItem(list, series, 1);
        when(diaryEntryRepository.findWatchedDirectContentIds(viewerId, Set.of(series.getId())))
                .thenReturn(Set.of());
        when(diaryEntryRepository.findWatchedEpisodeCoordinates(viewerId, Set.of(SERIES_TMDB_ID)))
                .thenReturn(Set.of());
        when(contentScheduleReader.readSeries(SERIES_TMDB_ID, "BR", "pt-BR"))
                .thenReturn(new ContentScheduleLookup.NotFound(null));
        ContentStateDTO unknown = state(WatchStatus.UNKNOWN);
        when(contentStateResolver.resolve(any(), any(), anySet(), anySet(), same(clock))).thenReturn(unknown);

        UserListContentStateResult result = service.resolve(viewerId, List.of(item));

        assertThat(result.stateByItemId()).containsEntry(item.getId(), unknown);
        assertThat(result.watchedPercentageByListId()).containsEntry(list.getId(), 0.0);
        ArgumentCaptor<ContentSchedule> scheduleCaptor = ArgumentCaptor.forClass(ContentSchedule.class);
        verify(contentStateResolver).resolve(eq(series), scheduleCaptor.capture(), eq(Set.of()), eq(Set.of()), same(clock));
        assertThat(scheduleCaptor.getValue().key()).isEqualTo(ContentScheduleKey.series(SERIES_TMDB_ID));
        assertThat(scheduleCaptor.getValue().complete()).isFalse();
    }

    @Test
    @DisplayName("[resolve] Should Pass Only The Requested Episode Schedule Data - When Reusing A Season Lookup")
    void shouldPassOnlyTheRequestedEpisodeScheduleDataWhenReusingASeasonLookup() {
        when(userRepository.findById(viewerId)).thenReturn(Optional.of(viewer));
        UserList list = list();
        Content episode = content(ContentType.EPISODE, null, SERIES_TMDB_ID, SEASON_NUMBER, 2);
        UserListItem item = contentItem(list, episode, 1);
        ContentSchedule seasonSchedule = new ContentSchedule(
                ContentScheduleKey.season(SERIES_TMDB_ID, SEASON_NUMBER),
                LocalDate.of(2026, 9, 1),
                "Ended",
                List.of(
                        new ContentScheduleEpisode(null, SEASON_NUMBER, 1, LocalDate.of(2026, 9, 1)),
                        new ContentScheduleEpisode(null, SEASON_NUMBER, 2, LocalDate.of(2026, 9, 8))),
                true,
                false);
        when(diaryEntryRepository.findWatchedDirectContentIds(viewerId, Set.of(episode.getId())))
                .thenReturn(Set.of());
        when(diaryEntryRepository.findWatchedEpisodeCoordinates(viewerId, Set.of(SERIES_TMDB_ID)))
                .thenReturn(Set.of());
        when(contentScheduleReader.readSeason(SERIES_TMDB_ID, SEASON_NUMBER, "BR", "pt-BR"))
                .thenReturn(new ContentScheduleLookup.Found(seasonSchedule));
        when(contentStateResolver.resolve(any(), any(), anySet(), anySet(), same(clock)))
                .thenReturn(state(WatchStatus.UNWATCHED));

        service.resolve(viewerId, List.of(item));

        ArgumentCaptor<ContentSchedule> scheduleCaptor = ArgumentCaptor.forClass(ContentSchedule.class);
        verify(contentStateResolver).resolve(eq(episode), scheduleCaptor.capture(), eq(Set.of()), eq(Set.of()), same(clock));
        assertThat(scheduleCaptor.getValue().episodes()).containsExactly(
                new ContentScheduleEpisode(null, SEASON_NUMBER, 2, LocalDate.of(2026, 9, 8)));
        assertThat(scheduleCaptor.getValue().releaseDate()).isEqualTo(LocalDate.of(2026, 9, 8));
    }

    @Test
    @DisplayName("[resolve] Should Skip Empty Batch Queries And State Resolution - When All Items Are Nested Lists")
    void shouldSkipEmptyBatchQueriesAndStateResolutionWhenAllItemsAreNestedLists() {
        when(userRepository.findById(viewerId)).thenReturn(Optional.of(viewer));
        UserList list = list();
        UserListItem item = childListItem(list, list(), 1);

        UserListContentStateResult result = service.resolve(viewerId, List.of(item));

        assertThat(result.stateByItemId()).isEmpty();
        assertThat(result.watchedPercentageByListId()).containsEntry(list.getId(), 0.0);
        verifyNoInteractions(diaryEntryRepository, contentScheduleReader, contentStateResolver);
    }

    @Test
    @DisplayName("[resolve] Should Reject A Missing Viewer - When The Viewer ID Does Not Exist")
    void shouldRejectAMissingViewerWhenTheViewerIdDoesNotExist() {
        when(userRepository.findById(viewerId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolve(viewerId, List.of()))
                .isInstanceOf(NotFoundException.class);
        verifyNoInteractions(diaryEntryRepository, contentScheduleReader, contentStateResolver);
    }

    private UserList list() {
        return UserList.builder().id(UUID.randomUUID()).user(viewer).name("List").build();
    }

    private UserListItem contentItem(UserList list, Content content, int position) {
        return UserListItem.builder().id(UUID.randomUUID()).userList(list).content(content).position(position).build();
    }

    private UserListItem childListItem(UserList list, UserList childList, int position) {
        return UserListItem.builder().id(UUID.randomUUID()).userList(list).childList(childList).position(position).build();
    }

    private Content content(
            ContentType type, String tmdbId, String seriesTmdbId, Integer seasonNumber, Integer episodeNumber) {
        return Content.builder()
                .id(UUID.randomUUID())
                .type(type)
                .tmdbId(tmdbId)
                .seriesTmdbId(seriesTmdbId)
                .seasonNumber(seasonNumber)
                .episodeNumber(episodeNumber)
                .build();
    }

    private ContentSchedule schedule(ContentScheduleKey key) {
        return new ContentSchedule(key, LocalDate.of(2026, 9, 1), "Ended", List.of(), true, false);
    }

    private ContentStateDTO state(WatchStatus watchStatus) {
        return new ContentStateDTO(watchStatus, ReleaseStatus.RELEASED, ContentProductionStatus.FINISHED, null, null);
    }
}
