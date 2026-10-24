package com.watchwise.watchwise_api.diarycompletion.repository;

import com.watchwise.watchwise_api.diarycompletion.entity.DiaryCompletionProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.UUID;
import java.util.Collection;

public interface DiaryCompletionProjectionRepository extends JpaRepository<DiaryCompletionProjection, UUID> {
    List<DiaryCompletionProjection> findByUserIdAndContentIdOrderByWatchNumber(UUID userId, UUID contentId);

    List<DiaryCompletionProjection> findByUserIdAndContentIdAndWatchNumber(
            UUID userId, UUID contentId, Integer watchNumber);

    @Query(value = """
            SELECT p.* FROM diary_completion_projections p
            JOIN contents c ON c.id = p.content_id
            JOIN users owner ON owner.id = p.user_id
            JOIN user_series_history_states state ON state.user_id = p.user_id
              AND state.series_tmdb_id = CASE WHEN c.type = 'SERIES' THEN c.tmdb_id ELSE c.series_tmdb_id END
            JOIN calendar_schedule_completeness marker
              ON marker.series_tmdb_id = state.series_tmdb_id
             AND marker.group_type = CASE WHEN c.type = 'SERIES' THEN 'SERIES' ELSE 'SEASON' END
             AND marker.season_number IS NOT DISTINCT FROM CASE WHEN c.type = 'SEASON' THEN c.season_number ELSE NULL END
             AND marker.region = owner.preferred_region
             AND marker.language = owner.preferred_language
             AND marker.scope_version = p.schedule_version
             AND marker.complete = TRUE
            WHERE p.user_id = :userId AND p.content_id IN (:contentIds)
              AND p.history_revision = state.history_revision
              AND p.history_revision = state.computed_revision
              AND (c.type = 'SEASON' OR p.schedule_version = state.schedule_version)
              AND p.valid_until > CURRENT_TIMESTAMP
              AND state.valid_until > CURRENT_TIMESTAMP
            ORDER BY p.watch_number DESC, p.ordered_at DESC, p.id DESC
            """, nativeQuery = true)
    List<DiaryCompletionProjection> findCurrentByUserIdAndContentIdIn(
            @Param("userId") UUID userId, @Param("contentIds") Collection<UUID> contentIds);

    long deleteByUserIdAndContentId(UUID userId, UUID contentId);

    @Query(value = """
            SELECT c.series_tmdb_id AS seriesTmdbId,
                   c.season_number AS seasonNumber,
                   p.schedule_version AS scheduleVersion
            FROM diary_completion_projections p
            JOIN contents c ON c.id = p.content_id
            WHERE p.user_id = :userId
              AND p.content_type = 'SEASON'
            """, nativeQuery = true)
    List<SeasonProjectionVersion> findSeasonProjectionVersions(@Param("userId") UUID userId);

    interface SeasonProjectionVersion {
        String getSeriesTmdbId();
        Integer getSeasonNumber();
        Long getScheduleVersion();
    }

    @Query(value = """
            SELECT p.content_id AS contentId, COUNT(*) AS count
            FROM diary_completion_projections p
            JOIN users u ON u.id = p.user_id
            JOIN contents c ON c.id = p.content_id
            JOIN user_series_history_states state ON state.user_id = p.user_id
              AND state.series_tmdb_id = CASE WHEN c.type = 'SERIES' THEN c.tmdb_id ELSE c.series_tmdb_id END
            JOIN calendar_schedule_completeness marker
              ON marker.series_tmdb_id = state.series_tmdb_id
             AND marker.group_type = CASE WHEN c.type = 'SERIES' THEN 'SERIES' ELSE 'SEASON' END
             AND marker.season_number IS NOT DISTINCT FROM CASE WHEN c.type = 'SEASON' THEN c.season_number ELSE NULL END
             AND marker.region = u.preferred_region
             AND marker.language = u.preferred_language
             AND marker.scope_version = p.schedule_version
             AND marker.complete = TRUE
            WHERE p.content_id IN (:contentIds) AND u.is_profile_public = true
              AND p.history_revision = state.history_revision
              AND p.history_revision = state.computed_revision
              AND (c.type = 'SEASON' OR p.schedule_version = state.schedule_version)
              AND p.valid_until > CURRENT_TIMESTAMP
              AND state.valid_until > CURRENT_TIMESTAMP
            GROUP BY p.content_id
            """, nativeQuery = true)
    List<PublicCompletionCount> countPublicCurrentPassesByContentIdIn(
            @Param("contentIds") java.util.Collection<UUID> contentIds);

    interface PublicCompletionCount {
        UUID getContentId();
        Long getCount();
    }
}
