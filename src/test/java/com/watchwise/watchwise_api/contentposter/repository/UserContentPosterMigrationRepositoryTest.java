package com.watchwise.watchwise_api.contentposter.repository;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class UserContentPosterMigrationRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    @DisplayName("[V57/V58] Should Backfill Valid Legacy Posters And Remove Legacy Storage")
    void shouldBackfillValidLegacyPostersAndRemoveLegacyStorage() {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target("56")
                .load()
                .migrate();

        JdbcTemplate jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        LegacyFixture fixture = insertLegacyFixture(jdbcTemplate);

        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target("57")
                .load()
                .migrate();

        assertCanonicalPosters(jdbcTemplate, fixture);

        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        assertCanonicalPosters(jdbcTemplate, fixture);
        assertThat(hasColumn(jdbcTemplate, "diary_entries", "custom_poster_url")).isFalse();
        assertThat(hasColumn(jdbcTemplate, "top5_entries", "custom_poster_url")).isFalse();
        assertThat(hasColumn(jdbcTemplate, "user_list_items", "custom_poster_url")).isFalse();
        assertThat(hasConstraint(jdbcTemplate, "ck_user_list_items_poster_content_only")).isFalse();
    }

    private void assertCanonicalPosters(JdbcTemplate jdbcTemplate, LegacyFixture fixture) {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_content_posters", Integer.class)).isEqualTo(3);
        assertThat(posterUrl(jdbcTemplate, fixture.diaryContentId()))
                .isEqualTo("https://image.tmdb.org/t/p/w342/diary.png");
        assertThat(posterUrl(jdbcTemplate, fixture.top5ContentId()))
                .isEqualTo("https://image.tmdb.org/t/p/w342/top5.png");
        assertThat(posterUrl(jdbcTemplate, fixture.listContentId()))
                .isEqualTo("https://image.tmdb.org/t/p/w342/list.png");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_content_posters WHERE content_id = ?",
                Integer.class,
                fixture.invalidContentId())).isZero();
    }

    private LegacyFixture insertLegacyFixture(JdbcTemplate jdbcTemplate) {
        UUID userId = UUID.randomUUID();
        UUID diaryContentId = insertUserAndContent(jdbcTemplate, userId, "550", "diary");
        UUID top5ContentId = insertContent(jdbcTemplate, "680");
        UUID listContentId = insertContent(jdbcTemplate, "1396");
        UUID invalidContentId = insertContent(jdbcTemplate, "603");
        UUID listId = UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO diary_entries (id, user_id, content_id, watch_number, custom_poster_url) VALUES (?, ?, ?, 1, ?)",
                UUID.randomUUID(), userId, diaryContentId, "https://image.tmdb.org/t/p/w342/diary.png");
        jdbcTemplate.update(
                "INSERT INTO diary_entries (id, user_id, content_id, watch_number, custom_poster_url) VALUES (?, ?, ?, 1, ?)",
                UUID.randomUUID(), userId, invalidContentId, "https://image.tmdb.org/t/p/w500/invalid.png");
        jdbcTemplate.update(
                "INSERT INTO top5_entries (id, user_id, content_id, type, position) VALUES (?, ?, ?, 'MOVIE', 1)",
                UUID.randomUUID(), userId, top5ContentId);
        jdbcTemplate.update(
                "UPDATE top5_entries SET custom_poster_url = ? WHERE user_id = ? AND content_id = ?",
                "https://image.tmdb.org/t/p/w342/top5.png", userId, top5ContentId);
        jdbcTemplate.update(
                "INSERT INTO user_lists (id, user_id, name) VALUES (?, ?, ?)",
                listId, userId, "Legacy posters");
        jdbcTemplate.update(
                "INSERT INTO user_list_items (id, user_list_id, content_id, position, custom_poster_url) VALUES (?, ?, ?, 1, ?)",
                UUID.randomUUID(), listId, listContentId, "https://image.tmdb.org/t/p/w342/list.png");

        return new LegacyFixture(diaryContentId, top5ContentId, listContentId, invalidContentId);
    }

    private UUID insertUserAndContent(JdbcTemplate jdbcTemplate, UUID userId, String tmdbId, String suffix) {
        jdbcTemplate.update(
                "INSERT INTO users (id, username, email, password, profile_picture) VALUES (?, ?, ?, ?, ?)",
                userId, "migration-" + suffix, "migration-" + suffix + "@email.com", "password", "");
        return insertContent(jdbcTemplate, tmdbId);
    }

    private UUID insertContent(JdbcTemplate jdbcTemplate, String tmdbId) {
        UUID contentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO contents (id, tmdb_id, type) VALUES (?, ?, 'MOVIE')",
                contentId, tmdbId);
        return contentId;
    }

    private String posterUrl(JdbcTemplate jdbcTemplate, UUID contentId) {
        return jdbcTemplate.queryForObject(
                "SELECT custom_poster_url FROM user_content_posters WHERE content_id = ?",
                String.class,
                contentId);
    }

    private boolean hasColumn(JdbcTemplate jdbcTemplate, String tableName, String columnName) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = ? AND column_name = ?)",
                Boolean.class,
                tableName,
                columnName));
    }

    private boolean hasConstraint(JdbcTemplate jdbcTemplate, String constraintName) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = ?)",
                Boolean.class,
                constraintName));
    }

    private record LegacyFixture(
            UUID diaryContentId,
            UUID top5ContentId,
            UUID listContentId,
            UUID invalidContentId) {
    }
}
