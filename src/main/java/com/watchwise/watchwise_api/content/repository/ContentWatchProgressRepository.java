package com.watchwise.watchwise_api.content.repository;

import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.service.ContentCoordinate;
import com.watchwise.watchwise_api.content.service.ContentStateResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Repository
@RequiredArgsConstructor
public class ContentWatchProgressRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public Map<ContentCoordinate, ContentStateResolver.WatchProgress> findCurrentProgress(
            java.util.UUID viewerId, Collection<ContentCoordinate> coordinates, Instant now) {
        if (viewerId == null || coordinates == null || coordinates.isEmpty()) {
            return Map.of();
        }

        List<ContentCoordinate> targets = coordinates.stream()
                .filter(Objects::nonNull)
                .filter(this::isSeriesOrRegularSeason)
                .distinct()
                .toList();
        if (targets.isEmpty()) {
            return Map.of();
        }

        LinkedHashSet<String> seriesIds = new LinkedHashSet<>();
        LinkedHashSet<SeasonPair> seasonPairs = new LinkedHashSet<>();
        targets.forEach(target -> {
            if (target.type() == ContentType.SERIES) {
                seriesIds.add(target.tmdbId());
            } else {
                seasonPairs.add(new SeasonPair(target.seriesTmdbId(), target.seasonNumber()));
            }
        });

        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("viewerId", viewerId)
                .addValue("today", LocalDate.ofInstant(now, ZoneOffset.UTC))
                .addValue("checkedAt", LocalDateTime.ofInstant(now, ZoneOffset.UTC))
                .addValue("seriesIds", seriesIds.isEmpty() ? List.of("__none__") : List.copyOf(seriesIds));
        String directSeasonPredicate = seasonPairPredicate(seasonPairs, parameters);

        String sql = """
                WITH target_seasons AS (
                    SELECT marker.series_tmdb_id, marker.season_number,
                           marker.expected_episode_count, marker.region, marker.language
                    FROM calendar_schedule_completeness marker
                    JOIN users viewer ON viewer.id = :viewerId
                      AND viewer.preferred_region = marker.region
                      AND viewer.preferred_language = marker.language
                    WHERE marker.group_type = 'SEASON'
                      AND marker.complete = true
                      AND marker.expected_episode_count >= 0
                      AND (marker.series_tmdb_id IN (:seriesIds) OR %s)
                ), season_facts AS (
                    SELECT target.series_tmdb_id,
                           target.season_number,
                           target.expected_episode_count,
                           COUNT(snapshot.id) AS snapshot_count,
                           COUNT(DISTINCT snapshot.episode_number) AS distinct_episode_count,
                           COUNT(snapshot.id) FILTER (
                               WHERE snapshot.episode_number IS NULL
                                  OR snapshot.episode_number <= 0
                                  OR snapshot.release_date IS NULL
                                  OR snapshot.next_check_at <= :checkedAt
                           ) AS invalid_snapshot_count,
                           COUNT(snapshot.id) FILTER (
                               WHERE snapshot.release_date <= :today
                           ) AS released_episode_count,
                           COUNT(snapshot.id) FILTER (
                               WHERE snapshot.release_date <= :today
                                 AND EXISTS (
                                     SELECT 1
                                     FROM diary_entries diary
                                     JOIN contents watched ON watched.id = diary.content_id
                                     WHERE diary.user_id = :viewerId
                                       AND watched.type = 'EPISODE'
                                       AND watched.series_tmdb_id = target.series_tmdb_id
                                       AND watched.season_number = target.season_number
                                       AND watched.episode_number = snapshot.episode_number
                                 )
                           ) AS watched_episode_count
                    FROM target_seasons target
                    LEFT JOIN calendar_schedule_snapshots snapshot
                      ON snapshot.event_type = 'EPISODE'
                     AND snapshot.series_tmdb_id = target.series_tmdb_id
                     AND snapshot.season_number = target.season_number
                     AND snapshot.region = target.region
                     AND snapshot.language = target.language
                     AND snapshot.present_in_last_tmdb_snapshot = true
                    GROUP BY target.series_tmdb_id, target.season_number,
                             target.expected_episode_count, target.region, target.language
                ), trusted_seasons AS (
                    SELECT * FROM season_facts
                    WHERE snapshot_count = expected_episode_count
                      AND distinct_episode_count = expected_episode_count
                      AND invalid_snapshot_count = 0
                ), direct_season_progress AS (
                    SELECT 'SEASON' AS content_type,
                           season.series_tmdb_id,
                           season.season_number,
                           season.watched_episode_count,
                           season.released_episode_count
                    FROM trusted_seasons season
                    WHERE %s
                ), series_progress AS (
                    SELECT 'SERIES' AS content_type,
                           series.series_tmdb_id,
                           NULL::integer AS season_number,
                           SUM(season.watched_episode_count)::integer AS watched_episode_count,
                           SUM(season.released_episode_count)::integer AS released_episode_count
                    FROM calendar_schedule_completeness series
                    JOIN users viewer ON viewer.id = :viewerId
                      AND viewer.preferred_region = series.region
                      AND viewer.preferred_language = series.language
                    JOIN trusted_seasons season ON season.series_tmdb_id = series.series_tmdb_id
                    WHERE series.group_type = 'SERIES'
                      AND series.complete = true
                      AND series.expected_episode_count >= 0
                      AND series.series_tmdb_id IN (:seriesIds)
                    GROUP BY series.series_tmdb_id, series.expected_episode_count
                    HAVING SUM(season.expected_episode_count) = series.expected_episode_count
                )
                SELECT content_type, series_tmdb_id, season_number,
                       watched_episode_count, released_episode_count
                FROM direct_season_progress
                UNION ALL
                SELECT content_type, series_tmdb_id, season_number,
                       watched_episode_count, released_episode_count
                FROM series_progress
                """.formatted(directSeasonPredicate, directSeasonPredicate);

        Map<ContentCoordinate, ContentStateResolver.WatchProgress> result = new LinkedHashMap<>();
        jdbc.query(sql, parameters, row -> {
            ContentType type = ContentType.valueOf(row.getString("content_type"));
            String seriesTmdbId = row.getString("series_tmdb_id");
            Integer seasonNumber = row.getObject("season_number", Integer.class);
            int watched = row.getInt("watched_episode_count");
            int released = row.getInt("released_episode_count");
            ContentCoordinate coordinate = type == ContentType.SERIES
                    ? new ContentCoordinate(ContentType.SERIES, seriesTmdbId, null, null, null)
                    : new ContentCoordinate(ContentType.SEASON, null, seriesTmdbId, seasonNumber, null);
            result.put(coordinate, ContentStateResolver.resolveWatchProgress(watched, released));
        });
        return Map.copyOf(result);
    }

    private String seasonPairPredicate(
            Collection<SeasonPair> pairs, MapSqlParameterSource parameters) {
        if (pairs.isEmpty()) {
            return "false";
        }
        return pairs.stream().map(pair -> {
            int index = parameters.getValues().size();
            parameters.addValue("seasonSeries" + index, pair.seriesTmdbId());
            parameters.addValue("seasonNumber" + index, pair.seasonNumber());
            return "(series_tmdb_id = :seasonSeries" + index
                    + " AND season_number = :seasonNumber" + index + ")";
        }).collect(java.util.stream.Collectors.joining(" OR ", "(", ")"));
    }

    private boolean isSeriesOrRegularSeason(ContentCoordinate coordinate) {
        return coordinate.type() == ContentType.SERIES && hasText(coordinate.tmdbId())
                || coordinate.type() == ContentType.SEASON && hasText(coordinate.seriesTmdbId())
                        && coordinate.seasonNumber() != null && coordinate.seasonNumber() > 0;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record SeasonPair(String seriesTmdbId, int seasonNumber) { }
}
