package com.watchwise.watchwise_api.content.service.impl;

import com.watchwise.watchwise_api.comment.repository.CommentRepository;
import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.content.dto.ContentStatsResponseDTO;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.content.service.ContentStatsService;
import com.watchwise.watchwise_api.contentrating.repository.ContentRatingRepository;
import com.watchwise.watchwise_api.contentreview.repository.ContentReviewRepository;
import com.watchwise.watchwise_api.diarycompletion.repository.DiaryCompletionProjectionRepository;
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
    private final ContentRepository contentRepository;
    private final ContentRatingRepository ratingRepository;
    private final ContentReviewRepository reviewRepository;
    private final DiaryCompletionProjectionRepository projectionRepository;

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

        Map<UUID, ContentType> typeById = contentRepository.findAllById(contentIds).stream()
                .collect(Collectors.toMap(Content::getId, Content::getType));
        List<UUID> watchIds = contentIds.stream()
                .filter(id -> typeById.get(id) == ContentType.MOVIE || typeById.get(id) == ContentType.EPISODE)
                .distinct().toList();
        List<UUID> opinionIds = contentIds.stream()
                .filter(id -> typeById.get(id) == ContentType.SERIES || typeById.get(id) == ContentType.SEASON)
                .distinct().toList();
        Map<UUID, DiaryEntryRepository.ContentStats> diaryStatsByContentId = (watchIds.isEmpty()
                ? List.<DiaryEntryRepository.ContentStats>of()
                : diaryEntryRepository.findContentStatsByContentIdIn(watchIds)).stream()
                .collect(Collectors.toMap(DiaryEntryRepository.ContentStats::getContentId, stats -> stats));
        Map<UUID, ContentRatingRepository.PublicRatingStats> ratings = (opinionIds.isEmpty()
                ? List.<ContentRatingRepository.PublicRatingStats>of()
                : ratingRepository.findPublicStatsByContentIdIn(opinionIds)).stream()
                .collect(Collectors.toMap(ContentRatingRepository.PublicRatingStats::getContentId, row -> row));
        Map<UUID, Long> reviews = (opinionIds.isEmpty()
                ? List.<ContentReviewRepository.PublicReviewCount>of()
                : reviewRepository.countPublicReviewsByContentIdIn(opinionIds)).stream()
                .collect(Collectors.toMap(ContentReviewRepository.PublicReviewCount::getContentId,
                        ContentReviewRepository.PublicReviewCount::getCount));
        Map<UUID, Long> completions = (opinionIds.isEmpty()
                ? List.<DiaryCompletionProjectionRepository.PublicCompletionCount>of()
                : projectionRepository.countPublicCurrentPassesByContentIdIn(opinionIds)).stream()
                .collect(Collectors.toMap(DiaryCompletionProjectionRepository.PublicCompletionCount::getContentId,
                        DiaryCompletionProjectionRepository.PublicCompletionCount::getCount));

        Map<UUID, Long> commentsCountByContentId = commentRepository.countByContentIdIn(contentIds).stream()
                .collect(Collectors.toMap(CommentRepository.ContentCommentCount::getContentId, CommentRepository.ContentCommentCount::getCount));

        return contentIds.stream()
                .map(contentId -> opinionIds.contains(contentId)
                        ? new ContentStatsResponseDTO(contentId,
                                ratings.containsKey(contentId) ? ratings.get(contentId).getAverageScore() : null,
                                completions.getOrDefault(contentId, 0L), reviews.getOrDefault(contentId, 0L),
                                commentsCountByContentId.getOrDefault(contentId, 0L))
                        : toResponseDto(contentId, diaryStatsByContentId.get(contentId), commentsCountByContentId))
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
