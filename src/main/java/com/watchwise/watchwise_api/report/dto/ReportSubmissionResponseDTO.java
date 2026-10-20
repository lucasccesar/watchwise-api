package com.watchwise.watchwise_api.report.dto;

import com.watchwise.watchwise_api.report.entity.ReportCaseStatus;

import java.time.LocalDateTime;

public record ReportSubmissionResponseDTO(ReportCaseStatus status, LocalDateTime submittedAt) {
}
