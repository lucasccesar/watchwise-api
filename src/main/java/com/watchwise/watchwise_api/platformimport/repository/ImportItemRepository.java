package com.watchwise.watchwise_api.platformimport.repository;
import com.watchwise.watchwise_api.platformimport.entity.ImportItem; import org.springframework.data.jpa.repository.JpaRepository; import java.util.UUID;
public interface ImportItemRepository extends JpaRepository<ImportItem, UUID> { }
