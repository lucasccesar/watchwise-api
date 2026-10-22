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
import com.watchwise.watchwise_api.content.dto.MediaReference;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.platformimport.repository.ImportItemRepository;
import com.watchwise.watchwise_api.platformimport.repository.ImportJobRepository;
import com.watchwise.watchwise_api.userlist.repository.UserListRepository;
import com.watchwise.watchwise_api.userlist.entity.UserList;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.platformimport.service.impl.ImportPreviewServiceImpl;
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
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;

@ExtendWith(MockitoExtension.class)
class ImportPreviewServiceTest {
    @Mock
    private ImportJobRepository importJobRepository;

    @Mock
    private ImportItemRepository importItemRepository;

    @Mock
    private ImportModeRegistry importModeRegistry;

    @Mock
    private ExternalContentAliasService externalContentAliasService;

    @Mock
    private UserListRepository userListRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ImportLimits importLimits = new ImportLimits();
    private final PageRequestFactory pageRequestFactory = new PageRequestFactory();
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"), ZoneId.of("UTC"));

    private ImportPreviewService service;

    @BeforeEach
    void setUp() {
        service = new ImportPreviewServiceImpl(importJobRepository, importItemRepository, importModeRegistry,
                externalContentAliasService, userListRepository, objectMapper, importLimits, clock, pageRequestFactory);
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
        assertThat(result.version()).isEqualTo(5);
        assertThat(result.digest()).isEqualTo(expectedDigest(jobId, 5, selectionJson));
        verify(importJobRepository).findOwnedByIdForUpdate(jobId, ownerId);
        verifyNoInteractions(importItemRepository);
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
        setPlanGeneration(job, 4);
        ImportItem item = item(itemId, jobId, 4, ImportRecordKind.DIARY_EVENT,
                "{\"title\":\"Original title\",\"score\":7}", 1);
        ImportTarget target = new ImportTarget.ContentTarget(MediaReference.movie("123"));
        when(importJobRepository.findOwnedByIdForUpdate(jobId, ownerId)).thenReturn(Optional.of(job));
        when(importItemRepository.findAllByJobIdAndPlanGenerationAndIdIn(eq(jobId), eq(4L), anyCollection()))
                .thenReturn(List.of(item));

        var result = service.resolve(ownerId, jobId,
                List.of(new ImportResolutionOperation.ResolveTarget(itemId, target)), 4);

        assertThat(job.getPreviewVersion()).isEqualTo(5);
        assertThat(job.getState()).isEqualTo(ImportJobState.RESOLVING);
        assertThat(job.isWorkPending()).isTrue();
        assertThat(item.getDecision()).isEqualTo(ImportDecision.RESOLVE_TARGET);
        assertThat(item.getResolvedTargetJson()).contains("123");
        assertThat(item.getPayloadJson()).contains("Original title");
        assertThat(result.version()).isEqualTo(5);
        assertThat(result.digest()).isEqualTo(expectedDigest(jobId, 5, job.getSelectionJson()));
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
                        itemId, ImportRecordKind.LIST_ITEM, null, null));
        assertThat(result.getTotalElements()).isEqualTo(1);
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
