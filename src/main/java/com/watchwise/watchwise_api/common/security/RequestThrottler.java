package com.watchwise.watchwise_api.common.security;

import com.watchwise.watchwise_api.common.exception.TooManyRequestsException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RequestThrottler {

    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public RequestThrottler(Clock clock) {
        this.clock = clock;
    }

    public void checkAllowed(String key, int maxRequests, Duration window) {
        Instant now = clock.instant();

        windows.compute(key, (k, existing) -> {
            boolean expired = existing == null || now.isAfter(existing.windowStart().plus(existing.window()));

            if (expired) {
                return new Window(1, now, window);
            }

            if (existing.count() >= maxRequests) {
                throw new TooManyRequestsException("Too many requests. Try again later.");
            }

            return new Window(existing.count() + 1, existing.windowStart(), existing.window());
        });
    }

    public void cleanupExpired() {
        Instant now = clock.instant();
        windows.entrySet().removeIf(entry -> now.isAfter(entry.getValue().windowStart().plus(entry.getValue().window())));
    }

    void reset() {
        windows.clear();
    }

    int size() {
        return windows.size();
    }

    private record Window(int count, Instant windowStart, Duration window) {
    }
}
