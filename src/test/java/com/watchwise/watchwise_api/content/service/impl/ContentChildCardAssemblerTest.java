package com.watchwise.watchwise_api.content.service.impl;

import com.watchwise.watchwise_api.content.dto.ContentChildCardDTO;
import com.watchwise.watchwise_api.content.dto.ContentDetailsDTO;
import com.watchwise.watchwise_api.content.dto.ContentNavigationDTO;
import com.watchwise.watchwise_api.content.dto.ContentPageSectionsDTO;
import com.watchwise.watchwise_api.content.dto.ContentStatsResponseDTO;
import com.watchwise.watchwise_api.content.dto.ContentViewerStateDTO;
import com.watchwise.watchwise_api.content.dto.EpisodeSummaryDTO;
import com.watchwise.watchwise_api.content.dto.SeasonSummaryDTO;
import com.watchwise.watchwise_api.content.dto.WatchStatus;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.content.service.ContentCoordinate;
import com.watchwise.watchwise_api.content.service.ContentStateResolver;
import com.watchwise.watchwise_api.content.service.ContentStatsService;
import com.watchwise.watchwise_api.content.service.ContentViewerStateService;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.dropped.repository.DroppedEntryRepository;
import com.watchwise.watchwise_api.userlist.repository.UserListItemRepository;
import com.watchwise.watchwise_api.watchlist.repository.WatchlistEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContentChildCardAssemblerTest {

    private static final UUID VIEWER_ID = UUID.randomUUID();
    private static final String SERIES_TMDB_ID = "1399";

    @Mock
    private ContentStatsService contentStatsService;

    @Mock
    private ContentViewerStateService contentViewerStateService;

    private ContentChildCardAssembler assembler;

    @BeforeEach
    void setUp() {
        assembler = new ContentChildCardAssembler(contentStatsService, contentViewerStateService);
    }

    @Test
    @DisplayName("[assembleSections] Should Map Series Seasons And Three Recent Episodes In One Viewer Read")
    void shouldMapSeriesSeasonsAndThreeRecentEpisodesInOneViewerRead() {
        ContentCoordinate seasonOne = season(1);
        ContentCoordinate seasonTwo = season(2);
        ContentCoordinate firstRecentEpisode = episode(2, 3);
        ContentCoordinate secondRecentEpisode = episode(2, 2);
        ContentCoordinate thirdRecentEpisode = episode(1, 10);
        List<ContentCoordinate> coordinates = List.of(
                seasonOne, seasonTwo, firstRecentEpisode, secondRecentEpisode, thirdRecentEpisode);
        UUID seasonOneId = UUID.randomUUID();
        UUID firstRecentEpisodeId = UUID.randomUUID();
        ContentViewerStateDTO watched = state(WatchStatus.WATCHED, 1);
        when(contentViewerStateService.resolve(VIEWER_ID, coordinates, Map.of()))
                .thenReturn(new ContentViewerStateService.Resolution(
                        Map.of(
                                seasonOne, watched,
                                seasonTwo, emptyState(),
                                firstRecentEpisode, watched,
                                secondRecentEpisode, emptyState(),
                                thirdRecentEpisode, emptyState()),
                        Map.of(seasonOne, seasonOneId, firstRecentEpisode, firstRecentEpisodeId)));
        ContentStatsResponseDTO seasonStats = new ContentStatsResponseDTO(seasonOneId, 8.0, 4, 2, 1);
        ContentStatsResponseDTO episodeStats = new ContentStatsResponseDTO(firstRecentEpisodeId, 9.0, 3, 1, 0);
        when(contentStatsService.getStatsBatch(List.of(seasonOneId, firstRecentEpisodeId)))
                .thenReturn(List.of(seasonStats, episodeStats));

        ContentPageSectionsDTO result = assembler.assembleSections(
                details(
                        ContentType.SERIES,
                        List.of(
                                new SeasonSummaryDTO(0, "Specials", "/specials.png",
                                        LocalDate.of(2020, 1, 1), 1, 1),
                                new SeasonSummaryDTO(1, "Season 1", "/season-1.png",
                                        LocalDate.of(2021, 1, 1), 10, 10),
                                new SeasonSummaryDTO(2, "Season 2", "/season-2.png",
                                        LocalDate.of(2022, 1, 1), 10, 3)),
                        List.of(),
                        List.of(
                                episodeSummary(2, 3, "Episode 3", 52, "/ep-3.png"),
                                episodeSummary(2, 2, "Episode 2", 51, "/ep-2.png"),
                                episodeSummary(1, 10, "Episode 10", 50, "/ep-10.png"),
                                episodeSummary(1, 9, "Episode 9", 49, "/ep-9.png"))),
                new ContentCoordinate(ContentType.SERIES, SERIES_TMDB_ID, null, null, null),
                VIEWER_ID);

        assertThat(result.seasons()).extracting(ContentChildCardDTO::seasonNumber)
                .containsExactly(1, 2);
        assertThat(result.seasons().get(0))
                .extracting(ContentChildCardDTO::contentId, ContentChildCardDTO::title,
                        ContentChildCardDTO::posterPath, ContentChildCardDTO::releaseDate,
                        ContentChildCardDTO::stats, ContentChildCardDTO::viewerState)
                .containsExactly(seasonOneId, "Season 1", "/season-1.png", LocalDate.of(2021, 1, 1),
                        seasonStats, watched);
        assertThat(result.seasons().get(1).contentId()).isNull();
        assertThat(result.recentEpisodes()).extracting(ContentChildCardDTO::episodeNumber)
                .containsExactly(3, 2, 10);
        assertThat(result.recentEpisodes().get(0))
                .extracting(ContentChildCardDTO::contentId, ContentChildCardDTO::title,
                        ContentChildCardDTO::posterPath, ContentChildCardDTO::runtimeMinutes,
                        ContentChildCardDTO::stats)
                .containsExactly(firstRecentEpisodeId, "Episode 3", "/ep-3.png", 52, episodeStats);
        assertThat(result.episodes()).isEmpty();
        verify(contentViewerStateService).resolve(VIEWER_ID, coordinates, Map.of());
        verify(contentStatsService).getStatsBatch(List.of(seasonOneId, firstRecentEpisodeId));
        verifyNoMoreInteractions(contentViewerStateService, contentStatsService);
    }

    @Test
    @DisplayName("[assembleSections] Should Keep Missing Season Episode Reference Nullable With Empty Read-Only State")
    void shouldKeepMissingSeasonEpisodeReferenceNullableWithEmptyReadOnlyState() {
        ContentCoordinate firstEpisode = episode(1, 1);
        ContentCoordinate secondEpisode = episode(1, 2);
        UUID firstEpisodeId = UUID.randomUUID();
        ContentViewerStateDTO watched = state(WatchStatus.WATCHED, 1);
        when(contentViewerStateService.resolve(VIEWER_ID, List.of(firstEpisode, secondEpisode), Map.of()))
                .thenReturn(new ContentViewerStateService.Resolution(
                        Map.of(firstEpisode, watched, secondEpisode, emptyState()),
                        Map.of(firstEpisode, firstEpisodeId)));
        ContentStatsResponseDTO firstStats = new ContentStatsResponseDTO(firstEpisodeId, 7.5, 2, 1, 3);
        when(contentStatsService.getStatsBatch(List.of(firstEpisodeId))).thenReturn(List.of(firstStats));

        ContentPageSectionsDTO result = assembler.assembleSections(
                details(ContentType.SEASON, List.of(), List.of(
                        episodeSummary(1, 1, "Pilot", 48, "/pilot.png"),
                        episodeSummary(1, 2, "Second", 47, "/second.png"))),
                new ContentCoordinate(ContentType.SEASON, null, SERIES_TMDB_ID, 1, null),
                VIEWER_ID);

        assertThat(result.episodes()).extracting(ContentChildCardDTO::episodeNumber)
                .containsExactly(1, 2);
        assertThat(result.episodes().get(0))
                .extracting(ContentChildCardDTO::contentId, ContentChildCardDTO::stats,
                        ContentChildCardDTO::viewerState)
                .containsExactly(firstEpisodeId, firstStats, watched);
        ContentChildCardDTO missing = result.episodes().get(1);
        assertThat(missing)
                .extracting(ContentChildCardDTO::contentId, ContentChildCardDTO::seriesTmdbId,
                        ContentChildCardDTO::seasonNumber, ContentChildCardDTO::episodeNumber,
                        ContentChildCardDTO::stats, ContentChildCardDTO::viewerState)
                .containsExactly(null, SERIES_TMDB_ID, 1, 2,
                        new ContentStatsResponseDTO(null, null, 0, 0, 0), emptyState());
        verify(contentViewerStateService).resolve(VIEWER_ID, List.of(firstEpisode, secondEpisode), Map.of());
        verify(contentStatsService).getStatsBatch(List.of(firstEpisodeId));
        verifyNoMoreInteractions(contentViewerStateService, contentStatsService);
    }

    @Test
    @DisplayName("[assembleSections] Should Map Parent Series Seasons For A Season Page")
    void shouldMapParentSeriesSeasonsForASeasonPage() {
        ContentCoordinate parentSeasonOne = season(1);
        ContentCoordinate parentSeasonTwo = season(2);
        ContentCoordinate firstEpisode = episode(1, 1);
        UUID parentSeasonOneId = UUID.randomUUID();
        UUID firstEpisodeId = UUID.randomUUID();
        List<ContentCoordinate> coordinates = List.of(parentSeasonOne, parentSeasonTwo, firstEpisode);
        when(contentViewerStateService.resolve(VIEWER_ID, coordinates, Map.of()))
                .thenReturn(new ContentViewerStateService.Resolution(
                        Map.of(parentSeasonOne, emptyState(), parentSeasonTwo, emptyState(), firstEpisode, emptyState()),
                        Map.of(parentSeasonOne, parentSeasonOneId, firstEpisode, firstEpisodeId)));
        ContentStatsResponseDTO seasonStats = new ContentStatsResponseDTO(parentSeasonOneId, 8.0, 3, 1, 0);
        ContentStatsResponseDTO episodeStats = new ContentStatsResponseDTO(firstEpisodeId, 7.0, 1, 1, 0);
        when(contentStatsService.getStatsBatch(List.of(parentSeasonOneId, firstEpisodeId)))
                .thenReturn(List.of(seasonStats, episodeStats));

        ContentPageSectionsDTO result = assembler.assembleSections(
                details(ContentType.SEASON, List.of(), List.of(episodeSummary(1, 1, "Pilot", 48, "/pilot.png"))),
                details(ContentType.SERIES, List.of(
                        new SeasonSummaryDTO(1, "Season 1", "/season-1.png",
                                LocalDate.of(2021, 1, 1), 10, 10),
                        new SeasonSummaryDTO(2, "Season 2", "/season-2.png",
                                LocalDate.of(2022, 1, 1), 8, 8)), List.of(), List.of()),
                new ContentCoordinate(ContentType.SEASON, null, SERIES_TMDB_ID, 1, null),
                VIEWER_ID);

        assertThat(result.seasons()).extracting(ContentChildCardDTO::seasonNumber)
                .containsExactly(1, 2);
        assertThat(result.seasons().get(0))
                .extracting(ContentChildCardDTO::contentId, ContentChildCardDTO::title,
                        ContentChildCardDTO::stats)
                .containsExactly(parentSeasonOneId, "Season 1", seasonStats);
        assertThat(result.episodes().get(0))
                .extracting(ContentChildCardDTO::contentId, ContentChildCardDTO::episodeNumber,
                        ContentChildCardDTO::stats)
                .containsExactly(firstEpisodeId, 1, episodeStats);
        verify(contentViewerStateService).resolve(VIEWER_ID, coordinates, Map.of());
        verify(contentStatsService).getStatsBatch(List.of(parentSeasonOneId, firstEpisodeId));
    }

    @Test
    @DisplayName("[assembleSections] Should Not Request Stats For Any Unreferenced Child")
    void shouldNotRequestStatsForAnyUnreferencedChild() {
        ContentCoordinate child = episode(1, 1);
        when(contentViewerStateService.resolve(VIEWER_ID, List.of(child), Map.of()))
                .thenReturn(new ContentViewerStateService.Resolution(Map.of(child, emptyState()), Map.of()));

        ContentPageSectionsDTO result = assembler.assembleSections(
                details(ContentType.SEASON, List.of(), List.of(episodeSummary(1, 1, "Pilot", 48, "/pilot.png"))),
                new ContentCoordinate(ContentType.SEASON, null, SERIES_TMDB_ID, 1, null),
                VIEWER_ID);

        assertThat(result.episodes().get(0).contentId()).isNull();
        assertThat(result.episodes().get(0).stats())
                .isEqualTo(new ContentStatsResponseDTO(null, null, 0, 0, 0));
        verify(contentViewerStateService).resolve(VIEWER_ID, List.of(child), Map.of());
        verifyNoMoreInteractions(contentViewerStateService, contentStatsService);
    }

    @Test
    @DisplayName("[assembleSections] Should Return Missing Child Through Real Viewer Resolver Without Writing")
    void shouldReturnMissingChildThroughRealViewerResolverWithoutWriting() {
        ContentCoordinate child = episode(1, 1);
        ContentRepository contentRepository = mock(ContentRepository.class);
        DiaryEntryRepository diaryEntryRepository = mock(DiaryEntryRepository.class);
        WatchlistEntryRepository watchlistEntryRepository = mock(WatchlistEntryRepository.class);
        DroppedEntryRepository droppedEntryRepository = mock(DroppedEntryRepository.class);
        UserListItemRepository userListItemRepository = mock(UserListItemRepository.class);
        when(contentRepository.findAllByCoordinates(List.of(child))).thenReturn(List.of());
        ContentViewerStateService readOnlyViewerStateService = new ContentViewerStateServiceImpl(
                diaryEntryRepository,
                watchlistEntryRepository,
                droppedEntryRepository,
                userListItemRepository,
                contentRepository,
                new ContentStateResolver(),
                Clock.systemUTC());
        ContentChildCardAssembler readOnlyAssembler = new ContentChildCardAssembler(
                contentStatsService, readOnlyViewerStateService);

        ContentChildCardDTO card = readOnlyAssembler.assembleSections(
                        details(ContentType.SEASON, List.of(), List.of(
                                episodeSummary(1, 1, "Pilot", 48, "/pilot.png"))),
                        new ContentCoordinate(ContentType.SEASON, null, SERIES_TMDB_ID, 1, null),
                        VIEWER_ID)
                .episodes().get(0);

        assertThat(card.contentId()).isNull();
        assertThat(card.stats()).isEqualTo(new ContentStatsResponseDTO(null, null, 0, 0, 0));
        assertThat(card.viewerState()).isEqualTo(emptyState());
        verify(contentRepository).findAllByCoordinates(List.of(child));
        verify(contentRepository, never()).save(any(Content.class));
        verifyNoMoreInteractions(contentRepository);
        verifyNoInteractions(
                diaryEntryRepository,
                watchlistEntryRepository,
                droppedEntryRepository,
                userListItemRepository,
                contentStatsService);
    }

    @Test
    @DisplayName("[assembleSections] Should Request Referenced Stats In Batches Of At Most One Hundred")
    void shouldRequestReferencedStatsInBatchesOfAtMostOneHundred() {
        List<SeasonSummaryDTO> seasonSummaries = new ArrayList<>();
        List<ContentCoordinate> coordinates = new ArrayList<>();
        Map<ContentCoordinate, ContentViewerStateDTO> statesByCoordinate = new LinkedHashMap<>();
        Map<ContentCoordinate, UUID> idsByCoordinate = new LinkedHashMap<>();
        for (int seasonNumber = 1; seasonNumber <= 101; seasonNumber++) {
            ContentCoordinate coordinate = season(seasonNumber);
            coordinates.add(coordinate);
            seasonSummaries.add(new SeasonSummaryDTO(
                    seasonNumber, "Season " + seasonNumber, null, LocalDate.of(2020, 1, 1), 10, 10));
            statesByCoordinate.put(coordinate, emptyState());
            idsByCoordinate.put(coordinate, UUID.randomUUID());
        }
        List<UUID> idsInOrder = new ArrayList<>(idsByCoordinate.values());
        List<UUID> firstBatch = idsInOrder.subList(0, 100);
        List<UUID> secondBatch = idsInOrder.subList(100, 101);
        ContentStatsResponseDTO firstBatchStats = new ContentStatsResponseDTO(
                firstBatch.get(0), 7.5, 11, 2, 3);
        ContentStatsResponseDTO secondBatchStats = new ContentStatsResponseDTO(
                secondBatch.get(0), 9.0, 13, 4, 5);
        when(contentViewerStateService.resolve(VIEWER_ID, coordinates, Map.of()))
                .thenReturn(new ContentViewerStateService.Resolution(statesByCoordinate, idsByCoordinate));
        when(contentStatsService.getStatsBatch(firstBatch)).thenReturn(List.of(firstBatchStats));
        when(contentStatsService.getStatsBatch(secondBatch)).thenReturn(List.of(secondBatchStats));

        ContentPageSectionsDTO result = assembler.assembleSections(
                details(ContentType.SERIES, seasonSummaries, List.of(), List.of()),
                new ContentCoordinate(ContentType.SERIES, SERIES_TMDB_ID, null, null, null),
                VIEWER_ID);

        assertThat(result.seasons()).hasSize(101).extracting(ContentChildCardDTO::contentId)
                .containsExactlyElementsOf(idsInOrder);
        assertThat(result.seasons().get(0).stats()).isEqualTo(firstBatchStats);
        assertThat(result.seasons().get(100).stats()).isEqualTo(secondBatchStats);
        verify(contentStatsService).getStatsBatch(firstBatch);
        verify(contentStatsService).getStatsBatch(secondBatch);
        verifyNoMoreInteractions(contentStatsService);
    }

    @Test
    @DisplayName("[assembleNavigation] Should Resolve Adjacent Episode Coordinates In One Read")
    void shouldResolveAdjacentEpisodeCoordinatesInOneRead() {
        ContentCoordinate previous = episode(2, 1);
        ContentCoordinate next = episode(2, 3);
        UUID previousId = UUID.randomUUID();
        when(contentViewerStateService.resolve(VIEWER_ID, List.of(previous, next), Map.of()))
                .thenReturn(new ContentViewerStateService.Resolution(
                        Map.of(previous, emptyState(), next, emptyState()),
                        Map.of(previous, previousId)));
        ContentStatsResponseDTO previousStats = new ContentStatsResponseDTO(previousId, 8.5, 2, 1, 0);
        when(contentStatsService.getStatsBatch(List.of(previousId))).thenReturn(List.of(previousStats));

        ContentNavigationDTO result = assembler.assembleNavigation(
                details(ContentType.EPISODE, List.of(), List.of()),
                detailsWithEpisodeCount(3, seasonEpisodes()),
                new ContentCoordinate(ContentType.EPISODE, null, SERIES_TMDB_ID, 2, 2),
                VIEWER_ID);

        assertThat(result.seriesTmdbId()).isEqualTo(SERIES_TMDB_ID);
        assertThat(result.seasonNumber()).isEqualTo(2);
        assertThat(result.episodeNumber()).isEqualTo(2);
        assertThat(result.seasonEpisodeCount()).isEqualTo(3);
        assertThat(result.previousEpisode())
                .extracting(ContentChildCardDTO::contentId, ContentChildCardDTO::seasonNumber,
                        ContentChildCardDTO::episodeNumber, ContentChildCardDTO::stats)
                .containsExactly(previousId, 2, 1, previousStats);
        assertThat(result.nextEpisode())
                .extracting(ContentChildCardDTO::contentId, ContentChildCardDTO::seasonNumber,
                        ContentChildCardDTO::episodeNumber, ContentChildCardDTO::stats)
                .containsExactly(null, 2, 3,
                        new ContentStatsResponseDTO(null, null, 0, 0, 0));
        verify(contentViewerStateService).resolve(VIEWER_ID, List.of(previous, next), Map.of());
        verify(contentStatsService).getStatsBatch(List.of(previousId));
        verifyNoMoreInteractions(contentViewerStateService, contentStatsService);
    }

    @Test
    @DisplayName("[assembleNavigation] Should Omit Previous And Include Next For The First Episode")
    void shouldOmitPreviousAndIncludeNextForTheFirstEpisode() {
        ContentCoordinate next = episode(1, 2);
        when(contentViewerStateService.resolve(VIEWER_ID, List.of(next), Map.of()))
                .thenReturn(new ContentViewerStateService.Resolution(Map.of(next, emptyState()), Map.of()));

        ContentNavigationDTO result = assembler.assembleNavigation(
                details(ContentType.EPISODE, List.of(), List.of()),
                detailsWithEpisodeCount(3, seasonEpisodes()),
                new ContentCoordinate(ContentType.EPISODE, null, SERIES_TMDB_ID, 1, 1),
                VIEWER_ID);

        assertThat(result.previousEpisode()).isNull();
        assertThat(result.nextEpisode())
                .extracting(ContentChildCardDTO::seriesTmdbId, ContentChildCardDTO::seasonNumber,
                        ContentChildCardDTO::episodeNumber)
                .containsExactly(SERIES_TMDB_ID, 1, 2);
        verify(contentViewerStateService).resolve(VIEWER_ID, List.of(next), Map.of());
        verifyNoMoreInteractions(contentViewerStateService, contentStatsService);
    }

    @Test
    @DisplayName("[assembleNavigation] Should Omit Next At The Parent Season Boundary For The Last Episode")
    void shouldOmitNextAtTheParentSeasonBoundaryForTheLastEpisode() {
        ContentCoordinate previous = episode(1, 2);
        when(contentViewerStateService.resolve(VIEWER_ID, List.of(previous), Map.of()))
                .thenReturn(new ContentViewerStateService.Resolution(Map.of(previous, emptyState()), Map.of()));

        ContentNavigationDTO result = assembler.assembleNavigation(
                details(ContentType.EPISODE, List.of(), List.of()),
                detailsWithEpisodeCount(3, seasonEpisodes()),
                new ContentCoordinate(ContentType.EPISODE, null, SERIES_TMDB_ID, 1, 3),
                VIEWER_ID);

        assertThat(result.previousEpisode())
                .extracting(ContentChildCardDTO::seasonNumber, ContentChildCardDTO::episodeNumber)
                .containsExactly(1, 2);
        assertThat(result.nextEpisode()).isNull();
        verify(contentViewerStateService).resolve(VIEWER_ID, List.of(previous), Map.of());
        verifyNoMoreInteractions(contentViewerStateService, contentStatsService);
    }

    @Test
    @DisplayName("[assembleNavigation] Should Return No Navigation Without A Parent Season Boundary")
    void shouldNotInventNextWithoutAParentSeasonBoundary() {
        ContentNavigationDTO result = assembler.assembleNavigation(
                details(ContentType.EPISODE, List.of(), List.of()),
                new ContentCoordinate(ContentType.EPISODE, null, SERIES_TMDB_ID, 1, 2),
                VIEWER_ID);

        assertThat(result).isNull();
        verifyNoInteractions(contentViewerStateService, contentStatsService);
    }

    private ContentCoordinate season(int seasonNumber) {
        return new ContentCoordinate(ContentType.SEASON, null, SERIES_TMDB_ID, seasonNumber, null);
    }

    private ContentCoordinate episode(int seasonNumber, int episodeNumber) {
        return new ContentCoordinate(
                ContentType.EPISODE, null, SERIES_TMDB_ID, seasonNumber, episodeNumber);
    }

    private ContentDetailsDTO details(
            ContentType type,
            List<SeasonSummaryDTO> seasons,
            List<EpisodeSummaryDTO> episodes) {
        return details(type, seasons, episodes, List.of());
    }

    private ContentDetailsDTO details(
            ContentType type,
            List<SeasonSummaryDTO> seasons,
            List<EpisodeSummaryDTO> episodes,
            List<EpisodeSummaryDTO> recentEpisodes) {
        return new ContentDetailsDTO(
                null, type, "Root", null, null, null, null, null, null, null, null,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                seasons, episodes, recentEpisodes, null, null, List.of(), List.of(), List.of());
    }

    private ContentDetailsDTO detailsWithEpisodeCount(
            Integer numberOfEpisodes, List<EpisodeSummaryDTO> episodes) {
        return new ContentDetailsDTO(
                null, ContentType.SEASON, "Season", null, null, null, null, null, null, null,
                numberOfEpisodes, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                null, episodes, List.of(), null, null, List.of(), List.of(), List.of());
    }

    private List<EpisodeSummaryDTO> seasonEpisodes() {
        return List.of(
                episodeSummary(1, 1, "Pilot", 48, "/pilot.png"),
                episodeSummary(1, 2, "Second", 47, "/second.png"),
                episodeSummary(1, 3, "Third", 46, "/third.png"));
    }

    private EpisodeSummaryDTO episodeSummary(
            int seasonNumber, int episodeNumber, String title, int runtime, String stillPath) {
        return new EpisodeSummaryDTO(
                seasonNumber, episodeNumber, title, LocalDate.of(2022, 1, episodeNumber), runtime, stillPath);
    }

    private ContentViewerStateDTO state(WatchStatus watchStatus, int playsCount) {
        return new ContentViewerStateDTO(
                watchStatus, 8, "Review", LocalDate.of(2026, 1, 1), 1, false,
                playsCount, UUID.randomUUID(), false, null, false, null, List.of(), null, null);
    }

    private ContentViewerStateDTO emptyState() {
        return new ContentViewerStateDTO(
                WatchStatus.UNWATCHED, null, null, null, null, null, 0, null,
                false, null, false, null, List.of(), null, null);
    }
}
