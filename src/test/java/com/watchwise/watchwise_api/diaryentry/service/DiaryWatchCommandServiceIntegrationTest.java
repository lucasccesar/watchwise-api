package com.watchwise.watchwise_api.diaryentry.service;

import com.watchwise.watchwise_api.auth.repository.RefreshTokenRepository;
import com.watchwise.watchwise_api.common.security.CookieUtil;
import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.common.security.RequestThrottlerTestSupport;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbEpisodeSummary;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbEpisodeFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonSummary;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvFullDetails;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.dto.MediaReference;
import com.watchwise.watchwise_api.diarycompletion.model.CompletionScope;
import com.watchwise.watchwise_api.diarycompletion.model.EpisodeCoordinate;
import com.watchwise.watchwise_api.diarycompletion.model.VersionedCompletionScope;
import com.watchwise.watchwise_api.diarycompletion.service.CompletionScopeLoader;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.contentrating.entity.ContentRating;
import com.watchwise.watchwise_api.contentrating.repository.ContentRatingRepository;
import com.watchwise.watchwise_api.contentreview.entity.ContentReview;
import com.watchwise.watchwise_api.contentreview.entity.ReviewContextKind;
import com.watchwise.watchwise_api.contentreview.entity.ReviewPublicationKind;
import com.watchwise.watchwise_api.contentreview.repository.ContentReviewRepository;
import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import com.watchwise.watchwise_api.diaryentry.entity.ImportedWatchAssertion;
import com.watchwise.watchwise_api.diaryentry.dto.WatchEventCommand;
import com.watchwise.watchwise_api.diaryentry.dto.WatchMutationResult;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.diaryentry.repository.ImportedWatchAssertionRepository;
import com.watchwise.watchwise_api.diaryentry.service.DiaryWatchCommandService;
import com.watchwise.watchwise_api.dropped.repository.DroppedEntryRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.user.service.TestEmailConfirmationSender;
import com.watchwise.watchwise_api.user.service.TestRegistrationHelper;
import com.watchwise.watchwise_api.watchlist.repository.WatchlistEntryRepository;
import com.watchwise.watchwise_api.watchlist.entity.WatchlistEntry;
import com.watchwise.watchwise_api.dropped.entity.DroppedEntry;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class DiaryWatchCommandServiceIntegrationTest {

    private static final String SERIES_ID = "b7-command-series";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.docker.compose.enabled", () -> "false");
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private TestEmailConfirmationSender emailConfirmationSender;
    @Autowired private ContentRepository contentRepository;
    @Autowired private DiaryEntryRepository diaryEntryRepository;
    @Autowired private ImportedWatchAssertionRepository importedWatchAssertionRepository;
    @Autowired private ContentRatingRepository contentRatingRepository;
    @Autowired private ContentReviewRepository contentReviewRepository;
    @Autowired private DiaryEntryService diaryEntryService;
    @Autowired private DiaryWatchCommandService diaryWatchCommandService;
    @Autowired private WatchlistEntryRepository watchlistEntryRepository;
    @Autowired private DroppedEntryRepository droppedEntryRepository;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private RequestThrottler requestThrottler;
    @Autowired private JdbcTemplate jdbc;

    @MockitoBean
    private TmdbClient tmdbClient;

    @MockitoBean
    private CompletionScopeLoader completionScopeLoader;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM external_import_links");
        importedWatchAssertionRepository.deleteAll();
        diaryEntryRepository.deleteAll();
        contentReviewRepository.deleteAll();
        contentRatingRepository.deleteAll();
        watchlistEntryRepository.deleteAll();
        droppedEntryRepository.deleteAll();
        contentRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
        RequestThrottlerTestSupport.reset(requestThrottler);
        when(tmdbClient.getSeasonFullDetails(eq(SERIES_ID), eq(1), any()))
                .thenReturn(new TmdbLookupResult.Found<>(seasonWithOneAiredEpisode()));
        when(tmdbClient.getTvFullDetails(eq(SERIES_ID), any()))
                .thenReturn(new TmdbLookupResult.Found<>(seriesWithOneSeason()));
        when(tmdbClient.getEpisodeFullDetails(eq(SERIES_ID), eq(1), eq(1), any()))
                .thenReturn(new TmdbLookupResult.Found<>(
                        new TmdbEpisodeFullDetails(null, null, null, "2020-01-01", 1, 1, 40, null, null)));
        when(tmdbClient.getTvFullDetails(eq("9001"), any()))
                .thenReturn(new TmdbLookupResult.Found<>(seriesWithOneSeason()));
        when(tmdbClient.getEpisodeFullDetails(eq("9001"), eq(1), eq(1), any()))
                .thenReturn(new TmdbLookupResult.Found<>(
                        new TmdbEpisodeFullDetails(null, null, null, "2020-01-01", 1, 1, 40, null, null)));
        lenient().when(completionScopeLoader.load(any(MediaReference.class), any(), any(), any()))
                .thenAnswer(invocation -> new VersionedCompletionScope(
                        new CompletionScope(invocation.getArgument(0),
                                java.util.Set.of(new EpisodeCoordinate(1, 1)), true),
                        "test-schedule", Instant.now().plusSeconds(60)));
    }

    @Test
    @DisplayName("[diary bulk] Should Replay The Same Result - When The Same Idempotency Key And Payload Are Repeated")
    void shouldReplayTheSameResultWithoutCreatingAnotherPassWhenTheSameKeyAndPayloadAreRepeated() throws Exception {
        RegisteredUser owner = registerUser("b7-replay");
        String body = seasonBulkBody("2024-01-01");

        MvcResult first = mockMvc.perform(bulkRequest(owner, body, "b7-replay-key"))
                .andExpect(status().isCreated())
                .andReturn();
        MvcResult replay = mockMvc.perform(bulkRequest(owner, body, "b7-replay-key"))
                .andExpect(status().isCreated())
                .andReturn();

        assertThat(replay.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
        assertThat(jdbc.query("""
                SELECT d.watch_number FROM diary_entries d JOIN contents c ON c.id = d.content_id
                WHERE d.user_id = ? AND c.type = 'EPISODE' ORDER BY d.watch_number
                """, (rs, rowNum) -> rs.getInt(1), owner.id())).containsExactly(1);
    }

    @Test
    @DisplayName("[diary bulk] Should Return Conflict - When An Idempotency Key Is Reused With A Different Payload")
    void shouldRejectDifferentPayloadWhenAnIdempotencyKeyHasAlreadyBeenUsed() throws Exception {
        RegisteredUser owner = registerUser("b7-hash-conflict");
        mockMvc.perform(bulkRequest(owner, seasonBulkBody("2024-01-01"), "b7-conflict-key"))
                .andExpect(status().isCreated());

        mockMvc.perform(bulkRequest(owner, seasonBulkBody("2024-01-02"), "b7-conflict-key"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", is("Idempotency key already used with different request")));

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM diary_entries d JOIN contents c ON c.id = d.content_id
                WHERE d.user_id = ? AND c.type = 'EPISODE'
                """, Long.class, owner.id())).isEqualTo(1L);
    }

    @Test
    @DisplayName("[diary bulk] Should Start A Rewatch - When A New Key Is Used After A Complete Pass")
    void shouldStartTheNextWatchPassWhenANewKeyIsUsedAfterThePreviousPassCompletes() throws Exception {
        RegisteredUser owner = registerUser("b7-new-action");
        String body = seasonBulkBody("2024-01-01");

        mockMvc.perform(bulkRequest(owner, body, "b7-first-pass"))
                .andExpect(status().isCreated());
        mockMvc.perform(bulkRequest(owner, body, "b7-second-pass"))
                .andExpect(status().isCreated());

        assertThat(jdbc.query("""
                SELECT d.watch_number
                FROM diary_entries d JOIN contents c ON c.id = d.content_id
                WHERE d.user_id = ? AND c.type = 'EPISODE'
                ORDER BY d.watch_number
                """, (rs, rowNum) -> rs.getInt(1), owner.id()))
                .containsExactly(1, 2);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM diary_entries d JOIN contents c ON c.id = d.content_id
                WHERE d.user_id = ? AND c.type IN ('SEASON', 'SERIES')
                """, Long.class, owner.id())).isZero();
    }

    @Test
    @DisplayName("[diary bulk] Should Require An Idempotency Key - When Starting A Bulk Action")
    void shouldRejectBulkActionWithoutAnIdempotencyKey() throws Exception {
        RegisteredUser owner = registerUser("b7-missing-key");

        mockMvc.perform(bulkRequest(owner, seasonBulkBody("2024-01-01"), null))
                .andExpect(status().isBadRequest());

        assertThat(diaryEntryRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("[diary watch command] Should Persist Source Precision And Remove Series Tracking - When Recording An Episode")
    void shouldPersistWatchedAtAndRemoveSeriesTrackingWhenRecordingAnEpisode() {
        UUID ownerId = userRepository.saveAndFlush(User.builder()
                .username("b7-command-" + UUID.randomUUID().toString().substring(0, 8))
                .email("b7-command-" + UUID.randomUUID() + "@email.com")
                .password("hashed")
                .name("B7 command")
                .isProfilePublic(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build()).getId();
        Content series = contentRepository.saveAndFlush(content(ContentType.SERIES, "9001", null, null));
        Content episode = contentRepository.saveAndFlush(content(ContentType.EPISODE, null, "9001", 1, 1));
        LocalDateTime now = LocalDateTime.now();
        watchlistEntryRepository.saveAndFlush(WatchlistEntry.builder()
                .user(userRepository.getReferenceById(ownerId)).content(series).type(ContentType.SERIES)
                .position(1).createdAt(now).updatedAt(now).build());
        droppedEntryRepository.saveAndFlush(DroppedEntry.builder()
                .user(userRepository.getReferenceById(ownerId)).content(series).type(ContentType.SERIES)
                .comment("stopped").createdAt(now).updatedAt(now).build());
        Instant sourceInstant = Instant.parse("2024-03-04T05:06:07.123456Z");

        WatchMutationResult result = diaryWatchCommandService.recordPrepared(ownerId, new WatchEventCommand(
                MediaReference.episode("9001", 1, 1), LocalDate.of(2024, 3, 4), null,
                null, null, null, List.of(), sourceInstant));

        assertThat(result.inserted()).isTrue();
        assertThat(result.diaryEntryId()).isNotNull();
        assertThat(result.historyRevision()).isPositive();
        DiaryEntry saved = diaryEntryRepository.findByIdWithContentAndUser(result.diaryEntryId()).orElseThrow();
        assertThat(saved.getContent().getId()).isEqualTo(episode.getId());
        assertThat(saved.getWatchedAt()).isEqualTo(sourceInstant);
        assertThat(watchlistEntryRepository.findByUserIdAndTypeAndContentId(ownerId, ContentType.SERIES, series.getId()))
                .isEmpty();
        assertThat(droppedEntryRepository.findByUserIdAndTypeAndContentId(ownerId, ContentType.SERIES, series.getId()))
                .isEmpty();
        verify(tmdbClient, never()).getEpisodeFullDetails(eq("9001"), eq(1), eq(1), any());
    }

    @Test
    @DisplayName("[delete diary series] Should Delete Inline Episode Opinions And Preserve Standalone Opinions")
    void shouldDeleteInlineEpisodeOpinionsWithHistoryAndPreserveStandaloneSeriesSeasonOpinions() throws Exception {
        RegisteredUser owner = registerUser("b7-wipe");
        Content series = contentRepository.saveAndFlush(content(ContentType.SERIES, "9001", null, null));
        Content season = contentRepository.saveAndFlush(content(ContentType.SEASON, null, "9001", 1));
        Content episode = contentRepository.saveAndFlush(content(ContentType.EPISODE, null, "9001", 1, 1));
        Content otherSeries = contentRepository.saveAndFlush(content(ContentType.SERIES, "9002", null, null));
        LocalDateTime now = LocalDateTime.now();
        UUID ignoredDiaryEntryId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO diary_entries
                    (id, user_id, content_id, watch_number, watched_date, "ignore",
                     likes_count, created_at, updated_at)
                VALUES (?, ?, ?, 1, ?, true, 0, ?, ?)
                """, ignoredDiaryEntryId, owner.id(), episode.getId(), LocalDate.of(2024, 1, 1), now, now);
        DiaryEntry episodeOpinion = diaryEntryRepository.saveAndFlush(DiaryEntry.builder()
                .user(userRepository.getReferenceById(owner.id())).content(episode)
                .comment("episode-specific review").score(9).watchedDate(LocalDate.of(2024, 2, 3))
                .watchNumber(2).ignore(false).createdAt(now).updatedAt(now).build());
        assertThat(jdbc.queryForObject("SELECT score FROM diary_entries WHERE id = ?", Integer.class,
                episodeOpinion.getId())).isEqualTo(9);
        assertThat(jdbc.queryForObject("SELECT comment FROM diary_entries WHERE id = ?", String.class,
                episodeOpinion.getId())).isEqualTo("episode-specific review");

        List<ImportedWatchAssertion> scopedAssertions = List.of(
                assertion(owner.id(), series, "series-source"),
                assertion(owner.id(), season, "season-source"),
                assertion(owner.id(), episode, "episode-source"));
        scopedAssertions = importedWatchAssertionRepository.saveAllAndFlush(scopedAssertions);
        ImportedWatchAssertion unrelatedAssertion = importedWatchAssertionRepository.saveAndFlush(
                assertion(owner.id(), otherSeries, "other-series-source"));
        scopedAssertions.forEach(assertion -> addSourceLink(owner.id(), assertion));

        ContentRating seriesRating = contentRatingRepository.saveAndFlush(ContentRating.builder()
                .userId(owner.id()).contentId(series.getId()).contentType(ContentType.SERIES)
                .score(8).createdAt(Instant.now()).updatedAt(Instant.now()).build());
        ContentRating seasonRating = contentRatingRepository.saveAndFlush(ContentRating.builder()
                .userId(owner.id()).contentId(season.getId()).contentType(ContentType.SEASON)
                .score(7).createdAt(Instant.now()).updatedAt(Instant.now()).build());
        ContentReview seriesReview = contentReviewRepository.saveAndFlush(ContentReview.builder()
                .userId(owner.id()).contentId(series.getId()).contentType(ContentType.SERIES)
                .publicationKind(ReviewPublicationKind.WRITTEN_REVIEW).contextKind(ReviewContextKind.MANUAL)
                .watchNumber(1)
                .comment("kept series review").createdAt(Instant.now()).updatedAt(Instant.now()).build());
        ContentReview seasonReview = contentReviewRepository.saveAndFlush(ContentReview.builder()
                .userId(owner.id()).contentId(season.getId()).contentType(ContentType.SEASON)
                .publicationKind(ReviewPublicationKind.WRITTEN_REVIEW).contextKind(ReviewContextKind.MANUAL)
                .watchNumber(1)
                .comment("kept season review").createdAt(Instant.now()).updatedAt(Instant.now()).build());

        diaryEntryService.deleteAllDiaryEntriesForSeries(owner.id(), "9001");

        assertThat(importedWatchAssertionRepository.findAll()).extracting(ImportedWatchAssertion::getId)
                .containsExactly(unrelatedAssertion.getId());
        assertThat(scopedAssertions).allSatisfy(assertion -> assertThat(jdbc.queryForObject(
                "SELECT state FROM external_import_links WHERE target_id = ?", String.class, assertion.getId()))
                .isEqualTo("TOMBSTONE"));
        assertThat(diaryEntryRepository.findById(episodeOpinion.getId())).isEmpty();
        assertThat(diaryEntryRepository.findAll()).isEmpty();
        assertThat(contentRatingRepository.existsById(seriesRating.getId())).isTrue();
        assertThat(contentRatingRepository.existsById(seasonRating.getId())).isTrue();
        assertThat(contentReviewRepository.existsById(seriesReview.getId())).isTrue();
        assertThat(contentReviewRepository.existsById(seasonReview.getId())).isTrue();
    }

    private TmdbSeasonFullDetails seasonWithOneAiredEpisode() {
        return new TmdbSeasonFullDetails(null, null, null, null, null, 1,
                List.of(new TmdbEpisodeSummary(1, null, null, "2020-01-01", 40, null, null)), null, null);
    }

    private TmdbTvFullDetails seriesWithOneSeason() {
        return new TmdbTvFullDetails(null, null, null, null, null, null, null, null, null, null, null,
                List.of(new TmdbSeasonSummary(1, "Season 1", null, null, 1, null)),
                null, null, null, null, 1, 1, null, null, null, null);
    }

    private String seasonBulkBody(String watchedDate) {
        return """
                {
                  "content": { "type": "SEASON", "seriesTmdbId": "%s", "seasonNumber": 1 },
                  "watchedDate": "%s",
                  "finaleEpisodeNumber": 1
                }
                """.formatted(SERIES_ID, watchedDate);
    }

    private MockHttpServletRequestBuilder bulkRequest(RegisteredUser owner, String body, String idempotencyKey) {
        MockHttpServletRequestBuilder request = post("/diary/bulk")
                .cookie(owner.accessToken(), owner.csrfToken())
                .header("X-XSRF-TOKEN", owner.csrfToken().getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        return idempotencyKey == null ? request : request.header("Idempotency-Key", idempotencyKey);
    }

    private RegisteredUser registerUser(String suffix) throws Exception {
        String username = suffix + "-" + UUID.randomUUID().toString().substring(0, 8);
        TestRegistrationHelper.RegisteredTestUser registered = TestRegistrationHelper.register(
                mockMvc, userRepository, emailConfirmationSender,
                username, username, username + "@email.com", true);
        return new RegisteredUser(registered.id(), registered.accessToken(), registered.csrfToken());
    }

    private Content content(ContentType type, String tmdbId, String seriesTmdbId, Integer seasonNumber) {
        return content(type, tmdbId, seriesTmdbId, seasonNumber, null);
    }

    private Content content(ContentType type, String tmdbId, String seriesTmdbId, Integer seasonNumber, Integer episodeNumber) {
        LocalDateTime now = LocalDateTime.now();
        return Content.builder().type(type).tmdbId(tmdbId).seriesTmdbId(seriesTmdbId)
                .seasonNumber(seasonNumber).episodeNumber(episodeNumber).createdAt(now).updatedAt(now).build();
    }

    private ImportedWatchAssertion assertion(UUID ownerId, Content content, String sourceId) {
        return ImportedWatchAssertion.builder().user(userRepository.getReferenceById(ownerId))
                .content(content).sourceNamespace("trakt").objectKind("watched_item").sourceId(sourceId)
                .coverage("UNKNOWN").createdAt(LocalDateTime.now()).build();
    }

    private void addSourceLink(UUID ownerId, ImportedWatchAssertion assertion) {
        jdbc.update("""
                INSERT INTO external_import_links
                    (id, user_id, source_namespace, object_kind, source_id, state, target_type, target_id,
                     created_at, updated_at)
                VALUES (?, ?, 'trakt', 'watched_item', ?, 'APPLIED', 'IMPORTED_WATCH_ASSERTION', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), ownerId, assertion.getSourceId(), assertion.getId());
    }

    private record RegisteredUser(UUID id, Cookie accessToken, Cookie csrfToken) { }
}
