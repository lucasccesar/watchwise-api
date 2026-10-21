package com.watchwise.watchwise_api.platformimport.repository;
import com.watchwise.watchwise_api.platformimport.entity.ImportDeferredRecord; import org.springframework.data.jpa.repository.JpaRepository; import java.util.UUID;
public interface ImportDeferredRecordRepository extends JpaRepository<ImportDeferredRecord, UUID> { }
