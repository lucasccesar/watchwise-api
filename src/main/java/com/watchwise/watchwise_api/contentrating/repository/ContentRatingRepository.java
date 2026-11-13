package com.watchwise.watchwise_api.contentrating.repository;

import com.watchwise.watchwise_api.contentrating.entity.ContentRating;
import com.watchwise.watchwise_api.content.entity.ContentType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ContentRatingRepository extends JpaRepository<ContentRating, UUID> {
    Optional<ContentRating> findByUserIdAndContentId(UUID userId, UUID contentId);

    List<ContentRating> findByUserIdAndContentIdIn(UUID userId, Collection<UUID> contentIds);

    @Query("""
            SELECT rating.contentId AS contentId, rating.score AS score, rating.version AS version
            FROM ContentRating rating
            WHERE rating.userId = :userId
              AND rating.contentType = :contentType
              AND rating.contentId IN :contentIds
            """)
    List<RatingState> findRatingStates(
            @Param("userId") UUID userId,
            @Param("contentType") ContentType contentType,
            @Param("contentIds") Collection<UUID> contentIds);

    List<ContentRating> findByUserIdAndContentTypeInOrderByScoreDescContentIdAsc(
            UUID userId, Collection<ContentType> contentTypes, Pageable pageable);

    List<ContentRating> findByUserIdAndContentTypeInOrderByScoreAscContentIdAsc(
            UUID userId, Collection<ContentType> contentTypes, Pageable pageable);

    @Query(value = """
            SELECT r.content_id AS contentId, AVG(r.score)::double precision AS averageScore,
                   COUNT(*) AS voteCount
            FROM content_ratings r JOIN users u ON u.id = r.user_id
            WHERE r.content_id IN (:contentIds) AND u.is_profile_public = true
            GROUP BY r.content_id
            """, nativeQuery = true)
    List<PublicRatingStats> findPublicStatsByContentIdIn(@Param("contentIds") Collection<UUID> contentIds);

    @Query(value = """
            SELECT r.score AS score, COUNT(*) AS count
            FROM content_ratings r JOIN users u ON u.id = r.user_id
            WHERE r.content_id = :contentId AND u.is_profile_public = true
            GROUP BY r.score ORDER BY r.score
            """, nativeQuery = true)
    List<PublicRatingDistribution> findPublicDistribution(@Param("contentId") UUID contentId);

    interface PublicRatingStats {
        UUID getContentId();
        Double getAverageScore();
        Long getVoteCount();
    }

    interface RatingState {
        UUID getContentId();
        Integer getScore();
        Long getVersion();
    }

    interface PublicRatingDistribution {
        Integer getScore();
        Long getCount();
    }
}
