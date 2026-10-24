package com.watchwise.watchwise_api.summary.service.impl;

import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.mapper.ContentMapper;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.contentreview.entity.ContentReview;
import com.watchwise.watchwise_api.contentreview.entity.ReviewContextKind;
import com.watchwise.watchwise_api.contentreview.entity.ReviewPublicationKind;
import com.watchwise.watchwise_api.contentreview.repository.ContentReviewRepository;
import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import com.watchwise.watchwise_api.diaryentry.repository.WatchCompanionRepository;
import com.watchwise.watchwise_api.dropped.entity.DroppedEntry;
import com.watchwise.watchwise_api.dropped.repository.DroppedEntryRepository;
import com.watchwise.watchwise_api.summary.repository.ProfileSummaryQueryRepository;
import com.watchwise.watchwise_api.summary.dto.ProfileDiaryPreviewDTO;
import com.watchwise.watchwise_api.summary.service.ProfileSummaryDataReader;
import com.watchwise.watchwise_api.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Instant;
import java.util.TimeZone;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProfileSummaryDataReaderImplTest {

    @Mock
    private ProfileSummaryQueryRepository queryRepository;

    @Mock
    private DroppedEntryRepository droppedEntryRepository;

    @Mock
    private ContentRepository contentRepository;

    @Mock
    private ContentMapper contentMapper;

    @Mock
    private WatchCompanionRepository watchCompanionRepository;

    @Mock
    private UserMapper userMapper;

    @Mock
    private ContentReviewRepository contentReviewRepository;

    private ProfileSummaryDataReaderImpl reader;
    private UUID userId;

    @BeforeEach
    void setUp() {
        reader = new ProfileSummaryDataReaderImpl(queryRepository, droppedEntryRepository, contentRepository,
                contentMapper, watchCompanionRepository, userMapper, contentReviewRepository);
        userId = UUID.randomUUID();
        org.mockito.Mockito.lenient().when(queryRepository.findRecentReviews(any(), any(), any())).thenReturn(List.of());
        org.mockito.Mockito.lenient().when(queryRepository.findRecentTopLevelEntries(any(), any(), any())).thenReturn(List.of());
        org.mockito.Mockito.lenient().when(queryRepository.findRecentSeriesCompletionActivities(any(), any())).thenReturn(List.of());
        org.mockito.Mockito.lenient().when(contentReviewRepository.findRecentWrittenReviewsByUserIdAndContentTypeIn(any(), any(), any()))
                .thenReturn(List.of());
        org.mockito.Mockito.lenient().when(droppedEntryRepository.findByUserIdAndTypeOrderByCreatedAtDesc(any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of()));
        org.mockito.Mockito.lenient().when(queryRepository.countDistinctMoviesByGenre(any())).thenReturn(List.of());
        org.mockito.Mockito.lenient().when(queryRepository.countDistinctEpisodesByGenre(any())).thenReturn(List.of());
        org.mockito.Mockito.lenient().when(queryRepository.countLatestScoresByUserIdAndContentType(any(), anyString())).thenReturn(List.of());
        org.mockito.Mockito.lenient().when(queryRepository.countDiaryEntriesGroupByContentType(any(), anyString(), any())).thenReturn(List.of());
        org.mockito.Mockito.lenient().when(queryRepository.findLongestMovieContentByUserId(any(), any())).thenReturn(List.of());
        org.mockito.Mockito.lenient().when(queryRepository.sumRuntimeMinutesByUserIdGroupBySeriesTmdbId(any(), any())).thenReturn(List.of());
    }

    @Test
    void shouldBatchCompletedAndDroppedWatchCountsForRecentActivity() {
        Content movie = Content.builder().id(UUID.randomUUID()).tmdbId("550").type(ContentType.MOVIE).build();
        DiaryEntry completed = DiaryEntry.builder()
                .id(UUID.randomUUID()).content(movie).score(9).watchNumber(2)
                .watchedDate(LocalDate.now()).createdAt(LocalDateTime.now().minusDays(1)).build();
        DroppedEntry dropped = DroppedEntry.builder()
                .id(UUID.randomUUID()).content(movie).type(ContentType.MOVIE)
                .createdAt(LocalDateTime.now()).build();
        ContentRefDTO contentRef = new ContentRefDTO(movie.getId(), "550", ContentType.MOVIE,
                null, null, null, null, null, null, null);
        ProfileSummaryQueryRepository.ContentWatchCount watchCount =
                org.mockito.Mockito.mock(ProfileSummaryQueryRepository.ContentWatchCount.class);

        when(queryRepository.findRecentTopLevelEntries(org.mockito.ArgumentMatchers.eq(userId),
                org.mockito.ArgumentMatchers.eq(ContentType.MOVIE), any())).thenReturn(List.of(completed));
        when(droppedEntryRepository.findByUserIdAndTypeOrderByCreatedAtDesc(
                org.mockito.ArgumentMatchers.eq(userId), org.mockito.ArgumentMatchers.eq(ContentType.MOVIE), any()))
                .thenReturn(new PageImpl<>(List.of(dropped)));
        when(queryRepository.countDiaryEntriesByUserIdAndContentIdsAndContentType(
                userId, List.of(movie.getId()), ContentType.MOVIE.name())).thenReturn(List.of(watchCount));
        when(watchCount.getContentId()).thenReturn(movie.getId());
        when(watchCount.getCount()).thenReturn(2L);
        when(contentMapper.contentToContentRefDto(movie)).thenReturn(contentRef);

        ProfileSummaryDataReader.Snapshot result = reader.read(userId, ContentType.MOVIE);

        assertThat(result.recentActivity()).hasSize(2);
        assertThat(result.recentActivity()).extracting("timesWatched").containsOnly(2L);
        verify(queryRepository).countDiaryEntriesByUserIdAndContentIdsAndContentType(
                userId, List.of(movie.getId()), ContentType.MOVIE.name());
    }

    @Test
    void shouldBuildRecentSeriesActivityFromCurrentCompletionProjection() {
        UUID contentId = UUID.randomUUID();
        Content series = Content.builder().id(contentId).tmdbId("1399").type(ContentType.SERIES).build();
        ContentRefDTO contentRef = new ContentRefDTO(contentId, "1399", ContentType.SERIES,
                null, null, null, null, null, null, null);
        Instant orderedAt = Instant.parse("2026-10-02T12:00:00Z");
        ProfileSummaryQueryRepository.SeriesCompletionActivity activity =
                org.mockito.Mockito.mock(ProfileSummaryQueryRepository.SeriesCompletionActivity.class);
        when(queryRepository.findRecentSeriesCompletionActivities(eq(userId), any()))
                .thenReturn(List.of(activity));
        when(activity.getProjectionId()).thenReturn(UUID.randomUUID());
        when(activity.getContentId()).thenReturn(contentId);
        when(activity.getOrderedAt()).thenReturn(orderedAt);
        when(activity.getScore()).thenReturn(8);
        when(activity.getComment()).thenReturn("review at completion");
        when(activity.getTimesWatched()).thenReturn(1L);
        when(contentRepository.findAllById(List.of(contentId))).thenReturn(List.of(series));
        when(contentMapper.contentToContentRefDto(series)).thenReturn(contentRef);

        ProfileSummaryDataReader.Snapshot result = reader.read(userId, ContentType.SERIES);

        assertThat(result.recentActivity()).singleElement().satisfies(item -> {
            assertThat(item.content()).isEqualTo(contentRef);
            assertThat(item.status()).isEqualTo(com.watchwise.watchwise_api.summary.dto.RecentActivityStatus.COMPLETED);
            assertThat(item.score()).isEqualTo(8);
            assertThat(item.comment()).isEqualTo("review at completion");
            assertThat(item.timesWatched()).isEqualTo(1L);
            assertThat(item.activityDate()).isEqualTo(LocalDateTime.ofInstant(
                    orderedAt, java.time.ZoneId.systemDefault()));
        });
    }

    @Test
    void shouldIncludeIndependentSeriesReviewsInRecentReviewPreviews() {
        UUID contentId = UUID.randomUUID();
        UUID reviewId = UUID.randomUUID();
        Content series = Content.builder().id(contentId).tmdbId("1399").type(ContentType.SERIES).build();
        ContentRefDTO contentRef = new ContentRefDTO(contentId, "1399", ContentType.SERIES,
                null, null, null, null, null, null, null);
        ContentReview review = ContentReview.builder()
                .id(reviewId)
                .userId(userId)
                .contentId(contentId)
                .contentType(ContentType.SERIES)
                .publicationKind(ReviewPublicationKind.WRITTEN_REVIEW)
                .contextKind(ReviewContextKind.COMPLETION)
                .comment("review after season one")
                .scoreAtPublication(9)
                .watchNumber(1)
                .sourcePublishedAt(Instant.parse("2026-10-02T12:00:00Z"))
                .createdAt(Instant.parse("2026-10-02T12:00:00Z"))
                .updatedAt(Instant.parse("2026-10-02T12:00:00Z"))
                .build();
        when(contentReviewRepository.findRecentWrittenReviewsByUserIdAndContentTypeIn(
                eq(userId), eq(List.of(ContentType.SERIES.name(), ContentType.SEASON.name())), any()))
                .thenReturn(List.of(review));
        when(contentRepository.findAllById(List.of(contentId))).thenReturn(List.of(series));
        when(contentMapper.contentToContentRefDto(series)).thenReturn(contentRef);

        ProfileSummaryDataReader.Snapshot result = reader.read(userId, ContentType.SERIES);

        assertThat(result.recentReviews()).singleElement().satisfies(preview -> {
            assertThat(preview.id()).isEqualTo(reviewId);
            assertThat(preview.source()).isEqualTo(com.watchwise.watchwise_api.summary.dto.ProfileDiaryPreviewSource.CONTENT_REVIEW);
            assertThat(preview.content()).isEqualTo(contentRef);
            assertThat(preview.score()).isEqualTo(9);
            assertThat(preview.watchNumber()).isEqualTo(1);
            assertThat(preview.activityDate()).isEqualTo(LocalDate.of(2026, 10, 2));
        });
    }

    @Test
    void shouldOrderDateOnlyReviewAtUtcMidnightAndPreserveItsCalendarDate() {
        TimeZone previousTimeZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
        try {
            UUID episodeContentId = UUID.randomUUID();
            UUID seriesContentId = UUID.randomUUID();
            Content episode = Content.builder().id(episodeContentId).seriesTmdbId("1399")
                    .seasonNumber(1).episodeNumber(1).type(ContentType.EPISODE).build();
            Content series = Content.builder().id(seriesContentId).tmdbId("1399").type(ContentType.SERIES).build();
            ContentRefDTO episodeRef = new ContentRefDTO(episodeContentId, null, ContentType.EPISODE,
                    "1399", 1, 1, null, null, null, null);
            ContentRefDTO seriesRef = new ContentRefDTO(seriesContentId, "1399", ContentType.SERIES,
                    null, null, null, null, null, null, null);
            DiaryEntry diaryReview = DiaryEntry.builder()
                    .id(UUID.randomUUID()).content(episode).comment("episode review")
                    .watchNumber(1).watchedDate(LocalDate.of(2026, 10, 2))
                    .createdAt(LocalDateTime.of(2026, 10, 1, 17, 30)).build();
            ContentReview dateOnlyReview = ContentReview.builder()
                    .id(UUID.randomUUID()).userId(userId).contentId(seriesContentId)
                    .contentType(ContentType.SERIES)
                    .publicationKind(ReviewPublicationKind.WRITTEN_REVIEW)
                    .contextKind(ReviewContextKind.COMPLETION)
                    .comment("date-only review").scoreAtPublication(8).watchNumber(1)
                    .sourcePublishedOn(LocalDate.of(2026, 10, 2))
                    .createdAt(Instant.parse("2026-10-03T12:00:00Z"))
                    .updatedAt(Instant.parse("2026-10-03T12:00:00Z")).build();
            when(queryRepository.findRecentReviews(eq(userId), any(), any())).thenReturn(List.of(diaryReview));
            when(contentReviewRepository.findRecentWrittenReviewsByUserIdAndContentTypeIn(
                    eq(userId), eq(List.of(ContentType.SERIES.name(), ContentType.SEASON.name())), any()))
                    .thenReturn(List.of(dateOnlyReview));
            when(contentRepository.findAllById(List.of(seriesContentId))).thenReturn(List.of(series));
            when(contentMapper.contentToContentRefDto(episode)).thenReturn(episodeRef);
            when(contentMapper.contentToContentRefDto(series)).thenReturn(seriesRef);
            when(watchCompanionRepository.findByDiaryEntryIdIn(List.of(diaryReview.getId())))
                    .thenReturn(List.of());

            ProfileSummaryDataReader.Snapshot result = reader.read(userId, ContentType.SERIES);

            assertThat(result.recentReviews()).extracting(ProfileDiaryPreviewDTO::id)
                    .containsExactly(diaryReview.getId(), dateOnlyReview.getId());
            assertThat(result.recentReviews().get(1).activityDate()).isEqualTo(LocalDate.of(2026, 10, 2));
        } finally {
            TimeZone.setDefault(previousTimeZone);
        }
    }

    @Test
    void shouldReturnEmptyRecentReviewsWhenProfileReviewQueryReturnsNoRows() {
        ProfileSummaryDataReader.Snapshot result = reader.read(userId, ContentType.SERIES);

        assertThat(result.recentReviews()).isEmpty();
        assertThat(result.recentEpisodes()).isEmpty();
        verify(queryRepository).findRecentReviews(userId,
                List.of(ContentType.EPISODE),
                org.springframework.data.domain.PageRequest.of(0, 5));
        verify(contentReviewRepository).findRecentWrittenReviewsByUserIdAndContentTypeIn(
                userId, List.of(ContentType.SERIES.name(), ContentType.SEASON.name()),
                org.springframework.data.domain.PageRequest.of(0, 5));
    }

    @Test
    void shouldUseCurrentSeriesRatingsInsteadOfEpisodeDiaryScores() {
        reader.read(userId, ContentType.SERIES);

        verify(queryRepository).countLatestScoresByUserIdAndContentType(userId, ContentType.SERIES.name());
        org.mockito.Mockito.verify(queryRepository, org.mockito.Mockito.never())
                .countLatestScoresByUserIdAndContentType(userId, ContentType.EPISODE.name());
    }

    @Test
    void shouldLeavePreviewPosterResolutionToTheVisualCardReader() {
        Content episodeContent = Content.builder().id(UUID.randomUUID()).tmdbId(null)
                .seriesTmdbId("1399").seasonNumber(1).episodeNumber(1).type(ContentType.EPISODE).build();
        DiaryEntry episode = DiaryEntry.builder()
                .id(UUID.randomUUID()).content(episodeContent).comment("review")
                .watchNumber(1).watchedDate(LocalDate.now()).createdAt(LocalDateTime.now()).build();
        ContentRefDTO contentRef = new ContentRefDTO(episodeContent.getId(), null, ContentType.EPISODE,
                "1399", 1, 1, null, null, null, null);
        when(queryRepository.findRecentEpisodes(eq(userId), any())).thenReturn(List.of(episode));
        when(queryRepository.findRecentReviews(eq(userId), any(), any())).thenReturn(List.of(episode));
        when(contentMapper.contentToContentRefDto(episodeContent)).thenReturn(contentRef);
        when(watchCompanionRepository.findByDiaryEntryIdIn(List.of(episode.getId())))
                .thenReturn(List.of());

        ProfileSummaryDataReader.Snapshot result = reader.read(userId, ContentType.SERIES);

        assertThat(result.recentEpisodes()).hasSize(1);
        assertThat(result.recentReviews()).hasSize(1);
        assertThat(result.recentEpisodes().get(0).customPosterUrl()).isNull();
    }
}
