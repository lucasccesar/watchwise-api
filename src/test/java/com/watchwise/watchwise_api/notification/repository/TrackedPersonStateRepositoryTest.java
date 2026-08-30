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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class TrackedPersonStateRepositoryTest {

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
    @DisplayName("[findByPersonTmdbId] Should Return The Row - When It Exists")
    void shouldReturnTheRowWhenItExists() {
        trackedPersonStateRepository.saveAndFlush(buildState("6193"));
        entityManager.clear();

        Optional<TrackedPersonState> result = trackedPersonStateRepository.findByPersonTmdbId("6193");

        assertThat(result).isPresent();
    }

    @Test
    @DisplayName("[save] Should Throw DataIntegrityViolationException - When PersonTmdbId Already Tracked")
    void shouldThrowDataIntegrityViolationExceptionWhenPersonTmdbIdAlreadyTracked() {
        trackedPersonStateRepository.saveAndFlush(buildState("6193"));
        entityManager.clear();

        assertThatThrownBy(() -> trackedPersonStateRepository.saveAndFlush(buildState("6193")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("[findByTrackedPersonStateId] Should Return Only That Person's Credits - When Multiple People Are Tracked")
    void shouldReturnOnlyThatPersonsCreditsWhenMultiplePeopleAreTracked() {
        TrackedPersonState brad = trackedPersonStateRepository.saveAndFlush(buildState("6193"));
        TrackedPersonState kate = trackedPersonStateRepository.saveAndFlush(buildState("1813"));
        trackedPersonCreditRepository.saveAndFlush(buildCredit(brad, "603"));
        trackedPersonCreditRepository.saveAndFlush(buildCredit(kate, "597"));
        entityManager.clear();

        List<TrackedPersonCredit> result = trackedPersonCreditRepository.findByTrackedPersonStateId(brad.getId());

        assertThat(result).extracting(TrackedPersonCredit::getCreditTmdbId).containsExactly("603");
    }

    @Test
    @DisplayName("[existsByTrackedPersonStateIdAndCreditTmdbId] Should Return False - When Credit Is New")
    void shouldReturnFalseWhenCreditIsNew() {
        TrackedPersonState brad = trackedPersonStateRepository.saveAndFlush(buildState("6193"));
        entityManager.clear();

        boolean result = trackedPersonCreditRepository.existsByTrackedPersonStateIdAndCreditTmdbId(brad.getId(), "603");

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("[deleteAll] Should Cascade Delete Credits - When The TrackedPersonState Is Deleted")
    void shouldCascadeDeleteCreditsWhenTheTrackedPersonStateIsDeleted() {
        TrackedPersonState brad = trackedPersonStateRepository.saveAndFlush(buildState("6193"));
        TrackedPersonCredit credit = trackedPersonCreditRepository.saveAndFlush(buildCredit(brad, "603"));
        entityManager.clear();

        trackedPersonStateRepository.delete(trackedPersonStateRepository.findById(brad.getId()).orElseThrow());
        trackedPersonStateRepository.flush();

        assertThat(trackedPersonCreditRepository.findById(credit.getId())).isEmpty();
    }

    private TrackedPersonState buildState(String personTmdbId) {
        return TrackedPersonState.builder()
                .personTmdbId(personTmdbId)
                .lastCheckedAt(LocalDateTime.now())
                .build();
    }

    private TrackedPersonCredit buildCredit(TrackedPersonState state, String creditTmdbId) {
        return TrackedPersonCredit.builder()
                .trackedPersonState(state)
                .creditTmdbId(creditTmdbId)
                .creditType(ContentType.MOVIE)
                .build();
    }
}
