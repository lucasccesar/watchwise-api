package com.watchwise.watchwise_api.platformimport.repository;
import com.watchwise.watchwise_api.platformimport.entity.ImportFileCleanupTask; import org.springframework.data.jpa.repository.JpaRepository; import java.util.UUID;
public interface ImportFileCleanupTaskRepository extends JpaRepository<ImportFileCleanupTask, UUID> {
 boolean existsByStorageKey(String storageKey);
}
