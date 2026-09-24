package com.watchwise.watchwise_api.seriesprogress.repository;

import com.watchwise.watchwise_api.seriesprogress.entity.SeriesProgressMetadata;
import com.watchwise.watchwise_api.seriesprogress.entity.SeriesProgressSeasonMetadata;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class SeriesProgressMetadataRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private SeriesProgressMetadataRepository metadataRepository;

    @Autowired
    private SeriesProgressSeasonMetadataRepository seasonMetadataRepository;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        seasonMetadataRepository.deleteAll();
        metadataRepository.deleteAll();
    }

    @Test
    @DisplayName("[findAllBySeriesTmdbIdIn] Should Return Series And Regular Season Snapshots In One Batch")
    void shouldReturnSeriesAndRegularSeasonSnapshotsInOneBatch() {
        metadataRepository.saveAndFlush(buildSeriesMetadata("1396"));
        seasonMetadataRepository.saveAllAndFlush(List.of(
                buildSeasonMetadata("1396", 1),
                buildSeasonMetadata("1396", 2)));
        entityManager.clear();

        SeriesProgressMetadataRepository.SeriesProgressMetadataProjection series = metadataRepository
                .findAllBySeriesTmdbIdIn(List.of("1396"))
                .get(0);
        List<SeriesProgressSeasonMetadataRepository.SeriesProgressSeasonMetadataProjection> seasons = seasonMetadataRepository
                .findAllBySeriesTmdbIdIn(List.of("1396"));

        assertThat(series.getSeriesTmdbId()).isEqualTo("1396");
        assertThat(series.getRegularReleasedEpisodeCount()).isEqualTo(20);
        assertThat(series.getTotalKnownRuntime()).isEqualTo(1_000);
        assertThat(series.getKnownRuntimeEpisodeCount()).isEqualTo(18);
        assertThat(series.getLastReleasedEpisodeDate()).isEqualTo(LocalDate.of(2026, 9, 20));
        assertThat(series.getRefreshedAt()).isEqualTo(LocalDateTime.of(2026, 9, 23, 10, 0));
        assertThat(series.getRuntimeVerifiedAt()).isEqualTo(LocalDateTime.of(2026, 9, 23, 10, 1));
        assertThat(seasons).extracting(SeriesProgressSeasonMetadataRepository.SeriesProgressSeasonMetadataProjection::getSeasonNumber)
                .containsExactlyInAnyOrder(1, 2);
        assertThat(seasons).allSatisfy(season -> {
            assertThat(season.getSeriesTmdbId()).isEqualTo("1396");
            assertThat(season.getRegularReleasedEpisodeCount()).isEqualTo(10);
            assertThat(season.getTotalKnownRuntime()).isEqualTo(500);
            assertThat(season.getKnownRuntimeEpisodeCount()).isEqualTo(9);
            assertThat(season.getLastReleasedEpisodeDate()).isEqualTo(LocalDate.of(2026, 9, 20));
            assertThat(season.getRefreshedAt()).isEqualTo(LocalDateTime.of(2026, 9, 23, 10, 0));
        });
        assertThat(seasonMetadataRepository.findBySeriesTmdbIdAndSeasonNumber("1396", 2))
                .isPresent();
    }

    @Test
    @DisplayName("[save] Should Reject Season Zero")
    void shouldRejectSeasonZero() {
        assertThatThrownBy(() -> seasonMetadataRepository.saveAndFlush(buildSeasonMetadata("1396", 0)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_series_progress_season_metadata_positive_season");
    }

    @Test
    @DisplayName("[save] Should Reject Duplicate Series And Season Identity")
    void shouldRejectDuplicateSeriesAndSeasonIdentity() {
        seasonMetadataRepository.saveAndFlush(buildSeasonMetadata("1396", 1));

        assertThatThrownBy(() -> seasonMetadataRepository.saveAndFlush(buildSeasonMetadata("1396", 1)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_series_progress_season_metadata_identity");
    }

    private SeriesProgressMetadata buildSeriesMetadata(String seriesTmdbId) {
        return SeriesProgressMetadata.builder()
                .seriesTmdbId(seriesTmdbId)
                .regularReleasedEpisodeCount(20)
                .totalKnownRuntime(1_000)
                .knownRuntimeEpisodeCount(18)
                .lastReleasedEpisodeDate(LocalDate.of(2026, 9, 20))
                .refreshedAt(LocalDateTime.of(2026, 9, 23, 10, 0))
                .runtimeVerifiedAt(LocalDateTime.of(2026, 9, 23, 10, 1))
                .build();
    }

    private SeriesProgressSeasonMetadata buildSeasonMetadata(String seriesTmdbId, int seasonNumber) {
        return SeriesProgressSeasonMetadata.builder()
                .seriesTmdbId(seriesTmdbId)
                .seasonNumber(seasonNumber)
                .regularReleasedEpisodeCount(10)
                .totalKnownRuntime(500)
                .knownRuntimeEpisodeCount(9)
                .lastReleasedEpisodeDate(LocalDate.of(2026, 9, 20))
                .refreshedAt(LocalDateTime.of(2026, 9, 23, 10, 0))
                .build();
    }
}
