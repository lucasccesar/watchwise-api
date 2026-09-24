package com.watchwise.watchwise_api.seriesprogress.repository;

import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.dropped.entity.DroppedEntry;
import com.watchwise.watchwise_api.dropped.repository.DroppedEntryRepository;
import com.watchwise.watchwise_api.seriesprogress.entity.SeriesProgressMetadata;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class SeriesProgressReadRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private SeriesProgressReadRepository seriesProgressReadRepository;

    @Autowired
    private DiaryEntryRepository diaryEntryRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ContentRepository contentRepository;

    @Autowired
    private DroppedEntryRepository droppedEntryRepository;

    @Autowired
    private SeriesProgressMetadataRepository metadataRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private User lucas;

    @BeforeEach
    void setUp() {
        diaryEntryRepository.deleteAll();
        droppedEntryRepository.deleteAll();
        metadataRepository.deleteAll();
        contentRepository.deleteAll();
        userRepository.deleteAll();
        lucas = userRepository.save(buildUser("lucas", "lucas-series-progress@email.com"));
    }

    @Test
    @DisplayName("[findCandidatesByUserId] Should Batch Progress And Keep Last Coordinates With Its Date")
    void shouldBatchProgressAndKeepLastCoordinatesWithItsDate() {
        Content firstEpisode = saveEpisode("1399", 1, 1, 40);
        Content secondEpisode = saveEpisode("1399", 1, 2, null);
        Content secondSeasonEpisode = saveEpisode("1399", 2, 1, 50);
        Content specialEpisode = saveEpisode("1399", 0, 99, 999);

        saveEntry(firstEpisode, 1, LocalDate.of(2024, 1, 1), LocalDateTime.of(2024, 1, 1, 10, 0));
        saveEntry(secondEpisode, 1, LocalDate.of(2024, 2, 1), LocalDateTime.of(2024, 2, 1, 10, 0));
        saveEntry(secondSeasonEpisode, 1, LocalDate.of(2024, 2, 1), LocalDateTime.of(2024, 2, 1, 11, 0));
        saveEntry(firstEpisode, 2, LocalDate.of(2024, 3, 1), LocalDateTime.of(2024, 3, 1, 10, 0));
        saveEntry(specialEpisode, 1, LocalDate.of(2024, 12, 1), LocalDateTime.of(2024, 12, 1, 10, 0));
        saveMetadata("1399", 8, 400, LocalDate.of(2025, 1, 1));
        entityManager.clear();

        var page = seriesProgressReadRepository.findCandidatesByUserId(
                lucas.getId(), SeriesProgressReadRepository.SeriesProgressSort.LAST_WATCHED, PageRequest.of(0, 10));

        assertThat(page.getContent()).hasSize(1);
        var row = page.getContent().getFirst();
        assertThat(row.getSeriesTmdbId()).isEqualTo("1399");
        assertThat(row.getWatchedEpisodeCount()).isEqualTo(3L);
        assertThat(row.getWatchedRuntimeMinutes()).isEqualTo(90L);
        assertThat(row.getMaxSeasonNumber()).isEqualTo(2);
        assertThat(row.getMaxEpisodeNumber()).isEqualTo(1);
        assertThat(row.getLastWatchedDate()).isEqualTo(LocalDate.of(2024, 3, 1));
        assertThat(row.getLastWatchedSeasonNumber()).isEqualTo(1);
        assertThat(row.getLastWatchedEpisodeNumber()).isEqualTo(1);
        assertThat(row.getTotalReleasedEpisodeCount()).isEqualTo(8);
        assertThat(row.getTotalKnownRuntime()).isEqualTo(400);
        assertThat(row.getLastReleasedEpisodeDate()).isEqualTo(LocalDate.of(2025, 1, 1));
        assertThat(row.getRemainingEpisodeCount()).isEqualTo(5L);
        assertThat(row.getRemainingRuntimeMinutes()).isEqualTo(310L);
    }

    @Test
    @DisplayName("[findCandidatesByUserId] Should Tie Break By Series Id And Put Missing Runtime Last")
    void shouldTieBreakBySeriesIdAndPutMissingRuntimeLast() {
        Content firstSeriesEpisode = saveEpisode("1399", 1, 1, null);
        Content secondSeriesEpisode = saveEpisode("1396", 1, 1, 10);
        Content missingMetadataEpisode = saveEpisode("1400", 1, 1, 20);
        LocalDate tiedDate = LocalDate.of(2024, 5, 1);
        saveEntry(firstSeriesEpisode, 1, tiedDate, LocalDateTime.of(2024, 5, 1, 10, 0));
        saveEntry(secondSeriesEpisode, 1, tiedDate, LocalDateTime.of(2024, 5, 1, 10, 0));
        saveEntry(missingMetadataEpisode, 1, LocalDate.of(2024, 6, 1), LocalDateTime.of(2024, 6, 1, 10, 0));
        saveMetadata("1399", 2, 100, LocalDate.of(2024, 5, 1));
        saveMetadata("1396", 2, 100, LocalDate.of(2024, 5, 1));
        entityManager.clear();

        var page = seriesProgressReadRepository.findCandidatesByUserId(
                lucas.getId(), SeriesProgressReadRepository.SeriesProgressSort.LAST_WATCHED, PageRequest.of(0, 10));

        assertThat(page.getContent()).extracting(SeriesProgressReadRepository.SeriesProgressCandidate::getSeriesTmdbId)
                .containsExactly("1400", "1396", "1399");

        var runtimePage = seriesProgressReadRepository.findCandidatesByUserId(
                lucas.getId(), SeriesProgressReadRepository.SeriesProgressSort.REMAINING_RUNTIME, PageRequest.of(0, 10));
        assertThat(runtimePage.getContent()).extracting(SeriesProgressReadRepository.SeriesProgressCandidate::getSeriesTmdbId)
                .containsExactly("1396", "1399", "1400");
    }

    @Test
    @DisplayName("[findGlobalTotalsByUserId] Should Include Off Page Candidates And Exclude Completion Drop Specials And Rewatches")
    void shouldIncludeOffPageCandidatesAndExcludeCompletionDropSpecialsAndRewatches() {
        Content firstSeriesEpisode = saveEpisode("1399", 1, 1, 40);
        Content firstSeriesSpecial = saveEpisode("1399", 0, 1, 999);
        Content secondSeriesEpisode = saveEpisode("1396", 1, 1, 10);
        Content offPageEpisode = saveEpisode("1400", 1, 1, 20);
        Content completedEpisode = saveEpisode("1401", 1, 1, 30);
        Content completedSeries = saveContent("1401", ContentType.SERIES);
        Content droppedEpisode = saveEpisode("1402", 1, 1, 30);
        Content droppedSeries = saveContent("1402", ContentType.SERIES);

        saveEntry(firstSeriesEpisode, 1, LocalDate.of(2024, 1, 1), LocalDateTime.of(2024, 1, 1, 10, 0));
        saveEntry(firstSeriesEpisode, 2, LocalDate.of(2024, 2, 1), LocalDateTime.of(2024, 2, 1, 10, 0));
        saveEntry(firstSeriesSpecial, 1, LocalDate.of(2024, 3, 1), LocalDateTime.of(2024, 3, 1, 10, 0));
        saveEntry(secondSeriesEpisode, 1, LocalDate.of(2024, 1, 1), LocalDateTime.of(2024, 1, 1, 10, 0));
        saveEntry(offPageEpisode, 1, LocalDate.of(2024, 1, 1), LocalDateTime.of(2024, 1, 1, 10, 0));
        saveEntry(completedEpisode, 1, LocalDate.of(2024, 1, 1), LocalDateTime.of(2024, 1, 1, 10, 0));
        saveEntry(completedSeries, 1, LocalDate.of(2024, 2, 1), LocalDateTime.of(2024, 2, 1, 10, 0));
        saveEntry(droppedEpisode, 1, LocalDate.of(2024, 1, 1), LocalDateTime.of(2024, 1, 1, 10, 0));
        LocalDateTime now = LocalDateTime.of(2024, 4, 1, 10, 0);
        droppedEntryRepository.saveAndFlush(DroppedEntry.builder()
                .user(lucas).content(droppedSeries).type(ContentType.SERIES)
                .createdAt(now).updatedAt(now).build());
        entityManager.clear();

        var page = seriesProgressReadRepository.findCandidatesByUserId(
                lucas.getId(), SeriesProgressReadRepository.SeriesProgressSort.LAST_WATCHED, PageRequest.of(0, 1));
        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getTotalElements()).isEqualTo(3L);

        var totals = seriesProgressReadRepository.findGlobalTotalsByUserId(lucas.getId());

        assertThat(totals.getWatchedEpisodeCount()).isEqualTo(3L);
        assertThat(totals.getWatchedRuntimeMinutes()).isEqualTo(70L);
    }

    private Content saveEpisode(String seriesTmdbId, int seasonNumber, int episodeNumber, Integer runtimeMinutes) {
        return contentRepository.saveAndFlush(Content.builder()
                .seriesTmdbId(seriesTmdbId)
                .seasonNumber(seasonNumber)
                .episodeNumber(episodeNumber)
                .runtimeMinutes(runtimeMinutes)
                .type(ContentType.EPISODE)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());
    }

    private Content saveContent(String tmdbId, ContentType type) {
        return contentRepository.saveAndFlush(Content.builder()
                .tmdbId(tmdbId)
                .type(type)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());
    }

    private void saveEntry(Content content, int watchNumber, LocalDate watchedDate, LocalDateTime createdAt) {
        diaryEntryRepository.saveAndFlush(DiaryEntry.builder()
                .user(lucas)
                .content(content)
                .watchNumber(watchNumber)
                .watchedDate(watchedDate)
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .build());
    }

    private void saveMetadata(String seriesTmdbId, int releasedEpisodes, Integer totalRuntime, LocalDate lastRelease) {
        metadataRepository.saveAndFlush(SeriesProgressMetadata.builder()
                .seriesTmdbId(seriesTmdbId)
                .regularReleasedEpisodeCount(releasedEpisodes)
                .totalKnownRuntime(totalRuntime)
                .knownRuntimeEpisodeCount(totalRuntime == null ? 0 : releasedEpisodes)
                .lastReleasedEpisodeDate(lastRelease)
                .refreshedAt(LocalDateTime.of(2024, 6, 1, 10, 0))
                .build());
    }

    private User buildUser(String username, String email) {
        return User.builder()
                .username(username)
                .email(email)
                .password("hashed_password")
                .profilePicture("https://example.com/photo.png")
                .isProfilePublic(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }
}
