package com.watchwise.watchwise_api.userlist.repository;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class UserListRankMigrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @BeforeEach
    void migrateSchemaToVersion83() throws SQLException {
        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA public CASCADE");
            statement.execute("CREATE SCHEMA public");
        }
        flywayAt("83").migrate();
    }

    @Test
    @DisplayName("[V84] Should Compact Positive User List Ranks And Preserve Nulls And IDs")
    void shouldCompactPositiveRanksAndPreserveNullsAndIds() throws SQLException, IOException {
        UUID ownerId = UUID.randomUUID();
        UUID otherOwnerId = UUID.randomUUID();
        insertUser(ownerId, "rankrepairowner");
        insertUser(otherOwnerId, "rankrepairother");
        UUID firstId = insertList(ownerId, "First", 1);
        UUID thirdId = insertList(ownerId, "Third", 3);
        UUID seventhId = insertList(ownerId, "Seventh", 7);
        UUID nullRankId = insertList(ownerId, "Unranked", null);
        UUID otherOwnerRankId = insertList(otherOwnerId, "Other owner", 8);

        flywayAt("84").migrate();

        assertThat(readRanks(ownerId)).containsExactly(
                new RankedList(firstId, 1),
                new RankedList(thirdId, 2),
                new RankedList(seventhId, 3),
                new RankedList(nullRankId, null));
        assertThat(readRanks(otherOwnerId)).containsExactly(new RankedList(otherOwnerRankId, 1));

        rerunRankRepair();
        flywayAt("84").migrate();

        assertThat(readRanks(ownerId)).containsExactly(
                new RankedList(firstId, 1),
                new RankedList(thirdId, 2),
                new RankedList(seventhId, 3),
                new RankedList(nullRankId, null));
    }

    @Test
    @DisplayName("[V84] Should Fail Without Changing Rows - When A Non-Positive Rank Exists")
    void shouldFailWithoutChangingRowsWhenANonPositiveRankExists() throws SQLException {
        UUID ownerId = UUID.randomUUID();
        insertUser(ownerId, "invalidrankowner");
        UUID invalidRankId = insertList(ownerId, "Invalid", 0);
        UUID positiveRankId = insertList(ownerId, "Positive", 3);

        assertThatThrownBy(() -> flywayAt("84").migrate())
                .isInstanceOf(FlywayException.class);

        assertThat(readRanks(ownerId)).containsExactly(
                new RankedList(invalidRankId, 0),
                new RankedList(positiveRankId, 3));
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

    private UUID insertList(UUID ownerId, String name, Integer rank) throws SQLException {
        UUID listId = UUID.randomUUID();
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO user_lists (id, user_id, name, visibility, rank)
                VALUES (?, ?, ?, 'PUBLIC', ?)
                """)) {
            statement.setObject(1, listId);
            statement.setObject(2, ownerId);
            statement.setString(3, name);
            if (rank == null) {
                statement.setNull(4, java.sql.Types.INTEGER);
            } else {
                statement.setInt(4, rank);
            }
            statement.executeUpdate();
        }
        return listId;
    }

    private List<RankedList> readRanks(UUID ownerId) throws SQLException {
        List<RankedList> ranks = new ArrayList<>();
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement("""
                SELECT id, rank FROM user_lists WHERE user_id = ? ORDER BY rank ASC NULLS LAST, id ASC
                """)) {
            statement.setObject(1, ownerId);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    ranks.add(new RankedList(results.getObject("id", UUID.class), results.getObject("rank", Integer.class)));
                }
            }
        }
        return ranks;
    }

    private void rerunRankRepair() throws SQLException, IOException {
        String migration = new ClassPathResource("db/migration/V84__compact-user-list-ranks.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            statement.execute(migration);
        }
    }

    private record RankedList(UUID id, Integer rank) {
    }
}
