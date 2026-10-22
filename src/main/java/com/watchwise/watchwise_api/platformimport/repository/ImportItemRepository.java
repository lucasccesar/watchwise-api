package com.watchwise.watchwise_api.platformimport.repository;

import com.watchwise.watchwise_api.platformimport.entity.ImportItem;
import com.watchwise.watchwise_api.platformimport.model.ImportRecordKind;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    List<ImportItem> findByJobIdAndPlanGenerationAndOutcomeIsNullOrderByOrdinalAscIdAsc(
            UUID jobId, long planGeneration, Pageable pageable);

    @Query("""
            SELECT item FROM ImportItem item
            WHERE item.jobId = :jobId AND item.planGeneration = :generation
              AND (item.outcome IS NULL OR item.outcome = 'UNAVAILABLE')
            ORDER BY item.ordinal ASC, item.id ASC
            """)
    Page<ImportItem> findResolutionPending(@Param("jobId") UUID jobId,
                                          @Param("generation") long generation, Pageable pageable);

    @Query("""
            SELECT item FROM ImportItem item
            WHERE item.jobId = :jobId AND item.planGeneration = :generation
              AND item.normalizedContentKey = :contentKey AND item.outcome IN :outcomes
            ORDER BY item.ordinal ASC, item.id ASC
            """)
    List<ImportItem> findStagedResolution(@Param("jobId") UUID jobId,
                                          @Param("generation") long generation,
                                          @Param("contentKey") String contentKey,
                                          @Param("outcomes") Collection<String> outcomes,
                                          Pageable pageable);

    List<ImportItem> findByJobIdAndPlanGenerationAndOutcomeInOrderByOrdinalAscIdAsc(
            UUID jobId, long planGeneration, Collection<String> outcomes, Pageable pageable);

    @Query("""
            SELECT item FROM ImportItem item
            WHERE item.jobId = :jobId AND item.planGeneration = :generation
              AND (item.outcome IS NULL OR item.outcome IN ('MATCHED', 'RESOLVED'))
            ORDER BY item.ordinal ASC, item.id ASC
            """)
    List<ImportItem> findApplyPending(@Param("jobId") UUID jobId,
                                     @Param("generation") long generation, Pageable pageable);

    @Query("""
            SELECT CASE WHEN COUNT(item) > 0 THEN true ELSE false END FROM ImportItem item
            WHERE item.jobId = :jobId AND item.planGeneration = :generation
              AND (item.outcome IS NULL OR item.outcome IN ('MATCHED', 'RESOLVED'))
            """)
    boolean existsApplyPending(@Param("jobId") UUID jobId, @Param("generation") long generation);

    @Modifying
    @Query("""
            UPDATE ImportItem item SET item.payloadJson = null, item.resolvedTargetJson = null,
                item.resolvedContentKey = null
            WHERE item.jobId = :jobId AND item.planGeneration = :generation
              AND item.outcome IN ('APPLIED', 'SKIPPED')
            """)
    int compactAppliedPayloads(@Param("jobId") UUID jobId, @Param("generation") long generation);
}
