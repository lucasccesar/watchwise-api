package com.watchwise.watchwise_api.diaryentry.repository;

import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.diaryentry.entity.WatchCompanion;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface WatchCompanionRepository extends JpaRepository<WatchCompanion, UUID> {

    @Query("SELECT wc FROM WatchCompanion wc JOIN FETCH wc.user WHERE wc.diaryEntry.id IN :diaryEntryIds")
    List<WatchCompanion> findByDiaryEntryIdIn(@Param("diaryEntryIds") Collection<UUID> diaryEntryIds);

    @Transactional
    @Modifying
    @Query("DELETE FROM WatchCompanion wc WHERE wc.diaryEntry.id = :diaryEntryId")
    void deleteByDiaryEntryId(@Param("diaryEntryId") UUID diaryEntryId);

    @Query("""
            SELECT wc.user.id AS companionUserId, COUNT(wc) AS count
            FROM WatchCompanion wc
            WHERE wc.diaryEntry.user.id = :userId
            AND wc.diaryEntry.content.type = :contentType
            AND wc.diaryEntry.watchedDate BETWEEN :start AND :end
            GROUP BY wc.user.id
            ORDER BY COUNT(wc) DESC
            """)
    List<CompanionWatchCount> countGroupedByCompanionUserIdAndContentTypeAndWatchedDateBetween(
            @Param("userId") UUID userId, @Param("contentType") ContentType contentType,
            @Param("start") LocalDate start, @Param("end") LocalDate end, Pageable pageable);

    @Query("""
            SELECT wc.user.id AS companionUserId, COUNT(wc) AS count
            FROM WatchCompanion wc
            WHERE wc.diaryEntry.user.id = :userId
            AND wc.diaryEntry.content.type IN :contentTypes
            GROUP BY wc.user.id
            ORDER BY COUNT(wc) DESC
            """)
    List<CompanionWatchCount> countGroupedByCompanionUserIdAndContentTypeIn(
            @Param("userId") UUID userId, @Param("contentTypes") Collection<ContentType> contentTypes,
            Pageable pageable);

    interface CompanionWatchCount {
        UUID getCompanionUserId();
        Long getCount();
    }

}
