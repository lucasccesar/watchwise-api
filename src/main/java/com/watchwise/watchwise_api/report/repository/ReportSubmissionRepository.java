package com.watchwise.watchwise_api.report.repository;

import com.watchwise.watchwise_api.report.entity.ReportSubmission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReportSubmissionRepository extends JpaRepository<ReportSubmission, UUID> {

    boolean existsByReportCaseIdAndReporterId(UUID reportCaseId, UUID reporterId);

    List<ReportSubmission> findByReportCaseIdOrderByCreatedAtAsc(UUID reportCaseId);
}
