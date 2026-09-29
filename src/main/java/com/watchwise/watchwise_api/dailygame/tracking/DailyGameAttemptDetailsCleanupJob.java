package com.watchwise.watchwise_api.dailygame.tracking;

import com.watchwise.watchwise_api.dailygame.service.DailyGameAttemptDetailsCleanupService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

@Component
public class DailyGameAttemptDetailsCleanupJob {

    private final DailyGameAttemptDetailsCleanupService cleanupService;
    private final Clock clock;

    public DailyGameAttemptDetailsCleanupJob(DailyGameAttemptDetailsCleanupService cleanupService, Clock clock) {
        this.cleanupService = cleanupService;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.daily-games.attempt-details-cleanup.cron}", zone = "UTC")
    public void run() {
        cleanupService.cleanup(LocalDate.now(clock));
    }
}
