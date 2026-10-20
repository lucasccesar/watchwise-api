package com.watchwise.watchwise_api.common.transaction;

import com.watchwise.watchwise_api.report.entity.ReportTargetType;

import java.util.UUID;

public final class ReportTargetLockIdentity {

    private ReportTargetLockIdentity() {
    }

    public static String of(ReportTargetType targetType, UUID targetId) {
        return "report-target|" + targetType.name() + "|" + targetId;
    }
}
