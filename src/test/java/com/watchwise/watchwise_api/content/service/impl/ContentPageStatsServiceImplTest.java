package com.watchwise.watchwise_api.content.service.impl;

import com.watchwise.watchwise_api.content.dto.ContentPageStatsDTO;
import com.watchwise.watchwise_api.content.dto.ContentStatsResponseDTO;
import com.watchwise.watchwise_api.content.dto.RatingDistributionDTO;
import com.watchwise.watchwise_api.content.service.ContentStatsService;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContentPageStatsServiceImplTest {

    @Mock
    private ContentStatsService contentStatsService;

    @Mock
    private DiaryEntryRepository diaryEntryRepository;

    private ContentPageStatsServiceImpl statsService;
    private UUID contentId;

    @BeforeEach
    void setUp() {
        statsService = new ContentPageStatsServiceImpl(contentStatsService, diaryEntryRepository);
        contentId = UUID.randomUUID();
    }

    @Test
    @DisplayName("[getStats] Should Build All Ten Rating Buckets - When Public Scored Diary Entries Exist")
    void shouldBuildAllTenRatingBucketsWhenPublicScoredDiaryEntriesExist() {
        when(contentStatsService.getStats(contentId))
                .thenReturn(new ContentStatsResponseDTO(contentId, 7.25, 8, 2, 3));
        when(diaryEntryRepository.findPublicScoreDistributionByContentId(contentId))
                .thenReturn(List.of(scoreCount(1, 2), scoreCount(4, 3), scoreCount(10, 1)));

        ContentPageStatsDTO result = statsService.getStats(contentId);

        assertThat(result.contentId()).isEqualTo(contentId);
        assertThat(result.averageScore()).isEqualTo(7.25);
        assertThat(result.ratingsCount()).isEqualTo(6);
        assertThat(result.playsCount()).isEqualTo(8);
        assertThat(result.commentsCount()).isEqualTo(3);
        assertThat(result.ratingsDistribution())
                .extracting(RatingDistributionDTO::score, RatingDistributionDTO::count)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1, 2L),
                        org.assertj.core.groups.Tuple.tuple(2, 0L),
                        org.assertj.core.groups.Tuple.tuple(3, 0L),
                        org.assertj.core.groups.Tuple.tuple(4, 3L),
                        org.assertj.core.groups.Tuple.tuple(5, 0L),
                        org.assertj.core.groups.Tuple.tuple(6, 0L),
                        org.assertj.core.groups.Tuple.tuple(7, 0L),
                        org.assertj.core.groups.Tuple.tuple(8, 0L),
                        org.assertj.core.groups.Tuple.tuple(9, 0L),
                        org.assertj.core.groups.Tuple.tuple(10, 1L));
        verify(diaryEntryRepository).findPublicScoreDistributionByContentId(contentId);
    }

    @Test
    @DisplayName("[getStats] Should Return Zero Rating Buckets - When No Public Scores Exist")
    void shouldReturnZeroRatingBucketsWhenNoPublicScoresExist() {
        when(contentStatsService.getStats(contentId))
                .thenReturn(new ContentStatsResponseDTO(contentId, null, 0, 0, 0));
        when(diaryEntryRepository.findPublicScoreDistributionByContentId(contentId)).thenReturn(List.of());

        ContentPageStatsDTO result = statsService.getStats(contentId);

        assertThat(result.ratingsCount()).isZero();
        assertThat(result.ratingsDistribution()).hasSize(10)
                .allSatisfy(bucket -> assertThat(bucket.count()).isZero());
    }

    private DiaryEntryRepository.PublicScoreDistribution scoreCount(int score, long count) {
        return new DiaryEntryRepository.PublicScoreDistribution() {
            @Override
            public Integer getScore() {
                return score;
            }

            @Override
            public long getCount() {
                return count;
            }
        };
    }
}
