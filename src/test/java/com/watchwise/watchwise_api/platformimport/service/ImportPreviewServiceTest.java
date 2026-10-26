package com.watchwise.watchwise_api.platformimport.service;

import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.platformimport.entity.ImportJob;
import com.watchwise.watchwise_api.platformimport.entity.ImportItem;
import com.watchwise.watchwise_api.platformimport.model.ImportDecision;
import com.watchwise.watchwise_api.platformimport.model.ImportInventory;
import com.watchwise.watchwise_api.platformimport.model.AliasQuery;
import com.watchwise.watchwise_api.platformimport.model.AliasTrust;
import com.watchwise.watchwise_api.platformimport.model.ImportResolutionOperation;
import com.watchwise.watchwise_api.platformimport.model.ImportInputKind;
import com.watchwise.watchwise_api.platformimport.model.ImportJobState;
import com.watchwise.watchwise_api.platformimport.model.ImportModeKey;
import com.watchwise.watchwise_api.platformimport.model.ImportPlatform;
import com.watchwise.watchwise_api.platformimport.model.ImportReadContext;
import com.watchwise.watchwise_api.platformimport.model.ImportRecordKind;
import com.watchwise.watchwise_api.platformimport.model.ImportTarget;
import com.watchwise.watchwise_api.platformimport.model.ImportSelection;
import com.watchwise.watchwise_api.platformimport.model.ImportSourceFormat;
import com.watchwise.watchwise_api.platformimport.model.ImportSourceKey;
import com.watchwise.watchwise_api.platformimport.model.NormalizedImportRecord;
import com.watchwise.watchwise_api.platformimport.model.ConfirmedWatchScopeSnapshot;
import com.watchwise.watchwise_api.platformimport.dto.ImportWatchScopePreviewDTO;
import com.watchwise.watchwise_api.content.dto.MediaReference;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.calendar.repository.CalendarScheduleCompletenessRepository;
import com.watchwise.watchwise_api.diarycompletion.model.EpisodeCoordinate;
import com.watchwise.watchwise_api.platformimport.repository.ImportItemRepository;
import com.watchwise.watchwise_api.platformimport.repository.ImportDeferredRecordRepository;
import com.watchwise.watchwise_api.platformimport.repository.ImportJobRepository;
import com.watchwise.watchwise_api.userlist.repository.UserListRepository;
import com.watchwise.watchwise_api.userlist.entity.UserList;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.platformimport.service.impl.ImportPreviewServiceImpl;
import com.watchwise.watchwise_api.platformimport.service.impl.ImportedReviewTextNormalizer;
import com.watchwise.watchwise_api.platformimport.service.impl.DeferredOpinionReplayStager;
import com.watchwise.watchwise_api.platformimport.service.ExternalContentAliasService;
import com.watchwise.watchwise_api.common.pagination.PageRequestFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;

@ExtendWith(MockitoExtension.class)
class ImportPreviewServiceTest {
    @Mock
    private ImportJobRepository importJobRepository;

    @Mock
    private ImportItemRepository importItemRepository;

    @Mock
    private ImportDeferredRecordRepository deferredRecordRepository;

    @Mock
    private DeferredOpinionReplayStager deferredOpinionReplayStager;

    @Mock
    private CalendarScheduleCompletenessRepository scheduleCompletenessRepository;

    @Mock
    private ImportModeRegistry importModeRegistry;

    @Mock
    private ExternalContentAliasService externalContentAliasService;

    @Mock
    private UserListRepository userListRepository;

    @Mock
    private UserRepository userRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ImportLimits importLimits = new ImportLimits();
    private final PageRequestFactory pageRequestFactory = new PageRequestFactory();
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"), ZoneId.of("UTC"));

    private ImportPreviewService service;

    @BeforeEach
    void setUp() {
        service = new ImportPreviewServiceImpl(importJobRepository, importItemRepository,
                deferredRecordRepository, deferredOpinionReplayStager, scheduleCompletenessRepository,
                importModeRegistry,
                externalContentAliasService, userListRepository, userRepository,
                objectMapper, new ImportedReviewTextNormalizer(),
                importLimits, clock, pageRequestFactory);
    }

    @Test
    @DisplayName("[preview] Should Return Not Found - When The Job Belongs To Another Owner")
    void shouldReturnNotFoundWhenTheJobBelongsToAnotherOwner() {
        UUID ownerId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        when(importJobRepository.findOwnedById(jobId, ownerId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.preview(ownerId, jobId, 0, 20))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Import job not found");

        verify(importJobRepository).findOwnedById(jobId, ownerId);
        verifyNoInteractions(importItemRepository, importModeRegistry, externalContentAliasService, userListRepository);
    }

    @Test
    @DisplayName("[select] Should Increment The Preview Version - When A Valid Selection Is Submitted")
    void shouldIncrementThePreviewVersionWhenAValidSelectionIsSubmitted() {
        UUID ownerId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        ImportJob job = job(ownerId, jobId, 4, ImportJobState.AWAITING_SELECTION);
        job.setLeaseToken(UUID.randomUUID());
        job.setLeaseUntil(LocalDateTime.now(clock).plusMinutes(2));
        when(importJobRepository.findOwnedByIdForUpdate(jobId, ownerId)).thenReturn(Optional.of(job));
        when(importModeRegistry.require(new ImportModeKey(job.getInputKind(), job.getSourceFormat())))
                .thenReturn(emptyModeHandler(new ImportModeKey(job.getInputKind(), job.getSourceFormat())));
        ImportSelection selection = new ImportSelection(true, false, Set.of("zeta", "alpha"), null);

        var result = service.select(ownerId, jobId, selection, 4);

        String selectionJson = "{\"schemaVersion\":1,\"planGeneration\":1,\"selection\":{\"history\":true,\"watchlist\":false,\"listIds\":[\"alpha\",\"zeta\"],\"metadata\":null}}";
        assertThat(job.getPreviewVersion()).isEqualTo(5);
        assertThat(job.getVersion()).isEqualTo(9);
        assertThat(job.getSelectionJson()).isEqualTo(selectionJson);
        assertThat(job.isWorkPending()).isTrue();
        assertThat(job.getState()).isEqualTo(ImportJobState.ANALYZING);
        assertThat(job.getNextRunAt()).isEqualTo(LocalDateTime.now(clock));
        assertThat(job.getLeaseToken()).isNull();
        assertThat(job.getLeaseUntil()).isNull();
        assertThat(result.version()).isEqualTo(5);
        assertThat(result.digest()).isEqualTo(expectedDigest(jobId, 5, selectionJson));
        verify(importJobRepository).findOwnedByIdForUpdate(jobId, ownerId);
        verifyNoInteractions(importItemRepository);
    }

    @Test
    @DisplayName("[select] Should Bind The External Account Namespace - When Account Identity Is Supplied")
    void shouldBindExternalAccountNamespaceWhenAccountIdentityIsSupplied() {
        UUID ownerId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        ImportJob job = job(ownerId, jobId, 4, ImportJobState.AWAITING_SELECTION);
        when(importJobRepository.findOwnedByIdForUpdate(jobId, ownerId)).thenReturn(Optional.of(job));
        ImportModeKey mode = new ImportModeKey(job.getInputKind(), job.getSourceFormat());
        when(importModeRegistry.require(mode)).thenReturn(emptyModeHandler(mode));
        when(importItemRepository.countByJobId(jobId)).thenReturn(0L);
        ObjectNode metadata = objectMapper.createObjectNode().put("sourceAccount", "letterboxd-lucas");

        service.select(ownerId, jobId, new ImportSelection(true, false, Set.of(), metadata), 4);

        assertThat(job.getSourceAccountId()).isEqualTo("letterboxd-lucas");
        assertThat(job.getSourceNamespace()).isEqualTo("TRAKT:ACCOUNT:letterboxd-lucas");
        verify(importItemRepository).countByJobId(jobId);
    }

    @Test
    @DisplayName("[select] Should Reject A Different External Account - When Upload Already Bound One")
    void shouldRejectDifferentExternalAccountWhenUploadAlreadyBoundOne() {
        UUID ownerId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        ImportJob job = ImportJob.builder()
                .id(jobId).userId(ownerId).inputKind(ImportInputKind.PLATFORM_ARCHIVE)
                .sourceFormat(ImportSourceFormat.TRAKT_ZIP).platform(ImportPlatform.TRAKT)
                .sourceAccountId("upload-account").sourceNamespace("TRAKT:ACCOUNT:upload-account")
                .state(ImportJobState.AWAITING_SELECTION).version(9).previewVersion(4)
                .selectionJson("{}").timezone("UTC").storageKey("private-key").uploadSizeBytes(10)
                .workPending(false).createdAt(LocalDateTime.of(2026, 10, 21, 0, 0))
                .updatedAt(LocalDateTime.of(2026, 10, 21, 0, 0)).build();
        when(importJobRepository.findOwnedByIdForUpdate(jobId, ownerId)).thenReturn(Optional.of(job));
        ImportModeKey mode = new ImportModeKey(job.getInputKind(), job.getSourceFormat());
        when(importModeRegistry.require(mode)).thenReturn(emptyModeHandler(mode));
        ObjectNode metadata = objectMapper.createObjectNode().put("sourceAccount", "other-account");

        assertThatThrownBy(() -> service.select(ownerId, jobId,
                new ImportSelection(true, false, Set.of(), metadata), 4))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Source account does not match the uploaded account");

        assertThat(job.getSourceAccountId()).isEqualTo("upload-account");
        assertThat(job.getSourceNamespace()).isEqualTo("TRAKT:ACCOUNT:upload-account");
        verifyNoInteractions(importItemRepository);
    }

    @Test
    @DisplayName("[select] Should Keep The Job Awaiting Selection - When Archive Account Identity Is Missing")
    void shouldRequireExternalAccountBeforeArchiveNormalization() {
        UUID ownerId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        ImportJob job = job(ownerId, jobId, 4, ImportJobState.AWAITING_SELECTION);
        job.setSourceNamespace("TRAKT:OWNER:" + ownerId);
        when(importJobRepository.findOwnedByIdForUpdate(jobId, ownerId)).thenReturn(Optional.of(job));
        ImportModeKey mode = new ImportModeKey(job.getInputKind(), job.getSourceFormat());
        when(importModeRegistry.require(mode)).thenReturn(emptyModeHandler(mode));

        assertThatThrownBy(() -> service.select(ownerId, jobId,
                new ImportSelection(true, false, Set.of(), null), 4))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Source account identifier is required before archive normalization");

        assertThat(job.getState()).isEqualTo(ImportJobState.AWAITING_SELECTION);
        assertThat(job.getPreviewVersion()).isEqualTo(4);
        assertThat(job.getSelectionJson()).isEqualTo("{}");
        verifyNoInteractions(importItemRepository);
    }

    @Test
    @DisplayName("[resolve] Should Reject Episodes Outside The Resolved Series - When Scope Is Confirmed")
    void shouldRejectConfirmedEpisodesOutsideResolvedSeries() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        ImportJob job = job(ownerId, jobId, 4, ImportJobState.READY);
        setPlanGeneration(job, 4);
        ImportItem summary = item(itemId, jobId, 4, ImportRecordKind.WATCHED_SUMMARY, "{}", 1);
        summary.setResolvedTargetJson(objectMapper.writeValueAsString(
                new ImportTarget.ContentTarget(MediaReference.series("123"))));
        seedWatchScopeSnapshot(summary, MediaReference.series("123"), "schedule-v2",
                Set.of(new EpisodeCoordinate(1, 1), new EpisodeCoordinate(1, 2)));
        when(importJobRepository.findOwnedByIdForUpdate(jobId, ownerId)).thenReturn(Optional.of(job));
        when(importItemRepository.findAllByJobIdAndPlanGenerationAndIdIn(eq(jobId), eq(4L), anyCollection()))
                .thenReturn(List.of(summary));

        assertThatThrownBy(() -> service.resolve(ownerId, jobId,
                List.of(new ImportResolutionOperation.ConfirmWatchScope(itemId,
                        ImportResolutionOperation.WatchScope.SPECIFIC_EPISODES,
                        "schedule-v1", List.of(MediaReference.episode("999", 1, 1)))), 4))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Watch scope episodes must belong to the resolved series or season");

        assertThat(summary.getDecision()).isNull();
        assertThat(job.getPreviewVersion()).isEqualTo(4);
    }

    @Test
    @DisplayName("[resolve] Should Reject A Stale Watch Scope Version - When Schedule Changed After Preview")
    void shouldRejectStaleWatchScopeVersionWhenScheduleChangedAfterPreview() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        ImportJob job = job(ownerId, jobId, 4, ImportJobState.READY);
        setPlanGeneration(job, 4);
        ImportItem summary = item(itemId, jobId, 4, ImportRecordKind.WATCHED_SUMMARY, "{}", 1);
        summary.setResolvedTargetJson(objectMapper.writeValueAsString(
                new ImportTarget.ContentTarget(MediaReference.series("123"))));
        seedWatchScopeSnapshot(summary, MediaReference.series("123"), "schedule-v2",
                Set.of(new EpisodeCoordinate(1, 1), new EpisodeCoordinate(1, 2)));
        when(importJobRepository.findOwnedByIdForUpdate(jobId, ownerId)).thenReturn(Optional.of(job));
        when(importItemRepository.findAllByJobIdAndPlanGenerationAndIdIn(eq(jobId), eq(4L), anyCollection()))
                .thenReturn(List.of(summary));
        User owner = mock(User.class);
        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
        assertThatThrownBy(() -> service.resolve(ownerId, jobId,
                List.of(new ImportResolutionOperation.ConfirmWatchScope(itemId,
                        ImportResolutionOperation.WatchScope.ALL_RELEASED_EPISODES, "schedule-v1",
                        List.of(MediaReference.episode("123", 1, 1)))), 4))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Episode schedule changed; refresh the import preview and confirm again");

        assertThat(summary.getDecision()).isNull();
        assertThat(job.getPreviewVersion()).isEqualTo(4);
    }

    @Test
    @DisplayName("[resolve] Should Reject Specific Episodes Outside The Released Scope")
    void shouldRejectSpecificEpisodesOutsideCurrentReleasedScope() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        ImportJob job = job(ownerId, jobId, 4, ImportJobState.READY);
        setPlanGeneration(job, 4);
        ImportItem summary = item(itemId, jobId, 4, ImportRecordKind.WATCHED_SUMMARY, "{}", 1);
        summary.setResolvedTargetJson(objectMapper.writeValueAsString(
                new ImportTarget.ContentTarget(MediaReference.series("123"))));
        seedWatchScopeSnapshot(summary, MediaReference.series("123"), "schedule-v1",
                Set.of(new EpisodeCoordinate(1, 1), new EpisodeCoordinate(1, 2)));
        when(importJobRepository.findOwnedByIdForUpdate(jobId, ownerId)).thenReturn(Optional.of(job));
        when(importItemRepository.findAllByJobIdAndPlanGenerationAndIdIn(eq(jobId), eq(4L), anyCollection()))
                .thenReturn(List.of(summary));
        User owner = mock(User.class);
        when(owner.getPreferredRegion()).thenReturn("US");
        when(owner.getPreferredLanguage()).thenReturn("en-US");
        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
        assertThatThrownBy(() -> service.resolve(ownerId, jobId,
                List.of(new ImportResolutionOperation.ConfirmWatchScope(itemId,
                        ImportResolutionOperation.WatchScope.SPECIFIC_EPISODES, "schedule-v1",
                        List.of(MediaReference.episode("123", 1, 99)))), 4))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Confirmed episodes are outside the current released schedule");

        assertThat(summary.getDecision()).isNull();
        assertThat(job.getPreviewVersion()).isEqualTo(4);
    }

    @Test
    @DisplayName("[resolve] Should Resume Deferred Opinion - When The User Confirms Its Watch Scope")
    void shouldResumeDeferredOpinionWhenWatchScopeIsConfirmed() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        ImportJob job = job(ownerId, jobId, 4, ImportJobState.READY);
        setPlanGeneration(job, 4);
        ImportItem replay = ImportItem.builder().id(itemId).jobId(jobId).planGeneration(4)
                .normalizedKind(ImportRecordKind.REVIEW).sourceNamespace("trakt")
                .objectKind("deferred_opinion_replay").sourceId("deferred-id")
                .payloadJson("{}").ordinal(1).outcome("NORMALIZATION_ISSUE")
                .issue("OPINION_EVIDENCE_PENDING: A complete pass is required.")
                .resolvedTargetJson(objectMapper.writeValueAsString(
                        new ImportTarget.ContentTarget(MediaReference.series("123"))))
                .build();
        seedWatchScopeSnapshot(replay, MediaReference.series("123"), "schedule-v1",
                Set.of(new EpisodeCoordinate(1, 1), new EpisodeCoordinate(1, 2)));
        when(importJobRepository.findOwnedByIdForUpdate(jobId, ownerId)).thenReturn(Optional.of(job));
        when(importItemRepository.findAllByJobIdAndPlanGenerationAndIdIn(eq(jobId), eq(4L), anyCollection()))
                .thenReturn(List.of(replay));
        User owner = mock(User.class);
        when(owner.getPreferredRegion()).thenReturn("US");
        when(owner.getPreferredLanguage()).thenReturn("en-US");
        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
        service.resolve(ownerId, jobId, List.of(new ImportResolutionOperation.ConfirmWatchScope(itemId,
                ImportResolutionOperation.WatchScope.ALL_RELEASED_EPISODES, "schedule-v1",
                List.of(MediaReference.episode("123", 1, 1), MediaReference.episode("123", 1, 2)))), 4);

        assertThat(replay.getDecision()).isEqualTo(ImportDecision.CONFIRM_WATCH_SCOPE);
        assertThat(replay.getOutcome()).isEqualTo("RESOLVED");
        assertThat(replay.getIssue()).isNull();
        assertThat(replay.getPayloadJson()).contains("confirmWatchScope");
    }

    @Test
    @DisplayName("[select] Should Canonicalize Metadata Objects - When Property Insertion Order Differs")
    void shouldCanonicalizeMetadataObjectsWhenPropertyInsertionOrderDiffers() {
        UUID ownerId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        ImportJob firstJob = job(ownerId, jobId, 4, ImportJobState.AWAITING_SELECTION);
        ImportJob secondJob = job(ownerId, jobId, 4, ImportJobState.AWAITING_SELECTION);
        when(importJobRepository.findOwnedByIdForUpdate(jobId, ownerId))
                .thenReturn(Optional.of(firstJob), Optional.of(secondJob));
        ImportModeKey mode = new ImportModeKey(firstJob.getInputKind(), firstJob.getSourceFormat());
        when(importModeRegistry.require(mode)).thenReturn(emptyModeHandler(mode));

        ObjectNode firstMetadata = objectMapper.createObjectNode();
        firstMetadata.put("zeta", 3);
        ObjectNode firstNested = firstMetadata.putObject("nested");
        firstNested.put("second", true);
        firstNested.put("first", "value");
        firstMetadata.putArray("ordered").add(2).add(1);

        ObjectNode secondMetadata = objectMapper.createObjectNode();
        secondMetadata.putArray("ordered").add(2).add(1);
        ObjectNode secondNested = secondMetadata.putObject("nested");
        secondNested.put("first", "value");
        secondNested.put("second", true);
        secondMetadata.put("zeta", 3);

        var firstResult = service.select(ownerId, jobId,
                new ImportSelection(true, false, Set.of(), firstMetadata), 4);
        var secondResult = service.select(ownerId, jobId,
                new ImportSelection(true, false, Set.of(), secondMetadata), 4);

        assertThat(firstJob.getSelectionJson()).isEqualTo(secondJob.getSelectionJson());
        assertThat(firstJob.getSelectionJson()).contains("\"ordered\":[2,1]");
        assertThat(firstResult.version()).isEqualTo(secondResult.version());
        assertThat(firstResult.digest()).isEqualTo(secondResult.digest());
    }

    @Test
    @DisplayName("[select] Should Leave The Plan Unchanged - When The Expected Version Is Stale")
    void shouldLeaveThePlanUnchangedWhenTheExpectedVersionIsStale() {
        UUID ownerId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        ImportJob job = job(ownerId, jobId, 4, ImportJobState.AWAITING_SELECTION);
        when(importJobRepository.findOwnedByIdForUpdate(jobId, ownerId)).thenReturn(Optional.of(job));
        ImportSelection selection = new ImportSelection(true, false, Set.of("list-1"), null);

        assertThatThrownBy(() -> service.select(ownerId, jobId, selection, 3))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Import preview has changed");

        assertThat(job.getPreviewVersion()).isEqualTo(4);
        assertThat(job.getSelectionJson()).isEqualTo("{}");
        assertThat(job.isWorkPending()).isFalse();
        assertThat(job.getState()).isEqualTo(ImportJobState.AWAITING_SELECTION);
        verifyNoInteractions(importItemRepository, externalContentAliasService, userListRepository);
    }

    @Test
    @DisplayName("[resolve] Should Increment The Preview Version - When A Typed Target Is Selected")
    void shouldIncrementThePreviewVersionWhenATypedTargetIsSelected() {
        UUID ownerId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        ImportJob job = job(ownerId, jobId, 4, ImportJobState.READY);
        job.setLeaseToken(UUID.randomUUID());
        job.setLeaseUntil(LocalDateTime.now(clock).plusMinutes(2));
        setPlanGeneration(job, 4);
        ImportItem item = item(itemId, jobId, 4, ImportRecordKind.DIARY_EVENT,
                "{\"title\":\"Original title\",\"score\":7,\"sourceQuery\":{"
                        + "\"kind\":\"CONTENT\",\"title\":\"Original title\",\"year\":2001,"
                        + "\"contentType\":\"MOVIE\"}}", 1);
        ImportTarget target = new ImportTarget.ContentTarget(MediaReference.movie("123"));
        when(importJobRepository.findOwnedByIdForUpdate(jobId, ownerId)).thenReturn(Optional.of(job));
        when(importItemRepository.findAllByJobIdAndPlanGenerationAndIdIn(eq(jobId), eq(4L), anyCollection()))
                .thenReturn(List.of(item));

        var result = service.resolve(ownerId, jobId,
                List.of(new ImportResolutionOperation.ResolveTarget(itemId, target)), 4);

        assertThat(job.getPreviewVersion()).isEqualTo(5);
        assertThat(job.getState()).isEqualTo(ImportJobState.RESOLVING);
        assertThat(job.isWorkPending()).isTrue();
        assertThat(job.getLeaseToken()).isNull();
        assertThat(job.getLeaseUntil()).isNull();
        assertThat(item.getDecision()).isEqualTo(ImportDecision.RESOLVE_TARGET);
        assertThat(item.getResolvedTargetJson()).contains("123");
        assertThat(item.getPayloadJson()).contains("Original title");
        assertThat(result.version()).isEqualTo(5);
        assertThat(result.digest()).isEqualTo(expectedDigest(jobId, 5, job.getSelectionJson()));
    }

    @Test
    @DisplayName("[resolve] Should Reject A Series Target Change - After Episode Expansion Was Prepared")
    void shouldRejectChangingASeriesTargetAfterEpisodeExpansionWasPrepared() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        ImportJob job = job(ownerId, jobId, 4, ImportJobState.RESOLVING);
        setPlanGeneration(job, 4);
        ImportItem item = item(itemId, jobId, 4, ImportRecordKind.SERIES_EXPANSION,
                "{\"_watchwiseResolution\":1,\"source\":{\"sourceQuery\":{"
                        + "\"kind\":\"CONTENT\",\"title\":\"Original Series\",\"year\":2020,"
                        + "\"contentType\":\"SERIES\"},\"_watchwiseExpansion\":{"
                        + "\"schemaVersion\":1,\"childCount\":12}},\"resolution\":{\"fields\":{}}}", 1);
        item.setOutcome("NORMALIZATION_ISSUE");
        item.setResolvedTargetJson(objectMapper.writeValueAsString(
                new ImportTarget.ContentTarget(MediaReference.series("111"))));
        when(importJobRepository.findOwnedByIdForUpdate(jobId, ownerId)).thenReturn(Optional.of(job));
        when(importItemRepository.findAllByJobIdAndPlanGenerationAndIdIn(eq(jobId), eq(4L), anyCollection()))
                .thenReturn(List.of(item));

        assertThatThrownBy(() -> service.resolve(ownerId, jobId,
                List.of(new ImportResolutionOperation.ResolveTarget(itemId,
                        new ImportTarget.ContentTarget(MediaReference.series("222")))), 4))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("cancel and create a new import");

        assertThat(item.getDecision()).isNull();
        assertThat(item.getResolvedTargetJson()).contains("111").doesNotContain("222");
        assertThat(job.getPreviewVersion()).isEqualTo(4);
        verifyNoInteractions(externalContentAliasService);
    }

    @Test
    @DisplayName("[resolve] Should Reject A Watched Date Change - After Episode Expansion Was Prepared")
    void shouldRejectChangingTheWatchedDateAfterEpisodeExpansionWasPrepared() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        ImportJob job = job(ownerId, jobId, 4, ImportJobState.RESOLVING);
        setPlanGeneration(job, 4);
        ImportItem item = item(itemId, jobId, 4, ImportRecordKind.SERIES_EXPANSION,
                "{\"_watchwiseExpansion\":{\"schemaVersion\":1,\"childCount\":12}}", 1);
        item.setOutcome("NORMALIZATION_ISSUE");
        when(importJobRepository.findOwnedByIdForUpdate(jobId, ownerId)).thenReturn(Optional.of(job));
        when(importItemRepository.findAllByJobIdAndPlanGenerationAndIdIn(eq(jobId), eq(4L), anyCollection()))
                .thenReturn(List.of(item));

        assertThatThrownBy(() -> service.resolve(ownerId, jobId,
                List.of(new ImportResolutionOperation.EditField(itemId,
                        ImportResolutionOperation.EditableField.WATCHED_DATE, "2021-01-01")), 4))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("cancel and create a new import");

        assertThat(item.getDecision()).isNull();
        assertThat(job.getPreviewVersion()).isEqualTo(4);
    }

    @Test
    @DisplayName("[resolve] Should Allow A Review Fix - When The Source Review Exceeds Its Limit")
    void shouldAllowAReviewFixWhenTheSourceReviewExceedsItsLimit() {
        UUID ownerId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        ImportJob job = job(ownerId, jobId, 4, ImportJobState.READY);
        setPlanGeneration(job, 4);
        ImportItem item = item(itemId, jobId, 4, ImportRecordKind.SERIES_EXPANSION,
                "{\"review\":\"original\",\"_watchwiseExpansion\":{\"schemaVersion\":1,\"childCount\":12}}", 1);
        item.setOutcome("NORMALIZATION_ISSUE");
        item.setIssue("REVIEW_TEXT_TOO_LONG: Edit the review.");
        when(importJobRepository.findOwnedByIdForUpdate(jobId, ownerId)).thenReturn(Optional.of(job));
        when(importItemRepository.findAllByJobIdAndPlanGenerationAndIdIn(eq(jobId), eq(4L), anyCollection()))
                .thenReturn(List.of(item));

        service.resolve(ownerId, jobId, List.of(new ImportResolutionOperation.EditField(itemId,
                ImportResolutionOperation.EditableField.REVIEW, "Shorter review.")), 4);

        assertThat(item.getOutcome()).isEqualTo("RESOLVED");
        assertThat(item.getIssue()).isNull();
        assertThat(item.getPayloadJson()).contains("Shorter review.");
        assertThat(job.getPreviewVersion()).isEqualTo(5);
    }

    @Test
    @DisplayName("[resolve] Should Allow A List Item Description Fix - When The Source Note Exceeds Its Limit")
    void shouldAllowAListItemDescriptionFixWhenTheSourceNoteExceedsItsLimit() {
        UUID ownerId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        ImportJob job = job(ownerId, jobId, 4, ImportJobState.READY);
        setPlanGeneration(job, 4);
        ImportItem item = item(itemId, jobId, 4, ImportRecordKind.LIST_ITEM,
                "{\"title\":\"Example\",\"description\":\"" + "x".repeat(401) + "\"}", 1);
        item.setOutcome("NORMALIZATION_ISSUE");
        item.setIssue("LIST_ITEM_DESCRIPTION_TOO_LONG: Edit the note.");
        when(importJobRepository.findOwnedByIdForUpdate(jobId, ownerId)).thenReturn(Optional.of(job));
        when(importItemRepository.findAllByJobIdAndPlanGenerationAndIdIn(eq(jobId), eq(4L), anyCollection()))
                .thenReturn(List.of(item));

        service.resolve(ownerId, jobId, List.of(new ImportResolutionOperation.EditField(itemId,
                ImportResolutionOperation.EditableField.DESCRIPTION, "Short note.")), 4);

        assertThat(item.getOutcome()).isEqualTo("RESOLVED");
        assertThat(item.getIssue()).isNull();
        assertThat(item.getPayloadJson()).contains("Short note.");
        assertThat(job.getPreviewVersion()).isEqualTo(5);
    }

    @Test
    @DisplayName("[resolve] Should Reject An Overlong List Item Description Edit - When It Still Exceeds Its Limit")
    void shouldRejectAnOverlongListItemDescriptionEditWhenItStillExceedsItsLimit() {
        UUID ownerId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        ImportJob job = job(ownerId, jobId, 4, ImportJobState.READY);
        setPlanGeneration(job, 4);
        ImportItem item = item(itemId, jobId, 4, ImportRecordKind.LIST_ITEM, "{}", 1);
        when(importJobRepository.findOwnedByIdForUpdate(jobId, ownerId)).thenReturn(Optional.of(job));
        when(importItemRepository.findAllByJobIdAndPlanGenerationAndIdIn(eq(jobId), eq(4L), anyCollection()))
                .thenReturn(List.of(item));

        assertThatThrownBy(() -> service.resolve(ownerId, jobId,
                List.of(new ImportResolutionOperation.EditField(itemId,
                        ImportResolutionOperation.EditableField.DESCRIPTION, "x".repeat(401))), 4))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("400");

        assertThat(item.getDecision()).isNull();
        assertThat(job.getPreviewVersion()).isEqualTo(4);
    }

    @Test
    @DisplayName("[resolve] Should Reject An Overlong Review Edit - When It Still Exceeds The Review Limit")
    void shouldRejectAnOverlongReviewEditWhenItStillExceedsTheReviewLimit() {
        UUID ownerId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        ImportJob job = job(ownerId, jobId, 4, ImportJobState.READY);
        setPlanGeneration(job, 4);
        ImportItem item = item(itemId, jobId, 4, ImportRecordKind.DIARY_EVENT, "{}", 1);
        item.setOutcome("NORMALIZATION_ISSUE");
        item.setIssue("REVIEW_TEXT_TOO_LONG: Edit the review.");
        when(importJobRepository.findOwnedByIdForUpdate(jobId, ownerId)).thenReturn(Optional.of(job));
        when(importItemRepository.findAllByJobIdAndPlanGenerationAndIdIn(eq(jobId), eq(4L), anyCollection()))
                .thenReturn(List.of(item));

        assertThatThrownBy(() -> service.resolve(ownerId, jobId,
                List.of(new ImportResolutionOperation.EditField(itemId,
                        ImportResolutionOperation.EditableField.REVIEW, "x".repeat(20_001))), 4))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("20000");

        assertThat(item.getDecision()).isNull();
        assertThat(job.getPreviewVersion()).isEqualTo(4);
    }

    @Test
    @DisplayName("[resolve] Should Require Pass Assignment On Expanded Episodes - Not On The Series Parent")
    void shouldRejectAssigningAPassToTheSeriesParent() {
        UUID ownerId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        ImportJob job = job(ownerId, jobId, 4, ImportJobState.RESOLVING);
        setPlanGeneration(job, 4);
        ImportItem item = item(itemId, jobId, 4, ImportRecordKind.SERIES_EXPANSION, "{}", 1);
        item.setOutcome("EXPANSION_PREPARED");
        when(importJobRepository.findOwnedByIdForUpdate(jobId, ownerId)).thenReturn(Optional.of(job));
        when(importItemRepository.findAllByJobIdAndPlanGenerationAndIdIn(eq(jobId), eq(4L), anyCollection()))
                .thenReturn(List.of(item));

        assertThatThrownBy(() -> service.resolve(ownerId, jobId,
                List.of(new ImportResolutionOperation.AssignPass(itemId, 2)), 4))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("expanded episode");

        assertThat(item.getDecision()).isNull();
        assertThat(job.getPreviewVersion()).isEqualTo(4);
    }

    @Test
    @DisplayName("[resolve] Should Remember A Confirmed Alias In Owner Scope - When A User Selects A Target")
    void shouldRememberAConfirmedAliasInOwnerScopeWhenAUserSelectsATarget() {
        UUID ownerId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        ImportJob job = job(ownerId, jobId, 4, ImportJobState.READY);
        setPlanGeneration(job, 4);
        ImportItem item = item(itemId, jobId, 4, ImportRecordKind.DIARY_EVENT, "{}", 1);
        String candidateEnvelope = "{\"schemaVersion\":1,\"query\":{\"kind\":\"CONTENT\","
                + "\"title\":\"Example Movie\",\"year\":2001,\"contentType\":\"MOVIE\"},\"candidates\":[]}";
        item.setResolvedTargetJson(candidateEnvelope);
        ImportTarget target = new ImportTarget.ContentTarget(MediaReference.movie("123"));
        when(importJobRepository.findOwnedByIdForUpdate(jobId, ownerId)).thenReturn(Optional.of(job));
        when(importItemRepository.findAllByJobIdAndPlanGenerationAndIdIn(eq(jobId), eq(4L), anyCollection()))
                .thenReturn(List.of(item));

        service.resolve(ownerId, jobId,
                List.of(new ImportResolutionOperation.ResolveTarget(itemId, target)), 4);

        verify(externalContentAliasService).remember(ownerId,
                new AliasQuery("Example Movie", 2001, ContentType.MOVIE), target, AliasTrust.USER_CONFIRMED);
    }

    @Test
    @DisplayName("[resolve] Should Preserve The Source Query - When The User Changes A Target Choice")
    void shouldPreserveTheSourceQueryWhenTheUserChangesATargetChoice() {
        UUID ownerId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        ImportJob job = job(ownerId, jobId, 4, ImportJobState.READY);
        setPlanGeneration(job, 4);
        ImportItem item = item(itemId, jobId, 4, ImportRecordKind.DIARY_EVENT, "{}", 1);
        item.setResolvedTargetJson("{\"schemaVersion\":1,\"query\":{\"kind\":\"CONTENT\","
                + "\"title\":\"Example Movie\",\"year\":2001,\"contentType\":\"MOVIE\"},\"candidates\":[]}");
        ImportTarget firstChoice = new ImportTarget.ContentTarget(MediaReference.movie("123"));
        ImportTarget secondChoice = new ImportTarget.ContentTarget(MediaReference.movie("456"));
        when(importJobRepository.findOwnedByIdForUpdate(jobId, ownerId)).thenReturn(Optional.of(job));
        when(importItemRepository.findAllByJobIdAndPlanGenerationAndIdIn(eq(jobId), eq(4L), anyCollection()))
                .thenReturn(List.of(item));

        service.resolve(ownerId, jobId, List.of(new ImportResolutionOperation.ResolveTarget(itemId, firstChoice)), 4);
        service.resolve(ownerId, jobId, List.of(new ImportResolutionOperation.ResolveTarget(itemId, secondChoice)), 5);

        AliasQuery query = new AliasQuery("Example Movie", 2001, ContentType.MOVIE);
        verify(externalContentAliasService).remember(ownerId, query, firstChoice, AliasTrust.USER_CONFIRMED);
        verify(externalContentAliasService).remember(ownerId, query, secondChoice, AliasTrust.USER_CONFIRMED);
    }

    @Test
    @DisplayName("[resolve] Should Leave The Plan Unchanged - When The Expected Version Is Stale")
    void shouldLeaveThePlanUnchangedWhenResolutionVersionIsStale() {
        UUID ownerId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        ImportJob job = job(ownerId, jobId, 4, ImportJobState.READY);
        setPlanGeneration(job, 4);
        when(importJobRepository.findOwnedByIdForUpdate(jobId, ownerId)).thenReturn(Optional.of(job));

        assertThatThrownBy(() -> service.resolve(ownerId, jobId,
                List.of(new ImportResolutionOperation.SkipItem(UUID.randomUUID())), 3))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Import preview has changed");

        assertThat(job.getPreviewVersion()).isEqualTo(4);
        assertThat(job.isWorkPending()).isFalse();
        assertThat(job.getState()).isEqualTo(ImportJobState.READY);
        verifyNoInteractions(importItemRepository, externalContentAliasService, userListRepository);
    }

    @Test
    @DisplayName("[resolve] Should Reject A Person Target - When The Item Is A Watchlist Entry")
    void shouldRejectAPersonTargetWhenTheItemIsAWatchlistEntry() {
        UUID ownerId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        ImportJob job = job(ownerId, jobId, 4, ImportJobState.READY);
        setPlanGeneration(job, 4);
        ImportItem item = item(itemId, jobId, 4, ImportRecordKind.WATCHLIST_ITEM, "{}", 1);
        when(importJobRepository.findOwnedByIdForUpdate(jobId, ownerId)).thenReturn(Optional.of(job));
        when(importItemRepository.findAllByJobIdAndPlanGenerationAndIdIn(eq(jobId), eq(4L), anyCollection()))
                .thenReturn(List.of(item));

        assertThatThrownBy(() -> service.resolve(ownerId, jobId,
                List.of(new ImportResolutionOperation.ResolveTarget(itemId,
                        new ImportTarget.PersonTarget("9001", null))), 4))
                .isInstanceOf(BadRequestException.class);

        assertThat(item.getDecision()).isNull();
        assertThat(item.getResolvedTargetJson()).isNull();
        assertThat(job.getPreviewVersion()).isEqualTo(4);
        assertThat(job.isWorkPending()).isFalse();
    }

    @Test
    @DisplayName("[resolve] Should Reject A Typed Target - When Its Source Query Is Missing")
    void shouldRejectATypedTargetWhenItsSourceQueryIsMissing() {
        UUID ownerId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        ImportJob job = job(ownerId, jobId, 4, ImportJobState.READY);
        setPlanGeneration(job, 4);
        ImportItem item = item(itemId, jobId, 4, ImportRecordKind.DIARY_EVENT, "{}", 1);
        ImportTarget target = new ImportTarget.ContentTarget(MediaReference.movie("123"));
        when(importJobRepository.findOwnedByIdForUpdate(jobId, ownerId)).thenReturn(Optional.of(job));
        when(importItemRepository.findAllByJobIdAndPlanGenerationAndIdIn(eq(jobId), eq(4L), anyCollection()))
                .thenReturn(List.of(item));

        assertThatThrownBy(() -> service.resolve(ownerId, jobId,
                List.of(new ImportResolutionOperation.ResolveTarget(itemId, target)), 4))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Candidate query is invalid");

        assertThat(item.getDecision()).isNull();
        assertThat(item.getResolvedTargetJson()).isNull();
        assertThat(item.getPayloadJson()).isEqualTo("{}");
        assertThat(job.getPreviewVersion()).isEqualTo(4);
        assertThat(job.isWorkPending()).isFalse();
        verify(importItemRepository, never()).saveAll(anyCollection());
        verifyNoInteractions(externalContentAliasService);
    }

    @Test
    @DisplayName("[resolve] Should Reject A Candidate Envelope - When Its Schema Version Is Unsupported")
    void shouldRejectACandidateEnvelopeWhenItsSchemaVersionIsUnsupported() {
        assertRejectedTypedResolution("{\"schemaVersion\":2,\"query\":{"
                + "\"kind\":\"CONTENT\",\"title\":\"Example Movie\",\"year\":2001,"
                + "\"contentType\":\"MOVIE\"},\"candidates\":[]}");
    }

    @Test
    @DisplayName("[resolve] Should Reject A Candidate Envelope - When Its Query Is Missing And Candidates Are Empty")
    void shouldRejectACandidateEnvelopeWhenItsQueryIsMissingAndCandidatesAreEmpty() {
        assertRejectedTypedResolution("{\"schemaVersion\":1,\"candidates\":[]}");
    }

    @Test
    @DisplayName("[resolve] Should Reject A Candidate Envelope - When Its Query Is Not Typed")
    void shouldRejectACandidateEnvelopeWhenItsQueryIsNotTyped() {
        assertRejectedTypedResolution("{\"schemaVersion\":1,\"query\":{"
                + "\"kind\":\"ALIEN\",\"title\":\"Example Movie\"},\"candidates\":[]}");
    }

    @Test
    @DisplayName("[resolve] Should Reject A Candidate Envelope - When An Entry Has No Typed Target")
    void shouldRejectACandidateEnvelopeWhenAnEntryHasNoTypedTarget() {
        assertRejectedTypedResolution("{\"schemaVersion\":1,\"query\":{"
                + "\"kind\":\"CONTENT\",\"title\":\"Example Movie\",\"year\":2001,"
                + "\"contentType\":\"MOVIE\"},\"candidates\":[{\"candidateId\":\"candidate-1\"}]}");
    }

    @Test
    @DisplayName("[resolve] Should Reject A Candidate Envelope - When An Entry Has No Candidate Id")
    void shouldRejectACandidateEnvelopeWhenAnEntryHasNoCandidateId() {
        assertRejectedTypedResolution("{\"schemaVersion\":1,\"query\":{"
                + "\"kind\":\"CONTENT\",\"title\":\"Example Movie\",\"year\":2001,"
                + "\"contentType\":\"MOVIE\"},\"candidates\":[{\"target\":{"
                + "\"kind\":\"CONTENT\",\"content\":{\"type\":\"MOVIE\",\"tmdbId\":\"123\"}}}]}");
    }

    @Test
    @DisplayName("[resolve] Should Reject An Arbitrary Candidate Id - When It Is Not In The Stored Envelope")
    void shouldRejectAnArbitraryCandidateIdWhenItIsNotInTheStoredEnvelope() {
        UUID ownerId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        ImportJob job = job(ownerId, jobId, 4, ImportJobState.READY);
        setPlanGeneration(job, 4);
        ImportItem item = item(itemId, jobId, 4, ImportRecordKind.DIARY_EVENT, "{}", 1);
        item.setResolvedTargetJson("{\"schemaVersion\":1,\"query\":{"
                + "\"kind\":\"CONTENT\",\"title\":\"Example Movie\",\"year\":2001,"
                + "\"contentType\":\"MOVIE\"},\"candidates\":[{\"candidateId\":\"candidate-1\","
                + "\"target\":{\"kind\":\"CONTENT\",\"content\":{\"type\":\"MOVIE\","
                + "\"tmdbId\":\"123\"}}}]}");
        when(importJobRepository.findOwnedByIdForUpdate(jobId, ownerId)).thenReturn(Optional.of(job));
        when(importItemRepository.findAllByJobIdAndPlanGenerationAndIdIn(eq(jobId), eq(4L), anyCollection()))
                .thenReturn(List.of(item));

        assertThatThrownBy(() -> service.resolve(ownerId, jobId,
                List.of(new ImportResolutionOperation.ResolveTarget(itemId, "arbitrary-id")), 4))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Candidate does not belong to this import item");

        assertThat(item.getDecision()).isNull();
        assertThat(job.getPreviewVersion()).isEqualTo(4);
        verify(importItemRepository, never()).saveAll(anyCollection());
        verifyNoInteractions(externalContentAliasService);
    }

    private void assertRejectedTypedResolution(String candidateEnvelope) {
        UUID ownerId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        ImportJob job = job(ownerId, jobId, 4, ImportJobState.READY);
        setPlanGeneration(job, 4);
        ImportItem item = item(itemId, jobId, 4, ImportRecordKind.DIARY_EVENT, "{}", 1);
        item.setResolvedTargetJson(candidateEnvelope);
        ImportTarget target = new ImportTarget.ContentTarget(MediaReference.movie("123"));
        when(importJobRepository.findOwnedByIdForUpdate(jobId, ownerId)).thenReturn(Optional.of(job));
        when(importItemRepository.findAllByJobIdAndPlanGenerationAndIdIn(eq(jobId), eq(4L), anyCollection()))
                .thenReturn(List.of(item));

        assertThatThrownBy(() -> service.resolve(ownerId, jobId,
                List.of(new ImportResolutionOperation.ResolveTarget(itemId, target)), 4))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Candidate envelope is invalid");

        assertThat(item.getDecision()).isNull();
        assertThat(job.getPreviewVersion()).isEqualTo(4);
        verify(importItemRepository, never()).saveAll(anyCollection());
        verifyNoInteractions(externalContentAliasService);
    }

    @Test
    @DisplayName("[resolve] Should Validate Every Item Before Mutation - When A Decision References Another Job")
    void shouldValidateEveryItemBeforeMutationWhenADecisionReferencesAnotherJob() {
        UUID ownerId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID validItemId = UUID.randomUUID();
        UUID foreignItemId = UUID.randomUUID();
        ImportJob job = job(ownerId, jobId, 4, ImportJobState.READY);
        setPlanGeneration(job, 4);
        ImportItem validItem = item(validItemId, jobId, 4, ImportRecordKind.DIARY_EVENT, "{}", 1);
        when(importJobRepository.findOwnedByIdForUpdate(jobId, ownerId)).thenReturn(Optional.of(job));
        when(importItemRepository.findAllByJobIdAndPlanGenerationAndIdIn(
                eq(jobId), eq(4L), anyCollection())).thenReturn(List.of(validItem));

        assertThatThrownBy(() -> service.resolve(ownerId, jobId, List.of(
                new ImportResolutionOperation.SkipItem(validItemId),
                new ImportResolutionOperation.SkipItem(foreignItemId)), 4))
                .isInstanceOf(BadRequestException.class);

        assertThat(validItem.getDecision()).isNull();
        assertThat(job.getPreviewVersion()).isEqualTo(4);
        assertThat(job.isWorkPending()).isFalse();
    }

    @Test
    @DisplayName("[resolve] Should Hide Another User's List - When Linking An Imported List")
    void shouldHideAnotherUsersListWhenLinkingAnImportedList() {
        UUID ownerId = UUID.randomUUID();
        UUID anotherOwnerId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        UUID targetListId = UUID.randomUUID();
        ImportJob job = job(ownerId, jobId, 4, ImportJobState.READY);
        setPlanGeneration(job, 4);
        ImportItem item = item(itemId, jobId, 4, ImportRecordKind.LIST_METADATA, "{}", 1);
        UserList anotherOwnersList = mock(UserList.class);
        User anotherOwner = mock(User.class);
        when(importJobRepository.findOwnedByIdForUpdate(jobId, ownerId)).thenReturn(Optional.of(job));
        when(importItemRepository.findAllByJobIdAndPlanGenerationAndIdIn(eq(jobId), eq(4L), anyCollection()))
                .thenReturn(List.of(item));
        when(userListRepository.findById(targetListId)).thenReturn(Optional.of(anotherOwnersList));
        when(anotherOwnersList.getUser()).thenReturn(anotherOwner);
        when(anotherOwner.getId()).thenReturn(anotherOwnerId);

        assertThatThrownBy(() -> service.resolve(ownerId, jobId,
                List.of(new ImportResolutionOperation.LinkImportedList(itemId, targetListId)), 4))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("User list not found");

        assertThat(item.getDecision()).isNull();
        assertThat(job.getPreviewVersion()).isEqualTo(4);
        assertThat(job.isWorkPending()).isFalse();
    }

    @Test
    @DisplayName("[preview] Should Return Current Generation Items - When The Owner Requests A Page")
    void shouldReturnCurrentGenerationItemsWhenTheOwnerRequestsAPage() {
        UUID ownerId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        ImportJob job = job(ownerId, jobId, 4, ImportJobState.READY);
        setPlanGeneration(job, 4);
        ImportItem item = item(itemId, jobId, 4, ImportRecordKind.LIST_ITEM, "{}", 1);
        when(importJobRepository.findOwnedById(jobId, ownerId)).thenReturn(Optional.of(job));
        when(importItemRepository.findByJobIdAndPlanGenerationOrderByOrdinalAscIdAsc(
                jobId, 4, PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of(item), PageRequest.of(0, 20), 1));

        var result = service.preview(ownerId, jobId, 0, 20);

        assertThat(result.getContent()).containsExactly(
                new com.watchwise.watchwise_api.platformimport.dto.ImportPreviewItemDTO(
                        itemId, 1, ImportRecordKind.LIST_ITEM, null, null, null, null, null, null, null,
                        null, null, false, null, null, false, null, null, false, null));
        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("[preview] Should Expose Watch Scope For Deferred Replay - When The Pending Opinion Targets A Series")
    void shouldExposeExactWatchScopePreviewForDeferredReplayItems() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        ImportJob job = job(ownerId, jobId, 4, ImportJobState.READY);
        setPlanGeneration(job, 4);
        ImportItem replay = ImportItem.builder().id(itemId).jobId(jobId).planGeneration(4)
                .normalizedKind(ImportRecordKind.REVIEW).sourceNamespace("trakt")
                .objectKind("deferred_opinion_replay").sourceId("deferred-id")
                .payloadJson("{}").ordinal(1)
                .resolvedTargetJson(objectMapper.writeValueAsString(
                        new ImportTarget.ContentTarget(MediaReference.series("123"))))
                .build();
        seedWatchScopeSnapshot(replay, MediaReference.series("123"), "schedule-v1",
                Set.of(new EpisodeCoordinate(1, 1), new EpisodeCoordinate(1, 2)));
        when(importJobRepository.findOwnedById(jobId, ownerId)).thenReturn(Optional.of(job));
        when(importItemRepository.findByJobIdAndPlanGenerationOrderByOrdinalAscIdAsc(
                jobId, 4, PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of(replay), PageRequest.of(0, 20), 1));
        var preview = service.preview(ownerId, jobId, 0, 20).getContent().getFirst();

        assertThat(preview.watchScopePreview()).isNotNull();
        assertThat(preview.watchScopePreview().available()).isTrue();
        assertThat(preview.watchScopePreview().scheduleVersion()).isEqualTo("schedule-v1");
        assertThat(preview.watchScopePreview().episodes())
                .containsExactly(new EpisodeCoordinate(1, 1), new EpisodeCoordinate(1, 2));
    }

    @Test
    @DisplayName("[preview] Should Project Review Text Safely - When The Diary Item Contains A Review")
    void shouldProjectReviewTextSafelyWhenTheDiaryItemContainsAReview() {
        UUID ownerId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID shortId = UUID.randomUUID();
        UUID longId = UUID.randomUUID();
        ImportJob job = job(ownerId, jobId, 4, ImportJobState.READY);
        setPlanGeneration(job, 4);
        ImportItem shortReview = item(shortId, jobId, 4, ImportRecordKind.DIARY_EVENT,
                "{\"review\":\"First<br>Second &amp; third\"}", 1);
        String unicodeReview = "x".repeat(19_999) + "🎬" + "y";
        ImportItem longReview = item(longId, jobId, 4, ImportRecordKind.SERIES_EXPANSION,
                "{\"review\":\"" + unicodeReview + "\"}", 2);
        when(importJobRepository.findOwnedById(jobId, ownerId)).thenReturn(Optional.of(job));
        when(importItemRepository.findByJobIdAndPlanGenerationOrderByOrdinalAscIdAsc(
                jobId, 4, PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of(shortReview, longReview), PageRequest.of(0, 20), 2));

        var result = service.preview(ownerId, jobId, 0, 20);

        assertThat(result.getContent()).extracting(
                com.watchwise.watchwise_api.platformimport.dto.ImportPreviewItemDTO::reviewText)
                .containsExactly("First\nSecond & third", "x".repeat(19_999));
        assertThat(result.getContent().get(1).reviewTextLength()).isEqualTo(20_002);
        assertThat(result.getContent().get(1).reviewTextTruncated()).isTrue();
    }

    @Test
    @DisplayName("[preview] Should Project List Notes With A Safe Length - When A List Item Has A Note")
    void shouldProjectListNotesWithSafeLengthWhenAListItemHasANote() {
        UUID ownerId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        ImportJob job = job(ownerId, jobId, 4, ImportJobState.READY);
        setPlanGeneration(job, 4);
        String note = "x".repeat(401);
        ImportItem item = item(itemId, jobId, 4, ImportRecordKind.LIST_ITEM,
                "{\"title\":\"Example\",\"description\":\"" + note + "\"}", 1);
        when(importJobRepository.findOwnedById(jobId, ownerId)).thenReturn(Optional.of(job));
        when(importItemRepository.findByJobIdAndPlanGenerationOrderByOrdinalAscIdAsc(
                jobId, 4, PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of(item), PageRequest.of(0, 20), 1));

        var result = service.preview(ownerId, jobId, 0, 20).getContent().getFirst();

        assertThat(result.itemDescription()).isEqualTo("x".repeat(400));
        assertThat(result.itemDescriptionLength()).isEqualTo(401);
        assertThat(result.itemDescriptionTruncated()).isTrue();
    }

    @Test
    @DisplayName("[preview] Should Project List Metadata Within Field Limits - When Name And Description Are Long")
    void shouldProjectListMetadataWithinFieldLimitsWhenNameAndDescriptionAreLong() {
        UUID ownerId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        ImportJob job = job(ownerId, jobId, 4, ImportJobState.READY);
        setPlanGeneration(job, 4);
        ImportItem item = item(itemId, jobId, 4, ImportRecordKind.LIST_METADATA,
                "{\"title\":\"" + "n".repeat(256) + "\",\"description\":\"" + "d".repeat(401) + "\"}", 1);
        when(importJobRepository.findOwnedById(jobId, ownerId)).thenReturn(Optional.of(job));
        when(importItemRepository.findByJobIdAndPlanGenerationOrderByOrdinalAscIdAsc(
                jobId, 4, PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of(item), PageRequest.of(0, 20), 1));

        var result = service.preview(ownerId, jobId, 0, 20).getContent().getFirst();

        assertThat(result.itemTitle()).isEqualTo("n".repeat(255));
        assertThat(result.itemTitleLength()).isEqualTo(256);
        assertThat(result.itemTitleTruncated()).isTrue();
        assertThat(result.itemDescription()).isEqualTo("d".repeat(400));
        assertThat(result.itemDescriptionLength()).isEqualTo(401);
        assertThat(result.itemDescriptionTruncated()).isTrue();
    }

    @Test
    @DisplayName("[resolve] Should Reject Incompatible Field Edits - When A Watchlist Item Uses Eligibility")
    void shouldRejectIncompatibleFieldEditsWhenAWatchlistItemUsesEligibility() {
        UUID ownerId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        ImportJob job = job(ownerId, jobId, 4, ImportJobState.READY);
        setPlanGeneration(job, 4);
        ImportItem item = item(itemId, jobId, 4, ImportRecordKind.WATCHLIST_ITEM, "{}", 1);
        when(importJobRepository.findOwnedByIdForUpdate(jobId, ownerId)).thenReturn(Optional.of(job));
        when(importItemRepository.findAllByJobIdAndPlanGenerationAndIdIn(eq(jobId), eq(4L), anyCollection()))
                .thenReturn(List.of(item));

        assertThatThrownBy(() -> service.resolve(ownerId, jobId,
                List.of(new ImportResolutionOperation.EditField(itemId,
                        ImportResolutionOperation.EditableField.ELIGIBILITY_START, "2026-01-01")), 4))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Edited field is not valid for this import item");

        assertThat(item.getDecision()).isNull();
        assertThat(job.getPreviewVersion()).isEqualTo(4);
        assertThat(job.isWorkPending()).isFalse();
    }

    @Test
    @DisplayName("[resolve] Should Preserve A Fixed Category Nominee - When Skip Would Empty Its Category")
    void shouldPreserveAFixedCategoryNomineeWhenSkipWouldEmptyItsCategory() {
        UUID ownerId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID optionId = UUID.randomUUID();
        String categorySourceId = "category-source-1";
        ImportJob job = job(ownerId, jobId, 4, ImportJobState.READY);
        setPlanGeneration(job, 4);
        ImportItem option = ImportItem.builder()
                .id(optionId)
                .jobId(jobId)
                .planGeneration(4)
                .normalizedKind(ImportRecordKind.TEMPLATE_OPTION)
                .sourceNamespace("watchwise-template")
                .objectKind("TEMPLATE_OPTION")
                .sourceId("nominee-1")
                .parentSourceId(categorySourceId)
                .payloadJson("{\"name\":\"Nominee\"}")
                .ordinal(1)
                .build();
        ImportItem category = ImportItem.builder()
                .id(UUID.randomUUID())
                .jobId(jobId)
                .planGeneration(4)
                .normalizedKind(ImportRecordKind.TEMPLATE_CATEGORY)
                .sourceNamespace("watchwise-template")
                .objectKind("TEMPLATE_CATEGORY")
                .sourceId(categorySourceId)
                .payloadJson("{\"optionMode\":\"FIXED\"}")
                .ordinal(0)
                .build();
        when(importJobRepository.findOwnedByIdForUpdate(jobId, ownerId)).thenReturn(Optional.of(job));
        when(importItemRepository.findAllByJobIdAndPlanGenerationAndIdIn(eq(jobId), eq(4L), anyCollection()))
                .thenReturn(List.of(option));
        when(importItemRepository.findAllByJobIdAndPlanGenerationAndSourceIdAndNormalizedKind(
                jobId, 4, categorySourceId, ImportRecordKind.TEMPLATE_CATEGORY)).thenReturn(List.of(category));
        when(importItemRepository.findAllByJobIdAndPlanGenerationAndParentSourceIdAndNormalizedKind(
                jobId, 4, categorySourceId, ImportRecordKind.TEMPLATE_OPTION)).thenReturn(List.of(option));
        when(importItemRepository.findAllByJobIdAndPlanGenerationAndSourceIdAndNormalizedKind(
                jobId, 4, categorySourceId, ImportRecordKind.TEMPLATE_CATEGORY)).thenReturn(List.of(category));
        when(importItemRepository.findAllByJobIdAndPlanGenerationAndParentSourceIdAndNormalizedKind(
                jobId, 4, categorySourceId, ImportRecordKind.TEMPLATE_OPTION)).thenReturn(List.of(option));

        assertThatThrownBy(() -> service.resolve(ownerId, jobId,
                List.of(new ImportResolutionOperation.SkipItem(optionId)), 4))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("A fixed template category must retain at least one nominee or be opened explicitly");

        assertThat(option.getDecision()).isNull();
        assertThat(job.getPreviewVersion()).isEqualTo(4);
    }

    @Test
    @DisplayName("[resolve] Should Reject Closing An Open Fixed Category - When Its Last Nominee Is Removed")
    void shouldRejectClosingAnOpenFixedCategoryWhenItsLastNomineeIsRemoved() {
        UUID ownerId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        UUID optionId = UUID.randomUUID();
        String categorySourceId = "category-source-1";
        ImportJob job = job(ownerId, jobId, 4, ImportJobState.READY);
        setPlanGeneration(job, 4);
        ImportItem category = ImportItem.builder()
                .id(categoryId)
                .jobId(jobId)
                .planGeneration(4)
                .normalizedKind(ImportRecordKind.TEMPLATE_CATEGORY)
                .sourceNamespace("watchwise-template")
                .objectKind("TEMPLATE_CATEGORY")
                .sourceId(categorySourceId)
                .payloadJson("{\"optionMode\":\"FIXED\"}")
                .ordinal(0)
                .build();
        ImportItem option = ImportItem.builder()
                .id(optionId)
                .jobId(jobId)
                .planGeneration(4)
                .normalizedKind(ImportRecordKind.TEMPLATE_OPTION)
                .sourceNamespace("watchwise-template")
                .objectKind("TEMPLATE_OPTION")
                .sourceId("nominee-1")
                .parentSourceId(categorySourceId)
                .payloadJson("{\"name\":\"Nominee\"}")
                .ordinal(1)
                .build();
        when(importJobRepository.findOwnedByIdForUpdate(jobId, ownerId)).thenReturn(Optional.of(job));
        when(importItemRepository.findAllByJobIdAndPlanGenerationAndIdIn(eq(jobId), eq(4L), anyCollection()))
                .thenReturn(List.of(category), List.of(category, option));
        when(importItemRepository.findAllByJobIdAndPlanGenerationAndSourceIdAndNormalizedKind(
                jobId, 4, categorySourceId, ImportRecordKind.TEMPLATE_CATEGORY)).thenReturn(List.of(category));
        when(importItemRepository.findAllByJobIdAndPlanGenerationAndParentSourceIdAndNormalizedKind(
                jobId, 4, categorySourceId, ImportRecordKind.TEMPLATE_OPTION)).thenReturn(List.of(option));

        service.resolve(ownerId, jobId,
                List.of(new ImportResolutionOperation.SetCategoryOpen(categoryId, true)), 4);

        assertThat(category.getDecision()).isEqualTo(ImportDecision.SET_CATEGORY_OPEN);
        assertThat(category.getPayloadJson()).contains("\"open\":true");

        assertThatThrownBy(() -> service.resolve(ownerId, jobId, List.of(
                new ImportResolutionOperation.SetCategoryOpen(categoryId, false),
                new ImportResolutionOperation.RemoveNominee(optionId)), 5))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("A fixed template category must retain at least one nominee or be opened explicitly");

        assertThat(category.getPayloadJson()).contains("\"open\":true");
        assertThat(option.getDecision()).isNull();
        assertThat(job.getPreviewVersion()).isEqualTo(5);
    }

    @Test
    @DisplayName("[resolve] Should Store Every Operation Mapping - When A Mixed Resolution Batch Is Applied")
    void shouldStoreEveryOperationMappingWhenAMixedResolutionBatchIsApplied() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        ImportJob job = job(ownerId, jobId, 4, ImportJobState.READY);
        setPlanGeneration(job, 4);

        ImportItem resolveTarget = item(UUID.randomUUID(), jobId, 4, ImportRecordKind.DIARY_EVENT,
                "{\"sourceQuery\":{\"kind\":\"CONTENT\",\"title\":\"Mapping Test\","
                        + "\"contentType\":\"MOVIE\"}}", 1);
        ImportItem skipItem = item(UUID.randomUUID(), jobId, 4, ImportRecordKind.DIARY_EVENT, "{}", 2);
        ImportItem editField = item(UUID.randomUUID(), jobId, 4, ImportRecordKind.DIARY_EVENT, "{}", 3);
        ImportItem assignPass = item(UUID.randomUUID(), jobId, 4, ImportRecordKind.DIARY_EVENT, "{}", 4);
        ImportItem watchScope = item(UUID.randomUUID(), jobId, 4, ImportRecordKind.WATCHED_SUMMARY, "{}", 5);
        watchScope.setResolvedTargetJson(objectMapper.writeValueAsString(
                new ImportTarget.ContentTarget(MediaReference.series("123"))));
        seedWatchScopeSnapshot(watchScope, MediaReference.series("123"), "schedule-v1",
                Set.of(new EpisodeCoordinate(1, 1)));
        ImportItem listMetadata = item(UUID.randomUUID(), jobId, 4, ImportRecordKind.LIST_METADATA, "{}", 6);
        UUID reviewId = UUID.randomUUID();
        UUID beforeReviewId = UUID.randomUUID();
        ImportItem review = ImportItem.builder()
                .id(reviewId).jobId(jobId).planGeneration(4).normalizedKind(ImportRecordKind.REVIEW)
                .sourceNamespace("trakt").objectKind("REVIEW").sourceId("review-1")
                .normalizedContentKey("same-content").payloadJson("{}").ordinal(7).build();
        ImportItem beforeReview = ImportItem.builder()
                .id(beforeReviewId).jobId(jobId).planGeneration(4).normalizedKind(ImportRecordKind.REVIEW)
                .sourceNamespace("trakt").objectKind("REVIEW").sourceId("review-2")
                .normalizedContentKey("same-content").payloadJson("{}").ordinal(8).build();
        String fixedCategorySourceId = "fixed-category";
        ImportItem nominee = ImportItem.builder()
                .id(UUID.randomUUID()).jobId(jobId).planGeneration(4)
                .normalizedKind(ImportRecordKind.TEMPLATE_OPTION).sourceNamespace("watchwise-template")
                .objectKind("TEMPLATE_OPTION").sourceId("nominee-1").parentSourceId(fixedCategorySourceId)
                .payloadJson("{}").ordinal(9).build();
        ImportItem otherNominee = ImportItem.builder()
                .id(UUID.randomUUID()).jobId(jobId).planGeneration(4)
                .normalizedKind(ImportRecordKind.TEMPLATE_OPTION).sourceNamespace("watchwise-template")
                .objectKind("TEMPLATE_OPTION").sourceId("nominee-2").parentSourceId(fixedCategorySourceId)
                .payloadJson("{}").ordinal(10).build();
        ImportItem fixedCategory = ImportItem.builder()
                .id(UUID.randomUUID()).jobId(jobId).planGeneration(4)
                .normalizedKind(ImportRecordKind.TEMPLATE_CATEGORY).sourceNamespace("watchwise-template")
                .objectKind("TEMPLATE_CATEGORY").sourceId(fixedCategorySourceId)
                .payloadJson("{\"optionMode\":\"FIXED\"}").ordinal(11).build();
        ImportItem removeCategory = item(UUID.randomUUID(), jobId, 4,
                ImportRecordKind.TEMPLATE_CATEGORY, "{}", 12);
        ImportItem setCategoryOpen = item(UUID.randomUUID(), jobId, 4,
                ImportRecordKind.TEMPLATE_CATEGORY, "{}", 13);
        List<ImportItem> allItems = List.of(resolveTarget, skipItem, editField, assignPass, watchScope,
                listMetadata, review, beforeReview, nominee, otherNominee, fixedCategory,
                removeCategory, setCategoryOpen);
        when(importJobRepository.findOwnedByIdForUpdate(jobId, ownerId)).thenReturn(Optional.of(job));
        when(importItemRepository.findAllByJobIdAndPlanGenerationAndIdIn(eq(jobId), eq(4L), anyCollection()))
                .thenAnswer(invocation -> {
                    Set<?> requestedIds = invocation.getArgument(2);
                    return allItems.stream().filter(candidate -> requestedIds.contains(candidate.getId())).toList();
                });
        when(importItemRepository.findAllByJobIdAndPlanGenerationAndSourceIdAndNormalizedKind(
                jobId, 4, fixedCategorySourceId, ImportRecordKind.TEMPLATE_CATEGORY))
                .thenReturn(List.of(fixedCategory));
        when(importItemRepository.findAllByJobIdAndPlanGenerationAndParentSourceIdAndNormalizedKind(
                jobId, 4, fixedCategorySourceId, ImportRecordKind.TEMPLATE_OPTION))
                .thenReturn(List.of(nominee, otherNominee));
        UUID targetListId = UUID.randomUUID();
        var listOwner = mock(com.watchwise.watchwise_api.user.entity.User.class);
        when(listOwner.getId()).thenReturn(ownerId);
        UserList linkedList = mock(UserList.class);
        when(linkedList.getUser()).thenReturn(listOwner);
        when(userListRepository.findById(targetListId)).thenReturn(Optional.of(linkedList));
        User scopeOwner = mock(User.class);
        when(scopeOwner.getPreferredRegion()).thenReturn("US");
        when(scopeOwner.getPreferredLanguage()).thenReturn("en-US");
        when(userRepository.findById(ownerId)).thenReturn(Optional.of(scopeOwner));
        List<ImportResolutionOperation> operations = List.of(
                new ImportResolutionOperation.ResolveTarget(resolveTarget.getId(),
                        new ImportTarget.ContentTarget(MediaReference.movie("123"))),
                new ImportResolutionOperation.SkipItem(skipItem.getId()),
                new ImportResolutionOperation.EditField(editField.getId(),
                        ImportResolutionOperation.EditableField.TITLE, "Edited"),
                new ImportResolutionOperation.AssignPass(assignPass.getId(), 2),
                new ImportResolutionOperation.ConfirmWatchScope(watchScope.getId(),
                        ImportResolutionOperation.WatchScope.ALL_RELEASED_EPISODES, "schedule-v1",
                        List.of(MediaReference.episode("123", 1, 1))),
                new ImportResolutionOperation.LinkImportedList(listMetadata.getId(), targetListId),
                new ImportResolutionOperation.ResolveReviewOrder(reviewId, beforeReviewId),
                new ImportResolutionOperation.RemoveNominee(nominee.getId()),
                new ImportResolutionOperation.RemoveCategory(removeCategory.getId()),
                new ImportResolutionOperation.SetCategoryOpen(setCategoryOpen.getId(), true));

        service.resolve(ownerId, jobId, operations, 4);

        assertDecisionOverlay(resolveTarget, ImportDecision.RESOLVE_TARGET, "resolveTarget");
        assertDecisionOverlay(skipItem, ImportDecision.SKIP_ITEM, "skipItem");
        assertThat(editField.getDecision()).isEqualTo(ImportDecision.EDIT_FIELD);
        assertThat(editField.getPayloadJson()).contains("\"fields\":{\"TITLE\":\"Edited\"}");
        assertDecisionOverlay(assignPass, ImportDecision.ASSIGN_PASS, "assignPass");
        assertDecisionOverlay(watchScope, ImportDecision.CONFIRM_WATCH_SCOPE, "confirmWatchScope");
        assertDecisionOverlay(listMetadata, ImportDecision.LINK_IMPORTED_LIST, "linkImportedList");
        assertDecisionOverlay(review, ImportDecision.RESOLVE_REVIEW_ORDER, "resolveReviewOrder");
        assertDecisionOverlay(nominee, ImportDecision.REMOVE_NOMINEE, "removeNominee");
        assertDecisionOverlay(removeCategory, ImportDecision.REMOVE_CATEGORY, "removeCategory");
        assertDecisionOverlay(setCategoryOpen, ImportDecision.SET_CATEGORY_OPEN, "setCategoryOpen");
    }

    private void assertDecisionOverlay(ImportItem item, ImportDecision decision, String overlayKey) {
        assertThat(item.getDecision()).isEqualTo(decision);
        assertThat(item.getPayloadJson()).contains("\"" + overlayKey + "\":");
    }

    @Test
    @DisplayName("[normalize] Should Emit Only Selected List Items - When The Registry Dispatches A List Selection")
    void shouldEmitOnlySelectedListItemsWhenTheRegistryDispatchesAListSelection() {
        ImportModeKey mode = new ImportModeKey(ImportInputKind.SINGLE_LIST, ImportSourceFormat.TRAKT_JSON);
        ImportModeHandler handler = new ImportModeHandler() {
            @Override
            public ImportModeKey mode() {
                return mode;
            }

            @Override
            public com.watchwise.watchwise_api.platformimport.model.ImportInventory inspect(ImportReadContext context) {
                return new com.watchwise.watchwise_api.platformimport.model.ImportInventory(List.of(), List.of());
            }

            @Override
            public void normalize(ImportReadContext context, ImportSelection selection,
                                  Consumer<NormalizedImportRecord> sink) {
                for (String listId : List.of("selected-list", "unselected-list")) {
                    if (selection.listIds().contains(listId)) {
                        sink.accept(new NormalizedImportRecord(UUID.randomUUID().toString(), ImportRecordKind.LIST_ITEM,
                                new ImportSourceKey("test", "LIST_ITEM", listId + "-item"), listId,
                                new ImportTarget.ContentTarget(MediaReference.movie("1")),
                                objectMapper.createObjectNode(), 1));
                    }
                }
            }
        };
        ImportModeRegistry registry = new ImportModeRegistry(List.of(handler));
        ImportReadContext context = new ImportReadContext(UUID.randomUUID(), UUID.randomUUID(), "private-key",
                "test", ZoneId.of("UTC"));
        List<NormalizedImportRecord> emitted = new ArrayList<>();

        registry.normalize(mode, context, new ImportSelection(false, false, Set.of("selected-list"), null), emitted::add);

        assertThat(emitted).extracting(record -> record.sourceKey().sourceId()).containsExactly("selected-list-item");
    }

    @Test
    @DisplayName("[registry] Should Remain Available - When No Mode Handlers Are Registered")
    void shouldRemainAvailableWhenNoModeHandlersAreRegistered() {
        assertThat(new ImportModeRegistry(List.of())).isNotNull();
    }

    private ImportJob job(UUID ownerId, UUID jobId, long previewVersion, ImportJobState state) {
        return ImportJob.builder()
                .id(jobId)
                .userId(ownerId)
                .inputKind(ImportInputKind.PLATFORM_ARCHIVE)
                .sourceFormat(ImportSourceFormat.TRAKT_ZIP)
                .platform(ImportPlatform.TRAKT)
                .sourceNamespace("trakt")
                .state(state)
                .version(9)
                .previewVersion(previewVersion)
                .selectionJson("{}")
                .timezone("UTC")
                .storageKey("private-key")
                .uploadSizeBytes(10)
                .workPending(false)
                .createdAt(LocalDateTime.of(2026, 10, 21, 0, 0))
                .updatedAt(LocalDateTime.of(2026, 10, 21, 0, 0))
                .build();
    }

    private void setPlanGeneration(ImportJob job, long generation) {
        job.setSelectionJson("{\"schemaVersion\":1,\"planGeneration\":" + generation
                + ",\"selection\":{\"history\":false,\"watchlist\":false,\"listIds\":[],\"metadata\":null}}");
    }

    private void seedWatchScopeSnapshot(ImportItem item, MediaReference target, String scheduleVersion,
                                        Set<EpisodeCoordinate> episodes) throws Exception {
        List<EpisodeCoordinate> ordered = episodes.stream()
                .sorted(java.util.Comparator.comparingInt(EpisodeCoordinate::seasonNumber)
                        .thenComparingInt(EpisodeCoordinate::episodeNumber)).toList();
        Instant validUntil = Instant.parse("2026-10-23T00:00:00Z");
        ConfirmedWatchScopeSnapshot snapshot = new ConfirmedWatchScopeSnapshot(target, scheduleVersion,
                validUntil, true, ordered, false, 0);
        ObjectNode envelope = objectMapper.createObjectNode().put("_watchwiseResolution", 1);
        envelope.set("source", objectMapper.readTree(item.getPayloadJson()));
        ObjectNode resolution = envelope.putObject("resolution");
        resolution.set("fields", objectMapper.createObjectNode());
        resolution.set("watchScopeSnapshot", objectMapper.valueToTree(snapshot));
        resolution.set("watchScopePreview", objectMapper.valueToTree(new ImportWatchScopePreviewDTO(
                true, null, scheduleVersion, validUntil, true, ordered)));
        item.setPayloadJson(objectMapper.writeValueAsString(envelope));
    }

    private ImportModeHandler emptyModeHandler(ImportModeKey mode) {
        return new ImportModeHandler() {
            @Override
            public ImportModeKey mode() {
                return mode;
            }

            @Override
            public ImportInventory inspect(ImportReadContext context) {
                return new ImportInventory(List.of(), List.of());
            }

            @Override
            public void normalize(ImportReadContext context, ImportSelection selection,
                                  Consumer<NormalizedImportRecord> sink) {
            }
        };
    }

    private ImportItem item(UUID itemId, UUID jobId, long generation, ImportRecordKind kind,
                            String payload, long ordinal) {
        return ImportItem.builder()
                .id(itemId)
                .jobId(jobId)
                .planGeneration(generation)
                .normalizedKind(kind)
                .sourceNamespace("trakt")
                .objectKind(kind.name())
                .sourceId("source-" + itemId)
                .payloadJson(payload)
                .ordinal(ordinal)
                .build();
    }

    private String expectedDigest(UUID jobId, long version, String selectionJson) {
        String canonicalTuple = "{\"jobId\":\"" + jobId + "\",\"previewVersion\":" + version
                + ",\"selection\":" + selectionJson + "}";
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonicalTuple.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
