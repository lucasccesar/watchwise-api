package com.watchwise.watchwise_api.userlist.repository;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class UserListOrderRevisionMigrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @BeforeEach
    void migrateSchemaToVersion103() throws SQLException {
        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA public CASCADE");
            statement.execute("CREATE SCHEMA public");
        }
        flywayAt("103").migrate();
    }

    @Test
    void shouldAppendLegacyUnrankedListsUsingTheirExistingDisplayTieBreakers() throws SQLException {
        UUID ownerId = UUID.randomUUID();
        insertUser(ownerId, "legacylistorderowner");
        UUID rankedOne = insertList(ownerId, "Ranked one", 1, LocalDateTime.of(2020, 1, 1, 0, 0));
        UUID rankedTwo = insertList(ownerId, "Ranked two", 2, LocalDateTime.of(2020, 1, 2, 0, 0));
        UUID unrankedOld = UUID.fromString("10000000-0000-0000-0000-000000000001");
        UUID unrankedNewerLowId = UUID.fromString("20000000-0000-0000-0000-000000000001");
        UUID unrankedNewerHighId = UUID.fromString("20000000-0000-0000-0000-000000000002");
        LocalDateTime older = LocalDateTime.of(2024, 1, 1, 0, 0);
        LocalDateTime newer = LocalDateTime.of(2025, 1, 1, 0, 0);
        insertList(ownerId, "Unranked old", null, older, unrankedOld);
        insertList(ownerId, "Unranked newer low", null, newer, unrankedNewerLowId);
        insertList(ownerId, "Unranked newer high", null, newer, unrankedNewerHighId);

        flywayAt("104").migrate();

        assertThat(readRankedIds(ownerId)).containsExactly(
                rankedOne, rankedTwo, unrankedNewerHighId, unrankedNewerLowId, unrankedOld);
        assertThat(readRanks(ownerId)).containsExactly(1, 2, 3, 4, 5);
    }

    private Flyway flywayAt(String version) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion(version))
                .load();
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private void insertUser(UUID userId, String username) throws SQLException {
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO users (id, username, email, password, profile_picture, name)
                VALUES (?, ?, ?, 'not-used', '', ?)
                """)) {
            statement.setObject(1, userId);
            statement.setString(2, username);
            statement.setString(3, username + "@example.com");
            statement.setString(4, username);
            statement.executeUpdate();
        }
    }

    private UUID insertList(UUID ownerId, String name, Integer rank, LocalDateTime createdAt) throws SQLException {
        return insertList(ownerId, name, rank, createdAt, UUID.randomUUID());
    }

    private UUID insertList(UUID ownerId, String name, Integer rank, LocalDateTime createdAt, UUID listId)
            throws SQLException {
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO user_lists (id, user_id, name, visibility, rank, created_at, updated_at)
                VALUES (?, ?, ?, 'PUBLIC', ?, ?, ?)
                """)) {
            statement.setObject(1, listId);
            statement.setObject(2, ownerId);
            statement.setString(3, name);
            if (rank == null) statement.setNull(4, java.sql.Types.INTEGER);
            else statement.setInt(4, rank);
            statement.setObject(5, createdAt);
            statement.setObject(6, createdAt);
            statement.executeUpdate();
        }
        return listId;
    }

    private List<UUID> readRankedIds(UUID ownerId) throws SQLException {
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement("""
                SELECT id FROM user_lists WHERE user_id = ? ORDER BY rank ASC, id ASC
                """)) {
            statement.setObject(1, ownerId);
            try (ResultSet results = statement.executeQuery()) {
                java.util.ArrayList<UUID> ids = new java.util.ArrayList<>();
                while (results.next()) ids.add(results.getObject("id", UUID.class));
                return ids;
            }
        }
    }

    private List<Integer> readRanks(UUID ownerId) throws SQLException {
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement("""
                SELECT rank FROM user_lists WHERE user_id = ? ORDER BY rank ASC, id ASC
                """)) {
            statement.setObject(1, ownerId);
            try (ResultSet results = statement.executeQuery()) {
                java.util.ArrayList<Integer> ranks = new java.util.ArrayList<>();
                while (results.next()) ranks.add(results.getInt("rank"));
                return ranks;
            }
        }
    }
}
