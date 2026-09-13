package com.watchwise.watchwise_api.calendar.repository;

import com.watchwise.watchwise_api.calendar.entity.CalendarScheduleCompleteness;
import jakarta.persistence.EntityManager;
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
class CalendarScheduleCompletenessRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private CalendarScheduleCompletenessRepository repository;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
    }

    @Test
    @DisplayName("[identity] Should Keep Season And Series Completeness Markers Separate")
    void shouldKeepSeasonAndSeriesCompletenessMarkersSeparate() {
        CalendarScheduleCompleteness season = repository.saveAndFlush(marker(
                CalendarScheduleCompleteness.GroupType.SEASON, 2, 13, true));
        CalendarScheduleCompleteness series = repository.saveAndFlush(marker(
                CalendarScheduleCompleteness.GroupType.SERIES, null, 62, true));
        entityManager.clear();

        assertThat(repository.findSeasonIdentity("1396", 2, "BR", "pt-BR"))
                .extracting(CalendarScheduleCompleteness::getId).contains(season.getId());
        assertThat(repository.findSeriesIdentity("1396", "BR", "pt-BR"))
                .extracting(CalendarScheduleCompleteness::getId).contains(series.getId());
    }

    @Test
    @DisplayName("[save] Should Preserve An Incomplete Marker")
    void shouldPreserveAnIncompleteMarker() {
        CalendarScheduleCompleteness saved = repository.saveAndFlush(marker(
                CalendarScheduleCompleteness.GroupType.SEASON, 2, 13, false));
        entityManager.clear();

        CalendarScheduleCompleteness reloaded = repository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getComplete()).isFalse();
        assertThat(reloaded.getExpectedEpisodeCount()).isEqualTo(13);
    }

    @Test
    @DisplayName("[save] Should Reject A Series Marker With A Season Number")
    void shouldRejectASeriesMarkerWithASeasonNumber() {
        assertThatThrownBy(() -> repository.saveAndFlush(marker(
                CalendarScheduleCompleteness.GroupType.SERIES, 1, 62, true)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_calendar_schedule_completeness_coordinates");
    }

    private CalendarScheduleCompleteness marker(
            CalendarScheduleCompleteness.GroupType groupType,
            Integer seasonNumber,
            int expectedEpisodeCount,
            boolean complete) {
        return CalendarScheduleCompleteness.builder()
                .groupType(groupType)
                .seriesTmdbId("1396")
                .seasonNumber(seasonNumber)
                .region("BR")
                .language("pt-BR")
                .expectedEpisodeCount(expectedEpisodeCount)
                .complete(complete)
                .lastCheckedAt(LocalDateTime.of(2026, 9, 12, 10, 0))
                .build();
    }
}
