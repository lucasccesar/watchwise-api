package com.watchwise.watchwise_api.platformimport.repository;

import com.watchwise.watchwise_api.platformimport.entity.ImportItem;
import com.watchwise.watchwise_api.platformimport.model.ImportRecordKind;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ImportItemRepository extends JpaRepository<ImportItem, UUID> {
    List<ImportItem> findAllByJobIdAndPlanGenerationAndIdIn(
            UUID jobId, long planGeneration, Collection<UUID> ids);

    List<ImportItem> findAllByJobIdAndPlanGenerationAndParentSourceIdAndNormalizedKind(
            UUID jobId, long planGeneration, String parentSourceId, ImportRecordKind normalizedKind);

    List<ImportItem> findAllByJobIdAndPlanGenerationAndSourceIdAndNormalizedKind(
            UUID jobId, long planGeneration, String sourceId, ImportRecordKind normalizedKind);

    Page<ImportItem> findByJobIdAndPlanGenerationOrderByOrdinalAscIdAsc(
            UUID jobId, long planGeneration, Pageable pageable);
}
