package com.watchwise.watchwise_api.diaryentry.repository;

import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface DiaryEntryRepository extends JpaRepository<DiaryEntry, UUID> {

    Page<DiaryEntry> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Page<DiaryEntry> findByUserIdAndWatchedDateBetweenOrderByCreatedAtDesc(
            UUID userId, LocalDate watchedDateStart, LocalDate watchedDateEnd, Pageable pageable);

    Optional<DiaryEntry> findFirstByUserIdAndContentIdOrderByCreatedAtDesc(UUID userId, UUID contentId);

    @Query("""
            SELECT COUNT(DISTINCT de.content.id) FROM DiaryEntry de
            WHERE de.user.id = :userId
            AND de.content.type = com.watchwise.watchwise_api.content.entity.ContentType.EPISODE
            AND de.content.seriesTmdbId = :seriesTmdbId
            AND de.content.seasonNumber = :seasonNumber
            """)
    long countDistinctWatchedEpisodesInSeason(
            @Param("userId") UUID userId,
            @Param("seriesTmdbId") String seriesTmdbId,
            @Param("seasonNumber") Integer seasonNumber);

    @Query("""
            SELECT COUNT(DISTINCT de.content.id) FROM DiaryEntry de
            WHERE de.user.id = :userId
            AND de.content.type = com.watchwise.watchwise_api.content.entity.ContentType.SEASON
            AND de.content.seriesTmdbId = :seriesTmdbId
            """)
    long countDistinctWatchedSeasonsInSeries(
            @Param("userId") UUID userId,
            @Param("seriesTmdbId") String seriesTmdbId);

}
