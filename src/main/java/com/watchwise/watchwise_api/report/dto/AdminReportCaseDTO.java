package com.watchwise.watchwise_api.report.dto;

import com.watchwise.watchwise_api.report.entity.ReportCaseStatus;
import com.watchwise.watchwise_api.report.entity.ReportTargetType;

import java.time.LocalDateTime;
import java.util.UUID;

public record AdminReportCaseDTO(
        UUID id,
        ReportTargetType targetType,
        UUID targetId,
        ReportCaseStatus status,
        long submissionsCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime closedAt
) {
}
