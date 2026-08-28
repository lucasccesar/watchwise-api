package com.watchwise.watchwise_api.content.service.impl;

import com.watchwise.watchwise_api.comment.repository.CommentRepository;
import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.content.dto.ContentStatsResponseDTO;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContentStatsServiceImplTest {

    @Mock
    private DiaryEntryRepository diaryEntryRepository;

    @Mock
    private CommentRepository commentRepository;

    @InjectMocks
    private ContentStatsServiceImpl contentStatsService;

    private UUID contentId;

    @BeforeEach
    void setUp() {
        contentId = UUID.randomUUID();
        lenient().when(diaryEntryRepository.findContentStatsByContentIdIn(List.of(contentId))).thenReturn(List.of());
        lenient().when(commentRepository.countByContentIdIn(List.of(contentId))).thenReturn(List.of());
    }

    @Test
    @DisplayName("[getStats] Should Return Aggregated Stats - When Content Has Diary Entries And Comments")
    void shouldReturnAggregatedStatsWhenContentHasDiaryEntriesAndComments() {
        when(diaryEntryRepository.findContentStatsByContentIdIn(List.of(contentId)))
                .thenReturn(List.of(contentStats(contentId, 8.5, 12, 3)));
        when(commentRepository.countByContentIdIn(List.of(contentId)))
                .thenReturn(List.of(commentCount(contentId, 5)));

        ContentStatsResponseDTO result = contentStatsService.getStats(contentId);

        assertThat(result.contentId()).isEqualTo(contentId);
        assertThat(result.averageScore()).isEqualTo(8.5);
        assertThat(result.playsCount()).isEqualTo(12);
        assertThat(result.reviewsCount()).isEqualTo(3);
        assertThat(result.commentsCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("[getStats] Should Return Zeroed Stats With Null Average Score - When Content Has No Diary Entries Or Comments")
    void shouldReturnZeroedStatsWhenContentHasNoDiaryEntriesOrComments() {
        ContentStatsResponseDTO result = contentStatsService.getStats(contentId);

        assertThat(result.contentId()).isEqualTo(contentId);
        assertThat(result.averageScore()).isNull();
        assertThat(result.playsCount()).isZero();
        assertThat(result.reviewsCount()).isZero();
        assertThat(result.commentsCount()).isZero();
    }

    @Test
    @DisplayName("[getStatsBatch] Should Return One Entry Per Requested Id, Preserving Order - When Only Some Ids Have Data")
    void shouldReturnOneEntryPerRequestedIdPreservingOrderWhenOnlySomeIdsHaveData() {
        UUID withData = UUID.randomUUID();
        UUID withoutData = UUID.randomUUID();
        when(diaryEntryRepository.findContentStatsByContentIdIn(List.of(withData, withoutData)))
                .thenReturn(List.of(contentStats(withData, 6.0, 4, 1)));
        when(commentRepository.countByContentIdIn(List.of(withData, withoutData))).thenReturn(List.of());

        List<ContentStatsResponseDTO> result = contentStatsService.getStatsBatch(List.of(withData, withoutData));

        assertThat(result).extracting(ContentStatsResponseDTO::contentId).containsExactly(withData, withoutData);
        assertThat(result.get(0).playsCount()).isEqualTo(4);
        assertThat(result.get(1).playsCount()).isZero();
        assertThat(result.get(1).averageScore()).isNull();
    }

    @Test
    @DisplayName("[getStatsBatch] Should Throw BadRequestException - When Ids Is Empty")
    void shouldThrowBadRequestExceptionWhenIdsIsEmpty() {
        assertThatThrownBy(() -> contentStatsService.getStatsBatch(Collections.emptyList()))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("ids must not be empty");
    }

    @Test
    @DisplayName("[getStatsBatch] Should Throw BadRequestException - When Ids Exceed The Batch Limit")
    void shouldThrowBadRequestExceptionWhenIdsExceedTheBatchLimit() {
        List<UUID> tooMany = java.util.stream.Stream.generate(UUID::randomUUID)
                .limit(ContentStatsServiceImpl.MAX_BATCH_IDS + 1)
                .toList();

        assertThatThrownBy(() -> contentStatsService.getStatsBatch(tooMany))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Cannot request stats for more than " + ContentStatsServiceImpl.MAX_BATCH_IDS + " contents at once");
    }

    private DiaryEntryRepository.ContentStats contentStats(UUID id, Double averageScore, long playsCount, long reviewsCount) {
        return new DiaryEntryRepository.ContentStats() {
            @Override
            public UUID getContentId() {
                return id;
            }

            @Override
            public Double getAverageScore() {
                return averageScore;
            }

            @Override
            public long getPlaysCount() {
                return playsCount;
            }

            @Override
            public long getReviewsCount() {
                return reviewsCount;
            }
        };
    }

    private CommentRepository.ContentCommentCount commentCount(UUID id, long count) {
        return new CommentRepository.ContentCommentCount() {
            @Override
            public UUID getContentId() {
                return id;
            }

            @Override
            public long getCount() {
                return count;
            }
        };
    }
}
