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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AttemptLockoutTest {

    private static final int MAX_ATTEMPTS = 3;
    private static final Duration WINDOW = Duration.ofMinutes(10);
    private static final Duration BLOCK_DURATION = Duration.ofMinutes(5);

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

    private AttemptLockout attemptLockout;

    @BeforeEach
    void setUp() {
        attemptLockout = new AttemptLockout(clock);
    }

    private void advanceClock(Duration duration) {
        currentInstant.set(currentInstant.get().plus(duration));
    }

    private void recordFailure(String key) {
        attemptLockout.recordFailure(key, MAX_ATTEMPTS, WINDOW, BLOCK_DURATION);
    }

    @Test
    @DisplayName("[checkAllowed] Should Not Throw - When No Prior Attempts Exist")
    void shouldNotThrowWhenNoPriorAttemptsExist() {
        assertThatCode(() -> attemptLockout.checkAllowed("1.2.3.4|user"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("[checkAllowed] Should Not Throw - When Failures Are Below The Max Attempts")
    void shouldNotThrowWhenFailuresAreBelowMaxAttempts() {
        String key = "1.2.3.4|user";

        recordFailure(key);
        recordFailure(key);

        assertThatCode(() -> attemptLockout.checkAllowed(key))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("[checkAllowed] Should Throw TooManyRequestsException - When Failures Reach The Max Attempts")
    void shouldThrowTooManyRequestsExceptionWhenFailuresReachMaxAttempts() {
        String key = "1.2.3.4|user";

        recordFailure(key);
        recordFailure(key);
        recordFailure(key);

        assertThatThrownBy(() -> attemptLockout.checkAllowed(key))
                .isInstanceOf(TooManyRequestsException.class)
                .hasMessage("Too many attempts. Try again later.");
    }

    @Test
    @DisplayName("[checkAllowed] Should Throw TooManyRequestsException - When Max Attempts Is One And The First Failure Is Recorded")
    void shouldThrowTooManyRequestsExceptionWhenMaxAttemptsIsOneAndTheFirstFailureIsRecorded() {
        String key = "1.2.3.4|user";

        attemptLockout.recordFailure(key, 1, WINDOW, BLOCK_DURATION);

        assertThatThrownBy(() -> attemptLockout.checkAllowed(key))
                .isInstanceOf(TooManyRequestsException.class)
                .hasMessage("Too many attempts. Try again later.");
    }

    @Test
    @DisplayName("[checkAllowed] Should Not Throw - When Block Duration Has Elapsed")
    void shouldNotThrowWhenBlockDurationHasElapsed() {
        String key = "1.2.3.4|user";

        recordFailure(key);
        recordFailure(key);
        recordFailure(key);
        assertThatThrownBy(() -> attemptLockout.checkAllowed(key)).isInstanceOf(TooManyRequestsException.class);

        advanceClock(BLOCK_DURATION.plusSeconds(1));

        assertThatCode(() -> attemptLockout.checkAllowed(key))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("[recordFailure] Should Reset The Counter - When The Block Duration Has Already Elapsed")
    void shouldResetTheCounterWhenBlockDurationHasAlreadyElapsed() {
        String key = "1.2.3.4|user";

        recordFailure(key);
        recordFailure(key);
        recordFailure(key);
        advanceClock(BLOCK_DURATION.plusSeconds(1));

        recordFailure(key);

        assertThatCode(() -> attemptLockout.checkAllowed(key))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("[recordFailure] Should Reset The Counter - When The Window Has Naturally Expired")
    void shouldResetTheCounterWhenTheWindowHasNaturallyExpired() {
        String key = "1.2.3.4|user";

        recordFailure(key);
        recordFailure(key);
        advanceClock(WINDOW.plusSeconds(1));

        recordFailure(key);
        recordFailure(key);

        assertThatCode(() -> attemptLockout.checkAllowed(key))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("[recordSuccess] Should Clear Prior Failures - When Called")
    void shouldClearPriorFailuresWhenRecordSuccessCalled() {
        String key = "1.2.3.4|user";

        recordFailure(key);
        recordFailure(key);
        attemptLockout.recordSuccess(key);
        recordFailure(key);
        recordFailure(key);

        assertThatCode(() -> attemptLockout.checkAllowed(key))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("[checkAllowed] Should Not Block A Different Identifier - When Another Identifier Is Blocked")
    void shouldNotBlockADifferentIdentifierWhenAnotherIdentifierIsBlocked() {
        String blockedKey = "1.2.3.4|userA";
        String otherKey = "1.2.3.4|userB";

        recordFailure(blockedKey);
        recordFailure(blockedKey);
        recordFailure(blockedKey);

        assertThatThrownBy(() -> attemptLockout.checkAllowed(blockedKey)).isInstanceOf(TooManyRequestsException.class);
        assertThatCode(() -> attemptLockout.checkAllowed(otherKey)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("[checkAllowed] Should Not Block A Different Ip - When Another Ip Is Blocked")
    void shouldNotBlockADifferentIpWhenAnotherIpIsBlocked() {
        String blockedKey = "1.2.3.4|user";
        String otherKey = "5.6.7.8|user";

        recordFailure(blockedKey);
        recordFailure(blockedKey);
        recordFailure(blockedKey);

        assertThatThrownBy(() -> attemptLockout.checkAllowed(blockedKey)).isInstanceOf(TooManyRequestsException.class);
        assertThatCode(() -> attemptLockout.checkAllowed(otherKey)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("[checkAllowed] Should Not Block A Different Action - When Same Key Is Blocked For Another Action")
    void shouldNotBlockADifferentActionWhenSameKeyIsBlockedForAnotherAction() {
        String blockedKey = "login|1.2.3.4|user";
        String otherActionKey = "delete-account|1.2.3.4|user";

        recordFailure(blockedKey);
        recordFailure(blockedKey);
        recordFailure(blockedKey);

        assertThatThrownBy(() -> attemptLockout.checkAllowed(blockedKey)).isInstanceOf(TooManyRequestsException.class);
        assertThatCode(() -> attemptLockout.checkAllowed(otherActionKey)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("[cleanupExpired] Should Remove An Entry - When Its Window Has Elapsed And It Is Not Blocked")
    void shouldRemoveAnEntryWhenItsWindowHasElapsedAndItIsNotBlocked() {
        String key = "1.2.3.4|user";
        recordFailure(key);
        advanceClock(WINDOW.plusSeconds(1));

        attemptLockout.cleanupExpired();

        assertThat(attemptLockout.size()).isZero();
    }

    @Test
    @DisplayName("[cleanupExpired] Should Not Remove An Entry - When Its Window Is Still Open")
    void shouldNotRemoveAnEntryWhenItsWindowIsStillOpen() {
        String key = "1.2.3.4|user";
        recordFailure(key);

        attemptLockout.cleanupExpired();

        assertThat(attemptLockout.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("[cleanupExpired] Should Not Remove An Entry - When Its Block Outlasts The Window")
    void shouldNotRemoveAnEntryWhenItsBlockOutlastsTheWindow() {
        String key = "1.2.3.4|user";
        attemptLockout.recordFailure(key, 2, Duration.ofMinutes(1), Duration.ofMinutes(30));
        attemptLockout.recordFailure(key, 2, Duration.ofMinutes(1), Duration.ofMinutes(30));
        advanceClock(Duration.ofMinutes(2));

        attemptLockout.cleanupExpired();

        assertThat(attemptLockout.size()).isEqualTo(1);
    }
}
