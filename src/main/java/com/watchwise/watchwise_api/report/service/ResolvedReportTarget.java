package com.watchwise.watchwise_api.report.service;

import java.util.UUID;

public record ResolvedReportTarget(UUID targetId, UUID ownerId, String snapshot) {
}
