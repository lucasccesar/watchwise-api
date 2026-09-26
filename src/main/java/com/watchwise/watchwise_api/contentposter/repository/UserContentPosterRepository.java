package com.watchwise.watchwise_api.contentposter.repository;

import com.watchwise.watchwise_api.contentposter.entity.UserContentPoster;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserContentPosterRepository extends JpaRepository<UserContentPoster, UUID> {

    Optional<UserContentPoster> findByUserIdAndContentId(UUID userId, UUID contentId);

    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO user_content_posters (
                id, user_id, content_id, custom_poster_url, created_at, updated_at
            ) VALUES (
                :id, :userId, :contentId, :customPosterUrl, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
            )
            ON CONFLICT (user_id, content_id) DO UPDATE
            SET custom_poster_url = EXCLUDED.custom_poster_url,
                updated_at = CURRENT_TIMESTAMP
            """, nativeQuery = true)
    void upsert(
            @Param("id") UUID id,
            @Param("userId") UUID userId,
            @Param("contentId") UUID contentId,
            @Param("customPosterUrl") String customPosterUrl);

    @Modifying
    @Transactional
    @Query("""
            DELETE FROM UserContentPoster poster
            WHERE poster.user.id = :userId
            AND poster.content.id = :contentId
            """)
    void deleteByUserIdAndContentId(
            @Param("userId") UUID userId,
            @Param("contentId") UUID contentId);

    @Query("""
            SELECT poster.content.id AS contentId,
                   poster.customPosterUrl AS customPosterUrl
            FROM UserContentPoster poster
            WHERE poster.user.id = :userId
            AND poster.content.id IN :contentIds
            """)
    List<ContentPosterProjection> findByUserIdAndContentIdIn(
            @Param("userId") UUID userId,
            @Param("contentIds") Collection<UUID> contentIds);

    @Query("""
            SELECT poster.content.tmdbId AS seriesTmdbId,
                   poster.customPosterUrl AS customPosterUrl
            FROM UserContentPoster poster
            WHERE poster.user.id = :userId
            AND poster.content.type = com.watchwise.watchwise_api.content.entity.ContentType.SERIES
            AND poster.content.tmdbId IN :seriesTmdbIds
            """)
    List<SeriesPosterProjection> findByUserIdAndSeriesTmdbIdIn(
            @Param("userId") UUID userId,
            @Param("seriesTmdbIds") Collection<String> seriesTmdbIds);

    interface ContentPosterProjection {
        UUID getContentId();

        String getCustomPosterUrl();
    }

    interface SeriesPosterProjection {
        String getSeriesTmdbId();

        String getCustomPosterUrl();
    }
}
