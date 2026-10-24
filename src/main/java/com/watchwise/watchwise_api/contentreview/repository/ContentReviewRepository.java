package com.watchwise.watchwise_api.contentreview.repository;

import com.watchwise.watchwise_api.contentreview.entity.ContentReview;
import com.watchwise.watchwise_api.contentreview.entity.ReviewPublicationKind;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import java.time.LocalDateTime;
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

    @Query(value = """
            SELECT r.content_id AS contentId, COUNT(*) AS count
            FROM content_reviews r JOIN users u ON u.id = r.user_id
            WHERE r.content_id IN (:contentIds) AND u.is_profile_public = true
              AND r.comment IS NOT NULL AND btrim(r.comment) <> ''
            GROUP BY r.content_id
            """, nativeQuery = true)
    List<PublicReviewCount> countPublicReviewsByContentIdIn(@Param("contentIds") Collection<UUID> contentIds);

    interface PublicReviewCount {
        UUID getContentId();
        Long getCount();
    }

    @Query(value = """
            SELECT review.*
            FROM content_reviews review
            JOIN followers follow ON follow.followed_id = review.user_id
              AND follow.follower_id = :viewerId AND follow.status = 'ACCEPTED'
            WHERE review.user_id IN (:userIds)
              AND review.publication_kind = 'WRITTEN_REVIEW'
              AND review.comment IS NOT NULL AND btrim(review.comment) <> ''
              AND (review.source_published_at IS NOT NULL OR review.source_published_on IS NOT NULL)
              AND NOT EXISTS (SELECT 1 FROM user_blocks block
                              WHERE (block.blocker_id = :viewerId AND block.blocked_id = review.user_id)
                                 OR (block.blocker_id = review.user_id AND block.blocked_id = :viewerId))
              AND (CAST(:cursorCreatedAt AS timestamp) IS NULL
                   OR COALESCE(review.source_published_at AT TIME ZONE 'UTC',
                               review.source_published_on::timestamp) < :cursorCreatedAt
                   OR (COALESCE(review.source_published_at AT TIME ZONE 'UTC',
                                review.source_published_on::timestamp) = :cursorCreatedAt
                       AND :cursorId IS NOT NULL AND review.id < :cursorId))
            ORDER BY COALESCE(review.source_published_at AT TIME ZONE 'UTC',
                              review.source_published_on::timestamp) DESC, review.id DESC
            """, nativeQuery = true)
    List<ContentReview> findFeedCandidates(
            @Param("userIds") Collection<UUID> userIds,
            @Param("viewerId") UUID viewerId,
            @Param("cursorCreatedAt") LocalDateTime cursorCreatedAt,
            @Param("cursorId") UUID cursorId,
            Pageable pageable);
}
