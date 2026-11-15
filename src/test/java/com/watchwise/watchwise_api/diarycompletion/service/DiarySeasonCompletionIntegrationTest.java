package com.watchwise.watchwise_api.diarycompletion.service;

import com.jayway.jsonpath.JsonPath;
import com.watchwise.watchwise_api.calendar.entity.CalendarScheduleCompleteness;
import com.watchwise.watchwise_api.calendar.entity.CalendarScheduleSnapshot;
import com.watchwise.watchwise_api.calendar.repository.CalendarScheduleCompletenessRepository;
import com.watchwise.watchwise_api.calendar.repository.CalendarScheduleSnapshotRepository;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbEpisodeSummary;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonSummary;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvContentRatings;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvFullDetails;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.diarycompletion.repository.DiaryCompletionProjectionRepository;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.user.service.TestEmailConfirmationSender;
import com.watchwise.watchwise_api.user.service.TestRegistrationHelper;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = com.watchwise_test.WatchwiseIntegrationTestApplication.class)
@AutoConfigureMockMvc
@Testcontainers
class DiarySeasonCompletionIntegrationTest {

    private static final String SERIES_TMDB_ID = "1421";
    private static final int SEASON_NUMBER = 1;

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
    private TestEmailConfirmationSender emailConfirmationSender;
    @Autowired
    private ContentRepository contentRepository;
    @Autowired
    private DiaryEntryRepository diaryEntryRepository;
    @Autowired
    private DiaryCompletionProjectionRepository projectionRepository;
    @Autowired
    private CalendarScheduleCompletenessRepository completenessRepository;
    @Autowired
    private CalendarScheduleSnapshotRepository snapshotRepository;

    @MockitoBean
    private TmdbClient tmdbClient;

    @Test
    @DisplayName("[diarySeasonCompletion] Should Recognize A 24-Episode Season Across Bulk, Diary And Content Page")
    void shouldRecognizeA24EpisodeSeasonAcrossBulkDiaryAndContentPage() throws Exception {
        String username = "seasoncompletion" + System.nanoTime();
        String email = username + "@example.com";
        TestRegistrationHelper.RegisteredTestUser owner = TestRegistrationHelper.register(
                mockMvc, userRepository, emailConfirmationSender, username, "Season Completion", email, true);
        var user = userRepository.findById(owner.id()).orElseThrow();
        LocalDate watchedDate = LocalDate.of(2026, 9, 21);
        LocalDateTime now = LocalDateTime.now();
        Content series = contentRepository.saveAndFlush(Content.builder()
                .tmdbId(SERIES_TMDB_ID)
                .type(ContentType.SERIES)
                .createdAt(now)
                .updatedAt(now)
                .build());
        TmdbSeasonFullDetails seasonDetails = seasonDetails();
        when(tmdbClient.getSeasonFullDetails(SERIES_TMDB_ID, SEASON_NUMBER, user.getPreferredLanguage()))
                .thenReturn(new TmdbLookupResult.Found<>(seasonDetails));
        when(tmdbClient.getTvFullDetails(SERIES_TMDB_ID, user.getPreferredLanguage()))
                .thenReturn(new TmdbLookupResult.Found<>(seriesDetails()));
        when(tmdbClient.getTvContentRatings(SERIES_TMDB_ID, user.getPreferredLanguage()))
                .thenReturn(new TmdbLookupResult.Found<>(new TmdbTvContentRatings(SERIES_TMDB_ID, List.of())));

        MvcResult bulkResult = mockMvc.perform(post("/diary/bulk")
                        .cookie(owner.accessToken(), owner.csrfToken())
                        .header("X-XSRF-TOKEN", owner.csrfToken().getValue())
                        .header("Idempotency-Key", "modern-family-season-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "content": {
                                    "type": "SEASON",
                                    "seriesTmdbId": "%s",
                                    "seasonNumber": %d
                                  },
                                  "watchedDate": "%s"
                                }
                                """.formatted(SERIES_TMDB_ID, SEASON_NUMBER, watchedDate)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.events.length()").value(24))
                .andExpect(jsonPath("$.completions.length()").value(1))
                .andReturn();

        Content season = contentRepository.findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType(
                SERIES_TMDB_ID, SEASON_NUMBER, null, ContentType.SEASON).orElseThrow();
        assertThat(JsonPath.<String>read(bulkResult.getResponse().getContentAsString(),
                "$.completions[0].contentId")).isEqualTo(season.getId().toString());
        assertThat(diaryEntryRepository.findEpisodeCompletionWatches(owner.id(), List.of(SERIES_TMDB_ID)))
                .hasSize(24);
        assertThat(completenessRepository.findSeasonIdentity(
                SERIES_TMDB_ID, SEASON_NUMBER, user.getPreferredRegion(), user.getPreferredLanguage()))
                .get()
                .satisfies(marker -> {
                    assertThat(marker.getComplete()).isTrue();
                    assertThat(marker.getExpectedEpisodeCount()).isEqualTo(24);
                });
        assertThat(snapshotRepository.findByEventTypeAndSeriesTmdbIdAndSeasonNumberAndRegionAndLanguage(
                CalendarScheduleSnapshot.EventType.EPISODE,
                SERIES_TMDB_ID,
                SEASON_NUMBER,
                user.getPreferredRegion(),
                user.getPreferredLanguage())).hasSize(24);
        verify(tmdbClient, times(1)).getSeasonFullDetails(
                SERIES_TMDB_ID, SEASON_NUMBER, user.getPreferredLanguage());
        verify(tmdbClient, never()).getCalendarSeasonDetails(
                SERIES_TMDB_ID, SEASON_NUMBER, user.getPreferredLanguage());

        mockMvc.perform(get("/users/{userId}/diary/view", owner.id())
                        .param("grouped", "true")
                        .cookie(owner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].groupType").value("SEASON"))
                .andExpect(jsonPath("$.content[0].episodeCount").value(24))
                .andExpect(jsonPath("$.content[0].seasonNumbers[0]").value(SEASON_NUMBER))
                .andExpect(jsonPath("$.content[0].ratingContentId").value(season.getId().toString()));
        mockMvc.perform(get("/users/{userId}/diary/view", owner.id())
                        .param("type", "SEASON")
                        .param("grouped", "false")
                        .cookie(owner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].kind").value("DERIVED_COMPLETION"))
                .andExpect(jsonPath("$.content[0].content.type").value("SEASON"));

        mockMvc.perform(get("/contents/{contentId}/page", season.getId()).cookie(owner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.viewerState.watchStatus").value("WATCHED"))
                .andExpect(jsonPath("$.sections.seasons[0].viewerState.watchStatus").value("WATCHED"));
        mockMvc.perform(get("/contents/{contentId}/page", series.getId()).cookie(owner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections.seasons[0].seasonNumber").value(SEASON_NUMBER))
                .andExpect(jsonPath("$.sections.seasons[0].viewerState.watchStatus").value("WATCHED"));

        assertThat(projectionRepository.findByUserIdAndContentIdOrderByWatchNumber(owner.id(), series.getId()))
                .isEmpty();
        verify(tmdbClient, never()).getCalendarSeasonDetails(
                SERIES_TMDB_ID, SEASON_NUMBER, user.getPreferredLanguage());
    }

    private TmdbSeasonFullDetails seasonDetails() {
        List<TmdbEpisodeSummary> episodes = java.util.stream.IntStream.rangeClosed(1, 24)
                .mapToObj(episodeNumber -> new TmdbEpisodeSummary(
                        episodeNumber,
                        "Episode " + episodeNumber,
                        null,
                        "2026-09-01",
                        22,
                        "/still.jpg",
                        List.of()))
                .toList();
        return new TmdbSeasonFullDetails(
                101, "Season 1", null, "/season.jpg", "2009-09-23", 1, episodes, null, null);
    }

    private TmdbTvFullDetails seriesDetails() {
        return new TmdbTvFullDetails(
                SERIES_TMDB_ID,
                "Modern Family",
                "Modern Family",
                null,
                null,
                null,
                null,
                "2009-09-23",
                List.of(22),
                List.of(),
                List.of(),
                List.of(),
                List.of(
                        new TmdbSeasonSummary(1, "Season 1", null, "2009-09-23", 24, "/season-1.jpg"),
                        new TmdbSeasonSummary(2, "Season 2", null, "2010-09-22", 1, "/season-2.jpg")),
                null,
                null,
                null,
                null,
                2,
                25,
                List.of(),
                null,
                "Returning Series",
                null,
                List.of(),
                null);
    }
}
