package com.watchwise.watchwise_api.report.service;

import com.watchwise.watchwise_api.report.entity.ReportCaseStatus;

import java.util.UUID;

public interface ReportNotificationService {
    void notifyCaseClosed(UUID caseId, ReportCaseStatus finalStatus);
}
