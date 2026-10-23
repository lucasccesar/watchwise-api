package com.watchwise.watchwise_api.report.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.transaction.AdvisoryLock;
import com.watchwise.watchwise_api.common.transaction.ReportTargetLockIdentity;
import com.watchwise.watchwise_api.report.dto.ReportCreationDTO;
import com.watchwise.watchwise_api.report.dto.ReportSubmissionResponseDTO;
import com.watchwise.watchwise_api.report.entity.ReportCase;
import com.watchwise.watchwise_api.report.entity.ReportCaseStatus;
import com.watchwise.watchwise_api.report.entity.ReportReason;
import com.watchwise.watchwise_api.report.entity.ReportSubmission;
import com.watchwise.watchwise_api.report.entity.ReportTargetType;
import com.watchwise.watchwise_api.report.repository.ReportCaseRepository;
import com.watchwise.watchwise_api.report.repository.ReportSubmissionRepository;
import com.watchwise.watchwise_api.report.service.ReportService;
import com.watchwise.watchwise_api.report.service.ReportTargetResolver;
import com.watchwise.watchwise_api.report.service.ResolvedReportTarget;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ReportServiceImpl implements ReportService {

    private final ReportCaseRepository reportCaseRepository;
    private final ReportSubmissionRepository reportSubmissionRepository;
    private final ReportTargetResolver reportTargetResolver;
    private final UserRepository userRepository;
    private final AdvisoryLock advisoryLock;

    @Override
    @Transactional
    public ReportSubmissionResponseDTO create(UUID reporterId, ReportCreationDTO request) {
        if (request == null || request.targetType() == null || request.targetId() == null || request.reason() == null) {
            throw new BadRequestException("Report fields are required");
        }
        if (request.details() != null && request.details().length() > 280) {
            throw new BadRequestException("Report details must not exceed 280 characters");
        }
        validateReason(request.targetType(), request.reason());

        ResolvedReportTarget target = reportTargetResolver.resolve(
                reporterId, request.targetType(), request.targetId());
        advisoryLock.lock(ReportTargetLockIdentity.of(request.targetType(), target.targetId()));

        ReportCase reportCase = reportCaseRepository.findActiveForUpdate(request.targetType(), target.targetId())
                .orElseGet(() -> createCase(request.targetType(), target.targetId()));
        Optional<ReportSubmission> existing = reportSubmissionRepository
                .findByReportCaseIdAndReporterId(reportCase.getId(), reporterId);
        if (existing.isPresent()) {
            return new ReportSubmissionResponseDTO(reportCase.getStatus(), existing.get().getCreatedAt());
        }

        LocalDateTime now = LocalDateTime.now();
        String details = normalizeDetails(request.details());
        ReportSubmission submission = ReportSubmission.builder()
                .reportCase(reportCase)
                .reporter(userRepository.getReferenceById(reporterId))
                .reason(request.reason())
                .details(details)
                .targetSnapshot(target.snapshot())
                .createdAt(now)
                .build();
        reportSubmissionRepository.saveAndFlush(submission);
        return new ReportSubmissionResponseDTO(reportCase.getStatus(), now);
    }

    private ReportCase createCase(ReportTargetType targetType, UUID targetId) {
        LocalDateTime now = LocalDateTime.now();
        return reportCaseRepository.saveAndFlush(ReportCase.builder()
                .targetType(targetType)
                .targetId(targetId)
                .status(ReportCaseStatus.OPEN)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    private void validateReason(ReportTargetType targetType, ReportReason reason) {
        if (reason == ReportReason.UNMARKED_SPOILER
                && targetType != ReportTargetType.COMMENT
                && targetType != ReportTargetType.REVIEW
                && targetType != ReportTargetType.CONTENT_REVIEW) {
            throw new BadRequestException("Unmarked spoiler applies only to comments and reviews");
        }
    }

    private String normalizeDetails(String details) {
        if (details == null) {
            return null;
        }
        String trimmed = details.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
