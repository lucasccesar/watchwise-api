package com.watchwise.watchwise_api.platformimport.service;

import com.watchwise.watchwise_api.content.dto.MediaReference;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.contentrating.entity.ContentRating;
import com.watchwise.watchwise_api.contentrating.repository.ContentRatingRepository;
import com.watchwise.watchwise_api.common.exception.TmdbUnavailableException;
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
import com.watchwise.watchwise_api.platformimport.repository.ImportItemRepository;
import com.watchwise.watchwise_api.platformimport.repository.ImportJobRepository;
import com.watchwise.watchwise_api.platformimport.service.impl.ImportUnitTransaction;
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

import java.time.Instant;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
    @Autowired private ObjectMapper mapper;

    @MockitoBean
    private CompletionScopeLoader completionScopeLoader;

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
    void unavailableCalendarDefersTheOpinionInsteadOfFailingTheImportJob() throws Exception {
        User owner = createUser();
        ImportJob job = createJob(owner);
        ImportItem rating = createRatingItem(owner, job.getId(), "calendar-unavailable-rating", 8, SERIES_ID);
        when(completionScopeLoader.load(any(MediaReference.class), anyString(), anyString(), any(Instant.class)))
                .thenThrow(new TmdbUnavailableException("Calendar unavailable in test"));

        apply(rating);

        assertThat(jobs.findById(job.getId()).orElseThrow().getState()).isEqualTo(ImportJobState.IMPORTING);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM import_deferred_records WHERE user_id=?", Long.class,
                owner.getId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content_ratings WHERE user_id=?", Long.class,
                owner.getId())).isZero();
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
        assertThat(items.findById(summary.getId()).orElseThrow().getOutcome()).isEqualTo("APPLIED");
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
        ImportJob job = createJob(owner);
        Content series = contents.findByTmdbIdAndType(seriesId, ContentType.SERIES).orElseThrow();
        var payload = mapper.createObjectNode().put("review", text).put("score", score)
                .put("sourceRecordedOn", publishedOn.toString());
        ImportItem item = ImportItem.builder().jobId(job.getId()).planGeneration(1).ordinal(0)
                .normalizedKind(ImportRecordKind.REVIEW).sourceNamespace(job.getSourceNamespace())
                .objectKind("review").sourceId(sourceId).payloadJson(mapper.writeValueAsString(payload))
                .resolvedTargetJson(mapper.writeValueAsString(new ImportTarget.ContentTarget(
                        MediaReference.series(seriesId))))
                .resolvedContentKey("CONTENT|SERIES|" + seriesId).outcome("MATCHED").build();
        return items.saveAndFlush(item);
    }

    private ImportItem createSummaryItem(User owner, String sourceId, String seriesId, String payload)
            throws Exception {
        ImportJob job = createJob(owner);
        return items.saveAndFlush(ImportItem.builder().jobId(job.getId()).planGeneration(1).ordinal(0)
                .normalizedKind(ImportRecordKind.WATCHED_SUMMARY).sourceNamespace(job.getSourceNamespace())
                .objectKind("watched_summary").sourceId(sourceId).payloadJson(payload)
                .resolvedTargetJson(mapper.writeValueAsString(new ImportTarget.ContentTarget(
                        MediaReference.series(seriesId))))
                .resolvedContentKey("CONTENT|SERIES|" + seriesId).outcome("MATCHED").build());
    }

    private ImportItem createRatingItem(User owner, UUID jobId, String sourceId, int score, String seriesId)
            throws Exception {
        ImportJob job = jobs.findById(jobId).orElseThrow();
        return items.saveAndFlush(ImportItem.builder().jobId(jobId).planGeneration(1).ordinal(1)
                .normalizedKind(ImportRecordKind.CURRENT_RATING).sourceNamespace(job.getSourceNamespace())
                .objectKind("rating").sourceId(sourceId).payloadJson(mapper.createObjectNode()
                        .put("score", score).toString())
                .resolvedTargetJson(mapper.writeValueAsString(new ImportTarget.ContentTarget(
                        MediaReference.series(seriesId))))
                .resolvedContentKey("CONTENT|SERIES|" + seriesId).outcome("MATCHED").build());
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
