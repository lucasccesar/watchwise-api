package com.watchwise.watchwise_api.notification.repository;

import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.notification.entity.TrackedPersonCredit;
import com.watchwise.watchwise_api.notification.entity.TrackedPersonState;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class TrackedPersonCreditRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private TrackedPersonStateRepository trackedPersonStateRepository;

    @Autowired
    private TrackedPersonCreditRepository trackedPersonCreditRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        trackedPersonCreditRepository.deleteAll();
        trackedPersonStateRepository.deleteAll();
    }

    @Test
    @DisplayName("[save] Should Allow The Same TMDB ID For Movie And Series - When Tracking One Person")
    void shouldAllowTheSameTmdbIdForMovieAndSeriesWhenTrackingOnePerson() {
        TrackedPersonState state = trackedPersonStateRepository.saveAndFlush(buildState("6193"));

        trackedPersonCreditRepository.saveAndFlush(buildCredit(state, "123", ContentType.MOVIE));
        trackedPersonCreditRepository.saveAndFlush(buildCredit(state, "123", ContentType.SERIES));
        entityManager.clear();

        assertThat(trackedPersonCreditRepository.findByTrackedPersonStateId(state.getId()))
                .extracting(TrackedPersonCredit::getCreditType)
                .containsExactlyInAnyOrder(ContentType.MOVIE, ContentType.SERIES);
    }

    @Test
    @DisplayName("[save] Should Reject A Repeated Typed Key - When The Same Credit Is Tracked Twice")
    void shouldRejectARepeatedTypedKeyWhenTheSameCreditIsTrackedTwice() {
        TrackedPersonState state = trackedPersonStateRepository.saveAndFlush(buildState("6193"));
        trackedPersonCreditRepository.saveAndFlush(buildCredit(state, "123", ContentType.MOVIE));

        assertThatThrownBy(() -> trackedPersonCreditRepository.saveAndFlush(
                buildCredit(state, "123", ContentType.MOVIE)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private TrackedPersonState buildState(String personTmdbId) {
        return TrackedPersonState.builder()
                .personTmdbId(personTmdbId)
                .lastCheckedAt(LocalDateTime.now())
                .build();
    }

    private TrackedPersonCredit buildCredit(TrackedPersonState state, String creditTmdbId, ContentType type) {
        return TrackedPersonCredit.builder()
                .trackedPersonState(state)
                .creditTmdbId(creditTmdbId)
                .creditType(type)
                .build();
    }
}
