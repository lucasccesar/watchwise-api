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

    @Query("SELECT d FROM DiaryEntry d JOIN FETCH d.content WHERE d.user.id = :userId ORDER BY d.createdAt DESC")
    Page<DiaryEntry> findByUserIdOrderByCreatedAtDesc(@Param("userId") UUID userId, Pageable pageable);

    @Query("""
            SELECT d FROM DiaryEntry d JOIN FETCH d.content
            WHERE d.user.id = :userId
            AND d.watchedDate BETWEEN :watchedDateStart AND :watchedDateEnd
            ORDER BY d.createdAt DESC
            """)
    Page<DiaryEntry> findByUserIdAndWatchedDateBetweenOrderByCreatedAtDesc(
            @Param("userId") UUID userId,
            @Param("watchedDateStart") LocalDate watchedDateStart,
            @Param("watchedDateEnd") LocalDate watchedDateEnd,
            Pageable pageable);

    List<DiaryEntry> findByUserIdAndContentIdAndWatchNumberGreaterThan(UUID userId, UUID contentId, Integer watchNumber);

    @Query("SELECT d FROM DiaryEntry d JOIN FETCH d.user WHERE d.id = :id")
    Optional<DiaryEntry> findByIdWithUser(@Param("id") UUID id);

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
