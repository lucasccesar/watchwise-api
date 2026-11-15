package com.watchwise.watchwise_api.diarycompletion.service;

import com.watchwise.watchwise_api.calendar.entity.CalendarScheduleCompleteness;
import com.watchwise.watchwise_api.calendar.repository.CalendarScheduleCompletenessRepository;
import com.watchwise.watchwise_api.calendar.repository.CalendarScheduleSnapshotRepository;
import com.watchwise.watchwise_api.calendar.entity.CalendarScheduleSnapshot;
import com.watchwise.watchwise_api.calendar.service.impl.CalendarScheduleRefreshService;
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
import com.watchwise.watchwise_api.user.service.TestEmailConfirmationSender;
import com.watchwise.watchwise_api.user.service.TestRegistrationHelper;
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
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = com.watchwise_test.WatchwiseIntegrationTestApplication.class)
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
    private TestEmailConfirmationSender emailConfirmationSender;

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

    @Autowired
    private CalendarScheduleSnapshotRepository snapshotRepository;

    @MockitoBean
    private CompletionScopeLoader completionScopeLoader;

    @MockitoBean
    private LetterboxdItemCsvParserImpl letterboxdItemCsvParser;

    @MockitoBean
    private CalendarScheduleRefreshService calendarScheduleRefreshService;

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
    @DisplayName("[refreshStaleScopes] Should Rebuild Completion Projections From Surviving Episode Events - When History State Is Missing")
    void shouldRebuildCompletionProjectionsFromSurvivingEpisodeEventsWhenHistoryStateIsMissing() throws Exception {
        RegisteredUser owner = registerUser();
        User user = userRepository.findById(owner.id()).orElseThrow();
        String seriesTmdbId = "62001";
        Content series = persistContent(ContentType.SERIES, seriesTmdbId, null, null, null);
        Content season = persistContent(ContentType.SEASON, null, seriesTmdbId, 1, null);
        Content episode = persistContent(ContentType.EPISODE, null, seriesTmdbId, 1, 1);
        DiaryEntry watchedEpisode = persistEntry(user, episode, LocalDate.of(2026, 1, 1));
        completenessRepository.saveAndFlush(markerForSeries(
                seriesTmdbId, CalendarScheduleCompleteness.GroupType.SERIES, null));
        completenessRepository.saveAndFlush(markerForSeries(
                seriesTmdbId, CalendarScheduleCompleteness.GroupType.SEASON, 1));

        assertThat(historyStateRepository.findById(new UserSeriesHistoryState.Key(owner.id(), seriesTmdbId))).isEmpty();
        assertThat(projectionRepository.findByUserIdAndContentIdOrderByWatchNumber(owner.id(), series.getId())).isEmpty();
        assertThat(projectionRepository.findByUserIdAndContentIdOrderByWatchNumber(owner.id(), season.getId())).isEmpty();

        when(completionScopeLoader.load(any(), anyString(), anyString(), any()))
                .thenAnswer(invocation -> {
                    var reference = invocation.getArgument(0, com.watchwise.watchwise_api.content.dto.MediaReference.class);
                    return new VersionedCompletionScope(
                            new CompletionScope(reference, Set.of(new EpisodeCoordinate(1, 1)), true),
                            reference.type() == ContentType.SERIES ? "series-hash" : "season-hash",
                            Instant.parse("2099-01-01T00:00:00Z"));
                });

        mockMvc.perform(get("/users/{userId}/diary/view", owner.id()).cookie(owner.accessToken()))
                .andExpect(status().isOk());

        assertThat(historyStateRepository.findById(new UserSeriesHistoryState.Key(owner.id(), seriesTmdbId)))
                .isPresent();
        assertThat(projectionRepository.findByUserIdAndContentIdOrderByWatchNumber(owner.id(), season.getId()))
                .hasSize(1);
        assertThat(projectionRepository.findByUserIdAndContentIdOrderByWatchNumber(owner.id(), series.getId()))
                .hasSize(1);
        mockMvc.perform(get("/users/{userId}/diary/view", owner.id()).cookie(owner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3));

        assertThat(historyStateRepository.findById(new UserSeriesHistoryState.Key(owner.id(), seriesTmdbId)))
                .get()
                .satisfies(state -> {
                    assertThat(state.getHistoryRevision()).isZero();
                    assertThat(state.getComputedRevision()).isZero();
                    assertThat(state.getValidUntil()).isEqualTo(Instant.parse("2099-01-01T00:00:00Z"));
                });
        assertThat(projectionRepository.findByUserIdAndContentIdOrderByWatchNumber(owner.id(), season.getId()))
                .singleElement()
                .extracting(DiaryCompletionProjection::getAnchorDiaryEntryId)
                .isEqualTo(watchedEpisode.getId());
        assertThat(projectionRepository.findByUserIdAndContentIdOrderByWatchNumber(owner.id(), series.getId()))
                .singleElement()
                .extracting(DiaryCompletionProjection::getAnchorDiaryEntryId)
                .isEqualTo(watchedEpisode.getId());
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
                .andExpect(jsonPath("$.content[0].kind").value("DERIVED_COMPLETION"))
                .andExpect(jsonPath("$.content[0].content.type").value("SERIES"))
                .andExpect(jsonPath("$.content[1].kind").value("DERIVED_COMPLETION"))
                .andExpect(jsonPath("$.content[1].content.type").value("SEASON"))
                .andExpect(jsonPath("$.content[2].kind").value("PERSISTED_ENTRY"));

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

    @Test
    @DisplayName("[refreshStaleSeasonScopes] Should Project A Repaired Complete Season Once - When The Old Pass Had No Projection")
    void shouldProjectARepairedCompleteSeasonOnceWhenTheOldPassHadNoProjection() throws Exception {
        RegisteredUser owner = registerUser();
        User user = userRepository.findById(owner.id()).orElseThrow();
        String seriesId = "98" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        Content series = persistContent(ContentType.SERIES, seriesId, null, null, null);
        Content season = persistContent(ContentType.SEASON, null, seriesId, 1, null);
        Content episode = persistContent(ContentType.EPISODE, null, seriesId, 1, 1);
        persistEntry(user, episode, LocalDate.of(2026, 1, 1));
        CalendarScheduleCompleteness seriesMarker = markerForSeries(
                seriesId, CalendarScheduleCompleteness.GroupType.SERIES, null).toBuilder()
                .expectedEpisodeCount(1).scopeVersion(4L).build();
        CalendarScheduleCompleteness seasonMarker = markerForSeries(
                seriesId, CalendarScheduleCompleteness.GroupType.SEASON, 1).toBuilder()
                .expectedEpisodeCount(1).scopeVersion(7L).build();
        completenessRepository.saveAndFlush(seriesMarker);
        completenessRepository.saveAndFlush(seasonMarker);
        snapshotRepository.saveAndFlush(CalendarScheduleSnapshot.builder()
                .eventType(CalendarScheduleSnapshot.EventType.EPISODE)
                .seriesTmdbId(seriesId).seasonNumber(1).episodeNumber(1)
                .region(user.getPreferredRegion()).language(user.getPreferredLanguage())
                .releaseDate(LocalDate.of(2026, 1, 1)).title("Pilot")
                .lastCheckedAt(LocalDateTime.now())
                .nextCheckAt(LocalDateTime.of(2099, 1, 1, 0, 0))
                .presentInLastTmdbSnapshot(true).build());
        historyStateRepository.saveAndFlush(UserSeriesHistoryState.builder()
                .id(new UserSeriesHistoryState.Key(owner.id(), seriesId))
                .historyRevision(1L).computedRevision(1L).scheduleVersion(4L)
                .validUntil(Instant.parse("2099-01-01T00:00:00Z")).build());
        var seasonScope = new VersionedCompletionScope(
                new CompletionScope(com.watchwise.watchwise_api.content.dto.MediaReference.season(seriesId, 1),
                        Set.of(new EpisodeCoordinate(1, 1)), true),
                "7", Instant.parse("2099-01-01T00:00:00Z"));
        when(completionScopeLoader.loadPersistedSeasons(
                eq(seriesId), anyCollection(), eq(user.getPreferredRegion()), eq(user.getPreferredLanguage()), any()))
                .thenReturn(com.watchwise.watchwise_api.diarycompletion.service.CompletionScopeLoader.PersistedSeasonScopes
                        .supported(java.util.Map.of(1, seasonScope)));
        when(completionScopeLoader.loadPersistedSeries(
                eq(seriesId), eq(user.getPreferredRegion()), eq(user.getPreferredLanguage()), any()))
                .thenReturn(java.util.Optional.of(new VersionedCompletionScope(
                        new CompletionScope(com.watchwise.watchwise_api.content.dto.MediaReference.series(seriesId),
                                Set.of(new EpisodeCoordinate(1, 1)), true),
                        "4", Instant.parse("2099-01-01T00:00:00Z"))));

        var firstProjection = projectionRepository.findByUserIdAndContentIdOrderByWatchNumber(owner.id(), season.getId());
        assertThat(firstProjection).isEmpty();
        mockMvc.perform(get("/users/{userId}/diary/view", owner.id())
                        .param("type", "SEASON").cookie(owner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].kind").value("DERIVED_COMPLETION"));

        var projected = projectionRepository.findByUserIdAndContentIdOrderByWatchNumber(owner.id(), season.getId());
        assertThat(projected).hasSize(1);
        UUID projectionId = projected.getFirst().getId();
        Long projectionVersion = projected.getFirst().getVersion();
        mockMvc.perform(get("/users/{userId}/diary/view", owner.id())
                        .param("type", "SEASON").cookie(owner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        assertThat(projectionRepository.findByUserIdAndContentIdOrderByWatchNumber(owner.id(), season.getId()))
                .singleElement().satisfies(saved -> {
                    assertThat(saved.getId()).isEqualTo(projectionId);
                    assertThat(saved.getVersion()).isEqualTo(projectionVersion);
                });
        verify(completionScopeLoader, never()).load(any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("[refreshStaleSeasonScopes] Should Keep A Pass Incomplete - When One Aired Episode Is Missing")
    void shouldKeepASeasonPassIncompleteWhenOneAiredEpisodeIsMissing() throws Exception {
        RegisteredUser owner = registerUser();
        User user = userRepository.findById(owner.id()).orElseThrow();
        String seriesId = "97" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        Content season = persistContent(ContentType.SEASON, null, seriesId, 1, null);
        Content watchedEpisode = persistContent(ContentType.EPISODE, null, seriesId, 1, 1);
        persistEntry(user, watchedEpisode, LocalDate.of(2026, 1, 1));
        LocalDateTime now = LocalDateTime.now();
        completenessRepository.saveAndFlush(markerForSeries(
                seriesId, CalendarScheduleCompleteness.GroupType.SEASON, 1).toBuilder()
                .expectedEpisodeCount(2).scopeVersion(5L).lastCheckedAt(now).build());
        for (int episodeNumber = 1; episodeNumber <= 2; episodeNumber++) {
            snapshotRepository.saveAndFlush(CalendarScheduleSnapshot.builder()
                    .eventType(CalendarScheduleSnapshot.EventType.EPISODE)
                    .seriesTmdbId(seriesId).seasonNumber(1).episodeNumber(episodeNumber)
                    .region(user.getPreferredRegion()).language(user.getPreferredLanguage())
                    .releaseDate(LocalDate.of(2026, 1, episodeNumber)).title("Episode " + episodeNumber)
                    .lastCheckedAt(now).nextCheckAt(LocalDateTime.of(2099, 1, 1, 0, 0))
                    .presentInLastTmdbSnapshot(true).build());
        }
        when(completionScopeLoader.supportsPersistedSchedules()).thenReturn(true);
        when(completionScopeLoader.loadPersistedSeasons(
                eq(seriesId), anyCollection(), eq(user.getPreferredRegion()), eq(user.getPreferredLanguage()), any()))
                .thenReturn(com.watchwise.watchwise_api.diarycompletion.service.CompletionScopeLoader.PersistedSeasonScopes
                        .supported(java.util.Map.of(1, new VersionedCompletionScope(
                                new CompletionScope(
                                        com.watchwise.watchwise_api.content.dto.MediaReference.season(seriesId, 1),
                                        Set.of(new EpisodeCoordinate(1, 1), new EpisodeCoordinate(1, 2)), true),
                                "5", Instant.parse("2099-01-01T00:00:00Z")))));
        when(completionScopeLoader.loadPersistedSeries(
                eq(seriesId), eq(user.getPreferredRegion()), eq(user.getPreferredLanguage()), any()))
                .thenReturn(java.util.Optional.empty());

        mockMvc.perform(get("/users/{userId}/diary/view", owner.id())
                        .param("type", "SEASON").cookie(owner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        assertThat(projectionRepository.findByUserIdAndContentIdOrderByWatchNumber(owner.id(), season.getId()))
                .isEmpty();
        verify(completionScopeLoader, never()).load(any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("[refreshStaleSeasonScopes] Should Publish With The Persisted Marker Version - When It Appears During Loading")
    void shouldPublishWithPersistedMarkerVersionWhenMarkerAppearsDuringLoading() throws Exception {
        RegisteredUser owner = registerUser();
        User user = userRepository.findById(owner.id()).orElseThrow();
        String seriesId = "96" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        Content season = persistContent(ContentType.SEASON, null, seriesId, 1, null);
        Content episode = persistContent(ContentType.EPISODE, null, seriesId, 1, 1);
        persistEntry(user, episode, LocalDate.of(2026, 1, 1));
        historyStateRepository.saveAndFlush(UserSeriesHistoryState.builder()
                .id(new UserSeriesHistoryState.Key(owner.id(), seriesId))
                .historyRevision(1L).computedRevision(0L).scheduleVersion(0L)
                .validUntil(Instant.parse("2099-01-01T00:00:00Z")).build());
        LocalDateTime checkedAt = LocalDateTime.now();
        CalendarScheduleCompleteness marker = markerForSeries(
                seriesId, CalendarScheduleCompleteness.GroupType.SEASON, 1).toBuilder()
                .expectedEpisodeCount(1).scopeVersion(1L).lastCheckedAt(checkedAt).build();
        CalendarScheduleSnapshot snapshot = CalendarScheduleSnapshot.builder()
                .eventType(CalendarScheduleSnapshot.EventType.EPISODE)
                .seriesTmdbId(seriesId).seasonNumber(1).episodeNumber(1)
                .region(user.getPreferredRegion()).language(user.getPreferredLanguage())
                .releaseDate(LocalDate.of(2026, 1, 1)).title("Pilot")
                .lastCheckedAt(checkedAt).nextCheckAt(LocalDateTime.of(2099, 1, 1, 0, 0))
                .presentInLastTmdbSnapshot(true).build();
        var seasonScope = new VersionedCompletionScope(
                new CompletionScope(com.watchwise.watchwise_api.content.dto.MediaReference.season(seriesId, 1),
                        Set.of(new EpisodeCoordinate(1, 1)), true),
                "1", Instant.parse("2099-01-01T00:00:00Z"));
        when(completionScopeLoader.supportsPersistedSchedules()).thenReturn(true);
        when(completionScopeLoader.loadPersistedSeasons(
                eq(seriesId), anyCollection(), eq(user.getPreferredRegion()), eq(user.getPreferredLanguage()), any()))
                .thenAnswer(invocation -> {
                    completenessRepository.saveAndFlush(marker);
                    snapshotRepository.saveAndFlush(snapshot);
                    return com.watchwise.watchwise_api.diarycompletion.service.CompletionScopeLoader
                            .PersistedSeasonScopes.supported(java.util.Map.of(1, seasonScope));
                });
        when(completionScopeLoader.loadPersistedSeries(
                eq(seriesId), eq(user.getPreferredRegion()), eq(user.getPreferredLanguage()), any()))
                .thenReturn(java.util.Optional.empty());

        mockMvc.perform(get("/users/{userId}/diary/view", owner.id())
                        .param("type", "SEASON").cookie(owner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].kind").value("DERIVED_COMPLETION"));

        assertThat(projectionRepository.findByUserIdAndContentIdOrderByWatchNumber(owner.id(), season.getId()))
                .singleElement()
                .extracting(DiaryCompletionProjection::getScheduleVersion)
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("[userPreferences] Should Rebuild Season Projection - When Locale Changes But Marker Version Matches")
    void shouldRebuildSeasonProjectionWhenLocaleChangesButMarkerVersionMatches() throws Exception {
        RegisteredUser owner = registerUser();
        User user = userRepository.findById(owner.id()).orElseThrow();
        String seriesId = "95" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        Content series = persistContent(ContentType.SERIES, seriesId, null, null, null);
        Content season = persistContent(ContentType.SEASON, null, seriesId, 1, null);
        Content episode = persistContent(ContentType.EPISODE, null, seriesId, 1, 1);
        DiaryEntry anchor = persistEntry(user, episode, LocalDate.of(2026, 1, 1));
        LocalDateTime checkedAt = LocalDateTime.now();
        completenessRepository.saveAndFlush(markerForSeries(
                seriesId, CalendarScheduleCompleteness.GroupType.SEASON, 1).toBuilder()
                .region("US").language("en-US").expectedEpisodeCount(1).scopeVersion(1L)
                .lastCheckedAt(checkedAt).build());
        completenessRepository.saveAndFlush(markerForSeries(
                seriesId, CalendarScheduleCompleteness.GroupType.SERIES, null).toBuilder()
                .region("US").language("en-US").expectedEpisodeCount(1).scopeVersion(1L)
                .lastCheckedAt(checkedAt).build());
        snapshotRepository.saveAndFlush(CalendarScheduleSnapshot.builder()
                .eventType(CalendarScheduleSnapshot.EventType.EPISODE)
                .seriesTmdbId(seriesId).seasonNumber(1).episodeNumber(1)
                .region("US").language("en-US").releaseDate(LocalDate.of(2026, 1, 1))
                .title("Episode 1").lastCheckedAt(checkedAt)
                .nextCheckAt(LocalDateTime.of(2099, 1, 1, 0, 0)).presentInLastTmdbSnapshot(true).build());
        completenessRepository.saveAndFlush(markerForSeries(
                seriesId, CalendarScheduleCompleteness.GroupType.SEASON, 1).toBuilder()
                .region("BR").language("en-US").expectedEpisodeCount(2).scopeVersion(1L)
                .lastCheckedAt(checkedAt).build());
        completenessRepository.saveAndFlush(markerForSeries(
                seriesId, CalendarScheduleCompleteness.GroupType.SERIES, null).toBuilder()
                .region("BR").language("en-US").expectedEpisodeCount(2).scopeVersion(1L)
                .lastCheckedAt(checkedAt).build());
        snapshotRepository.saveAllAndFlush(List.of(
                CalendarScheduleSnapshot.builder()
                        .eventType(CalendarScheduleSnapshot.EventType.EPISODE)
                        .seriesTmdbId(seriesId).seasonNumber(1).episodeNumber(1)
                        .region("BR").language("en-US").releaseDate(LocalDate.of(2026, 1, 1))
                        .title("Episode 1").lastCheckedAt(checkedAt)
                        .nextCheckAt(LocalDateTime.of(2099, 1, 1, 0, 0)).presentInLastTmdbSnapshot(true).build(),
                CalendarScheduleSnapshot.builder()
                        .eventType(CalendarScheduleSnapshot.EventType.EPISODE)
                        .seriesTmdbId(seriesId).seasonNumber(1).episodeNumber(2)
                        .region("BR").language("en-US").releaseDate(LocalDate.of(2026, 1, 2))
                        .title("Episode 2").lastCheckedAt(checkedAt)
                        .nextCheckAt(LocalDateTime.of(2099, 1, 1, 0, 0)).presentInLastTmdbSnapshot(true).build()));
        historyStateRepository.saveAndFlush(UserSeriesHistoryState.builder()
                .id(new UserSeriesHistoryState.Key(owner.id(), seriesId))
                .historyRevision(1L).computedRevision(1L).scheduleVersion(1L)
                .validUntil(Instant.parse("2099-01-01T00:00:00Z")).build());
        projectionRepository.saveAndFlush(DiaryCompletionProjection.builder()
                .userId(owner.id()).contentId(season.getId()).contentType(ContentType.SEASON)
                .watchNumber(1).anchorDiaryEntryId(anchor.getId()).completedOn(LocalDate.of(2026, 1, 1))
                .orderedAt(Instant.parse("2026-01-01T00:00:00Z"))
                .historyRevision(1L).scheduleVersion(1L).validUntil(Instant.parse("2099-01-01T00:00:00Z"))
                .build());
        projectionRepository.saveAndFlush(DiaryCompletionProjection.builder()
                .userId(owner.id()).contentId(series.getId()).contentType(ContentType.SERIES)
                .watchNumber(1).anchorDiaryEntryId(anchor.getId()).completedOn(LocalDate.of(2026, 1, 1))
                .orderedAt(Instant.parse("2026-01-01T00:00:00Z"))
                .historyRevision(1L).scheduleVersion(1L).validUntil(Instant.parse("2099-01-01T00:00:00Z"))
                .build());
        var brazilScope = new VersionedCompletionScope(
                new CompletionScope(com.watchwise.watchwise_api.content.dto.MediaReference.season(seriesId, 1),
                        Set.of(new EpisodeCoordinate(1, 1), new EpisodeCoordinate(1, 2)), true),
                "1", Instant.parse("2099-01-01T00:00:00Z"));
        when(completionScopeLoader.supportsPersistedSchedules()).thenReturn(true);
        when(completionScopeLoader.loadPersistedSeasons(
                eq(seriesId), anyCollection(), eq("BR"), eq("en-US"), any()))
                .thenReturn(com.watchwise.watchwise_api.diarycompletion.service.CompletionScopeLoader
                        .PersistedSeasonScopes.supported(java.util.Map.of(1, brazilScope)));
        when(completionScopeLoader.loadPersistedSeries(eq(seriesId), eq("BR"), eq("en-US"), any()))
                .thenReturn(java.util.Optional.empty());

        mockMvc.perform(patch("/users/me")
                        .cookie(owner.accessToken(), owner.csrfToken())
                        .header("X-XSRF-TOKEN", owner.csrfToken().getValue())
                        .contentType("application/json")
                        .content("{\"preferredRegion\":\"BR\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/users/{userId}/diary/view", owner.id())
                        .param("type", "SEASON").cookie(owner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        assertThat(projectionRepository.findByUserIdAndContentIdOrderByWatchNumber(owner.id(), season.getId()))
                .isEmpty();
        assertThat(projectionRepository.findByUserIdAndContentIdOrderByWatchNumber(owner.id(), series.getId()))
                .isEmpty();
        assertThat(historyStateRepository.findById(new UserSeriesHistoryState.Key(owner.id(), seriesId))
                .orElseThrow().getValidUntil()).isAfter(Instant.now());
    }

    @Test
    @DisplayName("[refreshMissingSeasonCandidates] Should Rebuild The Series Projection - When A Complete Season Pass Is Discovered")
    void shouldRebuildSeriesProjectionWhenACompleteSeasonPassIsDiscovered() throws Exception {
        RegisteredUser owner = registerUser();
        User user = userRepository.findById(owner.id()).orElseThrow();
        String seriesId = "94" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        Content series = persistContent(ContentType.SERIES, seriesId, null, null, null);
        Content season = persistContent(ContentType.SEASON, null, seriesId, 1, null);
        Content episode = persistContent(ContentType.EPISODE, null, seriesId, 1, 1);
        persistEntry(user, episode, LocalDate.of(2026, 1, 1));
        LocalDateTime checkedAt = LocalDateTime.now();
        completenessRepository.saveAndFlush(markerForSeries(
                seriesId, CalendarScheduleCompleteness.GroupType.SERIES, null).toBuilder()
                .expectedEpisodeCount(1).scopeVersion(1L).lastCheckedAt(checkedAt).build());
        completenessRepository.saveAndFlush(markerForSeries(
                seriesId, CalendarScheduleCompleteness.GroupType.SEASON, 1).toBuilder()
                .expectedEpisodeCount(1).scopeVersion(1L).lastCheckedAt(checkedAt).build());
        snapshotRepository.saveAndFlush(CalendarScheduleSnapshot.builder()
                .eventType(CalendarScheduleSnapshot.EventType.EPISODE)
                .seriesTmdbId(seriesId).seasonNumber(1).episodeNumber(1)
                .region(user.getPreferredRegion()).language(user.getPreferredLanguage())
                .releaseDate(LocalDate.of(2026, 1, 1)).title("Pilot")
                .lastCheckedAt(checkedAt).nextCheckAt(LocalDateTime.of(2099, 1, 1, 0, 0))
                .presentInLastTmdbSnapshot(true).build());
        historyStateRepository.saveAndFlush(UserSeriesHistoryState.builder()
                .id(new UserSeriesHistoryState.Key(owner.id(), seriesId))
                .historyRevision(1L).computedRevision(1L).scheduleVersion(1L)
                .validUntil(Instant.parse("2099-01-01T00:00:00Z")).build());
        var seasonScope = new VersionedCompletionScope(
                new CompletionScope(com.watchwise.watchwise_api.content.dto.MediaReference.season(seriesId, 1),
                        Set.of(new EpisodeCoordinate(1, 1)), true),
                "1", Instant.parse("2099-01-01T00:00:00Z"));
        var seriesScope = new VersionedCompletionScope(
                new CompletionScope(com.watchwise.watchwise_api.content.dto.MediaReference.series(seriesId),
                        Set.of(new EpisodeCoordinate(1, 1)), true),
                "1", Instant.parse("2099-01-01T00:00:00Z"));
        when(completionScopeLoader.supportsPersistedSchedules()).thenReturn(true);
        when(completionScopeLoader.loadPersistedSeasons(
                eq(seriesId), anyCollection(), eq(user.getPreferredRegion()), eq(user.getPreferredLanguage()), any()))
                .thenReturn(com.watchwise.watchwise_api.diarycompletion.service.CompletionScopeLoader
                        .PersistedSeasonScopes.supported(java.util.Map.of(1, seasonScope)));
        when(completionScopeLoader.loadPersistedSeries(
                eq(seriesId), eq(user.getPreferredRegion()), eq(user.getPreferredLanguage()), any()))
                .thenReturn(java.util.Optional.of(seriesScope));

        mockMvc.perform(get("/users/{userId}/diary/view", owner.id())
                        .param("type", "SEASON").cookie(owner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].kind").value("DERIVED_COMPLETION"));

        assertThat(projectionRepository.findByUserIdAndContentIdOrderByWatchNumber(owner.id(), season.getId()))
                .hasSize(1);
        assertThat(projectionRepository.findByUserIdAndContentIdOrderByWatchNumber(owner.id(), series.getId()))
                .hasSize(1);
    }

    @Test
    @DisplayName("[diaryView] Should Return Persisted Events During Calendar Repair - When No Usable Season Schedule Exists")
    void shouldReturnPersistedEventsDuringCalendarRepairWhenNoUsableSeasonScheduleExists() throws Exception {
        RegisteredUser owner = registerUser();
        User user = userRepository.findById(owner.id()).orElseThrow();
        String seriesId = "99" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        Content episode = persistContent(ContentType.EPISODE, null, seriesId, 1, 1);
        persistEntry(user, episode, LocalDate.of(2026, 1, 1));
        when(completionScopeLoader.supportsPersistedSchedules()).thenReturn(true);
        when(completionScopeLoader.loadPersistedSeasons(
                eq(seriesId), anyCollection(), eq(user.getPreferredRegion()), eq(user.getPreferredLanguage()), any()))
                .thenReturn(com.watchwise.watchwise_api.diarycompletion.service.CompletionScopeLoader.PersistedSeasonScopes
                        .supported(java.util.Map.of()));
        when(completionScopeLoader.loadPersistedSeries(
                eq(seriesId), eq(user.getPreferredRegion()), eq(user.getPreferredLanguage()), any()))
                .thenReturn(java.util.Optional.of(new VersionedCompletionScope(
                        new CompletionScope(com.watchwise.watchwise_api.content.dto.MediaReference.series(seriesId),
                                Set.of(), false),
                        "0", Instant.parse("2099-01-01T00:00:00Z"))));
        when(calendarScheduleRefreshService.refreshSeasonInBackground(
                anyString(), anyInt(), anyString(), anyString())).thenReturn(true);

        mockMvc.perform(get("/users/{userId}/diary/view", owner.id()).cookie(owner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].kind").value("PERSISTED_ENTRY"));

        verify(calendarScheduleRefreshService).refreshSeasonInBackground(
                seriesId, 1, user.getPreferredRegion(), user.getPreferredLanguage());
        verify(completionScopeLoader, never()).load(any(), anyString(), anyString(), any());
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
        TestRegistrationHelper.RegisteredTestUser registered = TestRegistrationHelper.register(
                mockMvc, userRepository, emailConfirmationSender,
                username, "Diary Projection", username + "@example.com", true);
        return new RegisteredUser(registered.id(), registered.accessToken(), registered.csrfToken());
    }

    private record RegisteredUser(UUID id, Cookie accessToken, Cookie csrfToken) {
    }
}
