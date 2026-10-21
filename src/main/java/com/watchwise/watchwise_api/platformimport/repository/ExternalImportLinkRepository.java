package com.watchwise.watchwise_api.platformimport.repository;
import com.watchwise.watchwise_api.platformimport.entity.ExternalImportLink; import org.springframework.data.jpa.repository.JpaRepository; import java.util.UUID;
public interface ExternalImportLinkRepository extends JpaRepository<ExternalImportLink, UUID> { }
