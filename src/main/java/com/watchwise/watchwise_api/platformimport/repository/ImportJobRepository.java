package com.watchwise.watchwise_api.platformimport.repository;
import com.watchwise.watchwise_api.platformimport.entity.ImportJob; import org.springframework.data.jpa.repository.JpaRepository; import java.util.UUID;
public interface ImportJobRepository extends JpaRepository<ImportJob, UUID> { }
