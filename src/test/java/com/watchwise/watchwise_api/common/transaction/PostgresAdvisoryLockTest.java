package com.watchwise.watchwise_api.common.transaction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(PostgresAdvisoryLock.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PostgresAdvisoryLockTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private AdvisoryLock advisoryLock;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("[lock] Should Serialize Transactions - When They Use The Same Identity")
    void shouldSerializeTransactionsWhenTheyUseTheSameIdentity() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch firstAcquired = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondReady = new CountDownLatch(1);
        CountDownLatch secondAcquired = new CountDownLatch(1);
        AtomicLong firstBackendPid = new AtomicLong();
        AtomicLong secondBackendPid = new AtomicLong();

        try {
            Future<?> first = executor.submit(() -> inTransaction(() -> {
                firstBackendPid.set(currentBackendPid());
                advisoryLock.lock("same-identity");
                firstAcquired.countDown();
                await(releaseFirst);
            }));
            assertThat(firstAcquired.await(5, TimeUnit.SECONDS)).isTrue();

            Future<?> second = executor.submit(() -> inTransaction(() -> {
                secondBackendPid.set(currentBackendPid());
                secondReady.countDown();
                advisoryLock.lock("same-identity");
                secondAcquired.countDown();
            }));

            assertThat(secondReady.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(awaitWaitingForAdvisoryLock(secondBackendPid.get())).isTrue();
            assertThat(firstBackendPid.get()).isNotEqualTo(secondBackendPid.get());

            releaseFirst.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);

            assertThat(secondAcquired.getCount()).isZero();
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    @DisplayName("[lock] Should Not Serialize Transactions - When They Use Different Identities")
    void shouldNotSerializeTransactionsWhenTheyUseDifferentIdentities() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch firstAcquired = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondReady = new CountDownLatch(1);
        CountDownLatch secondAcquired = new CountDownLatch(1);
        AtomicLong firstBackendPid = new AtomicLong();
        AtomicLong secondBackendPid = new AtomicLong();

        try {
            Future<?> first = executor.submit(() -> inTransaction(() -> {
                firstBackendPid.set(currentBackendPid());
                advisoryLock.lock("first-identity");
                firstAcquired.countDown();
                await(releaseFirst);
            }));
            assertThat(firstAcquired.await(5, TimeUnit.SECONDS)).isTrue();

            Future<?> second = executor.submit(() -> inTransaction(() -> {
                secondBackendPid.set(currentBackendPid());
                secondReady.countDown();
                advisoryLock.lock("second-identity");
                secondAcquired.countDown();
            }));

            assertThat(secondReady.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(secondAcquired.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(firstBackendPid.get()).isNotEqualTo(secondBackendPid.get());

            releaseFirst.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void inTransaction(Runnable action) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> action.run());
    }

    private long currentBackendPid() {
        return jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Long.class);
    }

    private boolean awaitWaitingForAdvisoryLock(long backendPid) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        while (Instant.now().isBefore(deadline)) {
            Boolean waiting = jdbcTemplate.queryForObject("""
                    SELECT EXISTS (
                        SELECT 1
                        FROM pg_locks
                        WHERE pid = ?
                          AND locktype = 'advisory'
                          AND granted = false
                    )
                    """, Boolean.class, backendPid);
            if (Boolean.TRUE.equals(waiting)) {
                return true;
            }
            Thread.sleep(10);
        }
        return false;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for the transaction release");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }
}
