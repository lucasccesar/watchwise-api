package com.watchwise.watchwise_api.platformimport.repository;

import com.watchwise.watchwise_api.platformimport.entity.ImportJob;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ImportJobRepository extends JpaRepository<ImportJob, UUID> {
    @Query("SELECT COUNT(job) FROM ImportJob job WHERE job.userId = :ownerId AND job.state IN :states AND job.uploadSizeBytes > 0")
    long countPendingByOwnerId(@Param("ownerId") UUID ownerId, @Param("states") Collection<com.watchwise.watchwise_api.platformimport.model.ImportJobState> states);

    @Query("SELECT COALESCE(SUM(job.uploadSizeBytes), 0) FROM ImportJob job WHERE job.userId = :ownerId AND job.state IN :states AND job.uploadSizeBytes > 0")
    long sumPendingUploadBytesByOwnerId(@Param("ownerId") UUID ownerId, @Param("states") Collection<com.watchwise.watchwise_api.platformimport.model.ImportJobState> states);

    @Query("SELECT COALESCE(SUM(job.uploadSizeBytes), 0) FROM ImportJob job WHERE job.state IN :states AND job.uploadSizeBytes > 0")
    long sumPendingUploadBytes(@Param("states") Collection<com.watchwise.watchwise_api.platformimport.model.ImportJobState> states);

    @Query("SELECT COALESCE(SUM(job.uploadSizeBytes), 0) FROM ImportJob job WHERE job.userId = :ownerId AND job.uploadSizeBytes > 0")
    long sumStoredUploadBytesByOwnerId(@Param("ownerId") UUID ownerId);

    @Query("SELECT COALESCE(SUM(job.uploadSizeBytes), 0) FROM ImportJob job WHERE job.uploadSizeBytes > 0")
    long sumStoredUploadBytes();

    @Query("SELECT job FROM ImportJob job WHERE job.id = :jobId AND job.userId = :ownerId")
    Optional<ImportJob> findOwnedById(@Param("jobId") UUID jobId, @Param("ownerId") UUID ownerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT job FROM ImportJob job WHERE job.id = :jobId AND job.userId = :ownerId")
    Optional<ImportJob> findOwnedByIdForUpdate(@Param("jobId") UUID jobId, @Param("ownerId") UUID ownerId);

    List<ImportJob> findAllByUserId(UUID userId);

    Optional<ImportJob> findByStorageKey(String storageKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT job FROM ImportJob job WHERE job.id = :jobId")
    Optional<ImportJob> findByIdForUpdate(@Param("jobId") UUID jobId);

    @Query("""
            SELECT job FROM ImportJob job
            WHERE job.uploadSizeBytes > 0 AND job.expiresAt <= :now
              AND job.state <> :activeState
              AND (job.leaseUntil IS NULL OR job.leaseUntil <= :now)
            ORDER BY job.expiresAt ASC, job.id ASC
            """)
    List<ImportJob> findExpiredUploadCandidates(@Param("now") LocalDateTime now,
                                                @Param("activeState") com.watchwise.watchwise_api.platformimport.model.ImportJobState activeState,
                                                Pageable pageable);
}
