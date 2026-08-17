package com.watchwise.watchwise_api.auth;

import com.watchwise.watchwise_api.auth.service.RefreshTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RefreshTokenCleanupJob {

    private final RefreshTokenService refreshTokenService;

    @Scheduled(cron = "${app.refresh-token-cleanup.cron}")
    public void cleanupExpiredTokens() {
        refreshTokenService.cleanupExpiredTokens();
    }
}
