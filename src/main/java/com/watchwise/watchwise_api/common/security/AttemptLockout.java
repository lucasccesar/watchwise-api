package com.watchwise.watchwise_api.common.security;

import com.watchwise.watchwise_api.common.exception.TooManyRequestsException;
import org.springframework.stereotype.Component;
import java.time.Duration;

@Component
public class AttemptLockout {
    static final int MAX_TRACKED_KEYS = 10_000;
    private final SharedLimiterStore store;

    public AttemptLockout(SharedLimiterStore store) {
        this.store = store;
    }

    public void checkAllowed(String key, int maxAttempts, Duration window) {
        if (!store.reserve("lockout", key, maxAttempts, window)) {
            throw new TooManyRequestsException("Too many attempts. Try again later.");
        }
    }

    public void recordFailure(String key, int maxAttempts, Duration blockDuration) {
        store.recordFailure(key, maxAttempts, blockDuration);
    }

    public void recordSuccess(String key) {
        store.recordSuccess(key);
    }

    public void cleanupExpired() {
        store.cleanupExpired("lockout");
    }
}
