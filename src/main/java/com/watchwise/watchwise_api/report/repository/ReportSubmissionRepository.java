package com.watchwise.watchwise_api.report.repository;

import com.watchwise.watchwise_api.report.entity.ReportSubmission;
import com.watchwise.watchwise_api.report.entity.ReportCaseStatus;
import com.watchwise.watchwise_api.report.entity.ReportTargetType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Collection;
import java.util.UUID;

public interface ReportSubmissionRepository extends JpaRepository<ReportSubmission, UUID> {

    interface ReportTargetIdentity {
        ReportTargetType getTargetType();
        UUID getTargetId();
    }

    boolean existsByReportCaseIdAndReporterId(UUID reportCaseId, UUID reporterId);

    Optional<ReportSubmission> findByReportCaseIdAndReporterId(UUID reportCaseId, UUID reporterId);

    List<ReportSubmission> findByReportCaseIdOrderByCreatedAtAsc(UUID reportCaseId);

    @Query("""
            SELECT submission FROM ReportSubmission submission JOIN FETCH submission.reporter
            WHERE submission.reportCase.id = :reportCaseId
            ORDER BY submission.createdAt ASC, submission.id ASC
            """)
    List<ReportSubmission> findWithReporterByReportCaseId(@Param("reportCaseId") UUID reportCaseId);

    interface ReportCaseSubmissionCount {
        UUID getReportCaseId();
        Long getCount();
    }

    @Query("""
            SELECT submission.reportCase.id AS reportCaseId, COUNT(submission.id) AS count
            FROM ReportSubmission submission
            WHERE submission.reportCase.id IN :reportCaseIds
            GROUP BY submission.reportCase.id
            """)
    List<ReportCaseSubmissionCount> countByReportCaseIdIn(@Param("reportCaseIds") List<UUID> reportCaseIds);

    long countByReportCaseId(UUID reportCaseId);

    @Query("""
            SELECT DISTINCT reportCase.targetType AS targetType, reportCase.targetId AS targetId
            FROM ReportSubmission submission
            JOIN submission.reportCase reportCase
            WHERE submission.reporter.id = :reporterId
              AND reportCase.status IN :activeStatuses
            ORDER BY reportCase.targetType ASC, reportCase.targetId ASC
            """)
    List<ReportTargetIdentity> findActiveTargetIdentitiesByReporterId(
            @Param("reporterId") UUID reporterId,
            @Param("activeStatuses") Collection<ReportCaseStatus> activeStatuses);

    @Modifying
    @Transactional
    @Query("DELETE FROM ReportSubmission submission WHERE submission.reporter.id = :reporterId")
    int deleteByReporterId(@Param("reporterId") UUID reporterId);
}
