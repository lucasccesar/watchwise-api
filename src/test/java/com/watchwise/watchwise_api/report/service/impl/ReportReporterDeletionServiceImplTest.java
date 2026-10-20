package com.watchwise.watchwise_api.report.service.impl;

import com.watchwise.watchwise_api.common.transaction.AdvisoryLock;
import com.watchwise.watchwise_api.report.entity.ReportCaseStatus;
import com.watchwise.watchwise_api.report.entity.ReportTargetType;
import com.watchwise.watchwise_api.report.repository.ReportCaseRepository;
import com.watchwise.watchwise_api.report.repository.ReportSubmissionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportReporterDeletionServiceImplTest {

    @Mock ReportSubmissionRepository reportSubmissionRepository;
    @Mock ReportCaseRepository reportCaseRepository;
    @Mock AdvisoryLock advisoryLock;
    @InjectMocks ReportReporterDeletionServiceImpl service;

    @Test
    void removesReporterSubmissionsAndThenDeletesEmptyCases() {
        UUID reporterId = UUID.randomUUID();
        when(reportSubmissionRepository.findActiveTargetIdentitiesByReporterId(
                reporterId, List.of(ReportCaseStatus.OPEN, ReportCaseStatus.IN_REVIEW))).thenReturn(List.of());

        service.removeReporterSubmissions(reporterId);

        var order = inOrder(reportSubmissionRepository, advisoryLock, reportCaseRepository);
        order.verify(reportSubmissionRepository).findActiveTargetIdentitiesByReporterId(
                reporterId, List.of(ReportCaseStatus.OPEN, ReportCaseStatus.IN_REVIEW));
        order.verify(reportSubmissionRepository).deleteByReporterId(reporterId);
        order.verify(reportCaseRepository).deleteEmptyCases();
    }

    @Test
    void locksEveryDistinctTargetBeforeDeletingReporterSubmissions() {
        UUID reporterId = UUID.randomUUID();
        UUID commentId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        var commentTarget = mock(ReportSubmissionRepository.ReportTargetIdentity.class);
        when(commentTarget.getTargetType()).thenReturn(ReportTargetType.COMMENT);
        when(commentTarget.getTargetId()).thenReturn(commentId);
        var userTarget = mock(ReportSubmissionRepository.ReportTargetIdentity.class);
        when(userTarget.getTargetType()).thenReturn(ReportTargetType.USER);
        when(userTarget.getTargetId()).thenReturn(userId);
        when(reportSubmissionRepository.findActiveTargetIdentitiesByReporterId(
                reporterId, List.of(ReportCaseStatus.OPEN, ReportCaseStatus.IN_REVIEW)))
                .thenReturn(List.of(commentTarget, userTarget));

        service.removeReporterSubmissions(reporterId);

        var order = inOrder(reportSubmissionRepository, advisoryLock, reportCaseRepository);
        order.verify(reportSubmissionRepository).findActiveTargetIdentitiesByReporterId(
                reporterId, List.of(ReportCaseStatus.OPEN, ReportCaseStatus.IN_REVIEW));
        order.verify(advisoryLock).lock("report-target|COMMENT|" + commentId);
        order.verify(advisoryLock).lock("report-target|USER|" + userId);
        order.verify(reportSubmissionRepository).deleteByReporterId(reporterId);
        order.verify(reportCaseRepository).deleteEmptyCases();
    }
}
