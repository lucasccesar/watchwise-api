package com.watchwise.watchwise_api.userlist.repository;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class OrderedEntryPositionMigrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @BeforeEach
    void migrateSchemaToV85() throws SQLException {
        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA public CASCADE");
            statement.execute("CREATE SCHEMA public");
        }
        flywayAt("85").migrate();
    }

    @Test
    @DisplayName("[V86] Should Compact Top5 And User List Positions While Preserving IDs And Order")
    void shouldCompactTop5AndUserListPositionsWhilePreservingIdsAndOrder() throws SQLException {
        UUID ownerId = UUID.randomUUID();
        UUID listId = UUID.randomUUID();
        insertUser(ownerId, "compactentriesowner");
        insertList(listId, ownerId, "Ordered entries", 1);

        UUID[] top5Ids = new UUID[3];
        UUID[] top5ContentIds = new UUID[3];
        UUID[] listItemIds = new UUID[3];
        UUID[] listContentIds = new UUID[3];
        int[] oldPositions = {1, 3, 5};
        for (int i = 0; i < oldPositions.length; i++) {
            top5ContentIds[i] = UUID.randomUUID();
            listContentIds[i] = UUID.randomUUID();
            insertContent(top5ContentIds[i], "top5-" + i);
            insertContent(listContentIds[i], "list-" + i);
            top5Ids[i] = insertTop5Entry(ownerId, top5ContentIds[i], oldPositions[i]);
            listItemIds[i] = insertListItem(listId, listContentIds[i], oldPositions[i]);
        }

        List<EntryRow> top5Before = readTop5Positions(ownerId);
        List<EntryRow> itemsBefore = readListItemPositions(listId);

        flywayLatest().migrate();

        assertThat(readTop5Positions(ownerId)).containsExactly(
                new EntryRow(top5Ids[0], 1), new EntryRow(top5Ids[1], 2), new EntryRow(top5Ids[2], 3));
        assertThat(readListItemPositions(listId)).containsExactly(
                new EntryRow(listItemIds[0], 1), new EntryRow(listItemIds[1], 2), new EntryRow(listItemIds[2], 3));
        assertThat(readTop5Positions(ownerId)).extracting(EntryRow::id)
                .containsExactlyElementsOf(top5Before.stream().map(EntryRow::id).toList());
        assertThat(readListItemPositions(listId)).extracting(EntryRow::id)
                .containsExactlyElementsOf(itemsBefore.stream().map(EntryRow::id).toList());
    }

    @Test
    @DisplayName("[V86] Should Fail Without Deleting Edges Or Compacting Positions - When Nesting Contains A Dirty Component")
    void shouldFailWithoutDeletingEdgesOrCompactingPositionsWhenNestingContainsADirtyComponent()
            throws SQLException {
        UUID ownerId = UUID.randomUUID();
        UUID listOwnerId = UUID.randomUUID();
        insertUser(ownerId, "dirtygraphowner");
        insertUser(listOwnerId, "dirtyparentowner");
        UUID outerListId = UUID.randomUUID();
        UUID middleListId = UUID.randomUUID();
        UUID innerListId = UUID.randomUUID();
        UUID contentListId = UUID.randomUUID();
        insertList(outerListId, listOwnerId, "Outer", 1);
        insertList(middleListId, ownerId, "Middle", 1);
        insertList(innerListId, ownerId, "Inner", 2);
        insertList(contentListId, listOwnerId, "Content items", 2);

        UUID edgeOuterMiddleId = insertNestedListItem(outerListId, middleListId, 1);
        UUID edgeMiddleInnerId = insertNestedListItem(middleListId, innerListId, 1);
        UUID firstContentId = UUID.randomUUID();
        UUID thirdContentId = UUID.randomUUID();
        insertContent(firstContentId, "dirty-list-first");
        insertContent(thirdContentId, "dirty-list-third");
        UUID firstItemId = insertListItem(contentListId, firstContentId, 1);
        UUID thirdItemId = insertListItem(contentListId, thirdContentId, 3);
        UUID top5ContentOne = UUID.randomUUID();
        UUID top5ContentThree = UUID.randomUUID();
        insertContent(top5ContentOne, "dirty-top5-one");
        insertContent(top5ContentThree, "dirty-top5-three");
        UUID top5ItemOne = insertTop5Entry(ownerId, top5ContentOne, 1);
        UUID top5ItemThree = insertTop5Entry(ownerId, top5ContentThree, 3);

        assertThatThrownBy(() -> flywayLatest().migrate())
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("86")
                .hasStackTraceContaining("one-level graph rule");

        assertThat(readListItemRows(contentListId)).containsExactly(
                new ListItemRow(firstItemId, contentListId, null, 1),
                new ListItemRow(thirdItemId, contentListId, null, 3));
        assertThat(readListItemRows(outerListId))
                .containsExactly(new ListItemRow(edgeOuterMiddleId, outerListId, middleListId, 1));
        assertThat(readListItemRows(middleListId))
                .containsExactly(new ListItemRow(edgeMiddleInnerId, middleListId, innerListId, 1));
        assertThat(readTop5Positions(ownerId))
                .containsExactly(new EntryRow(top5ItemOne, 1), new EntryRow(top5ItemThree, 3));
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

    private void insertList(UUID listId, UUID ownerId, String name, int rank) throws SQLException {
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO user_lists (id, user_id, name, visibility, rank)
                VALUES (?, ?, ?, 'PUBLIC', ?)
                """)) {
            statement.setObject(1, listId);
            statement.setObject(2, ownerId);
            statement.setString(3, name);
            statement.setInt(4, rank);
            statement.executeUpdate();
        }
    }

    private void insertContent(UUID contentId, String tmdbId) throws SQLException {
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO contents (id, tmdb_id, type) VALUES (?, ?, 'MOVIE')
                """)) {
            statement.setObject(1, contentId);
            statement.setString(2, tmdbId);
            statement.executeUpdate();
        }
    }

    private UUID insertTop5Entry(UUID ownerId, UUID contentId, int position) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO top5_entries (id, user_id, content_id, type, position)
                VALUES (?, ?, ?, 'MOVIE', ?)
                """)) {
            statement.setObject(1, id);
            statement.setObject(2, ownerId);
            statement.setObject(3, contentId);
            statement.setInt(4, position);
            statement.executeUpdate();
        }
        return id;
    }

    private UUID insertListItem(UUID listId, UUID contentId, int position) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO user_list_items (id, user_list_id, content_id, position)
                VALUES (?, ?, ?, ?)
                """)) {
            statement.setObject(1, id);
            statement.setObject(2, listId);
            statement.setObject(3, contentId);
            statement.setInt(4, position);
            statement.executeUpdate();
        }
        return id;
    }

    private UUID insertNestedListItem(UUID parentListId, UUID childListId, int position) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO user_list_items (id, user_list_id, child_list_id, position)
                VALUES (?, ?, ?, ?)
                """)) {
            statement.setObject(1, id);
            statement.setObject(2, parentListId);
            statement.setObject(3, childListId);
            statement.setInt(4, position);
            statement.executeUpdate();
        }
        return id;
    }

    private List<EntryRow> readTop5Positions(UUID ownerId) throws SQLException {
        List<EntryRow> rows = new ArrayList<>();
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement("""
                SELECT id, position FROM top5_entries
                WHERE user_id = ? AND type = 'MOVIE' ORDER BY position
                """)) {
            statement.setObject(1, ownerId);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    rows.add(new EntryRow(results.getObject("id", UUID.class), results.getInt("position")));
                }
            }
        }
        return rows;
    }

    private List<EntryRow> readListItemPositions(UUID listId) throws SQLException {
        return readListItemRows(listId).stream().map(row -> new EntryRow(row.id(), row.position())).toList();
    }

    private List<ListItemRow> readListItemRows(UUID listId) throws SQLException {
        List<ListItemRow> rows = new ArrayList<>();
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement("""
                SELECT id, user_list_id, child_list_id, position
                FROM user_list_items WHERE user_list_id = ? ORDER BY position
                """)) {
            statement.setObject(1, listId);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    rows.add(new ListItemRow(
                            results.getObject("id", UUID.class),
                            results.getObject("user_list_id", UUID.class),
                            results.getObject("child_list_id", UUID.class),
                            results.getInt("position")));
                }
            }
        }
        return rows;
    }

    private record EntryRow(UUID id, int position) {
    }

    private record ListItemRow(UUID id, UUID userListId, UUID childListId, int position) {
    }
}
