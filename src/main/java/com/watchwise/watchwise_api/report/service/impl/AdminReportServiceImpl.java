package com.watchwise.watchwise_api.report.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.pagination.PageRequestFactory;
import com.watchwise.watchwise_api.report.dto.AdminReportCaseDTO;
import com.watchwise.watchwise_api.report.dto.AdminReportCaseDetailDTO;
import com.watchwise.watchwise_api.report.dto.AdminReportCasePatchDTO;
import com.watchwise.watchwise_api.report.entity.ReportCase;
import com.watchwise.watchwise_api.report.entity.ReportCaseStatus;
import com.watchwise.watchwise_api.report.entity.ReportSubmission;
import com.watchwise.watchwise_api.report.repository.ReportCaseRepository;
import com.watchwise.watchwise_api.report.repository.ReportSubmissionRepository;
import com.watchwise.watchwise_api.report.service.AdminReportService;
import com.watchwise.watchwise_api.report.service.ReportNotificationService;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.entity.UserRole;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AdminReportServiceImpl implements AdminReportService {

    private final ReportCaseRepository reportCaseRepository;
    private final ReportSubmissionRepository reportSubmissionRepository;
    private final UserRepository userRepository;
    private final PageRequestFactory pageRequestFactory;
    private final ReportNotificationService reportNotificationService;

    @Override
    @Transactional(readOnly = true)
    public Page<AdminReportCaseDTO> list(UUID adminId, ReportCaseStatus status, Integer page, Integer size) {
        requireAdmin(adminId);
        if (status == null) {
            throw new BadRequestException("Report case status is required");
        }
        PageRequest pageable = pageRequestFactory.build(page, size);
        Page<ReportCase> cases = reportCaseRepository.findByStatusOrderByCreatedAtAscIdAsc(status, pageable);
        if (cases.isEmpty()) {
            return cases.map(reportCase -> toSummary(reportCase, 0));
        }
        var caseIds = cases.getContent().stream().map(ReportCase::getId).toList();
        Map<UUID, Long> submissionCounts = reportSubmissionRepository.countByReportCaseIdIn(caseIds)
                .stream().collect(Collectors.toMap(
                        ReportSubmissionRepository.ReportCaseSubmissionCount::getReportCaseId,
                        ReportSubmissionRepository.ReportCaseSubmissionCount::getCount));
        return cases.map(reportCase -> toSummary(
                reportCase, submissionCounts.getOrDefault(reportCase.getId(), 0L)));
    }

    @Override
    @Transactional(readOnly = true)
    public AdminReportCaseDetailDTO get(UUID adminId, UUID caseId) {
        requireAdmin(adminId);
        ReportCase reportCase = reportCaseRepository.findById(caseId)
                .orElseThrow(() -> new NotFoundException("Report case not found"));
        var submissions = reportSubmissionRepository.findWithReporterByReportCaseId(caseId).stream()
                .map(this::toSubmission)
                .toList();
        return new AdminReportCaseDetailDTO(
                reportCase.getId(), reportCase.getTargetType(), reportCase.getTargetId(), reportCase.getStatus(),
                reportCase.getInternalNotes(), reportCase.getCreatedAt(), reportCase.getUpdatedAt(),
                reportCase.getClosedAt(), submissions);
    }

    @Override
    @Transactional
    public AdminReportCaseDTO update(UUID adminId, UUID caseId, AdminReportCasePatchDTO request) {
        requireAdmin(adminId);
        if (request == null || request.status() == null) {
            throw new BadRequestException("Report case status is required");
        }
        if (request.internalNotes() != null && request.internalNotes().length() > 2000) {
            throw new BadRequestException("Internal notes must not exceed 2000 characters");
        }
        ReportCase reportCase = reportCaseRepository.findByIdForUpdate(caseId)
                .orElseThrow(() -> new NotFoundException("Report case not found"));
        validateTransition(reportCase.getStatus(), request.status());

        LocalDateTime now = LocalDateTime.now();
        reportCase.setStatus(request.status());
        reportCase.setUpdatedAt(now);
        if (request.internalNotes() != null) {
            String notes = request.internalNotes().trim();
            reportCase.setInternalNotes(notes.isEmpty() ? null : notes);
        }
        if (request.status() == ReportCaseStatus.RESOLVED || request.status() == ReportCaseStatus.DISMISSED) {
            reportCase.setClosedAt(now);
        }
        ReportCase saved = reportCaseRepository.saveAndFlush(reportCase);
        if (request.status() == ReportCaseStatus.RESOLVED || request.status() == ReportCaseStatus.DISMISSED) {
            reportNotificationService.notifyCaseClosed(saved.getId(), saved.getStatus());
        }
        return toSummary(saved, reportSubmissionRepository.countByReportCaseId(saved.getId()));
    }

    private void requireAdmin(UUID adminId) {
        User actor = userRepository.findById(adminId)
                .orElseThrow(() -> new ForbiddenException("Administrator access required"));
        if (actor.getRole() != UserRole.ADMIN) {
            throw new ForbiddenException("Administrator access required");
        }
    }

    private void validateTransition(ReportCaseStatus current, ReportCaseStatus next) {
        boolean valid = (current == ReportCaseStatus.OPEN && next == ReportCaseStatus.IN_REVIEW)
                || (current == ReportCaseStatus.IN_REVIEW
                    && (next == ReportCaseStatus.RESOLVED || next == ReportCaseStatus.DISMISSED));
        if (!valid) {
            throw new BadRequestException("Invalid report case status transition");
        }
    }

    private AdminReportCaseDTO toSummary(ReportCase reportCase, long submissionsCount) {
        return new AdminReportCaseDTO(reportCase.getId(), reportCase.getTargetType(), reportCase.getTargetId(),
                reportCase.getStatus(), submissionsCount, reportCase.getCreatedAt(), reportCase.getUpdatedAt(),
                reportCase.getClosedAt());
    }

    private AdminReportCaseDetailDTO.Submission toSubmission(ReportSubmission submission) {
        return new AdminReportCaseDetailDTO.Submission(
                submission.getId(), submission.getReporter().getId(), submission.getReporter().getUsername(),
                submission.getReason(), submission.getDetails(), submission.getTargetSnapshot(),
                submission.getCreatedAt());
    }
}
