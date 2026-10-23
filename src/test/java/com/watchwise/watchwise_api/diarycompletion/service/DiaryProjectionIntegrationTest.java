package com.watchwise.watchwise_api.diarycompletion.service;

import com.watchwise.watchwise_api.calendar.entity.CalendarScheduleCompleteness;
import com.watchwise.watchwise_api.calendar.repository.CalendarScheduleCompletenessRepository;
import com.watchwise.watchwise_api.common.security.CookieUtil;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.diarycompletion.entity.DiaryCompletionProjection;
import com.watchwise.watchwise_api.diarycompletion.entity.UserSeriesHistoryState;
import com.watchwise.watchwise_api.diarycompletion.model.CompletionScope;
import com.watchwise.watchwise_api.diarycompletion.model.EpisodeCoordinate;
import com.watchwise.watchwise_api.diarycompletion.model.VersionedCompletionScope;
import com.watchwise.watchwise_api.diarycompletion.repository.DiaryCompletionProjectionRepository;
import com.watchwise.watchwise_api.diarycompletion.repository.UserSeriesHistoryStateRepository;
import com.watchwise.watchwise_api.platformimport.parser.impl.LetterboxdItemCsvParserImpl;
import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class DiaryProjectionIntegrationTest {

    private static final String SERIES_TMDB_ID = "1396";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.docker.compose.enabled", () -> "false");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ContentRepository contentRepository;

    @Autowired
    private DiaryEntryRepository diaryEntryRepository;

    @Autowired
    private DiaryCompletionProjectionRepository projectionRepository;

    @Autowired
    private UserSeriesHistoryStateRepository historyStateRepository;

    @Autowired
    private CalendarScheduleCompletenessRepository completenessRepository;

    @MockitoBean
    private CompletionScopeLoader completionScopeLoader;

    @MockitoBean
    private LetterboxdItemCsvParserImpl letterboxdItemCsvParser;

    @Test
    @DisplayName("[diaryProjection] Should Return An Empty Timeline For An Owner With No Diary Activity")
    void shouldReturnAnEmptyTimelineForAnOwnerWithNoDiaryActivity() throws Exception {
        RegisteredUser owner = registerUser();

        mockMvc.perform(get("/users/{userId}/diary/view", owner.id()).cookie(owner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @DisplayName("[refreshStaleScopes] Should Publish Fresh Completion Projections Without Writing Diary Rows - When The Cached Scope Has Expired")
    void shouldPublishFreshCompletionProjectionsWithoutWritingDiaryRowsWhenCachedScopeHasExpired() throws Exception {
        RegisteredUser owner = registerUser();
        User user = userRepository.findById(owner.id()).orElseThrow();
        Content series = persistContent(ContentType.SERIES, SERIES_TMDB_ID, null, null, null);
        Content season = persistContent(ContentType.SEASON, null, SERIES_TMDB_ID, 1, null);
        Content episode = persistContent(ContentType.EPISODE, null, SERIES_TMDB_ID, 1, 1);
        persistEntry(user, episode, LocalDate.of(2026, 1, 1));
        historyStateRepository.saveAndFlush(UserSeriesHistoryState.builder()
                .id(new UserSeriesHistoryState.Key(owner.id(), SERIES_TMDB_ID))
                .historyRevision(1L)
                .computedRevision(1L)
                .scheduleVersion(1L)
                .validUntil(Instant.parse("2000-01-01T00:00:00Z"))
                .build());
        completenessRepository.saveAndFlush(marker(CalendarScheduleCompleteness.GroupType.SERIES, null));
        completenessRepository.saveAndFlush(marker(CalendarScheduleCompleteness.GroupType.SEASON, 1));

        when(completionScopeLoader.load(any(), anyString(), anyString(), any()))
                .thenAnswer(invocation -> {
                    var reference = invocation.getArgument(0, com.watchwise.watchwise_api.content.dto.MediaReference.class);
                    return new VersionedCompletionScope(
                            new CompletionScope(reference, Set.of(new EpisodeCoordinate(1, 1)), true),
                            reference.type() == ContentType.SERIES ? "series-hash" : "season-hash",
                            Instant.parse("2099-01-01T00:00:00Z"));
                });

        mockMvc.perform(get("/users/{userId}/diary/view", owner.id()).cookie(owner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[0].kind").value("PERSISTED_ENTRY"))
                .andExpect(jsonPath("$.content[1].kind").value("DERIVED_COMPLETION"))
                .andExpect(jsonPath("$.content[1].content.type").value("SEASON"))
                .andExpect(jsonPath("$.content[2].content.type").value("SERIES"));

        assertThat(diaryEntryRepository.findByUserIdOrderByCreatedAtDesc(owner.id(),
                org.springframework.data.domain.PageRequest.of(0, 20)).getTotalElements()).isEqualTo(1);
        assertThat(projectionRepository.findByUserIdAndContentIdOrderByWatchNumber(owner.id(), season.getId()))
                .hasSize(1);
        assertThat(projectionRepository.findByUserIdAndContentIdOrderByWatchNumber(owner.id(), series.getId()))
                .hasSize(1);
    }

    @Test
    @DisplayName("[refreshStaleScopes] Should Refresh A Season Projection When Only The Season Scope Version Changes")
    void shouldRefreshASeasonProjectionWhenOnlyTheSeasonScopeVersionChanges() throws Exception {
        RegisteredUser owner = registerUser();
        User user = userRepository.findById(owner.id()).orElseThrow();
        String seriesTmdbId = "3999";
        Content series = persistContent(ContentType.SERIES, seriesTmdbId, null, null, null);
        Content season = persistContent(ContentType.SEASON, null, seriesTmdbId, 1, null);
        Content episode = persistContent(ContentType.EPISODE, null, seriesTmdbId, 1, 1);
        DiaryEntry anchor = persistEntry(user, episode, LocalDate.of(2026, 1, 1));
        historyStateRepository.saveAndFlush(UserSeriesHistoryState.builder()
                .id(new UserSeriesHistoryState.Key(owner.id(), seriesTmdbId))
                .historyRevision(1L)
                .computedRevision(1L)
                .scheduleVersion(10L)
                .validUntil(Instant.parse("2099-01-01T00:00:00Z"))
                .build());
        completenessRepository.saveAndFlush(markerForSeries(seriesTmdbId, CalendarScheduleCompleteness.GroupType.SERIES, null)
                .toBuilder().scopeVersion(10L).build());
        completenessRepository.saveAndFlush(markerForSeries(seriesTmdbId, CalendarScheduleCompleteness.GroupType.SEASON, 1)
                .toBuilder().scopeVersion(2L).build());
        projectionRepository.saveAndFlush(DiaryCompletionProjection.builder()
                .userId(owner.id())
                .contentId(season.getId())
                .contentType(ContentType.SEASON)
                .watchNumber(1)
                .anchorDiaryEntryId(anchor.getId())
                .completedOn(LocalDate.of(2026, 1, 1))
                .orderedAt(Instant.parse("2026-01-01T00:00:00Z"))
                .historyRevision(1L)
                .scheduleVersion(1L)
                .validUntil(Instant.parse("2099-01-01T00:00:00Z"))
                .build());

        when(completionScopeLoader.load(any(), anyString(), anyString(), any()))
                .thenAnswer(invocation -> {
                    var reference = invocation.getArgument(0, com.watchwise.watchwise_api.content.dto.MediaReference.class);
                    return new VersionedCompletionScope(
                            new CompletionScope(reference, Set.of(new EpisodeCoordinate(1, 1)), true),
                            reference.type() == ContentType.SERIES ? "series-hash" : "season-hash",
                            Instant.parse("2099-01-01T00:00:00Z"));
                });

        mockMvc.perform(get("/users/{userId}/diary/view", owner.id()).cookie(owner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3));

        assertThat(projectionRepository.findByUserIdAndContentIdOrderByWatchNumber(owner.id(), season.getId()))
                .singleElement()
                .extracting(DiaryCompletionProjection::getScheduleVersion)
                .isEqualTo(2L);
    }

    private Content persistContent(
            ContentType type, String tmdbId, String seriesTmdbId, Integer seasonNumber, Integer episodeNumber) {
        LocalDateTime now = LocalDateTime.now();
        return contentRepository.saveAndFlush(Content.builder()
                .type(type)
                .tmdbId(tmdbId)
                .seriesTmdbId(seriesTmdbId)
                .seasonNumber(seasonNumber)
                .episodeNumber(episodeNumber)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    private DiaryEntry persistEntry(User user, Content content, LocalDate watchedDate) {
        LocalDateTime now = LocalDateTime.now();
        return diaryEntryRepository.saveAndFlush(DiaryEntry.builder()
                .user(user)
                .content(content)
                .watchNumber(1)
                .watchedDate(watchedDate)
                .createdAt(now)
                .updatedAt(now)
                .autoGenerated(false)
                .ignore(false)
                .build());
    }

    private CalendarScheduleCompleteness marker(
            CalendarScheduleCompleteness.GroupType groupType, Integer seasonNumber) {
        return markerForSeries(SERIES_TMDB_ID, groupType, seasonNumber);
    }

    private CalendarScheduleCompleteness markerForSeries(
            String seriesTmdbId, CalendarScheduleCompleteness.GroupType groupType, Integer seasonNumber) {
        return CalendarScheduleCompleteness.builder()
                .groupType(groupType)
                .seriesTmdbId(seriesTmdbId)
                .seasonNumber(seasonNumber)
                .region("US")
                .language("en-US")
                .expectedEpisodeCount(1)
                .complete(true)
                .lastCheckedAt(LocalDateTime.now())
                .build();
    }

    private RegisteredUser registerUser() throws Exception {
        String username = "projection" + UUID.randomUUID().toString().replace("-", "");
        MvcResult result = mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "%s",
                                  "name": "Diary Projection",
                                  "email": "%s@example.com",
                                  "password": "Password123",
                                  "isProfilePublic": true
                                }
                                """.formatted(username, username)))
                .andExpect(status().isCreated())
                .andReturn();
        Cookie accessToken = result.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        User user = userRepository.findByUsernameIgnoreCaseOrEmailIgnoreCase(username, username).orElseThrow();
        return new RegisteredUser(user.getId(), accessToken);
    }

    private record RegisteredUser(UUID id, Cookie accessToken) {
    }
}
