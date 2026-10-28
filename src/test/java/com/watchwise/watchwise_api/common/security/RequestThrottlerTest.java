package com.watchwise.watchwise_api.common.security;

import com.watchwise.watchwise_api.common.exception.TooManyRequestsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestThrottlerTest extends SharedLimiterPostgresSupport {

    private static final int MAX_REQUESTS = 3;
    private static final Duration WINDOW = Duration.ofMinutes(10);

    private RequestThrottler requestThrottler;

    @BeforeEach
    void setUp() {
        requestThrottler = new RequestThrottler(store);
    }

    @Test
    void independentlyConstructedInstancesMustShareTheRequestBudget() {
        RequestThrottler otherReplica = new RequestThrottler(newStore());
        requestThrottler.checkAllowed("shared", 1, WINDOW);
        assertThatThrownBy(() -> otherReplica.checkAllowed("shared", 1, WINDOW))
                .isInstanceOf(TooManyRequestsException.class);
    }

    private void advanceClock(Duration duration) {
        elapse(duration);
    }

    @Test
    @DisplayName("[checkAllowed] Should Not Throw - When No Prior Requests Exist")
    void shouldNotThrowWhenNoPriorRequestsExist() {
        assertThatCode(() -> requestThrottler.checkAllowed("register:1.2.3.4", MAX_REQUESTS, WINDOW))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("[checkAllowed] Should Not Throw - When Requests Are Below The Max")
    void shouldNotThrowWhenRequestsAreBelowMax() {
        String key = "register:1.2.3.4";

        requestThrottler.checkAllowed(key, MAX_REQUESTS, WINDOW);
        requestThrottler.checkAllowed(key, MAX_REQUESTS, WINDOW);

        assertThatCode(() -> requestThrottler.checkAllowed(key, MAX_REQUESTS, WINDOW))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("[checkAllowed] Should Throw TooManyRequestsException - When Requests Exceed The Max")
    void shouldThrowTooManyRequestsExceptionWhenRequestsExceedTheMax() {
        String key = "register:1.2.3.4";

        requestThrottler.checkAllowed(key, MAX_REQUESTS, WINDOW);
        requestThrottler.checkAllowed(key, MAX_REQUESTS, WINDOW);
        requestThrottler.checkAllowed(key, MAX_REQUESTS, WINDOW);

        assertThatThrownBy(() -> requestThrottler.checkAllowed(key, MAX_REQUESTS, WINDOW))
                .isInstanceOf(TooManyRequestsException.class)
                .hasMessage("Too many requests. Try again later.");
    }

    @Test
    @DisplayName("[checkAllowed] Should Not Keep Incrementing - When Already Over The Max")
    void shouldNotKeepIncrementingWhenAlreadyOverTheMax() {
        String key = "register:1.2.3.4";

        requestThrottler.checkAllowed(key, MAX_REQUESTS, WINDOW);
        requestThrottler.checkAllowed(key, MAX_REQUESTS, WINDOW);
        requestThrottler.checkAllowed(key, MAX_REQUESTS, WINDOW);
        assertThatThrownBy(() -> requestThrottler.checkAllowed(key, MAX_REQUESTS, WINDOW))
                .isInstanceOf(TooManyRequestsException.class);
        assertThatThrownBy(() -> requestThrottler.checkAllowed(key, MAX_REQUESTS, WINDOW))
                .isInstanceOf(TooManyRequestsException.class);

        advanceClock(WINDOW.plusSeconds(1));

        assertThatCode(() -> requestThrottler.checkAllowed(key, MAX_REQUESTS, WINDOW))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("[checkAllowed] Should Reset The Counter - When The Window Has Naturally Expired")
    void shouldResetTheCounterWhenTheWindowHasNaturallyExpired() {
        String key = "register:1.2.3.4";

        requestThrottler.checkAllowed(key, MAX_REQUESTS, WINDOW);
        requestThrottler.checkAllowed(key, MAX_REQUESTS, WINDOW);
        requestThrottler.checkAllowed(key, MAX_REQUESTS, WINDOW);
        advanceClock(WINDOW.plusSeconds(1));

        assertThatCode(() -> requestThrottler.checkAllowed(key, MAX_REQUESTS, WINDOW))
                .doesNotThrowAnyException();
        assertThatCode(() -> requestThrottler.checkAllowed(key, MAX_REQUESTS, WINDOW))
                .doesNotThrowAnyException();
        assertThatCode(() -> requestThrottler.checkAllowed(key, MAX_REQUESTS, WINDOW))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> requestThrottler.checkAllowed(key, MAX_REQUESTS, WINDOW))
                .isInstanceOf(TooManyRequestsException.class);
    }

    @Test
    @DisplayName("[checkAllowed] Should Not Block A Different Key - When Another Key Is Blocked")
    void shouldNotBlockADifferentKeyWhenAnotherKeyIsBlocked() {
        String blockedKey = "register:1.2.3.4";
        String otherKey = "register:5.6.7.8";

        requestThrottler.checkAllowed(blockedKey, MAX_REQUESTS, WINDOW);
        requestThrottler.checkAllowed(blockedKey, MAX_REQUESTS, WINDOW);
        requestThrottler.checkAllowed(blockedKey, MAX_REQUESTS, WINDOW);

        assertThatThrownBy(() -> requestThrottler.checkAllowed(blockedKey, MAX_REQUESTS, WINDOW))
                .isInstanceOf(TooManyRequestsException.class);
        assertThatCode(() -> requestThrottler.checkAllowed(otherKey, MAX_REQUESTS, WINDOW))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("[checkAllowed] Should Not Block A Different Action - When Same Ip Is Blocked For Another Action")
    void shouldNotBlockADifferentActionWhenSameIpIsBlockedForAnotherAction() {
        String blockedKey = "register:1.2.3.4";
        String otherActionKey = "oauth:1.2.3.4";

        requestThrottler.checkAllowed(blockedKey, MAX_REQUESTS, WINDOW);
        requestThrottler.checkAllowed(blockedKey, MAX_REQUESTS, WINDOW);
        requestThrottler.checkAllowed(blockedKey, MAX_REQUESTS, WINDOW);

        assertThatThrownBy(() -> requestThrottler.checkAllowed(blockedKey, MAX_REQUESTS, WINDOW))
                .isInstanceOf(TooManyRequestsException.class);
        assertThatCode(() -> requestThrottler.checkAllowed(otherActionKey, MAX_REQUESTS, WINDOW))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("[reset] Should Clear Every Tracked Key - When Called")
    void shouldClearEveryTrackedKeyWhenCalled() {
        String key = "register:1.2.3.4";

        requestThrottler.checkAllowed(key, MAX_REQUESTS, WINDOW);
        requestThrottler.checkAllowed(key, MAX_REQUESTS, WINDOW);
        requestThrottler.checkAllowed(key, MAX_REQUESTS, WINDOW);
        assertThatThrownBy(() -> requestThrottler.checkAllowed(key, MAX_REQUESTS, WINDOW))
                .isInstanceOf(TooManyRequestsException.class);

        jdbc.update("DELETE FROM shared_limiter_state WHERE kind = 'request'");

        assertThatCode(() -> requestThrottler.checkAllowed(key, MAX_REQUESTS, WINDOW))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("[cleanupExpired] Should Remove An Entry - When Its Window Has Elapsed")
    void shouldRemoveAnEntryWhenItsWindowHasElapsed() {
        String key = "register:1.2.3.4";
        requestThrottler.checkAllowed(key, MAX_REQUESTS, WINDOW);
        advanceClock(WINDOW.plusSeconds(1));

        requestThrottler.cleanupExpired();

        assertThat(size("request")).isZero();
    }

    @Test
    @DisplayName("[cleanupExpired] Should Not Remove An Entry - When Its Window Is Still Open")
    void shouldNotRemoveAnEntryWhenItsWindowIsStillOpen() {
        String key = "register:1.2.3.4";
        requestThrottler.checkAllowed(key, MAX_REQUESTS, WINDOW);

        requestThrottler.cleanupExpired();

        assertThat(size("request")).isEqualTo(1);
    }
}
