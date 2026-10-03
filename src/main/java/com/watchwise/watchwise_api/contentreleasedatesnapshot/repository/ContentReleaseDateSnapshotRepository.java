package com.watchwise.watchwise_api.contentreleasedatesnapshot.repository;

import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.contentreleasedatesnapshot.entity.ContentReleaseDateSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ContentReleaseDateSnapshotRepository extends JpaRepository<ContentReleaseDateSnapshot, UUID> {

    List<ContentReleaseDateSnapshot> findByTypeAndRegionAndTmdbIdIn(
            ContentType type, String region, Collection<String> tmdbIds);

    List<ContentReleaseDateSnapshot> findByTypeAndRegionIsNullAndTmdbIdIn(
            ContentType type, Collection<String> tmdbIds);

    Optional<ContentReleaseDateSnapshot> findByTypeAndRegionAndTmdbId(
            ContentType type, String region, String tmdbId);

    Optional<ContentReleaseDateSnapshot> findByTypeAndRegionIsNullAndTmdbId(
            ContentType type, String tmdbId);

    List<ContentReleaseDateSnapshot> findByNextCheckAtBefore(LocalDateTime now);

    @Modifying
    @Query("""
            UPDATE ContentReleaseDateSnapshot snapshot
            SET snapshot.nextCheckAt = :leaseUntil
            WHERE snapshot.id = :snapshotId
              AND snapshot.nextCheckAt <= :now
            """)
    int claimDue(
            @Param("snapshotId") UUID snapshotId,
            @Param("now") LocalDateTime now,
            @Param("leaseUntil") LocalDateTime leaseUntil);

    @Modifying
    @Query("""
            UPDATE ContentReleaseDateSnapshot snapshot
            SET snapshot.nextCheckAt = :retryAt
            WHERE snapshot.id = :snapshotId
              AND snapshot.nextCheckAt = :leaseUntil
            """)
    int rescheduleClaimed(
            @Param("snapshotId") UUID snapshotId,
            @Param("leaseUntil") LocalDateTime leaseUntil,
            @Param("retryAt") LocalDateTime retryAt);

    @Query(value = """
            SELECT count(*)
            FROM watchlist_entries w
            JOIN contents c ON c.id = w.content_id
            JOIN content_release_date_snapshots s
              ON s.tmdb_id = c.tmdb_id
             AND s.type = w.type
             AND ((w.type = 'MOVIE' AND s.region = :region)
                  OR (w.type = 'SERIES' AND s.region IS NULL))
            WHERE w.user_id = :userId
              AND w.type = 'SERIES'
              AND (:type IS NULL OR w.type = :type)
              AND s.status = 'FOUND'
              AND s.release_date > :today
            """, nativeQuery = true)
    long countUpcomingByUserIdAndType(
            @Param("userId") UUID userId,
            @Param("type") String type,
            @Param("region") String region,
            @Param("today") LocalDate today);
}
