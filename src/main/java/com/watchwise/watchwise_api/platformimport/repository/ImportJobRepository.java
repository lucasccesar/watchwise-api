package com.watchwise.watchwise_api.platformimport.repository;

import com.watchwise.watchwise_api.platformimport.entity.ImportJob;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;
import java.util.Collection;

public interface ImportJobRepository extends JpaRepository<ImportJob, UUID> {
    @Query("SELECT COUNT(job) FROM ImportJob job WHERE job.userId = :ownerId AND job.state IN :states AND job.uploadSizeBytes > 0")
    long countPendingByOwnerId(@Param("ownerId") UUID ownerId, @Param("states") Collection<com.watchwise.watchwise_api.platformimport.model.ImportJobState> states);

    @Query("SELECT COALESCE(SUM(job.uploadSizeBytes), 0) FROM ImportJob job WHERE job.userId = :ownerId AND job.state IN :states AND job.uploadSizeBytes > 0")
    long sumPendingUploadBytesByOwnerId(@Param("ownerId") UUID ownerId, @Param("states") Collection<com.watchwise.watchwise_api.platformimport.model.ImportJobState> states);

    @Query("SELECT COALESCE(SUM(job.uploadSizeBytes), 0) FROM ImportJob job WHERE job.state IN :states AND job.uploadSizeBytes > 0")
    long sumPendingUploadBytes(@Param("states") Collection<com.watchwise.watchwise_api.platformimport.model.ImportJobState> states);

    @Query("SELECT job FROM ImportJob job WHERE job.id = :jobId AND job.userId = :ownerId")
    Optional<ImportJob> findOwnedById(@Param("jobId") UUID jobId, @Param("ownerId") UUID ownerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT job FROM ImportJob job WHERE job.id = :jobId AND job.userId = :ownerId")
    Optional<ImportJob> findOwnedByIdForUpdate(@Param("jobId") UUID jobId, @Param("ownerId") UUID ownerId);
}
