package com.watchwise.watchwise_api.common.idempotency;

import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.common.transaction.PostgresAdvisoryLock;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({UserActionExecutorImpl.class, PostgresAdvisoryLock.class})
class UserActionExecutorIntegrationTest {
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }
    @Autowired UserActionExecutor executor;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    @Test void replaysSameScopedResultAndRejectsChangedPayload() {
        UUID owner = user();
        AtomicInteger executions = new AtomicInteger();
        UUID review = UUID.randomUUID();
        UserActionExecutor.ActionResult expected = new UserActionExecutor.ActionResult(List.of(review), 3);
        TransactionTemplate tx = new TransactionTemplate(transactions);
        UserActionExecutor.ActionResult first = tx.execute(s -> executor.execute(owner, UserActionOperation.CONTENT_REVIEW_PUBLICATION, "key", "hash", () -> {
            executions.incrementAndGet(); return expected;
        }));
        assertThat(first).isEqualTo(expected);
        UserActionExecutor.ActionResult replay = tx.execute(s -> executor.execute(owner, UserActionOperation.CONTENT_REVIEW_PUBLICATION, "key", "hash", () -> {
            executions.incrementAndGet(); return new UserActionExecutor.ActionResult(List.of(UUID.randomUUID()), 4);
        }));
        assertThat(replay).isEqualTo(expected);
        assertThat(executions).hasValue(1);
        assertThatThrownBy(() -> tx.execute(s -> executor.execute(owner, UserActionOperation.CONTENT_REVIEW_PUBLICATION, "key", "different", () -> expected)))
                .isInstanceOf(ConflictException.class);
        assertThat(jdbc.queryForObject("select count(*) from user_action_receipts where user_id = ?", Integer.class, owner)).isEqualTo(1);
    }

    @Test void rollbackDoesNotPublishReceipt() {
        UUID owner = user();
        TransactionTemplate tx = new TransactionTemplate(transactions);
        assertThatThrownBy(() -> tx.execute(s -> executor.execute(owner, UserActionOperation.DIARY_BULK, "rollback", "hash", () -> {
            throw new IllegalStateException("action failed");
        }))).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select count(*) from user_action_receipts where user_id = ?", Integer.class, owner)).isZero();
    }

    private UUID user() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users(id, username, email, password, profile_picture, name) values (?, ?, ?, 'hash', 'picture', 'Test')", id, id.toString(), id + "@example.com");
        return id;
    }
}
