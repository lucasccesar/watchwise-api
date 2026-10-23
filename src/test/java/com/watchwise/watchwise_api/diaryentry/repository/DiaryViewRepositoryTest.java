package com.watchwise.watchwise_api.diaryentry.repository;

import com.watchwise.watchwise_api.calendar.entity.CalendarScheduleCompleteness;
import com.watchwise.watchwise_api.calendar.repository.CalendarScheduleCompletenessRepository;
import com.watchwise.watchwise_api.common.security.CookieUtil;
import com.watchwise.watchwise_api.content.dto.MediaReference;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.diarycompletion.entity.UserSeriesHistoryState;
import com.watchwise.watchwise_api.diarycompletion.model.CompletionScope;
import com.watchwise.watchwise_api.diarycompletion.model.EpisodeCoordinate;
import com.watchwise.watchwise_api.diarycompletion.model.VersionedCompletionScope;
import com.watchwise.watchwise_api.diarycompletion.repository.UserSeriesHistoryStateRepository;
import com.watchwise.watchwise_api.diarycompletion.service.CompletionScopeLoader;
import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.platformimport.parser.impl.LetterboxdItemCsvParserImpl;
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

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

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
class DiaryViewRepositoryTest {

    private static final String SERIES_TMDB_ID = "1396";
    private static final LocalDateTime EPISODE_RECORDED_AT = LocalDateTime.of(2026, 10, 20, 12, 0);

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
    private UserSeriesHistoryStateRepository historyStateRepository;

    @Autowired
    private CalendarScheduleCompletenessRepository completenessRepository;

    @MockitoBean
    private CompletionScopeLoader completionScopeLoader;

    @MockitoBean
    private LetterboxdItemCsvParserImpl letterboxdItemCsvParser;

    @Test
    @DisplayName("[diaryView] Should page an episode with its season and series conclusions by anchor chronology")
    void shouldPageEpisodeWithItsSeasonAndSeriesConclusionsByAnchorChronology() throws Exception {
        RegisteredUser owner = registerUser();
        User user = userRepository.findById(owner.id()).orElseThrow();
        Content series = persistContent(ContentType.SERIES, SERIES_TMDB_ID, null, null, null);
        Content season = persistContent(ContentType.SEASON, null, SERIES_TMDB_ID, 1, null);
        Content episode = persistContent(ContentType.EPISODE, null, SERIES_TMDB_ID, 1, 1);
        Content movie = persistContent(ContentType.MOVIE, "550", null, null, null);

        DiaryEntry importedEpisode = persistEntry(user, episode, LocalDate.of(2020, 1, 2), EPISODE_RECORDED_AT);
        persistEntry(user, movie, LocalDate.of(2025, 5, 1), EPISODE_RECORDED_AT.minusDays(1));
        historyStateRepository.saveAndFlush(UserSeriesHistoryState.builder()
                .id(new UserSeriesHistoryState.Key(owner.id(), SERIES_TMDB_ID))
                .historyRevision(1L)
                .computedRevision(0L)
                .scheduleVersion(0L)
                .build());
        completenessRepository.saveAndFlush(marker(CalendarScheduleCompleteness.GroupType.SERIES, null));
        completenessRepository.saveAndFlush(marker(CalendarScheduleCompleteness.GroupType.SEASON, 1));

        when(completionScopeLoader.load(any(), anyString(), anyString(), any()))
                .thenAnswer(invocation -> {
                    MediaReference reference = invocation.getArgument(0);
                    return new VersionedCompletionScope(
                            new CompletionScope(reference, Set.of(new EpisodeCoordinate(1, 1)), true),
                            "scope-" + reference.type(),
                            Instant.parse("2099-01-01T00:00:00Z"));
                });

        mockMvc.perform(get("/users/{userId}/diary/view", owner.id())
                        .cookie(owner.accessToken())
                        .param("page", "0")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].kind").value("PERSISTED_ENTRY"))
                .andExpect(jsonPath("$.content[0].diaryEntryId").value(importedEpisode.getId().toString()))
                .andExpect(jsonPath("$.content[0].content.type").value("EPISODE"))
                .andExpect(jsonPath("$.content[1].kind").value("DERIVED_COMPLETION"))
                .andExpect(jsonPath("$.content[1].content.type").value("SEASON"));

        mockMvc.perform(get("/users/{userId}/diary/view", owner.id())
                        .cookie(owner.accessToken())
                        .param("page", "2")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].kind").value("DERIVED_COMPLETION"))
                .andExpect(jsonPath("$.content[0].content.type").value("SERIES"))
                .andExpect(jsonPath("$.content[1].kind").value("PERSISTED_ENTRY"))
                .andExpect(jsonPath("$.content[1].content.type").value("MOVIE"));
    }

    private RegisteredUser registerUser() throws Exception {
        String username = "diaryview" + UUID.randomUUID().toString().replace("-", "");
        MvcResult result = mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "%s",
                                  "name": "Diary View",
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

    private Content persistContent(
            ContentType type,
            String tmdbId,
            String seriesTmdbId,
            Integer seasonNumber,
            Integer episodeNumber) {
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

    private DiaryEntry persistEntry(User user, Content content, LocalDate watchedDate, LocalDateTime createdAt) {
        return diaryEntryRepository.saveAndFlush(DiaryEntry.builder()
                .user(user)
                .content(content)
                .watchNumber(1)
                .watchedDate(watchedDate)
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .autoGenerated(false)
                .ignore(false)
                .build());
    }

    private CalendarScheduleCompleteness marker(
            CalendarScheduleCompleteness.GroupType type,
            Integer seasonNumber) {
        return CalendarScheduleCompleteness.builder()
                .groupType(type)
                .seriesTmdbId(SERIES_TMDB_ID)
                .seasonNumber(seasonNumber)
                .region("US")
                .language("en-US")
                .expectedEpisodeCount(1)
                .complete(true)
                .lastCheckedAt(EPISODE_RECORDED_AT)
                .build();
    }

    private record RegisteredUser(UUID id, Cookie accessToken) {
    }
}
