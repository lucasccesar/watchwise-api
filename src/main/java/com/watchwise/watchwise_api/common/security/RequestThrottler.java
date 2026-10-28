package com.watchwise.watchwise_api.common.security;

import com.watchwise.watchwise_api.common.exception.TooManyRequestsException;
import org.springframework.stereotype.Component;
import java.time.Duration;

@Component
public class RequestThrottler {
    private final SharedLimiterStore store;

    public RequestThrottler(SharedLimiterStore store) {
        this.store = store;
    }

    public void checkAllowed(String key, int maxRequests, Duration window) {
        if (!store.reserve("request", key, maxRequests, window)) {
            throw new TooManyRequestsException("Too many requests. Try again later.");
        }
    }

    public void cleanupExpired() {
        store.cleanupExpired("request");
    }
}
