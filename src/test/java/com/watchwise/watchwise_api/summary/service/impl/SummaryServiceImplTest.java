package com.watchwise.watchwise_api.summary.service.impl;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.watchwise.watchwise_api.common.dto.GenreCountDTO;
import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.exception.TmdbUnavailableException;
import com.watchwise.watchwise_api.calendar.dto.CalendarEventDTO;
import com.watchwise.watchwise_api.calendar.dto.CalendarEventType;
import com.watchwise.watchwise_api.calendar.dto.CalendarSource;
import com.watchwise.watchwise_api.calendar.dto.MovieCalendarContentDTO;
import com.watchwise.watchwise_api.calendar.service.CalendarService;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.dto.ContentCardDTO;
import com.watchwise.watchwise_api.content.dto.ContentCardFieldSet;
import com.watchwise.watchwise_api.content.dto.ContentPreviewStatus;
import com.watchwise.watchwise_api.content.dto.ReleaseStatus;
import com.watchwise.watchwise_api.content.dto.WatchStatus;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.mapper.ContentMapper;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.content.service.ContentCardContext;
import com.watchwise.watchwise_api.content.service.ContentCardSpec;
import com.watchwise.watchwise_api.content.service.ContentCoordinate;
import com.watchwise.watchwise_api.content.service.impl.ContentCardAssembler;
import com.watchwise.watchwise_api.contentposter.service.UserContentPosterService;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryEntryResponseDTO;
import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import com.watchwise.watchwise_api.diaryentry.mapper.DiaryEntryMapper;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.diaryentry.repository.WatchCompanionRepository;
import com.watchwise.watchwise_api.diaryentry.service.DiaryEntryService;
import com.watchwise.watchwise_api.dropped.entity.DroppedEntry;
import com.watchwise.watchwise_api.dropped.repository.DroppedEntryRepository;
import com.watchwise.watchwise_api.feed.dto.FeedEventType;
import com.watchwise.watchwise_api.feed.dto.FeedItemDTO;
import com.watchwise.watchwise_api.feed.service.FeedService;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.notification.repository.NotificationRepository;
import com.watchwise.watchwise_api.summary.dto.AllTimeStatsResponseDTO;
import com.watchwise.watchwise_api.summary.dto.AllTimeEditionStatsDTO;
import com.watchwise.watchwise_api.summary.dto.DailyWatchCountDTO;
import com.watchwise.watchwise_api.summary.dto.EpisodeRatingsGridResponseDTO;
import com.watchwise.watchwise_api.summary.dto.EpisodeRatingsMapItemDTO;
import com.watchwise.watchwise_api.summary.dto.EpisodeRatingsMapResponseDTO;
import com.watchwise.watchwise_api.summary.dto.HomeSummaryResponseDTO;
import com.watchwise.watchwise_api.summary.dto.HomeRecentlyWatchedDTO;
import com.watchwise.watchwise_api.summary.dto.MonthInReviewResponseDTO;
import com.watchwise.watchwise_api.summary.dto.ProfileDiaryPreviewDTO;
import com.watchwise.watchwise_api.summary.dto.RatingCountDTO;
import com.watchwise.watchwise_api.summary.dto.RecentActivityItemDTO;
import com.watchwise.watchwise_api.summary.dto.RecentActivityStatus;
import com.watchwise.watchwise_api.summary.dto.SeriesInProgressPreviewDTO;
import com.watchwise.watchwise_api.diaryentry.dto.SeriesInProgressResponseDTO;
import com.watchwise.watchwise_api.summary.dto.SummaryResponseDTO;
import com.watchwise.watchwise_api.summary.dto.WatchCompanionCountDTO;
import com.watchwise.watchwise_api.summary.dto.YearInReviewResponseDTO;
import com.watchwise.watchwise_api.summary.service.AllTimeStatsReader;
import com.watchwise.watchwise_api.seriesprogress.service.SeriesProgressMetadataRefreshService;
import com.watchwise.watchwise_api.top5entry.entity.Top5Entry;
import com.watchwise.watchwise_api.top5entry.repository.Top5EntryRepository;
import com.watchwise.watchwise_api.user.dto.UserPreviewDTO;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.mapper.UserMapper;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import com.watchwise.watchwise_api.common.dto.CursorPageResponseDTO;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Collection;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SummaryServiceImplTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private FollowerRepository followerRepository;

    @Mock
    private DiaryEntryRepository diaryEntryRepository;

    @Mock
    private DiaryEntryRepository.HomeWatchAggregate homeWatchAggregate;

    @Mock
    private DiaryEntryService diaryEntryService;

    @Mock
    private DroppedEntryRepository droppedEntryRepository;

    @Mock
    private ContentRepository contentRepository;

    @Mock
    private ContentMapper contentMapper;

    @Mock
    private DiaryEntryMapper diaryEntryMapper;

    @Mock
    private Top5EntryRepository top5EntryRepository;

    @Mock
    private WatchCompanionRepository watchCompanionRepository;

    @Mock
    private UserMapper userMapper;

    @Mock
    private SeriesProgressMetadataRefreshService seriesProgressMetadataRefreshService;

    @Mock
    private UserContentPosterService userContentPosterService;

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private FeedService feedService;

    @Mock
    private CalendarService calendarService;

    @Mock
    private HomeNextEpisodeAssembler homeNextEpisodeAssembler;

    @Mock
    private ContentCardAssembler contentCardAssembler;

    @Mock
    private com.watchwise.watchwise_api.summary.service.ProfileSummaryReader profileSummaryReader;

    @Mock
    private AllTimeStatsReader allTimeStatsReader;

    @InjectMocks
    private SummaryServiceImpl summaryService;

    private UUID lucasId;
    private UUID marinaId;
    private User lucas;

    private final ObjectMapper objectMapper = new ObjectMapper();


    @Test
    @DisplayName("[getAllTimeStatsEdition] Should Delegate To The Reader - When Type Is Valid")
    void shouldDelegateToTheReaderWhenAllTimeEditionTypeIsValid() {
        AllTimeEditionStatsDTO expected = new AllTimeEditionStatsDTO(ContentType.MOVIE, 1L, 120L, 1L,
                10.0, 2.0, 1.0, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(allTimeStatsReader.read(lucasId, ContentType.MOVIE)).thenReturn(expected);

        AllTimeEditionStatsDTO result = summaryService.getAllTimeStatsEdition(lucasId, lucasId, ContentType.MOVIE);

        assertThat(result).isSameAs(expected);
        verify(allTimeStatsReader).read(lucasId, ContentType.MOVIE);
    }

    @BeforeEach
    void setUp() {
        lucasId = UUID.randomUUID();
        marinaId = UUID.randomUUID();
        lucas = buildUser(lucasId, true);

        lenient().when(diaryEntryService.getDiaryEntries(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Page.empty());
        lenient().when(droppedEntryRepository.findByUserIdAndTypeOrderByCreatedAtDesc(any(), any(), any()))
                .thenReturn(Page.empty());
        lenient().when(diaryEntryRepository.findTopByUserIdAndContentTypeOrderByCreatedAtDesc(any(), any(), any()))
                .thenReturn(List.of());
        lenient().when(watchCompanionRepository.findByDiaryEntryIdIn(any())).thenReturn(List.of());
        lenient().when(diaryEntryRepository.findSeriesInProgressByUserId(any(), any(PageRequest.class)))
                .thenReturn(Page.empty());
        lenient().when(diaryEntryService.getSeriesInProgress(any(), any(), any(), any()))
                .thenReturn(Page.empty());
        lenient().when(diaryEntryRepository.countByUserIdAndWatchedDateBetween(any(), any(), any()))
                .thenReturn(List.of());
        lenient().when(diaryEntryRepository.countEntriesByGenreAndUserIdForMoviesAndWatchedDateBetween(any(), any(), any()))
                .thenReturn(List.of());
        lenient().when(diaryEntryRepository.countDistinctTitlesByGenreAndUserIdForSeriesAndWatchedDateBetween(any(), any(), any()))
                .thenReturn(List.of());
        lenient().when(diaryEntryRepository.countEpisodeEntriesByGenreAndUserIdForSeriesAndWatchedDateBetween(any(), any(), any()))
                .thenReturn(List.of());
        lenient().when(diaryEntryRepository.countDistinctMoviesByUserId(any())).thenReturn(0L);
        lenient().when(diaryEntryRepository.findHomeWatchAggregate(any())).thenReturn(homeWatchAggregate);
        lenient().when(homeWatchAggregate.getTotalMinutesWatchedMovies()).thenReturn(0L);
        lenient().when(homeWatchAggregate.getTotalMinutesWatchedEpisodes()).thenReturn(0L);
        lenient().when(homeWatchAggregate.getTotalMoviesWatched()).thenReturn(0L);
        lenient().when(homeWatchAggregate.getTotalDistinctMoviesWatched()).thenReturn(0L);
        lenient().when(homeWatchAggregate.getTotalEpisodesWatched()).thenReturn(0L);
        lenient().when(homeWatchAggregate.getDistinctSeriesWatched()).thenReturn(0L);
        lenient().when(notificationRepository.existsByUserIdAndIsReadFalse(any())).thenReturn(false);
        lenient().when(feedService.getFeed(any(), any(), any()))
                .thenReturn(new CursorPageResponseDTO<>(List.of(), 3, null, false));
        lenient().when(calendarService.getUpcoming(any(), eq(6))).thenReturn(List.of());
        lenient().when(homeNextEpisodeAssembler.assemble(any(), any())).thenReturn(List.of());
        lenient().when(top5EntryRepository.findByUserIdAndTypeWithContentOrderByPositionAsc(any(), any()))
                .thenReturn(List.of());
        lenient().when(diaryEntryMapper.diaryEntryToResponseDto(any(), anyBoolean()))
                .thenAnswer(invocation -> buildDiaryEntryResponseDto());
        lenient().when(userContentPosterService.findSeriesPosters(any(), any()))
                .thenReturn(Map.of());
        lenient().when(userContentPosterService.findByUserAndContentIds(any(), any()))
                .thenReturn(Map.of());
    }

    @Test
    @DisplayName("[getSummary] Should Delegate To The Profile Reader")
    void shouldDelegateToTheProfileReader() {
        SummaryResponseDTO expected = new SummaryResponseDTO(null, null, List.of(), null, List.of(),
                List.of(), List.of(), List.of());
        when(profileSummaryReader.read(lucasId, lucasId, ContentType.MOVIE)).thenReturn(expected);

        assertThat(summaryService.getSummary(lucasId, lucasId, ContentType.MOVIE)).isSameAs(expected);
        verify(profileSummaryReader).read(lucasId, lucasId, ContentType.MOVIE);
    }

    // ---------- getHomeSummary ----------

    @Test
    @DisplayName("[HomeSummaryResponseDTO] Should Preserve Legacy Recently Watched Rows")
    void shouldPreserveLegacyRecentlyWatchedRowsWhenUsingCompatibilityConstructor() {
        HomeRecentlyWatchedDTO recentlyWatched = new HomeRecentlyWatchedDTO(
                UUID.randomUUID(), null, 8, LocalDate.of(2026, 10, 8), null, List.of());

        HomeSummaryResponseDTO result = new HomeSummaryResponseDTO(
                0L, 0L, 0L, 0L, List.of(), List.of(), List.of(), List.of(), List.of(recentlyWatched));

        assertThat(result.recentlyWatched()).containsExactly(recentlyWatched);
    }

    @Test
    @DisplayName("[getHomeSummary] Should Throw NotFoundException - When User Does Not Exist")
    void shouldThrowNotFoundExceptionWhenUserDoesNotExistForHomeSummary() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> summaryService.getHomeSummary(lucasId, lucasId))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("User not found");

        verifyNoInteractions(diaryEntryRepository);
    }

    @Test
    @DisplayName("[getHomeSummary] Should Throw ForbiddenException - When Target Profile Is Private And Viewer Is Not An Accepted Follower")
    void shouldThrowForbiddenExceptionWhenTargetProfileIsPrivateForHomeSummary() {
        lucas.setIsProfilePublic(false);
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(followerRepository.existsByFollowerIdAndFollowedIdAndStatus(marinaId, lucasId, FollowStatus.ACCEPTED))
                .thenReturn(false);

        assertThatThrownBy(() -> summaryService.getHomeSummary(marinaId, lucasId))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("This user profile is private");
    }

    @Test
    void shouldLoadUpcomingReleasesForTheViewerNotTheTargetProfile() {
        CalendarEventDTO event = new CalendarEventDTO(
                LocalDate.of(2026, 9, 20),
                CalendarEventType.MOVIE,
                ReleaseStatus.UPCOMING,
                WatchStatus.UNWATCHED,
                Set.of(CalendarSource.WATCHLIST),
                new MovieCalendarContentDTO("550", "Movie 550", "/movie-550.jpg"));
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(calendarService.getUpcoming(marinaId, 6)).thenReturn(List.of(event));

        HomeSummaryResponseDTO result = summaryService.getHomeSummary(marinaId, lucasId);

        assertThat(result.upcomingReleases()).containsExactly(event);
        verify(calendarService).getUpcoming(marinaId, 6);
    }

    @Test
    void shouldReturnEmptyUpcomingReleasesWhenCalendarIsUnavailable() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(calendarService.getUpcoming(lucasId, 6))
                .thenThrow(new TmdbUnavailableException("TMDB is currently unavailable"));

        HomeSummaryResponseDTO result = summaryService.getHomeSummary(lucasId, lucasId);

        assertThat(result.upcomingReleases()).isEmpty();
    }

    @Test
    @DisplayName("[getHomeSummary] Should Return Totals, Next Episodes, Rolling 30-Day Stats And Genre Counts From The Repository")
    void shouldReturnTotalsNextEpisodesRollingStatsAndGenreCountsForHomeSummary() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(homeWatchAggregate.getTotalMinutesWatchedMovies()).thenReturn(6000L);
        when(homeWatchAggregate.getTotalMinutesWatchedEpisodes()).thenReturn(3000L);
        when(homeWatchAggregate.getTotalMoviesWatched()).thenReturn(42L);
        when(homeWatchAggregate.getTotalDistinctMoviesWatched()).thenReturn(40L);
        when(homeWatchAggregate.getTotalEpisodesWatched()).thenReturn(128L);
        SeriesInProgressResponseDTO row = new SeriesInProgressResponseDTO(
                "1399", 8, 6, LocalDate.of(2024, 5, 1), 3L, 12, 25.0);
        when(diaryEntryService.getSeriesInProgress(eq(lucasId), eq(lucasId), any(), any()))
                .thenReturn(new PageImpl<>(List.of(row)));
        when(homeNextEpisodeAssembler.assemble(eq(lucas), any()))
                .thenReturn(List.of(new SeriesInProgressPreviewDTO(
                        "1399", 8, 6, LocalDate.of(2024, 5, 1), 3L, 12, 25.0)));
        when(diaryEntryRepository.countByUserIdAndWatchedDateBetween(eq(lucasId), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(dailyWatchCount(LocalDate.of(2024, 5, 1), 3)));
        when(diaryEntryRepository.countEntriesByGenreAndUserIdForMoviesAndWatchedDateBetween(
                eq(lucasId), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(genreCount("Action", 2)));
        when(diaryEntryRepository.countEpisodeEntriesByGenreAndUserIdForSeriesAndWatchedDateBetween(
                eq(lucasId), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(genreCount("Drama", 5)));

        HomeSummaryResponseDTO result = summaryService.getHomeSummary(lucasId, lucasId);

        assertThat(result.totalMinutesWatchedMovies()).isEqualTo(6000L);
        assertThat(result.totalMinutesWatchedEpisodes()).isEqualTo(3000L);
        assertThat(result.totalMoviesWatched()).isEqualTo(42L);
        assertThat(result.totalDistinctMoviesWatched()).isEqualTo(40L);
        assertThat(result.totalEpisodesWatched()).isEqualTo(128L);
        assertThat(result.nextEpisodes()).containsExactly(new SeriesInProgressPreviewDTO(
                "1399", 8, 6, LocalDate.of(2024, 5, 1), 3L, 12, 25.0));
        verify(diaryEntryService).getSeriesInProgress(eq(lucasId), eq(lucasId), eq(1), eq(4));
        assertThat(result.watchCountByDayLast30Days()).containsExactly(new DailyWatchCountDTO(LocalDate.of(2024, 5, 1), 3));
        assertThat(result.genreCountsMoviesLast30Days()).containsExactly(new GenreCountDTO("Action", 2));
        assertThat(result.genreCountsEpisodesLast30Days()).containsExactly(new GenreCountDTO("Drama", 5));
    }

    @Test
    @DisplayName("[getHomeSummary] Should Merge Recent Movies And Episodes By CreatedAt Descending, Capped At Four")
    void shouldMergeRecentMoviesAndEpisodesByCreatedAtDescendingForHomeSummary() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        Content movieContent = buildContent("100", ContentType.MOVIE);
        Content episodeContent = buildContent("1399", ContentType.EPISODE);
        LocalDateTime now = LocalDateTime.now();
        DiaryEntry oldestMovie = buildDiaryEntry(movieContent, now.minusDays(3));
        DiaryEntry newestMovie = buildDiaryEntry(movieContent, now.minusHours(1));
        DiaryEntry oldestEpisode = buildDiaryEntry(episodeContent, now.minusDays(2));
        DiaryEntry newestEpisode = buildDiaryEntry(episodeContent, now);

        when(diaryEntryRepository.findRecentHomeEntries(eq(lucasId), any(), any()))
                .thenReturn(List.of(newestEpisode, newestMovie, oldestEpisode, oldestMovie));
        HomeSummaryResponseDTO result = summaryService.getHomeSummary(lucasId, lucasId);

        assertThat(result.recentlyWatched()).extracting(HomeRecentlyWatchedDTO::id)
                .containsExactly(newestEpisode.getId(), newestMovie.getId(), oldestEpisode.getId(), oldestMovie.getId());
    }

    @Test
    @DisplayName("[getHomeSummary] Should Enrich Recently Watched With The Profile Owner Poster In One Batch")
    void shouldEnrichRecentlyWatchedWithTheProfileOwnerPosterInOneBatch() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        Content movieContent = buildContent("100", ContentType.MOVIE);
        Content episodeContent = buildContent("1399", ContentType.EPISODE);
        LocalDateTime now = LocalDateTime.now();
        DiaryEntry movieEntry = buildDiaryEntry(movieContent, now.minusHours(1));
        DiaryEntry episodeEntry = buildDiaryEntry(episodeContent, now);
        when(diaryEntryRepository.findRecentHomeEntries(eq(lucasId), any(), any()))
                .thenReturn(List.of(episodeEntry, movieEntry));
        ContentCardDTO movieCard = cardFor(movieContent, "Fight Club", "https://image.tmdb.org/t/p/w342/lucas-movie.png");
        ContentCardDTO episodeCard = cardFor(episodeContent, "Pilot", "https://image.tmdb.org/t/p/w342/lucas-episode.png");
        ContentRefDTO socialContent = new ContentRefDTO(movieContent.getId(), movieContent.getTmdbId(),
                movieContent.getType(), movieContent.getSeriesTmdbId(), movieContent.getSeasonNumber(),
                movieContent.getEpisodeNumber(), false, false, now, now);
        FeedItemDTO socialActivity = new FeedItemDTO(
                FeedEventType.DIARY_ENTRY, UUID.randomUUID(),
                new UserPreviewDTO(lucasId, "lucas", "Lucas", "https://default-image.png", true),
                socialContent, null, 8, "review", 1, false, List.of(), null, null, now);
        when(feedService.getFeed(eq(lucasId), any(), eq(3)))
                .thenReturn(new CursorPageResponseDTO<>(List.of(socialActivity), 3, null, false));
        when(contentCardAssembler.assemble(anyCollection(), any(ContentCardContext.class), anySet()))
                .thenReturn(Map.of(ContentCoordinate.from(movieContent), movieCard,
                        ContentCoordinate.from(episodeContent), episodeCard));
        HomeSummaryResponseDTO result = summaryService.getHomeSummary(lucasId, lucasId);

        assertThat(result.recentlyWatched()).extracting(HomeRecentlyWatchedDTO::customPosterUrl)
                .containsExactly(
                        "https://image.tmdb.org/t/p/w342/lucas-episode.png",
                        "https://image.tmdb.org/t/p/w342/lucas-movie.png");

        JsonNode json = serialize(result);
        JsonNode recentlyWatchedCard = json.get("recentlyWatched").get(0).get("card");
        assertThat(recentlyWatchedCard.get("title").asString()).isEqualTo("Pilot");
        assertThat(recentlyWatchedCard.get("stats").isNull()).isTrue();
        assertThat(recentlyWatchedCard.get("viewerState").isNull()).isTrue();
        assertThat(json.get("socialActivities").get(0).get("card").get("title").asString())
                .isEqualTo("Fight Club");

        ArgumentCaptor<ContentCardContext> context = ArgumentCaptor.forClass(ContentCardContext.class);
        ArgumentCaptor<Set<ContentCardFieldSet>> fields = ArgumentCaptor.forClass(Set.class);
        verify(contentCardAssembler).assemble(anyCollection(), context.capture(), fields.capture());
        assertThat(context.getValue().posterUserId()).isEqualTo(lucasId);
        assertThat(context.getValue().viewerId()).isNull();
        assertThat(fields.getValue()).containsExactlyInAnyOrder(
                ContentCardFieldSet.BASIC_METADATA, ContentCardFieldSet.SOCIAL_METADATA);
    }

    private ContentCardDTO cardFor(Content content, String title, String customPosterUrl) {
        return new ContentCardDTO(content.getId(), content.getType(), content.getTmdbId(),
                content.getSeriesTmdbId(), content.getSeasonNumber(), content.getEpisodeNumber(), title,
                "/poster.jpg", customPosterUrl, LocalDate.of(1999, 10, 15), 1999,
                content.getRuntimeMinutes(), null, null, null, null, null, null,
                ContentPreviewStatus.AVAILABLE);
    }

    private JsonNode serialize(Object value) {
        try {
            return objectMapper.readTree(objectMapper.writeValueAsString(value));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    @Test
    @DisplayName("[getMonthInReview] Should Enrich Every Diary Response With The Profile Owner Poster In One Batch")
    void shouldEnrichEveryDiaryResponseWithTheProfileOwnerPosterInOneBatchForMonthInReview() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        DiaryEntry recent = buildDiaryEntry(buildContent("recent", ContentType.MOVIE), LocalDateTime.now());
        DiaryEntry top = buildDiaryEntry(buildContent("top", ContentType.MOVIE), LocalDateTime.now());
        DiaryEntry bottom = buildDiaryEntry(buildContent("bottom", ContentType.MOVIE), LocalDateTime.now());
        DiaryEntry first = buildDiaryEntry(buildContent("first", ContentType.MOVIE), LocalDateTime.now());
        DiaryEntry last = buildDiaryEntry(buildContent("last", ContentType.MOVIE), LocalDateTime.now());
        when(diaryEntryRepository.findByUserIdAndContentTypeAndWatchedDateBetweenOrderByWatchedDateDesc(
                eq(lucasId), eq(ContentType.MOVIE), any(), any(), any())).thenReturn(List.of(recent));
        when(diaryEntryRepository.findTopRatedByUserIdAndContentTypeAndWatchedDateBetween(
                eq(lucasId), eq(ContentType.MOVIE), any(), any(), any())).thenReturn(List.of(top));
        when(diaryEntryRepository.findBottomRatedByUserIdAndContentTypeAndWatchedDateBetween(
                eq(lucasId), eq(ContentType.MOVIE), any(), any(), any())).thenReturn(List.of(bottom));
        when(diaryEntryRepository.findEarliestWatchedEntriesByUserIdAndContentTypeAndWatchedDateBetween(
                eq(lucasId), eq(ContentType.MOVIE), any(), any(), any())).thenReturn(List.of(first));
        when(diaryEntryRepository.findLatestWatchedEntriesByUserIdAndContentTypeAndWatchedDateBetween(
                eq(lucasId), eq(ContentType.MOVIE), any(), any(), any())).thenReturn(List.of(last));
        when(diaryEntryMapper.diaryEntryToResponseDto(any(DiaryEntry.class), eq(false)))
                .thenAnswer(invocation -> buildDiaryEntryResponseDto(invocation.getArgument(0)));
        when(userContentPosterService.findByUserAndContentIds(eq(lucasId), any())).thenReturn(Map.of(
                recent.getContent().getId(), "https://image.tmdb.org/t/p/w342/recent.png",
                top.getContent().getId(), "https://image.tmdb.org/t/p/w342/top.png",
                bottom.getContent().getId(), "https://image.tmdb.org/t/p/w342/bottom.png",
                first.getContent().getId(), "https://image.tmdb.org/t/p/w342/first.png",
                last.getContent().getId(), "https://image.tmdb.org/t/p/w342/last.png"));

        MonthInReviewResponseDTO result = summaryService.getMonthInReview(
                lucasId, lucasId, ContentType.MOVIE, YearMonth.of(2026, 8));

        assertThat(result.recentWatched().getFirst().customPosterUrl()).isEqualTo("https://image.tmdb.org/t/p/w342/recent.png");
        assertThat(result.topRated().getFirst().customPosterUrl()).isEqualTo("https://image.tmdb.org/t/p/w342/top.png");
        assertThat(result.bottomRated().getFirst().customPosterUrl()).isEqualTo("https://image.tmdb.org/t/p/w342/bottom.png");
        assertThat(result.firstWatched().customPosterUrl()).isEqualTo("https://image.tmdb.org/t/p/w342/first.png");
        assertThat(result.lastWatched().customPosterUrl()).isEqualTo("https://image.tmdb.org/t/p/w342/last.png");
        verify(userContentPosterService).findByUserAndContentIds(eq(lucasId), any());
    }

    @Test
    @DisplayName("[getYearInReview] Should Enrich Rankings And First Last Diary Entries With The Owner Poster")
    void shouldEnrichRankingsAndFirstLastDiaryEntriesWithTheOwnerPosterForYearInReview() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        DiaryEntry top = buildDiaryEntry(buildContent("top", ContentType.MOVIE), LocalDateTime.now());
        DiaryEntry bottom = buildDiaryEntry(buildContent("bottom", ContentType.MOVIE), LocalDateTime.now());
        DiaryEntry first = buildDiaryEntry(buildContent("first", ContentType.MOVIE), LocalDateTime.now());
        DiaryEntry last = buildDiaryEntry(buildContent("last", ContentType.MOVIE), LocalDateTime.now());
        when(diaryEntryRepository.findTopRatedByUserIdAndContentTypeAndWatchedDateBetween(
                eq(lucasId), eq(ContentType.MOVIE), any(), any(), any())).thenReturn(List.of(top));
        when(diaryEntryRepository.findBottomRatedByUserIdAndContentTypeAndWatchedDateBetween(
                eq(lucasId), eq(ContentType.MOVIE), any(), any(), any())).thenReturn(List.of(bottom));
        when(diaryEntryRepository.findEarliestWatchedEntriesByUserIdAndContentTypeAndWatchedDateBetween(
                eq(lucasId), eq(ContentType.MOVIE), any(), any(), any())).thenReturn(List.of(first));
        when(diaryEntryRepository.findLatestWatchedEntriesByUserIdAndContentTypeAndWatchedDateBetween(
                eq(lucasId), eq(ContentType.MOVIE), any(), any(), any())).thenReturn(List.of(last));
        when(diaryEntryMapper.diaryEntryToResponseDto(any(DiaryEntry.class), eq(false)))
                .thenAnswer(invocation -> buildDiaryEntryResponseDto(invocation.getArgument(0)));
        when(userContentPosterService.findByUserAndContentIds(eq(lucasId), any())).thenReturn(Map.of(
                top.getContent().getId(), "https://image.tmdb.org/t/p/w342/top.png",
                bottom.getContent().getId(), "https://image.tmdb.org/t/p/w342/bottom.png",
                first.getContent().getId(), "https://image.tmdb.org/t/p/w342/first.png",
                last.getContent().getId(), "https://image.tmdb.org/t/p/w342/last.png"));

        YearInReviewResponseDTO result = summaryService.getYearInReview(lucasId, lucasId, ContentType.MOVIE, 2026);

        assertThat(result.topRated().getFirst().customPosterUrl()).isEqualTo("https://image.tmdb.org/t/p/w342/top.png");
        assertThat(result.bottomRated().getFirst().customPosterUrl()).isEqualTo("https://image.tmdb.org/t/p/w342/bottom.png");
        assertThat(result.firstWatched().customPosterUrl()).isEqualTo("https://image.tmdb.org/t/p/w342/first.png");
        assertThat(result.lastWatched().customPosterUrl()).isEqualTo("https://image.tmdb.org/t/p/w342/last.png");
        verify(userContentPosterService).findByUserAndContentIds(eq(lucasId), any());
    }

    @Test
    @DisplayName("[getMonthInReview] Should Resolve One Card For Repeated Ranking Coordinates And Preserve Totals")
    void shouldResolveOneCardForRepeatedRankingCoordinatesAndPreserveTotalsForMonthInReview() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        Content movie = buildContent("550", ContentType.MOVIE);
        movie.setRuntimeMinutes(139);
        DiaryEntry entry = buildDiaryEntry(movie, LocalDateTime.now());
        entry.setScore(9);
        when(diaryEntryRepository.findByUserIdAndContentTypeAndWatchedDateBetweenOrderByWatchedDateDesc(
                eq(lucasId), eq(ContentType.MOVIE), any(), any(), any())).thenReturn(List.of(entry));
        when(diaryEntryRepository.findTopRatedByUserIdAndContentTypeAndWatchedDateBetween(
                eq(lucasId), eq(ContentType.MOVIE), any(), any(), any())).thenReturn(List.of(entry));
        when(diaryEntryRepository.findBottomRatedByUserIdAndContentTypeAndWatchedDateBetween(
                eq(lucasId), eq(ContentType.MOVIE), any(), any(), any())).thenReturn(List.of(entry));
        when(diaryEntryRepository.findEarliestWatchedEntriesByUserIdAndContentTypeAndWatchedDateBetween(
                eq(lucasId), eq(ContentType.MOVIE), any(), any(), any())).thenReturn(List.of(entry));
        when(diaryEntryRepository.findLatestWatchedEntriesByUserIdAndContentTypeAndWatchedDateBetween(
                eq(lucasId), eq(ContentType.MOVIE), any(), any(), any())).thenReturn(List.of(entry));
        when(diaryEntryRepository.countByUserIdAndContentTypeAndWatchedDateBetween(
                eq(lucasId), eq(ContentType.MOVIE), any(), any())).thenReturn(5L);
        when(diaryEntryRepository.sumRuntimeMinutesByUserIdAndContentTypeAndWatchedDateBetween(
                eq(lucasId), eq(ContentType.MOVIE), any(), any())).thenReturn(695L);
        when(diaryEntryMapper.diaryEntryToResponseDto(entry, false))
                .thenReturn(buildDiaryEntryResponseDto(entry));

        ContentCardDTO card = cardFor(movie, "Fight Club", "/fight-club.jpg");
        when(contentCardAssembler.assemble(anyCollection(), any(ContentCardContext.class), anySet()))
                .thenReturn(Map.of(ContentCoordinate.from(movie), card));

        MonthInReviewResponseDTO result = summaryService.getMonthInReview(
                lucasId, lucasId, ContentType.MOVIE, YearMonth.of(2026, 8));

        assertThat(result.watchCount()).isEqualTo(5L);
        assertThat(result.minutesWatched()).isEqualTo(695L);
        assertThat(result.recentWatched().getFirst().card()).isEqualTo(card);
        assertThat(result.topRated().getFirst().card()).isEqualTo(card);
        assertThat(result.bottomRated().getFirst().card()).isEqualTo(card);
        assertThat(result.firstWatched().card()).isEqualTo(card);
        assertThat(result.lastWatched().card()).isEqualTo(card);

        ArgumentCaptor<Collection<ContentCardSpec>> specs = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<Set<ContentCardFieldSet>> fields = ArgumentCaptor.forClass(Set.class);
        verify(contentCardAssembler).assemble(specs.capture(), any(ContentCardContext.class), fields.capture());
        assertThat(specs.getValue()).hasSize(1);
        assertThat(fields.getValue()).containsExactlyInAnyOrder(
                ContentCardFieldSet.BASIC_METADATA, ContentCardFieldSet.STATS);
        assertThat(fields.getValue()).doesNotContain(ContentCardFieldSet.VIEWER_STATE);
    }

    @Test
    @DisplayName("[getMonthInReview] Should Keep The Ranking Row And Totals When Card Metadata Is Partial")
    void shouldKeepTheRankingRowAndTotalsWhenCardMetadataIsPartialForMonthInReview() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        Content movie = buildContent("partial-month", ContentType.MOVIE);
        DiaryEntry entry = buildDiaryEntry(movie, LocalDateTime.now());
        when(diaryEntryRepository.findByUserIdAndContentTypeAndWatchedDateBetweenOrderByWatchedDateDesc(
                eq(lucasId), eq(ContentType.MOVIE), any(), any(), any())).thenReturn(List.of(entry));
        when(diaryEntryRepository.countByUserIdAndContentTypeAndWatchedDateBetween(
                eq(lucasId), eq(ContentType.MOVIE), any(), any())).thenReturn(3L);
        when(diaryEntryRepository.sumRuntimeMinutesByUserIdAndContentTypeAndWatchedDateBetween(
                eq(lucasId), eq(ContentType.MOVIE), any(), any())).thenReturn(210L);
        when(diaryEntryMapper.diaryEntryToResponseDto(entry, false))
                .thenReturn(buildDiaryEntryResponseDto(entry));
        ContentCardDTO partialCard = new ContentCardDTO(movie.getId(), ContentType.MOVIE, movie.getTmdbId(),
                null, null, null, "Partial", null, null, null, null, null,
                null, null, null, null, null, null, ContentPreviewStatus.PARTIAL);
        when(contentCardAssembler.assemble(anyCollection(), any(ContentCardContext.class), anySet()))
                .thenReturn(Map.of(ContentCoordinate.from(movie), partialCard));

        MonthInReviewResponseDTO result = summaryService.getMonthInReview(
                lucasId, lucasId, ContentType.MOVIE, YearMonth.of(2026, 8));

        assertThat(result.recentWatched()).hasSize(1);
        assertThat(result.recentWatched().getFirst().card().previewStatus())
                .isEqualTo(ContentPreviewStatus.PARTIAL);
        assertThat(result.watchCount()).isEqualTo(3L);
        assertThat(result.minutesWatched()).isEqualTo(210L);

        ArgumentCaptor<Set<ContentCardFieldSet>> fields = ArgumentCaptor.forClass(Set.class);
        verify(contentCardAssembler).assemble(anyCollection(), any(ContentCardContext.class), fields.capture());
        assertThat(fields.getValue()).containsExactly(ContentCardFieldSet.BASIC_METADATA);
    }

    @Test
    @DisplayName("[getMonthInReview] Should Resolve The Series Ranking Card With The Selected Type")
    void shouldResolveTheSeriesRankingCardWithTheSelectedTypeForMonthInReview() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        Content series = buildContent("1399", ContentType.SERIES);
        DiaryEntry recent = buildDiaryEntry(series, LocalDateTime.now());
        when(diaryEntryRepository.findByUserIdAndContentTypeAndWatchedDateBetweenOrderByWatchedDateDesc(
                eq(lucasId), eq(ContentType.SERIES), any(), any(), any())).thenReturn(List.of(recent));
        when(diaryEntryMapper.diaryEntryToResponseDto(recent, false))
                .thenReturn(buildDiaryEntryResponseDto(recent));
        UUID seriesContentId = UUID.randomUUID();
        when(diaryEntryRepository.sumRuntimeMinutesByUserIdGroupBySeriesTmdbIdAndWatchedDateBetween(
                eq(lucasId), any(), any(), any())).thenReturn(List.of(seriesRuntime("1399", seriesContentId, 320L)));

        ContentCardDTO card = cardFor(series, "Breaking Bad", "/breaking-bad.jpg");
        when(contentCardAssembler.assemble(anyCollection(), any(ContentCardContext.class), anySet()))
                .thenReturn(Map.of(ContentCoordinate.from(series), card));

        MonthInReviewResponseDTO result = summaryService.getMonthInReview(
                lucasId, lucasId, ContentType.SERIES, YearMonth.of(2026, 8));

        assertThat(result.topSeriesByWatchTime()).hasSize(1);
        assertThat(serialize(result).get("topSeriesByWatchTime").get(0).get("card")).isNotNull();
        assertThat(result.recentWatched().getFirst().card()).isEqualTo(card);
        verify(diaryEntryRepository, never()).findByUserIdAndContentTypeAndWatchedDateBetweenOrderByWatchedDateDesc(
                eq(lucasId), eq(ContentType.EPISODE), any(), any(), any());
    }

    @Test
    @DisplayName("[getYearInReview] Should Resolve Ranking Cards Once And Preserve The Year Chart")
    void shouldResolveRankingCardsOnceAndPreserveTheYearChartForYearInReview() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        Content movie = buildContent("680", ContentType.MOVIE);
        movie.setRuntimeMinutes(181);
        DiaryEntry entry = buildDiaryEntry(movie, LocalDateTime.now());
        entry.setScore(10);
        when(diaryEntryRepository.findTopRatedByUserIdAndContentTypeAndWatchedDateBetween(
                eq(lucasId), eq(ContentType.MOVIE), any(), any(), any())).thenReturn(List.of(entry));
        when(diaryEntryRepository.findBottomRatedByUserIdAndContentTypeAndWatchedDateBetween(
                eq(lucasId), eq(ContentType.MOVIE), any(), any(), any())).thenReturn(List.of(entry));
        when(diaryEntryRepository.findEarliestWatchedEntriesByUserIdAndContentTypeAndWatchedDateBetween(
                eq(lucasId), eq(ContentType.MOVIE), any(), any(), any())).thenReturn(List.of(entry));
        when(diaryEntryRepository.findLatestWatchedEntriesByUserIdAndContentTypeAndWatchedDateBetween(
                eq(lucasId), eq(ContentType.MOVIE), any(), any(), any())).thenReturn(List.of(entry));
        when(diaryEntryRepository.findDistinctMovieContentByUserIdAndWatchedDateBetweenOrderByRuntimeDesc(
                eq(lucasId), any(), any(), any())).thenReturn(List.of(movie));
        when(diaryEntryRepository.countByUserIdAndContentTypeAndWatchedDateBetween(
                eq(lucasId), eq(ContentType.MOVIE), any(), any())).thenReturn(12L);
        when(diaryEntryRepository.sumRuntimeMinutesByUserIdAndContentTypeAndWatchedDateBetween(
                eq(lucasId), eq(ContentType.MOVIE), any(), any())).thenReturn(2172L);
        when(diaryEntryMapper.diaryEntryToResponseDto(entry, false))
                .thenReturn(buildDiaryEntryResponseDto(entry));
        ContentCardDTO card = cardFor(movie, "Inception", "/inception.jpg");
        when(contentCardAssembler.assemble(anyCollection(), any(ContentCardContext.class), anySet()))
                .thenReturn(Map.of(ContentCoordinate.from(movie), card));

        YearInReviewResponseDTO result = summaryService.getYearInReview(
                lucasId, lucasId, ContentType.MOVIE, 2026);

        assertThat(result.watchCount()).isEqualTo(12L);
        assertThat(result.minutesWatched()).isEqualTo(2172L);
        assertThat(result.topRated().getFirst().card()).isEqualTo(card);
        assertThat(result.bottomRated().getFirst().card()).isEqualTo(card);
        assertThat(result.firstWatched().card()).isEqualTo(card);
        assertThat(result.lastWatched().card()).isEqualTo(card);
        assertThat(serialize(result).get("longestWatched").get(0).get("card")).isNotNull();

        ArgumentCaptor<Collection<ContentCardSpec>> specs = ArgumentCaptor.forClass(Collection.class);
        verify(contentCardAssembler).assemble(specs.capture(), any(ContentCardContext.class), anySet());
        assertThat(specs.getValue()).hasSize(1);
    }

    @Test
    @DisplayName("[getAllTimeStats] Should Enrich Both Rating Rankings With The Owner Poster In One Batch")
    void shouldEnrichBothRatingRankingsWithTheOwnerPosterInOneBatchForAllTimeStats() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        DiaryEntry top = buildDiaryEntry(buildContent("top", ContentType.MOVIE), LocalDateTime.now());
        DiaryEntry bottom = buildDiaryEntry(buildContent("bottom", ContentType.MOVIE), LocalDateTime.now());
        when(diaryEntryRepository.findTopRatedByUserId(eq(lucasId), any())).thenReturn(List.of(top));
        when(diaryEntryRepository.findBottomRatedByUserId(eq(lucasId), any())).thenReturn(List.of(bottom));
        when(diaryEntryMapper.diaryEntryToResponseDto(any(DiaryEntry.class), eq(false)))
                .thenAnswer(invocation -> buildDiaryEntryResponseDto(invocation.getArgument(0)));
        when(userContentPosterService.findByUserAndContentIds(eq(lucasId), any())).thenReturn(Map.of(
                top.getContent().getId(), "https://image.tmdb.org/t/p/w342/top.png",
                bottom.getContent().getId(), "https://image.tmdb.org/t/p/w342/bottom.png"));

        AllTimeStatsResponseDTO result = summaryService.getAllTimeStats(lucasId, lucasId);

        assertThat(result.topRated().getFirst().customPosterUrl()).isEqualTo("https://image.tmdb.org/t/p/w342/top.png");
        assertThat(result.bottomRated().getFirst().customPosterUrl()).isEqualTo("https://image.tmdb.org/t/p/w342/bottom.png");
        verify(userContentPosterService).findByUserAndContentIds(eq(lucasId), any());
    }

    private DiaryEntryRepository.SeriesInProgress seriesInProgress(
            String seriesTmdbId, Integer maxSeasonNumber, Integer maxEpisodeNumber, LocalDate lastWatchedDate) {
        return seriesInProgress(seriesTmdbId, null, maxSeasonNumber, maxEpisodeNumber, lastWatchedDate);
    }

    private DiaryEntryRepository.SeriesInProgress seriesInProgress(
            String seriesTmdbId, Long watchedEpisodeCount, Integer maxSeasonNumber,
            Integer maxEpisodeNumber, LocalDate lastWatchedDate) {
        return new DiaryEntryRepository.SeriesInProgress() {
            @Override
            public String getSeriesTmdbId() {
                return seriesTmdbId;
            }

            @Override
            public Long getWatchedEpisodeCount() {
                return watchedEpisodeCount;
            }

            @Override
            public Integer getMaxSeasonNumber() {
                return maxSeasonNumber;
            }

            @Override
            public Integer getMaxEpisodeNumber() {
                return maxEpisodeNumber;
            }

            @Override
            public LocalDate getLastWatchedDate() {
                return lastWatchedDate;
            }
        };
    }

    private DiaryEntryRepository.DailyWatchCount dailyWatchCount(LocalDate watchedDate, long count) {
        return new DiaryEntryRepository.DailyWatchCount() {
            @Override
            public LocalDate getWatchedDate() {
                return watchedDate;
            }

            @Override
            public long getCount() {
                return count;
            }
        };
    }

    @Test
    @DisplayName("[getMonthInReview] Should Throw NotFoundException - When User Does Not Exist")
    void shouldThrowNotFoundExceptionWhenUserDoesNotExistForMonthInReview() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> summaryService.getMonthInReview(lucasId, lucasId, ContentType.MOVIE, YearMonth.of(2026, 8)))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("User not found");
    }

    @Test
    @DisplayName("[getMonthInReview] Should Throw ForbiddenException - When Target Profile Is Private And Viewer Is Not An Accepted Follower")
    void shouldThrowForbiddenExceptionWhenTargetProfileIsPrivateForMonthInReview() {
        lucas.setIsProfilePublic(false);
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(followerRepository.existsByFollowerIdAndFollowedIdAndStatus(marinaId, lucasId, FollowStatus.ACCEPTED))
                .thenReturn(false);

        assertThatThrownBy(() -> summaryService.getMonthInReview(marinaId, lucasId, ContentType.MOVIE, YearMonth.of(2026, 8)))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("This user profile is private");
    }

    @Test
    @DisplayName("[getMonthInReview] Should Throw BadRequestException - When Type Is Not MOVIE Or SERIES")
    void shouldThrowBadRequestExceptionWhenTypeIsInvalidForMonthInReview() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));

        assertThatThrownBy(() -> summaryService.getMonthInReview(lucasId, lucasId, ContentType.EPISODE, YearMonth.of(2026, 8)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("type must be one of: MOVIE, SERIES");
    }

    @Test
    @DisplayName("[getMonthInReview] Should Throw BadRequestException - When Month Is Null")
    void shouldThrowBadRequestExceptionWhenMonthIsNull() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));

        assertThatThrownBy(() -> summaryService.getMonthInReview(lucasId, lucasId, ContentType.MOVIE, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("month must be provided");
    }

    @Test
    @DisplayName("[getMonthInReview] Should Only Populate TopLongestMovies - When Type Is MOVIE")
    void shouldOnlyPopulateTopLongestMoviesWhenTypeIsMovieForMonthInReview() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        Content movie = buildContent("100", ContentType.MOVIE);
        movie.setRuntimeMinutes(120);
        when(diaryEntryRepository.findDistinctMovieContentByUserIdAndWatchedDateBetweenOrderByRuntimeDesc(eq(lucasId), any(), any(), any()))
                .thenReturn(List.of(movie));
        when(contentMapper.contentToContentRefDto(movie))
                .thenReturn(new ContentRefDTO(movie.getId(), "100", ContentType.MOVIE, null, null, null, null, null, null, null, 120, null));

        MonthInReviewResponseDTO result = summaryService.getMonthInReview(lucasId, lucasId, ContentType.MOVIE, YearMonth.of(2026, 8));

        assertThat(result.topLongestMovies()).hasSize(1);
        assertThat(result.topSeriesByWatchTime()).isEmpty();
    }

    @Test
    @DisplayName("[getMonthInReview] Should Add Top Longest Movie Cards Without Changing The Legacy Raw Ranking")
    void shouldAddTopLongestMovieCardsWithoutChangingTheLegacyRawRanking() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        Content movie = buildContent("100", ContentType.MOVIE);
        movie.setRuntimeMinutes(120);
        ContentRefDTO rawReference = new ContentRefDTO(movie.getId(), "100", ContentType.MOVIE,
                null, null, null, null, null, null, null, 120, null);
        when(diaryEntryRepository.findDistinctMovieContentByUserIdAndWatchedDateBetweenOrderByRuntimeDesc(
                eq(lucasId), any(), any(), any())).thenReturn(List.of(movie));
        when(contentMapper.contentToContentRefDto(movie)).thenReturn(rawReference);
        ContentCardDTO card = cardFor(movie, "Long movie", "/long-movie.jpg");
        when(contentCardAssembler.assemble(anyCollection(), any(ContentCardContext.class), anySet()))
                .thenReturn(Map.of(ContentCoordinate.from(movie), card));

        MonthInReviewResponseDTO result = summaryService.getMonthInReview(
                lucasId, lucasId, ContentType.MOVIE, YearMonth.of(2026, 8));

        assertThat(result.topLongestMovies()).containsExactly(rawReference);
        assertThat(result.topSeriesByWatchTime()).isEmpty();
        JsonNode json = serialize(result);
        assertThat(json.get("topLongestMovieCards")).isNotNull();
        assertThat(json.get("topLongestMovieCards").get(0).get("title").asString())
                .isEqualTo("Long movie");

        ArgumentCaptor<Collection<ContentCardSpec>> specs = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<Set<ContentCardFieldSet>> fields = ArgumentCaptor.forClass(Set.class);
        verify(contentCardAssembler).assemble(specs.capture(), any(ContentCardContext.class), fields.capture());
        assertThat(specs.getValue()).hasSize(1);
        assertThat(specs.getValue().iterator().next().coordinate()).isEqualTo(ContentCoordinate.from(movie));
        assertThat(specs.getValue().iterator().next().runtimeMinutes()).isEqualTo(120);
        assertThat(fields.getValue()).containsExactly(ContentCardFieldSet.BASIC_METADATA);
    }

    @Test
    @DisplayName("[getMonthInReview] Should Only Populate TopSeriesByWatchTime - When Type Is SERIES")
    void shouldOnlyPopulateTopSeriesByWatchTimeWhenTypeIsSeriesForMonthInReview() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        UUID seriesContentId = UUID.randomUUID();
        when(diaryEntryRepository.sumRuntimeMinutesByUserIdGroupBySeriesTmdbIdAndWatchedDateBetween(eq(lucasId), any(), any(), any()))
                .thenReturn(List.of(seriesRuntime("1399", seriesContentId, 320L)));

        MonthInReviewResponseDTO result = summaryService.getMonthInReview(lucasId, lucasId, ContentType.SERIES, YearMonth.of(2026, 8));

        assertThat(result.topSeriesByWatchTime()).hasSize(1);
        assertThat(result.topSeriesByWatchTime().getFirst().seriesTmdbId()).isEqualTo("1399");
        assertThat(result.topSeriesByWatchTime().getFirst().contentId()).isEqualTo(seriesContentId);
        assertThat(result.topLongestMovies()).isEmpty();
    }

    @Test
    @DisplayName("[getMonthInReview] Should Promote Top5 Members First - When Ranking TopRated")
    void shouldPromoteTop5MembersFirstWhenRankingTopRatedForMonthInReview() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        Content contentA = buildContent("a", ContentType.MOVIE);
        Content contentB = buildContent("b", ContentType.MOVIE);
        DiaryEntry entryA = buildDiaryEntry(contentA, LocalDateTime.now());
        DiaryEntry entryB = buildDiaryEntry(contentB, LocalDateTime.now());
        when(diaryEntryRepository.findTopRatedByUserIdAndContentTypeAndWatchedDateBetween(eq(lucasId), eq(ContentType.MOVIE), any(), any(), any()))
                .thenReturn(List.of(entryA, entryB));
        when(top5EntryRepository.findByUserIdAndTypeWithContentOrderByPositionAsc(lucasId, ContentType.MOVIE))
                .thenReturn(List.of(buildTop5Entry(contentB)));

        MonthInReviewResponseDTO result = summaryService.getMonthInReview(lucasId, lucasId, ContentType.MOVIE, YearMonth.of(2026, 8));

        assertThat(result.topRated()).hasSize(2);
    }

    @Test
    @DisplayName("[getMonthInReview] Should Keep BottomRated Ordered By Score Ascending - Even When A Top5 Entry Exists")
    void shouldKeepBottomRatedOrderedByScoreAscendingEvenWhenATop5EntryExists() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        Content lowRatedContent = buildContent("low", ContentType.MOVIE);
        Content highRatedContent = buildContent("high", ContentType.MOVIE);
        DiaryEntry lowRatedEntry = buildDiaryEntry(lowRatedContent, LocalDateTime.now());
        DiaryEntry highRatedEntry = buildDiaryEntry(highRatedContent, LocalDateTime.now());
        lowRatedEntry.setScore(2);
        highRatedEntry.setScore(9);
        when(diaryEntryRepository.findBottomRatedByUserIdAndContentTypeAndWatchedDateBetween(
                eq(lucasId), eq(ContentType.MOVIE), any(), any(), any()))
                .thenReturn(List.of(lowRatedEntry, highRatedEntry));
        when(top5EntryRepository.findByUserIdAndTypeWithContentOrderByPositionAsc(lucasId, ContentType.MOVIE))
                .thenReturn(List.of(buildTop5Entry(highRatedContent)));
        when(diaryEntryMapper.diaryEntryToResponseDto(any(DiaryEntry.class), anyBoolean()))
                .thenAnswer(invocation -> {
                    DiaryEntry entry = invocation.getArgument(0);
                    return new DiaryEntryResponseDTO(entry.getId(), lucasId, null, null, entry.getScore(),
                            entry.getWatchedDate(), 1, null, null, false, false, entry.getCreatedAt(),
                            entry.getUpdatedAt(), 0, false);
                });

        MonthInReviewResponseDTO result = summaryService.getMonthInReview(
                lucasId, lucasId, ContentType.MOVIE, YearMonth.of(2026, 8));

        assertThat(result.bottomRated()).extracting(DiaryEntryResponseDTO::score)
                .containsExactly(2, 9);
    }

    @Test
    @DisplayName("[getMonthInReview] Should Return Recent Watched Titles Without Episodes - When Type Is SERIES")
    void shouldReturnRecentWatchedTitlesWithoutEpisodesWhenTypeIsSeries() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        Content seriesContent = buildContent("1399", ContentType.SERIES);
        DiaryEntry seriesEntry = buildDiaryEntry(seriesContent, LocalDateTime.now());
        DiaryEntryResponseDTO seriesResponse = new DiaryEntryResponseDTO(
                seriesEntry.getId(), lucasId, null, null, null, LocalDate.of(2026, 8, 10), 1,
                null, null, false, false, seriesEntry.getCreatedAt(), seriesEntry.getUpdatedAt(), 0, false);
        when(diaryEntryRepository.findByUserIdAndContentTypeAndWatchedDateBetweenOrderByWatchedDateDesc(
                eq(lucasId), eq(ContentType.SERIES), any(), any(), any()))
                .thenReturn(List.of(seriesEntry));
        when(diaryEntryMapper.diaryEntryToResponseDto(seriesEntry, false)).thenReturn(seriesResponse);

        MonthInReviewResponseDTO result = summaryService.getMonthInReview(
                lucasId, lucasId, ContentType.SERIES, YearMonth.of(2026, 8));

        assertThat(result.recentWatched()).containsExactly(seriesResponse);
        verify(diaryEntryRepository).findByUserIdAndContentTypeAndWatchedDateBetweenOrderByWatchedDateDesc(
                lucasId, ContentType.SERIES, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), PageRequest.of(0, 6));
        verify(diaryEntryRepository, never()).findByUserIdAndContentTypeAndWatchedDateBetweenOrderByWatchedDateDesc(
                eq(lucasId), eq(ContentType.EPISODE), any(), any(), any());
    }

    @Test
    @DisplayName("[summary responses] Should Expose First And Last Watched As Diary Entries")
    void shouldExposeFirstAndLastWatchedAsDiaryEntries() {
        assertThat(recordComponentType(MonthInReviewResponseDTO.class, "firstWatched"))
                .isEqualTo(DiaryEntryResponseDTO.class);
        assertThat(recordComponentType(MonthInReviewResponseDTO.class, "lastWatched"))
                .isEqualTo(DiaryEntryResponseDTO.class);
        assertThat(recordComponentType(YearInReviewResponseDTO.class, "firstWatched"))
                .isEqualTo(DiaryEntryResponseDTO.class);
        assertThat(recordComponentType(YearInReviewResponseDTO.class, "lastWatched"))
                .isEqualTo(DiaryEntryResponseDTO.class);
    }

    @Test
    @DisplayName("[getMonthInReview] Should Return The First And Last Watched Entries With Their Dates")
    void shouldReturnFirstAndLastWatchedEntriesWithTheirDatesForMonthInReview() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        DiaryEntry firstEntry = buildDiaryEntry(buildContent("first", ContentType.MOVIE), LocalDateTime.now().minusDays(3));
        DiaryEntry lastEntry = buildDiaryEntry(buildContent("last", ContentType.MOVIE), LocalDateTime.now());
        firstEntry.setWatchedDate(LocalDate.of(2026, 8, 2));
        lastEntry.setWatchedDate(LocalDate.of(2026, 8, 28));
        when(diaryEntryRepository.findEarliestWatchedEntriesByUserIdAndContentTypeAndWatchedDateBetween(
                eq(lucasId), eq(ContentType.MOVIE), any(), any(), eq(PageRequest.of(0, 1))))
                .thenReturn(List.of(firstEntry));
        when(diaryEntryRepository.findLatestWatchedEntriesByUserIdAndContentTypeAndWatchedDateBetween(
                eq(lucasId), eq(ContentType.MOVIE), any(), any(), eq(PageRequest.of(0, 1))))
                .thenReturn(List.of(lastEntry));
        when(diaryEntryMapper.diaryEntryToResponseDto(firstEntry, false))
                .thenReturn(buildDiaryEntryResponseDto(firstEntry));
        when(diaryEntryMapper.diaryEntryToResponseDto(lastEntry, false))
                .thenReturn(buildDiaryEntryResponseDto(lastEntry));

        MonthInReviewResponseDTO result = summaryService.getMonthInReview(
                lucasId, lucasId, ContentType.MOVIE, YearMonth.of(2026, 8));

        assertThat(result.firstWatched().content().tmdbId()).isEqualTo("first");
        assertThat(result.firstWatched().watchedDate()).isEqualTo(LocalDate.of(2026, 8, 2));
        assertThat(result.lastWatched().content().tmdbId()).isEqualTo("last");
        assertThat(result.lastWatched().watchedDate()).isEqualTo(LocalDate.of(2026, 8, 28));
    }

    @Test
    @DisplayName("[getYearInReview] Should Return The First And Last Watched Entries With Their Dates")
    void shouldReturnFirstAndLastWatchedEntriesWithTheirDatesForYearInReview() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        DiaryEntry firstEntry = buildDiaryEntry(buildContent("1399", ContentType.EPISODE), LocalDateTime.now().minusDays(3));
        DiaryEntry lastEntry = buildDiaryEntry(buildContent("2468", ContentType.EPISODE), LocalDateTime.now());
        firstEntry.setWatchedDate(LocalDate.of(2026, 1, 10));
        lastEntry.setWatchedDate(LocalDate.of(2026, 12, 20));
        when(diaryEntryRepository.findEarliestWatchedEntriesByUserIdAndContentTypeAndWatchedDateBetween(
                eq(lucasId), eq(ContentType.EPISODE), any(), any(), eq(PageRequest.of(0, 1))))
                .thenReturn(List.of(firstEntry));
        when(diaryEntryRepository.findLatestWatchedEntriesByUserIdAndContentTypeAndWatchedDateBetween(
                eq(lucasId), eq(ContentType.EPISODE), any(), any(), eq(PageRequest.of(0, 1))))
                .thenReturn(List.of(lastEntry));
        when(diaryEntryMapper.diaryEntryToResponseDto(firstEntry, false))
                .thenReturn(buildDiaryEntryResponseDto(firstEntry));
        when(diaryEntryMapper.diaryEntryToResponseDto(lastEntry, false))
                .thenReturn(buildDiaryEntryResponseDto(lastEntry));

        YearInReviewResponseDTO result = summaryService.getYearInReview(
                lucasId, lucasId, ContentType.SERIES, 2026);

        assertThat(result.firstWatched().content().tmdbId()).isEqualTo("1399");
        assertThat(result.firstWatched().watchedDate()).isEqualTo(LocalDate.of(2026, 1, 10));
        assertThat(result.lastWatched().content().tmdbId()).isEqualTo("2468");
        assertThat(result.lastWatched().watchedDate()).isEqualTo(LocalDate.of(2026, 12, 20));
    }

    @Test
    @DisplayName("[getMonthInReview] Should Return Top Watch Companions Scoped By Type And Month")
    void shouldReturnTopWatchCompanionsForMonthInReview() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        User marina = buildUser(marinaId, true);
        when(watchCompanionRepository.countGroupedByCompanionUserIdAndContentTypeAndWatchedDateBetween(
                eq(lucasId), eq(ContentType.MOVIE), any(), any(), eq(PageRequest.of(0, 3))))
                .thenReturn(List.of(companionWatchCount(marinaId, 5L)));
        when(userRepository.findAllById(List.of(marinaId))).thenReturn(List.of(marina));
        when(userMapper.userToUserPreviewDto(marina)).thenReturn(new UserPreviewDTO(marinaId, "marina", null, true));

        MonthInReviewResponseDTO result = summaryService.getMonthInReview(lucasId, lucasId, ContentType.MOVIE, YearMonth.of(2026, 8));

        assertThat(result.topWatchCompanions()).hasSize(1);
        assertThat(result.topWatchCompanions().getFirst().companion().id()).isEqualTo(marinaId);
        assertThat(result.topWatchCompanions().getFirst().watchCount()).isEqualTo(5L);
    }

    @Test
    @DisplayName("[getMonthInReview] Should Use The Distinct Series Genre Query - When Type Is SERIES")
    void shouldUseTheDistinctSeriesGenreQueryInMonthInReviewWhenTypeIsSeries() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(diaryEntryRepository.countDistinctTitlesByGenreAndUserIdForSeriesAndWatchedDateBetween(eq(lucasId), any(), any()))
                .thenReturn(List.of(genreCount("Sci-Fi", 2L)));

        MonthInReviewResponseDTO result = summaryService.getMonthInReview(lucasId, lucasId, ContentType.SERIES, YearMonth.of(2026, 8));

        assertThat(result.genreCounts()).containsExactly(new GenreCountDTO("Sci-Fi", 2L));
        verify(diaryEntryRepository, never()).countEntriesByGenreAndUserIdForMoviesAndWatchedDateBetween(any(), any(), any());
    }

    @Test
    @DisplayName("[getYearInReview] Should Return Top Watch Companions Scoped By Type And Year")
    void shouldReturnTopWatchCompanionsForYearInReview() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        User marina = buildUser(marinaId, true);
        when(watchCompanionRepository.countGroupedByCompanionUserIdAndContentTypeAndWatchedDateBetween(
                eq(lucasId), eq(ContentType.EPISODE), eq(LocalDate.of(2026, 1, 1)), eq(LocalDate.of(2026, 12, 31)), eq(PageRequest.of(0, 3))))
                .thenReturn(List.of(companionWatchCount(marinaId, 12L)));
        when(userRepository.findAllById(List.of(marinaId))).thenReturn(List.of(marina));
        when(userMapper.userToUserPreviewDto(marina)).thenReturn(new UserPreviewDTO(marinaId, "marina", null, true));

        YearInReviewResponseDTO result = summaryService.getYearInReview(lucasId, lucasId, ContentType.SERIES, 2026);

        assertThat(result.topWatchCompanions()).hasSize(1);
        assertThat(result.topWatchCompanions().getFirst().companion().id()).isEqualTo(marinaId);
        assertThat(result.topWatchCompanions().getFirst().watchCount()).isEqualTo(12L);
    }

    @Test
    @DisplayName("[getYearInReview] Should Throw NotFoundException - When User Does Not Exist")
    void shouldThrowNotFoundExceptionWhenUserDoesNotExistForYearInReview() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> summaryService.getYearInReview(lucasId, lucasId, ContentType.MOVIE, 2026))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("User not found");
    }

    @Test
    @DisplayName("[getYearInReview] Should Throw ForbiddenException - When Target Profile Is Private And Viewer Is Not An Accepted Follower")
    void shouldThrowForbiddenExceptionWhenTargetProfileIsPrivateForYearInReview() {
        lucas.setIsProfilePublic(false);
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(followerRepository.existsByFollowerIdAndFollowedIdAndStatus(marinaId, lucasId, FollowStatus.ACCEPTED))
                .thenReturn(false);

        assertThatThrownBy(() -> summaryService.getYearInReview(marinaId, lucasId, ContentType.MOVIE, 2026))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("This user profile is private");
    }

    @Test
    @DisplayName("[getYearInReview] Should Throw BadRequestException - When Year Is Null")
    void shouldThrowBadRequestExceptionWhenYearIsNull() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));

        assertThatThrownBy(() -> summaryService.getYearInReview(lucasId, lucasId, ContentType.MOVIE, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("year must be provided");
    }

    @Test
    @DisplayName("[getYearInReview] Should Compute Average Minutes Per Week As Seven Times The Daily Average")
    void shouldComputeAverageMinutesPerWeekAsSevenTimesTheDailyAverage() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(diaryEntryRepository.sumRuntimeMinutesByUserIdAndContentTypeAndWatchedDateBetween(eq(lucasId), eq(ContentType.MOVIE), any(), any()))
                .thenReturn(3650L);

        YearInReviewResponseDTO result = summaryService.getYearInReview(lucasId, lucasId, ContentType.MOVIE, 2026);

        assertThat(result.averageMinutesPerWeek()).isEqualTo(result.averageMinutesPerDay() * 7);
    }

    @Test
    @DisplayName("[getYearInReview] Should Use The Distinct Series Genre Query - When Type Is SERIES")
    void shouldUseTheDistinctSeriesGenreQueryInYearInReviewWhenTypeIsSeries() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(diaryEntryRepository.countDistinctTitlesByGenreAndUserIdForSeriesAndWatchedDateBetween(eq(lucasId), any(), any()))
                .thenReturn(List.of(genreCount("Sci-Fi", 4L)));

        YearInReviewResponseDTO result = summaryService.getYearInReview(lucasId, lucasId, ContentType.SERIES, 2026);

        assertThat(result.genreCounts()).containsExactly(new GenreCountDTO("Sci-Fi", 4L));
    }

    @Test
    @DisplayName("[getAllTimeStats] Should Throw NotFoundException - When User Does Not Exist")
    void shouldThrowNotFoundExceptionWhenUserDoesNotExistForAllTimeStats() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> summaryService.getAllTimeStats(lucasId, lucasId))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("User not found");
    }

    @Test
    @DisplayName("[getAllTimeStats] Should Throw ForbiddenException - When Target Profile Is Private And Viewer Is Not An Accepted Follower")
    void shouldThrowForbiddenExceptionWhenTargetProfileIsPrivateForAllTimeStats() {
        lucas.setIsProfilePublic(false);
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(followerRepository.existsByFollowerIdAndFollowedIdAndStatus(marinaId, lucasId, FollowStatus.ACCEPTED))
                .thenReturn(false);

        assertThatThrownBy(() -> summaryService.getAllTimeStats(marinaId, lucasId))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("This user profile is private");
    }

    @Test
    @DisplayName("[getAllTimeStats] Should Return Total Movie And Episode Counts From The Repository")
    void shouldReturnTotalMovieAndEpisodeCountsFromTheRepository() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(diaryEntryRepository.countByUserIdAndContentType(lucasId, ContentType.MOVIE)).thenReturn(42L);
        when(diaryEntryRepository.countByUserIdAndContentType(lucasId, ContentType.EPISODE)).thenReturn(128L);
        when(diaryEntryRepository.sumRuntimeMinutesByUserIdAndContentType(lucasId, ContentType.MOVIE)).thenReturn(6000L);
        when(diaryEntryRepository.sumRuntimeMinutesByUserIdAndContentType(lucasId, ContentType.EPISODE)).thenReturn(3000L);

        AllTimeStatsResponseDTO result = summaryService.getAllTimeStats(lucasId, lucasId);

        assertThat(result.totalMoviesWatched()).isEqualTo(42L);
        assertThat(result.totalEpisodesWatched()).isEqualTo(128L);
        assertThat(result.totalMinutesWatchedMovies()).isEqualTo(6000L);
        assertThat(result.totalMinutesWatchedEpisodes()).isEqualTo(3000L);
    }

    @Test
    @DisplayName("[getAllTimeStats] Should Add Cards To Legacy Rankings Without Changing Raw Fields")
    void shouldAddCardsToLegacyRankingsWithoutChangingRawFields() {
        Content movie = Content.builder()
                .id(UUID.randomUUID()).tmdbId("550").type(ContentType.MOVIE).runtimeMinutes(139)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        DiaryEntry topRatedEntry = DiaryEntry.builder()
                .id(UUID.randomUUID()).content(movie).score(9)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        DiaryEntry bottomRatedEntry = DiaryEntry.builder()
                .id(UUID.randomUUID()).content(movie).score(4)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        ContentRefDTO contentRef = new ContentRefDTO(
                movie.getId(), "550", ContentType.MOVIE, null, null, null, null, null,
                movie.getCreatedAt(), movie.getUpdatedAt());
        DiaryEntryRepository.ContentWatchCount mostLoggedRow = new DiaryEntryRepository.ContentWatchCount() {
            @Override
            public UUID getContentId() {
                return movie.getId();
            }

            @Override
            public Long getCount() {
                return 3L;
            }
        };
        ContentCardDTO card = new ContentCardDTO(
                movie.getId(), ContentType.MOVIE, "550", null, null, null,
                "Fight Club", "/fight-club.jpg", "https://image.tmdb.org/t/p/w342/custom.jpg",
                LocalDate.of(1999, 10, 15), 1999, 139, null, null, null, List.of("Drama"),
                null, null, ContentPreviewStatus.AVAILABLE);
        DiaryEntryResponseDTO topResponse = buildDiaryEntryResponseDto(topRatedEntry);
        DiaryEntryResponseDTO bottomResponse = buildDiaryEntryResponseDto(bottomRatedEntry);

        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(diaryEntryRepository.countDiaryEntriesGroupByContentId(eq(lucasId), any(PageRequest.class)))
                .thenReturn(List.of(mostLoggedRow));
        when(contentRepository.findAllById(List.of(movie.getId()))).thenReturn(List.of(movie));
        when(contentMapper.contentToContentRefDto(movie)).thenReturn(contentRef);
        when(diaryEntryRepository.findTopRatedByUserId(eq(lucasId), any(PageRequest.class)))
                .thenReturn(List.of(topRatedEntry));
        when(diaryEntryRepository.findBottomRatedByUserId(eq(lucasId), any(PageRequest.class)))
                .thenReturn(List.of(bottomRatedEntry));
        when(diaryEntryMapper.diaryEntryToResponseDto(topRatedEntry, false)).thenReturn(topResponse);
        when(diaryEntryMapper.diaryEntryToResponseDto(bottomRatedEntry, false)).thenReturn(bottomResponse);
        when(contentCardAssembler.assemble(anyCollection(), any(ContentCardContext.class), anySet()))
                .thenReturn(Map.of(ContentCoordinate.from(movie), card));

        AllTimeStatsResponseDTO result = summaryService.getAllTimeStats(lucasId, lucasId);

        assertThat(result.mostLoggedContent().getFirst().card()).isEqualTo(card);
        assertThat(result.topRated().getFirst().card()).isEqualTo(card);
        assertThat(result.bottomRated().getFirst().card()).isEqualTo(card);
        assertThat(result.mostLoggedContent().getFirst().count()).isEqualTo(3L);
        assertThat(result.topRated().getFirst().score()).isEqualTo(9);
        assertThat(result.bottomRated().getFirst().score()).isEqualTo(4);
        verify(contentCardAssembler).assemble(anyCollection(),
                eq(new ContentCardContext(lucas.getPreferredLanguage(), lucas.getPreferredRegion(), lucasId, null)),
                anySet());
    }

    @Test
    @DisplayName("[getAllTimeStats] Should Use The Distinct Series Genre Query")
    void shouldUseTheDistinctSeriesGenreQueryInAllTimeStats() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(diaryEntryRepository.countEntriesByGenreAndUserIdForMovies(lucasId))
                .thenReturn(List.of(genreCount("Action", 9L)));
        when(diaryEntryRepository.countDistinctTitlesByGenreAndUserIdForSeries(lucasId))
                .thenReturn(List.of(genreCount("Sci-Fi", 6L)));

        AllTimeStatsResponseDTO result = summaryService.getAllTimeStats(lucasId, lucasId);

        assertThat(result.genreCountsSeries()).containsExactly(new GenreCountDTO("Sci-Fi", 6L));
        assertThat(result.genreCountsMovies()).containsExactly(new GenreCountDTO("Action", 9L));
        verify(diaryEntryRepository).countDistinctTitlesByGenreAndUserIdForSeries(lucasId);
    }

    @Test
    @DisplayName("[getAllTimeStats] Should Return Top Watch Companions Combining Movies And Episodes - When Companions Are Tagged")
    void shouldReturnTopWatchCompanionsForAllTimeStats() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        User marina = buildUser(marinaId, true);
        when(watchCompanionRepository.countGroupedByCompanionUserIdAndContentTypeIn(
                eq(lucasId), eq(Set.of(ContentType.MOVIE, ContentType.EPISODE)), eq(PageRequest.of(0, 3))))
                .thenReturn(List.of(companionWatchCount(marinaId, 37L)));
        when(userRepository.findAllById(List.of(marinaId))).thenReturn(List.of(marina));
        when(userMapper.userToUserPreviewDto(marina)).thenReturn(new UserPreviewDTO(marinaId, "marina", null, true));

        AllTimeStatsResponseDTO result = summaryService.getAllTimeStats(lucasId, lucasId);

        assertThat(result.topWatchCompanions()).hasSize(1);
        assertThat(result.topWatchCompanions().getFirst().companion().id()).isEqualTo(marinaId);
        assertThat(result.topWatchCompanions().getFirst().watchCount()).isEqualTo(37L);
    }

    @Test
    @DisplayName("[getAllTimeStats] Should Return Empty Top Watch Companions - When User Has No Companions Tagged")
    void shouldReturnEmptyTopWatchCompanionsWhenNoneTaggedForAllTimeStats() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));

        AllTimeStatsResponseDTO result = summaryService.getAllTimeStats(lucasId, lucasId);

        assertThat(result.topWatchCompanions()).isEmpty();
    }

    @Test
    @DisplayName("[getEpisodeRatingsGrid] Should Throw NotFoundException - When User Does Not Exist")
    void shouldThrowNotFoundExceptionWhenUserDoesNotExistForEpisodeRatingsGrid() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> summaryService.getEpisodeRatingsGrid(lucasId, lucasId, "1399"))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("User not found");
    }

    @Test
    @DisplayName("[getEpisodeRatingsGrid] Should Throw ForbiddenException - When Target Profile Is Private And Viewer Is Not An Accepted Follower")
    void shouldThrowForbiddenExceptionWhenTargetProfileIsPrivateForEpisodeRatingsGrid() {
        lucas.setIsProfilePublic(false);
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(followerRepository.existsByFollowerIdAndFollowedIdAndStatus(marinaId, lucasId, FollowStatus.ACCEPTED))
                .thenReturn(false);

        assertThatThrownBy(() -> summaryService.getEpisodeRatingsGrid(marinaId, lucasId, "1399"))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("This user profile is private");
    }

    @Test
    @DisplayName("[getEpisodeRatingsGrid] Should Throw BadRequestException - When SeriesTmdbId Is Blank")
    void shouldThrowBadRequestExceptionWhenSeriesTmdbIdIsBlank() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));

        assertThatThrownBy(() -> summaryService.getEpisodeRatingsGrid(lucasId, lucasId, ""))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("seriesTmdbId must be provided");
    }

    @Test
    @DisplayName("[getEpisodeRatingsGrid] Should Throw BadRequestException - When SeriesTmdbId Is Only Whitespace")
    void shouldThrowBadRequestExceptionWhenSeriesTmdbIdIsOnlyWhitespace() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));

        assertThatThrownBy(() -> summaryService.getEpisodeRatingsGrid(lucasId, lucasId, "   "))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("seriesTmdbId must be provided");

        verifyNoInteractions(diaryEntryRepository);
    }

    @Test
    @DisplayName("[getEpisodeRatingsGrid] Should Use The Highest WatchNumber Score - When An Episode Was Rewatched")
    void shouldUseTheHighestWatchNumberScoreWhenAnEpisodeWasRewatched() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        Content episode = Content.builder()
                .id(UUID.randomUUID())
                .type(ContentType.EPISODE)
                .seriesTmdbId("1399")
                .seasonNumber(1)
                .episodeNumber(1)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        DiaryEntry firstWatch = DiaryEntry.builder().id(UUID.randomUUID()).content(episode).score(6).watchNumber(1)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        DiaryEntry rewatch = DiaryEntry.builder().id(UUID.randomUUID()).content(episode).score(9).watchNumber(2)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        when(diaryEntryRepository.findEpisodeEntriesBySeriesForUser(lucasId, "1399")).thenReturn(List.of(firstWatch, rewatch));

        EpisodeRatingsGridResponseDTO result = summaryService.getEpisodeRatingsGrid(lucasId, lucasId, "1399");

        assertThat(result.episodes()).hasSize(1);
        assertThat(result.episodes().getFirst().score()).isEqualTo(9);
    }

    @Test
    @DisplayName("[getEpisodeRatingsGrid] Should Expose The Owner's Series Poster")
    void shouldExposeOwnersSeriesPosterForEpisodeRatingsGrid() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(diaryEntryRepository.findEpisodeEntriesBySeriesForUser(lucasId, "1399"))
                .thenReturn(List.of());
        when(userContentPosterService.findSeriesPosters(lucasId, List.of("1399")))
                .thenReturn(Map.of("1399", "https://image.tmdb.org/t/p/w342/lucas-series-poster.jpg"));

        EpisodeRatingsGridResponseDTO result = summaryService.getEpisodeRatingsGrid(lucasId, lucasId, "1399");

        assertThat(result.customPosterUrl())
                .isEqualTo("https://image.tmdb.org/t/p/w342/lucas-series-poster.jpg");
        verify(userContentPosterService).findSeriesPosters(lucasId, List.of("1399"));
    }

    @Test
    @DisplayName("[getEpisodeRatingsMap] Should Return Empty Series - When User Has No Episode Entries")
    void shouldReturnEmptySeriesWhenUserHasNoEpisodeEntriesForEpisodeRatingsMap() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(diaryEntryRepository.findEpisodeSeriesCountsByUserId(lucasId)).thenReturn(List.of());

        EpisodeRatingsMapResponseDTO result = summaryService.getEpisodeRatingsMap(lucasId, lucasId);

        assertThat(result.series()).isEmpty();
        verifyNoInteractions(seriesProgressMetadataRefreshService);
    }

    @Test
    @DisplayName("[getEpisodeRatingsMap] Should Return Watched And Total Counts - When Series Is Completed")
    void shouldReturnWatchedAndTotalCountsWhenSeriesIsCompletedForEpisodeRatingsMap() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(diaryEntryRepository.findEpisodeSeriesCountsByUserId(lucasId))
                .thenReturn(List.of(seriesEpisodeCount("1399", 7L)));
        when(seriesProgressMetadataRefreshService.getSnapshotsForRead(eq(List.of("1399")), any(LocalDate.class)))
                .thenReturn(Map.of("1399", seriesProgressSnapshot("1399", 10)));

        EpisodeRatingsMapResponseDTO result = summaryService.getEpisodeRatingsMap(lucasId, lucasId);

        assertThat(result.series())
                .extracting("seriesTmdbId", "watchedEpisodeCount", "totalEpisodeCount")
                .containsExactly(org.assertj.core.groups.Tuple.tuple("1399", 7L, 10));
    }

    @Test
    @DisplayName("[getEpisodeRatingsMap] Should Resolve Posters For The Target User And Preserve Null Fallbacks")
    void shouldResolvePostersForTargetUserAndPreserveNullFallbacksForEpisodeRatingsMap() {
        lucas.setIsProfilePublic(false);
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(followerRepository.existsByFollowerIdAndFollowedIdAndStatus(marinaId, lucasId, FollowStatus.ACCEPTED))
                .thenReturn(true);
        when(diaryEntryRepository.findEpisodeSeriesCountsByUserId(lucasId))
                .thenReturn(List.of(seriesEpisodeCount("1399", 7L), seriesEpisodeCount("94605", 2L)));
        when(seriesProgressMetadataRefreshService.getSnapshotsForRead(
                eq(List.of("1399", "94605")), any(LocalDate.class)))
                .thenReturn(Map.of(
                        "1399", seriesProgressSnapshot("1399", 10),
                        "94605", seriesProgressSnapshot("94605", 4)));
        when(userContentPosterService.findSeriesPosters(lucasId, List.of("1399", "94605")))
                .thenReturn(Map.of("1399", "https://image.tmdb.org/t/p/w342/lucas-series-poster.jpg"));

        EpisodeRatingsMapResponseDTO result = summaryService.getEpisodeRatingsMap(marinaId, lucasId);

        assertThat(result.series())
                .extracting(EpisodeRatingsMapItemDTO::seriesTmdbId,
                        EpisodeRatingsMapItemDTO::customPosterUrl)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "1399", "https://image.tmdb.org/t/p/w342/lucas-series-poster.jpg"),
                        org.assertj.core.groups.Tuple.tuple("94605", null));
        verify(userContentPosterService).findSeriesPosters(lucasId, List.of("1399", "94605"));
    }

    @Test
    @DisplayName("[getEpisodeRatingsMap] Should Throw ForbiddenException - When Target Profile Is Private")
    void shouldThrowForbiddenExceptionWhenTargetProfileIsPrivateForEpisodeRatingsMap() {
        lucas.setIsProfilePublic(false);
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(followerRepository.existsByFollowerIdAndFollowedIdAndStatus(marinaId, lucasId, FollowStatus.ACCEPTED))
                .thenReturn(false);

        assertThatThrownBy(() -> summaryService.getEpisodeRatingsMap(marinaId, lucasId))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("This user profile is private");

        verifyNoInteractions(diaryEntryRepository, seriesProgressMetadataRefreshService);
    }

    private DiaryEntryRepository.SeriesEpisodeCount seriesEpisodeCount(String seriesTmdbId, long watchedEpisodeCount) {
        return new DiaryEntryRepository.SeriesEpisodeCount() {
            @Override
            public String getSeriesTmdbId() {
                return seriesTmdbId;
            }

            @Override
            public Long getWatchedEpisodeCount() {
                return watchedEpisodeCount;
            }
        };
    }

    private SeriesProgressMetadataRefreshService.Snapshot seriesProgressSnapshot(String seriesTmdbId, int totalEpisodeCount) {
        return new SeriesProgressMetadataRefreshService.Snapshot(
                new SeriesProgressMetadataRefreshService.SeriesSnapshot(
                        seriesTmdbId, totalEpisodeCount, null, 0, null, LocalDateTime.now(), null),
                List.of());
    }

    private DiaryEntryRepository.SeriesRuntime seriesRuntime(String seriesTmdbId, long totalMinutes) {
        return seriesRuntime(seriesTmdbId, null, totalMinutes);
    }

    private DiaryEntryRepository.SeriesRuntime seriesRuntime(String seriesTmdbId, UUID contentId, long totalMinutes) {
        return new DiaryEntryRepository.SeriesRuntime() {
            @Override
            public String getSeriesTmdbId() {
                return seriesTmdbId;
            }

            @Override
            public Long getTotalMinutes() {
                return totalMinutes;
            }

            @Override
            public UUID getContentId() {
                return contentId;
            }
        };
    }

    private Top5Entry buildTop5Entry(Content content) {
        return Top5Entry.builder()
                .id(UUID.randomUUID())
                .content(content)
                .type(ContentType.MOVIE)
                .position(1)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private DiaryEntryRepository.GenreCount genreCount(String genre, long count) {
        return new DiaryEntryRepository.GenreCount() {
            @Override
            public String getGenre() {
                return genre;
            }

            @Override
            public Long getCount() {
                return count;
            }
        };
    }

    private DiaryEntryRepository.ScoreCount scoreCount(int score, long count) {
        return new DiaryEntryRepository.ScoreCount() {
            @Override
            public Integer getScore() {
                return score;
            }

            @Override
            public Long getCount() {
                return count;
            }
        };
    }

    private WatchCompanionRepository.CompanionWatchCount companionWatchCount(UUID companionUserId, long count) {
        return new WatchCompanionRepository.CompanionWatchCount() {
            @Override
            public UUID getCompanionUserId() {
                return companionUserId;
            }

            @Override
            public Long getCount() {
                return count;
            }
        };
    }

    private User buildUser(UUID id, boolean isProfilePublic) {
        return User.builder()
                .id(id)
                .username("lucas")
                .email("lucas@email.com")
                .password("hashed_password")
                .isProfilePublic(isProfilePublic)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private Content buildContent(String tmdbId, ContentType type) {
        LocalDateTime now = LocalDateTime.now();
        return Content.builder()
                .id(UUID.randomUUID())
                .tmdbId(tmdbId)
                .type(type)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private DiaryEntry buildDiaryEntry(Content content, LocalDateTime createdAt) {
        return DiaryEntry.builder()
                .id(UUID.randomUUID())
                .content(content)
                .watchNumber(1)
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .build();
    }

    private DroppedEntry buildDroppedEntry(Content content, LocalDateTime createdAt) {
        return DroppedEntry.builder()
                .id(UUID.randomUUID())
                .content(content)
                .type(ContentType.MOVIE)
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .build();
    }

    private DiaryEntryResponseDTO buildDiaryEntryResponseDto() {
        LocalDateTime now = LocalDateTime.now();
        ContentRefDTO content = new ContentRefDTO(UUID.randomUUID(), null, ContentType.EPISODE, "1399", 1, 1, null, null, now, now);
        return new DiaryEntryResponseDTO(UUID.randomUUID(), lucasId, content, null, null, null, 1, null, null, false, false, now, now, 0, false);
    }

    private DiaryEntryResponseDTO buildDiaryEntryResponseDto(DiaryEntry entry) {
        Content content = entry.getContent();
        LocalDateTime now = entry.getUpdatedAt();
        ContentRefDTO contentRef = new ContentRefDTO(content.getId(), content.getTmdbId(), content.getType(),
                content.getSeriesTmdbId(), content.getSeasonNumber(), content.getEpisodeNumber(),
                content.getIsSeasonFinale(), content.getIsSeriesFinale(), content.getCreatedAt(), content.getUpdatedAt());
        return new DiaryEntryResponseDTO(entry.getId(), lucasId, contentRef, entry.getComment(), entry.getScore(),
                entry.getWatchedDate(), entry.getWatchNumber(), entry.getWatchedInTheater(), null,
                entry.getAutoGenerated(), entry.getIgnore(), entry.getCreatedAt(), now, entry.getLikesCount(), false);
    }

    private Class<?> recordComponentType(Class<?> recordType, String componentName) {
        return Arrays.stream(recordType.getRecordComponents())
                .filter(component -> component.getName().equals(componentName))
                .map(java.lang.reflect.RecordComponent::getType)
                .findFirst()
                .orElse(null);
    }
}
