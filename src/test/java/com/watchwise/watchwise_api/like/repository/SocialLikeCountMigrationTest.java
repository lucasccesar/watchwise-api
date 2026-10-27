package com.watchwise.watchwise_api.like.repository;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.sql.Connection;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class SocialLikeCountMigrationTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    DriverManagerDataSource dataSource;
    JdbcTemplate jdbc;
    AccountLikeFixture fixture;

    @BeforeEach
    void migrateToPreviousVersion() {
        dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP SCHEMA public CASCADE");
        jdbc.execute("CREATE SCHEMA public");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("86").load().migrate();
        fixture = new AccountLikeFixture(jdbc);
        fixture.seed();
    }

    @Test
    void repairsAllSixKindsIncludingZeroCountsAndReapplyingSqlIsIdempotent() throws Exception {
        fixture.targets.forEach(target -> jdbc.update("UPDATE " + target.table() + " SET likes_count = 99 WHERE id = ?", target.id()));
        UUID emptyList = UUID.randomUUID();
        jdbc.update("INSERT INTO user_lists(id,user_id,name,visibility,rank,likes_count) VALUES (?,?,'No likes','PUBLIC',2,17)",
                emptyList, fixture.owner);

        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("87").load().migrate();

        fixture.assertCounts(2);
        assertThat(jdbc.queryForObject("SELECT likes_count FROM user_lists WHERE id = ?", Integer.class, emptyList)).isZero();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V87__reconcile-social-like-counts.sql"));
            connection.commit();
        }
        fixture.assertCounts(2);
        assertThat(jdbc.queryForObject("SELECT likes_count FROM user_lists WHERE id = ?", Integer.class, emptyList)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM likes", Long.class)).isEqualTo(12);
    }

    @Test
    void waitsForInflightLikeBeforeTakingRepairSnapshot() throws Exception {
        var target = fixture.targets.getFirst();
        String migrationUrl = postgres.getJdbcUrl() + (postgres.getJdbcUrl().contains("?") ? "&" : "?")
                + "ApplicationName=task7-v87";
        var migrationSource = new DriverManagerDataSource(migrationUrl, postgres.getUsername(), postgres.getPassword());
        try (Connection writer = dataSource.getConnection(); ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            writer.setAutoCommit(false);
            try (var update = writer.prepareStatement("UPDATE user_lists SET likes_count = likes_count + 1 WHERE id = ?");
                 var insert = writer.prepareStatement("INSERT INTO likes(id,user_id,list_id) VALUES (?,?,?)")) {
                update.setObject(1, target.id());
                update.executeUpdate();
                insert.setObject(1, UUID.randomUUID());
                insert.setObject(2, fixture.owner);
                insert.setObject(3, target.id());
                insert.executeUpdate();
            }
            Future<?> migrating = pool.submit(() -> Flyway.configure().dataSource(migrationSource)
                    .locations("classpath:db/migration").target("87").load().migrate());
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                boolean blocked = false;
                while (System.nanoTime() < deadline) {
                    blocked = jdbc.queryForObject("SELECT COUNT(*) FROM pg_stat_activity WHERE application_name = 'task7-v87' AND wait_event_type = 'Lock'", Integer.class) > 0;
                    if (blocked) break;
                    Thread.onSpinWait();
                }
                assertThat(blocked).as("migration must wait behind the in-flight like").isTrue();
                writer.commit();
                migrating.get(20, TimeUnit.SECONDS);
            } finally {
                writer.rollback();
            }
        }
        assertThat(jdbc.queryForObject("SELECT likes_count FROM user_lists WHERE id = ?", Integer.class, target.id())).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM likes WHERE list_id = ?", Long.class, target.id())).isEqualTo(3);
    }
}
