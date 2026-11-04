package com.watchwise.watchwise_api.diaryentry.repository;

final class DiaryViewGroupingSql {
    private DiaryViewGroupingSql() { }

    static String cte(String source, String filters) {
        return "WITH timeline AS (" + source + "), filtered AS (" +
                "SELECT t.*, c.runtime_minutes, COALESCE(t.watched_date, t.group_sort_at::date) AS group_date, " +
                "CASE WHEN t.content_type = 'MOVIE' THEN NULL ELSE COALESCE(t.series_tmdb_id, t.tmdb_id) END AS series_key, " +
                "ROW_NUMBER() OVER (ORDER BY " + order() + ") AS position " +
                "FROM timeline t JOIN contents c ON c.id = t.content_id WHERE 1=1 " + filters + "), " + """
                previous AS (
                    SELECT *, LAG(series_key) OVER w AS previous_series,
                           LAG(watch_number) OVER w AS previous_watch,
                           LAG(group_date) OVER w AS previous_date
                    FROM filtered WINDOW w AS (ORDER BY position)
                ), numbered AS (
                    SELECT *, SUM(CASE WHEN series_key IS NOT NULL AND series_key = previous_series
                                           AND watch_number IS NOT DISTINCT FROM previous_watch
                                           AND group_date = previous_date THEN 0 ELSE 1 END)
                              OVER (ORDER BY position) AS run_id
                    FROM previous
                ), runs AS (
                    SELECT run_id, MIN(position) AS first_position, COUNT(*) AS group_size,
                           MAX(CASE content_type WHEN 'SERIES' THEN 3 WHEN 'SEASON' THEN 2 ELSE 1 END) AS type_rank
                    FROM numbered GROUP BY run_id
                ), metric_events AS (
                    SELECT run_id, target_id AS entry_id, season_number, runtime_minutes
                    FROM numbered WHERE kind = 'PERSISTED_ENTRY' AND content_type = 'EPISODE'
                    UNION
                    SELECT n.run_id, e.id, ec.season_number, ec.runtime_minutes
                    FROM numbered n
                    JOIN contents ec ON ec.type = 'EPISODE' AND ec.series_tmdb_id = n.series_key
                                    AND ec.season_number = n.season_number
                    JOIN diary_entries e ON e.content_id = ec.id AND e.user_id = :ownerId
                                        AND e.watch_number = n.watch_number
                                        AND COALESCE(e.watched_date, e.created_at::date) = n.group_date
                    WHERE :type = 'SEASON' AND n.kind = 'DERIVED_COMPLETION' AND n.content_type = 'SEASON'
                      AND (:dateStart IS NULL OR e.watched_date >= :dateStart)
                      AND (:dateEnd IS NULL OR e.watched_date <= :dateEnd)
                      AND (:scoreMin IS NULL OR e.score >= :scoreMin)
                      AND (:scoreMax IS NULL OR e.score <= :scoreMax)
                      AND (:hasReview IS NULL OR (e.comment IS NOT NULL) = :hasReview)
                ), metrics AS (
                    SELECT run_id, COUNT(*)::integer AS episode_count,
                           COUNT(DISTINCT season_number)::integer AS season_count,
                           CASE WHEN COUNT(*) = COUNT(runtime_minutes) THEN SUM(runtime_minutes) END AS total_runtime
                    FROM metric_events GROUP BY run_id
                ), result AS (
                    SELECT n.*, r.group_size,
                           CASE r.type_rank WHEN 3 THEN 'SERIES' WHEN 2 THEN 'SEASON' ELSE 'EPISODE' END AS group_type,
                           sc.id AS series_content_id,
                           COALESCE(m.episode_count, 0) AS episode_count,
                           COALESCE(m.season_count, 0) AS season_count, m.total_runtime
                    FROM runs r JOIN numbered n ON n.position = r.first_position
                    LEFT JOIN metrics m ON m.run_id = r.run_id
                    LEFT JOIN contents sc ON sc.type = 'SERIES' AND sc.tmdb_id = n.series_key
                )
                """;
    }

    static String order() {
        return "group_sort_at DESC NULLS LAST, group_sort_id DESC NULLS LAST, " +
                "CASE WHEN kind = 'PERSISTED_ENTRY' THEN 0 ELSE 1 END, " +
                "CASE content_type WHEN 'EPISODE' THEN 0 WHEN 'SEASON' THEN 1 WHEN 'SERIES' THEN 2 ELSE 3 END, " +
                "ordered_at DESC, target_id DESC";
    }
}
