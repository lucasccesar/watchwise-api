package com.watchwise.watchwise_api.common.security;

import com.watchwise.watchwise_api.common.exception.TooManyRequestsException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AttemptLockout {

    private final Clock clock;
    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    public AttemptLockout(Clock clock) {
        this.clock = clock;
    }

    public void checkAllowed(String key, int maxAttempts, Duration window) {
        Instant now = clock.instant();
        attempts.compute(key, (k, existing) -> {
            boolean stillBlocked = existing != null
                    && existing.blockedUntil() != null
                    && now.isBefore(existing.blockedUntil());
            if (stillBlocked) {
                throw new TooManyRequestsException("Too many attempts. Try again later.");
            }

            boolean expired = existing == null
                    || now.isAfter(existing.windowStart().plus(existing.window()))
                    || existing.blockedUntil() != null;
            if (expired) {
                return new Attempt(1, now, null, window);
            }

            int count = existing.count() + 1;
            if (count > maxAttempts) {
                throw new TooManyRequestsException("Too many attempts. Try again later.");
            }

            return new Attempt(count, existing.windowStart(), null, existing.window());
        });
    }

    public void recordFailure(String key, int maxAttempts, Duration blockDuration) {
        Instant now = clock.instant();
        attempts.computeIfPresent(key, (k, existing) -> {
            Instant blockedUntil = existing.count() >= maxAttempts ? now.plus(blockDuration) : existing.blockedUntil();
            return new Attempt(existing.count(), existing.windowStart(), blockedUntil, existing.window());
        });
    }

    public void recordSuccess(String key) {
        attempts.remove(key);
    }

    public void cleanupExpired() {
        Instant now = clock.instant();
        attempts.entrySet().removeIf(entry -> {
            Attempt attempt = entry.getValue();
            boolean stillBlocked = attempt.blockedUntil() != null && now.isBefore(attempt.blockedUntil());
            boolean windowStillOpen = now.isBefore(attempt.windowStart().plus(attempt.window()));
            return !stillBlocked && !windowStillOpen;
        });
    }

    int size() {
        return attempts.size();
    }

    private record Attempt(int count, Instant windowStart, Instant blockedUntil, Duration window) {
    }
}
