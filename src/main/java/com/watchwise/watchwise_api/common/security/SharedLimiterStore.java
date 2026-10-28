package com.watchwise.watchwise_api.common.security;

import com.watchwise.watchwise_api.common.exception.ServiceUnavailableException;
import com.watchwise.watchwise_api.common.exception.TooManyRequestsException;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HexFormat;

/** Reservations and failures commit independently; success clearing joins the protected action transaction. */
@Component
public class SharedLimiterStore implements DisposableBean {
    private static final long LOCKOUT_CAPACITY_LOCK = 718473092614L;
    private static final int CLEANUP_BATCH_SIZE = 256;
    private static final int MAX_CLEANUP_BATCHES = 16;
    private final HikariDataSource dataSource;
    private final SecretKeySpec digestKey;
    private final JdbcTemplate successfulActionJdbc;

    public SharedLimiterStore(DataSource applicationDataSource, @Value("${app.jwt.secret}") String secret) {
        if (!(applicationDataSource instanceof HikariDataSource hikari)) {
            throw new IllegalArgumentException("Shared limiter requires the configured Hikari datasource");
        }
        this.successfulActionJdbc = new JdbcTemplate(applicationDataSource);
        this.successfulActionJdbc.setQueryTimeout(2);
        HikariConfig configuration = new HikariConfig();
        configuration.setJdbcUrl(hikari.getJdbcUrl());
        configuration.setUsername(hikari.getUsername());
        configuration.setPassword(hikari.getPassword());
        configuration.setDataSourceProperties(hikari.getDataSourceProperties());
        if (hikari.getDriverClassName() != null) {
            configuration.setDriverClassName(hikari.getDriverClassName());
        }
        configuration.setConnectionInitSql(hikari.getConnectionInitSql());
        configuration.setSchema(hikari.getSchema());
        configuration.setCatalog(hikari.getCatalog());
        configuration.setMaximumPoolSize(2);
        configuration.setMinimumIdle(0);
        configuration.setConnectionTimeout(1500);
        configuration.setValidationTimeout(1000);
        configuration.setInitializationFailTimeout(-1);
        configuration.setReadOnly(false);
        configuration.setAutoCommit(false);
        configuration.addDataSourceProperty("connectTimeout", "2");
        configuration.addDataSourceProperty("socketTimeout", "5");
        this.dataSource = new HikariDataSource(configuration);
        this.digestKey = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    public boolean reserve(String kind, String key, int maximum, Duration window) {
        String digest = digest(kind, key);
        return transaction(connection -> {
            if ("lockout".equals(kind) && !lockExisting(connection, digest)) {
                lockCapacity(connection);
                cleanup(connection, kind);
                try (PreparedStatement capacity = connection.prepareStatement("""
                        SELECT NOT EXISTS (
                            SELECT 1 FROM shared_limiter_state WHERE kind = 'lockout' AND key_digest = ?
                        ) AND (SELECT COUNT(*) FROM shared_limiter_state WHERE kind = 'lockout') >= ?
                        """)) {
                    capacity.setString(1, digest);
                    capacity.setInt(2, AttemptLockout.MAX_TRACKED_KEYS);
                    try (ResultSet result = capacity.executeQuery()) {
                        result.next();
                        if (result.getBoolean(1)) {
                            throw new TooManyRequestsException(
                                    "Too many active attempts are being tracked. Wait for an attempt window to expire and try again.");
                        }
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO shared_limiter_state AS state (kind, key_digest, request_count, window_end)
                    VALUES (?, ?, 1, CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'))
                    ON CONFLICT (kind, key_digest) DO UPDATE SET
                        request_count = CASE WHEN state.window_end < CURRENT_TIMESTAMP OR state.blocked_until IS NOT NULL
                            THEN 1 ELSE state.request_count + 1 END,
                        window_end = CASE WHEN state.window_end < CURRENT_TIMESTAMP OR state.blocked_until IS NOT NULL
                            THEN EXCLUDED.window_end ELSE state.window_end END,
                        blocked_until = NULL
                    WHERE (state.blocked_until IS NULL OR state.blocked_until <= CURRENT_TIMESTAMP)
                        AND (state.window_end < CURRENT_TIMESTAMP OR state.blocked_until IS NOT NULL
                            OR state.request_count < ?)
                    RETURNING request_count
                    """)) {
                statement.setString(1, kind);
                statement.setString(2, digest);
                statement.setLong(3, window.toMillis());
                statement.setInt(4, maximum);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next();
                }
            }
        });
    }

    public void recordFailure(String key, int maximum, Duration blockDuration) {
        String digest = digest("lockout", key);
        transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE shared_limiter_state
                    SET blocked_until = CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond')
                    WHERE kind = 'lockout' AND key_digest = ? AND request_count >= ?
                    """)) {
                statement.setLong(1, blockDuration.toMillis());
                statement.setString(2, digest);
                statement.setInt(3, maximum);
                statement.executeUpdate();
            }
            return null;
        });
    }

    public void recordSuccess(String key) {
        String digest = digest("lockout", key);
        try {
            // JdbcTemplate joins the primary JPA transaction so a failed clear rolls back the account action.
            successfulActionJdbc.update(
                    "DELETE FROM shared_limiter_state WHERE kind = 'lockout' AND key_digest = ?", digest);
        } catch (DataAccessException exception) {
            throw new ServiceUnavailableException("Request protection is temporarily unavailable. Try again shortly.");
        }
    }

    public void cleanupExpired(String kind) {
        transaction(connection -> {
            if ("lockout".equals(kind)) {
                lockCapacity(connection);
            }
            for (int batch = 0; batch < MAX_CLEANUP_BATCHES; batch++) {
                if (cleanup(connection, kind) < CLEANUP_BATCH_SIZE) {
                    break;
                }
            }
            return null;
        });
    }

    private int cleanup(Connection connection, String kind) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM shared_limiter_state WHERE (kind, key_digest) IN (
                    SELECT kind, key_digest FROM shared_limiter_state
                    WHERE kind = ? AND window_end < CURRENT_TIMESTAMP
                        AND (blocked_until IS NULL OR blocked_until <= CURRENT_TIMESTAMP)
                    ORDER BY window_end LIMIT ? FOR UPDATE SKIP LOCKED
                )
                """)) {
            statement.setString(1, kind);
            statement.setInt(2, CLEANUP_BATCH_SIZE);
            return statement.executeUpdate();
        }
    }

    private void lockCapacity(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
            statement.setLong(1, LOCKOUT_CAPACITY_LOCK);
            statement.execute();
        }
    }

    private boolean lockExisting(Connection connection, String digest) throws SQLException {
        // Existing rows retain their capacity slot until deleted; only new keys need the global lock.
        // Counting retained rows also prevents late recordFailure calls from reviving keys past the cap.
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM shared_limiter_state WHERE kind = 'lockout' AND key_digest = ? FOR UPDATE
                """)) {
            statement.setString(1, digest);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private <T> T transaction(DatabaseOperation<T> operation) {
        // Deliberately bypass DataSourceUtils/JPA: this private pool never joins a caller's transaction.
        try (Connection connection = dataSource.getConnection()) {
            try {
                try (PreparedStatement timeout = connection.prepareStatement(
                        "SELECT set_config('statement_timeout', '2s', true), set_config('lock_timeout', '1s', true)")) {
                    timeout.execute();
                }
                T result = operation.execute(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                }
                throw exception;
            }
        } catch (SQLException exception) {
            throw new ServiceUnavailableException("Request protection is temporarily unavailable. Try again shortly.");
        }
    }

    private String digest(String kind, String rawKey) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(digestKey);
            return HexFormat.of().formatHex(mac.doFinal(("watchwise-limiter-v1|" + kind + "|" + rawKey)
                    .getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Cannot initialize limiter key digest", exception);
        }
    }

    @Override
    public void destroy() {
        dataSource.close();
    }

    @FunctionalInterface
    private interface DatabaseOperation<T> {
        T execute(Connection connection) throws SQLException;
    }
}
