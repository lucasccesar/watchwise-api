package com.watchwise.watchwise_api.summary.service.impl;

import com.watchwise.watchwise_api.common.dto.GenreCountDTO;
import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.mapper.ContentMapper;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryEntryResponseDTO;
import com.watchwise.watchwise_api.diaryentry.dto.SeriesInProgressResponseDTO;
import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import com.watchwise.watchwise_api.diaryentry.mapper.DiaryEntryMapper;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.diaryentry.service.DiaryEntryService;
import com.watchwise.watchwise_api.dropped.entity.DroppedEntry;
import com.watchwise.watchwise_api.dropped.repository.DroppedEntryRepository;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.summary.dto.AllTimeStatsResponseDTO;
import com.watchwise.watchwise_api.summary.dto.DailyWatchCountDTO;
import com.watchwise.watchwise_api.summary.dto.EpisodeRatingsGridResponseDTO;
import com.watchwise.watchwise_api.summary.dto.HomeSummaryResponseDTO;
import com.watchwise.watchwise_api.summary.dto.MonthInReviewResponseDTO;
import com.watchwise.watchwise_api.summary.dto.RatingCountDTO;
import com.watchwise.watchwise_api.summary.dto.RecentActivityItemDTO;
import com.watchwise.watchwise_api.summary.dto.RecentActivityStatus;
import com.watchwise.watchwise_api.summary.dto.SummaryResponseDTO;
import com.watchwise.watchwise_api.summary.dto.YearInReviewResponseDTO;
import com.watchwise.watchwise_api.top5entry.entity.Top5Entry;
import com.watchwise.watchwise_api.top5entry.repository.Top5EntryRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
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

    @InjectMocks
    private SummaryServiceImpl summaryService;

    private UUID lucasId;
    private UUID marinaId;
    private User lucas;

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
        lenient().when(diaryEntryRepository.findSeriesInProgressByUserId(any(), any(PageRequest.class)))
                .thenReturn(Page.empty());
        lenient().when(diaryEntryRepository.countByUserIdAndWatchedDateBetween(any(), any(), any()))
                .thenReturn(List.of());
        lenient().when(diaryEntryRepository.countEntriesByGenreAndUserIdForMoviesAndWatchedDateBetween(any(), any(), any()))
                .thenReturn(List.of());
        lenient().when(diaryEntryRepository.countEntriesByGenreAndUserIdForSeriesAndWatchedDateBetween(any(), any(), any()))
                .thenReturn(List.of());
        lenient().when(top5EntryRepository.findByUserIdAndTypeWithContentOrderByPositionAsc(any(), any()))
                .thenReturn(List.of());
        lenient().when(diaryEntryMapper.diaryEntryToResponseDto(any(), anyBoolean()))
                .thenAnswer(invocation -> buildDiaryEntryResponseDto());
    }

    @Test
    @DisplayName("[getSummary] Should Throw NotFoundException - When User Does Not Exist")
    void shouldThrowNotFoundExceptionWhenUserDoesNotExist() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> summaryService.getSummary(lucasId, lucasId, ContentType.MOVIE))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("User not found");

        verifyNoInteractions(diaryEntryRepository);
    }

    @Test
    @DisplayName("[getSummary] Should Throw ForbiddenException - When Target Profile Is Private And Viewer Is Not An Accepted Follower")
    void shouldThrowForbiddenExceptionWhenTargetProfileIsPrivateAndViewerIsNotAnAcceptedFollower() {
        lucas.setIsProfilePublic(false);
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(followerRepository.existsByFollowerIdAndFollowedIdAndStatus(marinaId, lucasId, FollowStatus.ACCEPTED))
                .thenReturn(false);

        assertThatThrownBy(() -> summaryService.getSummary(marinaId, lucasId, ContentType.MOVIE))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("This user profile is private");

        verifyNoInteractions(diaryEntryRepository);
    }

    @Test
    @DisplayName("[getSummary] Should Throw BadRequestException - When Type Is Null")
    void shouldThrowBadRequestExceptionWhenTypeIsNull() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));

        assertThatThrownBy(() -> summaryService.getSummary(lucasId, lucasId, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("type must be one of: MOVIE, SERIES");
    }

    @Test
    @DisplayName("[getSummary] Should Throw BadRequestException - When Type Is Not MOVIE Or SERIES")
    void shouldThrowBadRequestExceptionWhenTypeIsNotMovieOrSeries() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));

        assertThatThrownBy(() -> summaryService.getSummary(lucasId, lucasId, ContentType.EPISODE))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("type must be one of: MOVIE, SERIES");
    }

    @Test
    @DisplayName("[getSummary] Should Compute WatchTime From MOVIE Content Type - When Type Is MOVIE")
    void shouldComputeWatchTimeFromMovieContentTypeWhenTypeIsMovie() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(diaryEntryRepository.sumRuntimeMinutesByUserIdAndContentType(lucasId, ContentType.MOVIE)).thenReturn(500L);
        when(diaryEntryRepository.sumRuntimeMinutesByUserIdAndContentTypeAndWatchedDateBetween(
                eq(lucasId), eq(ContentType.MOVIE), any(), any())).thenReturn(120L);

        SummaryResponseDTO result = summaryService.getSummary(lucasId, lucasId, ContentType.MOVIE);

        assertThat(result.watchTime().totalMinutesWatched()).isEqualTo(500L);
        assertThat(result.watchTime().minutesWatchedLast30Days()).isEqualTo(120L);
    }

    @Test
    @DisplayName("[getSummary] Should Compute WatchTime From EPISODE Content Type - When Type Is SERIES")
    void shouldComputeWatchTimeFromEpisodeContentTypeWhenTypeIsSeries() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(diaryEntryRepository.sumRuntimeMinutesByUserIdAndContentType(lucasId, ContentType.EPISODE)).thenReturn(900L);
        when(diaryEntryRepository.sumRuntimeMinutesByUserIdAndContentTypeAndWatchedDateBetween(
                eq(lucasId), eq(ContentType.EPISODE), any(), any())).thenReturn(45L);

        SummaryResponseDTO result = summaryService.getSummary(lucasId, lucasId, ContentType.SERIES);

        assertThat(result.watchTime().totalMinutesWatched()).isEqualTo(900L);
        assertThat(result.watchTime().minutesWatchedLast30Days()).isEqualTo(45L);
        verify(diaryEntryRepository, never()).sumRuntimeMinutesByUserIdAndContentType(lucasId, ContentType.MOVIE);
    }

    @Test
    @DisplayName("[getSummary] Should Use The Movie Genre Query - When Type Is MOVIE")
    void shouldUseTheMovieGenreQueryWhenTypeIsMovie() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(diaryEntryRepository.countDistinctTitlesByGenreAndUserIdForMovies(lucasId))
                .thenReturn(List.of(genreCount("Drama", 3L)));

        SummaryResponseDTO result = summaryService.getSummary(lucasId, lucasId, ContentType.MOVIE);

        assertThat(result.genreCounts()).containsExactly(new GenreCountDTO("Drama", 3L));
    }

    @Test
    @DisplayName("[getSummary] Should Use The Series Genre Query - When Type Is SERIES")
    void shouldUseTheSeriesGenreQueryWhenTypeIsSeries() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(diaryEntryRepository.countDistinctTitlesByGenreAndUserIdForSeries(lucasId))
                .thenReturn(List.of(genreCount("Sci-Fi", 2L)));

        SummaryResponseDTO result = summaryService.getSummary(lucasId, lucasId, ContentType.SERIES);

        assertThat(result.genreCounts()).containsExactly(new GenreCountDTO("Sci-Fi", 2L));
    }

    @Test
    @DisplayName("[getSummary] Should Return RatingsDistribution From Score Counts")
    void shouldReturnRatingsDistributionFromScoreCounts() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(diaryEntryRepository.countByUserIdAndContentTypeGroupByScore(lucasId, ContentType.MOVIE))
                .thenReturn(List.of(scoreCount(8, 5L)));

        SummaryResponseDTO result = summaryService.getSummary(lucasId, lucasId, ContentType.MOVIE);

        assertThat(result.ratingsDistribution()).containsExactly(new RatingCountDTO(8, 5L));
    }

    @Test
    @DisplayName("[getSummary] Should Not Query RecentEpisodes - When Type Is MOVIE")
    void shouldNotQueryRecentEpisodesWhenTypeIsMovie() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));

        SummaryResponseDTO result = summaryService.getSummary(lucasId, lucasId, ContentType.MOVIE);

        assertThat(result.recentEpisodes()).isEmpty();
        verify(diaryEntryService, never()).getDiaryEntries(any(), any(), any(), any(), any(), eq(ContentType.EPISODE), any(), any(), any());
    }

    @Test
    @DisplayName("[getSummary] Should Query RecentEpisodes With Type EPISODE And Size Four - When Type Is SERIES")
    void shouldQueryRecentEpisodesWithTypeEpisodeAndSizeFourWhenTypeIsSeries() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));

        summaryService.getSummary(lucasId, lucasId, ContentType.SERIES);

        verify(diaryEntryService).getDiaryEntries(lucasId, lucasId, null, 1, 4, ContentType.EPISODE, null, null, null);
    }

    @Test
    @DisplayName("[getSummary] Should Query RecentReviews With HasReview True And Size Five")
    void shouldQueryRecentReviewsWithHasReviewTrueAndSizeFive() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));

        summaryService.getSummary(lucasId, lucasId, ContentType.MOVIE);

        verify(diaryEntryService).getDiaryEntries(lucasId, lucasId, null, 1, 5, ContentType.MOVIE, null, null, true);
    }

    @Test
    @DisplayName("[getSummary] Should Merge Completed And Dropped Entries Sorted By Date Descending - When Both Exist")
    void shouldMergeCompletedAndDroppedEntriesSortedByDateDescendingWhenBothExist() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        Content movieContent = buildContent("550", ContentType.MOVIE);
        Content droppedContent = buildContent("680", ContentType.MOVIE);
        DiaryEntry completedEntry = buildDiaryEntry(movieContent, LocalDateTime.now().minusDays(1));
        DroppedEntry droppedEntry = buildDroppedEntry(droppedContent, LocalDateTime.now());
        when(diaryEntryRepository.findTopByUserIdAndContentTypeOrderByCreatedAtDesc(eq(lucasId), eq(ContentType.MOVIE), any()))
                .thenReturn(List.of(completedEntry));
        when(droppedEntryRepository.findByUserIdAndTypeOrderByCreatedAtDesc(eq(lucasId), eq(ContentType.MOVIE), any()))
                .thenReturn(new PageImpl<>(List.of(droppedEntry)));
        when(contentMapper.contentToContentRefDto(movieContent))
                .thenReturn(new ContentRefDTO(movieContent.getId(), "550", ContentType.MOVIE, null, null, null, null, null, null, null));
        when(contentMapper.contentToContentRefDto(droppedContent))
                .thenReturn(new ContentRefDTO(droppedContent.getId(), "680", ContentType.MOVIE, null, null, null, null, null, null, null));

        SummaryResponseDTO result = summaryService.getSummary(lucasId, lucasId, ContentType.MOVIE);

        assertThat(result.recentActivity()).extracting(RecentActivityItemDTO::status)
                .containsExactly(RecentActivityStatus.DROPPED, RecentActivityStatus.COMPLETED);
    }

    @Test
    @DisplayName("[getSummary] Should Limit RecentActivity To Six Items - When More Than Six Exist")
    void shouldLimitRecentActivityToSixItemsWhenMoreThanSixExist() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        List<DiaryEntry> completedEntries = List.of(
                buildDiaryEntry(buildContent("1", ContentType.MOVIE), LocalDateTime.now().minusDays(1)),
                buildDiaryEntry(buildContent("2", ContentType.MOVIE), LocalDateTime.now().minusDays(2)),
                buildDiaryEntry(buildContent("3", ContentType.MOVIE), LocalDateTime.now().minusDays(3)),
                buildDiaryEntry(buildContent("4", ContentType.MOVIE), LocalDateTime.now().minusDays(4)));
        List<DroppedEntry> droppedEntries = List.of(
                buildDroppedEntry(buildContent("5", ContentType.MOVIE), LocalDateTime.now().minusDays(5)),
                buildDroppedEntry(buildContent("6", ContentType.MOVIE), LocalDateTime.now().minusDays(6)),
                buildDroppedEntry(buildContent("7", ContentType.MOVIE), LocalDateTime.now().minusDays(7)));
        when(diaryEntryRepository.findTopByUserIdAndContentTypeOrderByCreatedAtDesc(eq(lucasId), eq(ContentType.MOVIE), any()))
                .thenReturn(completedEntries);
        when(droppedEntryRepository.findByUserIdAndTypeOrderByCreatedAtDesc(eq(lucasId), eq(ContentType.MOVIE), any()))
                .thenReturn(new PageImpl<>(droppedEntries));
        when(contentMapper.contentToContentRefDto(any())).thenAnswer(invocation -> {
            Content content = invocation.getArgument(0);
            return new ContentRefDTO(content.getId(), content.getTmdbId(), ContentType.MOVIE, null, null, null, null, null, null, null);
        });

        SummaryResponseDTO result = summaryService.getSummary(lucasId, lucasId, ContentType.MOVIE);

        assertThat(result.recentActivity()).hasSize(6);
    }

    @Test
    @DisplayName("[getSummary] Should Return Recent Episodes And Reviews Mapped From The Diary Service - When Available")
    void shouldReturnRecentEpisodesAndReviewsMappedFromTheDiaryServiceWhenAvailable() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        DiaryEntryResponseDTO episodeDto = buildDiaryEntryResponseDto();
        when(diaryEntryService.getDiaryEntries(lucasId, lucasId, null, 1, 4, ContentType.EPISODE, null, null, null))
                .thenReturn(new PageImpl<>(List.of(episodeDto)));

        SummaryResponseDTO result = summaryService.getSummary(lucasId, lucasId, ContentType.SERIES);

        assertThat(result.recentEpisodes()).containsExactly(episodeDto);
    }

    // ---------- getHomeSummary ----------

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
    @DisplayName("[getHomeSummary] Should Return Totals, Next Episodes, Rolling 30-Day Stats And Genre Counts From The Repository")
    void shouldReturnTotalsNextEpisodesRollingStatsAndGenreCountsForHomeSummary() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(diaryEntryRepository.sumRuntimeMinutesByUserId(lucasId)).thenReturn(9000L);
        when(diaryEntryRepository.countByUserIdAndContentType(lucasId, ContentType.MOVIE)).thenReturn(42L);
        when(diaryEntryRepository.countByUserIdAndContentType(lucasId, ContentType.EPISODE)).thenReturn(128L);
        DiaryEntryRepository.SeriesInProgress row = seriesInProgress("1399", 8, 6, LocalDate.of(2024, 5, 1));
        when(diaryEntryRepository.findSeriesInProgressByUserId(eq(lucasId), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(row)));
        when(diaryEntryRepository.countByUserIdAndWatchedDateBetween(eq(lucasId), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(dailyWatchCount(LocalDate.of(2024, 5, 1), 3)));
        when(diaryEntryRepository.countEntriesByGenreAndUserIdForMoviesAndWatchedDateBetween(
                eq(lucasId), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(genreCount("Action", 2)));
        when(diaryEntryRepository.countEntriesByGenreAndUserIdForSeriesAndWatchedDateBetween(
                eq(lucasId), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(genreCount("Drama", 5)));

        HomeSummaryResponseDTO result = summaryService.getHomeSummary(lucasId, lucasId);

        assertThat(result.totalMinutesWatched()).isEqualTo(9000L);
        assertThat(result.totalMoviesWatched()).isEqualTo(42L);
        assertThat(result.totalEpisodesWatched()).isEqualTo(128L);
        assertThat(result.nextEpisodes()).containsExactly(new SeriesInProgressResponseDTO("1399", 8, 6, LocalDate.of(2024, 5, 1)));
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

        when(diaryEntryRepository.findTopByUserIdAndContentTypeOrderByCreatedAtDesc(eq(lucasId), eq(ContentType.MOVIE), any()))
                .thenReturn(List.of(oldestMovie, newestMovie));
        when(diaryEntryRepository.findTopByUserIdAndContentTypeOrderByCreatedAtDesc(eq(lucasId), eq(ContentType.EPISODE), any()))
                .thenReturn(List.of(oldestEpisode, newestEpisode));
        when(diaryEntryMapper.diaryEntryToResponseDto(any(DiaryEntry.class), eq(false)))
                .thenAnswer(invocation -> {
                    DiaryEntry entry = invocation.getArgument(0);
                    return new DiaryEntryResponseDTO(entry.getId(), lucasId, null, null, null, null, 1, null, null, false,
                            entry.getCreatedAt(), entry.getCreatedAt(), 0, false);
                });

        HomeSummaryResponseDTO result = summaryService.getHomeSummary(lucasId, lucasId);

        assertThat(result.recentlyWatched()).extracting(DiaryEntryResponseDTO::id)
                .containsExactly(newestEpisode.getId(), newestMovie.getId(), oldestEpisode.getId(), oldestMovie.getId());
    }

    private DiaryEntryRepository.SeriesInProgress seriesInProgress(
            String seriesTmdbId, Integer maxSeasonNumber, Integer maxEpisodeNumber, LocalDate lastWatchedDate) {
        return new DiaryEntryRepository.SeriesInProgress() {
            @Override
            public String getSeriesTmdbId() {
                return seriesTmdbId;
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
    @DisplayName("[getMonthInReview] Should Only Populate TopSeriesByWatchTime - When Type Is SERIES")
    void shouldOnlyPopulateTopSeriesByWatchTimeWhenTypeIsSeriesForMonthInReview() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(diaryEntryRepository.sumRuntimeMinutesByUserIdGroupBySeriesTmdbIdAndWatchedDateBetween(eq(lucasId), any(), any(), any()))
                .thenReturn(List.of(seriesRuntime("1399", 320L)));

        MonthInReviewResponseDTO result = summaryService.getMonthInReview(lucasId, lucasId, ContentType.SERIES, YearMonth.of(2026, 8));

        assertThat(result.topSeriesByWatchTime()).hasSize(1);
        assertThat(result.topSeriesByWatchTime().getFirst().seriesTmdbId()).isEqualTo("1399");
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
        when(diaryEntryRepository.sumRuntimeMinutesByUserId(lucasId)).thenReturn(9000L);

        AllTimeStatsResponseDTO result = summaryService.getAllTimeStats(lucasId, lucasId);

        assertThat(result.totalMoviesWatched()).isEqualTo(42L);
        assertThat(result.totalEpisodesWatched()).isEqualTo(128L);
        assertThat(result.totalMinutesWatched()).isEqualTo(9000L);
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

    private DiaryEntryRepository.SeriesRuntime seriesRuntime(String seriesTmdbId, long totalMinutes) {
        return new DiaryEntryRepository.SeriesRuntime() {
            @Override
            public String getSeriesTmdbId() {
                return seriesTmdbId;
            }

            @Override
            public Long getTotalMinutes() {
                return totalMinutes;
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
        return new DiaryEntryResponseDTO(UUID.randomUUID(), lucasId, content, null, null, null, 1, null, null, false, now, now, 0, false);
    }
}
