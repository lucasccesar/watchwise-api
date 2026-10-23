package com.watchwise.watchwise_api.diarycompletion.repository;

import com.watchwise.watchwise_api.diarycompletion.entity.LegacyDiaryMigrationMap;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LegacyDiaryMigrationMapRepository extends JpaRepository<LegacyDiaryMigrationMap, UUID> {
    Optional<LegacyDiaryMigrationMap> findByOldDiaryEntryId(UUID oldDiaryEntryId);
    List<LegacyDiaryMigrationMap> findByOldDiaryEntryIdIn(Collection<UUID> oldDiaryEntryIds);
    Optional<LegacyDiaryMigrationMap> findByDestinationKindAndDestinationId(String destinationKind, UUID destinationId);
}
