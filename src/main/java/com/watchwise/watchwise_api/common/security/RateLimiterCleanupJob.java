package com.watchwise.watchwise_api.common.security;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RateLimiterCleanupJob {

    private final AttemptLockout attemptLockout;
    private final RequestThrottler requestThrottler;

    @Scheduled(cron = "${app.rate-limit.cleanup.cron}")
    public void cleanupExpiredEntries() {
        attemptLockout.cleanupExpired();
        requestThrottler.cleanupExpired();
    }
}
