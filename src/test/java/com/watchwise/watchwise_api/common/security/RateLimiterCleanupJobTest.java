package com.watchwise.watchwise_api.common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RateLimiterCleanupJobTest {

    @Mock
    private AttemptLockout attemptLockout;

    @Mock
    private RequestThrottler requestThrottler;

    @InjectMocks
    private RateLimiterCleanupJob rateLimiterCleanupJob;

    @Test
    @DisplayName("[cleanupExpiredEntries] Should Delegate To AttemptLockout And RequestThrottler - When Called")
    void shouldDelegateToAttemptLockoutAndRequestThrottlerWhenCalled() {
        rateLimiterCleanupJob.cleanupExpiredEntries();

        verify(attemptLockout).cleanupExpired();
        verify(requestThrottler).cleanupExpired();
    }
}
