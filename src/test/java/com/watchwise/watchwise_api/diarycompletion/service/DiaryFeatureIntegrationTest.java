package com.watchwise.watchwise_api.diarycompletion.service;

import com.jayway.jsonpath.JsonPath;
import com.watchwise.watchwise_api.calendar.entity.CalendarScheduleCompleteness;
import com.watchwise.watchwise_api.calendar.repository.CalendarScheduleSnapshotStore;
import com.watchwise.watchwise_api.calendar.repository.CalendarScheduleCompletenessRepository;
import com.watchwise.watchwise_api.calendar.service.CalendarEpisodeSchedule;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleKey;
import com.watchwise.watchwise_api.calendar.service.CalendarSeasonSchedule;
import com.watchwise.watchwise_api.calendar.service.CalendarSeriesSchedule;
import com.watchwise.watchwise_api.common.security.CookieUtil;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbEpisodeSummary;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupOrigin;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonSummary;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvFullDetails;
import com.watchwise.watchwise_api.content.dto.MediaReference;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.contentrating.entity.ContentRating;
import com.watchwise.watchwise_api.contentrating.repository.ContentRatingRepository;
import com.watchwise.watchwise_api.contentreview.entity.ContentReview;
import com.watchwise.watchwise_api.contentreview.repository.ContentReviewRepository;
import com.watchwise.watchwise_api.diarycompletion.model.CompletionScope;
import com.watchwise.watchwise_api.diarycompletion.model.EpisodeCoordinate;
import com.watchwise.watchwise_api.diarycompletion.model.VersionedCompletionScope;
import com.watchwise.watchwise_api.diarycompletion.entity.DiaryCompletionProjection;
import com.watchwise.watchwise_api.diarycompletion.entity.UserSeriesHistoryState;
import com.watchwise.watchwise_api.diarycompletion.repository.DiaryCompletionProjectionRepository;
import com.watchwise.watchwise_api.contentrating.service.OpinionEvidenceService;
import com.watchwise.watchwise_api.diarycompletion.repository.UserSeriesHistoryStateRepository;
import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.diarycompletion.service.CompletionScopeLoader;
import com.watchwise.watchwise_api.platformimport.parser.impl.LetterboxdItemCsvParserImpl;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import jakarta.persistence.EntityManagerFactory;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class DiaryFeatureIntegrationTest {

    private static final String SERIES_TMDB_ID = "91001";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.docker.compose.enabled", () -> "false");
        registry.add("spring.jpa.properties.hibernate.generate_statistics", () -> "true");
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
    private ContentRatingRepository contentRatingRepository;

    @Autowired
    private ContentReviewRepository contentReviewRepository;

    @Autowired
    private DiaryCompletionProjectionRepository projectionRepository;

    @Autowired
    private CalendarScheduleCompletenessRepository completenessRepository;

    @Autowired
    private CalendarScheduleSnapshotStore calendarScheduleSnapshotStore;

    @Autowired
    private UserSeriesHistoryStateRepository historyStateRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @MockitoBean
    private TmdbClient tmdbClient;

    @MockitoBean
    private CompletionScopeLoader completionScopeLoader;

    @MockitoSpyBean
    private OpinionEvidenceService opinionEvidenceService;

    @MockitoBean
    private LetterboxdItemCsvParserImpl letterboxdItemCsvParser;

    @MockitoSpyBean
    private NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    @Test
    @DisplayName("[diaryLifecycle] Should Preserve Episode History And Review Snapshots - When Two Seasons Are Rewatched")
    void shouldPreserveEpisodeHistoryAndReviewSnapshotsWhenTwoSeasonsAreRewatched() throws Exception {
        RegisteredUser owner = registerUser();
        stubTmdb();
        when(completionScopeLoader.load(any(MediaReference.class), anyString(), anyString(), any()))
                .thenAnswer(invocation -> {
                    MediaReference reference = invocation.getArgument(0, MediaReference.class);
                    Set<EpisodeCoordinate> coordinates;
                    if (reference.type() == ContentType.SEASON) {
                        coordinates = IntStream.rangeClosed(1, 3)
                                .mapToObj(number -> new EpisodeCoordinate(reference.seasonNumber(), number))
                                .collect(java.util.stream.Collectors.toSet());
                    } else {
                        coordinates = IntStream.rangeClosed(1, 3)
                                .boxed()
                                .flatMap(number -> java.util.stream.Stream.of(
                                        new EpisodeCoordinate(1, number), new EpisodeCoordinate(2, number)))
                                .collect(java.util.stream.Collectors.toSet());
                    }
                    return new VersionedCompletionScope(
                            new CompletionScope(reference, coordinates, true),
                            "lifecycle-" + reference.type() + "-" + reference.seasonNumber(),
                            Instant.parse("2099-01-01T00:00:00Z"));
                });

        bulkWatch(owner, 1, "season-1-pass-1");
        publishReview(owner, 1, 6, "Season one first pass");
        bulkWatch(owner, 1, "season-1-pass-2");
        publishReview(owner, 1, 9, "Season one rewatch");
        bulkWatch(owner, 2, "season-2-pass-1");
        publishReview(owner, 2, 7, "Season two first pass");
        bulkWatch(owner, 2, "season-2-pass-2");
        publishReview(owner, 2, 10, "Season two rewatch");

        Content seasonOne = season(1);
        Content seasonTwo = season(2);
        assertThat(diaryEntryRepository.findByUserIdOrderByCreatedAtDesc(owner.id(),
                org.springframework.data.domain.PageRequest.of(0, 100)).getContent())
                .hasSize(12)
                .allSatisfy(entry -> assertThat(entry.getContent().getType()).isEqualTo(ContentType.EPISODE));

        assertThat(contentRatingRepository.findByUserIdAndContentId(owner.id(), seasonOne.getId()))
                .get().extracting(ContentRating::getScore).isEqualTo(9);
        assertThat(contentRatingRepository.findByUserIdAndContentId(owner.id(), seasonTwo.getId()))
                .get().extracting(ContentRating::getScore).isEqualTo(10);

        assertThat(contentReviewRepository.findByUserIdAndContentIdOrderByCreatedAtDesc(owner.id(), seasonOne.getId()))
                .extracting(ContentReview::getScoreAtPublication, ContentReview::getWatchNumber,
                        review -> review.getContextKind().name())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(9, 2, "COMPLETION"),
                        org.assertj.core.groups.Tuple.tuple(6, 1, "COMPLETION"));
        assertThat(contentReviewRepository.findByUserIdAndContentIdOrderByCreatedAtDesc(owner.id(), seasonTwo.getId()))
                .extracting(ContentReview::getScoreAtPublication, ContentReview::getWatchNumber,
                        review -> review.getContextKind().name())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(10, 2, "COMPLETION"),
                        org.assertj.core.groups.Tuple.tuple(7, 1, "COMPLETION"));

        persistCalendarMarkers(userRepository.findById(owner.id()).orElseThrow());

        String timeline = mockMvc.perform(get("/users/{userId}/diary/view", owner.id())
                        .cookie(owner.accessToken()).param("type", "SEASON").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(4))
                .andReturn().getResponse().getContentAsString();
        List<java.util.Map<String, Object>> timelineItems = JsonPath.read(timeline, "$.content");
        assertThat(timelineItems).allSatisfy(item -> {
            assertThat(item.get("kind")).isEqualTo("DERIVED_COMPLETION");
            assertThat(item.get("diaryEntryId")).isNull();
            assertThat((String) item.get("completionKey")).isNotBlank();
            assertThat(((Number) item.get("watchNumber")).intValue()).isPositive();
            assertThat(((Number) item.get("reviewCount")).intValue()).isEqualTo(1);
            assertThat(item.get("likesCount")).isNull();
            assertThat(item.get("likedByMe")).isNull();
        });
        assertThat(JsonPath.<List<String>>read(timeline, "$.content[*].completionKey"))
                .containsExactlyInAnyOrder(
                        "SEASON:91001:1:pass:1", "SEASON:91001:1:pass:2",
                        "SEASON:91001:2:pass:1", "SEASON:91001:2:pass:2");
        assertThat(JsonPath.<List<Integer>>read(timeline, "$.content[*].currentScore"))
                .containsExactlyInAnyOrder(9, 9, 10, 10);
        assertThat(JsonPath.<List<java.util.Map<String, Object>>>read(
                timeline, "$.content[*].reviewPreviews[*]"))
                .allSatisfy(review -> assertThat(review)
                        .doesNotContainKeys("likesCount", "likedByMe", "commentsCount", "commentCount"));
        List<Integer> seasonOnePasses = JsonPath.read(timeline,
                "$.content[?(@.content.seasonNumber == 1)].watchNumber");
        List<Integer> seasonTwoPasses = JsonPath.read(timeline,
                "$.content[?(@.content.seasonNumber == 2)].watchNumber");
        assertThat(seasonOnePasses).containsExactlyInAnyOrder(1, 2);
        assertThat(seasonTwoPasses).containsExactlyInAnyOrder(1, 2);
        assertThat(JsonPath.<List<String>>read(timeline,
                "$.content[?(@.content.seasonNumber == 1)].kind")).containsOnly("DERIVED_COMPLETION");
        assertThat(JsonPath.<List<Integer>>read(timeline,
                "$.content[?(@.content.seasonNumber == 2)].currentScore")).containsOnly(10);
        assertThat(JsonPath.<List<Integer>>read(timeline,
                "$.content[?(@.content.seasonNumber == 2)].reviewCount")).containsExactlyInAnyOrder(1, 1);
        assertThat(JsonPath.<List<String>>read(timeline,
                "$.content[?(@.content.seasonNumber == 2)].reviewPreviews[*].contextKind"))
                .containsOnly("COMPLETION");
        assertThat(JsonPath.<List<Integer>>read(timeline,
                "$.content[?(@.content.seasonNumber == 1)].reviewPreviews[*].scoreAtPublication"))
                .containsExactlyInAnyOrder(6, 9);
        assertThat(JsonPath.<List<Integer>>read(timeline,
                "$.content[?(@.content.seasonNumber == 2)].reviewPreviews[*].scoreAtPublication"))
                .containsExactlyInAnyOrder(7, 10);
    }

    @Test
    @DisplayName("[seriesCompletionLifecycle] Should Reconcile Series Passes When Calendar Adds A Season")
    void shouldReconcileSeriesPassesWhenCalendarAddsASeason() throws Exception {
        RegisteredUser owner = registerUser();
        User user = userRepository.findById(owner.id()).orElseThrow();
        String seriesId = "91003";
        AtomicBoolean seasonTwoReleased = new AtomicBoolean(false);
        stubTmdb();
        when(completionScopeLoader.load(any(MediaReference.class), anyString(), anyString(), any()))
                .thenAnswer(invocation -> {
                    MediaReference reference = invocation.getArgument(0, MediaReference.class);
                    Set<EpisodeCoordinate> coordinates;
                    if (reference.type() == ContentType.SEASON) {
                        coordinates = IntStream.rangeClosed(1, 3)
                                .mapToObj(number -> new EpisodeCoordinate(reference.seasonNumber(), number))
                                .collect(java.util.stream.Collectors.toSet());
                    } else {
                        var episodes = new java.util.HashSet<EpisodeCoordinate>();
                        IntStream.rangeClosed(1, 3).forEach(number -> episodes.add(new EpisodeCoordinate(1, number)));
                        if (seasonTwoReleased.get()) {
                            IntStream.rangeClosed(1, 3).forEach(number -> episodes.add(new EpisodeCoordinate(2, number)));
                        }
                        coordinates = Set.copyOf(episodes);
                    }
                    long scopeVersion = reference.type() == ContentType.SERIES && seasonTwoReleased.get() ? 2L : 1L;
                    return new VersionedCompletionScope(new CompletionScope(reference, coordinates, true),
                            "transition-" + reference.type() + "-" + scopeVersion,
                            Instant.parse("2099-01-01T00:00:00Z"));
                });

        bulkWatch(owner, seriesId, 1, "transition-s1-pass-1");
        persistCompletionMarker(user, seriesId, CalendarScheduleCompleteness.GroupType.SERIES, null, 3);
        persistCompletionMarker(user, seriesId, CalendarScheduleCompleteness.GroupType.SEASON, 1, 3);
        Content series = contentRepository.findByTmdbIdAndType(seriesId, ContentType.SERIES).orElseThrow();

        publishReviewForContent(owner, series.getId(), 8, "Series pass one", "transition-review-pass-1");
        assertSeriesTimeline(owner, seriesId,
                List.of("SERIES:" + seriesId + ":pass:1"), List.of(1), List.of(8), List.of(1));

        bulkWatch(owner, seriesId, 1, "transition-s1-pass-2");
        assertSeriesTimeline(owner, seriesId,
                List.of("SERIES:" + seriesId + ":pass:1", "SERIES:" + seriesId + ":pass:2"),
                List.of(1, 2), List.of(8, 8), List.of(1, 0));
        publishReviewForContent(owner, series.getId(), 9, "Series pass two", "transition-review-pass-2");
        assertSeriesTimeline(owner, seriesId,
                List.of("SERIES:" + seriesId + ":pass:1", "SERIES:" + seriesId + ":pass:2"),
                List.of(1, 2), List.of(9, 9), List.of(1, 1));

        List<ReviewSnapshot> originalSeriesReviews = seriesReviewSnapshots(owner.id(), series.getId());
        assertThat(originalSeriesReviews).extracting(ReviewSnapshot::scoreAtPublication)
                .containsExactly(9, 8);
        assertThat(originalSeriesReviews).extracting(ReviewSnapshot::contextKind)
                .containsOnly("COMPLETION");
        assertThat(originalSeriesReviews).extracting(ReviewSnapshot::contextJson)
                .doesNotContainNull();

        CalendarScheduleCompleteness seriesMarker = completenessRepository.findSeriesIdentity(
                seriesId, user.getPreferredRegion(), user.getPreferredLanguage()).orElseThrow();
        completenessRepository.saveAndFlush(seriesMarker.toBuilder()
                .expectedEpisodeCount(6)
                .scopeVersion(2L)
                .lastCheckedAt(LocalDateTime.now())
                .build());
        persistCompletionMarker(user, seriesId, CalendarScheduleCompleteness.GroupType.SEASON, 2, 3);
        seasonTwoReleased.set(true);

        assertSeriesTimeline(owner, seriesId, List.of(), List.of(), List.of(), List.of());
        assertSeasonTimeline(owner, seriesId,
                List.of("SEASON:" + seriesId + ":1:pass:1", "SEASON:" + seriesId + ":1:pass:2"),
                List.of(1, 2));
        assertThat(seriesReviewSnapshots(owner.id(), series.getId())).containsExactlyElementsOf(originalSeriesReviews);
        assertThat(contentRatingRepository.findByUserIdAndContentId(owner.id(), series.getId()))
                .get().extracting(ContentRating::getScore).isEqualTo(9);

        ContentRating existingRating = contentRatingRepository.findByUserIdAndContentId(owner.id(), series.getId())
                .orElseThrow();
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/contents/{contentId}/my-rating", series.getId())
                        .cookie(owner.accessToken(), owner.csrfToken())
                        .header("X-XSRF-TOKEN", owner.csrfToken().getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"score\":5,\"expectedVersion\":" + existingRating.getVersion() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score").value(5));

        ContentRating editedRating = contentRatingRepository.findByUserIdAndContentId(owner.id(), series.getId())
                .orElseThrow();
        mockMvc.perform(post("/contents/{contentId}/reviews", series.getId())
                        .cookie(owner.accessToken(), owner.csrfToken())
                        .header("X-XSRF-TOKEN", owner.csrfToken().getValue())
                        .header("Idempotency-Key", "transition-review-while-pending")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"Pending series pass\",\"score\":7,\"expectedRatingVersion\":"
                                + editedRating.getVersion() + "}"))
                .andExpect(status().isBadRequest());
        assertThat(contentReviewRepository.findByUserIdAndContentIdOrderByCreatedAtDesc(owner.id(), series.getId()))
                .hasSize(2);
        assertThat(seriesReviewSnapshots(owner.id(), series.getId())).containsExactlyElementsOf(originalSeriesReviews);

        bulkWatch(owner, seriesId, 2, "transition-s2-pass-1");
        assertSeriesTimeline(owner, seriesId,
                List.of("SERIES:" + seriesId + ":pass:1"), List.of(1), List.of(5), List.of(1));

        publishReviewForContent(owner, series.getId(), 6,
                "Series pass one after calendar update", "transition-review-pass-1-after-calendar");
        assertSeriesTimeline(owner, seriesId,
                List.of("SERIES:" + seriesId + ":pass:1"), List.of(1), List.of(6), List.of(2));
        List<ReviewSnapshot> afterSecondPassOneReview = seriesReviewSnapshots(owner.id(), series.getId());
        assertThat(afterSecondPassOneReview).hasSize(3);
        assertThat(afterSecondPassOneReview).extracting(ReviewSnapshot::scoreAtPublication)
                .containsExactly(6, 9, 8);
        assertThat(afterSecondPassOneReview).extracting(ReviewSnapshot::watchNumber)
                .containsExactly(1, 2, 1);
        assertThat(afterSecondPassOneReview.subList(1, 3)).containsExactlyElementsOf(originalSeriesReviews);
        assertThat(afterSecondPassOneReview.getFirst().contextKind()).isEqualTo("COMPLETION");
        assertThat(afterSecondPassOneReview.getFirst().contextJson())
                .isNotEqualTo(originalSeriesReviews.getLast().contextJson());
        String passOneTimeline = mockMvc.perform(get("/users/{userId}/diary/view", owner.id())
                        .cookie(owner.accessToken()).param("type", "SERIES").param("size", "20"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Integer>>read(passOneTimeline,
                "$.content[?(@.watchNumber == 1)].reviewPreviews[*].scoreAtPublication"))
                .containsExactlyInAnyOrder(6, 8);
        assertThat(JsonPath.<List<String>>read(passOneTimeline,
                "$.content[?(@.watchNumber == 1)].reviewPreviews[*].comment"))
                .containsExactlyInAnyOrder("Series pass one", "Series pass one after calendar update");

        bulkWatch(owner, seriesId, 2, "transition-s2-pass-2");
        assertSeriesTimeline(owner, seriesId,
                List.of("SERIES:" + seriesId + ":pass:1", "SERIES:" + seriesId + ":pass:2"),
                List.of(1, 2), List.of(6, 6), List.of(2, 1));
        assertThat(seriesReviewSnapshots(owner.id(), series.getId()))
                .containsExactlyElementsOf(afterSecondPassOneReview);
        String finalSeriesTimeline = mockMvc.perform(get("/users/{userId}/diary/view", owner.id())
                        .cookie(owner.accessToken()).param("type", "SERIES").param("size", "20"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Integer>>read(finalSeriesTimeline,
                "$.content[?(@.watchNumber == 1)].reviewPreviews[*].scoreAtPublication"))
                .containsExactlyInAnyOrder(6, 8);
        assertThat(JsonPath.<List<Integer>>read(finalSeriesTimeline,
                "$.content[?(@.watchNumber == 2)].reviewPreviews[*].scoreAtPublication"))
                .containsExactly(9);
        assertThat(contentRatingRepository.findByUserIdAndContentId(owner.id(), series.getId()))
                .get().extracting(ContentRating::getScore).isEqualTo(6);
        assertThat(diaryEntryRepository.findByUserIdOrderByCreatedAtDesc(owner.id(),
                org.springframework.data.domain.PageRequest.of(0, 100)).getContent())
                .hasSize(12)
                .allSatisfy(entry -> assertThat(entry.getContent().getType()).isEqualTo(ContentType.EPISODE));
    }

    @Test
    @DisplayName("[diaryView] Should Keep Hot Page Query Count Bounded And Refresh Only The Changed Series - With 100 Series And 100000 Events")
    void shouldKeepHotPageQueryCountBoundedAndRefreshOnlyChangedSeriesWithLargeHistory() throws Exception {
        RegisteredUser owner = registerUser();
        User user = userRepository.findById(owner.id()).orElseThrow();
        List<String> seriesIds = seedSyntheticHistory(user, 100, 101, 100, 10);
        stubCompletionScopes();

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        clearInvocations(namedParameterJdbcTemplate, completionScopeLoader, tmdbClient);
        long startedAt = System.nanoTime();
        MvcResult page = mockMvc.perform(get("/users/{userId}/diary/view", owner.id())
                        .cookie(owner.accessToken()).param("page", "0").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(100_000))
                .andExpect(jsonPath("$.content.length()").value(20))
                .andReturn();
        long hotReadMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        long hibernateStatements = statistics.getPrepareStatementCount();
        assertThat(hibernateStatements).isBetween(1L, 20L);
        org.mockito.ArgumentCaptor<String> pageSql = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<SqlParameterSource> pageParameters =
                org.mockito.ArgumentCaptor.forClass(SqlParameterSource.class);
        org.mockito.ArgumentCaptor<String> countSql = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<SqlParameterSource> countParameters =
                org.mockito.ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(namedParameterJdbcTemplate, times(1)).query(
                pageSql.capture(), pageParameters.capture(), any(RowMapper.class));
        verify(namedParameterJdbcTemplate, times(1)).queryForObject(
                countSql.capture(), countParameters.capture(), org.mockito.ArgumentMatchers.eq(Long.class));
        verify(completionScopeLoader, org.mockito.Mockito.never()).load(
                any(MediaReference.class), anyString(), anyString(), any());
        verifyNoTmdbCalls();

        List<String> pagePlan = explainExact(pageSql.getValue(), pageParameters.getValue());
        List<String> countPlan = explainExact(countSql.getValue(), countParameters.getValue());
        System.out.println("B10_DIARY_PAGE_SQL_BEGIN\n" + pageSql.getValue()
                + "\nB10_DIARY_PAGE_SQL_END\nB10_DIARY_PAGE_EXPLAIN_BEGIN\n"
                + String.join("\n", pagePlan) + "\nB10_DIARY_PAGE_EXPLAIN_END");
        System.out.println("B10_DIARY_COUNT_SQL_BEGIN\n" + countSql.getValue()
                + "\nB10_DIARY_COUNT_SQL_END\nB10_DIARY_COUNT_EXPLAIN_BEGIN\n"
                + String.join("\n", countPlan) + "\nB10_DIARY_COUNT_EXPLAIN_END");
        System.out.printf("B10_DIARY_PAGE_METRICS events=100000 series=100 pageSize=20 pageItems=20 "
                        + "totalElements=100000 hibernateStatements=%d namedJdbcStatements=2 totalSqlStatements=%d "
                        + "elapsedMillis=%d%n",
                hibernateStatements, hibernateStatements + 2, hotReadMillis);

        String changedSeriesId = seriesIds.get(50);
        transactionTemplate.executeWithoutResult(status ->
                historyStateRepository.incrementHistoryRevision(owner.id(), changedSeriesId));
        clearInvocations(completionScopeLoader, tmdbClient);
        mockMvc.perform(get("/users/{userId}/diary/view", owner.id())
                        .cookie(owner.accessToken()).param("page", "0").param("size", "20"))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<MediaReference> references =
                org.mockito.ArgumentCaptor.forClass(MediaReference.class);
        verify(completionScopeLoader, atMost(2)).load(
                references.capture(), anyString(), anyString(), any());
        assertThat(references.getAllValues()).hasSizeBetween(1, 2);
        assertThat(references.getAllValues())
                .extracting(reference -> reference.type() == ContentType.SERIES
                        ? reference.tmdbId() : reference.seriesTmdbId())
                .containsOnly(changedSeriesId);
        verifyNoTmdbCalls();

        String otherOwnerSuffix = UUID.randomUUID().toString().substring(0, 8);
        User otherOwner = userRepository.saveAndFlush(User.builder()
                .username("b10cal-" + otherOwnerSuffix)
                .email("b10cal-" + otherOwnerSuffix + "@x.io")
                .password("hashed")
                .name("Calendar observer")
                .isProfilePublic(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());
        historyStateRepository.saveAll(seriesIds.stream()
                .map(seriesId -> UserSeriesHistoryState.builder()
                        .id(new UserSeriesHistoryState.Key(otherOwner.getId(), seriesId))
                        .historyRevision(1_000L)
                        .computedRevision(1_000L)
                        .scheduleVersion(1L)
                        .validUntil(Instant.parse("2099-01-01T00:00:00Z"))
                        .build())
                .toList());
        historyStateRepository.flush();

        reconcileCalendarSchedule(user, changedSeriesId);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM user_series_history_states", Long.class))
                .isEqualTo(200L);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM user_series_history_states
                WHERE user_id = ? AND history_revision = 1000 AND computed_revision = 1000
                  AND schedule_version = 1
                """, Long.class, otherOwner.getId())).isEqualTo(100L);
        assertThat(historyStateRepository.findById(
                new UserSeriesHistoryState.Key(owner.id(), changedSeriesId)).orElseThrow().getScheduleVersion())
                .isEqualTo(1L);

        clearInvocations(completionScopeLoader, tmdbClient);
        mockMvc.perform(get("/users/{userId}/diary/view", owner.id())
                        .cookie(owner.accessToken()).param("page", "0").param("size", "20"))
                .andExpect(status().isOk());
        org.mockito.ArgumentCaptor<MediaReference> calendarReferences =
                org.mockito.ArgumentCaptor.forClass(MediaReference.class);
        verify(completionScopeLoader, atMost(2)).load(
                calendarReferences.capture(), anyString(), anyString(), any());
        assertThat(calendarReferences.getAllValues()).hasSizeBetween(1, 2);
        assertThat(calendarReferences.getAllValues())
                .extracting(reference -> reference.type() == ContentType.SERIES
                        ? reference.tmdbId() : reference.seriesTmdbId())
                .containsOnly(changedSeriesId);
        assertThat(historyStateRepository.findById(
                new UserSeriesHistoryState.Key(owner.id(), changedSeriesId)).orElseThrow().getScheduleVersion())
                .isGreaterThan(1L);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM user_series_history_states
                WHERE user_id = ? AND series_tmdb_id <> ? AND history_revision = 1000
                  AND computed_revision = 1000 AND schedule_version = 1
                """, Long.class, owner.id(), changedSeriesId)).isEqualTo(99L);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM user_series_history_states
                WHERE user_id = ? AND history_revision = 1000 AND computed_revision = 1000
                  AND schedule_version = 1
                """, Long.class, otherOwner.getId())).isEqualTo(100L);
        verifyNoTmdbCalls();
    }

    @Test
    @DisplayName("[reviewPublication] Should Reject A Review When Diary Deletion Wins After Evidence Capture")
    void shouldRejectReviewWhenDiaryDeletionWinsAfterEvidenceCapture() throws Exception {
        RegisteredUser owner = registerUser();
        User user = userRepository.findById(owner.id()).orElseThrow();
        String seriesId = "91002";
        LocalDateTime now = LocalDateTime.now();
        Instant validUntil = Instant.parse("2099-01-01T00:00:00Z");
        contentRepository.saveAndFlush(Content.builder()
                .type(ContentType.SERIES).tmdbId(seriesId).createdAt(now).updatedAt(now).build());
        Content season = contentRepository.saveAndFlush(Content.builder()
                .type(ContentType.SEASON).seriesTmdbId(seriesId).seasonNumber(1)
                .createdAt(now).updatedAt(now).build());
        Content episode = contentRepository.saveAndFlush(Content.builder()
                .type(ContentType.EPISODE).seriesTmdbId(seriesId).seasonNumber(1).episodeNumber(1)
                .createdAt(now).updatedAt(now).build());
        DiaryEntry event = diaryEntryRepository.saveAndFlush(DiaryEntry.builder()
                .user(user).content(episode).watchNumber(1).watchedDate(LocalDate.now().minusDays(1))
                .ignore(false).createdAt(now).updatedAt(now).build());
        historyStateRepository.saveAndFlush(UserSeriesHistoryState.builder()
                .id(new UserSeriesHistoryState.Key(owner.id(), seriesId))
                .historyRevision(1L).computedRevision(1L).scheduleVersion(1L).validUntil(validUntil).build());
        persistCompletionMarker(user, seriesId, CalendarScheduleCompleteness.GroupType.SERIES, null, 1);
        persistCompletionMarker(user, seriesId, CalendarScheduleCompleteness.GroupType.SEASON, 1, 1);
        projectionRepository.saveAndFlush(DiaryCompletionProjection.builder()
                .userId(owner.id()).contentId(season.getId()).contentType(ContentType.SEASON)
                .watchNumber(1).anchorDiaryEntryId(event.getId()).completedOn(LocalDate.now().minusDays(1))
                .orderedAt(Instant.now()).historyRevision(1L).scheduleVersion(1L).validUntil(validUntil).build());
        when(completionScopeLoader.load(any(MediaReference.class), anyString(), anyString(), any()))
                .thenAnswer(invocation -> {
                    MediaReference reference = invocation.getArgument(0, MediaReference.class);
                    return new VersionedCompletionScope(new CompletionScope(reference,
                            Set.of(new EpisodeCoordinate(1, 1)), true), "race-scope", validUntil);
                });

        CountDownLatch evidenceCaptured = new CountDownLatch(1);
        CountDownLatch continuePublication = new CountDownLatch(1);
        doAnswer(invocation -> {
            Object snapshot = invocation.callRealMethod();
            evidenceCaptured.countDown();
            if (!continuePublication.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Review publication was not released by the race test");
            }
            return snapshot;
        }).when(opinionEvidenceService).capture(eq(owner.id()), any(Content.class), eq(true), any(Instant.class));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<MvcResult> publication = executor.submit(() -> mockMvc.perform(
                            post("/contents/{contentId}/reviews", season.getId())
                                    .cookie(owner.accessToken(), owner.csrfToken())
                                    .header("X-XSRF-TOKEN", owner.csrfToken().getValue())
                                    .header("Idempotency-Key", "review-delete-race")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"comment\":\"Race review\",\"score\":7}"))
                    .andReturn());

            assertThat(evidenceCaptured.await(10, TimeUnit.SECONDS)).isTrue();
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .delete("/diary/series/{seriesTmdbId}", seriesId)
                            .cookie(owner.accessToken(), owner.csrfToken())
                            .header("X-XSRF-TOKEN", owner.csrfToken().getValue()))
                    .andExpect(status().isNoContent());
            continuePublication.countDown();

            assertThat(publication.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(409);
            assertThat(diaryEntryRepository.findEpisodeEntriesBySeriesForUser(owner.id(), seriesId)).isEmpty();
            assertThat(contentReviewRepository.findByUserIdAndContentIdOrderByCreatedAtDesc(owner.id(), season.getId()))
                    .isEmpty();
            assertThat(contentRatingRepository.findByUserIdAndContentId(owner.id(), season.getId())).isEmpty();
        } finally {
            continuePublication.countDown();
            executor.shutdownNow();
        }
    }

    private void bulkWatch(RegisteredUser owner, int seasonNumber, String idempotencyKey) throws Exception {
        bulkWatch(owner, SERIES_TMDB_ID, seasonNumber, idempotencyKey);
    }

    private void bulkWatch(RegisteredUser owner, String seriesId, int seasonNumber, String idempotencyKey)
            throws Exception {
        mockMvc.perform(post("/diary/bulk")
                        .cookie(owner.accessToken(), owner.csrfToken())
                        .header("X-XSRF-TOKEN", owner.csrfToken().getValue())
                        .header("Idempotency-Key", idempotencyKey)
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
                                """.formatted(seriesId, seasonNumber,
                                LocalDate.now().minusDays(30 - seasonNumber))))
                .andExpect(status().isCreated());
    }

    private void publishReviewForContent(RegisteredUser owner, UUID contentId, int score,
            String comment, String idempotencyKey) throws Exception {
        String expectedVersion = contentRatingRepository.findByUserIdAndContentId(owner.id(), contentId)
                .map(rating -> ",\"expectedRatingVersion\":" + rating.getVersion())
                .orElse("");
        mockMvc.perform(post("/contents/{contentId}/reviews", contentId)
                        .cookie(owner.accessToken(), owner.csrfToken())
                        .header("X-XSRF-TOKEN", owner.csrfToken().getValue())
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"" + comment + "\",\"score\":" + score
                                + expectedVersion + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.scoreAtPublication").value(score))
                .andExpect(jsonPath("$.contextKind").value("COMPLETION"));
    }

    private void assertSeriesTimeline(RegisteredUser owner, String seriesId, List<String> completionKeys,
            List<Integer> watchNumbers, List<Integer> scores, List<Integer> reviewCounts) throws Exception {
        String timeline = mockMvc.perform(get("/users/{userId}/diary/view", owner.id())
                        .cookie(owner.accessToken()).param("type", "SERIES").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(completionKeys.size()))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(timeline, "$.content[*].completionKey"))
                .containsExactlyInAnyOrderElementsOf(completionKeys);
        assertThat(JsonPath.<List<Integer>>read(timeline, "$.content[*].watchNumber"))
                .containsExactlyInAnyOrderElementsOf(watchNumbers);
        assertThat(JsonPath.<List<Integer>>read(timeline, "$.content[*].currentScore"))
                .containsExactlyInAnyOrderElementsOf(scores);
        assertThat(JsonPath.<List<Integer>>read(timeline, "$.content[*].reviewCount"))
                .containsExactlyInAnyOrderElementsOf(reviewCounts);
        List<java.util.Map<String, Object>> items = JsonPath.read(timeline, "$.content");
        if (completionKeys.isEmpty()) {
            assertThat(items).isEmpty();
            return;
        }
        assertThat(JsonPath.<List<String>>read(timeline, "$.content[*].content.tmdbId"))
                .containsOnly(seriesId);
        assertThat(JsonPath.<List<String>>read(timeline, "$.content[*].kind"))
                .containsOnly("DERIVED_COMPLETION");
        assertThat(items).allSatisfy(item -> assertThat(item.get("diaryEntryId")).isNull());
    }

    private void assertSeasonTimeline(RegisteredUser owner, String seriesId, List<String> completionKeys,
            List<Integer> watchNumbers) throws Exception {
        String timeline = mockMvc.perform(get("/users/{userId}/diary/view", owner.id())
                        .cookie(owner.accessToken()).param("type", "SEASON").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(completionKeys.size()))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(timeline, "$.content[*].completionKey"))
                .containsExactlyInAnyOrderElementsOf(completionKeys);
        assertThat(JsonPath.<List<Integer>>read(timeline, "$.content[*].watchNumber"))
                .containsExactlyInAnyOrderElementsOf(watchNumbers);
        assertThat(JsonPath.<List<String>>read(timeline, "$.content[*].content.seriesTmdbId"))
                .containsOnly(seriesId);
    }

    private List<ReviewSnapshot> seriesReviewSnapshots(UUID ownerId, UUID contentId) {
        return contentReviewRepository.findByUserIdAndContentIdOrderByCreatedAtDesc(ownerId, contentId).stream()
                .map(review -> new ReviewSnapshot(review.getScoreAtPublication(), review.getWatchNumber(),
                        review.getContextKind().name(), review.getComment(), review.getContextJson()))
                .toList();
    }

    private void publishReview(RegisteredUser owner, int seasonNumber, int score, String comment) throws Exception {
        Content season = season(seasonNumber);
        String expectedVersion = contentRatingRepository.findByUserIdAndContentId(owner.id(), season.getId())
                .map(rating -> ",\"expectedRatingVersion\":" + rating.getVersion())
                .orElse("");
        mockMvc.perform(post("/contents/{contentId}/reviews", season.getId())
                        .cookie(owner.accessToken(), owner.csrfToken())
                        .header("X-XSRF-TOKEN", owner.csrfToken().getValue())
                        .header("Idempotency-Key", "review-" + seasonNumber + "-" + score)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"" + comment + "\",\"score\":" + score
                                + expectedVersion + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.scoreAtPublication").value(score))
                .andExpect(jsonPath("$.contextKind").value("COMPLETION"));
    }

    private Content season(int seasonNumber) {
        return contentRepository.findByTypeAndSeriesTmdbIdAndSeasonNumberIn(
                ContentType.SEASON, SERIES_TMDB_ID, List.of(seasonNumber)).getFirst();
    }

    private void persistCalendarMarkers(User owner) {
        LocalDateTime now = LocalDateTime.now();
        completenessRepository.saveAndFlush(CalendarScheduleCompleteness.builder()
                .groupType(CalendarScheduleCompleteness.GroupType.SERIES)
                .seriesTmdbId(SERIES_TMDB_ID)
                .region(owner.getPreferredRegion())
                .language(owner.getPreferredLanguage())
                .expectedEpisodeCount(6)
                .complete(true)
                .lastCheckedAt(now)
                .scopeVersion(1L)
                .build());
        for (int seasonNumber = 1; seasonNumber <= 2; seasonNumber++) {
            completenessRepository.saveAndFlush(CalendarScheduleCompleteness.builder()
                    .groupType(CalendarScheduleCompleteness.GroupType.SEASON)
                    .seriesTmdbId(SERIES_TMDB_ID)
                    .seasonNumber(seasonNumber)
                    .region(owner.getPreferredRegion())
                    .language(owner.getPreferredLanguage())
                    .expectedEpisodeCount(3)
                    .complete(true)
                    .lastCheckedAt(now)
                    .scopeVersion(1L)
                    .build());
        }
    }

    private void persistCompletionMarker(User owner, String seriesId,
            CalendarScheduleCompleteness.GroupType groupType, Integer seasonNumber, int episodeCount) {
        completenessRepository.saveAndFlush(CalendarScheduleCompleteness.builder()
                .groupType(groupType)
                .seriesTmdbId(seriesId)
                .seasonNumber(seasonNumber)
                .region(owner.getPreferredRegion())
                .language(owner.getPreferredLanguage())
                .expectedEpisodeCount(episodeCount)
                .complete(true)
                .lastCheckedAt(LocalDateTime.now())
                .scopeVersion(1L)
                .build());
    }

    private void reconcileCalendarSchedule(User owner, String seriesId) {
        Instant checkedAt = Instant.now().plusSeconds(60);
        Instant nextCheckAt = Instant.parse("2099-01-01T00:00:00Z");
        List<CalendarEpisodeSchedule> episodes = IntStream.rangeClosed(1, 101)
                .mapToObj(number -> new CalendarEpisodeSchedule(
                        number,
                        "Episode " + number,
                        LocalDate.of(2020, 1, 1).plusDays(number),
                        null,
                        checkedAt,
                        nextCheckAt))
                .toList();
        CalendarSeasonSchedule seasonSchedule = new CalendarSeasonSchedule(
                seriesId,
                1,
                owner.getPreferredRegion(),
                owner.getPreferredLanguage(),
                "Synthetic series",
                null,
                episodes);
        CalendarSeriesSchedule schedule = new CalendarSeriesSchedule(
                new CalendarScheduleKey(ContentType.SERIES, seriesId,
                        owner.getPreferredLanguage(), owner.getPreferredRegion()),
                List.of(new CalendarSeriesSchedule.Season(seasonSchedule, 101, TmdbLookupOrigin.REMOTE)),
                java.util.Map.of(1, 101),
                101,
                true);

        assertThat(calendarScheduleSnapshotStore.reconcileSeries(schedule, checkedAt)).isTrue();
    }

    private List<String> seedSyntheticHistory(User owner, int seriesCount, int releasedEpisodeCountPerSeries,
            int watchedEpisodeCountPerSeries, int watchesPerEpisode) {
        Instant farFuture = Instant.parse("2099-01-01T00:00:00Z");
        LocalDateTime now = LocalDateTime.now();
        List<String> seriesIds = new ArrayList<>(seriesCount);
        List<ContentFixture> episodes = new ArrayList<>(seriesCount * releasedEpisodeCountPerSeries);
        for (int seriesNumber = 0; seriesNumber < seriesCount; seriesNumber++) {
            String seriesId = "b10-perf-" + seriesNumber;
            seriesIds.add(seriesId);
            jdbcTemplate.update("""
                    INSERT INTO contents (id, type, tmdb_id, created_at, updated_at)
                    VALUES (?, 'SERIES', ?, ?, ?)
                    """, UUID.randomUUID(), seriesId, Timestamp.valueOf(now), Timestamp.valueOf(now));
            for (int episodeNumber = 1; episodeNumber <= releasedEpisodeCountPerSeries; episodeNumber++) {
                episodes.add(new ContentFixture(UUID.randomUUID(), seriesId, episodeNumber));
            }
        }
        jdbcTemplate.batchUpdate("""
                INSERT INTO contents (id, type, series_tmdb_id, season_number, episode_number, created_at, updated_at)
                VALUES (?, 'EPISODE', ?, 1, ?, ?, ?)
                """, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement statement, int index) throws SQLException {
                ContentFixture episode = episodes.get(index);
                statement.setObject(1, episode.id());
                statement.setString(2, episode.seriesTmdbId());
                statement.setInt(3, episode.episodeNumber());
                statement.setTimestamp(4, Timestamp.valueOf(now));
                statement.setTimestamp(5, Timestamp.valueOf(now));
            }

            @Override
            public int getBatchSize() {
                return episodes.size();
            }
        });

        for (String seriesId : seriesIds) {
            historyStateRepository.save(UserSeriesHistoryState.builder()
                    .id(new UserSeriesHistoryState.Key(owner.getId(), seriesId))
                    .historyRevision((long) watchedEpisodeCountPerSeries * watchesPerEpisode)
                    .computedRevision((long) watchedEpisodeCountPerSeries * watchesPerEpisode)
                    .scheduleVersion(1L)
                    .validUntil(farFuture)
                    .build());
            completenessRepository.save(CalendarScheduleCompleteness.builder()
                    .groupType(CalendarScheduleCompleteness.GroupType.SERIES)
                    .seriesTmdbId(seriesId)
                    .region(owner.getPreferredRegion())
                    .language(owner.getPreferredLanguage())
                    .expectedEpisodeCount(releasedEpisodeCountPerSeries)
                    .complete(true)
                    .lastCheckedAt(now)
                    .scopeVersion(1L)
                    .build());
        }
        historyStateRepository.flush();
        completenessRepository.flush();

        LocalDateTime firstEventAt = LocalDateTime.of(2020, 1, 1, 0, 0);
        int totalEventCount = seriesCount * watchedEpisodeCountPerSeries * watchesPerEpisode;
        for (int chunkStart = 0; chunkStart < totalEventCount; chunkStart += 5_000) {
            int chunkSize = Math.min(5_000, totalEventCount - chunkStart);
            int start = chunkStart;
            jdbcTemplate.batchUpdate("""
                    INSERT INTO diary_entries
                        (id, user_id, content_id, watch_number, watched_date, created_at, updated_at, "ignore", likes_count)
                    VALUES (?, ?, ?, ?, ?, ?, ?, false, 0)
                    """, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement statement, int row) throws SQLException {
                    int eventNumber = start + row;
                    int seriesEventCount = watchedEpisodeCountPerSeries * watchesPerEpisode;
                    int seriesIndex = eventNumber / seriesEventCount;
                    int inSeriesEventNumber = eventNumber % seriesEventCount;
                    int episodeIndex = seriesIndex * releasedEpisodeCountPerSeries
                            + inSeriesEventNumber / watchesPerEpisode;
                    int watchNumber = inSeriesEventNumber % watchesPerEpisode + 1;
                    Timestamp createdAt = Timestamp.valueOf(firstEventAt.plusSeconds(eventNumber));
                    statement.setObject(1, UUID.randomUUID());
                    statement.setObject(2, owner.getId());
                    statement.setObject(3, episodes.get(episodeIndex).id());
                    statement.setInt(4, watchNumber);
                    statement.setObject(5, java.sql.Date.valueOf(LocalDate.of(2020, 1, 1)));
                    statement.setTimestamp(6, createdAt);
                    statement.setTimestamp(7, createdAt);
                }

                @Override
                public int getBatchSize() {
                    return chunkSize;
                }
            });
        }
        return List.copyOf(seriesIds);
    }

    private void stubCompletionScopes() {
        when(completionScopeLoader.load(any(MediaReference.class), anyString(), anyString(), any()))
                .thenAnswer(invocation -> {
                    MediaReference reference = invocation.getArgument(0, MediaReference.class);
                    Set<EpisodeCoordinate> coordinates = reference.type() == ContentType.SEASON
                            ? IntStream.rangeClosed(1, 101)
                                    .mapToObj(number -> new EpisodeCoordinate(reference.seasonNumber(), number))
                                    .collect(java.util.stream.Collectors.toSet())
                            : IntStream.rangeClosed(1, 101)
                                    .mapToObj(number -> new EpisodeCoordinate(1, number))
                                    .collect(java.util.stream.Collectors.toSet());
                    return new VersionedCompletionScope(new CompletionScope(reference, coordinates, true),
                            "performance-" + reference.type(), Instant.parse("2099-01-01T00:00:00Z"));
                });
    }

    private List<String> explainExact(String sql, SqlParameterSource parameters) {
        return namedParameterJdbcTemplate.query(
                "EXPLAIN (ANALYZE, BUFFERS) " + sql,
                parameters,
                (resultSet, row) -> resultSet.getString(1));
    }

    private void verifyNoTmdbCalls() {
        org.mockito.Mockito.verifyNoInteractions(tmdbClient);
    }

    private RegisteredUser registerUser() throws Exception {
        String username = "diaryfeature" + UUID.randomUUID().toString().replace("-", "");
        MvcResult result = mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "%s",
                                  "name": "Diary Feature",
                                  "email": "%s@example.com",
                                  "password": "Password123",
                                  "isProfilePublic": true
                                }
                                """.formatted(username, username)))
                .andExpect(status().isCreated())
                .andReturn();
        User user = userRepository.findByUsernameIgnoreCaseOrEmailIgnoreCase(username, username).orElseThrow();
        return new RegisteredUser(user.getId(),
                result.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE),
                result.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE));
    }

    private void stubTmdb() {
        when(tmdbClient.getTvFullDetails(anyString(), anyString()))
                .thenAnswer(invocation -> foundTvWithRegularSeasons(invocation.getArgument(0, String.class), 3, 3));
        when(tmdbClient.getSeasonFullDetails(anyString(), any(), anyString()))
                .thenAnswer(invocation -> foundSeasonWithEpisodeCount(invocation.getArgument(1, Integer.class)));
    }

    private TmdbLookupResult<TmdbSeasonFullDetails> foundSeasonWithEpisodeCount(int seasonNumber) {
        List<TmdbEpisodeSummary> episodes = IntStream.rangeClosed(1, 3)
                .mapToObj(number -> new TmdbEpisodeSummary(number, null, null, "2020-01-01", 45, null, null))
                .toList();
        return new TmdbLookupResult.Found<>(new TmdbSeasonFullDetails(
                null, null, null, null, null, seasonNumber, episodes, null, null));
    }

    private TmdbLookupResult<TmdbTvFullDetails> foundTvWithRegularSeasons(
            String seriesTmdbId, int firstSeasonEpisodes, int secondSeasonEpisodes) {
        return new TmdbLookupResult.Found<>(new TmdbTvFullDetails(
                seriesTmdbId, null, null, null, null, null, null, null, null, null, null,
                List.of(
                        new TmdbSeasonSummary(1, "Season 1", null, null, firstSeasonEpisodes, null),
                        new TmdbSeasonSummary(2, "Season 2", null, null, secondSeasonEpisodes, null)),
                null, null, null, null, null, null, null, null, null, null));
    }

    private record RegisteredUser(UUID id, Cookie accessToken, Cookie csrfToken) {
    }

    private record ContentFixture(UUID id, String seriesTmdbId, int episodeNumber) {
    }

    private record ReviewSnapshot(
            Integer scoreAtPublication, Integer watchNumber, String contextKind, String comment, String contextJson) {
    }
}
