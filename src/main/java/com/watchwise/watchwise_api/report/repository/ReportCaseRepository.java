package com.watchwise.watchwise_api.report.repository;

import com.watchwise.watchwise_api.report.entity.ReportCase;
import com.watchwise.watchwise_api.report.entity.ReportCaseStatus;
import com.watchwise.watchwise_api.report.entity.ReportTargetType;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

public interface ReportCaseRepository extends JpaRepository<ReportCase, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT reportCase FROM ReportCase reportCase
            WHERE reportCase.targetType = :targetType
              AND reportCase.targetId = :targetId
              AND reportCase.status IN (com.watchwise.watchwise_api.report.entity.ReportCaseStatus.OPEN,
                                        com.watchwise.watchwise_api.report.entity.ReportCaseStatus.IN_REVIEW)
            """)
    Optional<ReportCase> findActiveForUpdate(@Param("targetType") ReportTargetType targetType,
            @Param("targetId") UUID targetId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT reportCase FROM ReportCase reportCase WHERE reportCase.id = :caseId")
    Optional<ReportCase> findByIdForUpdate(@Param("caseId") UUID caseId);

    Page<ReportCase> findByStatusOrderByCreatedAtAscIdAsc(ReportCaseStatus status, Pageable pageable);

    @Modifying
    @Transactional
    @Query("DELETE FROM ReportCase reportCase WHERE NOT EXISTS "
            + "(SELECT submission.id FROM ReportSubmission submission WHERE submission.reportCase = reportCase)")
    int deleteEmptyCases();

    @Modifying
    @Transactional
    @Query(value = """
            WITH expired AS (
                SELECT id
                FROM report_cases
                WHERE status IN ('RESOLVED', 'DISMISSED')
                  AND closed_at < :cutoff
                ORDER BY closed_at ASC, id ASC
                LIMIT :batchSize
            )
            DELETE FROM report_cases report_case
            USING expired
            WHERE report_case.id = expired.id
            """, nativeQuery = true)
    int deleteClosedBefore(@Param("cutoff") java.time.LocalDateTime cutoff,
            @Param("batchSize") int batchSize);
}
