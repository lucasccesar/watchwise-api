package com.watchwise.watchwise_api.platformimport.service;

import com.watchwise.watchwise_api.content.dto.MediaReference;
import com.watchwise.watchwise_api.platformimport.entity.ImportItem;
import com.watchwise.watchwise_api.platformimport.entity.ImportJob;
import com.watchwise.watchwise_api.platformimport.model.AliasQuery;
import com.watchwise.watchwise_api.platformimport.model.AliasResolution;
import com.watchwise.watchwise_api.platformimport.model.ImportInputKind;
import com.watchwise.watchwise_api.platformimport.model.ImportDecision;
import com.watchwise.watchwise_api.platformimport.model.ImportJobState;
import com.watchwise.watchwise_api.platformimport.model.ImportPlatform;
import com.watchwise.watchwise_api.platformimport.model.ImportRecordKind;
import com.watchwise.watchwise_api.platformimport.model.ImportReadContext;
import com.watchwise.watchwise_api.platformimport.model.ImportSourceFormat;
import com.watchwise.watchwise_api.platformimport.model.ImportTarget;
import com.watchwise.watchwise_api.platformimport.model.NormalizedImportRecord;
import com.watchwise.watchwise_api.platformimport.parser.CsvRecordReader;
import com.watchwise.watchwise_api.platformimport.parser.impl.LetterboxdHistoryCsvParser;
import com.watchwise.watchwise_api.platformimport.parser.impl.TraktItemParserImpl;
import com.watchwise.watchwise_api.platformimport.repository.ImportItemRepository;
import com.watchwise.watchwise_api.platformimport.repository.ImportJobRepository;
import com.watchwise.watchwise_api.platformimport.service.ImportLimits;
import com.watchwise.watchwise_api.platformimport.service.ImportIdentityNormalizer;
import com.watchwise.watchwise_api.platformimport.service.impl.ImportWorker;
import com.watchwise.watchwise_api.platformimport.service.impl.PlatformContentResolver;
import com.watchwise.watchwise_api.platformimport.service.ImportExecutionContext;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;

@SpringBootTest(properties = {
        "spring.docker.compose.enabled=false",
        "app.import.worker.enabled=false",
        "app.import.cleanup.enabled=false",
        "spring.jpa.show-sql=false",
        "logging.level.org.hibernate.SQL=OFF"
})
@Testcontainers
@Import(HistoryNormalizationTest.TestConfigurationBeans.class)
class HistoryNormalizationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.import.storage-directory", () -> java.nio.file.Path.of(
                System.getProperty("java.io.tmpdir"), "watchwise-history-normalization-tests").toAbsolutePath().toString());
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private ImportJobRepository jobRepository;
    @Autowired private ImportItemRepository itemRepository;
    @Autowired private ImportWorker worker;
    @Autowired private PlatformContentResolver contentResolver;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private Clock clock;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TestAliasService aliasService;
    @MockitoSpyBean private ImportIdentityNormalizer identityNormalizer;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE import_items, external_import_links, external_content_aliases, imported_watch_assertions, import_deferred_records, letterboxd_seed_loads, import_file_cleanup_tasks, import_jobs, users CASCADE");
        aliasService.reset();
    }

    @Test
    @DisplayName("[normalize] Should Merge Diary Review Watched And Rating Fragments - When They Reference One Movie")
    void shouldMergeDiaryReviewWatchedAndRatingFragmentsWhenTheyReferenceOneMovie() throws Exception {
        User owner = createUser();
        ImportJob job = createResolvingJob(owner.getId(), ImportSourceFormat.LETTERBOXD_ZIP, "letterboxd:test");
        ImportTarget movie = contentTarget(MediaReference.movie("603"));
        String eventKey = "https://letterboxd.com/user/film/arrival/";

        item(job, ImportRecordKind.DIARY_EVENT, "diary-1", eventKey, movie,
                payload("Arrival", 2016, "2025-05-30", 7, null), 1);
        item(job, ImportRecordKind.REVIEW, "review-1", eventKey, movie,
                payload("Arrival", 2016, null, 8, "A thoughtful review."), 2);
        item(job, ImportRecordKind.WATCHED_MARKER, "watched-1", eventKey, movie,
                payload("Arrival", 2016, null, null, null), 3);
        item(job, ImportRecordKind.CURRENT_RATING, "rating-1", eventKey, movie,
                payload("Arrival", 2016, null, 9, null), 4);

        run(job);

        List<ImportItem> rows = currentItems(job);
        assertThat(rows).filteredOn(row -> row.getNormalizedKind() == ImportRecordKind.DIARY_EVENT
                        && "MATCHED".equals(row.getOutcome()))
                .singleElement()
                .satisfies(event -> {
                    var payload = read(event.getPayloadJson());
                    assertThat(payload.path("watchedOn").asString()).isEqualTo("2025-05-30");
                    assertThat(payload.path("score").asInt()).isEqualTo(9);
                    assertThat(payload.path("review").asString()).isEqualTo("A thoughtful review.");
                });
        assertThat(rows).filteredOn(row -> "ABSORBED".equals(row.getOutcome())).hasSize(3);
    }

    @Test
    @DisplayName("[normalize] Should Bound Event Group Reads - When One Source Event Has Hundreds Of Fragments")
    void shouldBoundEventGroupReadsWhenOneSourceEventHasHundredsOfFragments() throws Exception {
        User owner = createUser();
        ImportJob job = createResolvingJob(owner.getId(), ImportSourceFormat.LETTERBOXD_ZIP, "letterboxd:hot-key");
        ImportTarget movie = contentTarget(MediaReference.movie("603"));
        List<ImportItem> hotGroup = new ArrayList<>();
        for (int index = 0; index < 401; index++) {
            hotGroup.add(stagedItem(job, 1, ImportRecordKind.REVIEW, "review-" + index, "one-hot-event",
                    movie, payload("Arrival", 2016, null, 8, "Review."), index));
        }
        itemRepository.saveAllAndFlush(hotGroup);

        List<ImportItem> fetched = new ArrayList<>();
        String afterEventKey = "";
        long afterOrdinal = -1;
        UUID afterId = new UUID(0, 0);
        int largestPage = 0;
        while (true) {
            List<ImportItem> page = itemRepository.findHistoryEventFragmentsAfter(job.getId(), owner.getId(), 1,
                    List.of("one-hot-event"), List.of(ImportRecordKind.REVIEW), List.of("MATCHED"),
                    afterEventKey, afterOrdinal, afterId, org.springframework.data.domain.PageRequest.of(0, 200));
            largestPage = Math.max(largestPage, page.size());
            if (page.isEmpty()) break;
            fetched.addAll(page);
            ImportItem last = page.getLast();
            afterEventKey = last.getSourceEventKey();
            afterOrdinal = last.getOrdinal();
            afterId = last.getId();
            if (page.size() < 200) break;
        }

        assertThat(largestPage).isLessThanOrEqualTo(200);
        assertThat(fetched).hasSize(401).extracting(ImportItem::getSourceId).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("[normalize] Should Keep Event Fragments Across Pages - When A Source Event Spans The Batch Boundary")
    void shouldKeepEventFragmentsAcrossPagesWhenASourceEventSpansTheBatchBoundary() throws Exception {
        User owner = createUser();
        ImportJob job = createResolvingJob(owner.getId(), ImportSourceFormat.LETTERBOXD_ZIP, "letterboxd:page-boundary");
        ImportTarget movie = contentTarget(MediaReference.movie("603"));
        List<ImportItem> eventRows = new ArrayList<>();
        eventRows.add(stagedItem(job, 1, ImportRecordKind.DIARY_EVENT, "diary-1", "one-hot-event",
                movie, payload("Arrival", 2016, "2025-01-01", 5, null), 1));
        for (int index = 0; index < 199; index++) {
            ImportItem skipped = stagedItem(job, 1, ImportRecordKind.REVIEW, "skipped-review-" + index,
                    "one-hot-event", movie, payload("Arrival", 2016, null, null, null), index + 2);
            skipped.setDecision(ImportDecision.SKIP_ITEM);
            eventRows.add(skipped);
        }
        ImportItem review = stagedItem(job, 1, ImportRecordKind.REVIEW, "review-last", "one-hot-event",
                movie, payload("Arrival", 2016, null, 9, "Review on the next page."), 201);
        eventRows.add(review);
        itemRepository.saveAllAndFlush(eventRows);

        run(job);

        ImportItem normalizedDiary = itemRepository.findByJobIdAndPlanGenerationOrderByOrdinalAscIdAsc(
                job.getId(), 1, org.springframework.data.domain.PageRequest.of(0, 205)).getContent().stream()
                .filter(row -> "diary-1".equals(row.getSourceId())).findFirst().orElseThrow();
        assertThat(read(normalizedDiary.getPayloadJson()).path("score").asInt()).isEqualTo(9);
        assertThat(read(normalizedDiary.getPayloadJson()).path("review").asString())
                .isEqualTo("Review on the next page.");
        assertThat(itemRepository.findById(review.getId()).orElseThrow().getOutcome()).isEqualTo("ABSORBED");
    }

    @Test
    @DisplayName("[normalize] Should Compare Hot Event Identities Linearly - When Many Fragments Share One Key")
    void shouldCompareHotEventIdentitiesLinearlyWhenManyFragmentsShareOneKey() throws Exception {
        User owner = createUser();
        ImportJob job = createResolvingJob(owner.getId(), ImportSourceFormat.LETTERBOXD_ZIP, "letterboxd:linear-key");
        ImportTarget movie = contentTarget(MediaReference.movie("603"));
        List<ImportItem> hotGroup = new ArrayList<>();
        for (int index = 0; index < 301; index++) {
            hotGroup.add(stagedItem(job, 1, ImportRecordKind.REVIEW, "review-" + index, "one-hot-event",
                    movie, payload("Arrival", 2016, null, 8, "Review."), index));
        }
        itemRepository.saveAllAndFlush(hotGroup);
        clearInvocations(identityNormalizer);

        ImportJob waiting = runWorker(job);

        assertThat(waiting.getState()).isEqualTo(ImportJobState.RESOLVING);
        verify(identityNormalizer, atMost(600)).normalizeTitle(anyString());
    }

    @Test
    @DisplayName("[normalize] Should Detect Conflicting Known Identity Fields - When The First Fragment Omits A Year")
    void shouldDetectConflictingKnownIdentityFieldsWhenTheFirstFragmentOmitsAYear() throws Exception {
        User owner = createUser();
        ImportJob job = createResolvingJob(owner.getId(), ImportSourceFormat.LETTERBOXD_ZIP,
                "letterboxd:partial-identity");
        ImportTarget movie = contentTarget(MediaReference.movie("603"));
        item(job, ImportRecordKind.DIARY_EVENT, "diary-1", "one-event", movie,
                payload("Arrival", null, "2025-01-01", 5, null), 1);
        item(job, ImportRecordKind.REVIEW, "review-1", "one-event", movie,
                payload("Arrival", 2016, null, 8, "Review one."), 2);
        ImportItem conflictingReview = item(job, ImportRecordKind.REVIEW, "review-2", "one-event", movie,
                payload("Arrival", 2017, null, 9, "Review two."), 3);

        runWorker(job);

        assertThat(itemRepository.findById(conflictingReview.getId()).orElseThrow().getIssue())
                .contains("incompatible identity payloads");
    }

    @Test
    @DisplayName("[normalize] Should Ignore Resolved And Skipped Issues - When No Actionable Issue Remains")
    void shouldIgnoreResolvedAndSkippedIssuesWhenNoActionableIssueRemains() throws Exception {
        User owner = createUser();
        ImportJob job = createResolvingJob(owner.getId(), ImportSourceFormat.LETTERBOXD_ZIP, "letterboxd:skipped-issue");
        ImportItem skippedIssue = item(job, ImportRecordKind.REVIEW, "skipped-review", "skipped-review", null,
                payload("Arrival", 2016, null, 8, "Review."), 1);
        skippedIssue.setOutcome("NORMALIZATION_ISSUE");
        skippedIssue.setIssue("Previously reported identity conflict.");
        skippedIssue.setDecision(ImportDecision.SKIP_ITEM);
        itemRepository.saveAndFlush(skippedIssue);
        ImportItem resolvedIssue = item(job, ImportRecordKind.REVIEW, "resolved-review", "resolved-review", null,
                payload("Arrival", 2016, null, 8, "Review."), 2);
        resolvedIssue.setOutcome("NORMALIZATION_ISSUE");
        resolvedIssue.setIssue("Previously resolved identity conflict.");
        resolvedIssue.setDecision(ImportDecision.RESOLVE_TARGET);
        itemRepository.saveAndFlush(resolvedIssue);

        ImportJob completed = runWorker(job);

        assertThat(completed.getState()).isEqualTo(ImportJobState.READY);
    }

    @Test
    @DisplayName("[normalize] Should Merge Direct Trakt References Without Queries - When Event Keys Match")
    void shouldMergeDirectTraktReferencesWithoutQueriesWhenEventKeysMatch() throws Exception {
        User owner = createUser();
        ImportJob job = createResolvingJob(owner.getId(), ImportSourceFormat.TRAKT_ZIP, "trakt:direct-reference");
        TraktItemParserImpl parser = new TraktItemParserImpl(objectMapper);
        ImportReadContext readContext = new ImportReadContext(owner.getId(), job.getId(), job.getStorageKey(),
                job.getSourceNamespace(), ZoneId.of("UTC"));
        NormalizedImportRecord diary = parseTraktRecord(parser, readContext, ImportRecordKind.DIARY_EVENT,
                "[{\"id\":\"17\",\"type\":\"movie\",\"movie\":{\"title\":\"Arrival\",\"year\":2016,\"ids\":{\"tmdb\":603}},\"watched_at\":\"2025-01-01T12:00:00Z\",\"rating\":5}]");
        NormalizedImportRecord review = parseTraktRecord(parser, readContext, ImportRecordKind.REVIEW,
                "[{\"id\":\"17\",\"type\":\"movie\",\"movie\":{\"title\":\"Arrival Redux\",\"year\":2018,\"ids\":{\"tmdb\":603}},\"watched_at\":\"2025-01-01T12:00:00Z\",\"rating\":9,\"review\":\"Direct reference review.\"}]");
        assertThat(diary.target()).isEqualTo(contentTarget(MediaReference.movie("603")));
        assertThat(diary.payload().path("sourceQuery").isMissingNode()).isTrue();
        assertThat(review.target()).isEqualTo(contentTarget(MediaReference.movie("603")));
        assertThat(review.payload().path("sourceQuery").isMissingNode()).isTrue();
        itemRepository.saveAllAndFlush(List.of(
                stagedItem(job, 1, diary, "17", 1),
                stagedItem(job, 1, review, "17", 2)));

        ImportJob completed = runWorker(job);

        assertThat(completed.getState()).isEqualTo(ImportJobState.READY);
        ImportItem normalizedDiary = itemRepository.findByJobIdAndPlanGenerationOrderByOrdinalAscIdAsc(
                job.getId(), 1, org.springframework.data.domain.PageRequest.of(0, 5)).getContent().stream()
                .filter(row -> row.getNormalizedKind() == ImportRecordKind.DIARY_EVENT).findFirst().orElseThrow();
        assertThat(read(normalizedDiary.getPayloadJson()).path("score").asInt()).isEqualTo(9);
        assertThat(read(normalizedDiary.getPayloadJson()).path("review").asString())
                .isEqualTo("Direct reference review.");
    }

    @Test
    @DisplayName("[normalize] Should Preserve A Series Identity Conflict - When The Resolved Target Is Shared")
    void shouldPreserveASeriesIdentityConflictWhenTheResolvedTargetIsShared() throws Exception {
        User owner = createUser();
        ImportJob job = createResolvingJob(owner.getId(), ImportSourceFormat.LETTERBOXD_ZIP,
                "letterboxd:series-identity-conflict");
        ImportTarget series = contentTarget(MediaReference.series("123"));
        ObjectNode eventPayload = payload("Example Series", 2020, null, null, null);
        ((ObjectNode) eventPayload.get("sourceQuery")).put("contentType", "SERIES");
        ObjectNode reviewPayload = payload("Different Series", 2020, null, 8, "Conflicting identity.");
        ((ObjectNode) reviewPayload.get("sourceQuery")).put("contentType", "SERIES");
        ImportItem diary = item(job, ImportRecordKind.DIARY_EVENT, "series-event", "series-event", series,
                eventPayload, 1);
        ImportItem review = item(job, ImportRecordKind.REVIEW, "series-review", "series-event", series,
                reviewPayload, 2);

        ImportJob waiting = runWorker(job);

        assertThat(waiting.getState()).isEqualTo(ImportJobState.RESOLVING);
        assertThat(itemRepository.findById(diary.getId()).orElseThrow().getOutcome()).isEqualTo("NORMALIZATION_ISSUE");
        assertThat(itemRepository.findById(review.getId()).orElseThrow().getOutcome()).isEqualTo("NORMALIZATION_ISSUE");
    }

    @Test
    @DisplayName("[normalize] Should Continue Past A Skipped Prefix - When Later Target Fragments Are Applicable")
    void shouldContinuePastASkippedPrefixWhenLaterTargetFragmentsAreApplicable() throws Exception {
        User owner = createUser();
        ImportJob job = createResolvingJob(owner.getId(), ImportSourceFormat.TRAKT_ZIP, "trakt:skip-prefix");
        ImportTarget movie = contentTarget(MediaReference.movie("603"));
        List<ImportItem> skippedPrefix = new ArrayList<>();
        for (int index = 0; index < 200; index++) {
            ImportItem skipped = stagedItem(job, 1, ImportRecordKind.WATCHED_MARKER,
                    "skipped-marker-" + index, "skip-marker-" + index, movie,
                    payload("Arrival", 2016, null, null, null), index + 1);
            skipped.setDecision(ImportDecision.SKIP_ITEM);
            skippedPrefix.add(skipped);
        }
        itemRepository.saveAllAndFlush(skippedPrefix);
        item(job, ImportRecordKind.DIARY_EVENT, "diary-1", "diary-1", movie,
                payload("Arrival", 2016, "2025-01-01", 5, null), 201);
        ImportItem applicableMarker = item(job, ImportRecordKind.WATCHED_MARKER, "marker-201", "marker-201", movie,
                payload("Arrival", 2016, null, null, null), 202);

        run(job);

        assertThat(itemRepository.findById(applicableMarker.getId()).orElseThrow().getOutcome()).isEqualTo("ABSORBED");
    }

    @Test
    @DisplayName("[normalize] Should Keep Same Day Events Separate - When Their Source Event Ids Differ")
    void shouldKeepSameDayEventsSeparateWhenTheirSourceEventIdsDiffer() throws Exception {
        User owner = createUser();
        ImportJob job = createResolvingJob(owner.getId(), ImportSourceFormat.LETTERBOXD_ZIP, "letterboxd:test");
        ImportTarget movie = contentTarget(MediaReference.movie("603"));
        for (int index = 0; index < 199; index++) {
            item(job, ImportRecordKind.DIARY_EVENT, "filler-" + index, "filler-" + index, movie,
                    payload("Arrival", 2016, "2025-05-30", null, null), index);
        }
        item(job, ImportRecordKind.DIARY_EVENT, "diary-1", "event-1", movie,
                payload("Arrival", 2016, "2025-05-30", 6, null), 199);
        item(job, ImportRecordKind.REVIEW, "review-1", "event-1", movie,
                payload("Arrival", 2016, null, 7, "First event review."), 200);
        item(job, ImportRecordKind.DIARY_EVENT, "diary-2", "event-2", movie,
                payload("Arrival", 2016, "2025-05-30", 8, null), 201);

        run(job);

        List<ImportItem> rows = currentItems(job);
        assertThat(rows).filteredOn(row -> row.getNormalizedKind() == ImportRecordKind.DIARY_EVENT
                        && "MATCHED".equals(row.getOutcome()))
                .hasSize(201)
                .extracting(ImportItem::getSourceEventKey)
                .contains("event-1", "event-2");
        assertThat(rows).filteredOn(row -> "ABSORBED".equals(row.getOutcome())).hasSize(1);
    }

    @Test
    @DisplayName("[resolve] Should Search Once - When One Hundred Records Repeat The Same Title")
    void shouldSearchOnceWhenOneHundredRecordsRepeatTheSameTitle() throws Exception {
        User owner = createUser();
        ImportJob job = createResolvingJob(owner.getId(), ImportSourceFormat.LETTERBOXD_ZIP, "letterboxd:test");
        for (int index = 0; index < 100; index++) {
            ImportItem item = item(job, ImportRecordKind.DIARY_EVENT, "event-" + index,
                    "event-" + index, null, payload("Arrival", 2016, "2025-05-30", null, null), index);
            item.setOutcome(null);
            itemRepository.save(item);
        }

        run(job);

        assertThat(aliasService.queries()).hasSize(1);
        assertThat(aliasService.queries().getFirst().title()).isEqualTo("Arrival");
        assertThat(currentItems(job)).filteredOn(row -> row.getNormalizedKind() == ImportRecordKind.DIARY_EVENT
                        && "MATCHED".equals(row.getOutcome()))
                .hasSize(100);
    }

    @Test
    @DisplayName("[normalize] Should Apply Current Rating Only To Latest New Event - When Older Events Have Different Scores")
    void shouldApplyCurrentRatingOnlyToLatestNewEventWhenOlderEventsHaveDifferentScores() throws Exception {
        User owner = createUser();
        ImportJob job = createResolvingJob(owner.getId(), ImportSourceFormat.LETTERBOXD_ZIP, "letterboxd:test");
        ImportTarget movie = contentTarget(MediaReference.movie("603"));
        item(job, ImportRecordKind.DIARY_EVENT, "old-event", "old-event", movie,
                payload("Arrival", 2016, "2024-01-01", 5, null), 1);
        ImportItem latest = item(job, ImportRecordKind.DIARY_EVENT, "new-event", "new-event", movie,
                payload("Arrival", 2016, "2025-01-01", 7, null), 2);
        item(job, ImportRecordKind.REVIEW, "new-review", "new-event", movie,
                payload("Arrival", 2016, null, 8, "Latest event review."), 3);
        item(job, ImportRecordKind.CURRENT_RATING, "current-rating", "rating-uri", movie,
                payload("Arrival", 2016, null, 9, null), 4);
        markConsumed(owner.getId(), job.getSourceNamespace(), "old-event");

        run(job);

        List<ImportItem> rows = currentItems(job);
        ImportItem normalizedLatest = rows.stream().filter(row -> row.getId().equals(latest.getId())).findFirst().orElseThrow();
        assertThat(read(normalizedLatest.getPayloadJson()).path("score").asInt()).isEqualTo(9);
        assertThat(read(normalizedLatest.getPayloadJson()).path("review").asString()).isEqualTo("Latest event review.");
        ImportItem normalizedOlder = rows.stream().filter(row -> "old-event".equals(row.getSourceId())).findFirst().orElseThrow();
        assertThat(read(normalizedOlder.getPayloadJson()).path("score").asInt()).isEqualTo(5);
        assertThat(rows).filteredOn(row -> "current-rating".equals(row.getSourceId())).singleElement()
                .extracting(ImportItem::getOutcome).isEqualTo("ABSORBED");
    }

    @Test
    @DisplayName("[normalize] Should Keep Series Review Snapshot Separate - When Current Rating Disagrees")
    void shouldKeepSeriesReviewSnapshotSeparateWhenCurrentRatingDisagrees() throws Exception {
        User owner = createUser();
        ImportJob job = createResolvingJob(owner.getId(), ImportSourceFormat.LETTERBOXD_ZIP, "letterboxd:test");
        ImportTarget series = contentTarget(MediaReference.series("42"));
        ImportItem review = item(job, ImportRecordKind.REVIEW, "series-review", "series-review", series,
                payload("Example Series", 2020, null, 7, "Series review snapshot."), 1);
        ImportItem rating = item(job, ImportRecordKind.CURRENT_RATING, "series-rating", "series-rating", series,
                payload("Example Series", 2020, null, 9, null), 2);

        run(job);

        List<ImportItem> rows = currentItems(job);
        assertThat(rows).filteredOn(row -> row.getId().equals(review.getId())).singleElement()
                .satisfies(row -> {
                    assertThat(row.getOutcome()).isEqualTo("MATCHED");
                    assertThat(read(row.getPayloadJson()).path("score").asInt()).isEqualTo(7);
                });
        assertThat(rows).filteredOn(row -> row.getId().equals(rating.getId())).singleElement()
                .satisfies(row -> {
                    assertThat(row.getOutcome()).isEqualTo("MATCHED");
                    assertThat(read(row.getPayloadJson()).path("score").asInt()).isEqualTo(9);
                });
    }

    @Test
    @DisplayName("[normalize] Should Keep Series Opinions On Their Expansion Parent - When Current Rating Disagrees")
    void shouldKeepSeriesOpinionsOnTheirExpansionParentWhenCurrentRatingDisagrees() throws Exception {
        User owner = createUser();
        ImportJob job = createResolvingJob(owner.getId(), ImportSourceFormat.LETTERBOXD_ZIP, "letterboxd:test");
        ImportTarget series = contentTarget(MediaReference.series("42"));
        String eventKey = "https://letterboxd.com/user/film/example-series/";
        ImportItem event = item(job, ImportRecordKind.DIARY_EVENT, "series-event", eventKey, series,
                payload("Example Series", 2020, "2025-01-01", 4, null), 1);
        ImportItem review = item(job, ImportRecordKind.REVIEW, "series-review", eventKey, series,
                payload("Example Series", 2020, null, 7, "Series review snapshot."), 2);
        ImportItem marker = item(job, ImportRecordKind.WATCHED_MARKER, "series-marker", "series-marker", series,
                payload("Example Series", 2020, null, null, null), 3);
        ImportItem rating = item(job, ImportRecordKind.CURRENT_RATING, "series-rating", "series-rating", series,
                payload("Example Series", 2020, null, 9, null), 4);

        run(job);

        List<ImportItem> rows = currentItems(job);
        ImportItem parent = rows.stream().filter(row -> row.getId().equals(event.getId())).findFirst().orElseThrow();
        assertThat(parent.getNormalizedKind()).isEqualTo(ImportRecordKind.SERIES_EXPANSION);
        ObjectNode parentPayload = read(parent.getPayloadJson());
        assertThat(parentPayload.path("review").asString()).isEqualTo("Series review snapshot.");
        assertThat(parentPayload.path("reviewScoreAtPublication").asInt()).isEqualTo(7);
        assertThat(parentPayload.path("currentRatingScore").asInt()).isEqualTo(9);
        assertThat(rows).filteredOn(row -> row.getId().equals(review.getId())
                        || row.getId().equals(marker.getId()) || row.getId().equals(rating.getId()))
                .allSatisfy(row -> assertThat(row.getOutcome()).isEqualTo("ABSORBED"));
    }

    @Test
    @DisplayName("[normalize] Should Keep A Watched Marker Undated - When No Diary Event Matches It")
    void shouldKeepAWatchedMarkerUndatedWhenNoDiaryEventMatchesIt() throws Exception {
        User owner = createUser();
        ImportJob job = createResolvingJob(owner.getId(), ImportSourceFormat.LETTERBOXD_ZIP, "letterboxd:test");
        ImportItem marker = item(job, ImportRecordKind.WATCHED_MARKER, "watched-only", "watched-only",
                contentTarget(MediaReference.movie("603")), payload("Arrival", 2016, null, null, null), 1);

        run(job);

        ImportItem normalized = itemRepository.findById(marker.getId()).orElseThrow();
        assertThat(normalized.getOutcome()).isEqualTo("MATCHED");
        assertThat(read(normalized.getPayloadJson()).path("watchedOn").isMissingNode()).isTrue();
    }

    @Test
    @DisplayName("[normalize] Should Report A Date Tie - When Current Rating Has Multiple Latest Events Without Times")
    void shouldReportADateTieWhenCurrentRatingHasMultipleLatestEventsWithoutTimes() throws Exception {
        User owner = createUser();
        ImportJob job = createResolvingJob(owner.getId(), ImportSourceFormat.LETTERBOXD_ZIP, "letterboxd:test");
        ImportTarget movie = contentTarget(MediaReference.movie("603"));
        item(job, ImportRecordKind.DIARY_EVENT, "event-1", "event-1", movie,
                payload("Arrival", 2016, "2025-05-30", 6, null), 1);
        item(job, ImportRecordKind.DIARY_EVENT, "event-2", "event-2", movie,
                payload("Arrival", 2016, "2025-05-30", 8, null), 2);
        ImportItem rating = item(job, ImportRecordKind.CURRENT_RATING, "rating", "rating", movie,
                payload("Arrival", 2016, null, 9, null), 3);

        ImportJob waiting = runWorker(job);

        assertThat(waiting.getState()).isEqualTo(ImportJobState.RESOLVING);
        assertThat(waiting.isWorkPending()).isFalse();
        ImportItem issue = itemRepository.findById(rating.getId()).orElseThrow();
        assertThat(issue.getOutcome()).isEqualTo("NORMALIZATION_ISSUE");
        assertThat(issue.getIssue()).contains("date is tied");
        assertThat(read(itemRepository.findByJobIdAndPlanGenerationOrderByOrdinalAscIdAsc(
                job.getId(), 1, org.springframework.data.domain.PageRequest.of(0, 2)).getContent().getFirst()
                .getPayloadJson()).path("score").asInt()).isEqualTo(6);
    }

    @Test
    @DisplayName("[normalize] Should Not Backfill An Older New Event - When The Latest Event Is Already Consumed")
    void shouldNotBackfillAnOlderNewEventWhenTheLatestEventIsAlreadyConsumed() throws Exception {
        User owner = createUser();
        ImportJob job = createResolvingJob(owner.getId(), ImportSourceFormat.LETTERBOXD_ZIP, "letterboxd:test");
        ImportTarget movie = contentTarget(MediaReference.movie("603"));
        ImportItem older = item(job, ImportRecordKind.DIARY_EVENT, "older-new", "older-new", movie,
                payload("Arrival", 2016, "2024-01-01", 5, null), 1);
        ImportItem latest = item(job, ImportRecordKind.DIARY_EVENT, "latest-consumed", "latest-consumed", movie,
                payload("Arrival", 2016, "2025-01-01", 7, null), 2);
        ImportItem rating = item(job, ImportRecordKind.CURRENT_RATING, "rating", "rating", movie,
                payload("Arrival", 2016, null, 9, null), 3);
        markConsumed(owner.getId(), job.getSourceNamespace(), latest.getSourceId());

        run(job);

        assertThat(read(itemRepository.findById(older.getId()).orElseThrow().getPayloadJson())
                .path("score").asInt()).isEqualTo(5);
        assertThat(read(itemRepository.findById(latest.getId()).orElseThrow().getPayloadJson())
                .path("score").asInt()).isEqualTo(7);
        assertThat(itemRepository.findById(rating.getId()).orElseThrow().getOutcome()).isEqualTo("ABSORBED");
    }

    @Test
    @DisplayName("[normalize] Should Mark Identity Conflicts - When Diary And Review Resolve To Different Content")
    void shouldMarkIdentityConflictsWhenDiaryAndReviewResolveToDifferentContent() throws Exception {
        User owner = createUser();
        ImportJob job = createResolvingJob(owner.getId(), ImportSourceFormat.LETTERBOXD_ZIP, "letterboxd:test");
        String eventKey = "https://letterboxd.com/user/film/arrival/";
        ImportItem diary = item(job, ImportRecordKind.DIARY_EVENT, "diary", eventKey,
                contentTarget(MediaReference.movie("603")), payload("Arrival", 2016, "2025-01-01", 5, null), 1);
        ImportItem review = item(job, ImportRecordKind.REVIEW, "review", eventKey,
                contentTarget(MediaReference.movie("604")), payload("Arrival", 2016, null, 9, "Review text."), 2);

        ImportJob waiting = runWorker(job);

        assertThat(waiting.getState()).isEqualTo(ImportJobState.RESOLVING);
        assertThat(itemRepository.findById(diary.getId()).orElseThrow().getOutcome()).isEqualTo("NORMALIZATION_ISSUE");
        assertThat(itemRepository.findById(review.getId()).orElseThrow().getOutcome()).isEqualTo("NORMALIZATION_ISSUE");
    }

    @Test
    @DisplayName("[normalize] Should Not Merge Parser Versions - When Fragments Share A Source Event Key")
    void shouldNotMergeParserVersionsWhenFragmentsShareASourceEventKey() throws Exception {
        User owner = createUser();
        ImportJob job = createResolvingJob(owner.getId(), ImportSourceFormat.LETTERBOXD_ZIP, "letterboxd:test");
        String eventKey = "https://letterboxd.com/user/film/arrival/";
        ImportItem diary = item(job, ImportRecordKind.DIARY_EVENT, "diary", eventKey,
                contentTarget(MediaReference.movie("603")), payload("Arrival", 2016, "2025-01-01", 5, null), 1);
        ImportItem review = item(job, ImportRecordKind.REVIEW, "review", eventKey,
                contentTarget(MediaReference.movie("603")), payload("Arrival", 2016, null, 9, "Review text."), 2);
        jdbc.update("UPDATE import_items SET parser_version = ? WHERE id = ?", "letterboxd-history-v2", review.getId());

        ImportJob waiting = runWorker(job);

        assertThat(waiting.getState()).isEqualTo(ImportJobState.RESOLVING);
        assertThat(itemRepository.findById(diary.getId()).orElseThrow().getIssue())
                .contains("different parser versions");
        assertThat(itemRepository.findById(review.getId()).orElseThrow().getOutcome()).isEqualTo("NORMALIZATION_ISSUE");
    }

    @Test
    @DisplayName("[normalize] Should Not Enrich A Consumed Event - When A Review Fragment Remains")
    void shouldNotEnrichAConsumedEventWhenAReviewFragmentRemains() throws Exception {
        User owner = createUser();
        ImportJob job = createResolvingJob(owner.getId(), ImportSourceFormat.LETTERBOXD_ZIP, "letterboxd:test");
        String eventKey = "https://letterboxd.com/user/film/arrival/";
        ImportItem diary = item(job, ImportRecordKind.DIARY_EVENT, "diary", eventKey,
                contentTarget(MediaReference.movie("603")), payload("Arrival", 2016, "2025-01-01", 5, null), 1);
        ImportItem review = item(job, ImportRecordKind.REVIEW, "review", eventKey,
                contentTarget(MediaReference.movie("603")), payload("Arrival", 2016, null, 9, "Review text."), 2);
        markConsumed(owner.getId(), job.getSourceNamespace(), diary.getSourceId());

        run(job);

        ObjectNode eventPayload = read(itemRepository.findById(diary.getId()).orElseThrow().getPayloadJson());
        assertThat(eventPayload.path("score").asInt()).isEqualTo(5);
        assertThat(eventPayload.path("review").isMissingNode()).isTrue();
        assertThat(itemRepository.findById(review.getId()).orElseThrow().getOutcome()).isEqualTo("ABSORBED");
    }

    @Test
    @DisplayName("[resolve] Should Reuse Within A Generation But Search Again In The Next Generation")
    void shouldReuseWithinAGenerationButSearchAgainInTheNextGeneration() throws Exception {
        User owner = createUser();
        ImportJob job = createResolvingJob(owner.getId(), ImportSourceFormat.LETTERBOXD_ZIP, "letterboxd:test");
        item(job, ImportRecordKind.DIARY_EVENT, "event-1", "event-1", null,
                payload("Arrival", 2016, "2025-01-01", null, null), 1);
        run(job);
        assertThat(aliasService.queries()).hasSize(1);

        ImportJob previewChanged = restartResolve(job.getId(), 2, 1);
        item(previewChanged, 1, ImportRecordKind.DIARY_EVENT, "event-2", "event-2", null,
                payload("Arrival", 2016, "2025-01-02", null, null), 2);
        run(previewChanged);
        assertThat(aliasService.queries()).hasSize(1);

        ImportJob generationChanged = restartResolve(job.getId(), 3, 2);
        item(generationChanged, 2, ImportRecordKind.DIARY_EVENT, "event-3", "event-3", null,
                payload("Arrival", 2016, "2025-01-03", null, null), 3);
        run(generationChanged);
        assertThat(aliasService.queries()).hasSize(2);
    }

    @Test
    @DisplayName("[resolve] Should Reject An Owner Mismatch - Before Reading Or Searching Staged Items")
    void shouldRejectAnOwnerMismatchBeforeReadingOrSearchingStagedItems() throws Exception {
        User owner = createUser();
        User other = createUser();
        ImportJob job = createResolvingJob(owner.getId(), ImportSourceFormat.LETTERBOXD_ZIP, "letterboxd:test");
        ImportItem pending = item(job, ImportRecordKind.DIARY_EVENT, "event", "event", null,
                payload("Arrival", 2016, null, null, null), 1);
        ImportExecutionContext wrongOwner = new ImportExecutionContext(other.getId(), job.getId(), 1,
                1, job.getSourceNamespace(), UUID.randomUUID());

        assertThatThrownBy(() -> contentResolver.resolve(wrongOwner, List.of(pending)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not belong");
        assertThat(aliasService.queries()).isEmpty();
    }

    @Test
    @DisplayName("[resolve] Should Keep Not Found Waiting And Unavailable Retryable")
    void shouldKeepNotFoundWaitingAndUnavailableRetryable() throws Exception {
        User owner = createUser();
        ImportJob notFoundJob = createResolvingJob(owner.getId(), ImportSourceFormat.LETTERBOXD_ZIP, "letterboxd:test");
        item(notFoundJob, ImportRecordKind.DIARY_EVENT, "not-found", "not-found", null,
                payload("Missing Film", 2001, null, null, null), 1);
        aliasService.setNextStatus(AliasResolution.Status.NOT_FOUND);

        ImportJob waiting = runWorker(notFoundJob);

        assertThat(waiting.getState()).isEqualTo(ImportJobState.RESOLVING);
        assertThat(waiting.isWorkPending()).isFalse();
        assertThat(itemRepository.findByJobIdAndPlanGenerationOrderByOrdinalAscIdAsc(
                notFoundJob.getId(), 1, org.springframework.data.domain.PageRequest.of(0, 1))
                .getContent().getFirst().getOutcome()).isEqualTo("NOT_FOUND");

        aliasService.reset();
        aliasService.setNextStatus(AliasResolution.Status.UNAVAILABLE);
        ImportJob unavailableJob = createResolvingJob(owner.getId(), ImportSourceFormat.LETTERBOXD_ZIP,
                "letterboxd:unavailable");
        item(unavailableJob, ImportRecordKind.DIARY_EVENT, "unavailable", "unavailable", null,
                payload("Unavailable Film", 2001, null, null, null), 1);

        ImportJob retry = runWorker(unavailableJob);

        assertThat(retry.getState()).isEqualTo(ImportJobState.RESOLVING);
        assertThat(retry.isWorkPending()).isTrue();
        assertThat(itemRepository.findByJobIdAndPlanGenerationOrderByOrdinalAscIdAsc(
                unavailableJob.getId(), 1, org.springframework.data.domain.PageRequest.of(0, 1))
                .getContent().getFirst().getOutcome()).isEqualTo("UNAVAILABLE");
    }

    @Test
    @DisplayName("[resolve] Should Resolve Missing Episode Parent Id As A Series - While Preserving Episode Coordinates")
    void shouldResolveMissingEpisodeParentIdAsASeriesWhilePreservingEpisodeCoordinates() throws Exception {
        User owner = createUser();
        ImportJob job = createResolvingJob(owner.getId(), ImportSourceFormat.TRAKT_ZIP, "trakt:test");
        ObjectNode payload = payload("Example Series", 2020, "2025-01-01", null, null);
        payload.putObject("sourceQuery").put("kind", "CONTENT").put("title", "Example Series")
                .put("year", 2020).put("contentType", "SERIES").put("seasonNumber", 2).put("episodeNumber", 4);
        ImportItem item = item(job, ImportRecordKind.DIARY_EVENT, "episode-1", "episode-1", null, payload, 1);
        item.setOutcome(null);
        itemRepository.save(item);

        run(job);

        ImportItem resolved = itemRepository.findById(item.getId()).orElseThrow();
        assertThat(resolved.getResolvedTargetJson()).contains("EPISODE", "123", "2", "4");
        assertThat(resolved.getResolvedContentKey()).isEqualTo("CONTENT|EPISODE|123|2|4");
        assertThat(aliasService.queries()).singleElement().satisfies(query -> {
            assertThat(query.title()).isEqualTo("Example Series");
            assertThat(query.contentType()).isEqualTo(com.watchwise.watchwise_api.content.entity.ContentType.SERIES);
        });
    }

    @Test
    @DisplayName("[parse] Should Leave Letterboxd Content Type Open - When The Title May Be A Series")
    void shouldLeaveLetterboxdContentTypeOpenWhenTheTitleMayBeASeries() {
        var context = new ImportReadContext(UUID.randomUUID(), UUID.randomUUID(), "fixture",
                "letterboxd:test", ZoneId.of("UTC"));
        List<com.watchwise.watchwise_api.platformimport.model.NormalizedImportRecord> records = new ArrayList<>();
        var parser = new LetterboxdHistoryCsvParser(new CsvRecordReader(new ImportLimits()));
        String csv = "Date,Name,Year,Letterboxd URI,Rating,Rewatch,Tags,Watched Date\n"
                + "2025-01-01,Example Series,2020,https://letterboxd.com/film/example-series/,,, ,2024-12-31\n";

        parser.parse(new java.io.StringReader(csv), context, "diary.csv", ImportRecordKind.DIARY_EVENT,
                records::add);

        assertThat(records).singleElement().satisfies(record -> assertThat(
                record.payload().path("sourceQuery").path("contentType").isMissingNode()).isTrue());
    }

    private User createUser() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        LocalDateTime now = LocalDateTime.now(clock);
        return userRepository.saveAndFlush(User.builder()
                .username("hn" + suffix)
                .name("History Normalization " + suffix)
                .email("hn" + suffix + "@example.com")
                .password(passwordEncoder.encode("Password123"))
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    private ImportJob createResolvingJob(UUID ownerId, ImportSourceFormat format, String namespace) {
        LocalDateTime now = LocalDateTime.now(clock);
        ImportPlatform platform = format == ImportSourceFormat.TRAKT_ZIP
                ? ImportPlatform.TRAKT : ImportPlatform.LETTERBOXD;
        return jobRepository.saveAndFlush(ImportJob.builder()
                .userId(ownerId)
                .inputKind(ImportInputKind.PLATFORM_ARCHIVE)
                .sourceFormat(format)
                .platform(platform)
                .sourceNamespace(namespace)
                .state(ImportJobState.RESOLVING)
                .previewVersion(1)
                .selectionJson("{\"schemaVersion\":1,\"planGeneration\":1,\"selection\":{}}")
                .timezone("UTC")
                .storageKey(UUID.randomUUID().toString())
                .uploadSizeBytes(1)
                .workPhase("RESOLVE")
                .workPending(true)
                .nextRunAt(now.minusSeconds(1))
                .createdAt(now)
                .updatedAt(now)
                .expiresAt(now.plusDays(1))
                .build());
    }

    private ImportItem item(ImportJob job, ImportRecordKind kind, String id, String eventKey,
                            ImportTarget target, ObjectNode payload, long ordinal) throws Exception {
        return item(job, 1, kind, id, eventKey, target, payload, ordinal);
    }

    private ImportItem item(ImportJob job, long generation, ImportRecordKind kind, String id, String eventKey,
                            ImportTarget target, ObjectNode payload, long ordinal) throws Exception {
        return itemRepository.saveAndFlush(stagedItem(job, generation, kind, id, eventKey, target, payload, ordinal));
    }

    private ImportItem stagedItem(ImportJob job, long generation, ImportRecordKind kind, String id, String eventKey,
                                  ImportTarget target, ObjectNode payload, long ordinal) throws Exception {
        String targetJson = target == null ? null : objectMapper.writeValueAsString(target);
        String contentKey = target == null ? null : contentKey(target);
        return ImportItem.builder()
                .id(UUID.randomUUID())
                .jobId(job.getId())
                .planGeneration(generation)
                .sourcePath(kind.name().toLowerCase() + ".csv")
                .recordLocator(Long.toString(ordinal))
                .normalizedKind(kind)
                .sourceNamespace(job.getSourceNamespace())
                .objectKind(kind == ImportRecordKind.REVIEW || kind == ImportRecordKind.DIARY_EVENT
                        ? "diary_event" : kind.name().toLowerCase())
                .sourceId(id)
                .sourceEventKey(eventKey)
                .normalizedContentKey("CONTENT|arrival|2016|MOVIE")
                .resolvedContentKey(contentKey)
                .parserVersion(job.getSourceFormat() == ImportSourceFormat.TRAKT_ZIP
                        ? "trakt-history-v1" : "letterboxd-history-v1")
                .resolvedTargetJson(targetJson)
                .payloadJson(objectMapper.writeValueAsString(payload))
                .outcome(target == null ? null : "MATCHED")
                .ordinal(ordinal)
                .build();
    }

    private ImportItem stagedItem(ImportJob job, long generation, NormalizedImportRecord record,
                                  String sourceEventKey, long ordinal) throws Exception {
        String targetJson = record.target() == null ? null : objectMapper.writeValueAsString(record.target());
        String contentKey = record.target() == null ? null : contentKey(record.target());
        return ImportItem.builder()
                .id(UUID.randomUUID())
                .jobId(job.getId())
                .planGeneration(generation)
                .sourcePath(record.sourceKey().objectKind() + ".json")
                .recordLocator(Long.toString(ordinal))
                .normalizedKind(record.kind())
                .sourceNamespace(record.sourceKey().namespace())
                .objectKind(record.sourceKey().objectKind())
                .sourceId(record.sourceKey().sourceId())
                .sourceEventKey(sourceEventKey)
                .normalizedContentKey("CONTENT|arrival|2016|MOVIE")
                .resolvedContentKey(contentKey)
                .parserVersion("trakt-history-v1")
                .resolvedTargetJson(targetJson)
                .payloadJson(objectMapper.writeValueAsString(record.payload()))
                .outcome(targetJson == null ? null : "MATCHED")
                .ordinal(ordinal)
                .build();
    }

    private NormalizedImportRecord parseTraktRecord(TraktItemParserImpl parser, ImportReadContext context,
                                                     ImportRecordKind kind, String json) {
        List<NormalizedImportRecord> records = new ArrayList<>();
        parser.parse(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), context,
                "history/direct.json", kind, records::add);
        return records.getFirst();
    }

    private ObjectNode payload(String title, Integer year, String watchedOn, Integer score, String review) {
        ObjectNode payload = objectMapper.createObjectNode().put("title", title);
        if (year != null) payload.put("releaseYear", year);
        if (watchedOn != null) payload.put("watchedOn", watchedOn);
        if (score != null) payload.put("score", score);
        if (review != null) payload.put("review", review);
        payload.putObject("sourceQuery").put("kind", "CONTENT").put("title", title)
                .put("year", year).put("contentType", "MOVIE");
        return payload;
    }

    private void markConsumed(UUID ownerId, String namespace, String sourceId) {
        jdbc.update("""
                INSERT INTO external_import_links(id, user_id, source_namespace, object_kind, source_id, state)
                VALUES (?, ?, ?, 'diary_event', ?, 'APPLIED')
                """, UUID.randomUUID(), ownerId, namespace, sourceId);
    }

    private String contentKey(ImportTarget target) {
        MediaReference media = ((ImportTarget.ContentTarget) target).content();
        return switch (media.type()) {
            case MOVIE, SERIES -> "CONTENT|" + media.type().name() + "|" + media.tmdbId();
            case SEASON -> "CONTENT|SEASON|" + media.seriesTmdbId() + "|" + media.seasonNumber();
            case EPISODE -> "CONTENT|EPISODE|" + media.seriesTmdbId() + "|"
                    + media.seasonNumber() + "|" + media.episodeNumber();
        };
    }

    private ImportTarget contentTarget(MediaReference media) {
        return new ImportTarget.ContentTarget(media);
    }

    private List<ImportItem> currentItems(ImportJob job) {
        return itemRepository.findByJobIdAndPlanGenerationOrderByOrdinalAscIdAsc(
                job.getId(), 1, org.springframework.data.domain.PageRequest.of(0, 500)).getContent();
    }

    private void run(ImportJob job) {
        assertThat(runWorker(job).getState()).isEqualTo(ImportJobState.READY);
    }

    private ImportJob runWorker(ImportJob job) {
        assertThat(worker.runAvailable(1)).isEqualTo(1);
        return jobRepository.findById(job.getId()).orElseThrow();
    }

    private ImportJob restartResolve(UUID jobId, long previewVersion, long generation) {
        ImportJob job = jobRepository.findById(jobId).orElseThrow();
        job.setPreviewVersion(previewVersion);
        job.setSelectionJson("{\"schemaVersion\":1,\"planGeneration\":" + generation + ",\"selection\":{}}");
        job.setState(ImportJobState.RESOLVING);
        job.setWorkPhase("RESOLVE");
        job.setWorkPending(true);
        job.setNextRunAt(LocalDateTime.now(clock).minusSeconds(1));
        job.setLeaseToken(null);
        job.setLeaseUntil(null);
        return jobRepository.saveAndFlush(job);
    }

    private ObjectNode read(String json) {
        try {
            return (ObjectNode) objectMapper.readTree(json);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfigurationBeans {
        @Bean
        @org.springframework.context.annotation.Primary
        TestAliasService historyTestAliasService() {
            return new TestAliasService();
        }
    }

    static final class TestAliasService implements ExternalContentAliasService {
        private final List<AliasQuery> queries = new CopyOnWriteArrayList<>();
        private volatile AliasResolution.Status nextStatus = AliasResolution.Status.MATCHED;

        @Override
        public AliasResolution resolve(UUID owner, AliasQuery query) {
            queries.add(query);
            return resolutionFor(query);
        }

        @Override
        public List<AliasResolution> resolveBatch(UUID owner, List<AliasQuery> batch) {
            queries.addAll(batch);
            return batch.stream().map(this::resolutionFor).toList();
        }

        @Override
        public void remember(UUID owner, AliasQuery query, ImportTarget target,
                             com.watchwise.watchwise_api.platformimport.model.AliasTrust trust) {
        }

        List<AliasQuery> queries() {
            return List.copyOf(queries);
        }

        void reset() {
            queries.clear();
            nextStatus = AliasResolution.Status.MATCHED;
        }

        void setNextStatus(AliasResolution.Status status) {
            nextStatus = status;
        }

        private AliasResolution resolutionFor(AliasQuery query) {
            return switch (nextStatus) {
                case MATCHED -> AliasResolution.matched(targetFor(query));
                case AMBIGUOUS -> AliasResolution.ambiguous(List.of(targetFor(query),
                        new ImportTarget.ContentTarget(MediaReference.movie("999"))));
                case NOT_FOUND -> AliasResolution.notFound();
                case UNAVAILABLE -> AliasResolution.unavailable(List.of());
            };
        }

        private static ImportTarget targetFor(AliasQuery query) {
            return query.contentType() == com.watchwise.watchwise_api.content.entity.ContentType.SERIES
                    || (query.contentType() == null && "Example Series".equals(query.title()))
                    ? new ImportTarget.ContentTarget(MediaReference.series("123"))
                    : new ImportTarget.ContentTarget(MediaReference.movie("603"));
        }
    }
}
