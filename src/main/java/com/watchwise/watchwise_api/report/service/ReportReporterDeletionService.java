package com.watchwise.watchwise_api.report.service;

import java.util.UUID;

public interface ReportReporterDeletionService {
    void removeReporterSubmissions(UUID reporterId);
}
