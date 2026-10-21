package com.watchwise.watchwise_api.platformimport.repository;
import com.watchwise.watchwise_api.platformimport.entity.ExternalContentAlias; import org.springframework.data.jpa.repository.JpaRepository; import java.util.UUID;
public interface ExternalContentAliasRepository extends JpaRepository<ExternalContentAlias, UUID> { }
