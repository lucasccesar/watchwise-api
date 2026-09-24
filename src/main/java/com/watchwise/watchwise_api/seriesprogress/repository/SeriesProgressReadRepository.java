package com.watchwise.watchwise_api.seriesprogress.repository;

import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.repository.Repository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

public interface SeriesProgressReadRepository extends Repository<DiaryEntry, UUID> {

    default Page<SeriesProgressCandidate> findCandidatesByUserId(
            UUID userId, SeriesProgressSort sort, Pageable pageable) {
        return findCandidatesByUserId(userId, sort, Sort.Direction.DESC, pageable);
    }

    default Page<SeriesProgressCandidate> findCandidatesByUserId(
            UUID userId, SeriesProgressSort sort, Sort.Direction direction, Pageable pageable) {
        return findCandidatesByUserIdAndSort(
                userId,
                Objects.requireNonNull(sort).name(),
                Objects.requireNonNull(direction).name(),
                pageable);
    }

    @Query(value = """
            WITH episode_entries AS (
                SELECT c.series_tmdb_id AS series_tmdb_id,
                       c.season_number AS season_number,
                       c.episode_number AS episode_number,
                       c.runtime_minutes AS runtime_minutes,
                       d.watched_date AS watched_date,
                       d.created_at AS created_at,
                       d.id AS diary_entry_id,
                       COALESCE(d.watched_date, d.created_at::date) AS effective_date
                FROM diary_entries d
                JOIN contents c ON c.id = d.content_id
                WHERE d.user_id = :userId
                AND c.type = 'EPISODE'
                AND c.season_number > 0
            ),
            candidate_series AS (
                SELECT DISTINCT ee.series_tmdb_id
                FROM episode_entries ee
                WHERE NOT EXISTS (
                    SELECT 1
                    FROM diary_entries completion
                    JOIN contents completed_series ON completed_series.id = completion.content_id
                    WHERE completion.user_id = :userId
                    AND completed_series.type = 'SERIES'
                    AND completed_series.tmdb_id = ee.series_tmdb_id
                )
                AND NOT EXISTS (
                    SELECT 1
                    FROM dropped_entries dropped
                    JOIN contents dropped_series ON dropped_series.id = dropped.content_id
                    WHERE dropped.user_id = :userId
                    AND dropped.type = 'SERIES'
                    AND dropped_series.type = 'SERIES'
                    AND dropped_series.tmdb_id = ee.series_tmdb_id
                )
            ),
            distinct_episode_coordinates AS (
                SELECT DISTINCT ee.series_tmdb_id,
                                ee.season_number,
                                ee.episode_number,
                                ee.runtime_minutes
                FROM episode_entries ee
                JOIN candidate_series cs ON cs.series_tmdb_id = ee.series_tmdb_id
            ),
            series_aggregates AS (
                SELECT dec.series_tmdb_id,
                       COUNT(*) AS watched_episode_count,
                       CASE WHEN COUNT(dec.runtime_minutes) = COUNT(*)
                            THEN COALESCE(SUM(dec.runtime_minutes), 0)
                            ELSE NULL END AS watched_runtime_minutes,
                       COUNT(dec.runtime_minutes) = COUNT(*) AS watched_runtime_complete
                FROM distinct_episode_coordinates dec
                GROUP BY dec.series_tmdb_id
            ),
            max_progress AS (
                SELECT dec.series_tmdb_id,
                       MAX(dec.season_number) AS max_season_number
                FROM distinct_episode_coordinates dec
                GROUP BY dec.series_tmdb_id
            ),
            max_progress_episode AS (
                SELECT dec.series_tmdb_id,
                       MAX(dec.episode_number) AS max_episode_number
                FROM distinct_episode_coordinates dec
                JOIN max_progress mp ON mp.series_tmdb_id = dec.series_tmdb_id
                                      AND mp.max_season_number = dec.season_number
                GROUP BY dec.series_tmdb_id
            ),
            last_watched_ranked AS (
                SELECT ee.series_tmdb_id,
                       ee.season_number,
                       ee.episode_number,
                       ee.effective_date,
                       ROW_NUMBER() OVER (
                           PARTITION BY ee.series_tmdb_id
                           ORDER BY ee.effective_date DESC, ee.created_at DESC, ee.diary_entry_id DESC
                       ) AS row_number
                FROM episode_entries ee
                JOIN candidate_series cs ON cs.series_tmdb_id = ee.series_tmdb_id
            ),
            candidate_rows AS (
                SELECT sa.series_tmdb_id,
                       sa.watched_episode_count,
                       sa.watched_runtime_minutes,
                       sa.watched_runtime_complete,
                       mp.max_season_number,
                       mpe.max_episode_number,
                       lw.effective_date AS last_watched_date,
                       lw.season_number AS last_watched_season_number,
                       lw.episode_number AS last_watched_episode_number,
                       metadata.regular_released_episode_count AS total_released_episode_count,
                       metadata.total_known_runtime AS total_known_runtime,
                       metadata.last_released_episode_date AS last_released_episode_date,
                       GREATEST(
                           metadata.regular_released_episode_count::bigint - sa.watched_episode_count,
                           0::bigint
                       ) AS remaining_episode_count,
                       CASE
                           WHEN metadata.total_known_runtime IS NULL
                                OR sa.watched_runtime_complete = FALSE
                           THEN NULL
                           ELSE GREATEST(
                               metadata.total_known_runtime::bigint - sa.watched_runtime_minutes,
                               0::bigint
                           )
                       END AS remaining_runtime_minutes
                FROM series_aggregates sa
                JOIN max_progress mp ON mp.series_tmdb_id = sa.series_tmdb_id
                JOIN max_progress_episode mpe ON mpe.series_tmdb_id = sa.series_tmdb_id
                JOIN last_watched_ranked lw ON lw.series_tmdb_id = sa.series_tmdb_id
                                            AND lw.row_number = 1
                LEFT JOIN series_progress_metadata metadata
                    ON metadata.series_tmdb_id = sa.series_tmdb_id
            )
            SELECT series_tmdb_id AS seriesTmdbId,
                   watched_episode_count AS watchedEpisodeCount,
                   watched_runtime_minutes AS watchedRuntimeMinutes,
                   watched_runtime_complete AS watchedRuntimeComplete,
                   max_season_number AS maxSeasonNumber,
                   max_episode_number AS maxEpisodeNumber,
                   last_watched_date AS lastWatchedDate,
                   last_watched_season_number AS lastWatchedSeasonNumber,
                   last_watched_episode_number AS lastWatchedEpisodeNumber,
                   total_released_episode_count AS totalReleasedEpisodeCount,
                   total_known_runtime AS totalKnownRuntime,
                   last_released_episode_date AS lastReleasedEpisodeDate,
                   remaining_episode_count AS remainingEpisodeCount,
                   remaining_runtime_minutes AS remainingRuntimeMinutes
            FROM candidate_rows
            ORDER BY
                CASE WHEN :sort = 'LAST_WATCHED' AND :direction = 'ASC' THEN last_watched_date END ASC NULLS LAST,
                CASE WHEN :sort = 'LAST_WATCHED' AND :direction = 'DESC' THEN last_watched_date END DESC NULLS LAST,
                CASE WHEN :sort = 'LAST_RELEASED' AND :direction = 'ASC' THEN last_released_episode_date END ASC NULLS LAST,
                CASE WHEN :sort = 'LAST_RELEASED' AND :direction = 'DESC' THEN last_released_episode_date END DESC NULLS LAST,
                CASE WHEN :sort = 'REMAINING_EPISODES' AND :direction = 'ASC' THEN remaining_episode_count END ASC NULLS LAST,
                CASE WHEN :sort = 'REMAINING_EPISODES' AND :direction = 'DESC' THEN remaining_episode_count END DESC NULLS LAST,
                CASE WHEN :sort = 'REMAINING_RUNTIME' AND :direction = 'ASC' THEN remaining_runtime_minutes END ASC NULLS LAST,
                CASE WHEN :sort = 'REMAINING_RUNTIME' AND :direction = 'DESC' THEN remaining_runtime_minutes END DESC NULLS LAST,
                series_tmdb_id ASC
            """,
            countQuery = """
            WITH episode_entries AS (
                SELECT DISTINCT c.series_tmdb_id
                FROM diary_entries d
                JOIN contents c ON c.id = d.content_id
                WHERE d.user_id = :userId
                AND c.type = 'EPISODE'
                AND c.season_number > 0
            ),
            candidate_series AS (
                SELECT DISTINCT ee.series_tmdb_id
                FROM episode_entries ee
                WHERE NOT EXISTS (
                    SELECT 1
                    FROM diary_entries completion
                    JOIN contents completed_series ON completed_series.id = completion.content_id
                    WHERE completion.user_id = :userId
                    AND completed_series.type = 'SERIES'
                    AND completed_series.tmdb_id = ee.series_tmdb_id
                )
                AND NOT EXISTS (
                    SELECT 1
                    FROM dropped_entries dropped
                    JOIN contents dropped_series ON dropped_series.id = dropped.content_id
                    WHERE dropped.user_id = :userId
                    AND dropped.type = 'SERIES'
                    AND dropped_series.type = 'SERIES'
                    AND dropped_series.tmdb_id = ee.series_tmdb_id
                )
            )
            SELECT COUNT(*)
            FROM candidate_series
            WHERE :sort IS NOT NULL
            """,
            nativeQuery = true)
    Page<SeriesProgressCandidate> findCandidatesByUserIdAndSort(
            @Param("userId") UUID userId, @Param("sort") String sort,
            @Param("direction") String direction, Pageable pageable);

    @Query(value = """
            WITH episode_entries AS (
                SELECT c.series_tmdb_id AS series_tmdb_id,
                       c.season_number AS season_number,
                       c.episode_number AS episode_number,
                       c.runtime_minutes AS runtime_minutes
                FROM diary_entries d
                JOIN contents c ON c.id = d.content_id
                WHERE d.user_id = :userId
                AND c.type = 'EPISODE'
                AND c.season_number > 0
            ),
            candidate_series AS (
                SELECT DISTINCT ee.series_tmdb_id
                FROM episode_entries ee
                WHERE NOT EXISTS (
                    SELECT 1
                    FROM diary_entries completion
                    JOIN contents completed_series ON completed_series.id = completion.content_id
                    WHERE completion.user_id = :userId
                    AND completed_series.type = 'SERIES'
                    AND completed_series.tmdb_id = ee.series_tmdb_id
                )
                AND NOT EXISTS (
                    SELECT 1
                    FROM dropped_entries dropped
                    JOIN contents dropped_series ON dropped_series.id = dropped.content_id
                    WHERE dropped.user_id = :userId
                    AND dropped.type = 'SERIES'
                    AND dropped_series.type = 'SERIES'
                    AND dropped_series.tmdb_id = ee.series_tmdb_id
                )
            ),
            distinct_episode_coordinates AS (
                SELECT DISTINCT ee.series_tmdb_id,
                                ee.season_number,
                                ee.episode_number,
                                ee.runtime_minutes
                FROM episode_entries ee
                JOIN candidate_series cs ON cs.series_tmdb_id = ee.series_tmdb_id
            )
            SELECT COALESCE(COUNT(*), 0) AS watchedEpisodeCount,
                   CASE WHEN COUNT(dec.runtime_minutes) = COUNT(*)
                        THEN COALESCE(SUM(dec.runtime_minutes), 0)
                        ELSE NULL END AS watchedRuntimeMinutes,
                   COUNT(dec.runtime_minutes) = COUNT(*) AS watchedRuntimeComplete
            FROM distinct_episode_coordinates dec
            """, nativeQuery = true)
    SeriesProgressTotals findGlobalTotalsByUserId(@Param("userId") UUID userId);

    enum SeriesProgressSort {
        LAST_WATCHED,
        LAST_RELEASED,
        REMAINING_EPISODES,
        REMAINING_RUNTIME
    }

    interface SeriesProgressCandidate {
        String getSeriesTmdbId();

        Long getWatchedEpisodeCount();

        Long getWatchedRuntimeMinutes();

        default Boolean getWatchedRuntimeComplete() {
            return true;
        }

        Integer getMaxSeasonNumber();

        Integer getMaxEpisodeNumber();

        LocalDate getLastWatchedDate();

        Integer getLastWatchedSeasonNumber();

        Integer getLastWatchedEpisodeNumber();

        Integer getTotalReleasedEpisodeCount();

        Integer getTotalKnownRuntime();

        LocalDate getLastReleasedEpisodeDate();

        Long getRemainingEpisodeCount();

        Long getRemainingRuntimeMinutes();
    }

    interface SeriesProgressTotals {
        Long getWatchedEpisodeCount();

        Long getWatchedRuntimeMinutes();

        default Boolean getWatchedRuntimeComplete() {
            return true;
        }
    }
}
