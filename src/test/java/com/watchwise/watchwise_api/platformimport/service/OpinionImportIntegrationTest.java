package com.watchwise.watchwise_api.platformimport.service;

import com.watchwise.watchwise_api.content.dto.MediaReference;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.contentrating.entity.ContentRating;
import com.watchwise.watchwise_api.contentrating.repository.ContentRatingRepository;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.contentreview.entity.ContentReview;
import com.watchwise.watchwise_api.contentreview.entity.ReviewContextKind;
import com.watchwise.watchwise_api.contentreview.entity.ReviewPublicationKind;
import com.watchwise.watchwise_api.contentreview.repository.ContentReviewRepository;
import com.watchwise.watchwise_api.diarycompletion.model.CompletionScope;
import com.watchwise.watchwise_api.diarycompletion.model.EpisodeCoordinate;
import com.watchwise.watchwise_api.diarycompletion.model.VersionedCompletionScope;
import com.watchwise.watchwise_api.diarycompletion.service.CompletionScopeLoader;
import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import com.watchwise.watchwise_api.diaryentry.entity.ImportedWatchAssertion;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.diaryentry.repository.ImportedWatchAssertionRepository;
import com.watchwise.watchwise_api.platformimport.entity.ImportItem;
import com.watchwise.watchwise_api.platformimport.entity.ImportJob;
import com.watchwise.watchwise_api.platformimport.model.ImportInputKind;
import com.watchwise.watchwise_api.platformimport.model.ImportJobState;
import com.watchwise.watchwise_api.platformimport.model.ImportPlatform;
import com.watchwise.watchwise_api.platformimport.model.ImportRecordKind;
import com.watchwise.watchwise_api.platformimport.model.ImportSourceFormat;
import com.watchwise.watchwise_api.platformimport.model.ImportTarget;
import com.watchwise.watchwise_api.platformimport.model.ImportResolutionOperation;
import com.watchwise.watchwise_api.platformimport.model.NormalizedImportRecord;
import com.watchwise.watchwise_api.platformimport.model.ConfirmedWatchScopeSnapshot;
import com.watchwise.watchwise_api.platformimport.dto.ImportWatchScopePreviewDTO;
import com.watchwise.watchwise_api.platformimport.repository.ImportItemRepository;
import com.watchwise.watchwise_api.platformimport.repository.ImportJobRepository;
import com.watchwise.watchwise_api.platformimport.service.impl.ImportUnitTransaction;
import com.watchwise.watchwise_api.platformimport.service.impl.DeferredOpinionReplayStager;
import com.watchwise.watchwise_api.platformimport.service.impl.ImportWorker;
import com.watchwise.watchwise_api.platformimport.service.ImportPreviewService;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@SpringBootTest
@Testcontainers
class OpinionImportIntegrationTest {

    private static final String SERIES_ID = "88001";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.docker.compose.enabled", () -> "false");
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private ContentRepository contents;
    @Autowired private DiaryEntryRepository diaryEntries;
    @Autowired private ContentRatingRepository ratings;
    @Autowired private ContentReviewRepository reviews;
    @Autowired private ImportedWatchAssertionRepository assertions;
    @Autowired private ImportJobRepository jobs;
    @Autowired private ImportItemRepository items;
    @Autowired private ImportUnitTransaction unitTransaction;
    @Autowired private DeferredOpinionReplayStager deferredOpinionReplayStager;
    @Autowired private ImportPreviewService previewService;
    @Autowired private ImportWorker worker;
    @Autowired private ObjectMapper mapper;

    @MockitoBean
    private CompletionScopeLoader completionScopeLoader;

    @MockitoBean
    private TmdbClient tmdbClient;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE import_items, external_import_links, import_deferred_records, imported_watch_assertions, "
                + "content_reviews, content_ratings, diary_entries, contents, users CASCADE");
        when(completionScopeLoader.load(any(MediaReference.class), anyString(), anyString(), any(Instant.class)))
                .thenReturn(new VersionedCompletionScope(new CompletionScope(MediaReference.series(SERIES_ID),
                        Set.of(new EpisodeCoordinate(1, 1), new EpisodeCoordinate(1, 2)), true),
                        "series-scope-v1", Instant.parse("2030-01-01T00:00:00Z")));
    }

    @Test
    void newLatestReviewRestoresItsScoreOverAnExistingManualRatingAndReimportIsSkipped() throws Exception {
        User owner = createUser();
        Content series = createContent(ContentType.SERIES, SERIES_ID, null, null, null);
        addCompletePass(owner, series);
        ratings.saveAndFlush(ContentRating.builder().userId(owner.getId()).contentId(series.getId())
                .contentType(ContentType.SERIES).score(8).createdAt(Instant.parse("2020-01-01T00:00:00Z"))
                .updatedAt(Instant.parse("2020-01-01T00:00:00Z")).build());

        ImportItem first = createReviewItem(owner, "shared-review", "New opinion", 7,
                LocalDate.of(2026, 10, 1), SERIES_ID);
        apply(first);
        assertThat(items.findById(first.getId()).orElseThrow().getOutcome()).isEqualTo("APPLIED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM import_deferred_records", Long.class)).isZero();
        finishJob(first.getJobId());

        assertThat(ratings.findByUserIdAndContentId(owner.getId(), series.getId()).orElseThrow().getScore())
                .isEqualTo(7);
        assertThat(reviews.findByUserIdAndContentIdOrderByCreatedAtDesc(owner.getId(), series.getId()))
                .singleElement().satisfies(review -> {
                    assertThat(review.getComment()).isEqualTo("New opinion");
                    assertThat(review.getScoreAtPublication()).isEqualTo(7);
                    assertThat(review.getSourcePublishedOn()).isEqualTo(LocalDate.of(2026, 10, 1));
                });

        ImportItem changedExport = createReviewItem(owner, "shared-review", "Edited upstream opinion", 10,
                LocalDate.of(2026, 10, 2), SERIES_ID);
        apply(changedExport);

        assertThat(ratings.findByUserIdAndContentId(owner.getId(), series.getId()).orElseThrow().getScore())
                .isEqualTo(7);
        assertThat(reviews.findByUserIdAndContentIdOrderByCreatedAtDesc(owner.getId(), series.getId()))
                .singleElement().extracting(ContentReview::getComment).isEqualTo("New opinion");
        assertThat(items.findById(changedExport.getId()).orElseThrow().getOutcome()).isEqualTo("SKIPPED");
    }

    @Test
    void olderImportedReviewDoesNotReplaceTheCurrentRatingFromANewerReview() throws Exception {
        User owner = createUser();
        Content series = createContent(ContentType.SERIES, SERIES_ID, null, null, null);
        addCompletePass(owner, series);
        ratings.saveAndFlush(ContentRating.builder().userId(owner.getId()).contentId(series.getId())
                .contentType(ContentType.SERIES).score(9).createdAt(Instant.parse("2020-01-01T00:00:00Z"))
                .updatedAt(Instant.parse("2020-01-01T00:00:00Z")).build());
        reviews.saveAndFlush(ContentReview.builder().userId(owner.getId()).contentId(series.getId())
                .contentType(ContentType.SERIES).publicationKind(ReviewPublicationKind.WRITTEN_REVIEW)
                .contextKind(ReviewContextKind.UNKNOWN).comment("Later review").scoreAtPublication(9)
                .watchNumber(1).sourcePublishedOn(LocalDate.of(2026, 10, 10))
                .createdAt(Instant.parse("2026-10-10T12:00:00Z")).updatedAt(Instant.parse("2026-10-10T12:00:00Z"))
                .build());

        ImportItem older = createReviewItem(owner, "older-review", "Older review", 6,
                LocalDate.of(2026, 10, 1), SERIES_ID);
        apply(older);

        assertThat(ratings.findByUserIdAndContentId(owner.getId(), series.getId()).orElseThrow().getScore())
                .isEqualTo(9);
        assertThat(reviews.findByUserIdAndContentIdOrderByCreatedAtDesc(owner.getId(), series.getId()))
                .extracting(ContentReview::getComment).contains("Older review", "Later review");
    }

    @Test
    void ratingWithoutReviewCreatesOnlyAnInitialRatingAfterACompletePass() throws Exception {
        User owner = createUser();
        Content series = createContent(ContentType.SERIES, SERIES_ID, null, null, null);
        addCompletePass(owner, series);
        ImportJob job = createJob(owner);
        ImportItem rating = createRatingItem(owner, job.getId(), "initial-rating", 8, SERIES_ID);

        apply(rating);

        assertThat(ratings.findByUserIdAndContentId(owner.getId(), series.getId()).orElseThrow().getScore())
                .isEqualTo(8);
        assertThat(reviews.findByUserIdAndContentIdOrderByCreatedAtDesc(owner.getId(), series.getId())).isEmpty();
    }

    @Test
    void missingPreparedCalendarSnapshotDefersTheOpinionInsteadOfFailingTheImportJob() throws Exception {
        User owner = createUser();
        ImportJob job = createJob(owner);
        ImportItem rating = createRatingItem(owner, job.getId(), "calendar-unavailable-rating", 8, SERIES_ID);
        rating.setPayloadJson(mapper.createObjectNode().put("score", 8).toString());
        items.saveAndFlush(rating);

        apply(rating);

        assertThat(jobs.findById(job.getId()).orElseThrow().getState()).isEqualTo(ImportJobState.IMPORTING);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM import_deferred_records WHERE user_id=?", Long.class,
                owner.getId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content_ratings WHERE user_id=?", Long.class,
                owner.getId())).isZero();
    }

    @Test
    void workerReappliesOriginalDeferredReviewAfterDetailedHistoryAndReimportIsIdempotent() throws Exception {
        User owner = createUser();
        Content series = createContent(ContentType.SERIES, SERIES_ID, null, null, null);
        ImportItem original = createReviewItem(owner, "deferred-review", "Original review", 7,
                LocalDate.of(2026, 10, 1), SERIES_ID);
        Instant sourcePublishedAt = Instant.parse("2026-10-01T12:30:00Z");
        original.setPayloadJson(mapper.createObjectNode().put("review", "Original review").put("score", 7)
                .put("sourceRecordedOn", "2026-10-01").put("sourcePublishedAt", sourcePublishedAt.toString()).toString());
        seedScopeSnapshot(original);
        items.saveAndFlush(original);

        apply(original);

        UUID deferredId = jdbc.queryForObject("SELECT id FROM import_deferred_records WHERE user_id=?",
                UUID.class, owner.getId());
        String originalTargetType = jdbc.queryForObject("SELECT target_type FROM external_import_links "
                        + "WHERE user_id=? AND source_namespace=? AND object_kind=? AND source_id=?",
                String.class, owner.getId(), "trakt:account-test", "review", "deferred-review");
        assertThat(originalTargetType).isEqualTo("IMPORT_DEFERRED_RECORD");
        finishJob(original.getJobId());

        createContent(ContentType.EPISODE, null, SERIES_ID, 1, 1);
        createContent(ContentType.EPISODE, null, SERIES_ID, 1, 2);
        ImportJob replayJob = createWorkerReadyJob(owner);
        addDiaryEvent(replayJob, "episode-1-event", MediaReference.episode(SERIES_ID, 1, 1),
                LocalDate.of(2020, 1, 1));
        addDiaryEvent(replayJob, "episode-2-event", MediaReference.episode(SERIES_ID, 1, 2),
                LocalDate.of(2020, 1, 2));
        ImportItem changedSource = createReviewItem(owner, replayJob.getId(), "deferred-review",
                "Changed upstream review", 9, LocalDate.of(2026, 10, 2), SERIES_ID);
        ImportItem replay = createDeferredReplayItem(owner, replayJob);

        assertThat(worker.runAvailable(1)).isEqualTo(1);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM import_deferred_records WHERE id=?",
                Long.class, deferredId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT provenance::jsonb ->> 'resolutionState' "
                        + "FROM import_deferred_records WHERE id=?", String.class, deferredId))
                .isEqualTo("RESOLVED_RESTORED");
        assertThat(items.findById(changedSource.getId()).orElseThrow().getOutcome()).isEqualTo("SKIPPED");
        assertThat(items.findById(original.getId()).orElseThrow().getPayloadJson())
                .contains("Original review").doesNotContain("Changed upstream review");
        assertThat(jdbc.queryForObject("SELECT target_type FROM external_import_links "
                        + "WHERE user_id=? AND source_namespace=? AND object_kind=? AND source_id=?",
                String.class, owner.getId(), "trakt:account-test", "review", "deferred-review"))
                .isEqualTo("CONTENT_REVIEW");
        assertThat(reviews.findByUserIdAndContentIdOrderByCreatedAtDesc(owner.getId(), series.getId()))
                .singleElement().satisfies(review -> {
                    assertThat(review.getComment()).isEqualTo("Original review");
                    assertThat(review.getScoreAtPublication()).isEqualTo(7);
                    assertThat(review.getSourcePublishedOn()).isEqualTo(LocalDate.of(2026, 10, 1));
                    assertThat(review.getSourcePublishedAt()).isEqualTo(sourcePublishedAt);
                });
        assertThat(items.findById(replay.getId()).orElseThrow().getOutcome()).isEqualTo("APPLIED");
        UUID restoredReviewId = jdbc.queryForObject("SELECT target_id FROM external_import_links "
                        + "WHERE user_id=? AND source_namespace=? AND object_kind=? AND source_id=?",
                UUID.class, owner.getId(), "trakt:account-test", "review", "deferred-review");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE user_id=?", Long.class,
                owner.getId())).isZero();

        List<NormalizedImportRecord> pendingReplay = new ArrayList<>();
        deferredOpinionReplayStager.emit(owner.getId(), replayJob.getSourceNamespace(),
                Set.of("CONTENT|SERIES|" + SERIES_ID), Set.of(), pendingReplay::add);
        assertThat(pendingReplay).isEmpty();
        ImportJob repeatedJob = createWorkerReadyJob(owner);
        ImportItem repeatedSource = createReviewItem(owner, repeatedJob.getId(), "deferred-review",
                "Another upstream edit", 10, LocalDate.of(2026, 10, 3), SERIES_ID);
        assertThat(worker.runAvailable(1)).isEqualTo(1);
        assertThat(items.findById(repeatedSource.getId()).orElseThrow().getOutcome()).isEqualTo("SKIPPED");
        assertThat(reviews.findByUserIdAndContentIdOrderByCreatedAtDesc(owner.getId(), series.getId()))
                .singleElement().extracting(ContentReview::getId).isEqualTo(restoredReviewId);
    }

    @Test
    void scopeConfirmedOnDeferredOpinionCreatesUndatedEpisodesBeforeRestoringReview() throws Exception {
        User owner = createUser();
        Content series = createContent(ContentType.SERIES, SERIES_ID, null, null, null);
        ImportItem original = createReviewItem(owner, "confirmed-deferred-review", "Review after all episodes", 8,
                LocalDate.of(2026, 10, 2), SERIES_ID, "watched_summary");
        apply(original);
        UUID deferredId = jdbc.queryForObject("SELECT id FROM import_deferred_records WHERE user_id=?",
                UUID.class, owner.getId());
        finishJob(original.getJobId());

        createContent(ContentType.EPISODE, null, SERIES_ID, 1, 1);
        createContent(ContentType.EPISODE, null, SERIES_ID, 1, 2);
        ImportJob replayJob = createJob(owner);
        replayJob.setState(ImportJobState.RESOLVING);
        replayJob.setWorkPhase("RESOLVE");
        replayJob.setWorkPending(true);
        replayJob.setSelectionJson("{\"schemaVersion\":1,\"planGeneration\":1,\"selection\":{"
                + "\"history\":true,\"watchlist\":false,\"listIds\":[],\"metadata\":null}}");
        LocalDateTime resolveAt = LocalDateTime.now(Clock.systemUTC()).minusMinutes(1);
        replayJob.setNextRunAt(resolveAt);
        replayJob.setLeaseUntil(resolveAt);
        replayJob = jobs.saveAndFlush(replayJob);
        ImportItem summary = createSummaryItem(owner, replayJob.getId(), "confirmed-deferred-review", SERIES_ID,
                "{\"declaredPlays\":1}");
        assertThat(worker.runAvailable(1)).isEqualTo(1);
        replayJob = jobs.findById(replayJob.getId()).orElseThrow();
        assertThat(replayJob.getState()).isEqualTo(ImportJobState.READY);
        ImportItem replay = items.findFirstByJobIdAndPlanGenerationAndNormalizedKindAndSourceId(
                replayJob.getId(), 1, ImportRecordKind.REVIEW, deferredId.toString()).orElseThrow();
        List<MediaReference> episodes = List.of(MediaReference.episode(SERIES_ID, 1, 1),
                MediaReference.episode(SERIES_ID, 1, 2));

        var replayPreview = previewService.preview(owner.getId(), replayJob.getId(), 0, 20).getContent().stream()
                .filter(item -> item.itemId().equals(replay.getId())).findFirst().orElseThrow();
        assertThat(replayPreview.watchScopePreview()).isNotNull();
        assertThat(replayPreview.watchScopePreview().scheduleVersion()).isEqualTo("series-scope-v1");

        previewService.resolve(owner.getId(), replayJob.getId(), List.of(new ImportResolutionOperation.ConfirmWatchScope(
                summary.getId(), ImportResolutionOperation.WatchScope.ALL_RELEASED_EPISODES,
                "series-scope-v1", episodes)), replayJob.getPreviewVersion());
        ImportItem preparedReplay = items.findById(replay.getId()).orElseThrow();
        assertThat(preparedReplay.getDecision()).isEqualTo(
                com.watchwise.watchwise_api.platformimport.model.ImportDecision.CONFIRM_WATCH_SCOPE);
        var replayPayload = mapper.readTree(preparedReplay.getPayloadJson());
        assertThat(replayPayload.path("resolution").path("confirmWatchScope").path("scheduleVersion").asString())
                .isEqualTo("series-scope-v1");
        assertThat(replayPayload.path("resolution").path("confirmWatchScope").path("episodeReferences"))
                .hasSize(2);
        ImportTarget replayTarget = mapper.treeToValue(mapper.readTree(preparedReplay.getResolvedTargetJson()),
                ImportTarget.class);
        assertThat(replayTarget).isInstanceOf(ImportTarget.ContentTarget.class);
        MediaReference replaySeries = ((ImportTarget.ContentTarget) replayTarget).content();
        assertThat(replaySeries.type()).isEqualTo(ContentType.SERIES);
        assertThat(replaySeries.tmdbId()).isEqualTo(SERIES_ID);

        assertThat(worker.runAvailable(1)).isEqualTo(1);

        replayJob = jobs.findById(replayJob.getId()).orElseThrow();
        replayJob.setState(ImportJobState.IMPORTING);
        replayJob.setWorkPhase("APPLY");
        replayJob.setWorkPending(true);
        replayJob.setLeaseToken(UUID.randomUUID());
        replayJob.setNextRunAt(LocalDateTime.now(Clock.systemUTC()).minusMinutes(1));
        replayJob.setLeaseUntil(LocalDateTime.now(Clock.systemUTC()).minusMinutes(1));
        jobs.saveAndFlush(replayJob);
        org.mockito.Mockito.clearInvocations(completionScopeLoader, tmdbClient);
        assertThat(worker.runAvailable(1)).isEqualTo(1);
        org.mockito.Mockito.verifyNoInteractions(completionScopeLoader, tmdbClient);
        assertThat(items.findById(summary.getId()).orElseThrow().getOutcome()).isEqualTo("SKIPPED");
        ImportItem appliedReplay = items.findById(replay.getId()).orElseThrow();
        assertThat(appliedReplay.getOutcome()).as("replay issue: %s", appliedReplay.getIssue())
                .isEqualTo("APPLIED");
        List<java.util.Map<String, Object>> confirmedEpisodeLinks = jdbc.queryForList(
                "SELECT target_type, target_id FROM external_import_links "
                        + "WHERE user_id=? AND source_namespace=? AND object_kind='confirmed_episode'",
                owner.getId(), replayJob.getSourceNamespace());
        assertThat(confirmedEpisodeLinks).as("confirmed episode source links").hasSize(2);
        assertThat(jdbc.queryForList("SELECT content.type, content.tmdb_id, content.series_tmdb_id, "
                        + "content.season_number, content.episode_number FROM diary_entries diary "
                        + "JOIN contents content ON content.id=diary.content_id WHERE diary.user_id=?",
                owner.getId())).as("recorded episode references").hasSize(2);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM diary_entries diary JOIN contents content "
                        + "ON content.id=diary.content_id WHERE diary.user_id=? AND content.type='EPISODE' "
                        + "AND content.series_tmdb_id=?", Long.class, owner.getId(), SERIES_ID)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM diary_entries diary JOIN contents content "
                        + "ON content.id=diary.content_id WHERE diary.user_id=? AND content.type='EPISODE' "
                        + "AND content.series_tmdb_id=? AND diary.watched_date IS NOT NULL",
                Long.class, owner.getId(), SERIES_ID)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM import_deferred_records WHERE id=?",
                Long.class, deferredId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT provenance::jsonb ->> 'resolutionState' "
                        + "FROM import_deferred_records WHERE id=?", String.class, deferredId))
                .isEqualTo("RESOLVED_RESTORED");
        assertThat(reviews.findByUserIdAndContentIdOrderByCreatedAtDesc(owner.getId(), series.getId()))
                .singleElement().extracting(ContentReview::getComment).isEqualTo("Review after all episodes");
        assertThat(items.findById(replay.getId()).orElseThrow().getOutcome()).isEqualTo("APPLIED");
    }

    @Test
    void unresolvedDeferredReplayWaitsForExplicitSkipAndThenClearsThePendingSource() throws Exception {
        User owner = createUser();
        createContent(ContentType.SERIES, SERIES_ID, null, null, null);
        ImportItem original = createReviewItem(owner, "skip-deferred-review", "Still pending", 7,
                LocalDate.of(2026, 10, 3), SERIES_ID);
        apply(original);
        UUID deferredId = jdbc.queryForObject("SELECT id FROM import_deferred_records WHERE user_id=?",
                UUID.class, owner.getId());
        finishJob(original.getJobId());

        ImportJob replayJob = createJob(owner);
        ImportItem replay = createDeferredReplayItem(owner, replayJob);

        apply(replay);

        assertThat(jobs.findById(replayJob.getId()).orElseThrow().getState()).isEqualTo(ImportJobState.READY);
        assertThat(items.findById(replay.getId()).orElseThrow().getIssue())
                .startsWith("DEFERRED_OPINION_EVIDENCE_PENDING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM import_deferred_records WHERE id=?",
                Long.class, deferredId)).isEqualTo(1L);

        ImportItem pending = items.findById(replay.getId()).orElseThrow();
        pending.setDecision(com.watchwise.watchwise_api.platformimport.model.ImportDecision.SKIP_ITEM);
        pending.setOutcome("RESOLVED");
        pending.setIssue(null);
        items.saveAndFlush(pending);
        ImportJob resumedJob = jobs.findById(replayJob.getId()).orElseThrow();
        resumedJob.setState(ImportJobState.IMPORTING);
        resumedJob.setWorkPhase("APPLY");
        resumedJob.setWorkPending(true);
        resumedJob.setLeaseToken(UUID.randomUUID());
        resumedJob.setLeaseUntil(LocalDateTime.now(Clock.systemUTC()).plusMinutes(2));
        jobs.saveAndFlush(resumedJob);

        apply(pending);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM import_deferred_records WHERE id=?",
                Long.class, deferredId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT provenance::jsonb ->> 'resolutionState' "
                        + "FROM import_deferred_records WHERE id=?", String.class, deferredId))
                .isEqualTo("RESOLVED_SKIPPED");
        assertThat(items.findById(replay.getId()).orElseThrow().getOutcome()).isEqualTo("SKIPPED");
        assertThat(jdbc.queryForObject("SELECT state FROM external_import_links WHERE user_id=? "
                        + "AND source_namespace=? AND object_kind=? AND source_id=?", String.class,
                owner.getId(), "trakt:account-test", "review", "skip-deferred-review"))
                .isEqualTo("SKIPPED");
    }

    @Test
    void skipOnOriginalWatchedSummaryDiscardsItsMatchingDeferredOpinion() throws Exception {
        User owner = createUser();
        createContent(ContentType.SERIES, SERIES_ID, null, null, null);
        ImportItem original = createReviewItem(owner, "summary-deferred-skip", "Review to skip", 7,
                LocalDate.of(2026, 10, 4), SERIES_ID, "watched_summary");
        apply(original);
        UUID deferredId = jdbc.queryForObject("SELECT id FROM import_deferred_records WHERE user_id=?",
                UUID.class, owner.getId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM import_deferred_records deferred "
                        + "JOIN external_import_links link ON link.user_id=deferred.user_id "
                        + "AND link.source_namespace=deferred.source_namespace AND link.object_kind=deferred.object_kind "
                        + "AND link.source_id=deferred.source_id AND link.state='APPLIED' "
                        + "AND link.target_type='IMPORT_DEFERRED_RECORD' AND link.target_id=deferred.id "
                        + "WHERE deferred.user_id=? AND deferred.source_namespace=? "
                        + "AND deferred.object_kind=? AND deferred.source_id=?", Long.class,
                owner.getId(), "trakt:account-test", "watched_summary", "summary-deferred-skip")).isEqualTo(1L);
        finishJob(original.getJobId());

        ImportJob replayJob = createJob(owner);
        replayJob.setState(ImportJobState.READY);
        replayJob.setWorkPending(false);
        replayJob.setLeaseToken(null);
        replayJob.setLeaseUntil(null);
        replayJob = jobs.saveAndFlush(replayJob);
        ImportItem summary = createSummaryItem(owner, replayJob.getId(), "summary-deferred-skip", SERIES_ID,
                "{\"declaredPlays\":1}");
        ImportItem replay = createDeferredReplayItem(owner, replayJob);

        previewService.resolve(owner.getId(), replayJob.getId(), List.of(
                new ImportResolutionOperation.SkipItem(summary.getId())), replayJob.getPreviewVersion());

        assertThat(jdbc.queryForObject("SELECT decision FROM import_items WHERE id=?", String.class,
                replay.getId())).isEqualTo("SKIP_ITEM");
        assertThat(items.findById(replay.getId()).orElseThrow().getDecision())
                .isEqualTo(com.watchwise.watchwise_api.platformimport.model.ImportDecision.SKIP_ITEM);
        ImportJob runnable = jobs.findById(replayJob.getId()).orElseThrow();
        runnable.setState(ImportJobState.IMPORTING);
        runnable.setWorkPhase("APPLY");
        runnable.setWorkPending(true);
        runnable.setNextRunAt(LocalDateTime.now(Clock.systemUTC()).minusMinutes(1));
        runnable.setLeaseToken(UUID.randomUUID());
        runnable.setLeaseUntil(LocalDateTime.now(Clock.systemUTC()).minusMinutes(1));
        jobs.saveAndFlush(runnable);

        assertThat(worker.runAvailable(1)).isEqualTo(1);

        assertThat(items.findById(summary.getId()).orElseThrow().getOutcome()).isEqualTo("SKIPPED");
        assertThat(items.findById(replay.getId()).orElseThrow().getOutcome()).isEqualTo("SKIPPED");
        assertThat(jdbc.queryForObject("SELECT provenance::jsonb ->> 'resolutionState' "
                        + "FROM import_deferred_records WHERE id=?", String.class, deferredId))
                .isEqualTo("RESOLVED_SKIPPED");
        assertThat(jdbc.queryForObject("SELECT state FROM external_import_links WHERE user_id=? "
                        + "AND source_namespace=? AND object_kind=? AND source_id=?", String.class,
                owner.getId(), "trakt:account-test", "watched_summary", "summary-deferred-skip"))
                .isEqualTo("SKIPPED");
        assertThat(jdbc.queryForObject("SELECT state FROM external_import_links WHERE user_id=? "
                        + "AND source_namespace=? AND object_kind=? AND source_id=?", String.class,
                owner.getId(), replayJob.getSourceNamespace(), "deferred_opinion_replay", deferredId.toString()))
                .isEqualTo("SKIPPED");
    }

    @Test
    void sameSourceOpinionReimportPreviewsDeferredReviewAndRatingWithoutDetailedHistory() throws Exception {
        User owner = createUser();
        createContent(ContentType.SERIES, SERIES_ID, null, null, null);

        ImportItem originalReview = createReviewItem(owner, "same-source-review", "Original review", 7,
                LocalDate.of(2026, 10, 6), SERIES_ID);
        apply(originalReview);
        UUID reviewDeferredId = jdbc.queryForObject("SELECT id FROM import_deferred_records "
                + "WHERE user_id=? AND object_kind='review' AND source_id=?", UUID.class,
                owner.getId(), "same-source-review");
        finishJob(originalReview.getJobId());

        ImportJob ratingOriginJob = createJob(owner);
        ImportItem originalRating = createRatingItem(owner, ratingOriginJob.getId(), "same-source-rating", 8,
                SERIES_ID);
        apply(originalRating);
        UUID ratingDeferredId = jdbc.queryForObject("SELECT id FROM import_deferred_records "
                + "WHERE user_id=? AND object_kind='rating' AND source_id=?", UUID.class,
                owner.getId(), "same-source-rating");
        finishJob(originalReview.getJobId());
        finishJob(ratingOriginJob.getId());

        ImportJob reimportJob = createJob(owner);
        reimportJob.setState(ImportJobState.RESOLVING);
        reimportJob.setWorkPhase("RESOLVE");
        reimportJob.setWorkPending(true);
        reimportJob.setSelectionJson("{\"schemaVersion\":1,\"planGeneration\":1,\"selection\":{"
                + "\"history\":true,\"watchlist\":false,\"listIds\":[],\"metadata\":null}}");
        LocalDateTime retryAt = LocalDateTime.now(Clock.systemUTC()).minusMinutes(1);
        reimportJob.setNextRunAt(retryAt);
        reimportJob.setLeaseUntil(retryAt);
        reimportJob = jobs.saveAndFlush(reimportJob);
        ImportItem changedReview = createReviewItem(owner, reimportJob.getId(), "same-source-review",
                "Edited upstream review", 9, LocalDate.of(2026, 10, 7), SERIES_ID);
        ImportItem changedRating = createRatingItem(owner, reimportJob.getId(), "same-source-rating", 9,
                SERIES_ID);

        assertThat(worker.runAvailable(1)).isEqualTo(1);

        reimportJob = jobs.findById(reimportJob.getId()).orElseThrow();
        List<String> stagedState = items.findByJobIdAndPlanGenerationOrderByOrdinalAscIdAsc(
                        reimportJob.getId(), 1, org.springframework.data.domain.PageRequest.of(0, 20)).getContent()
                .stream().map(item -> item.getNormalizedKind() + "/" + item.getOutcome() + "/"
                        + item.getDecision() + "/" + item.getIssue() + "/" + item.getResolvedTargetJson()).toList();
        assertThat(reimportJob.getState()).as("pending=%s, phase=%s, items=%s",
                reimportJob.isWorkPending(), reimportJob.getWorkPhase(), stagedState)
                .isEqualTo(ImportJobState.READY);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM diary_entries WHERE user_id=?", Long.class,
                owner.getId())).isZero();
        List<ImportItem> replayItems = items.findByJobIdAndPlanGenerationOrderByOrdinalAscIdAsc(
                        reimportJob.getId(), 1, org.springframework.data.domain.PageRequest.of(0, 20)).getContent()
                .stream().filter(item -> "deferred_opinion_replay".equals(item.getObjectKind())).toList();
        assertThat(replayItems).hasSize(2);
        ImportItem reviewReplay = replayItems.stream()
                .filter(item -> reviewDeferredId.toString().equals(item.getSourceId())).findFirst().orElseThrow();
        ImportItem ratingReplay = replayItems.stream()
                .filter(item -> ratingDeferredId.toString().equals(item.getSourceId())).findFirst().orElseThrow();

        var previewItems = previewService.preview(owner.getId(), reimportJob.getId(), 0, 20).getContent();
        assertThat(previewItems).filteredOn(item -> item.itemId().equals(reviewReplay.getId()))
                .singleElement().satisfies(item -> assertThat(item.watchScopePreview()).isNotNull());
        assertThat(previewItems).filteredOn(item -> item.itemId().equals(ratingReplay.getId()))
                .singleElement().satisfies(item -> assertThat(item.watchScopePreview()).isNotNull());

        List<MediaReference> knownEpisodes = List.of(MediaReference.episode(SERIES_ID, 1, 1),
                MediaReference.episode(SERIES_ID, 1, 2));
        previewService.resolve(owner.getId(), reimportJob.getId(), List.of(
                new ImportResolutionOperation.ConfirmWatchScope(reviewReplay.getId(),
                        ImportResolutionOperation.WatchScope.ALL_RELEASED_EPISODES,
                        "series-scope-v1", knownEpisodes),
                new ImportResolutionOperation.SkipItem(ratingReplay.getId())), reimportJob.getPreviewVersion());
        assertThat(items.findById(reviewReplay.getId()).orElseThrow().getDecision())
                .isEqualTo(com.watchwise.watchwise_api.platformimport.model.ImportDecision.CONFIRM_WATCH_SCOPE);
        assertThat(items.findById(ratingReplay.getId()).orElseThrow().getDecision())
                .isEqualTo(com.watchwise.watchwise_api.platformimport.model.ImportDecision.SKIP_ITEM);

        assertThat(worker.runAvailable(1)).isEqualTo(1);
        reimportJob = jobs.findById(reimportJob.getId()).orElseThrow();
        List<String> postConfirmationState = items.findByJobIdAndPlanGenerationOrderByOrdinalAscIdAsc(
                        reimportJob.getId(), 1, org.springframework.data.domain.PageRequest.of(0, 20)).getContent()
                .stream().map(item -> item.getNormalizedKind() + "/" + item.getObjectKind() + "/"
                        + item.getOutcome() + "/" + item.getDecision() + "/" + item.getIssue()).toList();
        assertThat(reimportJob.getState()).as("pending=%s, phase=%s, items=%s",
                reimportJob.isWorkPending(), reimportJob.getWorkPhase(), postConfirmationState)
                .isEqualTo(ImportJobState.READY);
        reimportJob.setState(ImportJobState.IMPORTING);
        reimportJob.setWorkPhase("APPLY");
        reimportJob.setWorkPending(true);
        reimportJob.setLeaseToken(UUID.randomUUID());
        reimportJob.setNextRunAt(retryAt);
        reimportJob.setLeaseUntil(retryAt);
        jobs.saveAndFlush(reimportJob);

        assertThat(worker.runAvailable(1)).isEqualTo(1);

        assertThat(items.findById(changedReview.getId()).orElseThrow().getOutcome()).isEqualTo("SKIPPED");
        assertThat(items.findById(changedRating.getId()).orElseThrow().getOutcome()).isEqualTo("SKIPPED");
        assertThat(items.findById(reviewReplay.getId()).orElseThrow().getOutcome()).isEqualTo("APPLIED");
        assertThat(items.findById(ratingReplay.getId()).orElseThrow().getOutcome()).isEqualTo("SKIPPED");
        assertThat(jdbc.queryForObject("SELECT provenance::jsonb ->> 'resolutionState' "
                        + "FROM import_deferred_records WHERE id=?", String.class, reviewDeferredId))
                .isEqualTo("RESOLVED_RESTORED");
        assertThat(jdbc.queryForObject("SELECT provenance::jsonb ->> 'resolutionState' "
                        + "FROM import_deferred_records WHERE id=?", String.class, ratingDeferredId))
                .isEqualTo("RESOLVED_SKIPPED");
        assertThat(reviews.findByUserIdAndContentIdOrderByCreatedAtDesc(owner.getId(),
                        contents.findByTmdbIdAndType(SERIES_ID, ContentType.SERIES).orElseThrow().getId()))
                .singleElement().extracting(ContentReview::getComment).isEqualTo("Original review");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM diary_entries WHERE user_id=?", Long.class,
                owner.getId())).isEqualTo(2L);
    }

    @Test
    void sameSourceReviewOnlyReimportStagesPreviewableDeferredReplayWithoutEpisodeHistory() throws Exception {
        User owner = createUser();
        createContent(ContentType.SERIES, SERIES_ID, null, null, null);
        ImportItem original = createReviewItem(owner, "review-only-reimport", "Original review", 7,
                LocalDate.of(2026, 10, 8), SERIES_ID);
        apply(original);
        UUID deferredId = jdbc.queryForObject("SELECT id FROM import_deferred_records "
                        + "WHERE user_id=? AND object_kind='review' AND source_id=?", UUID.class,
                owner.getId(), "review-only-reimport");
        finishJob(original.getJobId());

        ImportJob reimportJob = createJob(owner);
        reimportJob.setState(ImportJobState.RESOLVING);
        reimportJob.setWorkPhase("RESOLVE");
        reimportJob.setWorkPending(true);
        reimportJob.setSelectionJson("{\"schemaVersion\":1,\"planGeneration\":1,\"selection\":{"
                + "\"history\":true,\"watchlist\":false,\"listIds\":[],\"metadata\":null}}");
        LocalDateTime retryAt = LocalDateTime.now(Clock.systemUTC()).minusMinutes(1);
        reimportJob.setNextRunAt(retryAt);
        reimportJob.setLeaseUntil(retryAt);
        reimportJob = jobs.saveAndFlush(reimportJob);
        String overlongSourceReview = "x".repeat(20_001);
        ImportItem sourceReview = createReviewItem(owner, reimportJob.getId(), "review-only-reimport",
                overlongSourceReview, 9, LocalDate.of(2026, 10, 9), SERIES_ID);

        assertThat(worker.runAvailable(1)).isEqualTo(1);

        reimportJob = jobs.findById(reimportJob.getId()).orElseThrow();
        assertThat(reimportJob.getState()).isEqualTo(ImportJobState.RESOLVING);
        assertThat(reimportJob.isWorkPending()).isFalse();
        assertThat(items.findById(sourceReview.getId()).orElseThrow().getOutcome())
                .isEqualTo("NORMALIZATION_ISSUE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM diary_entries WHERE user_id=?", Long.class,
                owner.getId())).isZero();
        List<ImportItem> replays = items.findByJobIdAndPlanGenerationOrderByOrdinalAscIdAsc(
                        reimportJob.getId(), 1, org.springframework.data.domain.PageRequest.of(0, 20)).getContent()
                .stream().filter(item -> "deferred_opinion_replay".equals(item.getObjectKind())).toList();
        assertThat(replays).singleElement().satisfies(replay -> {
            assertThat(replay.getSourceId()).isEqualTo(deferredId.toString());
            assertThat(replay.getNormalizedKind()).isEqualTo(ImportRecordKind.REVIEW);
        });
        ImportItem replay = replays.getFirst();
        var preview = previewService.preview(owner.getId(), reimportJob.getId(), 0, 20).getContent();
        assertThat(preview).filteredOn(item -> item.itemId().equals(replay.getId())).singleElement()
                .satisfies(item -> {
                    assertThat(item.watchScopePreview()).isNotNull();
                    assertThat(item.watchScopePreview().scheduleVersion()).isEqualTo("series-scope-v1");
                });

        previewService.resolve(owner.getId(), reimportJob.getId(), List.of(
                new ImportResolutionOperation.SkipItem(sourceReview.getId()),
                new ImportResolutionOperation.ConfirmWatchScope(replay.getId(),
                        ImportResolutionOperation.WatchScope.ALL_RELEASED_EPISODES, "series-scope-v1",
                        List.of(MediaReference.episode(SERIES_ID, 1, 1),
                                MediaReference.episode(SERIES_ID, 1, 2)))), reimportJob.getPreviewVersion());

        assertThat(items.findById(sourceReview.getId()).orElseThrow().getDecision())
                .isEqualTo(com.watchwise.watchwise_api.platformimport.model.ImportDecision.SKIP_ITEM);
        assertThat(items.findById(replay.getId()).orElseThrow().getDecision())
                .isEqualTo(com.watchwise.watchwise_api.platformimport.model.ImportDecision.CONFIRM_WATCH_SCOPE);

        assertThat(worker.runAvailable(1)).isEqualTo(1);
        reimportJob = jobs.findById(reimportJob.getId()).orElseThrow();
        assertThat(reimportJob.getState()).isEqualTo(ImportJobState.READY);
        reimportJob.setState(ImportJobState.IMPORTING);
        reimportJob.setWorkPhase("APPLY");
        reimportJob.setWorkPending(true);
        reimportJob.setLeaseToken(UUID.randomUUID());
        reimportJob.setNextRunAt(retryAt);
        reimportJob.setLeaseUntil(retryAt);
        jobs.saveAndFlush(reimportJob);

        assertThat(worker.runAvailable(1)).isEqualTo(1);

        assertThat(items.findById(sourceReview.getId()).orElseThrow().getOutcome()).isEqualTo("SKIPPED");
        assertThat(items.findById(replay.getId()).orElseThrow().getOutcome()).isEqualTo("APPLIED");
        assertThat(jdbc.queryForObject("SELECT provenance::jsonb ->> 'resolutionState' "
                        + "FROM import_deferred_records WHERE id=?", String.class, deferredId))
                .isEqualTo("RESOLVED_RESTORED");
        assertThat(reviews.findByUserIdAndContentIdOrderByCreatedAtDesc(owner.getId(),
                        contents.findByTmdbIdAndType(SERIES_ID, ContentType.SERIES).orElseThrow().getId()))
                .singleElement().extracting(ContentReview::getComment).isEqualTo("Original review");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM diary_entries WHERE user_id=?", Long.class,
                owner.getId())).isEqualTo(2L);
    }

    @Test
    void unrelatedHistoryImportDoesNotStageOrPauseAnotherSeriesDeferredOpinion() throws Exception {
        User owner = createUser();
        String deferredSeriesId = SERIES_ID;
        String unrelatedSeriesId = "88002";
        createContent(ContentType.SERIES, deferredSeriesId, null, null, null);
        createContent(ContentType.SERIES, unrelatedSeriesId, null, null, null);
        ImportItem original = createReviewItem(owner, "unrelated-deferred-review", "Still pending", 7,
                LocalDate.of(2026, 10, 5), deferredSeriesId);
        apply(original);
        UUID deferredId = jdbc.queryForObject("SELECT id FROM import_deferred_records WHERE user_id=?",
                UUID.class, owner.getId());
        finishJob(original.getJobId());

        when(completionScopeLoader.load(eq(MediaReference.series(unrelatedSeriesId)), eq("US"), eq("en-US"),
                any(Instant.class)))
                .thenReturn(new VersionedCompletionScope(new CompletionScope(MediaReference.series(unrelatedSeriesId),
                        Set.of(new EpisodeCoordinate(1, 1)), true), "unrelated-schedule-v1",
                        Instant.parse("2030-01-01T00:00:00Z")));

        ImportJob unrelatedJob = createJob(owner);
        unrelatedJob.setState(ImportJobState.RESOLVING);
        unrelatedJob.setWorkPhase("RESOLVE");
        unrelatedJob.setWorkPending(true);
        unrelatedJob.setSelectionJson("{\"schemaVersion\":1,\"planGeneration\":1,\"selection\":{"
                + "\"history\":true,\"watchlist\":false,\"listIds\":[],\"metadata\":null}}");
        LocalDateTime retryAt = LocalDateTime.now(Clock.systemUTC()).minusMinutes(1);
        unrelatedJob.setNextRunAt(retryAt);
        unrelatedJob.setLeaseUntil(retryAt);
        unrelatedJob = jobs.saveAndFlush(unrelatedJob);
        ImportItem unrelatedSummary = createSummaryItem(owner, unrelatedJob.getId(),
                "unrelated-summary", unrelatedSeriesId, "{\"declaredPlays\":1}");

        assertThat(worker.runAvailable(1)).isEqualTo(1);
        unrelatedJob = jobs.findById(unrelatedJob.getId()).orElseThrow();
        assertThat(unrelatedJob.getState()).isEqualTo(ImportJobState.READY);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM import_items WHERE job_id=? "
                        + "AND plan_generation=1 AND object_kind='deferred_opinion_replay'", Long.class,
                unrelatedJob.getId())).isZero();

        unrelatedJob.setState(ImportJobState.IMPORTING);
        unrelatedJob.setWorkPhase("APPLY");
        unrelatedJob.setWorkPending(true);
        unrelatedJob.setNextRunAt(retryAt);
        unrelatedJob.setLeaseToken(UUID.randomUUID());
        unrelatedJob.setLeaseUntil(retryAt);
        jobs.saveAndFlush(unrelatedJob);
        assertThat(worker.runAvailable(1)).isEqualTo(1);

        assertThat(jobs.findById(unrelatedJob.getId()).orElseThrow().getState())
                .isEqualTo(ImportJobState.COMPLETED);
        assertThat(items.findById(unrelatedSummary.getId()).orElseThrow().getOutcome()).isEqualTo("APPLIED");
        assertThat(jdbc.queryForObject("SELECT provenance::jsonb ->> 'resolutionState' "
                        + "FROM import_deferred_records WHERE id=?", String.class, deferredId))
                .isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT target_type FROM external_import_links WHERE user_id=? "
                        + "AND source_namespace=? AND object_kind='review' AND source_id=?", String.class,
                owner.getId(), "trakt:account-test", "unrelated-deferred-review"))
                .isEqualTo("IMPORT_DEFERRED_RECORD");
    }

    @Test
    void showSummaryWithoutDetailHistoryStoresUnknownAssertionWithoutCreatingEpisodes() throws Exception {
        User owner = createUser();
        Content series = createContent(ContentType.SERIES, SERIES_ID, null, null, null);
        ImportItem summary = createSummaryItem(owner, "show-summary-1", SERIES_ID,
                "{\"declaredPlays\":1,\"declaredLastWatchedOn\":\"2025-06-03\",\"airedEpisodeCount\":84}");

        apply(summary);
        ImportItem rating = createRatingItem(owner, summary.getJobId(), "show-rating-1", 8, SERIES_ID);
        apply(rating);

        assertThat(assertions.findAll()).singleElement().satisfies(assertion -> {
            assertThat(assertion.getContent().getId()).isEqualTo(series.getId());
            assertThat(assertion.getDeclaredPlays()).isEqualTo(1);
            assertThat(assertion.getDeclaredDate()).isEqualTo(LocalDate.of(2025, 6, 3));
            assertThat(assertion.getCoverage()).isEqualTo("UNKNOWN");
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM diary_entries WHERE user_id=?", Long.class,
                owner.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT target_type FROM external_import_links "
                        + "WHERE user_id=? AND source_namespace=? AND object_kind=? AND source_id=?",
                String.class, owner.getId(), "trakt:account-test", "watched_summary", "show-summary-1"))
                .isEqualTo("IMPORTED_WATCH_ASSERTION");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM import_deferred_records WHERE user_id=?", Long.class,
                owner.getId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content_ratings WHERE user_id=?", Long.class,
                owner.getId())).isZero();
    }

    @Test
    void partialEpisodeHistoryConsumesShowSummaryWithoutCreatingAnAssertion() throws Exception {
        User owner = createUser();
        createContent(ContentType.SERIES, SERIES_ID, null, null, null);
        createContent(ContentType.EPISODE, null, SERIES_ID, 1, 1);
        Content episode = contents.findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType(
                SERIES_ID, 1, 1, ContentType.EPISODE).orElseThrow();
        LocalDateTime now = LocalDateTime.now();
        diaryEntries.saveAndFlush(DiaryEntry.builder().user(owner).content(episode).watchNumber(1)
                .watchedDate(LocalDate.of(2020, 1, 1)).createdAt(now).updatedAt(now).build());
        ImportItem summary = createSummaryItem(owner, "show-summary-2", SERIES_ID, "{\"declaredPlays\":1}");

        apply(summary);

        assertThat(assertions.findAll()).isEmpty();
        assertThat(items.findById(summary.getId()).orElseThrow().getOutcome()).isEqualTo("SKIPPED");
        assertThat(items.findById(summary.getId()).orElseThrow().getIssue())
                .isEqualTo("DETAIL_HISTORY_COVERS_SUMMARY");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM diary_entries WHERE user_id=?", Long.class,
                owner.getId())).isEqualTo(1L);
    }

    private void apply(ImportItem item) {
        ImportJob job = jobs.findById(item.getJobId()).orElseThrow();
        assertThat(unitTransaction.apply(new ImportExecutionContext(job.getUserId(), job.getId(),
                job.getPreviewVersion(), 1, job.getSourceNamespace(), job.getLeaseToken()), item.getId())).isTrue();
    }

    private void finishJob(UUID jobId) {
        ImportJob job = jobs.findById(jobId).orElseThrow();
        job.setState(ImportJobState.COMPLETED);
        job.setWorkPending(false);
        job.setLeaseToken(null);
        job.setLeaseUntil(null);
        jobs.saveAndFlush(job);
    }

    private ImportItem createReviewItem(User owner, String sourceId, String text, int score, LocalDate publishedOn,
                                        String seriesId) throws Exception {
        return createReviewItem(owner, createJob(owner).getId(), sourceId, text, score, publishedOn, seriesId,
                "review");
    }

    private ImportItem createReviewItem(User owner, String sourceId, String text, int score, LocalDate publishedOn,
                                        String seriesId, String objectKind) throws Exception {
        return createReviewItem(owner, createJob(owner).getId(), sourceId, text, score, publishedOn, seriesId,
                objectKind);
    }

    private ImportItem createReviewItem(User owner, UUID jobId, String sourceId, String text, int score,
                                        LocalDate publishedOn, String seriesId) throws Exception {
        return createReviewItem(owner, jobId, sourceId, text, score, publishedOn, seriesId, "review");
    }

    private ImportItem createReviewItem(User owner, UUID jobId, String sourceId, String text, int score,
                                        LocalDate publishedOn, String seriesId, String objectKind) throws Exception {
        ImportJob job = jobs.findById(jobId).orElseThrow();
        Content series = contents.findByTmdbIdAndType(seriesId, ContentType.SERIES).orElseThrow();
        var payload = mapper.createObjectNode().put("review", text).put("score", score)
                .put("sourceRecordedOn", publishedOn.toString());
        ImportItem item = ImportItem.builder().jobId(jobId).planGeneration(1).ordinal(items.countByJobId(jobId))
                .normalizedKind(ImportRecordKind.REVIEW).sourceNamespace(job.getSourceNamespace())
                .objectKind(objectKind).sourceId(sourceId).sourceEventKey(sourceId)
                .parserVersion("trakt-history-v1")
                .payloadJson(mapper.writeValueAsString(payload))
                .resolvedTargetJson(mapper.writeValueAsString(new ImportTarget.ContentTarget(
                        MediaReference.series(seriesId))))
                .resolvedContentKey("CONTENT|SERIES|" + seriesId).outcome("MATCHED").build();
        seedScopeSnapshot(item);
        return items.saveAndFlush(item);
    }

    private ImportJob createWorkerReadyJob(User owner) {
        ImportJob job = createJob(owner);
        LocalDateTime retryAt = LocalDateTime.now(Clock.systemUTC()).minusMinutes(1);
        job.setNextRunAt(retryAt);
        job.setLeaseUntil(retryAt);
        return jobs.saveAndFlush(job);
    }

    private ImportItem addDiaryEvent(ImportJob job, String sourceId, MediaReference episode, LocalDate watchedDate)
            throws Exception {
        return items.saveAndFlush(ImportItem.builder().jobId(job.getId()).planGeneration(1)
                .ordinal(items.countByJobId(job.getId())).normalizedKind(ImportRecordKind.DIARY_EVENT)
                .sourceNamespace(job.getSourceNamespace()).objectKind("diary_event").sourceId(sourceId)
                .payloadJson(mapper.createObjectNode().put("watchedDate", watchedDate.toString()).toString())
                .resolvedTargetJson(mapper.writeValueAsString(new ImportTarget.ContentTarget(episode)))
                .resolvedContentKey("CONTENT|EPISODE|" + episode.seriesTmdbId() + "|"
                        + episode.seasonNumber() + "|" + episode.episodeNumber())
                .outcome("MATCHED").build());
    }

    private ImportItem createSummaryItem(User owner, String sourceId, String seriesId, String payload)
            throws Exception {
        ImportJob job = createJob(owner);
        return createSummaryItem(owner, job.getId(), sourceId, seriesId, payload);
    }

    private ImportItem createSummaryItem(User owner, UUID jobId, String sourceId, String seriesId, String payload)
            throws Exception {
        ImportJob job = jobs.findById(jobId).orElseThrow();
        return items.saveAndFlush(ImportItem.builder().jobId(jobId).planGeneration(1).ordinal(items.countByJobId(jobId))
                .normalizedKind(ImportRecordKind.WATCHED_SUMMARY).sourceNamespace(job.getSourceNamespace())
                .objectKind("watched_summary").sourceId(sourceId).payloadJson(payload)
                .resolvedTargetJson(mapper.writeValueAsString(new ImportTarget.ContentTarget(
                        MediaReference.series(seriesId))))
                .resolvedContentKey("CONTENT|SERIES|" + seriesId).outcome("MATCHED").build());
    }

    private ImportItem createRatingItem(User owner, UUID jobId, String sourceId, int score, String seriesId)
            throws Exception {
        ImportJob job = jobs.findById(jobId).orElseThrow();
        ImportItem item = ImportItem.builder().jobId(jobId).planGeneration(1).ordinal(1)
                .normalizedKind(ImportRecordKind.CURRENT_RATING).sourceNamespace(job.getSourceNamespace())
                .objectKind("rating").sourceId(sourceId).parserVersion("trakt-history-v1")
                .payloadJson(mapper.createObjectNode()
                        .put("score", score).toString())
                .resolvedTargetJson(mapper.writeValueAsString(new ImportTarget.ContentTarget(
                        MediaReference.series(seriesId))))
                .resolvedContentKey("CONTENT|SERIES|" + seriesId).outcome("MATCHED").build();
        seedScopeSnapshot(item);
        return items.saveAndFlush(item);
    }

    private ImportItem createDeferredReplayItem(User owner, ImportJob job) throws Exception {
        List<NormalizedImportRecord> replayRecords = new ArrayList<>();
        deferredOpinionReplayStager.emit(owner.getId(), job.getSourceNamespace(),
                Set.of("CONTENT|SERIES|" + SERIES_ID), Set.of(), replayRecords::add);
        assertThat(replayRecords).singleElement().satisfies(record -> {
            assertThat(record.kind()).isEqualTo(ImportRecordKind.REVIEW);
            assertThat(record.sourceKey().objectKind()).isEqualTo("deferred_opinion_replay");
            assertThat(((ImportTarget.ContentTarget) record.target()).content())
                    .isEqualTo(MediaReference.series(SERIES_ID));
        });
        NormalizedImportRecord record = replayRecords.getFirst();
        String itemSeed = job.getId() + ":1:" + record.itemId();
        UUID itemId = UUID.nameUUIDFromBytes(itemSeed.getBytes(StandardCharsets.UTF_8));
        return items.saveAndFlush(ImportItem.builder().id(itemId).jobId(job.getId()).planGeneration(1).ordinal(0)
                .normalizedKind(record.kind()).sourceNamespace(record.sourceKey().namespace())
                .objectKind(record.sourceKey().objectKind()).sourceId(record.sourceKey().sourceId())
                .sourceEventKey(record.sourceKey().sourceId())
                .payloadJson(mapper.writeValueAsString(record.payload()))
                .resolvedTargetJson(mapper.writeValueAsString(record.target()))
                .resolvedContentKey("CONTENT|SERIES|" + SERIES_ID).outcome("MATCHED").build());
    }

    private void seedScopeSnapshot(ImportItem item) throws Exception {
        List<EpisodeCoordinate> episodes = List.of(new EpisodeCoordinate(1, 1), new EpisodeCoordinate(1, 2));
        Instant validUntil = Instant.parse("2030-01-01T00:00:00Z");
        ConfirmedWatchScopeSnapshot snapshot = new ConfirmedWatchScopeSnapshot(
                MediaReference.series(SERIES_ID), "series-scope-v1", validUntil, true, episodes, false, 0L);
        ObjectNode envelope = mapper.createObjectNode().put("_watchwiseResolution", 1);
        envelope.set("source", mapper.readTree(item.getPayloadJson()));
        ObjectNode resolution = envelope.putObject("resolution");
        resolution.set("fields", mapper.createObjectNode());
        resolution.set("watchScopeSnapshot", mapper.valueToTree(snapshot));
        resolution.set("watchScopePreview", mapper.valueToTree(new ImportWatchScopePreviewDTO(
                true, null, "series-scope-v1", validUntil, true, episodes)));
        item.setPayloadJson(mapper.writeValueAsString(envelope));
    }

    private ImportJob createJob(User owner) {
        LocalDateTime now = LocalDateTime.now(Clock.systemUTC());
        UUID lease = UUID.randomUUID();
        return jobs.saveAndFlush(ImportJob.builder().userId(owner.getId())
                .inputKind(ImportInputKind.PLATFORM_ARCHIVE).sourceFormat(ImportSourceFormat.TRAKT_ZIP)
                .platform(ImportPlatform.TRAKT).sourceAccountId("trakt-test-account")
                .sourceNamespace("trakt:account-test").state(ImportJobState.IMPORTING).previewVersion(1)
                .selectionJson("{\"schemaVersion\":1,\"planGeneration\":1,\"selection\":{}}")
                .timezone("UTC").storageKey(UUID.randomUUID().toString()).uploadSizeBytes(1)
                .workPhase("APPLY").workPending(true).leaseToken(lease).leaseUntil(now.plusMinutes(2))
                .createdAt(now).updatedAt(now).expiresAt(now.plusDays(1)).build());
    }

    private User createUser() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        LocalDateTime now = LocalDateTime.now();
        return users.saveAndFlush(User.builder().username("opinions" + suffix).name("Opinion Import")
                .email("opinions" + suffix + "@example.test").password("test-password")
                .preferredLanguage("en-US").preferredRegion("US").createdAt(now).updatedAt(now).build());
    }

    private Content createContent(ContentType type, String tmdbId, String seriesId, Integer season, Integer episode) {
        LocalDateTime now = LocalDateTime.now();
        return contents.saveAndFlush(Content.builder().type(type).tmdbId(tmdbId).seriesTmdbId(seriesId)
                .seasonNumber(season).episodeNumber(episode).createdAt(now).updatedAt(now).build());
    }

    private void addCompletePass(User owner, Content series) {
        createContent(ContentType.EPISODE, null, series.getTmdbId(), 1, 1);
        Content second = createContent(ContentType.EPISODE, null, series.getTmdbId(), 1, 2);
        Content first = contents.findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType(
                series.getTmdbId(), 1, 1, ContentType.EPISODE).orElseThrow();
        LocalDateTime now = LocalDateTime.now();
        diaryEntries.saveAndFlush(DiaryEntry.builder().user(owner).content(first).watchNumber(1)
                .watchedDate(LocalDate.of(2020, 1, 1)).createdAt(now).updatedAt(now).build());
        diaryEntries.saveAndFlush(DiaryEntry.builder().user(owner).content(second).watchNumber(1)
                .watchedDate(LocalDate.of(2020, 1, 2)).createdAt(now).updatedAt(now).build());
    }
}
