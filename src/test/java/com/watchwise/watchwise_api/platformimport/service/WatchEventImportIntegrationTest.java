package com.watchwise.watchwise_api.platformimport.service;

import com.watchwise.watchwise_api.content.dto.MediaReference;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.diaryentry.dto.WatchEventCommand;
import com.watchwise.watchwise_api.diaryentry.dto.WatchMutationResult;
import com.watchwise.watchwise_api.diaryentry.service.DiaryEntryService;
import com.watchwise.watchwise_api.diaryentry.service.DiaryWatchCommandService;
import com.watchwise.watchwise_api.diaryentry.service.impl.DiaryWatchCommandServiceImpl;
import com.watchwise.watchwise_api.platformimport.entity.ImportItem;
import com.watchwise.watchwise_api.platformimport.entity.ImportJob;
import com.watchwise.watchwise_api.platformimport.model.ImportDecision;
import com.watchwise.watchwise_api.platformimport.model.ImportInputKind;
import com.watchwise.watchwise_api.platformimport.model.ImportJobState;
import com.watchwise.watchwise_api.platformimport.model.ImportPlatform;
import com.watchwise.watchwise_api.platformimport.model.ImportRecordKind;
import com.watchwise.watchwise_api.platformimport.model.ImportResolutionOperation;
import com.watchwise.watchwise_api.platformimport.model.ImportSourceFormat;
import com.watchwise.watchwise_api.platformimport.model.ImportSourceKey;
import com.watchwise.watchwise_api.platformimport.model.ImportTarget;
import com.watchwise.watchwise_api.platformimport.repository.ImportItemRepository;
import com.watchwise.watchwise_api.platformimport.repository.ImportJobRepository;
import com.watchwise.watchwise_api.platformimport.service.ImportExecutionContext;
import com.watchwise.watchwise_api.platformimport.service.impl.LetterboxdHistoryNormalizer;
import com.watchwise.watchwise_api.platformimport.service.impl.TraktHistoryNormalizer;
import com.watchwise.watchwise_api.platformimport.service.impl.ImportUnitTransaction;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

@SpringBootTest(properties = {
        "spring.docker.compose.enabled=false",
        "app.import.worker.enabled=false",
        "app.import.cleanup.enabled=false"
})
@Testcontainers
class WatchEventImportIntegrationTest {

    private static final AtomicLong MOVIE_IDS = new AtomicLong(700_000);

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.import.storage-directory", () -> java.nio.file.Path.of(
                System.getProperty("java.io.tmpdir"), "watchwise-c4-import-tests").toAbsolutePath().toString());
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private ContentRepository contentRepository;
    @Autowired private DiaryEntryService diaryEntryService;
    @Autowired private DiaryWatchCommandService diaryWatchCommandService;
    @Autowired private ImportJobRepository jobRepository;
    @Autowired private ImportItemRepository itemRepository;
    @Autowired private ImportLedger ledger;
    @Autowired private ImportPreviewService previewService;
    @Autowired private ImportUnitTransaction unitTransaction;
    @Autowired private LetterboxdHistoryNormalizer letterboxdHistoryNormalizer;
    @Autowired private TraktHistoryNormalizer traktHistoryNormalizer;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private Clock clock;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoSpyBean private DiaryWatchCommandServiceImpl diaryWatchCommandServiceSpy;

    @Test
    void shouldIgnoreRepeatedZipPayloadAndApplyOnlyNewIncrementalEvents() {
        User owner = createUser();
        Content movie = createMovie();
        ImportJob firstJob = createJob(owner.getId(), "trakt:account-1", ImportSourceFormat.TRAKT_ZIP);
        ImportItem first = createEvent(firstJob, movie, 0, "history-1", "2024-05-06", 5, "first review");

        assertThatCode(() -> apply(firstJob, first)).doesNotThrowAnyException();
        WatchRow original = watches(owner.getId(), movie.getId()).getFirst();
        assertThat(original.score()).isEqualTo(5);
        assertThat(original.comment()).isEqualTo("first review");
        completeJob(firstJob.getId());

        ImportJob repeatedJob = createJob(owner.getId(), "trakt:account-1", ImportSourceFormat.TRAKT_ZIP);
        ImportItem repeated = createEvent(repeatedJob, movie, 0, "history-1", "2024-05-07", 9, "changed review");
        assertThatCode(() -> apply(repeatedJob, repeated)).doesNotThrowAnyException();
        assertThat(itemRepository.findById(repeated.getId()).orElseThrow().getOutcome()).isEqualTo("SKIPPED");
        assertThat(watches(owner.getId(), movie.getId())).containsExactly(original);
        completeJob(repeatedJob.getId());

        ImportJob incrementalJob = createJob(owner.getId(), "trakt:account-1", ImportSourceFormat.TRAKT_ZIP);
        ImportItem incremental = createEvent(incrementalJob, movie, 0, "history-2", "2024-05-06", 7, null);
        assertThatCode(() -> apply(incrementalJob, incremental)).doesNotThrowAnyException();
        assertThat(watches(owner.getId(), movie.getId())).extracting(WatchRow::watchNumber).containsExactly(1, 2);
        assertThat(watches(owner.getId(), movie.getId()).getFirst()).isEqualTo(original);
        completeJob(incrementalJob.getId());
    }

    @Test
    void shouldKeepDistinctSourceEventIdsAsSeparateWatchesOnTheSameDay() {
        User owner = createUser();
        Content movie = createMovie();
        ImportJob job = createJob(owner.getId(), "letterboxd:account-1", ImportSourceFormat.LETTERBOXD_ZIP);
        List<ImportItem> events = new ArrayList<>();
        events.add(createEvent(job, movie, 0, "diary-uri-1", "2023-11-12", null, null));
        events.add(createEvent(job, movie, 1, "diary-uri-2", "2023-11-12", null, null));

        for (ImportItem event : events) {
            assertThatCode(() -> apply(job, event)).doesNotThrowAnyException();
        }

        assertThat(watches(owner.getId(), movie.getId())).extracting(WatchRow::watchNumber).containsExactly(1, 2);
        assertThat(watches(owner.getId(), movie.getId())).extracting(WatchRow::watchedDate)
                .containsExactly(LocalDate.of(2023, 11, 12), LocalDate.of(2023, 11, 12));
        completeJob(job.getId());
    }

    @Test
    void shouldApplyCurrentRatingOnlyToNewestWatchWhileKeepingOlderEventScores() throws Exception {
        User owner = createUser();
        Content movie = createMovie();
        ImportJob job = createJob(owner.getId(), "letterboxd:account-1", ImportSourceFormat.LETTERBOXD_ZIP);
        ImportItem oldest = createEvent(job, movie, 0, "rated-event-1", "2023-01-02", 4, "oldest review");
        ImportItem older = createEvent(job, movie, 1, "rated-event-2", "2024-03-04", 6, "older review");
        ImportItem newest = createEvent(job, movie, 2, "rated-event-3", "2025-05-06", 7, "newest review");
        ImportItem currentRating = createCurrentRating(job, movie, 3, "current-rating-1", 9);

        ImportJob resolvingJob = jobRepository.findById(job.getId()).orElseThrow();
        resolvingJob.setState(ImportJobState.RESOLVING);
        resolvingJob.setWorkPhase("RESOLVE");
        jobRepository.saveAndFlush(resolvingJob);
        normalizeAfterResolution(job.getId(), false);

        ImportItem normalizedOldest = itemRepository.findById(oldest.getId()).orElseThrow();
        ImportItem normalizedOlder = itemRepository.findById(older.getId()).orElseThrow();
        ImportItem normalizedNewest = itemRepository.findById(newest.getId()).orElseThrow();
        assertThat(objectMapper.readTree(normalizedOldest.getPayloadJson()).path("score").asInt()).isEqualTo(4);
        assertThat(objectMapper.readTree(normalizedOlder.getPayloadJson()).path("score").asInt()).isEqualTo(6);
        assertThat(objectMapper.readTree(normalizedNewest.getPayloadJson()).path("score").asInt()).isEqualTo(9);
        assertThat(itemRepository.findById(currentRating.getId()).orElseThrow().getOutcome()).isEqualTo("ABSORBED");

        ImportJob applyingJob = requeueAfterResolution(job.getId());
        for (ImportItem event : List.of(normalizedOldest, normalizedOlder, normalizedNewest)) {
            assertThatCode(() -> apply(applyingJob, event)).doesNotThrowAnyException();
        }

        assertThat(watches(owner.getId(), movie.getId())).extracting(WatchRow::score).containsExactly(4, 6, 9);
        assertThat(watches(owner.getId(), movie.getId())).extracting(WatchRow::comment)
                .containsExactly("oldest review", "older review", "newest review");
        assertThat(watches(owner.getId(), movie.getId())).extracting(WatchRow::watchedDate)
                .containsExactly(LocalDate.of(2023, 1, 2), LocalDate.of(2024, 3, 4), LocalDate.of(2025, 5, 6));
    }

    @Test
    void shouldPauseForManualOrCrossPlatformMatchesUntilLinkedOrIgnored() {
        User owner = createUser();
        Content movie = createMovie();
        LocalDate watchedOn = LocalDate.of(2022, 8, 9);
        WatchMutationResult manual = diaryWatchCommandService.recordPrepared(owner.getId(),
                new WatchEventCommand(MediaReference.movie(movie.getTmdbId()), watchedOn, null,
                        "manual entry", null, null, List.of(), null));

        ImportJob linkJob = createJob(owner.getId(), "letterboxd:account-1", ImportSourceFormat.LETTERBOXD_ZIP);
        ImportItem linkItem = createEvent(linkJob, movie, 0, "letterboxd-event-1", "2022-08-09", null, null);
        assertThatCode(() -> apply(linkJob, linkItem)).doesNotThrowAnyException();
        ImportItem waiting = itemRepository.findById(linkItem.getId()).orElseThrow();
        assertThat(waiting.getIssue()).contains("MATCH_REQUIRED");
        assertThat(jobRepository.findById(linkJob.getId()).orElseThrow().getState()).isEqualTo(ImportJobState.READY);
        assertThat(watches(owner.getId(), movie.getId())).hasSize(1);

        previewService.resolve(owner.getId(), linkJob.getId(),
                List.of(new ImportResolutionOperation.AssignPass(linkItem.getId(), 1)), linkJob.getPreviewVersion());
        normalizeAfterResolution(linkJob.getId(), false);
        ImportItem resolvedLink = itemRepository.findById(linkItem.getId()).orElseThrow();
        assertThat(resolvedLink.getIssue()).isNull();
        assertThat(objectMapper.readTree(resolvedLink.getPayloadJson())
                .path("resolution").path("linkExistingOnly").asBoolean()).isTrue();
        ImportJob retryLinkJob = requeueAfterResolution(linkJob.getId());
        assertThatCode(() -> apply(retryLinkJob, linkItem)).doesNotThrowAnyException();
        assertThat(watches(owner.getId(), movie.getId())).hasSize(1);
        assertThat(linkTarget(owner.getId(), "letterboxd:account-1", "letterboxd-event-1"))
                .isEqualTo(manual.diaryEntryId());
        completeJob(linkJob.getId());

        ImportJob skipJob = createJob(owner.getId(), "trakt:account-1", ImportSourceFormat.TRAKT_ZIP);
        ImportItem skipItem = createEvent(skipJob, movie, 0, "trakt-event-1", "2022-08-09", null, null);
        assertThatCode(() -> apply(skipJob, skipItem)).doesNotThrowAnyException();
        previewService.resolve(owner.getId(), skipJob.getId(),
                List.of(new ImportResolutionOperation.AssignPass(skipItem.getId(), 2)), skipJob.getPreviewVersion());
        normalizeAfterResolution(skipJob.getId(), true);
        ImportItem resolvedCopy = itemRepository.findById(skipItem.getId()).orElseThrow();
        assertThat(resolvedCopy.getIssue()).isNull();
        assertThat(objectMapper.readTree(resolvedCopy.getPayloadJson())
                .path("resolution").path("linkExistingOnly").asBoolean()).isTrue();
        ImportJob attemptedCopyJob = requeueAfterResolution(skipJob.getId());
        assertThatCode(() -> apply(attemptedCopyJob, skipItem)).doesNotThrowAnyException();
        assertThat(watches(owner.getId(), movie.getId())).hasSize(1);
        ImportJob stillWaitingJob = jobRepository.findById(skipJob.getId()).orElseThrow();
        assertThat(stillWaitingJob.getState()).isEqualTo(ImportJobState.READY);
        assertThat(itemRepository.findById(skipItem.getId()).orElseThrow().getIssue()).contains("MATCH_REQUIRED");

        previewService.resolve(owner.getId(), skipJob.getId(),
                List.of(new ImportResolutionOperation.SkipItem(skipItem.getId())), stillWaitingJob.getPreviewVersion());
        ImportJob retrySkipJob = requeueAfterResolution(skipJob.getId());
        assertThatCode(() -> apply(retrySkipJob, skipItem)).doesNotThrowAnyException();
        assertThat(itemRepository.findById(skipItem.getId()).orElseThrow().getOutcome()).isEqualTo("SKIPPED");
        assertThat(linkState(owner.getId(), "trakt:account-1", "trakt-event-1")).isEqualTo("SKIPPED");
        assertThat(watches(owner.getId(), movie.getId())).hasSize(1);
        completeJob(skipJob.getId());
    }

    @Test
    void shouldRequireLinkOrSkipForAnUndatedManualWatchMatch() {
        User owner = createUser();
        Content movie = createMovie();
        WatchMutationResult manual = diaryWatchCommandService.recordPrepared(owner.getId(),
                new WatchEventCommand(MediaReference.movie(movie.getTmdbId()), null, null,
                        "undated manual entry", null, null, List.of(), null));
        ImportJob job = createJob(owner.getId(), "trakt:account-1", ImportSourceFormat.TRAKT_ZIP);
        ImportItem event = createEvent(job, movie, 0, "undated-cross-source", null, null, null);

        assertThatCode(() -> apply(job, event)).doesNotThrowAnyException();

        assertThat(watches(owner.getId(), movie.getId())).hasSize(1)
                .extracting(WatchRow::id).containsExactly(manual.diaryEntryId());
        assertThat(itemRepository.findById(event.getId()).orElseThrow().getIssue()).contains("MATCH_REQUIRED");
        assertThat(jobRepository.findById(job.getId()).orElseThrow().getState()).isEqualTo(ImportJobState.READY);
    }

    @Test
    void shouldKeepDistinctUndatedEventsFromTheSameNamespaceAsSeparateWatches() {
        User owner = createUser();
        Content movie = createMovie();
        ImportJob firstJob = createJob(owner.getId(), "trakt:account-1", ImportSourceFormat.TRAKT_ZIP);
        ImportItem first = createEvent(firstJob, movie, 0, "undated-trakt-1", null, null, null);
        assertThatCode(() -> apply(firstJob, first)).doesNotThrowAnyException();
        completeJob(firstJob.getId());

        ImportJob secondJob = createJob(owner.getId(), "trakt:account-1", ImportSourceFormat.TRAKT_ZIP);
        ImportItem second = createEvent(secondJob, movie, 0, "undated-trakt-2", null, null, null);
        assertThatCode(() -> apply(secondJob, second)).doesNotThrowAnyException();

        assertThat(watches(owner.getId(), movie.getId())).extracting(WatchRow::watchNumber).containsExactly(1, 2);
        assertThat(watches(owner.getId(), movie.getId())).extracting(WatchRow::watchedDate).containsOnlyNulls();
        completeJob(secondJob.getId());
    }

    @Test
    void shouldLeaveDeletedImportedWatchesAsTombstonesAcrossReimport() {
        User owner = createUser();
        Content movie = createMovie();
        ImportJob firstJob = createJob(owner.getId(), "trakt:account-1", ImportSourceFormat.TRAKT_ZIP);
        ImportItem first = createEvent(firstJob, movie, 0, "deleted-event", "2020-02-03", null, null);
        assertThatCode(() -> apply(firstJob, first)).doesNotThrowAnyException();
        UUID diaryEntryId = watches(owner.getId(), movie.getId()).getFirst().id();
        completeJob(firstJob.getId());

        diaryEntryService.deleteDiaryEntry(owner.getId(), diaryEntryId);

        ImportJob replayJob = createJob(owner.getId(), "trakt:account-1", ImportSourceFormat.TRAKT_ZIP);
        ImportItem replay = createEvent(replayJob, movie, 0, "deleted-event", "2020-02-03", null, null);
        assertThatCode(() -> apply(replayJob, replay)).doesNotThrowAnyException();
        assertThat(watches(owner.getId(), movie.getId())).isEmpty();
        assertThat(itemRepository.findById(replay.getId()).orElseThrow().getOutcome()).isEqualTo("SKIPPED");
        assertThat(linkState(owner.getId(), "trakt:account-1", "deleted-event")).isEqualTo("TOMBSTONE");
    }

    @Test
    void shouldNotApplyAnActiveReservationOwnedByAnotherJob() {
        User owner = createUser();
        Content movie = createMovie();
        ImportSourceKey key = new ImportSourceKey("trakt:account-1", "diary_event", "reserved-event");
        ImportJob reservationOwner = createJob(owner.getId(), "trakt:account-1", ImportSourceFormat.TRAKT_ZIP);
        ledger.reserve(owner.getId(), reservationOwner.getId(), key, "reserved-hash");
        reservationOwner.setState(ImportJobState.FAILED);
        jobRepository.saveAndFlush(reservationOwner);
        ImportJob competingJob = createJob(owner.getId(), "trakt:account-1", ImportSourceFormat.TRAKT_ZIP);
        ImportItem competing = createEvent(competingJob, movie, 0, "reserved-event", "2024-01-01", null, null);

        assertThatThrownBy(() -> apply(competingJob, competing))
                .isInstanceOf(ImportUnitTransaction.WaitForOtherJobException.class);
        assertThat(watches(owner.getId(), movie.getId())).isEmpty();
    }

    @Test
    void shouldNotReassignAPlannedPassWhenAnotherWatchOccupiesItBeforeTheLockedWrite() {
        User owner = createUser();
        Content movie = createMovie();
        ImportJob job = createJob(owner.getId(), "trakt:account-1", ImportSourceFormat.TRAKT_ZIP);
        ImportItem event = createEvent(job, movie, 0, "pass-race-event", "2024-04-05", null, null);
        LocalDate racingDate = LocalDate.of(2020, 3, 4);
        TransactionTemplate concurrentWrite = new TransactionTemplate(transactionManager);
        concurrentWrite.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        doAnswer(invocation -> {
            UUID ownerId = invocation.getArgument(0);
            WatchEventCommand plannedCommand = invocation.getArgument(1);
            concurrentWrite.execute(status -> diaryWatchCommandService.recordPrepared(ownerId,
                    new WatchEventCommand(plannedCommand.content(), racingDate, 1,
                            "concurrent watch", null, null, List.of(), null)));
            return invocation.callRealMethod();
        }).when(diaryWatchCommandServiceSpy).recordPreparedImport(
                any(UUID.class), any(WatchEventCommand.class), eq(false));

        assertThatCode(() -> apply(job, event)).doesNotThrowAnyException();

        List<WatchRow> saved = watches(owner.getId(), movie.getId());
        assertThat(saved).hasSize(1);
        assertThat(saved.getFirst().watchNumber()).isEqualTo(1);
        assertThat(saved.getFirst().watchedDate()).isEqualTo(racingDate);
        ImportItem waiting = itemRepository.findById(event.getId()).orElseThrow();
        assertThat(waiting.getOutcome()).isEqualTo("MATCHED");
        assertThat(waiting.getIssue()).contains("PASS_ASSIGNMENT_CONFLICT");
        assertThat(jobRepository.findById(job.getId()).orElseThrow().getState()).isEqualTo(ImportJobState.READY);
        assertThat(linkState(owner.getId(), "trakt:account-1", "pass-race-event")).isEqualTo("IN_PROGRESS");
    }

    @Test
    void shouldRollbackDiaryWriteAndLedgerReservationWhenFailureFollowsTheWrite() {
        User owner = createUser();
        Content movie = createMovie();
        ImportJob job = createJob(owner.getId(), "trakt:account-1", ImportSourceFormat.TRAKT_ZIP);
        ImportItem event = createEvent(job, movie, 0, "crash-after-save", "2024-04-05", 8, "review");
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new SimulatedUnitFailure();
        }).when(diaryWatchCommandServiceSpy).recordPreparedImport(
                any(UUID.class), any(WatchEventCommand.class), anyBoolean());

        assertThatThrownBy(() -> apply(job, event)).isInstanceOf(SimulatedUnitFailure.class);

        verify(diaryWatchCommandServiceSpy).recordPreparedImport(
                any(UUID.class), any(WatchEventCommand.class), anyBoolean());
        assertThat(watches(owner.getId(), movie.getId())).isEmpty();
        assertThat(linkStateOrNull(owner.getId(), "trakt:account-1", "crash-after-save")).isNull();
        assertThat(itemRepository.findById(event.getId()).orElseThrow().getOutcome()).isEqualTo("MATCHED");
    }

    private User createUser() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        LocalDateTime now = LocalDateTime.now(clock);
        return userRepository.saveAndFlush(User.builder()
                .username("c4" + suffix)
                .name("Import C4 " + suffix)
                .email("c4" + suffix + "@example.com")
                .password(passwordEncoder.encode("Password123"))
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    private Content createMovie() {
        LocalDateTime now = LocalDateTime.now(clock);
        return contentRepository.saveAndFlush(Content.builder()
                .type(ContentType.MOVIE)
                .tmdbId(Long.toString(MOVIE_IDS.incrementAndGet()))
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    private ImportJob createJob(UUID ownerId, String namespace, ImportSourceFormat format) {
        LocalDateTime now = LocalDateTime.now(clock);
        ImportPlatform platform = format == ImportSourceFormat.LETTERBOXD_ZIP
                ? ImportPlatform.LETTERBOXD : ImportPlatform.TRAKT;
        UUID leaseToken = UUID.randomUUID();
        return jobRepository.saveAndFlush(ImportJob.builder()
                .userId(ownerId)
                .inputKind(ImportInputKind.PLATFORM_ARCHIVE)
                .sourceFormat(format)
                .platform(platform)
                .sourceAccountId("account-1")
                .sourceNamespace(namespace)
                .state(ImportJobState.IMPORTING)
                .previewVersion(1)
                .selectionJson("{\"schemaVersion\":1,\"planGeneration\":1,\"selection\":{}}")
                .timezone("UTC")
                .storageKey(UUID.randomUUID().toString())
                .uploadSizeBytes(1)
                .workPhase("APPLY")
                .workPending(true)
                .nextRunAt(now.minusSeconds(1))
                .leaseToken(leaseToken)
                .leaseUntil(now.plusMinutes(2))
                .createdAt(now)
                .updatedAt(now)
                .expiresAt(now.plusDays(1))
                .build());
    }

    private ImportItem createEvent(ImportJob job, Content movie, int ordinal, String sourceId,
                                   String watchedOn, Integer score, String review) {
        var payload = objectMapper.createObjectNode();
        payload.put("title", "C4 fixture " + movie.getTmdbId());
        if (watchedOn == null) payload.putNull("watchedOn");
        else {
            payload.put("watchedOn", watchedOn);
            payload.put("sourceWatchedAt", watchedOn + "T18:30:00Z");
        }
        if (score != null) payload.put("score", score);
        if (review != null) payload.put("review", review);
        return itemRepository.saveAndFlush(ImportItem.builder()
                .jobId(job.getId())
                .planGeneration(1)
                .normalizedKind(ImportRecordKind.DIARY_EVENT)
                .sourceNamespace(job.getSourceNamespace())
                .objectKind("diary_event")
                .sourceId(sourceId)
                .sourceEventKey(sourceId)
                .parserVersion(job.getSourceFormat() == ImportSourceFormat.LETTERBOXD_ZIP
                        ? "letterboxd-history-v1" : "trakt-history-v1")
                .resolvedContentKey("CONTENT|MOVIE|" + movie.getTmdbId())
                .resolvedTargetJson(writeJson(new ImportTarget.ContentTarget(MediaReference.movie(movie.getTmdbId()))))
                .payloadJson(writeJson(payload))
                .outcome("MATCHED")
                .ordinal(ordinal)
                .build());
    }

    private ImportItem createCurrentRating(ImportJob job, Content movie, int ordinal, String sourceId, int score) {
        var payload = objectMapper.createObjectNode();
        payload.put("title", "C4 fixture " + movie.getTmdbId());
        payload.put("score", score);
        return itemRepository.saveAndFlush(ImportItem.builder()
                .jobId(job.getId())
                .planGeneration(1)
                .normalizedKind(ImportRecordKind.CURRENT_RATING)
                .sourceNamespace(job.getSourceNamespace())
                .objectKind("current_rating")
                .sourceId(sourceId)
                .sourceEventKey(sourceId)
                .parserVersion("letterboxd-history-v1")
                .resolvedContentKey("CONTENT|MOVIE|" + movie.getTmdbId())
                .resolvedTargetJson(writeJson(new ImportTarget.ContentTarget(MediaReference.movie(movie.getTmdbId()))))
                .payloadJson(writeJson(payload))
                .outcome("MATCHED")
                .ordinal(ordinal)
                .build());
    }

    private ImportJob requeueAfterResolution(UUID jobId) {
        ImportJob job = jobRepository.findById(jobId).orElseThrow();
        LocalDateTime now = LocalDateTime.now(clock);
        job.setState(ImportJobState.IMPORTING);
        job.setWorkPhase("APPLY");
        job.setWorkPending(true);
        job.setNextRunAt(now);
        job.setLeaseToken(UUID.randomUUID());
        job.setLeaseUntil(now.plusMinutes(2));
        job.setUpdatedAt(now);
        return jobRepository.saveAndFlush(job);
    }

    private void normalizeAfterResolution(UUID jobId, boolean trakt) {
        ImportJob job = jobRepository.findById(jobId).orElseThrow();
        LocalDateTime now = LocalDateTime.now(clock);
        UUID leaseToken = UUID.randomUUID();
        job.setLeaseToken(leaseToken);
        job.setLeaseUntil(now.plusMinutes(2));
        job.setUpdatedAt(now);
        jobRepository.saveAndFlush(job);
        ImportExecutionContext context = new ImportExecutionContext(job.getUserId(), job.getId(),
                job.getPreviewVersion(), 1, job.getSourceNamespace(), leaseToken);
        if (trakt) traktHistoryNormalizer.normalize(context);
        else letterboxdHistoryNormalizer.normalize(context);
    }

    private void apply(ImportJob job, ImportItem item) {
        unitTransaction.apply(new ImportExecutionContext(job.getUserId(), job.getId(), job.getPreviewVersion(),
                1, job.getSourceNamespace(), job.getLeaseToken()), item.getId());
    }

    private void completeJob(UUID jobId) {
        ImportJob job = jobRepository.findById(jobId).orElseThrow();
        job.setState(ImportJobState.COMPLETED);
        job.setWorkPending(false);
        job.setNextRunAt(null);
        job.setLeaseToken(null);
        job.setLeaseUntil(null);
        jobRepository.saveAndFlush(job);
    }

    private List<WatchRow> watches(UUID ownerId, UUID contentId) {
        return jdbc.query("""
                SELECT id, watch_number, watched_date, score, comment
                FROM diary_entries WHERE user_id = ? AND content_id = ? ORDER BY watch_number
                """, (rs, row) -> new WatchRow(rs.getObject("id", UUID.class), rs.getInt("watch_number"),
                rs.getObject("watched_date", LocalDate.class), rs.getObject("score", Integer.class),
                rs.getString("comment")), ownerId, contentId);
    }

    private UUID linkTarget(UUID ownerId, String namespace, String sourceId) {
        return jdbc.queryForObject("""
                SELECT target_id FROM external_import_links
                WHERE user_id = ? AND source_namespace = ? AND object_kind = 'diary_event' AND source_id = ?
                """, UUID.class, ownerId, namespace, sourceId);
    }

    private String linkState(UUID ownerId, String namespace, String sourceId) {
        return jdbc.queryForObject("""
                SELECT state FROM external_import_links
                WHERE user_id = ? AND source_namespace = ? AND object_kind = 'diary_event' AND source_id = ?
                """, String.class, ownerId, namespace, sourceId);
    }

    private String linkStateOrNull(UUID ownerId, String namespace, String sourceId) {
        return jdbc.query("""
                SELECT state FROM external_import_links
                WHERE user_id = ? AND source_namespace = ? AND object_kind = 'diary_event' AND source_id = ?
                """, rs -> rs.next() ? rs.getString(1) : null, ownerId, namespace, sourceId);
    }

    private String writeJson(Object value) {
        return objectMapper.writeValueAsString(value);
    }

    private record WatchRow(UUID id, int watchNumber, LocalDate watchedDate, Integer score, String comment) { }

    private static final class SimulatedUnitFailure extends RuntimeException { }
}
