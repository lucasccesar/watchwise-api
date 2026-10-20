package com.watchwise.watchwise_api.report.service.impl;

import com.watchwise.watchwise_api.common.transaction.AdvisoryLock;
import com.watchwise.watchwise_api.common.transaction.ReportTargetLockIdentity;
import com.watchwise.watchwise_api.report.entity.ReportCaseStatus;
import com.watchwise.watchwise_api.report.repository.ReportCaseRepository;
import com.watchwise.watchwise_api.report.repository.ReportSubmissionRepository;
import com.watchwise.watchwise_api.report.service.ReportReporterDeletionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ReportReporterDeletionServiceImpl implements ReportReporterDeletionService {

    private final ReportSubmissionRepository reportSubmissionRepository;
    private final ReportCaseRepository reportCaseRepository;
    private final AdvisoryLock advisoryLock;

    @Override
    @Transactional
    public void removeReporterSubmissions(UUID reporterId) {
        var activeStatuses = List.of(ReportCaseStatus.OPEN, ReportCaseStatus.IN_REVIEW);
        reportSubmissionRepository.findActiveTargetIdentitiesByReporterId(reporterId, activeStatuses)
                .forEach(target -> advisoryLock.lock(
                        ReportTargetLockIdentity.of(target.getTargetType(), target.getTargetId())));
        reportSubmissionRepository.deleteByReporterId(reporterId);
        reportCaseRepository.deleteEmptyCases();
    }
}
