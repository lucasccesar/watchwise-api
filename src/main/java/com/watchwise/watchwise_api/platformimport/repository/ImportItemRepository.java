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
import java.util.Optional;
import java.util.UUID;

public interface ImportItemRepository extends JpaRepository<ImportItem, UUID> {
    long countByJobId(UUID jobId);
    long countByJobIdAndPlanGeneration(UUID jobId, long planGeneration);

    @Query("SELECT COALESCE(MAX(item.ordinal), -1) FROM ImportItem item WHERE item.jobId = :jobId AND item.planGeneration = :generation")
    long findMaxOrdinal(@Param("jobId") UUID jobId, @Param("generation") long generation);
    List<ImportItem> findAllByJobIdAndPlanGenerationAndIdIn(
            UUID jobId, long planGeneration, Collection<UUID> ids);

    List<ImportItem> findAllByJobIdAndPlanGenerationAndParentSourceIdAndNormalizedKind(
            UUID jobId, long planGeneration, String parentSourceId, ImportRecordKind normalizedKind);

    Optional<ImportItem> findFirstByJobIdAndPlanGenerationAndNormalizedKindAndSourceId(
            UUID jobId, long planGeneration, ImportRecordKind normalizedKind, String sourceId);

    long countByJobIdAndPlanGenerationAndParentSourceIdAndNormalizedKind(
            UUID jobId, long planGeneration, String parentSourceId, ImportRecordKind normalizedKind);

    @Query(value = """
            WITH staged AS (
                SELECT item.*,
                       COALESCE(
                           CASE WHEN COALESCE(item.payload_json::jsonb ->> 'rank',
                                              item.payload_json::jsonb #>> '{source,rank}') ~ '^[0-9]+$'
                                THEN COALESCE(item.payload_json::jsonb ->> 'rank',
                                              item.payload_json::jsonb #>> '{source,rank}')::bigint END,
                           item.ordinal + 1) AS source_rank
                FROM import_items item
                WHERE item.job_id = :jobId AND item.plan_generation = :generation
                  AND item.parent_source_id = :parentSourceId AND item.normalized_kind = 'LIST_ITEM'
            )
            SELECT * FROM staged
            WHERE source_rank > :afterRank
               OR (source_rank = :afterRank AND ordinal > :afterOrdinal)
               OR (source_rank = :afterRank AND ordinal = :afterOrdinal AND id > CAST(:afterId AS uuid))
            ORDER BY source_rank ASC, ordinal ASC, id ASC
            LIMIT :batchSize
            """, nativeQuery = true)
    List<ImportItem> findListItemsAfter(@Param("jobId") UUID jobId, @Param("generation") long generation,
                                       @Param("parentSourceId") String parentSourceId,
                                       @Param("afterRank") long afterRank,
                                       @Param("afterOrdinal") long afterOrdinal, @Param("afterId") String afterId,
                                       @Param("batchSize") int batchSize);

    @Query(value = """
            WITH staged AS (
                SELECT item.*,
                       COALESCE(
                           CASE WHEN COALESCE(item.payload_json::jsonb ->> 'rank',
                                              item.payload_json::jsonb #>> '{source,rank}') ~ '^[0-9]+$'
                                THEN COALESCE(item.payload_json::jsonb ->> 'rank',
                                              item.payload_json::jsonb #>> '{source,rank}')::bigint END,
                           item.ordinal + 1) AS source_rank
                FROM import_items item
                WHERE item.job_id = :jobId AND item.plan_generation = :generation
                  AND item.normalized_kind = 'WATCHLIST_ITEM'
                  AND (item.outcome IS NULL OR item.outcome IN ('MATCHED', 'RESOLVED'))
            )
            SELECT * FROM staged
            WHERE source_rank > :afterRank
               OR (source_rank = :afterRank AND ordinal > :afterOrdinal)
               OR (source_rank = :afterRank AND ordinal = :afterOrdinal
                   AND id > CAST(:afterId AS uuid))
            ORDER BY source_rank ASC, ordinal ASC, id ASC
            LIMIT :batchSize
            """, nativeQuery = true)
    List<ImportItem> findWatchlistItemsAfter(@Param("jobId") UUID jobId, @Param("generation") long generation,
                                            @Param("afterRank") long afterRank,
                                            @Param("afterOrdinal") long afterOrdinal,
                                            @Param("afterId") String afterId,
                                            @Param("batchSize") int batchSize);

    @Query(value = """
            SELECT * FROM import_items item
            WHERE item.job_id = :jobId AND item.plan_generation = :generation
              AND item.normalized_kind = :kind
              AND (item.ordinal > :afterOrdinal
                   OR (item.ordinal = :afterOrdinal AND item.id > CAST(:afterId AS uuid)))
            ORDER BY item.ordinal ASC, item.id ASC
            LIMIT :batchSize
            """, nativeQuery = true)
    List<ImportItem> findItemsOfKindAfter(@Param("jobId") UUID jobId, @Param("generation") long generation,
                                          @Param("kind") String kind,
                                          @Param("afterOrdinal") long afterOrdinal,
                                          @Param("afterId") String afterId,
                                          @Param("batchSize") int batchSize);

    @Query(value = """
            WITH staged AS (
                SELECT item.id, item.resolved_content_key, item.ordinal, item.decision,
                       COALESCE(
                           CASE WHEN COALESCE(item.payload_json::jsonb ->> 'rank',
                                              item.payload_json::jsonb #>> '{source,rank}') ~ '^[0-9]+$'
                                THEN COALESCE(item.payload_json::jsonb ->> 'rank',
                                              item.payload_json::jsonb #>> '{source,rank}')::bigint END,
                           item.ordinal + 1) AS source_rank
                FROM import_items item
                WHERE item.job_id = :jobId AND item.plan_generation = :generation
                  AND item.parent_source_id = :parentSourceId AND item.normalized_kind = 'LIST_ITEM'
                  AND item.resolved_content_key IN (:contentKeys)
                  AND item.resolved_content_key IS NOT NULL
                  AND (item.decision IS NULL OR item.decision <> 'SKIP_ITEM')
            )
            SELECT DISTINCT ON (resolved_content_key) id
            FROM staged
            ORDER BY resolved_content_key, source_rank ASC, ordinal ASC, id ASC
            """, nativeQuery = true)
    List<UUID> findFirstListItemsForContentKeys(@Param("jobId") UUID jobId,
                                                @Param("generation") long generation,
                                                @Param("parentSourceId") String parentSourceId,
                                                @Param("contentKeys") Collection<String> contentKeys);

    @Query(value = """
            SELECT EXISTS (
                SELECT 1 FROM import_items item
                WHERE item.job_id = :jobId AND item.plan_generation = :generation
                  AND item.resolved_content_key = :contentKey
                  AND item.normalized_kind IN ('DIARY_EVENT', 'WATCHED_MARKER', 'WATCHED_SUMMARY', 'SERIES_EXPANSION')
                  AND (item.decision IS NULL OR item.decision <> 'SKIP_ITEM')
            )
            """, nativeQuery = true)
    boolean existsSourceWatchEvidence(@Param("jobId") UUID jobId, @Param("generation") long generation,
                                      @Param("contentKey") String contentKey);

    @Query(value = """
            SELECT DISTINCT item.resolved_content_key
            FROM import_items item
            WHERE item.job_id = :jobId AND item.plan_generation = :generation
              AND item.resolved_content_key IN (:contentKeys)
              AND item.normalized_kind IN ('DIARY_EVENT', 'WATCHED_MARKER', 'WATCHED_SUMMARY', 'SERIES_EXPANSION')
              AND (item.decision IS NULL OR item.decision <> 'SKIP_ITEM')
            """, nativeQuery = true)
    List<String> findSourceWatchEvidenceContentKeys(@Param("jobId") UUID jobId,
                                                    @Param("generation") long generation,
                                                    @Param("contentKeys") Collection<String> contentKeys);

    @Query(value = """
            SELECT DISTINCT series.id
            FROM import_items item
            CROSS JOIN LATERAL jsonb_array_elements_text(CAST(:seriesIdsJson AS jsonb)) AS series(id)
            WHERE item.job_id = :jobId AND item.plan_generation = :generation
              AND item.normalized_kind IN ('DIARY_EVENT', 'WATCHED_MARKER', 'WATCHED_SUMMARY', 'SERIES_EXPANSION')
              AND (item.decision IS NULL OR item.decision <> 'SKIP_ITEM')
              AND (left(item.resolved_content_key, length('CONTENT|EPISODE|' || series.id || '|'))
                       = 'CONTENT|EPISODE|' || series.id || '|'
                   OR left(item.resolved_content_key, length('CONTENT|SEASON|' || series.id || '|'))
                       = 'CONTENT|SEASON|' || series.id || '|')
            """, nativeQuery = true)
    List<String> findSourceWatchedSeriesIds(@Param("jobId") UUID jobId,
                                            @Param("generation") long generation,
                                            @Param("seriesIdsJson") String seriesIdsJson);

    long countByJobIdAndPlanGenerationAndParentSourceIdAndNormalizedKindAndObjectKindAndOutcome(
            UUID jobId, long planGeneration, String parentSourceId, ImportRecordKind normalizedKind,
            String objectKind, String outcome);

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
              AND (item.sourceEventKey > :afterEventKey
                   OR (item.sourceEventKey = :afterEventKey AND
                       (item.ordinal > :afterOrdinal OR (item.ordinal = :afterOrdinal AND item.id > :afterId))))
            ORDER BY item.sourceEventKey, item.ordinal, item.id
            """)
    List<ImportItem> findHistoryEventFragmentsAfter(@Param("jobId") UUID jobId, @Param("ownerId") UUID ownerId,
                                                   @Param("generation") long generation,
                                                   @Param("eventKeys") Collection<String> eventKeys,
                                                   @Param("kinds") Collection<ImportRecordKind> kinds,
                                                   @Param("outcomes") Collection<String> outcomes,
                                                   @Param("afterEventKey") String afterEventKey,
                                                   @Param("afterOrdinal") long afterOrdinal,
                                                   @Param("afterId") UUID afterId, Pageable pageable);

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
              AND (item.decision IS NULL OR item.decision <> :skipDecision)
              AND (item.decision IS NULL OR item.decision <> :resolvedDecision)
            """)
    long countHistoryIssues(@Param("jobId") UUID jobId, @Param("ownerId") UUID ownerId,
                            @Param("generation") long generation,
                            @Param("skipDecision") ImportDecision skipDecision,
                            @Param("resolvedDecision") ImportDecision resolvedDecision);

    @Query(value = """
            SELECT EXISTS (
                SELECT 1 FROM import_items event
                WHERE event.job_id = :jobId AND event.plan_generation = :generation
                  AND event.normalized_kind = 'DIARY_EVENT'
                  AND event.resolved_content_key = :contentKey
                  AND event.outcome IN (:outcomes)
                  AND (event.decision IS NULL OR event.decision <> :skipDecision)
            )
            """, nativeQuery = true)
    boolean existsDetailDiaryEventByContentKey(@Param("jobId") UUID jobId,
                                                @Param("generation") long generation,
                                                @Param("contentKey") String contentKey,
                                                @Param("outcomes") Collection<String> outcomes,
                                                @Param("skipDecision") String skipDecision);

    @Query(value = """
            SELECT EXISTS (
                SELECT 1 FROM import_items event
                WHERE event.job_id = :jobId AND event.plan_generation = :generation
                  AND event.normalized_kind = 'DIARY_EVENT'
                  AND left(event.resolved_content_key, length(:prefix)) = :prefix
                  AND event.outcome IN (:outcomes)
                  AND (event.decision IS NULL OR event.decision <> :skipDecision)
            )
            """, nativeQuery = true)
    boolean existsDetailDiaryEventByContentKeyPrefix(@Param("jobId") UUID jobId,
                                                      @Param("generation") long generation,
                                                      @Param("prefix") String prefix,
                                                      @Param("outcomes") Collection<String> outcomes,
                                                      @Param("skipDecision") String skipDecision);

    List<ImportItem> findByJobIdAndPlanGenerationAndOutcomeInOrderByOrdinalAscIdAsc(
            UUID jobId, long planGeneration, Collection<String> outcomes, Pageable pageable);

    @Query("""
            SELECT item FROM ImportItem item
            WHERE item.jobId = :jobId AND item.planGeneration = :generation
              AND (item.outcome IS NULL OR item.outcome IN ('MATCHED', 'RESOLVED', 'EXPANSION_PREPARED'))
            ORDER BY item.ordinal ASC, item.id ASC
            """)
    List<ImportItem> findApplyPending(@Param("jobId") UUID jobId,
                                     @Param("generation") long generation, Pageable pageable);

    @Query("""
            SELECT CASE WHEN COUNT(item) > 0 THEN true ELSE false END FROM ImportItem item
            WHERE item.jobId = :jobId AND item.planGeneration = :generation
              AND (item.outcome IS NULL OR item.outcome IN ('MATCHED', 'RESOLVED', 'EXPANSION_PREPARED'))
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
