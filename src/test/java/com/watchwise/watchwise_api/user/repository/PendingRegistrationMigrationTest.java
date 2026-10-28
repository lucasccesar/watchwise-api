package com.watchwise.watchwise_api.user.repository;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class PendingRegistrationMigrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @BeforeEach
    void migrateSchemaToV91() throws SQLException {
        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA public CASCADE");
            statement.execute("CREATE SCHEMA public");
        }
        flywayAt("91").migrate();
    }

    @Test
    @DisplayName("[V92] Should Move Unverified Registration To Pending Storage And Preserve Active User Email Change")
    void shouldMoveUnverifiedRegistrationToPendingStorageAndPreserveActiveUserEmailChange() throws SQLException {
        UUID pendingUserId = UUID.randomUUID();
        UUID activeUserId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.of(2026, 10, 27, 12, 0);
        String confirmationHash = "a".repeat(64);
        String browserProofHash = "b".repeat(64);
        String emailChangeHash = "c".repeat(64);

        insertLegacyPendingUser(pendingUserId, confirmationHash, browserProofHash, now);
        insertActiveUserWithPendingEmail(activeUserId, emailChangeHash, now);

        flywayAt("92").migrate();

        try (Connection connection = openConnection()) {
            assertThat(userExists(connection, pendingUserId)).isFalse();
            assertThat(pendingRegistrationExists(connection, pendingUserId, confirmationHash, browserProofHash)).isTrue();
            assertThat(activeUserEmailChangeWasPreserved(connection, activeUserId, emailChangeHash)).isTrue();
            assertThat(legacyRegistrationColumnsRemain(connection)).isZero();
            assertThat(schemaVersion(connection)).isEqualTo("92");
        }
    }

    private void insertLegacyPendingUser(UUID id, String confirmationHash, String proofHash, LocalDateTime now)
            throws SQLException {
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO users (
                    id, username, name, email, password, profile_picture, is_profile_public,
                    is_email_verified, email_confirmation_token_hash, email_confirmation_expires_at,
                    email_confirmation_registration_hash, email_confirmation_registration_required,
                    created_at, updated_at
                ) VALUES (?, 'pendingmigrant', 'Pending Migrant', 'pending@example.com', 'password-hash',
                    '/profile.png', TRUE, FALSE, ?, ?, ?, TRUE, ?, ?)
                """)) {
            statement.setObject(1, id);
            statement.setString(2, confirmationHash);
            statement.setObject(3, now.plusMinutes(30));
            statement.setString(4, proofHash);
            statement.setObject(5, now);
            statement.setObject(6, now);
            statement.executeUpdate();
        }
    }

    private void insertActiveUserWithPendingEmail(UUID id, String confirmationHash, LocalDateTime now)
            throws SQLException {
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO users (
                    id, username, name, email, pending_email, password, profile_picture,
                    is_profile_public, is_email_verified, email_confirmation_token_hash,
                    email_confirmation_expires_at, created_at, updated_at
                ) VALUES (?, 'activeuser', 'Active User', 'active@example.com', 'new@example.com',
                    'active-password-hash', '/profile.png', TRUE, TRUE, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, id);
            statement.setString(2, confirmationHash);
            statement.setObject(3, now.plusMinutes(30));
            statement.setObject(4, now);
            statement.setObject(5, now);
            statement.executeUpdate();
        }
    }

    private boolean userExists(Connection connection, UUID id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM users WHERE id = ?")) {
            statement.setObject(1, id);
            try (ResultSet results = statement.executeQuery()) {
                return results.next();
            }
        }
    }

    private boolean pendingRegistrationExists(
            Connection connection, UUID id, String confirmationHash, String proofHash) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM pending_registrations
                WHERE id = ? AND username = 'pendingmigrant' AND email = 'pending@example.com'
                  AND password_hash = 'password-hash' AND confirmation_token_hash = ?
                  AND registration_proof_hash = ? AND expires_at IS NOT NULL
                """)) {
            statement.setObject(1, id);
            statement.setString(2, confirmationHash);
            statement.setString(3, proofHash);
            try (ResultSet results = statement.executeQuery()) {
                return results.next();
            }
        }
    }

    private boolean activeUserEmailChangeWasPreserved(Connection connection, UUID id, String confirmationHash)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM users
                WHERE id = ? AND email = 'active@example.com' AND pending_email = 'new@example.com'
                  AND is_email_verified IS TRUE AND email_confirmation_token_hash = ?
                """)) {
            statement.setObject(1, id);
            statement.setString(2, confirmationHash);
            try (ResultSet results = statement.executeQuery()) {
                return results.next();
            }
        }
    }

    private int legacyRegistrationColumnsRemain(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'users'
                  AND column_name IN ('email_confirmation_registration_hash',
                                      'email_confirmation_registration_required')
                """); ResultSet results = statement.executeQuery()) {
            results.next();
            return results.getInt(1);
        }
    }

    private String schemaVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet results = statement.executeQuery("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1")) {
            results.next();
            return results.getString(1);
        }
    }

    private Flyway flywayAt(String version) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion(version))
                .load();
    }

    private Flyway flywayLatest() {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load();
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }
}
