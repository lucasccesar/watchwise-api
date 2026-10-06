package com.watchwise.watchwise_api.summary.service.impl;

import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.mapper.ContentMapper;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.contentposter.service.UserContentPosterService;
import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import com.watchwise.watchwise_api.diaryentry.repository.WatchCompanionRepository;
import com.watchwise.watchwise_api.dropped.entity.DroppedEntry;
import com.watchwise.watchwise_api.dropped.repository.DroppedEntryRepository;
import com.watchwise.watchwise_api.summary.repository.ProfileSummaryQueryRepository;
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
import java.util.List;
import java.util.Map;
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
    private UserContentPosterService userContentPosterService;

    private ProfileSummaryDataReaderImpl reader;
    private UUID userId;

    @BeforeEach
    void setUp() {
        reader = new ProfileSummaryDataReaderImpl(queryRepository, droppedEntryRepository, contentRepository,
                contentMapper, watchCompanionRepository, userMapper, userContentPosterService);
        userId = UUID.randomUUID();
        org.mockito.Mockito.lenient().when(queryRepository.findRecentReviews(any(), any(), any())).thenReturn(List.of());
        org.mockito.Mockito.lenient().when(queryRepository.findRecentTopLevelEntries(any(), any(), any())).thenReturn(List.of());
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
    void shouldReturnEmptyRecentReviewsWhenProfileReviewQueryReturnsNoRows() {
        ProfileSummaryDataReader.Snapshot result = reader.read(userId, ContentType.SERIES);

        assertThat(result.recentReviews()).isEmpty();
        assertThat(result.recentEpisodes()).isEmpty();
        verify(queryRepository).findRecentReviews(userId,
                List.of(ContentType.SERIES, ContentType.SEASON, ContentType.EPISODE),
                org.springframework.data.domain.PageRequest.of(0, 5));
    }

    @Test
    void shouldLoadPreviewPostersOnceAcrossEpisodesAndReviews() {
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
        when(userContentPosterService.findByUserAndContentIds(userId, List.of(episodeContent.getId())))
                .thenReturn(Map.of());
        when(watchCompanionRepository.findByDiaryEntryIdIn(List.of(episode.getId())))
                .thenReturn(List.of());

        ProfileSummaryDataReader.Snapshot result = reader.read(userId, ContentType.SERIES);

        assertThat(result.recentEpisodes()).hasSize(1);
        assertThat(result.recentReviews()).hasSize(1);
        verify(userContentPosterService).findByUserAndContentIds(userId, List.of(episodeContent.getId()));
    }
}
