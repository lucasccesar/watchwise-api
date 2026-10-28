package com.watchwise.watchwise_api.common.security;

import com.watchwise.watchwise_api.common.exception.TooManyRequestsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AttemptLockoutTest extends SharedLimiterPostgresSupport {

    private static final int MAX_ATTEMPTS = 3;
    private static final Duration WINDOW = Duration.ofMinutes(10);
    private static final Duration BLOCK_DURATION = Duration.ofMinutes(5);

    private AttemptLockout attemptLockout;

    @BeforeEach
    void setUp() {
        attemptLockout = new AttemptLockout(store);
    }

    @Test
    void independentlyConstructedInstancesMustShareLiveBlocks() {
        AttemptLockout otherReplica = new AttemptLockout(newStore());
        attemptLockout.checkAllowed("shared", 1, WINDOW);
        attemptLockout.recordFailure("shared", 1, BLOCK_DURATION);
        assertThatThrownBy(() -> otherReplica.checkAllowed("shared", 1, WINDOW))
                .isInstanceOf(TooManyRequestsException.class);
    }

    private void advanceClock(Duration duration) {
        elapse(duration);
    }

    private void checkAllowed(String key) {
        attemptLockout.checkAllowed(key, MAX_ATTEMPTS, WINDOW);
    }

    private void simulateFailedAttempt(String key) {
        attemptLockout.checkAllowed(key, MAX_ATTEMPTS, WINDOW);
        attemptLockout.recordFailure(key, MAX_ATTEMPTS, BLOCK_DURATION);
    }

    private void simulateSuccessfulAttempt(String key) {
        attemptLockout.checkAllowed(key, MAX_ATTEMPTS, WINDOW);
        attemptLockout.recordSuccess(key);
    }

    @Test
    @DisplayName("[checkAllowed] Should Not Throw - When No Prior Attempts Exist")
    void shouldNotThrowWhenNoPriorAttemptsExist() {
        assertThatCode(() -> checkAllowed("1.2.3.4|user"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("[checkAllowed] Should Reject New Keys At Capacity - Without Evicting Existing Attempts")
    void shouldRejectNewKeysAtCapacityWithoutEvictingExistingAttempts() {
        insertActiveLockouts(10_000);

        assertThatThrownBy(() -> attemptLockout.checkAllowed("new-user", MAX_ATTEMPTS, WINDOW))
                .isInstanceOf(TooManyRequestsException.class);
        assertThat(size("lockout")).isEqualTo(10_000);
    }

    @Test
    @DisplayName("[checkAllowed] Should Not Throw - When Failures Are Below The Max Attempts")
    void shouldNotThrowWhenFailuresAreBelowMaxAttempts() {
        String key = "1.2.3.4|user";

        simulateFailedAttempt(key);
        simulateFailedAttempt(key);

        assertThatCode(() -> checkAllowed(key))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("[checkAllowed] Should Throw TooManyRequestsException - When Failures Reach The Max Attempts")
    void shouldThrowTooManyRequestsExceptionWhenFailuresReachMaxAttempts() {
        String key = "1.2.3.4|user";

        simulateFailedAttempt(key);
        simulateFailedAttempt(key);
        simulateFailedAttempt(key);

        assertThatThrownBy(() -> checkAllowed(key))
                .isInstanceOf(TooManyRequestsException.class)
                .hasMessage("Too many attempts. Try again later.");
    }

    @Test
    @DisplayName("[checkAllowed] Should Throw TooManyRequestsException - When A Concurrent Reservation Would Exceed Max Attempts")
    void shouldThrowTooManyRequestsExceptionWhenAConcurrentReservationWouldExceedMaxAttempts() {
        String key = "1.2.3.4|user";

        // Two attempts reserve their slots before either one records its outcome,
        // simulating requests racing past the slow (e.g. password-hashing) step.
        attemptLockout.checkAllowed(key, 1, WINDOW);

        assertThatThrownBy(() -> attemptLockout.checkAllowed(key, 1, WINDOW))
                .isInstanceOf(TooManyRequestsException.class)
                .hasMessage("Too many attempts. Try again later.");
    }

    @Test
    @DisplayName("[checkAllowed] Should Throw TooManyRequestsException - When Max Attempts Is One And The First Failure Is Recorded")
    void shouldThrowTooManyRequestsExceptionWhenMaxAttemptsIsOneAndTheFirstFailureIsRecorded() {
        String key = "1.2.3.4|user";

        attemptLockout.checkAllowed(key, 1, WINDOW);
        attemptLockout.recordFailure(key, 1, BLOCK_DURATION);

        assertThatThrownBy(() -> attemptLockout.checkAllowed(key, 1, WINDOW))
                .isInstanceOf(TooManyRequestsException.class)
                .hasMessage("Too many attempts. Try again later.");
    }

    @Test
    @DisplayName("[checkAllowed] Should Not Throw - When Block Duration Has Elapsed")
    void shouldNotThrowWhenBlockDurationHasElapsed() {
        String key = "1.2.3.4|user";

        simulateFailedAttempt(key);
        simulateFailedAttempt(key);
        simulateFailedAttempt(key);
        assertThatThrownBy(() -> checkAllowed(key)).isInstanceOf(TooManyRequestsException.class);

        advanceClock(BLOCK_DURATION.plusSeconds(1));

        assertThatCode(() -> checkAllowed(key))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("[checkAllowed] Should Reset The Counter - When The Block Duration Has Already Elapsed")
    void shouldResetTheCounterWhenBlockDurationHasAlreadyElapsed() {
        String key = "1.2.3.4|user";

        simulateFailedAttempt(key);
        simulateFailedAttempt(key);
        simulateFailedAttempt(key);
        advanceClock(BLOCK_DURATION.plusSeconds(1));

        simulateFailedAttempt(key);

        assertThatCode(() -> checkAllowed(key))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("[checkAllowed] Should Reset The Counter - When The Window Has Naturally Expired")
    void shouldResetTheCounterWhenTheWindowHasNaturallyExpired() {
        String key = "1.2.3.4|user";

        simulateFailedAttempt(key);
        simulateFailedAttempt(key);
        advanceClock(WINDOW.plusSeconds(1));

        simulateFailedAttempt(key);
        simulateFailedAttempt(key);

        assertThatCode(() -> checkAllowed(key))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("[recordSuccess] Should Clear Prior Failures - When Called")
    void shouldClearPriorFailuresWhenRecordSuccessCalled() {
        String key = "1.2.3.4|user";

        simulateFailedAttempt(key);
        simulateFailedAttempt(key);
        simulateSuccessfulAttempt(key);
        simulateFailedAttempt(key);
        simulateFailedAttempt(key);

        assertThatCode(() -> checkAllowed(key))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("[checkAllowed] Should Not Block A Different Identifier - When Another Identifier Is Blocked")
    void shouldNotBlockADifferentIdentifierWhenAnotherIdentifierIsBlocked() {
        String blockedKey = "1.2.3.4|userA";
        String otherKey = "1.2.3.4|userB";

        simulateFailedAttempt(blockedKey);
        simulateFailedAttempt(blockedKey);
        simulateFailedAttempt(blockedKey);

        assertThatThrownBy(() -> checkAllowed(blockedKey)).isInstanceOf(TooManyRequestsException.class);
        assertThatCode(() -> checkAllowed(otherKey)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("[checkAllowed] Should Not Block A Different Ip - When Another Ip Is Blocked")
    void shouldNotBlockADifferentIpWhenAnotherIpIsBlocked() {
        String blockedKey = "1.2.3.4|user";
        String otherKey = "5.6.7.8|user";

        simulateFailedAttempt(blockedKey);
        simulateFailedAttempt(blockedKey);
        simulateFailedAttempt(blockedKey);

        assertThatThrownBy(() -> checkAllowed(blockedKey)).isInstanceOf(TooManyRequestsException.class);
        assertThatCode(() -> checkAllowed(otherKey)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("[checkAllowed] Should Not Block A Different Action - When Same Key Is Blocked For Another Action")
    void shouldNotBlockADifferentActionWhenSameKeyIsBlockedForAnotherAction() {
        String blockedKey = "login|1.2.3.4|user";
        String otherActionKey = "delete-account|1.2.3.4|user";

        simulateFailedAttempt(blockedKey);
        simulateFailedAttempt(blockedKey);
        simulateFailedAttempt(blockedKey);

        assertThatThrownBy(() -> checkAllowed(blockedKey)).isInstanceOf(TooManyRequestsException.class);
        assertThatCode(() -> checkAllowed(otherActionKey)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("[cleanupExpired] Should Remove An Entry - When Its Window Has Elapsed And It Is Not Blocked")
    void shouldRemoveAnEntryWhenItsWindowHasElapsedAndItIsNotBlocked() {
        String key = "1.2.3.4|user";
        simulateFailedAttempt(key);
        advanceClock(WINDOW.plusSeconds(1));

        attemptLockout.cleanupExpired();

        assertThat(size("lockout")).isZero();
    }

    @Test
    @DisplayName("[cleanupExpired] Should Not Remove An Entry - When Its Window Is Still Open")
    void shouldNotRemoveAnEntryWhenItsWindowIsStillOpen() {
        String key = "1.2.3.4|user";
        simulateFailedAttempt(key);

        attemptLockout.cleanupExpired();

        assertThat(size("lockout")).isEqualTo(1);
    }

    @Test
    @DisplayName("[cleanupExpired] Should Not Remove An Entry - When Its Block Outlasts The Window")
    void shouldNotRemoveAnEntryWhenItsBlockOutlastsTheWindow() {
        String key = "1.2.3.4|user";
        Duration shortWindow = Duration.ofMinutes(1);
        Duration longBlock = Duration.ofMinutes(30);

        attemptLockout.checkAllowed(key, 2, shortWindow);
        attemptLockout.recordFailure(key, 2, longBlock);
        attemptLockout.checkAllowed(key, 2, shortWindow);
        attemptLockout.recordFailure(key, 2, longBlock);
        advanceClock(Duration.ofMinutes(2));

        attemptLockout.cleanupExpired();

        assertThat(size("lockout")).isEqualTo(1);
    }
}
