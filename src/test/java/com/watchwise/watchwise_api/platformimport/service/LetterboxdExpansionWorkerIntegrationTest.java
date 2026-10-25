package com.watchwise.watchwise_api.platformimport.service;

import com.watchwise.watchwise_api.calendar.service.CalendarEpisodeSchedule;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleKey;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleLookup;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleProvider;
import com.watchwise.watchwise_api.calendar.service.CalendarSeasonSchedule;
import com.watchwise.watchwise_api.calendar.service.CalendarSeriesSchedule;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupOrigin;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.dto.MediaReference;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.content.service.ContentService;
import com.watchwise.watchwise_api.contentrating.service.ContentRatingService;
import com.watchwise.watchwise_api.contentreview.service.HistoricalOpinion;
import com.watchwise.watchwise_api.contentreview.service.HistoricalOpinionRestoreService;
import com.watchwise.watchwise_api.contentreview.service.HistoricalViewingEvidence;
import com.watchwise.watchwise_api.contentreview.service.HistoricalViewingEvidenceVerifier;
import com.watchwise.watchwise_api.diaryentry.dto.WatchEventCommand;
import com.watchwise.watchwise_api.diaryentry.service.DiaryWatchCommandService;
import com.watchwise.watchwise_api.platformimport.entity.ImportItem;
import com.watchwise.watchwise_api.platformimport.entity.ImportJob;
import com.watchwise.watchwise_api.platformimport.model.ImportInputKind;
import com.watchwise.watchwise_api.platformimport.model.ImportJobState;
import com.watchwise.watchwise_api.platformimport.model.ImportPlatform;
import com.watchwise.watchwise_api.platformimport.model.ImportRecordKind;
import com.watchwise.watchwise_api.platformimport.model.ImportResolutionOperation;
import com.watchwise.watchwise_api.platformimport.model.ImportSourceFormat;
import com.watchwise.watchwise_api.platformimport.model.ImportTarget;
import com.watchwise.watchwise_api.platformimport.model.SeriesExpansionPlan;
import com.watchwise.watchwise_api.platformimport.repository.ImportItemRepository;
import com.watchwise.watchwise_api.platformimport.repository.ImportJobRepository;
import com.watchwise.watchwise_api.platformimport.service.impl.ImportPassPlanner;
import com.watchwise.watchwise_api.platformimport.service.impl.ImportWorker;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.docker.compose.enabled=false",
        "app.import.worker.enabled=false",
        "app.import.cleanup.enabled=false"
})
@Testcontainers
class LetterboxdExpansionWorkerIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private ImportWorker worker;
    @Autowired private ImportJobRepository jobs;
    @Autowired private ImportItemRepository items;
    @Autowired private UserRepository users;
    @Autowired private ContentRepository contents;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private DiaryWatchCommandService diaryWatchCommandService;
    @Autowired private ImportPreviewService previewService;
    @MockitoBean private CalendarScheduleProvider calendar;
    @MockitoBean private ContentService contentService;
    @MockitoBean private ImportPassPlanner passPlanner;
    @MockitoBean private HistoricalOpinionRestoreService opinionRestoreService;
    @MockitoBean private HistoricalViewingEvidenceVerifier evidenceVerifier;
    @MockitoBean private ContentRatingService contentRatingService;

    @BeforeEach
    void stubHistoricalEvidenceAsCompleteByDefault() {
        org.mockito.Mockito.lenient().doAnswer(invocation -> {
            HistoricalViewingEvidence evidence = invocation.getArgument(2);
            return new HistoricalViewingEvidenceVerifier.Verification(1, 1,
                    Instant.now().plusSeconds(3600), true, evidence.eventIds());
        }).when(evidenceVerifier).verify(any(), any(), any(), eq(false), any());
    }

    @Test
    void workerStagesChildrenThenFinalizesParentAndACompletedUriDoesNotExpandAfterSeasonTwo() throws Exception {
        User owner = createUser();
        Content series = createContent(ContentType.SERIES, "95001", null, null, null);
        Content episodeOne = createContent(ContentType.EPISODE, null, "95001", 1, 1);
        Content episodeTwo = createContent(ContentType.EPISODE, null, "95001", 1, 2);
        ImportJob firstJob = createJob(owner.getId());
        String parentUri = "https://letterboxd.com/film/fixture/diary/123/";
        ImportItem parent = createParent(firstJob, series, parentUri,
                "Parent series note", 8, 9);

        when(calendar.loadSeries("95001", "US", "en-US"))
                .thenReturn(new CalendarScheduleLookup.FoundSeries(schedule(List.of(
                        season(1, List.of(airing(1, LocalDate.of(2020, 1, 1)), airing(2, LocalDate.of(2020, 1, 1))))))));
        when(contentService.getOrCreateReference(any())).thenAnswer(invocation -> {
            var ref = (com.watchwise.watchwise_api.content.dto.ContentRefCreationDTO) invocation.getArgument(0);
            Content content = ref.episodeNumber() == 1 ? episodeOne : episodeTwo;
            return new ContentRefDTO(content.getId(), null, ContentType.EPISODE, "95001", ref.seasonNumber(),
                    ref.episodeNumber(), false, false, null, null);
        });
        when(passPlanner.plan(eq(owner.getId()), any())).thenAnswer(invocation -> {
            List<ImportPassPlanner.WatchEvent> events = invocation.getArgument(1);
            return new ImportPassPlanner.Plan(events.stream().collect(java.util.stream.Collectors.toMap(
                    ImportPassPlanner.WatchEvent::itemId, ignored -> 1)), Map.of(), Set.of());
        });

        worker.runAvailable(1);

        ImportItem stagedParent = items.findById(parent.getId()).orElseThrow();
        assertThat(stagedParent.getOutcome()).isEqualTo("EXPANDING");
        assertThat(jobs.findById(firstJob.getId()).orElseThrow().getState()).isEqualTo(ImportJobState.IMPORTING);
        assertThat(jdbc.queryForObject("SELECT state FROM external_import_links WHERE user_id=? AND source_namespace=? AND source_id=?",
                String.class, owner.getId(), firstJob.getSourceNamespace(), parentUri)).isEqualTo("IN_PROGRESS");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM diary_entries WHERE user_id=?", Long.class,
                owner.getId())).isZero();
        assertThat(items.findAllByJobIdAndPlanGenerationAndParentSourceIdAndNormalizedKind(
                firstJob.getId(), 1, parentUri, ImportRecordKind.DIARY_EVENT)).hasSize(2);

        CalendarSeriesSchedule expandedSchedule = schedule(List.of(
                season(1, List.of(airing(1, LocalDate.of(2020, 1, 1)), airing(2, LocalDate.of(2020, 1, 1)))),
                season(2, List.of(airing(1, LocalDate.of(2026, 9, 1))))));
        when(calendar.loadSeries("95001", "US", "en-US"))
                .thenReturn(new CalendarScheduleLookup.FoundSeries(expandedSchedule));

        worker.runAvailable(1);

        assertThat(jobs.findById(firstJob.getId()).orElseThrow().getState()).isEqualTo(ImportJobState.COMPLETED);
        assertThat(items.findById(parent.getId()).orElseThrow().getOutcome()).isEqualTo("APPLIED");
        assertThat(jdbc.queryForObject("SELECT state FROM external_import_links WHERE user_id=? AND source_namespace=? AND source_id=?",
                String.class, owner.getId(), firstJob.getSourceNamespace(), parentUri)).isEqualTo("APPLIED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM diary_entries WHERE user_id=?", Long.class, owner.getId()))
                .isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM diary_entries WHERE user_id=? AND (comment IS NOT NULL OR score IS NOT NULL)",
                Long.class, owner.getId())).isZero();
        verify(calendar, times(1)).loadSeries("95001", "US", "en-US");
        ArgumentCaptor<HistoricalOpinion> restoredOpinion = ArgumentCaptor.forClass(HistoricalOpinion.class);
        ArgumentCaptor<HistoricalViewingEvidence> evidence = ArgumentCaptor.forClass(HistoricalViewingEvidence.class);
        verify(opinionRestoreService, times(1)).restore(eq(owner.getId()), restoredOpinion.capture(), evidence.capture());
        assertThat(restoredOpinion.getValue().content()).isEqualTo(MediaReference.series("95001"));
        assertThat(restoredOpinion.getValue().comment()).isEqualTo("Parent series note");
        assertThat(restoredOpinion.getValue().scoreAtPublication()).isEqualTo(8);
        assertThat(restoredOpinion.getValue().watchNumber()).isEqualTo(1);
        assertThat(evidence.getValue().eventIds()).containsExactlyInAnyOrderElementsOf(jdbc.query(
                "SELECT id FROM diary_entries WHERE user_id=?", (rs, row) -> rs.getObject("id", UUID.class),
                owner.getId()));
        assertThat(evidence.getValue().scopeHash()).isNotBlank();
        verify(contentRatingService, times(1)).restoreInitial(eq(owner.getId()), eq(MediaReference.series("95001")),
                eq(9), eq(evidence.getValue()));

        ImportJob replayJob = createJob(owner.getId());
        ImportItem replayParent = createParent(replayJob, series, parentUri, "changed parent", 10, 10);
        worker.runAvailable(1);
        assertThat(jobs.findById(replayJob.getId()).orElseThrow().getState()).isEqualTo(ImportJobState.COMPLETED);
        assertThat(items.findById(replayParent.getId()).orElseThrow().getOutcome()).isEqualTo("SKIPPED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM diary_entries WHERE user_id=?", Long.class, owner.getId()))
                .isEqualTo(2L);
        verify(calendar, times(1)).loadSeries("95001", "US", "en-US");
        verify(opinionRestoreService, times(1)).restore(eq(owner.getId()), any(HistoricalOpinion.class),
                any(HistoricalViewingEvidence.class));
    }

    @Test
    void manualEpisodeMatchCanBeAssignedFromPreviewAndLinksWithoutCreatingADuplicate() throws Exception {
        User owner = createUser();
        Content series = createContent(ContentType.SERIES, "95002", null, null, null);
        Content episodeOne = createContent(ContentType.EPISODE, null, "95002", 1, 1);
        Content episodeTwo = createContent(ContentType.EPISODE, null, "95002", 1, 2);
        var manual = diaryWatchCommandService.recordPrepared(owner.getId(), new WatchEventCommand(
                MediaReference.episode("95002", 1, 1), LocalDate.of(2020, 1, 1), 1,
                null, null, null, List.of(), null));
        ImportJob job = createJob(owner.getId());
        String parentUri = "https://letterboxd.com/film/matched/diary/234/";
        ImportItem parent = createParent(job, series, parentUri, "Series note", 7, 8);
        when(calendar.loadSeries("95002", "US", "en-US")).thenReturn(new CalendarScheduleLookup.FoundSeries(
                schedule("95002", List.of(season("95002", 1, List.of(
                        airing(1, LocalDate.of(2020, 1, 1)), airing(2, LocalDate.of(2020, 1, 1))))))));
        when(contentService.getOrCreateReference(any())).thenAnswer(invocation -> {
            var ref = (com.watchwise.watchwise_api.content.dto.ContentRefCreationDTO) invocation.getArgument(0);
            Content content = ref.episodeNumber() == 1 ? episodeOne : episodeTwo;
            return new ContentRefDTO(content.getId(), null, ContentType.EPISODE, "95002", ref.seasonNumber(),
                    ref.episodeNumber(), false, false, null, null);
        });
        when(passPlanner.plan(eq(owner.getId()), any())).thenAnswer(invocation -> {
            List<ImportPassPlanner.WatchEvent> events = invocation.getArgument(1);
            if (events.size() == 2) {
                ImportPassPlanner.WatchEvent matching = events.stream()
                        .filter(event -> event.contentId().equals(episodeOne.getId())).findFirst().orElseThrow();
                ImportPassPlanner.WatchEvent newEpisode = events.stream()
                        .filter(event -> event.contentId().equals(episodeTwo.getId())).findFirst().orElseThrow();
                return new ImportPassPlanner.Plan(Map.of(newEpisode.itemId(), 1),
                        Map.of(matching.itemId(), "MATCH_REQUIRED: PASS_ASSIGNMENT_CONFLICT"), Set.of(),
                        Map.of(matching.itemId(), List.of(1)));
            }
            ImportPassPlanner.WatchEvent event = events.getFirst();
            if (event.contentId().equals(episodeOne.getId()) && event.assignedPass() == null) {
                return new ImportPassPlanner.Plan(Map.of(),
                        Map.of(event.itemId(), "MATCH_REQUIRED: PASS_ASSIGNMENT_CONFLICT"), Set.of(),
                        Map.of(event.itemId(), List.of(1)));
            }
            int pass = event.assignedPass() == null ? 1 : event.assignedPass();
            Set<UUID> linked = event.contentId().equals(episodeOne.getId()) && pass == 1
                    ? Set.of(event.itemId()) : Set.of();
            return new ImportPassPlanner.Plan(Map.of(event.itemId(), pass), Map.of(), linked);
        });

        worker.runAvailable(1);
        List<ImportItem> stagedChildren = items.findAllByJobIdAndPlanGenerationAndParentSourceIdAndNormalizedKind(
                job.getId(), 1, parentUri, ImportRecordKind.DIARY_EVENT);
        ImportItem matchingChild = stagedChildren.stream()
                .filter(item -> item.getResolvedContentKey().endsWith("|1|1")).findFirst().orElseThrow();
        assertThat(matchingChild.getDecision()).isNull();

        worker.runAvailable(1);
        ImportJob waiting = jobs.findById(job.getId()).orElseThrow();
        assertThat(waiting.getState()).isEqualTo(ImportJobState.READY);
        assertThat(items.findById(matchingChild.getId()).orElseThrow().getIssue()).contains("MATCH_REQUIRED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM diary_entries WHERE user_id=?", Long.class,
                owner.getId())).isEqualTo(1L);

        previewService.resolve(owner.getId(), job.getId(),
                List.of(new ImportResolutionOperation.AssignPass(matchingChild.getId(), 1)),
                waiting.getPreviewVersion());
        worker.runAvailable(1);
        requeueForApply(job.getId());

        worker.runAvailable(1);

        assertThat(jobs.findById(job.getId()).orElseThrow().getState()).isEqualTo(ImportJobState.COMPLETED);
        assertThat(items.findById(parent.getId()).orElseThrow().getOutcome()).isEqualTo("APPLIED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM diary_entries WHERE user_id=?", Long.class,
                owner.getId())).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT id FROM diary_entries WHERE user_id=? AND content_id=?",
                UUID.class, owner.getId(), episodeOne.getId())).isEqualTo(manual.diaryEntryId());
        assertThat(jdbc.queryForObject("SELECT state FROM external_import_links WHERE user_id=? AND source_namespace=? AND source_id=?",
                String.class, owner.getId(), job.getSourceNamespace(), matchingChild.getSourceId())).isEqualTo("APPLIED");
        verify(opinionRestoreService, times(1)).restore(eq(owner.getId()), any(HistoricalOpinion.class),
                any(HistoricalViewingEvidence.class));
    }

    @Test
    void skippingAnExpandedChildKeepsParentPendingUntilParentIsExplicitlySkipped() throws Exception {
        User owner = createUser();
        Content series = createContent(ContentType.SERIES, "95003", null, null, null);
        Content episode = createContent(ContentType.EPISODE, null, "95003", 1, 1);
        ImportJob job = createJob(owner.getId());
        String parentUri = "https://letterboxd.com/film/skipped/diary/345/";
        ImportItem parent = createParent(job, series, parentUri, "Do not restore", 7, 8);
        when(calendar.loadSeries("95003", "US", "en-US")).thenReturn(new CalendarScheduleLookup.FoundSeries(
                schedule("95003", List.of(season("95003", 1,
                        List.of(airing(1, LocalDate.of(2020, 1, 1))))))));
        when(contentService.getOrCreateReference(any())).thenReturn(new ContentRefDTO(episode.getId(), null,
                ContentType.EPISODE, "95003", 1, 1, false, false, null, null));
        when(passPlanner.plan(eq(owner.getId()), any())).thenAnswer(invocation -> {
            List<ImportPassPlanner.WatchEvent> events = invocation.getArgument(1);
            return new ImportPassPlanner.Plan(events.stream().collect(java.util.stream.Collectors.toMap(
                    ImportPassPlanner.WatchEvent::itemId, ignored -> 1)), Map.of(), Set.of());
        });

        worker.runAvailable(1);
        ImportItem child = items.findAllByJobIdAndPlanGenerationAndParentSourceIdAndNormalizedKind(
                job.getId(), 1, parentUri, ImportRecordKind.DIARY_EVENT).getFirst();
        child.setDecision(com.watchwise.watchwise_api.platformimport.model.ImportDecision.SKIP_ITEM);
        items.saveAndFlush(child);

        worker.runAvailable(1);

        ImportJob awaitingParentDecision = jobs.findById(job.getId()).orElseThrow();
        assertThat(awaitingParentDecision.getState()).isEqualTo(ImportJobState.READY);
        assertThat(awaitingParentDecision.isWorkPending()).isFalse();
        assertThat(items.findById(parent.getId()).orElseThrow().getOutcome()).isEqualTo("NORMALIZATION_ISSUE");
        assertThat(items.findById(parent.getId()).orElseThrow().getIssue()).contains("SERIES_CHILD_SKIPPED")
                .contains("explicitly skip the parent");
        assertThat(jdbc.queryForObject("SELECT state FROM external_import_links WHERE user_id=? AND source_namespace=? AND source_id=?",
                String.class, owner.getId(), job.getSourceNamespace(), parentUri)).isEqualTo("IN_PROGRESS");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM diary_entries WHERE user_id=?", Long.class,
                owner.getId())).isZero();
        verify(opinionRestoreService, org.mockito.Mockito.never()).restore(eq(owner.getId()),
                any(HistoricalOpinion.class), any(HistoricalViewingEvidence.class));

        previewService.resolve(owner.getId(), job.getId(),
                List.of(new ImportResolutionOperation.SkipItem(parent.getId())), awaitingParentDecision.getPreviewVersion());
        worker.runAvailable(1);
        requeueForApply(job.getId());
        worker.runAvailable(1);

        assertThat(jobs.findById(job.getId()).orElseThrow().getState()).isEqualTo(ImportJobState.COMPLETED);
        assertThat(items.findById(parent.getId()).orElseThrow().getOutcome()).isEqualTo("SKIPPED");
        assertThat(jdbc.queryForObject("SELECT state FROM external_import_links WHERE user_id=? AND source_namespace=? AND source_id=?",
                String.class, owner.getId(), job.getSourceNamespace(), parentUri)).isEqualTo("SKIPPED");
    }

    @Test
    void differentExistingEpisodePassesCanBeLinkedAndRequireExplicitParentSkip() throws Exception {
        User owner = createUser();
        Content series = createContent(ContentType.SERIES, "95004", null, null, null);
        Content episodeOne = createContent(ContentType.EPISODE, null, "95004", 1, 1);
        Content episodeTwo = createContent(ContentType.EPISODE, null, "95004", 1, 2);
        var firstPass = diaryWatchCommandService.recordPrepared(owner.getId(), new WatchEventCommand(
                MediaReference.episode("95004", 1, 1), LocalDate.of(2020, 1, 1), 1,
                null, null, null, List.of(), null));
        var secondPass = diaryWatchCommandService.recordPrepared(owner.getId(), new WatchEventCommand(
                MediaReference.episode("95004", 1, 1), LocalDate.of(2021, 1, 1), 2,
                null, null, null, List.of(), null));
        var otherEpisodePass = diaryWatchCommandService.recordPrepared(owner.getId(), new WatchEventCommand(
                MediaReference.episode("95004", 1, 2), LocalDate.of(2020, 1, 1), 1,
                null, null, null, List.of(), null));
        ImportJob job = createJob(owner.getId());
        String parentUri = "https://letterboxd.com/film/mixed/diary/456/";
        ImportItem parent = createParent(job, series, parentUri, "Do not lose this review", 7, 8);
        when(calendar.loadSeries("95004", "US", "en-US")).thenReturn(new CalendarScheduleLookup.FoundSeries(
                schedule("95004", List.of(season("95004", 1, List.of(
                        airing(1, LocalDate.of(2020, 1, 1)), airing(2, LocalDate.of(2020, 1, 1))))))));
        when(contentService.getOrCreateReference(any())).thenAnswer(invocation -> {
            var ref = (com.watchwise.watchwise_api.content.dto.ContentRefCreationDTO) invocation.getArgument(0);
            Content content = ref.episodeNumber() == 1 ? episodeOne : episodeTwo;
            return new ContentRefDTO(content.getId(), null, ContentType.EPISODE, "95004", ref.seasonNumber(),
                    ref.episodeNumber(), false, false, null, null);
        });
        when(passPlanner.plan(eq(owner.getId()), any())).thenAnswer(invocation -> {
            List<ImportPassPlanner.WatchEvent> events = invocation.getArgument(1);
            if (events.size() == 2) {
                Map<UUID, String> issues = events.stream().collect(java.util.stream.Collectors.toMap(
                        ImportPassPlanner.WatchEvent::itemId,
                        ignored -> "MATCH_REQUIRED: PASS_ASSIGNMENT_CONFLICT"));
                Map<UUID, List<Integer>> matchingPasses = new java.util.HashMap<>();
                events.forEach(event -> matchingPasses.put(event.itemId(),
                        event.contentId().equals(episodeOne.getId()) ? List.of(1, 2) : List.of(1)));
                return new ImportPassPlanner.Plan(Map.of(), issues, Set.of(), matchingPasses);
            }
            ImportPassPlanner.WatchEvent event = events.getFirst();
            if (event.assignedPass() == null) {
                return new ImportPassPlanner.Plan(Map.of(), Map.of(event.itemId(),
                        "MATCH_REQUIRED: PASS_ASSIGNMENT_CONFLICT"), Set.of(),
                        Map.of(event.itemId(), event.contentId().equals(episodeOne.getId())
                                ? List.of(1, 2) : List.of(1)));
            }
            return new ImportPassPlanner.Plan(Map.of(event.itemId(), event.assignedPass()), Map.of(),
                    Set.of(event.itemId()));
        });

        worker.runAvailable(1);
        List<ImportItem> staged = items.findAllByJobIdAndPlanGenerationAndParentSourceIdAndNormalizedKind(
                job.getId(), 1, parentUri, ImportRecordKind.DIARY_EVENT);
        ImportItem episodeOneChild = staged.stream().filter(item -> item.getResolvedContentKey().endsWith("|1|1"))
                .findFirst().orElseThrow();
        worker.runAvailable(1);

        ImportJob firstChildConflict = jobs.findById(job.getId()).orElseThrow();
        assertThat(firstChildConflict.getState()).isEqualTo(ImportJobState.READY);
        previewService.resolve(owner.getId(), job.getId(),
                List.of(new ImportResolutionOperation.AssignPass(episodeOneChild.getId(), 2)),
                firstChildConflict.getPreviewVersion());
        worker.runAvailable(1);
        requeueForApply(job.getId());
        worker.runAvailable(1);

        ImportItem episodeTwoChild = staged.stream().filter(item -> item.getResolvedContentKey().endsWith("|1|2"))
                .findFirst().orElseThrow();
        ImportJob secondChildConflict = jobs.findById(job.getId()).orElseThrow();
        assertThat(secondChildConflict.getState()).isEqualTo(ImportJobState.READY);
        previewService.resolve(owner.getId(), job.getId(),
                List.of(new ImportResolutionOperation.AssignPass(episodeTwoChild.getId(), 1)),
                secondChildConflict.getPreviewVersion());
        worker.runAvailable(1);
        requeueForApply(job.getId());
        worker.runAvailable(1);

        ImportJob unresolved = jobs.findById(job.getId()).orElseThrow();
        ImportItem unresolvedParent = items.findById(parent.getId()).orElseThrow();
        assertThat(unresolved.getState()).isEqualTo(ImportJobState.READY);
        assertThat(unresolved.isWorkPending()).isFalse();
        assertThat(unresolvedParent.getOutcome()).isEqualTo("NORMALIZATION_ISSUE");
        assertThat(unresolvedParent.getIssue()).contains("SERIES_PASS_CONFLICT")
                .contains("explicitly skip the parent item");
        assertThat(jdbc.queryForObject("SELECT state FROM external_import_links WHERE user_id=? AND source_namespace=? AND source_id=?",
                String.class, owner.getId(), job.getSourceNamespace(), parentUri)).isEqualTo("IN_PROGRESS");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM diary_entries WHERE user_id=?", Long.class,
                owner.getId())).isEqualTo(3L);
        assertThat(jdbc.queryForObject("SELECT id FROM diary_entries WHERE user_id=? AND content_id=? AND watch_number=2",
                UUID.class, owner.getId(), episodeOne.getId())).isEqualTo(secondPass.diaryEntryId());
        assertThat(jdbc.queryForObject("SELECT id FROM diary_entries WHERE user_id=? AND content_id=? AND watch_number=1",
                UUID.class, owner.getId(), episodeTwo.getId())).isEqualTo(otherEpisodePass.diaryEntryId());
        verify(opinionRestoreService, org.mockito.Mockito.never()).restore(eq(owner.getId()),
                any(HistoricalOpinion.class), any(HistoricalViewingEvidence.class));
        verify(contentRatingService, org.mockito.Mockito.never()).restoreInitial(eq(owner.getId()),
                any(MediaReference.class), any(), any(HistoricalViewingEvidence.class));

        previewService.resolve(owner.getId(), job.getId(),
                List.of(new ImportResolutionOperation.SkipItem(parent.getId())), unresolved.getPreviewVersion());
        worker.runAvailable(1);
        requeueForApply(job.getId());
        worker.runAvailable(1);

        assertThat(jobs.findById(job.getId()).orElseThrow().getState()).isEqualTo(ImportJobState.COMPLETED);
        assertThat(items.findById(parent.getId()).orElseThrow().getOutcome()).isEqualTo("SKIPPED");
        assertThat(jdbc.queryForObject("SELECT state FROM external_import_links WHERE user_id=? AND source_namespace=? AND source_id=?",
                String.class, owner.getId(), job.getSourceNamespace(), parentUri)).isEqualTo("SKIPPED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM diary_entries WHERE user_id=?", Long.class,
                owner.getId())).isEqualTo(3L);
    }

    @Test
    void mixedChildPassesDoNotRequireParentSkipWhenThereIsNoOpinion() throws Exception {
        User owner = createUser();
        Content series = createContent(ContentType.SERIES, "95006", null, null, null);
        Content episodeOne = createContent(ContentType.EPISODE, null, "95006", 1, 1);
        Content episodeTwo = createContent(ContentType.EPISODE, null, "95006", 1, 2);
        var episodeOnePass = diaryWatchCommandService.recordPrepared(owner.getId(), new WatchEventCommand(
                MediaReference.episode("95006", 1, 1), LocalDate.of(2020, 1, 1), 1,
                null, null, null, List.of(), null));
        var episodeTwoPass = diaryWatchCommandService.recordPrepared(owner.getId(), new WatchEventCommand(
                MediaReference.episode("95006", 1, 2), LocalDate.of(2020, 1, 1), 2,
                null, null, null, List.of(), null));
        ImportJob job = createJob(owner.getId());
        String parentUri = "https://letterboxd.com/film/no-opinion/diary/678/";
        ImportItem parent = createParent(job, series, parentUri, null, null, null);
        when(calendar.loadSeries("95006", "US", "en-US")).thenReturn(new CalendarScheduleLookup.FoundSeries(
                schedule("95006", List.of(season("95006", 1, List.of(
                        airing(1, LocalDate.of(2020, 1, 1)), airing(2, LocalDate.of(2020, 1, 1))))))));
        when(contentService.getOrCreateReference(any())).thenAnswer(invocation -> {
            var ref = (com.watchwise.watchwise_api.content.dto.ContentRefCreationDTO) invocation.getArgument(0);
            Content content = ref.episodeNumber() == 1 ? episodeOne : episodeTwo;
            return new ContentRefDTO(content.getId(), null, ContentType.EPISODE, "95006", ref.seasonNumber(),
                    ref.episodeNumber(), false, false, null, null);
        });
        when(passPlanner.plan(eq(owner.getId()), any())).thenAnswer(invocation -> {
            List<ImportPassPlanner.WatchEvent> events = invocation.getArgument(1);
            if (events.size() == 2) {
                Map<UUID, String> issues = events.stream().collect(java.util.stream.Collectors.toMap(
                        ImportPassPlanner.WatchEvent::itemId,
                        ignored -> "MATCH_REQUIRED: PASS_ASSIGNMENT_CONFLICT"));
                return new ImportPassPlanner.Plan(Map.of(), issues, Set.of(),
                        Map.of(events.get(0).itemId(), List.of(1), events.get(1).itemId(), List.of(2)));
            }
            ImportPassPlanner.WatchEvent event = events.getFirst();
            if (event.assignedPass() == null) {
                return new ImportPassPlanner.Plan(Map.of(), Map.of(event.itemId(),
                        "MATCH_REQUIRED: PASS_ASSIGNMENT_CONFLICT"), Set.of(),
                        Map.of(event.itemId(), event.contentId().equals(episodeOne.getId()) ? List.of(1) : List.of(2)));
            }
            return new ImportPassPlanner.Plan(Map.of(event.itemId(), event.assignedPass()), Map.of(),
                    Set.of(event.itemId()));
        });

        worker.runAvailable(1);
        List<ImportItem> staged = items.findAllByJobIdAndPlanGenerationAndParentSourceIdAndNormalizedKind(
                job.getId(), 1, parentUri, ImportRecordKind.DIARY_EVENT);
        ImportItem first = staged.stream().filter(item -> item.getResolvedContentKey().endsWith("|1|1"))
                .findFirst().orElseThrow();
        ImportItem second = staged.stream().filter(item -> item.getResolvedContentKey().endsWith("|1|2"))
                .findFirst().orElseThrow();

        worker.runAvailable(1);
        ImportJob firstConflict = jobs.findById(job.getId()).orElseThrow();
        previewService.resolve(owner.getId(), job.getId(),
                List.of(new ImportResolutionOperation.AssignPass(first.getId(), 1)), firstConflict.getPreviewVersion());
        worker.runAvailable(1);
        requeueForApply(job.getId());
        worker.runAvailable(1);

        ImportJob secondConflict = jobs.findById(job.getId()).orElseThrow();
        previewService.resolve(owner.getId(), job.getId(),
                List.of(new ImportResolutionOperation.AssignPass(second.getId(), 2)), secondConflict.getPreviewVersion());
        worker.runAvailable(1);
        requeueForApply(job.getId());
        worker.runAvailable(1);

        assertThat(jobs.findById(job.getId()).orElseThrow().getState()).isEqualTo(ImportJobState.COMPLETED);
        assertThat(items.findById(parent.getId()).orElseThrow().getOutcome()).isEqualTo("APPLIED");
        assertThat(jdbc.queryForObject("SELECT state FROM external_import_links WHERE user_id=? AND source_namespace=? AND source_id=?",
                String.class, owner.getId(), job.getSourceNamespace(), parentUri)).isEqualTo("APPLIED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM diary_entries WHERE user_id=?", Long.class,
                owner.getId())).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT id FROM diary_entries WHERE user_id=? AND content_id=?",
                UUID.class, owner.getId(), episodeOne.getId())).isEqualTo(episodeOnePass.diaryEntryId());
        assertThat(jdbc.queryForObject("SELECT id FROM diary_entries WHERE user_id=? AND content_id=?",
                UUID.class, owner.getId(), episodeTwo.getId())).isEqualTo(episodeTwoPass.diaryEntryId());
        verify(opinionRestoreService, org.mockito.Mockito.never()).restore(eq(owner.getId()),
                any(HistoricalOpinion.class), any(HistoricalViewingEvidence.class));
        verify(contentRatingService, org.mockito.Mockito.never()).restoreInitial(eq(owner.getId()),
                any(MediaReference.class), any(), any(HistoricalViewingEvidence.class));
    }

    @Test
    void seriesEpisodesAndTheirAvailableDatesAreStagedBeforeTheUserConfirmsThePreview() throws Exception {
        User owner = createUser();
        Content series = createContent(ContentType.SERIES, "95007", null, null, null);
        Content episodeOne = createContent(ContentType.EPISODE, null, "95007", 1, 1);
        Content episodeTwo = createContent(ContentType.EPISODE, null, "95007", 1, 2);
        var existingEpisodeTwo = diaryWatchCommandService.recordPrepared(owner.getId(), new WatchEventCommand(
                MediaReference.episode("95007", 1, 2), LocalDate.of(2023, 1, 1), 1,
                null, null, null, List.of(), null));
        ImportJob job = createJob(owner.getId());
        job.setState(ImportJobState.RESOLVING);
        job.setWorkPhase("RESOLVE");
        job.setSelectionJson("{\"schemaVersion\":1,\"planGeneration\":1,\"selection\":{\"history\":true,\"watchlist\":false,\"listIds\":[]}}");
        jobs.saveAndFlush(job);
        String parentUri = "https://letterboxd.com/film/preview-series/diary/789/";
        ImportItem parent = createParent(job, series, parentUri, "Series review", 8, 8);
        when(calendar.loadSeries("95007", "US", "en-US")).thenReturn(new CalendarScheduleLookup.FoundSeries(
                schedule("95007", List.of(season("95007", 1, List.of(
                        airing(1, LocalDate.of(2019, 1, 1)),
                        airing(2, LocalDate.of(2023, 1, 1)),
                        airing(3, LocalDate.of(2027, 1, 1))))))));
        when(contentService.getOrCreateReference(any())).thenAnswer(invocation -> {
            var ref = (com.watchwise.watchwise_api.content.dto.ContentRefCreationDTO) invocation.getArgument(0);
            Content content = ref.episodeNumber() == 1 ? episodeOne : episodeTwo;
            return new ContentRefDTO(content.getId(), null, ContentType.EPISODE, "95007", ref.seasonNumber(),
                    ref.episodeNumber(), false, false, null, null);
        });
        when(passPlanner.plan(eq(owner.getId()), any())).thenAnswer(invocation -> {
            List<ImportPassPlanner.WatchEvent> events = invocation.getArgument(1);
            if (events.size() == 2) {
                ImportPassPlanner.WatchEvent releasedBeforeWatchDate = events.stream()
                        .filter(event -> event.contentId().equals(episodeOne.getId())).findFirst().orElseThrow();
                ImportPassPlanner.WatchEvent releasedAfterWatchDate = events.stream()
                        .filter(event -> event.contentId().equals(episodeTwo.getId())).findFirst().orElseThrow();
                return new ImportPassPlanner.Plan(Map.of(releasedBeforeWatchDate.itemId(), 1),
                        Map.of(releasedAfterWatchDate.itemId(), "MATCH_REQUIRED: Existing watch pass 1"), Set.of(),
                        Map.of(releasedAfterWatchDate.itemId(), List.of(1)));
            }
            ImportPassPlanner.WatchEvent event = events.getFirst();
            return new ImportPassPlanner.Plan(Map.of(event.itemId(), event.assignedPass()), Map.of(),
                    event.contentId().equals(episodeTwo.getId()) ? Set.of(event.itemId()) : Set.of());
        });

        worker.runAvailable(1);

        ImportJob previewable = jobs.findById(job.getId()).orElseThrow();
        assertThat(previewable.getState()).isEqualTo(ImportJobState.RESOLVING);
        assertThat(previewable.isWorkPending()).isFalse();
        var preview = previewService.preview(owner.getId(), job.getId(), 0, 20);
        List<ImportItem> stagedChildren = items.findAllByJobIdAndPlanGenerationAndParentSourceIdAndNormalizedKind(
                job.getId(), 1, parentUri, ImportRecordKind.DIARY_EVENT);
        assertThat(stagedChildren).hasSize(2);
        assertThat(preview.getContent()).filteredOn(item -> item.kind() == ImportRecordKind.DIARY_EVENT)
                .hasSize(2);
        assertThat(stagedChildren).anySatisfy(child -> {
            assertThat(child.getResolvedContentKey()).endsWith("|1|1");
            assertThat(objectMapper.readTree(child.getPayloadJson()).path("source").path("watchedDate")
                    .asString()).isEqualTo("2020-01-01");
        });
        assertThat(stagedChildren).anySatisfy(child -> {
            assertThat(child.getResolvedContentKey()).endsWith("|1|2");
            assertThat(objectMapper.readTree(child.getPayloadJson()).path("source").has("watchedDate")).isFalse();
        });
        var childOnePreview = preview.getContent().stream()
                .filter(item -> item.itemId().equals(stagedChildren.stream()
                        .filter(child -> child.getResolvedContentKey().endsWith("|1|1"))
                        .findFirst().orElseThrow().getId()))
                .findFirst().orElseThrow();
        var childTwoPreview = preview.getContent().stream()
                .filter(item -> item.itemId().equals(stagedChildren.stream()
                        .filter(child -> child.getResolvedContentKey().endsWith("|1|2"))
                        .findFirst().orElseThrow().getId()))
                .findFirst().orElseThrow();
        assertThat(objectMapper.valueToTree(childOnePreview).path("watchedDate").asString())
                .isEqualTo("2020-01-01");
        assertThat(objectMapper.valueToTree(childOnePreview).path("datePrecision").asString()).isEqualTo("DAY");
        assertThat(objectMapper.valueToTree(childTwoPreview).path("watchedDate").isNull()).isTrue();
        assertThat(objectMapper.valueToTree(childTwoPreview).path("datePrecision").asString()).isEqualTo("UNKNOWN");
        assertThat(stagedChildren.stream().filter(child -> child.getResolvedContentKey().endsWith("|1|2"))
                .findFirst().orElseThrow().getOutcome()).isEqualTo("NORMALIZATION_ISSUE");
        assertThat(items.findById(parent.getId()).orElseThrow().getOutcome()).isEqualTo("EXPANSION_PREPARED");

        previewService.resolve(owner.getId(), job.getId(),
                List.of(new ImportResolutionOperation.AssignPass(childTwoPreview.itemId(), 1),
                        new ImportResolutionOperation.EditField(parent.getId(),
                                ImportResolutionOperation.EditableField.REVIEW, "Review edited in preview")),
                previewable.getPreviewVersion());
        worker.runAvailable(1);
        assertThat(jobs.findById(job.getId()).orElseThrow().getWorkPhase()).isEqualTo("APPLY");
        requeueForApply(job.getId());
        worker.runAvailable(1);

        assertThat(jobs.findById(job.getId()).orElseThrow().getState()).isEqualTo(ImportJobState.COMPLETED);
        assertThat(items.findById(parent.getId()).orElseThrow().getOutcome()).isEqualTo("APPLIED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM diary_entries WHERE user_id=?", Long.class,
                owner.getId())).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT id FROM diary_entries WHERE user_id=? AND content_id=?",
                UUID.class, owner.getId(), episodeTwo.getId())).isEqualTo(existingEpisodeTwo.diaryEntryId());
        org.mockito.ArgumentCaptor<HistoricalOpinion> editedOpinion =
                org.mockito.ArgumentCaptor.forClass(HistoricalOpinion.class);
        verify(opinionRestoreService).restore(eq(owner.getId()), editedOpinion.capture(),
                any(HistoricalViewingEvidence.class));
        assertThat(editedOpinion.getValue().comment()).isEqualTo("Review edited in preview");
        verify(calendar, times(1)).loadSeries("95007", "US", "en-US");
    }

    @Test
    void selectionWithoutHistoryDoesNotLoadSeriesCalendarOrStageEpisodes() throws Exception {
        User owner = createUser();
        Content series = createContent(ContentType.SERIES, "95008", null, null, null);
        ImportJob job = createJob(owner.getId());
        job.setState(ImportJobState.RESOLVING);
        job.setWorkPhase("RESOLVE");
        job.setSelectionJson("{\"schemaVersion\":1,\"planGeneration\":1,\"selection\":{\"history\":false,\"watchlist\":true,\"listIds\":[]}}");
        jobs.saveAndFlush(job);
        String parentUri = "https://letterboxd.com/film/watchlist-only/diary/890/";
        createParent(job, series, parentUri, "Must not be expanded", 8, 8);

        worker.runAvailable(1);

        assertThat(items.findAllByJobIdAndPlanGenerationAndParentSourceIdAndNormalizedKind(
                job.getId(), 1, parentUri, ImportRecordKind.DIARY_EVENT)).isEmpty();
        verify(calendar, org.mockito.Mockito.never()).loadSeries("95008", "US", "en-US");
    }

    @Test
    void incompleteRatingEvidenceKeepsParentPendingWithoutSwallowingRestoreFailure() throws Exception {
        User owner = createUser();
        Content series = createContent(ContentType.SERIES, "95005", null, null, null);
        Content episode = createContent(ContentType.EPISODE, null, "95005", 1, 1);
        ImportJob job = createJob(owner.getId());
        String parentUri = "https://letterboxd.com/film/incomplete-rating/diary/567/";
        ImportItem parent = createParent(job, series, parentUri, "Parent review", 7, 8);
        when(calendar.loadSeries("95005", "US", "en-US")).thenReturn(new CalendarScheduleLookup.FoundSeries(
                schedule("95005", List.of(season("95005", 1,
                        List.of(airing(1, LocalDate.of(2020, 1, 1))))))));
        when(contentService.getOrCreateReference(any())).thenReturn(new ContentRefDTO(episode.getId(), null,
                ContentType.EPISODE, "95005", 1, 1, false, false, null, null));
        when(passPlanner.plan(eq(owner.getId()), any())).thenAnswer(invocation -> {
            List<ImportPassPlanner.WatchEvent> events = invocation.getArgument(1);
            return new ImportPassPlanner.Plan(events.stream().collect(java.util.stream.Collectors.toMap(
                    ImportPassPlanner.WatchEvent::itemId, ignored -> 1)), Map.of(), Set.of());
        });
        org.mockito.Mockito.doAnswer(invocation -> {
            HistoricalViewingEvidence evidence = invocation.getArgument(2);
            return new HistoricalViewingEvidenceVerifier.Verification(1, 1,
                    Instant.now().plusSeconds(3600), false, evidence.eventIds());
        }).when(evidenceVerifier).verify(any(), any(), any(), eq(false), any());

        worker.runAvailable(1);
        worker.runAvailable(1);

        ImportJob awaitingConsent = jobs.findById(job.getId()).orElseThrow();
        assertThat(awaitingConsent.getState()).isEqualTo(ImportJobState.READY);
        assertThat(awaitingConsent.isWorkPending()).isFalse();
        assertThat(items.findById(parent.getId()).orElseThrow().getOutcome()).isEqualTo("NORMALIZATION_ISSUE");
        assertThat(items.findById(parent.getId()).orElseThrow().getIssue()).contains("SERIES_RATING_EVIDENCE_INCOMPLETE");
        assertThat(jdbc.queryForObject("SELECT state FROM external_import_links WHERE user_id=? AND source_namespace=? AND source_id=?",
                String.class, owner.getId(), job.getSourceNamespace(), parentUri)).isEqualTo("IN_PROGRESS");
        verify(contentRatingService, org.mockito.Mockito.never()).restoreInitial(eq(owner.getId()),
                any(MediaReference.class), any(), any(HistoricalViewingEvidence.class));
        verify(opinionRestoreService, org.mockito.Mockito.never()).restore(eq(owner.getId()),
                any(HistoricalOpinion.class), any(HistoricalViewingEvidence.class));

        previewService.resolve(owner.getId(), job.getId(),
                List.of(new ImportResolutionOperation.SkipItem(parent.getId())), awaitingConsent.getPreviewVersion());
        worker.runAvailable(1);
        requeueForApply(job.getId());
        worker.runAvailable(1);

        assertThat(jobs.findById(job.getId()).orElseThrow().getState()).isEqualTo(ImportJobState.COMPLETED);
        assertThat(items.findById(parent.getId()).orElseThrow().getOutcome()).isEqualTo("SKIPPED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM diary_entries WHERE user_id=?", Long.class,
                owner.getId())).isEqualTo(1L);
    }

    private User createUser() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        LocalDateTime now = LocalDateTime.now();
        return users.saveAndFlush(User.builder().username("c5" + suffix).name("C5 Import")
                .email("c5" + suffix + "@example.test").password(passwordEncoder.encode("Password123"))
                .preferredLanguage("en-US").preferredRegion("US").createdAt(now).updatedAt(now).build());
    }

    private Content createContent(ContentType type, String tmdbId, String seriesId, Integer season, Integer episode) {
        LocalDateTime now = LocalDateTime.now();
        return contents.saveAndFlush(Content.builder().type(type).tmdbId(tmdbId).seriesTmdbId(seriesId)
                .seasonNumber(season).episodeNumber(episode).createdAt(now).updatedAt(now).build());
    }

    private ImportJob createJob(UUID ownerId) {
        LocalDateTime now = LocalDateTime.now();
        return jobs.saveAndFlush(ImportJob.builder().userId(ownerId).inputKind(ImportInputKind.PLATFORM_ARCHIVE)
                .sourceFormat(ImportSourceFormat.LETTERBOXD_ZIP).platform(ImportPlatform.LETTERBOXD)
                .sourceAccountId("account-c5").sourceNamespace("letterboxd:account-c5")
                .state(ImportJobState.IMPORTING).previewVersion(1)
                .selectionJson("{\"schemaVersion\":1,\"planGeneration\":1,\"selection\":{}}")
                .timezone("UTC").storageKey(UUID.randomUUID().toString()).uploadSizeBytes(1)
                .workPhase("APPLY").workPending(true).nextRunAt(now.minusSeconds(1))
                .createdAt(now).updatedAt(now).expiresAt(now.plusDays(1)).build());
    }

    private void requeueForApply(UUID jobId) {
        ImportJob job = jobs.findById(jobId).orElseThrow();
        job.setState(ImportJobState.IMPORTING);
        job.setWorkPhase("APPLY");
        job.setWorkPending(true);
        job.setNextRunAt(LocalDateTime.now().minusSeconds(1));
        job.setLeaseToken(null);
        job.setLeaseUntil(null);
        jobs.saveAndFlush(job);
    }

    private ImportItem createParent(ImportJob job, Content series, String sourceId,
                                    String review, Integer publicationScore, Integer currentScore) throws Exception {
        var payload = objectMapper.createObjectNode();
        payload.put("watchedDate", "2020-01-01");
        if (review != null) payload.put("review", review);
        if (publicationScore != null) payload.put("reviewScoreAtPublication", publicationScore);
        if (currentScore != null) payload.put("currentRatingScore", currentScore);
        payload.put("sourceRecordedOn", "2020-01-02");
        return items.saveAndFlush(ImportItem.builder().jobId(job.getId()).planGeneration(1)
                .normalizedKind(ImportRecordKind.SERIES_EXPANSION).sourceNamespace(job.getSourceNamespace())
                .objectKind("diary_event").sourceId(sourceId).sourceEventKey(sourceId)
                .parserVersion("letterboxd-history-v1").resolvedContentKey("CONTENT|SERIES|" + series.getTmdbId())
                .resolvedTargetJson(objectMapper.writeValueAsString(new ImportTarget.ContentTarget(
                        MediaReference.series(series.getTmdbId()))))
                .payloadJson(objectMapper.writeValueAsString(payload)).outcome("MATCHED").ordinal(0).build());
    }

    private CalendarSeriesSchedule schedule(List<CalendarSeriesSchedule.Season> seasons) {
        return schedule("95001", seasons);
    }

    private CalendarSeriesSchedule schedule(String seriesId, List<CalendarSeriesSchedule.Season> seasons) {
        Map<Integer, Integer> counts = seasons.stream().collect(java.util.stream.Collectors.toMap(
                season -> season.schedule().seasonNumber(), CalendarSeriesSchedule.Season::expectedEpisodeCount));
        return new CalendarSeriesSchedule(new CalendarScheduleKey(ContentType.SERIES, seriesId, "en-US", "US"),
                seasons, counts, counts.values().stream().mapToInt(Integer::intValue).sum(), true);
    }

    private CalendarSeriesSchedule.Season season(int seasonNumber, List<CalendarEpisodeSchedule> episodes) {
        return season("95001", seasonNumber, episodes);
    }

    private CalendarSeriesSchedule.Season season(String seriesId, int seasonNumber,
                                                 List<CalendarEpisodeSchedule> episodes) {
        return new CalendarSeriesSchedule.Season(new CalendarSeasonSchedule(seriesId, seasonNumber, "US", "en-US",
                "Fixture Series", null, episodes), episodes.size(), TmdbLookupOrigin.REMOTE);
    }

    private CalendarEpisodeSchedule airing(int number, LocalDate releaseDate) {
        return new CalendarEpisodeSchedule(number, "Episode " + number, releaseDate, null, null, null, null);
    }
}
