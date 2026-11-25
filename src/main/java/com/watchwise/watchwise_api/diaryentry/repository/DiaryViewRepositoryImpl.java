package com.watchwise.watchwise_api.diaryentry.repository;

import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryViewFilter;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

@Repository
public class DiaryViewRepositoryImpl implements DiaryViewRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public DiaryViewRepositoryImpl(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page<DiaryViewKey> findPage(
            UUID ownerId,
            String region,
            String language,
            Instant now,
            DiaryViewFilter filter,
            Pageable pageable) {
        MapSqlParameterSource parameters = parameters(ownerId, region, language, now, filter);
        String source = sourceSql();
        String where = timelineFilterSql();
        String order = " ORDER BY " + DiaryViewGroupingSql.pageOrder(filter.sortBy());
        String sql = "SELECT * FROM (" + source + ") t WHERE 1 = 1 " + where + order
                + " LIMIT :limit OFFSET :offset";
        parameters.addValue("limit", pageable.getPageSize());
        parameters.addValue("offset", pageable.getOffset());
        List<DiaryViewKey> rows = jdbc.query(sql, parameters, this::mapRow);
        long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM (" + source + ") t WHERE 1 = 1 " + where,
                parameters,
                Long.class);
        return new PageImpl<>(rows, pageable, total);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page<GroupedDiaryViewKey> findGroupedPage(UUID ownerId, String region, String language,
            Instant now, DiaryViewFilter filter, Pageable pageable) {
        MapSqlParameterSource parameters = parameters(ownerId, region, language, now, filter)
                .addValue("limit", pageable.getPageSize()).addValue("offset", pageable.getOffset());
        String cte = DiaryViewGroupingSql.cte(sourceSql(null, filter.sortBy()), timelineFilterSql(),
                DiaryViewGroupingSql.pageCandidateSelection());
        List<GroupedDiaryViewKey> rows = jdbc.query(cte + " SELECT * FROM result ORDER BY candidate_order",
                parameters, this::mapGroupedRow);
        long total = jdbc.queryForObject(cte + " SELECT COUNT(*) FROM runs " +
                "WHERE (:seriesTmdbId IS NULL OR series_key = :seriesTmdbId) " +
                "AND (:watchedInTheater IS NULL OR includes_theater = :watchedInTheater)", parameters, Long.class);
        return new PageImpl<>(rows, pageable, total);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<GroupedDiaryViewKey> findGroupedCandidates(UUID ownerId, String region, String language,
            Instant now, DiaryViewFilter filter, DiaryViewCursorPosition cursor, int candidateLimit) {
        MapSqlParameterSource parameters = parameters(ownerId, region, language, now, filter, cursor)
                .addValue("candidateLimit", candidateLimit);
        boolean ratingSort = filter.sortBy() == DiaryEntrySort.RATING_DESC
                || filter.sortBy() == DiaryEntrySort.RATING_ASC;
        DiaryViewCursorPosition sourceCursor = ratingSort ? null : cursor;
        String cte = DiaryViewGroupingSql.cte(sourceSql(sourceCursor, filter.sortBy()), timelineFilterSql(),
                DiaryViewGroupingSql.cursorCandidateSelection(filter.sortBy(), cursor != null));
        return jdbc.query(cte + " SELECT * FROM result ORDER BY candidate_order", parameters, this::mapGroupedRow);
    }

    private MapSqlParameterSource parameters(
            UUID ownerId, String region, String language, Instant now, DiaryViewFilter filter) {
        return parameters(ownerId, region, language, now, filter, null);
    }

    private MapSqlParameterSource parameters(UUID ownerId, String region, String language, Instant now,
            DiaryViewFilter filter, DiaryViewCursorPosition cursor) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("ownerId", ownerId)
                .addValue("region", region)
                .addValue("language", language)
                .addValue("now", Timestamp.from(now))
                .addValue("type", filter.type() == null ? null : filter.type().name(), Types.VARCHAR)
                .addValue("dateStart", filter.dateStart(), Types.DATE)
                .addValue("dateEnd", filter.dateEnd(), Types.DATE)
                .addValue("scoreMin", filter.scoreMin(), Types.INTEGER)
                .addValue("scoreMax", filter.scoreMax(), Types.INTEGER)
                .addValue("hasReview", filter.hasReview(), Types.BOOLEAN)
                .addValue("seriesTmdbId", filter.seriesTmdbId(), Types.VARCHAR)
                .addValue("watchedInTheater", filter.watchedInTheater(), Types.BOOLEAN)
                .addValue("sortBy", filter.sortBy().name(), Types.VARCHAR);
        if (cursor != null) {
            parameters.addValue("cursorSortDate", cursor.sortDate(), Types.DATE)
                    .addValue("cursorSortAt", Timestamp.from(cursor.groupSortAt()))
                    .addValue("cursorSortId", cursor.groupSortId())
                    .addValue("cursorKindRank", cursor.kindRank())
                    .addValue("cursorContentTypeRank", cursor.contentTypeRank())
                    .addValue("cursorOrderedAt", Timestamp.from(cursor.orderedAt()))
                    .addValue("cursorTargetId", cursor.targetId())
                    .addValue("cursorSortScore", cursor.sortScore(), Types.INTEGER);
        }
        return parameters;
    }

    private String sourceSql() {
        return sourceSql(null, DiaryEntrySort.NEWEST);
    }

    private String sourceSql(DiaryViewCursorPosition cursor, DiaryEntrySort sortBy) {
        boolean oldestFirst = sortBy == DiaryEntrySort.OLDEST;
        String entryCursorFilter = cursor == null ? "" : " AND " + afterCursor(
                "COALESCE(d.watched_at, d.watched_date::timestamp AT TIME ZONE 'UTC', d.created_at)",
                "d.created_at", "d.id", "1", contentTypeRank("c.type"), "d.id",
                "COALESCE(d.watched_date, d.created_at::date)", oldestFirst);
        String completionCursorFilter = cursor == null ? "" : " AND " + afterCursor(
                "p.ordered_at", "anchor.created_at", "anchor.id", "0",
                contentTypeRank("p.content_type"), "p.id",
                "COALESCE(p.completed_on, anchor.created_at::date)", oldestFirst);
        return " " + """
                SELECT 'PERSISTED_ENTRY' AS kind,
                       d.id AS target_id,
                       d.content_id,
                       c.type AS content_type,
                       c.tmdb_id,
                       c.series_tmdb_id,
                       c.season_number,
                       c.episode_number,
                       d.watch_number,
                       d.id AS anchor_entry_id,
                       d.watched_date,
                       COALESCE(d.watched_at, d.watched_date::timestamp AT TIME ZONE 'UTC', d.created_at) AS ordered_at,
                       d.created_at AS group_sort_at,
                       d.id AS group_sort_id,
                       d.score,
                       d.watched_in_theater,
                       (d.comment IS NOT NULL) AS has_review
                FROM diary_entries d
                JOIN contents c ON c.id = d.content_id
                WHERE d.user_id = :ownerId
                  AND c.type IN ('MOVIE', 'EPISODE')
                """ + entryCursorFilter + " " + """
                UNION ALL
                SELECT 'DERIVED_COMPLETION' AS kind,
                       p.id AS target_id,
                       p.content_id,
                       p.content_type,
                       c.tmdb_id,
                       c.series_tmdb_id,
                       c.season_number,
                       c.episode_number,
                       p.watch_number,
                       p.anchor_diary_entry_id AS anchor_entry_id,
                       p.completed_on AS watched_date,
                       p.ordered_at,
                       anchor.created_at AS group_sort_at,
                       anchor.id AS group_sort_id,
                       COALESCE(pass_rating.score, rating.score) AS score,
                       NULL::boolean AS watched_in_theater,
                       EXISTS (SELECT 1
                               FROM content_reviews review
                               WHERE review.user_id = p.user_id
                                 AND review.content_id = p.content_id
                                 AND review.watch_number = p.watch_number
                                 AND review.comment IS NOT NULL
                                 AND btrim(review.comment) <> '') AS has_review
                FROM diary_completion_projections p
                JOIN contents c ON c.id = p.content_id
                 LEFT JOIN content_ratings rating
                   ON rating.user_id = p.user_id
                  AND rating.content_id = p.content_id
                 LEFT JOIN content_rating_passes pass_rating
                   ON pass_rating.user_id = p.user_id
                  AND pass_rating.content_id = p.content_id
                  AND pass_rating.watch_number = p.watch_number
                JOIN diary_entries anchor ON anchor.id = p.anchor_diary_entry_id
                                           AND anchor.user_id = p.user_id
                JOIN user_series_history_states state
                  ON state.user_id = p.user_id
                 AND state.series_tmdb_id = COALESCE(c.series_tmdb_id, c.tmdb_id)
                JOIN calendar_schedule_completeness marker
                  ON marker.series_tmdb_id = state.series_tmdb_id
                 AND marker.region = :region
                 AND marker.language = :language
                 AND marker.scope_version = p.schedule_version
                 AND ((p.content_type = 'SERIES' AND marker.group_type = 'SERIES' AND marker.season_number IS NULL)
                      OR (p.content_type = 'SEASON' AND marker.group_type = 'SEASON'
                          AND marker.season_number = c.season_number))
                 AND marker.complete = TRUE
                WHERE p.user_id = :ownerId
                  AND p.valid_until > :now
                  AND p.history_revision = state.history_revision
                  AND p.history_revision = state.computed_revision
                  AND state.valid_until > :now
                """ + completionCursorFilter;
    }

    private String contentTypeRank(String expression) {
        return "CASE " + expression + " WHEN 'SERIES' THEN 0 WHEN 'SEASON' THEN 1 "
                + "WHEN 'EPISODE' THEN 2 ELSE 3 END";
    }

    private String afterCursor(String orderedAt, String sortAt, String sortId, String kindRank,
            String contentRank, String targetId, String sortDate, boolean oldestFirst) {
        String comparison = oldestFirst ? ">" : "<";
        return "((" + orderedAt + ", " + sortAt + ", " + sortId + ", (0 - (" + kindRank + ")), " +
                "(0 - (" + contentRank + ")), " + targetId + ", " + sortDate + ") " + comparison + " " +
                "(:cursorOrderedAt, :cursorSortAt, :cursorSortId, (0 - (:cursorKindRank)), " +
                "(0 - (:cursorContentTypeRank)), :cursorTargetId, :cursorSortDate))";
    }

    private GroupedDiaryViewKey mapGroupedRow(ResultSet resultSet, int rowNumber) throws SQLException {
        List<Integer> seasonNumbers = integerList(resultSet, "season_numbers");
        List<Integer> seasonEpisodeCounts = integerList(resultSet, "season_episode_counts");
        List<SeasonEpisodeCount> countsBySeason = IntStream.range(0,
                        Math.min(seasonNumbers.size(), seasonEpisodeCounts.size()))
                .mapToObj(index -> new SeasonEpisodeCount(seasonNumbers.get(index), seasonEpisodeCounts.get(index)))
                .toList();
        DiaryViewCursorPosition nextPosition = new DiaryViewCursorPosition(
                resultSet.getObject("cursor_sort_date", LocalDate.class),
                instant(resultSet, "cursor_sort_at"), uuid(resultSet, "cursor_sort_id"),
                resultSet.getInt("cursor_kind_rank"), resultSet.getInt("cursor_content_type_rank"),
                instant(resultSet, "cursor_ordered_at"), uuid(resultSet, "cursor_target_id"),
                (Integer) resultSet.getObject("cursor_sort_score"));
        return new GroupedDiaryViewKey(mapRow(resultSet, rowNumber), resultSet.getLong("group_size"),
                ContentType.valueOf(resultSet.getString("group_type")),
                resultSet.getObject("group_date", LocalDate.class), uuid(resultSet, "series_content_id"),
                uuid(resultSet, "rating_content_id"), resultSet.getString("series_key"),
                resultSet.getInt("season_count"), seasonNumbers, resultSet.getInt("episode_count"),
                resultSet.getObject("total_runtime", Long.class), countsBySeason,
                uuidList(resultSet, "member_entry_ids"), resultSet.getLong("represented_item_count"), nextPosition);
    }

    private String timelineFilterSql() {
        return """
                AND (:type IS NULL OR t.content_type = :type)
                AND (:seriesTmdbId IS NULL OR
                     (t.content_type <> 'MOVIE' AND COALESCE(t.series_tmdb_id, t.tmdb_id) = :seriesTmdbId))
                AND (:dateStart IS NULL OR t.watched_date >= :dateStart)
                AND (:dateEnd IS NULL OR t.watched_date <= :dateEnd)
                AND (:scoreMin IS NULL OR t.score >= :scoreMin)
                AND (:scoreMax IS NULL OR t.score <= :scoreMax)
                AND (:hasReview IS NULL OR t.has_review = :hasReview)
                """;
    }

    private DiaryViewKey mapRow(ResultSet resultSet, int rowNumber) throws SQLException {
        return new DiaryViewKey(
                resultSet.getString("kind"),
                uuid(resultSet, "target_id"),
                uuid(resultSet, "content_id"),
                ContentType.valueOf(resultSet.getString("content_type")),
                resultSet.getString("tmdb_id"),
                resultSet.getString("series_tmdb_id"),
                (Integer) resultSet.getObject("season_number"),
                (Integer) resultSet.getObject("episode_number"),
                (Integer) resultSet.getObject("watch_number"),
                uuid(resultSet, "anchor_entry_id"),
                resultSet.getObject("watched_date", LocalDate.class),
                instant(resultSet, "ordered_at"),
                instant(resultSet, "group_sort_at"),
                uuid(resultSet, "group_sort_id"),
                (Integer) resultSet.getObject("score"));
    }

    private UUID uuid(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column, UUID.class);
    }

    private List<Integer> integerList(ResultSet resultSet, String column) throws SQLException {
        Array sqlArray = resultSet.getArray(column);
        if (sqlArray == null) {
            return List.of();
        }
        try {
            return Arrays.stream((Object[]) sqlArray.getArray())
                    .map(Integer.class::cast)
                    .toList();
        } finally {
            sqlArray.free();
        }
    }

    private List<UUID> uuidList(ResultSet resultSet, String column) throws SQLException {
        Array sqlArray = resultSet.getArray(column);
        if (sqlArray == null) {
            return List.of();
        }
        try {
            return Arrays.stream((Object[]) sqlArray.getArray())
                    .map(value -> UUID.fromString((String) value))
                    .toList();
        } finally {
            sqlArray.free();
        }
    }

    private Instant instant(ResultSet resultSet, String column) throws SQLException {
        java.sql.Timestamp timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
