package com.watchwise.watchwise_api.diarycompletion.repository;

import com.watchwise.watchwise_api.diarycompletion.entity.LegacyDiarySnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface LegacyDiarySnapshotRepository extends JpaRepository<LegacyDiarySnapshot, UUID> {
    Optional<LegacyDiarySnapshot> findByOldDiaryEntryId(UUID oldDiaryEntryId);
}
