package com.watchwise.watchwise_api.report.dto;

import com.watchwise.watchwise_api.report.entity.ReportCaseStatus;
import jakarta.validation.constraints.Size;

public record AdminReportCasePatchDTO(
        ReportCaseStatus status,
        @Size(max = 2000) String internalNotes
) {
}
