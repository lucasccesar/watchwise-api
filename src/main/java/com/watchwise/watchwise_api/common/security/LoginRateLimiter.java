package com.watchwise.watchwise_api.common.security;

import com.watchwise.watchwise_api.common.exception.TooManyRequestsException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class LoginRateLimiter {

    private final int maxAttempts;
    private final Duration window;
    private final Duration blockDuration;
    private final Clock clock;
    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    public LoginRateLimiter(
            @Value("${app.rate-limit.login.max-attempts}") int maxAttempts,
            @Value("${app.rate-limit.login.window-minutes}") long windowMinutes,
            @Value("${app.rate-limit.login.block-minutes}") long blockMinutes,
            Clock clock
    ) {
        this.maxAttempts = maxAttempts;
        this.window = Duration.ofMinutes(windowMinutes);
        this.blockDuration = Duration.ofMinutes(blockMinutes);
        this.clock = clock;
    }

    public void checkAllowed(String key) {
        Attempt attempt = attempts.get(key);
        if (attempt != null && attempt.blockedUntil() != null && clock.instant().isBefore(attempt.blockedUntil())) {
            throw new TooManyRequestsException("Too many login attempts. Try again later.");
        }
    }

    public void recordFailure(String key) {
        Instant now = clock.instant();
        attempts.compute(key, (k, existing) -> {
            boolean expired = existing == null
                    || now.isAfter(existing.windowStart().plus(window))
                    || (existing.blockedUntil() != null && !now.isBefore(existing.blockedUntil()));

            if (expired) {
                return new Attempt(1, now, null);
            }

            int count = existing.count() + 1;
            Instant blockedUntil = count >= maxAttempts ? now.plus(blockDuration) : null;
            return new Attempt(count, existing.windowStart(), blockedUntil);
        });
    }

    public void recordSuccess(String key) {
        attempts.remove(key);
    }

    private record Attempt(int count, Instant windowStart, Instant blockedUntil) {
    }
}
