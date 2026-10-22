package com.watchwise.watchwise_api.platformimport.repository;

import com.watchwise.watchwise_api.platformimport.entity.ImportJob;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ImportJobRepository extends JpaRepository<ImportJob, UUID> {
    @Query("SELECT job FROM ImportJob job WHERE job.id = :jobId AND job.userId = :ownerId")
    Optional<ImportJob> findOwnedById(@Param("jobId") UUID jobId, @Param("ownerId") UUID ownerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT job FROM ImportJob job WHERE job.id = :jobId AND job.userId = :ownerId")
    Optional<ImportJob> findOwnedByIdForUpdate(@Param("jobId") UUID jobId, @Param("ownerId") UUID ownerId);
}
