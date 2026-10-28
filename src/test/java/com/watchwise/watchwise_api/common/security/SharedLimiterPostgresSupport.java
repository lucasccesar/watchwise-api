package com.watchwise.watchwise_api.common.security;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Testcontainers
abstract class SharedLimiterPostgresSupport {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withImagePullPolicy(image -> false);
    private final List<SharedLimiterStore> stores = new ArrayList<>();
    HikariDataSource applicationDataSource;
    JdbcTemplate jdbc;
    SharedLimiterStore store;

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").load().migrate();
    }

    @BeforeEach
    void initializeDatabase() {
        HikariConfig configuration = new HikariConfig();
        configuration.setJdbcUrl(postgres.getJdbcUrl());
        configuration.setUsername(postgres.getUsername());
        configuration.setPassword(postgres.getPassword());
        configuration.setMaximumPoolSize(1);
        configuration.setMinimumIdle(0);
        applicationDataSource = new HikariDataSource(configuration);
        jdbc = new JdbcTemplate(applicationDataSource);
        jdbc.execute("TRUNCATE shared_limiter_state");
        store = newStore();
    }

    SharedLimiterStore newStore() {
        SharedLimiterStore created = new SharedLimiterStore(applicationDataSource,
                "local-test-only-shared-secret-for-limiter-digests");
        stores.add(created);
        return created;
    }

    @AfterEach
    void closePools() {
        stores.forEach(SharedLimiterStore::destroy);
        applicationDataSource.close();
    }

    void elapse(Duration duration) {
        jdbc.update("""
                UPDATE shared_limiter_state
                SET window_end = window_end - (? * INTERVAL '1 millisecond'),
                    blocked_until = blocked_until - (? * INTERVAL '1 millisecond')
                """, duration.toMillis(), duration.toMillis());
    }

    int size(String kind) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM shared_limiter_state WHERE kind = ?", Integer.class, kind);
    }

    void insertActiveLockouts(int count) {
        jdbc.update("""
                INSERT INTO shared_limiter_state (kind, key_digest, request_count, window_end)
                SELECT 'lockout', lpad(to_hex(value), 64, '0'), 1, CURRENT_TIMESTAMP + INTERVAL '1 hour'
                FROM generate_series(1, ?) AS value
                """, count);
    }
}
