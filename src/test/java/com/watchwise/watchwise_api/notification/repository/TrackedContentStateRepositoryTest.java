package com.watchwise.watchwise_api.notification.repository;

import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.notification.entity.TrackedContentState;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class TrackedContentStateRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private TrackedContentStateRepository trackedContentStateRepository;

    @Autowired
    private ContentRepository contentRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private Content movie;

    @BeforeEach
    void setUp() {
        trackedContentStateRepository.deleteAll();
        contentRepository.deleteAll();

        movie = contentRepository.saveAndFlush(Content.builder()
                .tmdbId("603")
                .type(ContentType.MOVIE)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());
    }

    @Test
    @DisplayName("[findByContentId] Should Return The Row - When It Exists")
    void shouldReturnTheRowWhenItExists() {
        trackedContentStateRepository.saveAndFlush(buildState(movie, "Released"));
        entityManager.clear();

        Optional<TrackedContentState> result = trackedContentStateRepository.findByContentId(movie.getId());

        assertThat(result).isPresent();
        assertThat(result.get().getLastKnownStatus()).isEqualTo("Released");
    }

    @Test
    @DisplayName("[findByContentId] Should Return Empty - When Content Is Not Tracked Yet")
    void shouldReturnEmptyWhenContentIsNotTrackedYet() {
        Optional<TrackedContentState> result = trackedContentStateRepository.findByContentId(movie.getId());

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("[deleteAll] Should Cascade Delete The Row - When The Content Is Deleted")
    void shouldCascadeDeleteTheRowWhenTheContentIsDeleted() {
        trackedContentStateRepository.saveAndFlush(buildState(movie, "Released"));
        entityManager.clear();

        contentRepository.delete(contentRepository.findById(movie.getId()).orElseThrow());
        contentRepository.flush();

        assertThat(trackedContentStateRepository.findByContentId(movie.getId())).isEmpty();
    }

    private TrackedContentState buildState(Content content, String status) {
        return TrackedContentState.builder()
                .content(content)
                .lastKnownReleaseDate(LocalDate.of(1999, 3, 31))
                .lastKnownStatus(status)
                .lastCheckedAt(LocalDateTime.now())
                .build();
    }
}
