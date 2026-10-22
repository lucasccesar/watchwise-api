package com.watchwise.watchwise_api.platformimport.repository;

import com.watchwise.watchwise_api.platformimport.entity.ExternalContentAlias;
import com.watchwise.watchwise_api.platformimport.model.AliasTrust;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ExternalContentAliasRepository extends JpaRepository<ExternalContentAlias, UUID> {
    @Query("""
            select alias from ExternalContentAlias alias
            where alias.targetKind = :targetKind
              and alias.normalizedTitle in :normalizedTitles
              and (alias.scopeKey = 'GLOBAL' or alias.scopeKey = :ownerScope)
            """)
    List<ExternalContentAlias> findApplicable(
            @Param("targetKind") String targetKind,
            @Param("normalizedTitles") Collection<String> normalizedTitles,
            @Param("ownerScope") String ownerScope);

    @Modifying
    @Query("delete from ExternalContentAlias alias where alias.provenance = :provenance and alias.trust = :trust")
    int deleteByProvenanceAndTrust(@Param("provenance") String provenance, @Param("trust") AliasTrust trust);
}
