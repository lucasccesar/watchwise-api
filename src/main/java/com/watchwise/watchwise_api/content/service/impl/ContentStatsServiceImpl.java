package com.watchwise.watchwise_api.content.service.impl;

import com.watchwise.watchwise_api.comment.repository.CommentRepository;
import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.content.dto.ContentStatsResponseDTO;
import com.watchwise.watchwise_api.content.service.ContentStatsService;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ContentStatsServiceImpl implements ContentStatsService {

    static final int MAX_BATCH_IDS = 100;

    private final DiaryEntryRepository diaryEntryRepository;
    private final CommentRepository commentRepository;

    @Override
    public ContentStatsResponseDTO getStats(UUID contentId) {
        return getStatsBatch(List.of(contentId)).get(0);
    }

    @Override
    public List<ContentStatsResponseDTO> getStatsBatch(List<UUID> contentIds) {
        if (contentIds == null || contentIds.isEmpty()) {
            throw new BadRequestException("ids must not be empty");
        }
        if (contentIds.size() > MAX_BATCH_IDS) {
            throw new BadRequestException("Cannot request stats for more than " + MAX_BATCH_IDS + " contents at once");
        }

        Map<UUID, DiaryEntryRepository.ContentStats> diaryStatsByContentId = diaryEntryRepository
                .findContentStatsByContentIdIn(contentIds).stream()
                .collect(Collectors.toMap(DiaryEntryRepository.ContentStats::getContentId, stats -> stats));

        Map<UUID, Long> commentsCountByContentId = commentRepository.countByContentIdIn(contentIds).stream()
                .collect(Collectors.toMap(CommentRepository.ContentCommentCount::getContentId, CommentRepository.ContentCommentCount::getCount));

        return contentIds.stream()
                .map(contentId -> toResponseDto(contentId, diaryStatsByContentId.get(contentId), commentsCountByContentId))
                .toList();
    }

    private ContentStatsResponseDTO toResponseDto(
            UUID contentId, DiaryEntryRepository.ContentStats diaryStats, Map<UUID, Long> commentsCountByContentId) {
        Double averageScore = diaryStats == null ? null : diaryStats.getAverageScore();
        long playsCount = diaryStats == null ? 0 : diaryStats.getPlaysCount();
        long reviewsCount = diaryStats == null ? 0 : diaryStats.getReviewsCount();
        long commentsCount = commentsCountByContentId.getOrDefault(contentId, 0L);

        return new ContentStatsResponseDTO(contentId, averageScore, playsCount, reviewsCount, commentsCount);
    }
}
