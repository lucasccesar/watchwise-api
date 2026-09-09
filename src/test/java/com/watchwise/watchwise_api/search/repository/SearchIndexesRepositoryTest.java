package com.watchwise.watchwise_api.search.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class SearchIndexesRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Should Create Indexes Suitable For Local Search")
    void shouldCreateIndexesSuitableForLocalSearch() {
        assertThat(indexDefinitions("idx_user_lists_name_trgm"))
                .singleElement()
                .satisfies(definition -> assertThat(definition)
                        .containsIgnoringCase("lower(")
                        .containsIgnoringCase("name")
                        .containsIgnoringCase("gin_trgm_ops"));
        assertThat(indexDefinitions("idx_users_username_lower_pattern"))
                .singleElement()
                .satisfies(definition -> assertThat(definition)
                        .containsIgnoringCase("lower(")
                        .containsIgnoringCase("username")
                        .containsIgnoringCase("text_pattern_ops"));
    }

    @Test
    @DisplayName("Should Use Search Indexes For Selective Local Queries")
    void shouldUseSearchIndexesForSelectiveLocalQueries() {
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        jdbcTemplate.update(
                """
                INSERT INTO users (
                    id, username, email, password, profile_picture,
                    is_profile_public, is_email_verified, preferred_language, preferred_region
                )
                VALUES (?, 'search-index-user', 'search-index-user@test.local', 'password',
                        'https://default-image.png', TRUE, TRUE, 'en-US', 'US')
                """,
                userId);
        jdbcTemplate.update(
                """
                INSERT INTO user_lists (id, user_id, name, visibility)
                SELECT
                    ('00000000-0000-0000-0000-' || LPAD(i::text, 12, '0'))::uuid,
                    ?,
                    CASE WHEN i = 1 THEN 'needle target list' ELSE 'unrelated list ' || i END,
                    'PUBLIC'
                FROM generate_series(1, 100000) AS series(i)
                """,
                userId);
        jdbcTemplate.update(
                """
                INSERT INTO users (
                    id, username, email, password, profile_picture,
                    is_profile_public, is_email_verified, preferred_language, preferred_region
                )
                SELECT
                    ('00000000-0000-0000-0001-' || LPAD(i::text, 12, '0'))::uuid,
                    CASE WHEN i = 1 THEN 'prefix-target' ELSE 'account' || i END,
                    'search-index-' || i || '@test.local',
                    'password', 'https://default-image.png', TRUE, TRUE, 'en-US', 'US'
                FROM generate_series(1, 100000) AS series(i)
                """);
        jdbcTemplate.execute("ANALYZE user_lists");
        jdbcTemplate.execute("ANALYZE users");

        List<String> listPlan = explain("SELECT id FROM user_lists WHERE LOWER(name) LIKE '%needle%'");
        List<String> userPlan = explain("SELECT id FROM users WHERE LOWER(username) LIKE 'prefix%'");

        assertThat(listPlan).anyMatch(line -> line.contains("idx_user_lists_name_trgm"));
        assertThat(userPlan).anyMatch(line -> line.contains("idx_users_username_lower_pattern"));
    }

    private List<String> indexDefinitions(String indexName) {
        return jdbcTemplate.queryForList(
                """
                SELECT indexdef
                FROM pg_indexes
                WHERE schemaname = current_schema()
                  AND indexname = ?
                """,
                String.class,
                indexName);
    }

    private List<String> explain(String sql) {
        return jdbcTemplate.queryForList("EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) " + sql, String.class);
    }
}
