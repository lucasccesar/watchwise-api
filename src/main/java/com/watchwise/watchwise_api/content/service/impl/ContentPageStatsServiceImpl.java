package com.watchwise.watchwise_api.content.service.impl;

import com.watchwise.watchwise_api.content.dto.ContentPageStatsDTO;
import com.watchwise.watchwise_api.content.dto.ContentStatsResponseDTO;
import com.watchwise.watchwise_api.content.dto.RatingDistributionDTO;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.content.service.ContentPageStatsService;
import com.watchwise.watchwise_api.content.service.ContentStatsService;
import com.watchwise.watchwise_api.contentrating.repository.ContentRatingRepository;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ContentPageStatsServiceImpl implements ContentPageStatsService {

    private static final int MIN_SCORE = 1;
    private static final int MAX_SCORE = 10;

    private final ContentStatsService contentStatsService;
    private final DiaryEntryRepository diaryEntryRepository;
    private final ContentRepository contentRepository;
    private final ContentRatingRepository ratingRepository;

    @Override
    public ContentPageStatsDTO getStats(UUID contentId) {
        ContentStatsResponseDTO existingStats = contentStatsService.getStats(contentId);
        boolean opinionContent = contentRepository.findById(contentId)
                .map(content -> content.getType() == ContentType.SERIES || content.getType() == ContentType.SEASON)
                .orElse(false);
        long[] countsByScore = new long[MAX_SCORE + 1];
        if (opinionContent) {
            for (ContentRatingRepository.PublicRatingDistribution row : ratingRepository.findPublicDistribution(contentId)) {
                Integer score = row.getScore();
                if (score != null && score >= MIN_SCORE && score <= MAX_SCORE) {
                    countsByScore[score] += row.getCount();
                }
            }
        } else {
            for (DiaryEntryRepository.PublicScoreDistribution row :
                    diaryEntryRepository.findPublicScoreDistributionByContentId(contentId)) {
                Integer score = row.getScore();
                if (score != null && score >= MIN_SCORE && score <= MAX_SCORE) {
                    countsByScore[score] += row.getCount();
                }
            }
        }

        List<RatingDistributionDTO> distribution = new ArrayList<>(MAX_SCORE);
        long ratingsCount = 0;
        for (int score = MIN_SCORE; score <= MAX_SCORE; score++) {
            long count = countsByScore[score];
            ratingsCount += count;
            distribution.add(new RatingDistributionDTO(score, count));
        }

        return new ContentPageStatsDTO(
                existingStats.contentId(),
                existingStats.averageScore(),
                ratingsCount,
                distribution,
                existingStats.playsCount(),
                existingStats.commentsCount());
    }
}
