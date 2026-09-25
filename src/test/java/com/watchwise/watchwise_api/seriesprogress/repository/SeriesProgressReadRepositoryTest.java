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
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
class SeriesProgressReadRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.properties.hibernate.generate_statistics", () -> "true");
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
    private User marina;

    @BeforeEach
    void setUp() {
        diaryEntryRepository.deleteAll();
        droppedEntryRepository.deleteAll();
        metadataRepository.deleteAll();
        contentRepository.deleteAll();
        userRepository.deleteAll();
        lucas = userRepository.save(buildUser("lucas", "lucas-series-progress@email.com"));
        marina = userRepository.save(buildUser("marina", "marina-series-progress@email.com"));
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
        assertThat(row.getWatchedRuntimeMinutes()).isNull();
        assertThat(row.getMaxSeasonNumber()).isEqualTo(2);
        assertThat(row.getMaxEpisodeNumber()).isEqualTo(1);
        assertThat(row.getLastWatchedDate()).isEqualTo(LocalDate.of(2024, 3, 1));
        assertThat(row.getLastWatchedSeasonNumber()).isEqualTo(1);
        assertThat(row.getLastWatchedEpisodeNumber()).isEqualTo(1);
        assertThat(row.getTotalReleasedEpisodeCount()).isEqualTo(8);
        assertThat(row.getTotalKnownRuntime()).isEqualTo(400);
        assertThat(row.getLastReleasedEpisodeDate()).isEqualTo(LocalDate.of(2025, 1, 1));
        assertThat(row.getRemainingEpisodeCount()).isEqualTo(5L);
        assertThat(row.getRemainingRuntimeMinutes()).isNull();
    }

    @Test
    @DisplayName("[findCandidatesByUserId] Should Tie Break By Series Id And Put Missing Runtime Last For Runtime Sort")
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
    @DisplayName("[findCandidatesByUserId] Should Sort By Last Released Date Descending")
    void shouldSortByLastReleasedDateDescending() {
        saveProgressSeries("1399", 1, 3, 100, LocalDate.of(2024, 1, 1));
        saveProgressSeries("1396", 1, 4, 100, LocalDate.of(2024, 3, 1));
        saveProgressSeries("1400", 1, 2, 100, LocalDate.of(2024, 2, 1));
        entityManager.clear();

        var page = seriesProgressReadRepository.findCandidatesByUserId(
                lucas.getId(), SeriesProgressReadRepository.SeriesProgressSort.LAST_RELEASED, PageRequest.of(0, 10));

        assertThat(page.getContent()).extracting(SeriesProgressReadRepository.SeriesProgressCandidate::getSeriesTmdbId)
                .containsExactly("1396", "1400", "1399");
    }

    @Test
    @DisplayName("[findCandidatesByUserId] Should Sort By Remaining Episodes Ascending")
    void shouldSortByRemainingEpisodesAscending() {
        saveProgressSeries("1399", 1, 3, 100, LocalDate.of(2024, 1, 1));
        saveProgressSeries("1396", 1, 4, 100, LocalDate.of(2024, 2, 1));
        saveProgressSeries("1400", 1, 2, 100, LocalDate.of(2024, 3, 1));
        entityManager.clear();

        var page = seriesProgressReadRepository.findCandidatesByUserId(
                lucas.getId(), SeriesProgressReadRepository.SeriesProgressSort.REMAINING_EPISODES,
                Sort.Direction.ASC, PageRequest.of(0, 10));

        assertThat(page.getContent()).extracting(SeriesProgressReadRepository.SeriesProgressCandidate::getSeriesTmdbId)
                .containsExactly("1400", "1399", "1396");
    }

    @Test
    @DisplayName("[findCandidatesByUserId] Should Isolate Candidates By User")
    void shouldIsolateCandidatesByUser() {
        Content lucasEpisode = saveEpisode("1399", 1, 1, 40);
        Content marinaEpisode = saveEpisode("1400", 1, 1, 40);
        saveEntry(lucas, lucasEpisode, 1, LocalDate.of(2024, 1, 1), LocalDateTime.of(2024, 1, 1, 10, 0));
        saveEntry(marina, marinaEpisode, 1, LocalDate.of(2024, 1, 1), LocalDateTime.of(2024, 1, 1, 10, 0));
        saveMetadata("1399", 2, 100, LocalDate.of(2024, 2, 1));
        saveMetadata("1400", 2, 100, LocalDate.of(2024, 2, 1));
        entityManager.clear();

        var lucasPage = seriesProgressReadRepository.findCandidatesByUserId(
                lucas.getId(), SeriesProgressReadRepository.SeriesProgressSort.LAST_WATCHED, PageRequest.of(0, 10));
        var marinaPage = seriesProgressReadRepository.findCandidatesByUserId(
                marina.getId(), SeriesProgressReadRepository.SeriesProgressSort.LAST_WATCHED, PageRequest.of(0, 10));

        assertThat(lucasPage.getContent()).extracting(SeriesProgressReadRepository.SeriesProgressCandidate::getSeriesTmdbId)
                .containsExactly("1399");
        assertThat(marinaPage.getContent()).extracting(SeriesProgressReadRepository.SeriesProgressCandidate::getSeriesTmdbId)
                .containsExactly("1400");
    }

    @Test
    @DisplayName("[findCandidatesByUserId] Should Preserve Null Total Known Runtime")
    void shouldPreserveNullTotalKnownRuntime() {
        Content episode = saveEpisode("1399", 1, 1, 40);
        saveEntry(lucas, episode, 1, LocalDate.of(2024, 1, 1), LocalDateTime.of(2024, 1, 1, 10, 0));
        saveMetadata("1399", 2, null, LocalDate.of(2024, 2, 1));
        entityManager.clear();

        var row = seriesProgressReadRepository.findCandidatesByUserId(
                lucas.getId(), SeriesProgressReadRepository.SeriesProgressSort.REMAINING_RUNTIME, PageRequest.of(0, 10))
                .getContent().getFirst();

        assertThat(row.getTotalKnownRuntime()).isNull();
        assertThat(row.getRemainingRuntimeMinutes()).isNull();
    }

    @Test
    @DisplayName("[findCandidatesByUserId] Should Execute One Data Query And One Count Query")
    void shouldExecuteOneDataQueryAndOneCountQuery() {
        saveProgressSeries("1399", 1, 2, 100, LocalDate.of(2024, 1, 1));
        saveProgressSeries("1396", 1, 3, 100, LocalDate.of(2024, 2, 1));
        entityManager.flush();
        entityManager.clear();
        Statistics statistics = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        seriesProgressReadRepository.findCandidatesByUserId(
                lucas.getId(), SeriesProgressReadRepository.SeriesProgressSort.LAST_WATCHED, PageRequest.of(0, 1));

        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
        assertThat(statistics.getQueryExecutionCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("[findCandidatesByUserId] Should Reject A Null Sort")
    void shouldRejectNullSort() {
        assertThatThrownBy(() -> seriesProgressReadRepository.findCandidatesByUserId(
                lucas.getId(), null, PageRequest.of(0, 10)))
                .isInstanceOf(NullPointerException.class);
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

    @Test
    @DisplayName("[findProgressByUserIdAndSeriesTmdbIds] Should Include Unwatched Series And Season-Only References")
    void shouldIncludeUnwatchedSeriesAndSeasonOnlyReferences() {
        saveSeason("1399", 1);
        Content watchedEpisode = saveEpisode("1399", 1, 1, 40);
        Content specialEpisode = saveEpisode("1399", 0, 99, 999);
        saveContent("1396", ContentType.SERIES);
        saveEntry(watchedEpisode, 1, LocalDate.of(2024, 1, 1), LocalDateTime.of(2024, 1, 1, 10, 0));
        saveEntry(watchedEpisode, 2, LocalDate.of(2024, 2, 1), LocalDateTime.of(2024, 2, 1, 10, 0));
        saveEntry(specialEpisode, 1, LocalDate.of(2024, 3, 1), LocalDateTime.of(2024, 3, 1, 10, 0));
        saveMetadata("1399", 2, 80, LocalDate.of(2024, 2, 1));
        saveMetadata("1396", 3, 120, LocalDate.of(2024, 2, 1));
        entityManager.clear();

        List<SeriesProgressReadRepository.SeriesProgressCandidate> rows =
                seriesProgressReadRepository.findProgressByUserIdAndSeriesTmdbIds(
                        lucas.getId(), List.of("1399", "1396"));

        assertThat(rows).extracting(SeriesProgressReadRepository.SeriesProgressCandidate::getSeriesTmdbId)
                .containsExactly("1396", "1399");
        var unwatched = rows.stream()
                .filter(row -> row.getSeriesTmdbId().equals("1396"))
                .findFirst()
                .orElseThrow();
        assertThat(unwatched.getWatchedEpisodeCount()).isZero();
        assertThat(unwatched.getMaxSeasonNumber()).isNull();
        assertThat(unwatched.getRemainingEpisodeCount()).isEqualTo(3L);
        assertThat(unwatched.getRemainingRuntimeMinutes()).isEqualTo(120L);

        var seasonOnly = rows.stream()
                .filter(row -> row.getSeriesTmdbId().equals("1399"))
                .findFirst()
                .orElseThrow();
        assertThat(seasonOnly.getWatchedEpisodeCount()).isEqualTo(1L);
        assertThat(seasonOnly.getMaxSeasonNumber()).isEqualTo(1);
        assertThat(seasonOnly.getRemainingEpisodeCount()).isEqualTo(1L);
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

    private Content saveSeason(String seriesTmdbId, int seasonNumber) {
        return contentRepository.saveAndFlush(Content.builder()
                .seriesTmdbId(seriesTmdbId)
                .seasonNumber(seasonNumber)
                .type(ContentType.SEASON)
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
        saveEntry(lucas, content, watchNumber, watchedDate, createdAt);
    }

    private void saveEntry(User user, Content content, int watchNumber, LocalDate watchedDate, LocalDateTime createdAt) {
        diaryEntryRepository.saveAndFlush(DiaryEntry.builder()
                .user(user)
                .content(content)
                .watchNumber(watchNumber)
                .watchedDate(watchedDate)
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .build());
    }

    private void saveProgressSeries(String seriesTmdbId, int watchedEpisode, int releasedEpisodes,
            Integer totalRuntime, LocalDate lastRelease) {
        Content episode = saveEpisode(seriesTmdbId, 1, watchedEpisode, 40);
        saveEntry(episode, 1, LocalDate.of(2024, 1, 1), LocalDateTime.of(2024, 1, 1, 10, 0));
        saveMetadata(seriesTmdbId, releasedEpisodes, totalRuntime, lastRelease);
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
