package com.watchwise.watchwise_api.platformimport.repository;

import com.watchwise.watchwise_api.platformimport.entity.ImportItem;
import com.watchwise.watchwise_api.platformimport.model.ImportDecision;
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
    long countByJobId(UUID jobId);
    long countByJobIdAndPlanGeneration(UUID jobId, long planGeneration);
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
            SELECT item FROM ImportItem item, ImportJob job
            WHERE item.jobId = :jobId AND item.planGeneration = :generation
              AND job.id = item.jobId AND job.userId = :ownerId
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
                                           @Param("ownerId") UUID ownerId,
                                           @Param("generation") long generation,
                                           @Param("contentKeys") Collection<String> contentKeys,
                                           @Param("outcomes") Collection<String> outcomes);

    @Query("""
            SELECT DISTINCT item.sourceEventKey FROM ImportItem item, ImportJob job
            WHERE item.jobId = :jobId AND item.planGeneration = :generation
              AND job.id = item.jobId AND job.userId = :ownerId
              AND item.sourceEventKey IS NOT NULL AND item.sourceEventKey > :afterKey
              AND item.normalizedKind IN :kinds AND item.outcome IN :outcomes
            ORDER BY item.sourceEventKey
            """)
    List<String> findSourceEventKeysAfter(@Param("jobId") UUID jobId, @Param("ownerId") UUID ownerId,
                                          @Param("generation") long generation, @Param("afterKey") String afterKey,
                                          @Param("kinds") Collection<ImportRecordKind> kinds,
                                          @Param("outcomes") Collection<String> outcomes, Pageable pageable);

    @Query("""
            SELECT item FROM ImportItem item, ImportJob job
            WHERE item.jobId = :jobId AND item.planGeneration = :generation
              AND job.id = item.jobId AND job.userId = :ownerId
              AND item.sourceEventKey IN :eventKeys
              AND item.normalizedKind IN :kinds AND item.outcome IN :outcomes
            ORDER BY item.sourceEventKey, item.ordinal, item.id
            """)
    List<ImportItem> findHistoryEventGroups(@Param("jobId") UUID jobId, @Param("ownerId") UUID ownerId,
                                            @Param("generation") long generation,
                                            @Param("eventKeys") Collection<String> eventKeys,
                                            @Param("kinds") Collection<ImportRecordKind> kinds,
                                            @Param("outcomes") Collection<String> outcomes);

    @Query("""
            SELECT DISTINCT item.resolvedContentKey FROM ImportItem item, ImportJob job
            WHERE item.jobId = :jobId AND item.planGeneration = :generation
              AND job.id = item.jobId AND job.userId = :ownerId
              AND item.resolvedContentKey IS NOT NULL AND item.resolvedContentKey > :afterKey
              AND item.normalizedKind IN :kinds AND item.outcome IN :outcomes
            ORDER BY item.resolvedContentKey
            """)
    List<String> findResolvedContentKeysAfter(@Param("jobId") UUID jobId, @Param("ownerId") UUID ownerId,
                                               @Param("generation") long generation,
                                               @Param("afterKey") String afterKey,
                                               @Param("kinds") Collection<ImportRecordKind> kinds,
                                               @Param("outcomes") Collection<String> outcomes,
                                               Pageable pageable);

    @Query("""
            SELECT item FROM ImportItem item, ImportJob job
            WHERE item.jobId = :jobId AND item.planGeneration = :generation
              AND job.id = item.jobId AND job.userId = :ownerId
              AND item.outcome IN ('AMBIGUOUS', 'NOT_FOUND', 'NORMALIZATION_ISSUE')
              AND (item.ordinal > :afterOrdinal OR (item.ordinal = :afterOrdinal AND item.id > :afterId))
            ORDER BY item.ordinal, item.id
            """)
    List<ImportItem> findWaitingItemsAfter(@Param("jobId") UUID jobId, @Param("ownerId") UUID ownerId,
                                            @Param("generation") long generation,
                                            @Param("afterOrdinal") long afterOrdinal,
                                            @Param("afterId") UUID afterId, Pageable pageable);

    @Query("""
            SELECT item FROM ImportItem item, ImportJob job
            WHERE item.jobId = :jobId AND item.planGeneration = :generation
              AND job.id = item.jobId AND job.userId = :ownerId
              AND item.resolvedContentKey = :contentKey
              AND item.normalizedKind IN :kinds AND item.outcome IN :outcomes
              AND (item.ordinal > :afterOrdinal OR (item.ordinal = :afterOrdinal AND item.id > :afterId))
            ORDER BY item.ordinal, item.id
            """)
    List<ImportItem> findHistoryTargetFragmentsAfter(@Param("jobId") UUID jobId,
                                                      @Param("ownerId") UUID ownerId,
                                                      @Param("generation") long generation,
                                                      @Param("contentKey") String contentKey,
                                                      @Param("kinds") Collection<ImportRecordKind> kinds,
                                                      @Param("outcomes") Collection<String> outcomes,
                                                      @Param("afterOrdinal") long afterOrdinal,
                                                      @Param("afterId") UUID afterId,
                                                      Pageable pageable);

    @Query("""
            SELECT item FROM ImportItem item, ImportJob job
            WHERE item.jobId = :jobId AND item.planGeneration = :generation
              AND job.id = item.jobId AND job.userId = :ownerId
              AND item.normalizedKind IN :kinds AND item.outcome IN :outcomes
              AND (item.ordinal > :afterOrdinal OR (item.ordinal = :afterOrdinal AND item.id > :afterId))
            ORDER BY item.ordinal, item.id
            """)
    List<ImportItem> findHistoryRowsAfter(@Param("jobId") UUID jobId, @Param("ownerId") UUID ownerId,
                                           @Param("generation") long generation,
                                           @Param("kinds") Collection<ImportRecordKind> kinds,
                                           @Param("outcomes") Collection<String> outcomes,
                                           @Param("afterOrdinal") long afterOrdinal,
                                           @Param("afterId") UUID afterId, Pageable pageable);

    @Query("""
            SELECT CASE WHEN COUNT(item) > 0 THEN true ELSE false END
            FROM ImportItem item, ImportJob job
            WHERE item.jobId = :jobId AND item.planGeneration = :generation
              AND job.id = item.jobId AND job.userId = :ownerId
              AND item.resolvedContentKey = :contentKey
              AND item.normalizedKind = :kind AND item.outcome IN :outcomes
              AND (item.decision IS NULL OR item.decision <> :skipDecision)
            """)
    boolean existsHistoryUnit(@Param("jobId") UUID jobId, @Param("ownerId") UUID ownerId,
                              @Param("generation") long generation, @Param("contentKey") String contentKey,
                              @Param("kind") ImportRecordKind kind,
                              @Param("outcomes") Collection<String> outcomes,
                              @Param("skipDecision") ImportDecision skipDecision);

    @Query(value = """
            SELECT item.id
            FROM import_items item
            JOIN import_jobs job ON job.id = item.job_id AND job.user_id = :ownerId
            JOIN external_import_links link ON link.user_id = job.user_id
                AND link.source_namespace = item.source_namespace
                AND link.object_kind = item.object_kind
                AND link.source_id = item.source_id
            WHERE item.job_id = :jobId AND item.plan_generation = :generation
              AND item.id IN (:itemIds) AND link.state <> 'IN_PROGRESS'
            """, nativeQuery = true)
    List<UUID> findConsumedHistoryItemIds(@Param("ownerId") UUID ownerId, @Param("jobId") UUID jobId,
                                          @Param("generation") long generation,
                                          @Param("itemIds") Collection<UUID> itemIds);

    @Query("""
            SELECT COUNT(item) FROM ImportItem item, ImportJob job
            WHERE item.jobId = :jobId AND item.planGeneration = :generation
              AND job.id = item.jobId AND job.userId = :ownerId
              AND item.normalizedKind = :kind AND item.outcome IN :outcomes
              AND (item.decision IS NULL OR item.decision <> :skipDecision)
            """)
    long countHistoryUnits(@Param("jobId") UUID jobId, @Param("ownerId") UUID ownerId,
                           @Param("generation") long generation, @Param("kind") ImportRecordKind kind,
                           @Param("outcomes") Collection<String> outcomes,
                           @Param("skipDecision") ImportDecision skipDecision);

    @Query("""
            SELECT COUNT(item) FROM ImportItem item, ImportJob job
            WHERE item.jobId = :jobId AND item.planGeneration = :generation
              AND job.id = item.jobId AND job.userId = :ownerId
              AND item.outcome = 'NORMALIZATION_ISSUE'
            """)
    long countHistoryIssues(@Param("jobId") UUID jobId, @Param("ownerId") UUID ownerId,
                            @Param("generation") long generation);

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
