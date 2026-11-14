package com.watchwise.watchwise_api.diaryentry.repository;

import com.watchwise.watchwise_api.WatchwiseApiApplication;
import com.watchwise.watchwise_api.calendar.entity.CalendarScheduleCompleteness;
import com.watchwise.watchwise_api.calendar.repository.CalendarScheduleCompletenessRepository;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.diarycompletion.entity.DiaryCompletionProjection;
import com.watchwise.watchwise_api.diarycompletion.entity.UserSeriesHistoryState;
import com.watchwise.watchwise_api.diarycompletion.repository.DiaryCompletionProjectionRepository;
import com.watchwise.watchwise_api.diarycompletion.repository.UserSeriesHistoryStateRepository;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryViewFilter;
import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@org.springframework.test.context.ContextConfiguration(classes = WatchwiseApiApplication.class)
@Import(DiaryViewRepositoryImpl.class)
@Testcontainers
class DiaryViewRatingTargetRepositoryTest {

    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final LocalDate COMPLETED_ON = LocalDate.of(2026, 11, 4);
    private static final Instant VALID_UNTIL = Instant.parse("2099-01-01T00:00:00Z");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.docker.compose.enabled", () -> "false");
    }

    @Autowired private DiaryViewRepository diaryViewRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ContentRepository contentRepository;
    @Autowired private DiaryEntryRepository diaryEntryRepository;
    @Autowired private DiaryCompletionProjectionRepository projectionRepository;
    @Autowired private UserSeriesHistoryStateRepository historyStateRepository;
    @Autowired private CalendarScheduleCompletenessRepository completenessRepository;

    @Test
    void returnsSeriesContentIdWhenGroupedRunContainsSeriesCompletion() {
        User owner = user();
        String seriesTmdbId = "910004";
        Content series = content(ContentType.SERIES, seriesTmdbId, null, null);
        Content episode = content(ContentType.EPISODE, null, seriesTmdbId, 1);
        DiaryEntry anchor = episode(owner, episode, 1);
        history(owner, seriesTmdbId);
        seriesMarker(seriesTmdbId);
        projection(owner, series, anchor, ContentType.SERIES, 1);

        var page = groupedPage(owner);

        assertThat(page.getContent()).singleElement().satisfies(group -> {
            assertThat(group.groupType()).isEqualTo(ContentType.SERIES);
            assertThat(group.seriesContentId()).isEqualTo(series.getId());
            assertThat(group.ratingContentId()).isEqualTo(series.getId());
        });
    }

    @Test
    void returnsSeasonContentIdWhenGroupedRunContainsOneCompletedSeason() {
        User owner = user();
        String seriesTmdbId = "910005";
        Content series = content(ContentType.SERIES, seriesTmdbId, null, null);
        Content season = content(ContentType.SEASON, null, seriesTmdbId, 1);
        Content episode = content(ContentType.EPISODE, null, seriesTmdbId, 1);
        DiaryEntry anchor = episode(owner, episode, 1);
        projection(owner, season, anchor, ContentType.SEASON, 1);
        history(owner, seriesTmdbId);
        seasonMarker(seriesTmdbId, 1);

        var page = groupedPage(owner);

        assertThat(page.getContent()).singleElement().satisfies(group -> {
            assertThat(group.groupType()).isEqualTo(ContentType.SEASON);
            assertThat(group.seriesContentId()).isEqualTo(series.getId());
            assertThat(group.seasonCount()).isEqualTo(1);
            assertThat(group.ratingContentId()).isEqualTo(season.getId());
        });
    }

    @Test
    void omitsSeasonRatingTargetWhenGroupedRunContainsMultipleSeasons() {
        User owner = user();
        String seriesTmdbId = "910006";
        Content series = content(ContentType.SERIES, seriesTmdbId, null, null);
        for (int seasonNumber = 1; seasonNumber <= 2; seasonNumber++) {
            Content season = content(ContentType.SEASON, null, seriesTmdbId, seasonNumber);
            Content episode = content(ContentType.EPISODE, null, seriesTmdbId, seasonNumber);
            DiaryEntry anchor = episode(owner, episode, 1);
            projection(owner, season, anchor, ContentType.SEASON, 1);
            seasonMarker(seriesTmdbId, seasonNumber);
        }
        history(owner, seriesTmdbId);

        var page = groupedPage(owner);

        assertThat(page.getContent()).singleElement().satisfies(group -> {
            assertThat(group.groupType()).isEqualTo(ContentType.SEASON);
            assertThat(group.seriesContentId()).isEqualTo(series.getId());
            assertThat(group.seasonCount()).isEqualTo(2);
            assertThat(group.ratingContentId()).isNull();
        });
    }

    private User user() {
        LocalDateTime now = LocalDateTime.now();
        return userRepository.saveAndFlush(User.builder()
                .username("rating-target-" + UUID.randomUUID())
                .name("Rating Target")
                .email(UUID.randomUUID() + "@example.test")
                .password("hashed")
                .preferredRegion("US")
                .preferredLanguage("en-US")
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    private Content content(ContentType type, String tmdbId, String seriesTmdbId, Integer seasonNumber) {
        LocalDateTime now = LocalDateTime.now();
        return contentRepository.saveAndFlush(Content.builder()
                .type(type)
                .tmdbId(tmdbId)
                .seriesTmdbId(seriesTmdbId)
                .seasonNumber(seasonNumber)
                .episodeNumber(type == ContentType.EPISODE ? 1 : null)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    private DiaryEntry episode(User owner, Content content, int watchNumber) {
        LocalDateTime now = LocalDateTime.of(2026, 11, 4, 12, 0);
        return diaryEntryRepository.saveAndFlush(DiaryEntry.builder()
                .user(owner)
                .content(content)
                .watchNumber(watchNumber)
                .watchedDate(COMPLETED_ON)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    private void projection(User owner, Content season, DiaryEntry anchor, int watchNumber) {
        projection(owner, season, anchor, ContentType.SEASON, watchNumber);
    }

    private void projection(User owner, Content content, DiaryEntry anchor, ContentType contentType, int watchNumber) {
        projectionRepository.saveAndFlush(DiaryCompletionProjection.builder()
                .userId(owner.getId())
                .contentId(content.getId())
                .contentType(contentType)
                .watchNumber(watchNumber)
                .anchorDiaryEntryId(anchor.getId())
                .completedOn(COMPLETED_ON)
                .orderedAt(Instant.parse("2026-11-04T12:00:00Z"))
                .historyRevision(1L)
                .scheduleVersion(0L)
                .validUntil(VALID_UNTIL)
                .build());
    }

    private void history(User owner, String seriesTmdbId) {
        historyStateRepository.saveAndFlush(UserSeriesHistoryState.builder()
                .id(new UserSeriesHistoryState.Key(owner.getId(), seriesTmdbId))
                .historyRevision(1L)
                .computedRevision(1L)
                .scheduleVersion(0L)
                .validUntil(VALID_UNTIL)
                .build());
    }

    private void seasonMarker(String seriesTmdbId, int seasonNumber) {
        marker(seriesTmdbId, CalendarScheduleCompleteness.GroupType.SEASON, seasonNumber);
    }

    private void seriesMarker(String seriesTmdbId) {
        marker(seriesTmdbId, CalendarScheduleCompleteness.GroupType.SERIES, null);
    }

    private void marker(String seriesTmdbId, CalendarScheduleCompleteness.GroupType groupType, Integer seasonNumber) {
        completenessRepository.saveAndFlush(CalendarScheduleCompleteness.builder()
                .groupType(groupType)
                .seriesTmdbId(seriesTmdbId)
                .seasonNumber(seasonNumber)
                .region("US")
                .language("en-US")
                .expectedEpisodeCount(1)
                .complete(true)
                .scopeVersion(0L)
                .lastCheckedAt(LocalDateTime.now())
                .build());
    }

    private org.springframework.data.domain.Page<DiaryViewRepository.GroupedDiaryViewKey> groupedPage(User owner) {
        return diaryViewRepository.findGroupedPage(owner.getId(), "US", "en-US", Instant.now(),
                new DiaryViewFilter(null, null, null, null, null, null),
                org.springframework.data.domain.PageRequest.of(0, 20));
    }
}
