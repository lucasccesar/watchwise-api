package com.watchwise.watchwise_api.summary.service.impl;

import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.mapper.ContentMapper;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.contentposter.service.UserContentPosterService;
import com.watchwise.watchwise_api.diaryentry.mapper.DiaryEntryMapper;
import com.watchwise.watchwise_api.diaryentry.repository.WatchCompanionRepository;
import com.watchwise.watchwise_api.summary.dto.AllTimeEditionStatsDTO;
import com.watchwise.watchwise_api.summary.repository.AllTimeStatsQueryRepository;
import com.watchwise.watchwise_api.summary.repository.ProfileSummaryQueryRepository;
import com.watchwise.watchwise_api.top5entry.repository.Top5EntryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AllTimeStatsReaderImplTest {

    @Mock
    private AllTimeStatsQueryRepository queryRepository;

    @Mock
    private ProfileSummaryQueryRepository profileSummaryQueryRepository;

    @Mock
    private ContentRepository contentRepository;

    @Mock
    private ContentMapper contentMapper;

    @Mock
    private DiaryEntryMapper diaryEntryMapper;

    @Mock
    private Top5EntryRepository top5EntryRepository;

    @Mock
    private UserContentPosterService userContentPosterService;

    @InjectMocks
    private AllTimeStatsReaderImpl reader;

    @Test
    @DisplayName("[read] Should Return Empty Movie Edition - When User Has No Movie Entries")
    void shouldReturnEmptyMovieEditionWhenUserHasNoMovieEntries() {
        UUID userId = UUID.randomUUID();
        when(queryRepository.findMinWatchedDateByUserIdAndContentType(userId, ContentType.MOVIE)).thenReturn(null);
        when(profileSummaryQueryRepository.countByUserIdAndContentType(userId, ContentType.MOVIE)).thenReturn(0L);
        when(profileSummaryQueryRepository.sumRuntimeMinutesByUserIdAndContentType(userId, ContentType.MOVIE)).thenReturn(0L);
        when(profileSummaryQueryRepository.countDistinctMoviesByGenre(userId)).thenReturn(List.of());
        when(profileSummaryQueryRepository.countLatestScoresByUserIdAndContentType(userId, ContentType.MOVIE.name()))
                .thenReturn(List.of());
        when(queryRepository.countByUserIdAndContentTypeGroupByYear(userId, ContentType.MOVIE.name()))
                .thenReturn(List.of());
        when(queryRepository.countDistinctMoviesByDecade(userId)).thenReturn(List.of());
        when(queryRepository.countDistinctMoviesByCountry(userId)).thenReturn(List.of());
        when(queryRepository.countMostLoggedMovies(org.mockito.ArgumentMatchers.eq(userId), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());
        when(queryRepository.findTopRatedByUserIdAndContentType(org.mockito.ArgumentMatchers.eq(userId),
                org.mockito.ArgumentMatchers.eq(ContentType.MOVIE), org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        when(queryRepository.findBottomRatedByUserIdAndContentType(org.mockito.ArgumentMatchers.eq(userId),
                org.mockito.ArgumentMatchers.eq(ContentType.MOVIE), org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        when(queryRepository.countTheaterVisitsByUserId(userId)).thenReturn(0L);

        AllTimeEditionStatsDTO result = reader.read(userId, ContentType.MOVIE);

        assertThat(result.type()).isEqualTo(ContentType.MOVIE);
        assertThat(result.watchedCount()).isZero();
        assertThat(result.minutesWatched()).isZero();
        assertThat(result.totalTheaterVisits()).isZero();
        assertThat(result.watchCountByYear()).isEmpty();
        assertThat(result.ratingsDistribution()).isEmpty();
    }
}
