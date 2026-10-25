package com.watchwise.watchwise_api.platformimport.repository;

import com.watchwise.watchwise_api.platformimport.entity.ExternalImportLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ExternalImportLinkRepository extends JpaRepository<ExternalImportLink, UUID> {
    Optional<ExternalImportLink> findByUserIdAndSourceNamespaceAndObjectKindAndSourceId(
            UUID userId, String sourceNamespace, String objectKind, String sourceId);

    @Query(value = """
            SELECT target_id FROM external_import_links
            WHERE user_id = :userId AND source_namespace = :namespace
              AND object_kind = 'series_diary_child' AND state = 'APPLIED'
              AND left(source_id, length(:prefix)) = :prefix AND target_type = 'DIARY_ENTRY'
            ORDER BY source_id
            """, nativeQuery = true)
    java.util.List<UUID> findAppliedSeriesChildDiaryIds(@Param("userId") UUID userId,
                                                        @Param("namespace") String namespace,
                                                        @Param("prefix") String prefix);
}
