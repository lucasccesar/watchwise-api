package com.watchwise.watchwise_api.platformimport.repository;

import com.watchwise.watchwise_api.platformimport.entity.ImportDeferredRecord;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ImportDeferredRecordRepository extends JpaRepository<ImportDeferredRecord, UUID> {
    @Query(value = """
            SELECT deferred.* FROM import_deferred_records deferred
            JOIN external_import_links link
              ON link.user_id = deferred.user_id
             AND link.source_namespace = deferred.source_namespace
             AND link.object_kind = deferred.object_kind
             AND link.source_id = deferred.source_id
             AND link.state = 'APPLIED'
             AND link.target_type = 'IMPORT_DEFERRED_RECORD'
             AND link.target_id = deferred.id
            WHERE deferred.user_id = :userId
              AND COALESCE(deferred.provenance::jsonb ->> 'resolutionState', 'PENDING') = 'PENDING'
            ORDER BY deferred.created_at ASC, deferred.id ASC
            """, countQuery = """
            SELECT COUNT(*) FROM import_deferred_records deferred
            JOIN external_import_links link
              ON link.user_id = deferred.user_id
             AND link.source_namespace = deferred.source_namespace
             AND link.object_kind = deferred.object_kind
             AND link.source_id = deferred.source_id
             AND link.state = 'APPLIED'
             AND link.target_type = 'IMPORT_DEFERRED_RECORD'
             AND link.target_id = deferred.id
            WHERE deferred.user_id = :userId
              AND COALESCE(deferred.provenance::jsonb ->> 'resolutionState', 'PENDING') = 'PENDING'
            """, nativeQuery = true)
    Page<ImportDeferredRecord> findPendingByUserId(@Param("userId") UUID userId, Pageable pageable);

    @Query(value = """
            SELECT deferred.* FROM import_deferred_records deferred
            JOIN external_import_links link
              ON link.user_id = deferred.user_id
             AND link.source_namespace = deferred.source_namespace
             AND link.object_kind = deferred.object_kind
             AND link.source_id = deferred.source_id
             AND link.state = 'APPLIED'
             AND link.target_type = 'IMPORT_DEFERRED_RECORD'
             AND link.target_id = deferred.id
            WHERE deferred.user_id = :userId
              AND deferred.source_namespace = :namespace
              AND deferred.object_kind = :objectKind
              AND deferred.source_id = :sourceId
              AND COALESCE(deferred.provenance::jsonb ->> 'resolutionState', 'PENDING') = 'PENDING'
            FOR UPDATE OF deferred
            """, nativeQuery = true)
    Optional<ImportDeferredRecord> findPendingBySourceIdentityForUpdate(
            @Param("userId") UUID userId,
            @Param("namespace") String namespace,
            @Param("objectKind") String objectKind,
            @Param("sourceId") String sourceId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT deferred FROM ImportDeferredRecord deferred WHERE deferred.id = :id AND deferred.userId = :userId")
    Optional<ImportDeferredRecord> findByIdAndUserIdForUpdate(@Param("id") UUID id, @Param("userId") UUID userId);
}
