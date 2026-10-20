package com.watchwise.watchwise_api.report.dto;

import com.watchwise.watchwise_api.report.entity.ReportReason;
import com.watchwise.watchwise_api.report.entity.ReportTargetType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record ReportCreationDTO(
        @NotNull ReportTargetType targetType,
        @NotNull UUID targetId,
        @NotNull ReportReason reason,
        @Size(max = 280) String details
) {
}
