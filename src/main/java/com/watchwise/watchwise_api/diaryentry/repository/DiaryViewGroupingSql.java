package com.watchwise.watchwise_api.diaryentry.repository;

final class DiaryViewGroupingSql {
    private DiaryViewGroupingSql() { }

    static String cte(String source, String filters, String candidateSelection) {
        return "WITH timeline AS (" + source + "), filtered AS (" +
                "SELECT t.*, c.runtime_minutes, COALESCE(t.watched_date, t.group_sort_at::date) AS group_date, " +
                "CASE WHEN t.content_type = 'MOVIE' THEN NULL ELSE COALESCE(t.series_tmdb_id, t.tmdb_id) END AS series_key, " +
                "ROW_NUMBER() OVER (ORDER BY " + order() + ") AS position " +
                "FROM timeline t JOIN contents c ON c.id = t.content_id WHERE 1=1 " + filters + " " +
                "), " + """
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
                    SELECT run_id, MIN(position) AS first_position, MAX(position) AS last_position,
                           COUNT(*) AS group_size,
                           MAX(CASE content_type WHEN 'SERIES' THEN 3 WHEN 'SEASON' THEN 2 ELSE 1 END) AS type_rank,
                           MAX(series_key) AS series_key,
                           BOOL_OR(COALESCE(watched_in_theater, false)) AS includes_theater,
                           CASE WHEN BOOL_OR(content_type = 'EPISODE')
                               THEN MAX(score) FILTER (WHERE content_type = 'EPISODE') ELSE MAX(score) END AS group_score
                    FROM numbered GROUP BY run_id
                ), candidate_runs AS (
                """ + candidateSelection + """
                ), metric_events AS (
                    SELECT n.run_id, n.target_id AS entry_id, n.season_number, n.runtime_minutes,
                           n.group_sort_at AS member_sort_at, n.group_sort_id AS member_sort_id
                    FROM numbered n
                    JOIN candidate_runs candidate ON candidate.run_id = n.run_id
                    WHERE candidate.collect_metrics
                      AND n.kind = 'PERSISTED_ENTRY' AND n.content_type = 'EPISODE'
                    UNION
                    SELECT n.run_id, e.id, ec.season_number, ec.runtime_minutes, e.created_at, e.id
                    FROM numbered n
                    JOIN candidate_runs candidate ON candidate.run_id = n.run_id
                    JOIN contents ec ON ec.type = 'EPISODE' AND ec.series_tmdb_id = n.series_key
                                    AND ec.season_number = n.season_number
                    JOIN diary_entries e ON e.content_id = ec.id AND e.user_id = :ownerId
                                        AND e.watch_number = n.watch_number
                                        AND COALESCE(e.watched_date, e.created_at::date) = n.group_date
                    WHERE candidate.collect_metrics
                      AND :type = 'SEASON' AND n.kind = 'DERIVED_COMPLETION' AND n.content_type = 'SEASON'
                      AND (:dateStart IS NULL OR e.watched_date >= :dateStart)
                      AND (:dateEnd IS NULL OR e.watched_date <= :dateEnd)
                      AND (:scoreMin IS NULL OR e.score >= :scoreMin)
                      AND (:scoreMax IS NULL OR e.score <= :scoreMax)
                      AND (:hasReview IS NULL OR (e.comment IS NOT NULL) = :hasReview)
                ), event_metrics AS (
                    SELECT run_id, COUNT(*)::integer AS episode_count,
                           CASE WHEN COUNT(*) = COUNT(runtime_minutes) THEN SUM(runtime_minutes) END AS total_runtime
                    FROM metric_events GROUP BY run_id
                ), season_metrics AS (
                    SELECT run_id, season_number, COUNT(*)::integer AS episode_count
                    FROM metric_events
                    WHERE season_number IS NOT NULL
                    GROUP BY run_id, season_number
                ), member_metrics AS (
                    SELECT run_id,
                           array_agg(entry_id::text ORDER BY member_sort_at DESC, member_sort_id DESC)
                               AS member_entry_ids
                    FROM metric_events
                    GROUP BY run_id
                ), metrics AS (
                    SELECT event.run_id, event.episode_count, event.total_runtime,
                           COALESCE(member.member_entry_ids, ARRAY[]::text[]) AS member_entry_ids,
                           COUNT(season.season_number)::integer AS season_count,
                           COALESCE(array_agg(season.season_number ORDER BY season.season_number)
                               FILTER (WHERE season.season_number IS NOT NULL), ARRAY[]::integer[]) AS season_numbers,
                           COALESCE(array_agg(season.episode_count ORDER BY season.season_number)
                               FILTER (WHERE season.season_number IS NOT NULL), ARRAY[]::integer[]) AS season_episode_counts
                    FROM event_metrics event
                    LEFT JOIN season_metrics season ON season.run_id = event.run_id
                    LEFT JOIN member_metrics member ON member.run_id = event.run_id
                    GROUP BY event.run_id, event.episode_count, event.total_runtime, member.member_entry_ids
                ), result AS (
                    SELECT n.*, r.group_size,
                           CASE r.type_rank WHEN 3 THEN 'SERIES' WHEN 2 THEN 'SEASON' ELSE 'EPISODE' END AS group_type,
                           sc.id AS series_content_id,
                           CASE
                               WHEN r.type_rank = 3 THEN sc.id
                               WHEN r.type_rank = 2 AND COALESCE(m.season_count, 0) = 1 THEN season_content.id
                           END AS rating_content_id,
                           COALESCE(m.episode_count, 0) AS episode_count,
                           COALESCE(m.season_count, 0) AS season_count,
                           COALESCE(m.season_numbers, ARRAY[]::integer[]) AS season_numbers,
                           COALESCE(m.season_episode_counts, ARRAY[]::integer[]) AS season_episode_counts,
                           COALESCE(m.member_entry_ids, ARRAY[]::text[]) AS member_entry_ids,
                           m.total_runtime,
                           candidate.candidate_order,
                           candidate.represented_item_count,
                           CASE WHEN :sortBy IN ('OLDEST', 'RATING_DESC', 'RATING_ASC') THEN n.group_date
                               ELSE last_n.group_date END AS cursor_sort_date,
                           CASE WHEN :sortBy IN ('OLDEST', 'RATING_DESC', 'RATING_ASC') THEN n.group_sort_at
                               ELSE last_n.group_sort_at END AS cursor_sort_at,
                           CASE WHEN :sortBy IN ('OLDEST', 'RATING_DESC', 'RATING_ASC') THEN n.group_sort_id
                               ELSE last_n.group_sort_id END AS cursor_sort_id,
                           CASE WHEN :sortBy IN ('OLDEST', 'RATING_DESC', 'RATING_ASC')
                               THEN CASE WHEN n.kind = 'DERIVED_COMPLETION' THEN 0 ELSE 1 END
                               ELSE CASE WHEN last_n.kind = 'DERIVED_COMPLETION' THEN 0 ELSE 1 END
                           END AS cursor_kind_rank,
                           CASE WHEN :sortBy IN ('OLDEST', 'RATING_DESC', 'RATING_ASC') THEN
                               CASE n.content_type WHEN 'SERIES' THEN 0 WHEN 'SEASON' THEN 1
                                   WHEN 'EPISODE' THEN 2 ELSE 3 END
                               ELSE CASE last_n.content_type WHEN 'SERIES' THEN 0 WHEN 'SEASON' THEN 1
                                   WHEN 'EPISODE' THEN 2 ELSE 3 END
                           END AS cursor_content_type_rank,
                           CASE WHEN :sortBy IN ('OLDEST', 'RATING_DESC', 'RATING_ASC') THEN n.ordered_at
                               ELSE last_n.ordered_at END AS cursor_ordered_at,
                           CASE WHEN :sortBy IN ('OLDEST', 'RATING_DESC', 'RATING_ASC') THEN n.target_id
                               ELSE last_n.target_id END AS cursor_target_id,
                           r.group_score AS cursor_sort_score
                    FROM candidate_runs candidate
                    JOIN runs r ON r.run_id = candidate.run_id
                    JOIN numbered n ON n.position = r.first_position
                    JOIN numbered last_n ON last_n.position = r.last_position
                    LEFT JOIN metrics m ON m.run_id = r.run_id
                    LEFT JOIN contents sc ON sc.type = 'SERIES' AND sc.tmdb_id = n.series_key
                    LEFT JOIN contents season_content ON season_content.type = 'SEASON'
                                                     AND season_content.series_tmdb_id = n.series_key
                                                     AND season_content.season_number = n.season_number
                )
                """;
    }

    static String pageCandidateSelection() {
        return "SELECT ranked.*, " + representedItemCount("ranked") +
                " AS represented_item_count, TRUE AS collect_metrics FROM (SELECT r.*, ROW_NUMBER() OVER (ORDER BY " +
                candidateOrder("r") + ") AS candidate_order FROM runs r WHERE " + runFilters("r") + ") ranked " +
                "ORDER BY candidate_order LIMIT :limit OFFSET :offset";
    }

    static String cursorCandidateSelection(DiaryEntrySort sortBy, boolean cursorPresent) {
        String cursorFilter = cursorPresent && (sortBy == DiaryEntrySort.RATING_DESC
                || sortBy == DiaryEntrySort.RATING_ASC) ? ratingCursorFilter(sortBy) : "";
        String ranked = "SELECT r.*, ROW_NUMBER() OVER (ORDER BY " + candidateOrder("r") +
                ") AS candidate_order FROM runs r " +
                (cursorFilter.isEmpty() ? "" : "JOIN numbered first_n ON first_n.position = r.first_position ") +
                "WHERE " + runFilters("r") + cursorFilter + " ORDER BY candidate_order LIMIT :candidateLimit";
        return "SELECT budgeted.*, (budgeted.candidate_order < :candidateLimit AND " +
                "(budgeted.candidate_order = 1 OR budgeted.cumulative_items <= 300)) AS collect_metrics " +
                "FROM (SELECT weighted.*, weighted.cumulative_items - weighted.represented_item_count " +
                "AS previous_items FROM (SELECT ranked.*, SUM(ranked.represented_item_count) OVER " +
                "(ORDER BY ranked.candidate_order ROWS UNBOUNDED PRECEDING) AS cumulative_items, " +
                "FIRST_VALUE(ranked.represented_item_count) OVER (ORDER BY ranked.candidate_order) AS first_item_count " +
                "FROM (SELECT ranked.*, " + representedItemCount("ranked") + " AS represented_item_count " +
                "FROM (" + ranked + ") ranked) ranked) weighted) budgeted " +
                "WHERE budgeted.previous_items <= 300 OR budgeted.candidate_order = 1 " +
                "OR (budgeted.candidate_order = 2 AND budgeted.first_item_count > 300) " +
                "ORDER BY budgeted.candidate_order";
    }

    private static String representedItemCount(String alias) {
        return "(" + alias + ".group_size + CASE WHEN :type = 'SEASON' THEN COALESCE((" +
                "SELECT COUNT(DISTINCT e.id) FROM numbered n " +
                "JOIN contents ec ON ec.type = 'EPISODE' AND ec.series_tmdb_id = n.series_key " +
                "AND ec.season_number = n.season_number " +
                "JOIN diary_entries e ON e.content_id = ec.id AND e.user_id = :ownerId " +
                "AND e.watch_number = n.watch_number " +
                "AND COALESCE(e.watched_date, e.created_at::date) = n.group_date " +
                "WHERE n.run_id = " + alias + ".run_id AND n.kind = 'DERIVED_COMPLETION' " +
                "AND n.content_type = 'SEASON' " +
                "AND (:dateStart IS NULL OR e.watched_date >= :dateStart) " +
                "AND (:dateEnd IS NULL OR e.watched_date <= :dateEnd) " +
                "AND (:scoreMin IS NULL OR e.score >= :scoreMin) " +
                "AND (:scoreMax IS NULL OR e.score <= :scoreMax) " +
                "AND (:hasReview IS NULL OR (e.comment IS NOT NULL) = :hasReview)), 0) " +
                "ELSE 0 END)::bigint";
    }

    private static String runFilters(String alias) {
        return "(:seriesTmdbId IS NULL OR " + alias + ".series_key = :seriesTmdbId) " +
                "AND (:watchedInTheater IS NULL OR " + alias + ".includes_theater = :watchedInTheater)";
    }

    private static String candidateOrder(String alias) {
        return "CASE WHEN :sortBy = 'RATING_DESC' THEN " + alias + ".group_score END DESC NULLS LAST, " +
                "CASE WHEN :sortBy = 'RATING_ASC' THEN " + alias + ".group_score END ASC NULLS LAST, " +
                "CASE WHEN :sortBy = 'OLDEST' THEN " + alias + ".first_position END DESC NULLS LAST, " +
                "CASE WHEN :sortBy <> 'OLDEST' THEN " + alias + ".first_position END ASC NULLS LAST";
    }

    private static String ratingCursorFilter(DiaryEntrySort sortBy) {
        String scoreComparison = sortBy == DiaryEntrySort.RATING_DESC ? "<" : ">";
        return " AND ((r.group_score IS NULL AND :cursorSortScore IS NOT NULL) OR " +
                "r.group_score " + scoreComparison + " :cursorSortScore OR " +
                "(r.group_score IS NOT DISTINCT FROM :cursorSortScore AND " + afterNewest("first_n") + "))";
    }

    private static String afterNewest(String alias) {
        return "((" + alias + ".ordered_at, " + alias + ".group_date, " + alias + ".group_sort_at, " +
                alias + ".group_sort_id, " +
                "(0 - (CASE WHEN " + alias + ".kind = 'DERIVED_COMPLETION' THEN 0 ELSE 1 END)), " +
                "(0 - (CASE " + alias + ".content_type WHEN 'SERIES' THEN 0 WHEN 'SEASON' THEN 1 " +
                "WHEN 'EPISODE' THEN 2 ELSE 3 END)), " + alias + ".target_id) < " +
                "(:cursorOrderedAt, :cursorSortDate, :cursorSortAt, :cursorSortId, " +
                "(0 - (:cursorKindRank)), (0 - (:cursorContentTypeRank)), :cursorTargetId))";
    }

    static String order() {
        return "ordered_at DESC NULLS LAST, " +
                "COALESCE(watched_date, group_sort_at::date) DESC NULLS LAST, " +
                "group_sort_at DESC NULLS LAST, group_sort_id DESC NULLS LAST, " +
                "CASE WHEN kind = 'DERIVED_COMPLETION' THEN 0 ELSE 1 END, " +
                "CASE content_type WHEN 'SERIES' THEN 0 WHEN 'SEASON' THEN 1 WHEN 'EPISODE' THEN 2 ELSE 3 END, " +
                "target_id DESC";
    }

    static String pageOrder(DiaryEntrySort sortBy) {
        if (sortBy == DiaryEntrySort.RATING_DESC || sortBy == DiaryEntrySort.RATING_ASC) {
            String scoreDirection = sortBy == DiaryEntrySort.RATING_DESC ? "DESC" : "ASC";
            return "CASE WHEN score IS NULL THEN 1 ELSE 0 END ASC, score " + scoreDirection + " NULLS LAST, " +
                    chronologicalPageOrder("DESC", "ASC");
        }
        boolean oldestFirst = sortBy == DiaryEntrySort.OLDEST;
        String direction = oldestFirst ? "ASC" : "DESC";
        String inverseDirection = oldestFirst ? "DESC" : "ASC";
        return chronologicalPageOrder(direction, inverseDirection);
    }

    private static String chronologicalPageOrder(String direction, String inverseDirection) {
        return "ordered_at " + direction + " NULLS LAST, " +
                "COALESCE(watched_date, group_sort_at::date) " + direction + " NULLS LAST, " +
                "group_sort_at " + direction + " NULLS LAST, group_sort_id " + direction + " NULLS LAST, " +
                "CASE WHEN kind = 'DERIVED_COMPLETION' THEN 0 ELSE 1 END " + inverseDirection + ", " +
                "CASE content_type WHEN 'SERIES' THEN 0 WHEN 'SEASON' THEN 1 WHEN 'EPISODE' THEN 2 ELSE 3 END " +
                inverseDirection + ", target_id " + direction + " NULLS LAST";
    }
}
