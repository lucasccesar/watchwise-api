package com.watchwise.watchwise_api.diaryentry.repository;

import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DiaryEntryRepository extends JpaRepository<DiaryEntry, UUID> {

    Page<DiaryEntry> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Page<DiaryEntry> findByUserIdAndWatchedDateBetweenOrderByCreatedAtDesc(
            UUID userId, LocalDate watchedDateStart, LocalDate watchedDateEnd, Pageable pageable);

    List<DiaryEntry> findByUserIdAndContentIdAndWatchNumberGreaterThan(UUID userId, UUID contentId, Integer watchNumber);

    @Query("""
            SELECT COALESCE(MAX(de.watchNumber), 0) FROM DiaryEntry de
            WHERE de.user.id = :userId
            AND de.content.id = :contentId
            """)
    int findMaxWatchNumber(@Param("userId") UUID userId, @Param("contentId") UUID contentId);

    Optional<DiaryEntry> findFirstByUserIdAndContentIdAndWatchNumber(UUID userId, UUID contentId, Integer watchNumber);

    @Query("""
            SELECT de.content.episodeNumber AS episodeNumber, COUNT(de) AS count FROM DiaryEntry de
            WHERE de.user.id = :userId
            AND de.content.type = com.watchwise.watchwise_api.content.entity.ContentType.EPISODE
            AND de.content.seriesTmdbId = :seriesTmdbId
            AND de.content.seasonNumber = :seasonNumber
            GROUP BY de.content.episodeNumber
            """)
    List<EpisodeWatchCount> countEntriesByEpisodeNumberInSeason(
            @Param("userId") UUID userId,
            @Param("seriesTmdbId") String seriesTmdbId,
            @Param("seasonNumber") Integer seasonNumber);

    @Query("""
            SELECT de.content.seasonNumber AS seasonNumber, MAX(de.watchNumber) AS maxWatchNumber FROM DiaryEntry de
            WHERE de.user.id = :userId
            AND de.content.type = com.watchwise.watchwise_api.content.entity.ContentType.SEASON
            AND de.content.seriesTmdbId = :seriesTmdbId
            GROUP BY de.content.seasonNumber
            """)
    List<SeasonWatchMax> maxWatchNumberBySeasonInSeries(
            @Param("userId") UUID userId,
            @Param("seriesTmdbId") String seriesTmdbId);

    interface EpisodeWatchCount {
        Integer getEpisodeNumber();
        Long getCount();
    }

    interface SeasonWatchMax {
        Integer getSeasonNumber();
        Integer getMaxWatchNumber();
    }

    @Query("""
            SELECT de FROM DiaryEntry de
            WHERE de.user.id = :userId
            AND de.content.type = com.watchwise.watchwise_api.content.entity.ContentType.EPISODE
            AND de.content.seriesTmdbId = :seriesTmdbId
            """)
    List<DiaryEntry> findAllEpisodeEntriesInSeries(
            @Param("userId") UUID userId, @Param("seriesTmdbId") String seriesTmdbId);

    @Query("""
            SELECT de FROM DiaryEntry de
            WHERE de.user.id = :userId
            AND de.content.type = com.watchwise.watchwise_api.content.entity.ContentType.SEASON
            AND de.content.seriesTmdbId = :seriesTmdbId
            """)
    List<DiaryEntry> findAllSeasonEntriesInSeries(
            @Param("userId") UUID userId, @Param("seriesTmdbId") String seriesTmdbId);

    @Query("""
            SELECT de FROM DiaryEntry de
            WHERE de.user.id = :userId
            AND de.content.type = com.watchwise.watchwise_api.content.entity.ContentType.SERIES
            AND de.content.tmdbId = :seriesTmdbId
            """)
    List<DiaryEntry> findAllSeriesEntries(
            @Param("userId") UUID userId, @Param("seriesTmdbId") String seriesTmdbId);

}
