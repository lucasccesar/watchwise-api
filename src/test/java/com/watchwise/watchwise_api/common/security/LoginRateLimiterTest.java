package com.watchwise.watchwise_api.common.security;

import com.watchwise.watchwise_api.common.exception.TooManyRequestsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoginRateLimiterTest {

    private static final int MAX_ATTEMPTS = 3;
    private static final long WINDOW_MINUTES = 10;
    private static final long BLOCK_MINUTES = 5;

    private final AtomicReference<Instant> currentInstant = new AtomicReference<>(Instant.parse("2024-01-01T00:00:00Z"));

    private final Clock clock = new Clock() {
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return currentInstant.get();
        }
    };

    private LoginRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        rateLimiter = new LoginRateLimiter(MAX_ATTEMPTS, WINDOW_MINUTES, BLOCK_MINUTES, clock);
    }

    private void advanceClock(Duration duration) {
        currentInstant.set(currentInstant.get().plus(duration));
    }

    @Test
    @DisplayName("[checkAllowed] Should Not Throw - When No Prior Attempts Exist")
    void shouldNotThrowWhenNoPriorAttemptsExist() {
        assertThatCode(() -> rateLimiter.checkAllowed("1.2.3.4|user"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("[checkAllowed] Should Not Throw - When Failures Are Below The Max Attempts")
    void shouldNotThrowWhenFailuresAreBelowMaxAttempts() {
        String key = "1.2.3.4|user";

        rateLimiter.recordFailure(key);
        rateLimiter.recordFailure(key);

        assertThatCode(() -> rateLimiter.checkAllowed(key))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("[checkAllowed] Should Throw TooManyRequestsException - When Failures Reach The Max Attempts")
    void shouldThrowTooManyRequestsExceptionWhenFailuresReachMaxAttempts() {
        String key = "1.2.3.4|user";

        rateLimiter.recordFailure(key);
        rateLimiter.recordFailure(key);
        rateLimiter.recordFailure(key);

        assertThatThrownBy(() -> rateLimiter.checkAllowed(key))
                .isInstanceOf(TooManyRequestsException.class)
                .hasMessage("Too many login attempts. Try again later.");
    }

    @Test
    @DisplayName("[checkAllowed] Should Not Throw - When Block Duration Has Elapsed")
    void shouldNotThrowWhenBlockDurationHasElapsed() {
        String key = "1.2.3.4|user";

        rateLimiter.recordFailure(key);
        rateLimiter.recordFailure(key);
        rateLimiter.recordFailure(key);
        assertThatThrownBy(() -> rateLimiter.checkAllowed(key)).isInstanceOf(TooManyRequestsException.class);

        advanceClock(Duration.ofMinutes(BLOCK_MINUTES).plusSeconds(1));

        assertThatCode(() -> rateLimiter.checkAllowed(key))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("[recordFailure] Should Reset The Counter - When The Block Duration Has Already Elapsed")
    void shouldResetTheCounterWhenBlockDurationHasAlreadyElapsed() {
        String key = "1.2.3.4|user";

        rateLimiter.recordFailure(key);
        rateLimiter.recordFailure(key);
        rateLimiter.recordFailure(key);
        advanceClock(Duration.ofMinutes(BLOCK_MINUTES).plusSeconds(1));

        rateLimiter.recordFailure(key);

        assertThatCode(() -> rateLimiter.checkAllowed(key))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("[recordFailure] Should Reset The Counter - When The Window Has Naturally Expired")
    void shouldResetTheCounterWhenTheWindowHasNaturallyExpired() {
        String key = "1.2.3.4|user";

        rateLimiter.recordFailure(key);
        rateLimiter.recordFailure(key);
        advanceClock(Duration.ofMinutes(WINDOW_MINUTES).plusSeconds(1));

        rateLimiter.recordFailure(key);
        rateLimiter.recordFailure(key);

        assertThatCode(() -> rateLimiter.checkAllowed(key))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("[recordSuccess] Should Clear Prior Failures - When Called")
    void shouldClearPriorFailuresWhenRecordSuccessCalled() {
        String key = "1.2.3.4|user";

        rateLimiter.recordFailure(key);
        rateLimiter.recordFailure(key);
        rateLimiter.recordSuccess(key);
        rateLimiter.recordFailure(key);
        rateLimiter.recordFailure(key);

        assertThatCode(() -> rateLimiter.checkAllowed(key))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("[checkAllowed] Should Not Block A Different Identifier - When Another Identifier Is Blocked")
    void shouldNotBlockADifferentIdentifierWhenAnotherIdentifierIsBlocked() {
        String blockedKey = "1.2.3.4|userA";
        String otherKey = "1.2.3.4|userB";

        rateLimiter.recordFailure(blockedKey);
        rateLimiter.recordFailure(blockedKey);
        rateLimiter.recordFailure(blockedKey);

        assertThatThrownBy(() -> rateLimiter.checkAllowed(blockedKey)).isInstanceOf(TooManyRequestsException.class);
        assertThatCode(() -> rateLimiter.checkAllowed(otherKey)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("[checkAllowed] Should Not Block A Different Ip - When Another Ip Is Blocked")
    void shouldNotBlockADifferentIpWhenAnotherIpIsBlocked() {
        String blockedKey = "1.2.3.4|user";
        String otherKey = "5.6.7.8|user";

        rateLimiter.recordFailure(blockedKey);
        rateLimiter.recordFailure(blockedKey);
        rateLimiter.recordFailure(blockedKey);

        assertThatThrownBy(() -> rateLimiter.checkAllowed(blockedKey)).isInstanceOf(TooManyRequestsException.class);
        assertThatCode(() -> rateLimiter.checkAllowed(otherKey)).doesNotThrowAnyException();
    }
}
