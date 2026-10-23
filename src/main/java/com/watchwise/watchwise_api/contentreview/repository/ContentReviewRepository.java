package com.watchwise.watchwise_api.contentreview.repository;

import com.watchwise.watchwise_api.contentreview.entity.ContentReview;
import com.watchwise.watchwise_api.contentreview.entity.ReviewPublicationKind;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.UUID;
import java.util.Collection;

public interface ContentReviewRepository extends JpaRepository<ContentReview, UUID> {
    List<ContentReview> findByUserIdAndContentIdOrderByCreatedAtDesc(UUID userId, UUID contentId);

    boolean existsByUserIdAndContentIdAndWatchNumberAndPublicationKind(
            UUID userId, UUID contentId, Integer watchNumber, ReviewPublicationKind publicationKind);

    boolean existsByUserIdAndContentIdAndScoreAtPublicationIsNotNullAndSourcePublishedAtIsNullAndSourcePublishedOnIsNull(
            UUID userId, UUID contentId);

    @Query(value = """
            SELECT review.*
            FROM content_reviews review
            WHERE review.user_id = :userId
              AND review.content_id = :contentId
              AND review.score_at_publication IS NOT NULL
              AND review.source_published_at IS NOT NULL
              AND review.source_published_at = (
                  SELECT MAX(candidate.source_published_at)
                  FROM content_reviews candidate
                  WHERE candidate.user_id = :userId
                    AND candidate.content_id = :contentId
                    AND candidate.score_at_publication IS NOT NULL
                    AND candidate.source_published_at IS NOT NULL
              )
            UNION ALL
            SELECT review.*
            FROM content_reviews review
            WHERE review.user_id = :userId
              AND review.content_id = :contentId
              AND review.score_at_publication IS NOT NULL
              AND review.source_published_at IS NULL
              AND review.source_published_on IS NOT NULL
              AND review.source_published_on = (
                  SELECT MAX(candidate.source_published_on)
                  FROM content_reviews candidate
                  WHERE candidate.user_id = :userId
                    AND candidate.content_id = :contentId
                    AND candidate.score_at_publication IS NOT NULL
                    AND candidate.source_published_at IS NULL
                    AND candidate.source_published_on IS NOT NULL
              )
            """, nativeQuery = true)
    List<ContentReview> findLatestScoredPublicationBoundary(
            @Param("userId") UUID userId,
            @Param("contentId") UUID contentId);

    List<ContentReview> findByUserIdAndContentIdInOrderByCreatedAtDesc(
            UUID userId, Collection<UUID> contentIds);
}
