package com.watchwise.watchwise_api.diaryentry.repository;

import com.watchwise.watchwise_api.diaryentry.entity.WatchCompanion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

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

}
