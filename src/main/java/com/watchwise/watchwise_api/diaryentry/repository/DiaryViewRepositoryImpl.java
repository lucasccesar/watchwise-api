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

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

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
        String where = filterSql(filter);
        String order = " ORDER BY group_sort_at DESC NULLS LAST, group_sort_id DESC NULLS LAST, "
                + "CASE WHEN kind = 'PERSISTED_ENTRY' THEN 0 ELSE 1 END, "
                + "CASE content_type WHEN 'EPISODE' THEN 0 WHEN 'SEASON' THEN 1 WHEN 'SERIES' THEN 2 ELSE 3 END, "
                + "ordered_at DESC, target_id DESC";
        String sql = "SELECT * FROM (" + source + ") timeline WHERE 1 = 1 " + where + order
                + " LIMIT :limit OFFSET :offset";
        parameters.addValue("limit", pageable.getPageSize());
        parameters.addValue("offset", pageable.getOffset());
        List<DiaryViewKey> rows = jdbc.query(sql, parameters, this::mapRow);
        long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM (" + source + ") timeline WHERE 1 = 1 " + where,
                parameters,
                Long.class);
        return new PageImpl<>(rows, pageable, total);
    }

    private MapSqlParameterSource parameters(
            UUID ownerId, String region, String language, Instant now, DiaryViewFilter filter) {
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
                .addValue("hasReview", filter.hasReview(), Types.BOOLEAN);
        return parameters;
    }

    private String sourceSql() {
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
                       COALESCE(d.watched_at, d.watched_date::timestamptz, d.created_at) AS ordered_at,
                       d.created_at AS group_sort_at,
                       d.id AS group_sort_id,
                       d.score,
                       (d.comment IS NOT NULL) AS has_review
                FROM diary_entries d
                JOIN contents c ON c.id = d.content_id
                WHERE d.user_id = :ownerId
                  AND c.type IN ('MOVIE', 'EPISODE')
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
                       rating.score AS score,
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
                """;
    }

    private String filterSql(DiaryViewFilter filter) {
        return """
                AND (:type IS NULL OR content_type = :type)
                AND (:dateStart IS NULL OR watched_date >= :dateStart)
                AND (:dateEnd IS NULL OR watched_date <= :dateEnd)
                AND (:scoreMin IS NULL OR score >= :scoreMin)
                AND (:scoreMax IS NULL OR score <= :scoreMax)
                AND (:hasReview IS NULL OR has_review = :hasReview)
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

    private Instant instant(ResultSet resultSet, String column) throws SQLException {
        java.sql.Timestamp timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
