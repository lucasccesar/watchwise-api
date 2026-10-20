package com.watchwise.watchwise_api.report.dto;

import com.watchwise.watchwise_api.report.entity.ReportCaseStatus;
import com.watchwise.watchwise_api.report.entity.ReportReason;
import com.watchwise.watchwise_api.report.entity.ReportTargetType;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record AdminReportCaseDetailDTO(
        UUID id,
        ReportTargetType targetType,
        UUID targetId,
        ReportCaseStatus status,
        String internalNotes,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime closedAt,
        List<Submission> submissions
) {
    public record Submission(
            UUID id,
            UUID reporterId,
            String reporterUsername,
            ReportReason reason,
            String details,
            String targetSnapshot,
            LocalDateTime createdAt
    ) {
    }
}
