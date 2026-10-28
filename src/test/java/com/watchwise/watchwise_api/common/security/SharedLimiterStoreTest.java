package com.watchwise.watchwise_api.common.security;

import com.watchwise.watchwise_api.common.exception.ServiceUnavailableException;
import com.watchwise.watchwise_api.common.exception.TooManyRequestsException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SharedLimiterStoreTest extends SharedLimiterPostgresSupport {
    private static final Duration WINDOW = Duration.ofMinutes(10);

    @Test
    void concurrentReplicasCannotAdmitMoreRequestsThanTheSharedBudget() throws Exception {
        RequestThrottler first = new RequestThrottler(store);
        RequestThrottler second = new RequestThrottler(newStore());
        List<Callable<Boolean>> requests = new ArrayList<>();
        for (int index = 0; index < 24; index++) {
            RequestThrottler replica = index % 2 == 0 ? first : second;
            requests.add(() -> admit(() -> replica.checkAllowed("concurrent", 3, WINDOW)));
        }
        assertThat(concurrently(requests)).filteredOn(Boolean::booleanValue).hasSize(3);
        assertThat(jdbc.queryForObject("SELECT request_count FROM shared_limiter_state", Integer.class)).isEqualTo(3);
        assertThat(size("request")).isOne();
    }

    @Test
    void concurrentLockoutReservationsCannotOverrunTheBudgetAndSuccessClearsBothReplicas() throws Exception {
        AttemptLockout first = new AttemptLockout(store);
        AttemptLockout second = new AttemptLockout(newStore());
        List<Callable<Boolean>> attempts = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            AttemptLockout replica = index % 2 == 0 ? first : second;
            attempts.add(() -> admit(() -> replica.checkAllowed("login", 3, WINDOW)));
        }
        assertThat(concurrently(attempts)).filteredOn(Boolean::booleanValue).hasSize(3);
        first.recordFailure("login", 3, WINDOW);
        assertThatThrownBy(() -> second.checkAllowed("login", 3, WINDOW)).isInstanceOf(TooManyRequestsException.class);
        second.recordSuccess("login");
        first.checkAllowed("login", 3, WINDOW);
        assertThat(jdbc.queryForObject("SELECT request_count FROM shared_limiter_state", Integer.class)).isOne();
    }

    @Test
    void theGlobalCapacityNeverEvictsALiveBlockEvenAfterItsWindowExpired() {
        AttemptLockout first = new AttemptLockout(store);
        AttemptLockout second = new AttemptLockout(newStore());
        first.checkAllowed("live-block", 1, Duration.ofMinutes(1));
        first.recordFailure("live-block", 1, Duration.ofHours(3));
        insertActiveLockouts(9999);
        elapse(Duration.ofMinutes(2));
        assertThatThrownBy(() -> second.checkAllowed("new-key", 1, WINDOW))
                .isInstanceOf(TooManyRequestsException.class).hasMessageContaining("Wait");
        second.cleanupExpired();
        assertThat(size("lockout")).isEqualTo(10_000);
        assertThatThrownBy(() -> second.checkAllowed("live-block", 1, WINDOW))
                .isInstanceOf(TooManyRequestsException.class);
    }

    @Test
    void simultaneousNewKeysCannotRacePastTheGlobalCapacity() throws Exception {
        insertActiveLockouts(9999);
        AttemptLockout first = new AttemptLockout(store);
        AttemptLockout second = new AttemptLockout(newStore());
        assertThat(concurrently(List.of(
                () -> admit(() -> first.checkAllowed("first", 1, WINDOW)),
                () -> admit(() -> second.checkAllowed("second", 1, WINDOW)))))
                .containsExactlyInAnyOrder(true, false);
        assertThat(size("lockout")).isEqualTo(10_000);
    }

    @Test
    void existingReservationsAndOutcomesDoNotWaitForTheGlobalCapacityLock() throws Exception {
        AttemptLockout lockout = new AttemptLockout(store);
        lockout.checkAllowed("existing", 3, WINDOW);
        try (var connection = postgres.createConnection("")) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("SELECT pg_advisory_xact_lock(718473092614)")) {
                statement.execute();
            }
            lockout.checkAllowed("existing", 3, WINDOW);
            lockout.recordFailure("existing", 2, WINDOW);
            lockout.recordSuccess("existing");
            connection.rollback();
        }
        assertThat(size("lockout")).isZero();
    }

    @Test
    void expiredRowsAreReclaimedWithoutCountingThemAgainstActiveCapacity() {
        insertActiveLockouts(10_000);
        elapse(Duration.ofHours(2));
        new AttemptLockout(store).checkAllowed("fresh", 1, WINDOW);
        assertThat(size("lockout")).isEqualTo(9745);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM shared_limiter_state
                WHERE window_end >= CURRENT_TIMESTAMP OR blocked_until > CURRENT_TIMESTAMP
                """, Integer.class)).isOne();
    }

    @Test
    void reservationsAndFailuresSurviveBusinessRollbackWhileSuccessClearRollsBack() {
        RequestThrottler requests = new RequestThrottler(store);
        AttemptLockout lockout = new AttemptLockout(store);
        TransactionTemplate business = new TransactionTemplate(new DataSourceTransactionManager(applicationDataSource));
        business.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT 1", Integer.class); // Holds the main pool's only connection.
            requests.checkAllowed("business", 1, WINDOW);
            lockout.checkAllowed("failure", 1, WINDOW);
            lockout.recordFailure("failure", 1, WINDOW);
            lockout.checkAllowed("success", 1, WINDOW);
            lockout.recordSuccess("success");
            status.setRollbackOnly();
        });
        RequestThrottler other = new RequestThrottler(newStore());
        assertThatThrownBy(() -> other.checkAllowed("business", 1, WINDOW)).isInstanceOf(TooManyRequestsException.class);
        assertThatThrownBy(() -> lockout.checkAllowed("failure", 1, WINDOW)).isInstanceOf(TooManyRequestsException.class);
        assertThatThrownBy(() -> lockout.checkAllowed("success", 1, WINDOW)).isInstanceOf(TooManyRequestsException.class);
        lockout.recordSuccess("success");
        lockout.checkAllowed("success", 1, WINDOW);
    }

    @Test
    void independentStoreFailureFailsClosedForReservationsAndFailuresAs503() {
        store.destroy();
        RequestThrottler requests = new RequestThrottler(store);
        AttemptLockout lockout = new AttemptLockout(store);
        assertThatThrownBy(() -> requests.checkAllowed("raw-ip", 1, WINDOW)).isInstanceOf(ServiceUnavailableException.class);
        assertThatThrownBy(() -> lockout.checkAllowed("raw-email", 1, WINDOW)).isInstanceOf(ServiceUnavailableException.class);
        assertThatThrownBy(() -> lockout.recordFailure("raw-email", 1, WINDOW)).isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void primaryStoreFailureFailsClosedWhenClearingSuccessfulAttempts() {
        applicationDataSource.close();
        assertThatThrownBy(() -> new AttemptLockout(store).recordSuccess("raw-email"))
                .isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void databaseLockTimeoutFailsClosedWithoutIncrementingTheCounter() throws Exception {
        RequestThrottler requests = new RequestThrottler(store);
        requests.checkAllowed("contended", 3, WINDOW);
        try (var connection = applicationDataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("SELECT * FROM shared_limiter_state FOR UPDATE")) {
                statement.executeQuery().close();
            }
            assertThatThrownBy(() -> requests.checkAllowed("contended", 3, WINDOW))
                    .isInstanceOf(ServiceUnavailableException.class);
            connection.rollback();
        }
        assertThat(jdbc.queryForObject("SELECT request_count FROM shared_limiter_state", Integer.class)).isOne();
    }

    @Test
    void arbitraryProgrammingFailuresAreNotDisguisedAsStoreUnavailability() {
        assertThatThrownBy(() -> new RequestThrottler(store).checkAllowed("key", 1, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void storedKeysAreKeyedDigestsAndChangingTheSecretChangesTheDigest() {
        String raw = "login|192.0.2.17|person@example.test";
        new RequestThrottler(store).checkAllowed(raw, 1, WINDOW);
        assertThat(jdbc.queryForObject("SELECT key_digest FROM shared_limiter_state", String.class))
                .matches("[0-9a-f]{64}").doesNotContain("login", "192.0.2.17", "person");
        SharedLimiterStore differentSecret = new SharedLimiterStore(applicationDataSource, "other-test-secret");
        try {
            new RequestThrottler(differentSecret).checkAllowed(raw, 1, WINDOW);
            assertThat(size("request")).isEqualTo(2);
        } finally {
            differentSecret.destroy();
        }
    }

    @Test
    void concurrentCleanupIsBoundedAndSkipsAnExpiredRowThatAnotherTransactionHolds() throws Exception {
        insertExpiredRequests(600);
        SharedLimiterStore other = newStore();
        try (var connection = applicationDataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("SELECT * FROM shared_limiter_state ORDER BY key_digest LIMIT 1 FOR UPDATE")) {
                statement.executeQuery().close();
            }
            concurrently(List.of(() -> { store.cleanupExpired("request"); return true; },
                    () -> { other.cleanupExpired("request"); return true; }));
            connection.rollback();
        }
        assertThat(size("request")).isOne();
        store.cleanupExpired("request");
        assertThat(size("request")).isZero();
    }

    @Test
    void cleanupDrainsMoreThanOneBatchOfExpiredRequests() {
        insertExpiredRequests(600);
        store.cleanupExpired("request");
        assertThat(size("request")).isZero();
    }

    @Test
    void cleanupStopsAtABoundedTotalEvenWhenMoreExpiredRowsRemain() {
        insertExpiredRequests(10_000);
        store.cleanupExpired("request");
        assertThat(size("request")).isEqualTo(5904);
    }

    @Test
    void migrationEnforcesUniquenessAndStoresTimezoneAwareExpiration() {
        new RequestThrottler(store).checkAllowed("key", 1, WINDOW);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO shared_limiter_state SELECT * FROM shared_limiter_state
                """)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForList("""
                SELECT data_type FROM information_schema.columns
                WHERE table_name = 'shared_limiter_state' AND column_name IN ('window_end', 'blocked_until')
                """, String.class)).containsExactly("timestamp with time zone", "timestamp with time zone");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version = '93' AND success",
                Integer.class)).isOne();
    }

    private boolean admit(Runnable request) {
        try {
            request.run();
            return true;
        } catch (TooManyRequestsException expected) {
            return false;
        }
    }

    private void insertExpiredRequests(int count) {
        jdbc.update("""
                INSERT INTO shared_limiter_state(kind, key_digest, request_count, window_end)
                SELECT 'request', lpad(to_hex(value), 64, '0'), 1, CURRENT_TIMESTAMP - INTERVAL '1 hour'
                FROM generate_series(1, ?) AS value
                """, count);
    }

    private List<Boolean> concurrently(List<Callable<Boolean>> operations) throws Exception {
        CountDownLatch ready = new CountDownLatch(operations.size());
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Boolean>> results = new ArrayList<>();
            for (Callable<Boolean> operation : operations) {
                results.add(executor.submit(() -> { ready.countDown(); start.await(); return operation.call(); }));
            }
            ready.await();
            start.countDown();
            List<Boolean> values = new ArrayList<>();
            for (Future<Boolean> result : results) {
                values.add(result.get());
            }
            return values;
        }
    }
}
