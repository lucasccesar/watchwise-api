package com.watchwise.watchwise_api.diaryentry.repository;

import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import com.watchwise.watchwise_api.diaryentry.entity.WatchCompanion;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class WatchCompanionRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private WatchCompanionRepository watchCompanionRepository;

    @Autowired
    private DiaryEntryRepository diaryEntryRepository;

    @Autowired
    private ContentRepository contentRepository;

    @Autowired
    private UserRepository userRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private User lucas;
    private User marina;

    @BeforeEach
    void setUp() {
        watchCompanionRepository.deleteAll();
        diaryEntryRepository.deleteAll();
        contentRepository.deleteAll();
        userRepository.deleteAll();

        lucas = userRepository.save(buildUser("lucas", "lucas@email.com"));
        marina = userRepository.save(buildUser("marina", "marina@email.com"));
    }

    @Test
    @DisplayName("[countGroupedByCompanionUserIdAndContentTypeAndWatchedDateBetween] Should Count Multiple Tags Including A Rewatch - When Same Companion Is Tagged Twice")
    void shouldCountMultipleTagsIncludingARewatchWhenSameCompanionIsTaggedTwice() {
        Content movie = contentRepository.save(buildContent("550", ContentType.MOVIE));
        LocalDate today = LocalDate.now();
        DiaryEntry firstWatch = diaryEntryRepository.saveAndFlush(buildEntry(lucas, movie, today, 1));
        DiaryEntry rewatch = diaryEntryRepository.saveAndFlush(buildEntry(lucas, movie, today, 2));
        watchCompanionRepository.saveAndFlush(buildCompanion(firstWatch, marina));
        watchCompanionRepository.saveAndFlush(buildCompanion(rewatch, marina));
        entityManager.clear();

        List<WatchCompanionRepository.CompanionWatchCount> result = watchCompanionRepository
                .countGroupedByCompanionUserIdAndContentTypeAndWatchedDateBetween(
                        lucas.getId(), ContentType.MOVIE, today.minusDays(1), today.plusDays(1), PageRequest.of(0, 10));

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getCompanionUserId()).isEqualTo(marina.getId());
        assertThat(result.getFirst().getCount()).isEqualTo(2L);
    }

    @Test
    @DisplayName("[countGroupedByCompanionUserIdAndContentTypeAndWatchedDateBetween] Should Exclude Tag On A SEASON Completion Marker - When Filtering By MOVIE")
    void shouldExcludeTagOnASeasonCompletionMarkerWhenFilteringByMovie() {
        Content season = contentRepository.save(buildSeason("1399", 1));
        LocalDate today = LocalDate.now();
        DiaryEntry seasonEntry = diaryEntryRepository.saveAndFlush(buildEntry(lucas, season, today, 1));
        watchCompanionRepository.saveAndFlush(buildCompanion(seasonEntry, marina));
        entityManager.clear();

        List<WatchCompanionRepository.CompanionWatchCount> result = watchCompanionRepository
                .countGroupedByCompanionUserIdAndContentTypeAndWatchedDateBetween(
                        lucas.getId(), ContentType.MOVIE, today.minusDays(1), today.plusDays(1), PageRequest.of(0, 10));

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("[countGroupedByCompanionUserIdAndContentTypeAndWatchedDateBetween] Should Exclude Tag On A SERIES Completion Marker - When Filtering By EPISODE")
    void shouldExcludeTagOnASeriesCompletionMarkerWhenFilteringByEpisode() {
        Content series = contentRepository.save(buildContent("1399", ContentType.SERIES));
        LocalDate today = LocalDate.now();
        DiaryEntry seriesEntry = diaryEntryRepository.saveAndFlush(buildEntry(lucas, series, today, 1));
        watchCompanionRepository.saveAndFlush(buildCompanion(seriesEntry, marina));
        entityManager.clear();

        List<WatchCompanionRepository.CompanionWatchCount> result = watchCompanionRepository
                .countGroupedByCompanionUserIdAndContentTypeAndWatchedDateBetween(
                        lucas.getId(), ContentType.EPISODE, today.minusDays(1), today.plusDays(1), PageRequest.of(0, 10));

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("[countGroupedByCompanionUserIdAndContentTypeAndWatchedDateBetween] Should Exclude Tag Outside The Given Window - When WatchedDate Is Before Start")
    void shouldExcludeTagOutsideTheGivenWindowWhenWatchedDateIsBeforeStart() {
        Content movie = contentRepository.save(buildContent("550", ContentType.MOVIE));
        LocalDate outsideWindow = LocalDate.of(2024, 1, 1);
        DiaryEntry entry = diaryEntryRepository.saveAndFlush(buildEntry(lucas, movie, outsideWindow, 1));
        watchCompanionRepository.saveAndFlush(buildCompanion(entry, marina));
        entityManager.clear();

        List<WatchCompanionRepository.CompanionWatchCount> result = watchCompanionRepository
                .countGroupedByCompanionUserIdAndContentTypeAndWatchedDateBetween(
                        lucas.getId(), ContentType.MOVIE, LocalDate.of(2024, 6, 1), LocalDate.of(2024, 6, 30), PageRequest.of(0, 10));

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("[countGroupedByCompanionUserIdAndContentTypeIn] Should Count Tags Across MOVIE And EPISODE Combined - When Same Companion Is Tagged On Both")
    void shouldCountTagsAcrossMovieAndEpisodeCombinedWhenSameCompanionIsTaggedOnBoth() {
        Content movie = contentRepository.save(buildContent("550", ContentType.MOVIE));
        Content episode = contentRepository.save(buildEpisode("1399", 1, 1));
        LocalDate today = LocalDate.now();
        DiaryEntry movieEntry = diaryEntryRepository.saveAndFlush(buildEntry(lucas, movie, today, 1));
        DiaryEntry episodeEntry = diaryEntryRepository.saveAndFlush(buildEntry(lucas, episode, today, 1));
        watchCompanionRepository.saveAndFlush(buildCompanion(movieEntry, marina));
        watchCompanionRepository.saveAndFlush(buildCompanion(episodeEntry, marina));
        entityManager.clear();

        List<WatchCompanionRepository.CompanionWatchCount> result = watchCompanionRepository
                .countGroupedByCompanionUserIdAndContentTypeIn(
                        lucas.getId(), Set.of(ContentType.MOVIE, ContentType.EPISODE), PageRequest.of(0, 10));

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getCompanionUserId()).isEqualTo(marina.getId());
        assertThat(result.getFirst().getCount()).isEqualTo(2L);
    }

    @Test
    @DisplayName("[countGroupedByCompanionUserIdAndContentTypeIn] Should Exclude Tag On A SEASON Or SERIES Completion Marker - When Filtering By MOVIE And EPISODE")
    void shouldExcludeTagOnASeasonOrSeriesCompletionMarkerWhenFilteringByMovieAndEpisode() {
        Content series = contentRepository.save(buildContent("1399", ContentType.SERIES));
        Content season = contentRepository.save(buildSeason("1396", 1));
        LocalDate today = LocalDate.now();
        DiaryEntry seriesEntry = diaryEntryRepository.saveAndFlush(buildEntry(lucas, series, today, 1));
        DiaryEntry seasonEntry = diaryEntryRepository.saveAndFlush(buildEntry(lucas, season, today, 1));
        watchCompanionRepository.saveAndFlush(buildCompanion(seriesEntry, marina));
        watchCompanionRepository.saveAndFlush(buildCompanion(seasonEntry, marina));
        entityManager.clear();

        List<WatchCompanionRepository.CompanionWatchCount> result = watchCompanionRepository
                .countGroupedByCompanionUserIdAndContentTypeIn(
                        lucas.getId(), Set.of(ContentType.MOVIE, ContentType.EPISODE), PageRequest.of(0, 10));

        assertThat(result).isEmpty();
    }

    private WatchCompanion buildCompanion(DiaryEntry diaryEntry, User user) {
        return WatchCompanion.builder()
                .diaryEntry(diaryEntry)
                .user(user)
                .createdAt(LocalDateTime.now())
                .build();
    }

    private DiaryEntry buildEntry(User user, Content content, LocalDate watchedDate, int watchNumber) {
        LocalDateTime now = LocalDateTime.now();
        return DiaryEntry.builder()
                .user(user)
                .content(content)
                .watchedDate(watchedDate)
                .watchNumber(watchNumber)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private Content buildContent(String tmdbId, ContentType type) {
        LocalDateTime now = LocalDateTime.now();
        return Content.builder()
                .tmdbId(tmdbId)
                .type(type)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private Content buildEpisode(String seriesTmdbId, Integer seasonNumber, Integer episodeNumber) {
        LocalDateTime now = LocalDateTime.now();
        return Content.builder()
                .seriesTmdbId(seriesTmdbId)
                .seasonNumber(seasonNumber)
                .episodeNumber(episodeNumber)
                .type(ContentType.EPISODE)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private Content buildSeason(String seriesTmdbId, Integer seasonNumber) {
        LocalDateTime now = LocalDateTime.now();
        return Content.builder()
                .seriesTmdbId(seriesTmdbId)
                .seasonNumber(seasonNumber)
                .type(ContentType.SEASON)
                .createdAt(now)
                .updatedAt(now)
                .build();
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
