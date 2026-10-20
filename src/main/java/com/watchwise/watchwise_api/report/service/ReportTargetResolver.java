package com.watchwise.watchwise_api.report.service;

import com.watchwise.watchwise_api.report.entity.ReportTargetType;

import java.util.UUID;

public interface ReportTargetResolver {
    ResolvedReportTarget resolve(UUID reporterId, ReportTargetType targetType, UUID targetId);
}
