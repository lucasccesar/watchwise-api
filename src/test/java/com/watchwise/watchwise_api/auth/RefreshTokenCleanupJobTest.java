package com.watchwise.watchwise_api.auth;

import com.watchwise.watchwise_api.auth.service.RefreshTokenService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RefreshTokenCleanupJobTest {

    @Mock
    private RefreshTokenService refreshTokenService;

    @InjectMocks
    private RefreshTokenCleanupJob refreshTokenCleanupJob;

    @Test
    @DisplayName("[cleanupExpiredTokens] Should Delegate To RefreshTokenService - When Called")
    void shouldDelegateToRefreshTokenServiceWhenCalled() {
        refreshTokenCleanupJob.cleanupExpiredTokens();

        verify(refreshTokenService).cleanupExpiredTokens();
    }
}
