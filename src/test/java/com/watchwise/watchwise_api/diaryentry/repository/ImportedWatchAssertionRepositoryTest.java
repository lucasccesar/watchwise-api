package com.watchwise.watchwise_api.diaryentry.repository;

import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.diaryentry.entity.ImportedWatchAssertion;
import com.watchwise.watchwise_api.user.entity.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class ImportedWatchAssertionRepositoryTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ImportedWatchAssertionRepository repository;
    @PersistenceContext private EntityManager entityManager;

    @Test
    @DisplayName("[existsByUserIdAndContentId] Should Match Both Owner And Content - When Assertion Exists")
    void shouldMatchBothOwnerAndContentWhenAssertionExists() {
        UUID ownerId = insertUser("assertion-owner");
        UUID otherOwnerId = insertUser("assertion-other-owner");
        UUID contentId = insertSeries("90001");
        UUID otherContentId = insertSeries("90002");

        repository.saveAndFlush(ImportedWatchAssertion.builder()
                .user(entityManager.getReference(User.class, ownerId))
                .content(entityManager.getReference(Content.class, contentId))
                .sourceNamespace("trakt")
                .objectKind("episode")
                .sourceId("episode-1")
                .coverage("UNKNOWN")
                .createdAt(LocalDateTime.now())
                .build());
        entityManager.clear();

        assertThat(repository.existsByUserIdAndContentId(ownerId, contentId)).isTrue();
        assertThat(repository.existsByUserIdAndContentId(otherOwnerId, contentId)).isFalse();
        assertThat(repository.existsByUserIdAndContentId(ownerId, otherContentId)).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from diary_entries where user_id = ?", Integer.class, ownerId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from series_progress_metadata where series_tmdb_id = '90001'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from series_progress_season_metadata where series_tmdb_id = '90001'", Integer.class)).isZero();
    }

    private UUID insertUser(String username) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into users(id, username, name, email, password, profile_picture, created_at, updated_at)
                values (?, ?, ?, ?, 'test', 'test', now(), now())
                """, id, username, username, username + "@test.local");
        return id;
    }

    private UUID insertSeries(String tmdbId) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into contents(id, tmdb_id, type, created_at, updated_at) values (?, ?, 'SERIES', now(), now())",
                id, tmdbId);
        return id;
    }
}
