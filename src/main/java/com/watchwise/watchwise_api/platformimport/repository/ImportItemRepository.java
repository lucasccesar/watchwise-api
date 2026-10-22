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
              AND item.normalizedContentKey IN :contentKeys AND item.outcome IN :outcomes
              AND NOT EXISTS (
                  SELECT earlier.id FROM ImportItem earlier
                  WHERE earlier.jobId = item.jobId AND earlier.planGeneration = item.planGeneration
                    AND earlier.normalizedContentKey = item.normalizedContentKey
                    AND earlier.outcome IN :outcomes
                    AND (earlier.ordinal < item.ordinal OR (earlier.ordinal = item.ordinal AND earlier.id < item.id))
              )
            """)
    List<ImportItem> findStagedResolutions(@Param("jobId") UUID jobId,
                                           @Param("generation") long generation,
                                           @Param("contentKeys") Collection<String> contentKeys,
                                           @Param("outcomes") Collection<String> outcomes);

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
    @Query(value = """
            WITH batch AS (
                SELECT id FROM import_items
                WHERE job_id = :jobId AND plan_generation = :generation
                  AND outcome IN ('APPLIED', 'SKIPPED')
                  AND (payload_json IS NOT NULL OR resolved_target_json IS NOT NULL OR resolved_content_key IS NOT NULL)
                ORDER BY ordinal ASC, id ASC
                LIMIT :batchSize
                FOR UPDATE
            )
            UPDATE import_items item SET payload_json = null, resolved_target_json = null,
                resolved_content_key = null
            WHERE item.id IN (SELECT id FROM batch)
            """, nativeQuery = true)
    int compactAppliedPayloads(@Param("jobId") UUID jobId, @Param("generation") long generation,
                               @Param("batchSize") int batchSize);

    @Query("""
            SELECT CASE WHEN COUNT(item) > 0 THEN true ELSE false END FROM ImportItem item
            WHERE item.jobId = :jobId AND item.planGeneration = :generation
              AND item.outcome IN ('APPLIED', 'SKIPPED')
              AND (item.payloadJson IS NOT NULL OR item.resolvedTargetJson IS NOT NULL OR item.resolvedContentKey IS NOT NULL)
            """)
    boolean existsAppliedPayloads(@Param("jobId") UUID jobId, @Param("generation") long generation);
}
