package com.watchwise.watchwise_api.diarycompletion.repository;

import com.watchwise.watchwise_api.diarycompletion.entity.DiaryCompletionProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.UUID;

public interface DiaryCompletionProjectionRepository extends JpaRepository<DiaryCompletionProjection, UUID> {
    List<DiaryCompletionProjection> findByUserIdAndContentIdOrderByWatchNumber(UUID userId, UUID contentId);

    List<DiaryCompletionProjection> findByUserIdAndContentIdAndWatchNumber(
            UUID userId, UUID contentId, Integer watchNumber);

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
}
