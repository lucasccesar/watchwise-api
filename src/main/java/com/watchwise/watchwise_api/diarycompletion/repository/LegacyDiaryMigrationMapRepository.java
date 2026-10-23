package com.watchwise.watchwise_api.diarycompletion.repository;

import com.watchwise.watchwise_api.diarycompletion.entity.LegacyDiaryMigrationMap;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface LegacyDiaryMigrationMapRepository extends JpaRepository<LegacyDiaryMigrationMap, UUID> {
    Optional<LegacyDiaryMigrationMap> findByOldDiaryEntryId(UUID oldDiaryEntryId);
    Optional<LegacyDiaryMigrationMap> findByDestinationKindAndDestinationId(String destinationKind, UUID destinationId);
}
