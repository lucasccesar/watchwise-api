package com.watchwise.watchwise_api.contentreview.service;

import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.contentreview.entity.ContentReview;
import com.watchwise.watchwise_api.contentreview.entity.ReviewPublicationKind;
import com.watchwise.watchwise_api.contentreview.repository.ContentReviewRepository;
import com.watchwise.watchwise_api.user.service.UserVisibilityService;
import com.watchwise.watchwise_api.userblock.service.UserBlockAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ContentReviewSocialTargetReader {

    private final ContentReviewRepository contentReviewRepository;
    private final UserBlockAccessService userBlockAccessService;
    private final UserVisibilityService userVisibilityService;

    @Transactional(readOnly = true)
    public ContentReview findVisible(UUID viewerId, UUID reviewId) {
        ContentReview review = findReview(reviewId);
        assertVisible(viewerId, review);
        return review;
    }

    @Transactional
    public ContentReview findWritable(UUID actorId, UUID reviewId) {
        ContentReview review = findReview(reviewId);
        assertProfileVisibility(actorId, review);
        if (review.getPublicationKind() != ReviewPublicationKind.WRITTEN_REVIEW) {
            throw new NotFoundException("Review not found");
        }
        return review;
    }

    private ContentReview findReview(UUID reviewId) {
        return contentReviewRepository.findById(reviewId)
                .orElseThrow(() -> new NotFoundException("Review not found"));
    }

    private void assertVisible(UUID viewerId, ContentReview review) {
        userBlockAccessService.assertContentVisible(viewerId, review.getUserId());
        assertProfileVisibility(viewerId, review);
    }

    private void assertProfileVisibility(UUID viewerId, ContentReview review) {
        if (viewerId.equals(review.getUserId())) {
            return;
        }
        userVisibilityService.assertCanView(viewerId, review.getUserId());
    }
}
