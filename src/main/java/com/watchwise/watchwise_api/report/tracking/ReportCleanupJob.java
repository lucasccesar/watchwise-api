package com.watchwise.watchwise_api.report.tracking;

import com.watchwise.watchwise_api.report.service.ReportCleanupService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ReportCleanupJob {

    private final ReportCleanupService cleanupService;

    @Scheduled(cron = "${app.report.cleanup.cron:0 0 2 * * *}", zone = "UTC")
    public void removeExpiredCases() {
        cleanupService.removeExpiredCases();
    }
}
