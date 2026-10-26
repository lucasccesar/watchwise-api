package com.watchwise.watchwise_api.platformimport.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.exception.TmdbUnavailableException;
import com.watchwise.watchwise_api.common.pagination.PageRequestFactory;
import com.watchwise.watchwise_api.calendar.entity.CalendarScheduleCompleteness;
import com.watchwise.watchwise_api.calendar.repository.CalendarScheduleCompletenessRepository;
import com.watchwise.watchwise_api.content.dto.MediaReference;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.pickstemplate.entity.PickAllowedType;
import com.watchwise.watchwise_api.platformimport.dto.ImportPreviewItemDTO;
import com.watchwise.watchwise_api.platformimport.dto.ImportWatchScopePreviewDTO;
import com.watchwise.watchwise_api.platformimport.entity.ImportDeferredRecord;
import com.watchwise.watchwise_api.platformimport.entity.ImportItem;
import com.watchwise.watchwise_api.platformimport.entity.ImportJob;
import com.watchwise.watchwise_api.platformimport.model.ImportDecision;
import com.watchwise.watchwise_api.platformimport.model.ConfirmedWatchScopeSnapshot;
import com.watchwise.watchwise_api.platformimport.model.ImportDatePrecision;
import com.watchwise.watchwise_api.platformimport.model.ImportInputKind;
import com.watchwise.watchwise_api.platformimport.model.ImportJobState;
import com.watchwise.watchwise_api.platformimport.model.AliasQuery;
import com.watchwise.watchwise_api.platformimport.model.AliasTrust;
import com.watchwise.watchwise_api.platformimport.model.ImportModeKey;
import com.watchwise.watchwise_api.platformimport.model.ImportPlanVersion;
import com.watchwise.watchwise_api.platformimport.model.ImportRecordKind;
import com.watchwise.watchwise_api.platformimport.model.ImportResolutionOperation;
import com.watchwise.watchwise_api.platformimport.model.ImportSelection;
import com.watchwise.watchwise_api.platformimport.model.ImportTarget;
import com.watchwise.watchwise_api.platformimport.model.NormalizedImportRecord;
import com.watchwise.watchwise_api.diarycompletion.model.EpisodeCoordinate;
import com.watchwise.watchwise_api.platformimport.repository.ImportItemRepository;
import com.watchwise.watchwise_api.platformimport.repository.ImportDeferredRecordRepository;
import com.watchwise.watchwise_api.platformimport.repository.ImportJobRepository;
import com.watchwise.watchwise_api.platformimport.service.ImportLimits;
import com.watchwise.watchwise_api.platformimport.service.ExternalContentAliasService;
import com.watchwise.watchwise_api.platformimport.service.ImportModeRegistry;
import com.watchwise.watchwise_api.platformimport.service.ImportPreviewService;
import com.watchwise.watchwise_api.userlist.entity.UserList;
import com.watchwise.watchwise_api.userlist.repository.UserListRepository;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.NullNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class ImportPreviewServiceImpl implements ImportPreviewService {
    private static final int STORED_PLAN_SCHEMA_VERSION = 1;
    private static final int RESOLUTION_OVERLAY_SCHEMA_VERSION = 1;
    private static final int MAX_RESOLUTION_OPERATIONS = 200;
    private static final String RESOLUTION_OVERLAY_MARKER = "_watchwiseResolution";
    private static final Set<ImportJobState> MUTABLE_JOB_STATES = Set.of(
            ImportJobState.AWAITING_SELECTION,
            ImportJobState.ANALYZING,
            ImportJobState.RESOLVING,
            ImportJobState.READY);

    private final ImportJobRepository importJobRepository;
    private final ImportItemRepository importItemRepository;
    private final ImportDeferredRecordRepository deferredRecordRepository;
    private final DeferredOpinionReplayStager deferredOpinionReplayStager;
    private final CalendarScheduleCompletenessRepository scheduleCompletenessRepository;
    private final ImportModeRegistry importModeRegistry;
    private final ExternalContentAliasService externalContentAliasService;
    private final UserListRepository userListRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final ImportedReviewTextNormalizer reviewTextNormalizer;
    private final ImportLimits importLimits;
    private final Clock clock;
    private final PageRequestFactory pageRequestFactory;

    public ImportPreviewServiceImpl(
            ImportJobRepository importJobRepository,
            ImportItemRepository importItemRepository,
            ImportDeferredRecordRepository deferredRecordRepository,
            DeferredOpinionReplayStager deferredOpinionReplayStager,
            CalendarScheduleCompletenessRepository scheduleCompletenessRepository,
            ImportModeRegistry importModeRegistry,
            ExternalContentAliasService externalContentAliasService,
            UserListRepository userListRepository,
            UserRepository userRepository,
            ObjectMapper objectMapper,
            ImportedReviewTextNormalizer reviewTextNormalizer,
            ImportLimits importLimits,
            Clock clock,
            PageRequestFactory pageRequestFactory) {
        this.importJobRepository = importJobRepository;
        this.importItemRepository = importItemRepository;
        this.deferredRecordRepository = deferredRecordRepository;
        this.deferredOpinionReplayStager = deferredOpinionReplayStager;
        this.scheduleCompletenessRepository = scheduleCompletenessRepository;
        this.importModeRegistry = importModeRegistry;
        this.externalContentAliasService = externalContentAliasService;
        this.userListRepository = userListRepository;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
        this.reviewTextNormalizer = reviewTextNormalizer;
        this.importLimits = importLimits;
        this.clock = clock;
        this.pageRequestFactory = pageRequestFactory;
    }

    @Override
    @Transactional
    public ImportPlanVersion select(UUID ownerId, UUID jobId, ImportSelection selection, long expectedVersion) {
        requireOwnerAndJob(ownerId, jobId);
        ImportJob job = lockOwnedJob(ownerId, jobId);
        requireExpectedVersion(job, expectedVersion);
        requireMutable(job);
        validateSelection(job, selection);
        importModeRegistry.require(new ImportModeKey(job.getInputKind(), job.getSourceFormat()));
        bindSourceAccount(job, selection);

        long nextVersion = nextVersion(job.getPreviewVersion());
        long nextGeneration = nextVersion(readPlanGeneration(job.getSelectionJson()));
        String storedSelection = serializeStoredPlan(selection, nextGeneration);
        LocalDateTime now = LocalDateTime.now(clock);

        job.setPreviewVersion(nextVersion);
        job.setSelectionJson(storedSelection);
        job.setCheckpoint(null);
        boolean normalizeSelection = job.getInputKind() == ImportInputKind.PLATFORM_ARCHIVE
                || job.getInputKind() == ImportInputKind.SINGLE_LIST
                || job.getInputKind() == ImportInputKind.SINGLE_WATCHLIST;
        job.setState(normalizeSelection ? ImportJobState.ANALYZING : ImportJobState.RESOLVING);
        job.setWorkPhase(normalizeSelection ? "NORMALIZE" : "RESOLVE");
        job.setWorkPending(true);
        job.setNextRunAt(now);
        job.setUpdatedAt(now);
        job.setLeaseToken(null);
        job.setLeaseUntil(null);
        return version(job, nextVersion);
    }

    @Override
    @Transactional
    public ImportPlanVersion resolve(UUID ownerId, UUID jobId, List<ImportResolutionOperation> decisions,
                                     long expectedVersion) {
        requireOwnerAndJob(ownerId, jobId);
        ImportJob job = lockOwnedJob(ownerId, jobId);
        requireExpectedVersion(job, expectedVersion);
        requireMutable(job);
        if (job.getState() != ImportJobState.READY && job.getState() != ImportJobState.RESOLVING) {
            throw new ConflictException("Import plan is not ready for resolutions");
        }
        if (decisions == null || decisions.isEmpty()) {
            throw new BadRequestException("At least one import resolution is required");
        }
        if (decisions.size() > MAX_RESOLUTION_OPERATIONS) {
            throw new BadRequestException("A resolution request cannot contain more than 200 operations");
        }

        long generation = readPlanGeneration(job.getSelectionJson());
        LinkedHashSet<UUID> requestedIds = collectItemIds(decisions);
        Map<UUID, ImportItem> itemsById = loadAndValidateItems(jobId, generation, requestedIds);
        Set<UUID> changedItemIds = new LinkedHashSet<>();
        Map<UUID, PreparedTargetResolution> preparedTargets = new LinkedHashMap<>();
        Map<UUID, ConfirmedWatchScopeSnapshot> confirmedWatchScopes = new LinkedHashMap<>();
        for (ImportResolutionOperation decision : decisions) {
            if (!changedItemIds.add(decision.itemId())) {
                throw new BadRequestException("Only one resolution per item is allowed in a request");
            }
            PreparedTargetResolution preparedTarget = validateOperation(
                    ownerId, decision, itemsById, confirmedWatchScopes);
            if (preparedTarget != null) preparedTargets.put(decision.itemId(), preparedTarget);
        }
        validateFixedCategoryConstraints(jobId, generation, decisions, itemsById);
        validateTemplateCategoryConstraints(jobId, generation, decisions);

        for (ImportResolutionOperation decision : decisions) {
            applyDecision(ownerId, job, decision, itemsById, preparedTargets.get(decision.itemId()),
                    confirmedWatchScopes.get(decision.itemId()));
        }
        validateTemplateEligibilityPlan(decisions, itemsById);
        importItemRepository.saveAll(changedItems(decisions, itemsById));

        long nextVersion = nextVersion(job.getPreviewVersion());
        LocalDateTime now = LocalDateTime.now(clock);
        job.setPreviewVersion(nextVersion);
        job.setState(ImportJobState.RESOLVING);
        job.setWorkPhase("RESOLVE");
        job.setWorkPending(true);
        job.setNextRunAt(now);
        job.setUpdatedAt(now);
        job.setLeaseToken(null);
        job.setLeaseUntil(null);
        return version(job, nextVersion);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ImportPreviewItemDTO> preview(UUID ownerId, UUID jobId, int page, int size) {
        requireOwnerAndJob(ownerId, jobId);
        ImportJob job = importJobRepository.findOwnedById(jobId, ownerId)
                .orElseThrow(() -> new NotFoundException("Import job not found"));
        long generation = readPlanGeneration(job.getSelectionJson());
        PageRequest pageable = pageRequestFactory.build(page, size);
        return importItemRepository.findByJobIdAndPlanGenerationOrderByOrdinalAscIdAsc(
                        jobId, generation, pageable)
                .map(item -> toPreviewItem(job, item));
    }

    @Override
    @Transactional(readOnly = true)
    public ImportPlanVersion currentVersion(UUID ownerId, UUID jobId) {
        requireOwnerAndJob(ownerId, jobId);
        ImportJob job = importJobRepository.findOwnedById(jobId, ownerId)
                .orElseThrow(() -> new NotFoundException("Import job not found"));
        return version(job, job.getPreviewVersion());
    }

    private ImportJob lockOwnedJob(UUID ownerId, UUID jobId) {
        return importJobRepository.findOwnedByIdForUpdate(jobId, ownerId)
                .orElseThrow(() -> new NotFoundException("Import job not found"));
    }

    private void requireOwnerAndJob(UUID ownerId, UUID jobId) {
        if (ownerId == null || jobId == null) {
            throw new BadRequestException("Import owner and job are required");
        }
    }

    private void requireExpectedVersion(ImportJob job, long expectedVersion) {
        if (expectedVersion < 0 || job.getPreviewVersion() != expectedVersion) {
            throw new ConflictException("Import preview has changed");
        }
    }

    private void requireMutable(ImportJob job) {
        if (job.getExpiresAt() != null && !job.getExpiresAt().isAfter(LocalDateTime.now(clock))) {
            throw new ConflictException("Import job has expired");
        }
        if (!MUTABLE_JOB_STATES.contains(job.getState())) {
            throw new ConflictException("Import job cannot change its preview in the current state");
        }
    }

    private long nextVersion(long current) {
        try {
            return Math.addExact(current, 1);
        } catch (ArithmeticException exception) {
            throw new ConflictException("Import preview version is exhausted");
        }
    }

    private void validateSelection(ImportJob job, ImportSelection selection) {
        if (selection == null) throw new BadRequestException("Import selection is required");
        if (selection.listIds().size() > importLimits.maxEntries()) {
            throw new BadRequestException("Too many imported lists were selected");
        }
        if (selection.listIds().stream().anyMatch(id -> id == null || id.isBlank() || id.length() > 1000)) {
            throw new BadRequestException("Selected list identifiers are invalid");
        }
        if (selection.metadata() != null) {
            if (selection.metadata().toString().getBytes(StandardCharsets.UTF_8).length > importLimits.maxFieldBytes()) {
                throw new BadRequestException("Selection metadata is too large");
            }
            if (jsonDepth(selection.metadata()) > importLimits.maxJsonDepth()) {
                throw new BadRequestException("Selection metadata exceeds the maximum JSON depth");
            }
        }
        switch (job.getInputKind()) {
            case PLATFORM_ARCHIVE -> {
                if (!selection.history() && !selection.watchlist() && selection.listIds().isEmpty()) {
                    throw new BadRequestException("At least one import category or list must be selected");
                }
            }
            case SINGLE_LIST, SINGLE_WATCHLIST, PICKS_TEMPLATE -> {
                if (selection.history() || selection.watchlist() || !selection.listIds().isEmpty()) {
                    throw new BadRequestException("This import mode does not accept archive category selection");
                }
            }
        }
    }

    private void bindSourceAccount(ImportJob job, ImportSelection selection) {
        JsonNode metadata = selection.metadata();
        boolean suppliedInSelection = metadata != null && metadata.isObject() && metadata.has("sourceAccount");
        boolean ownerFallback = job.getPlatform() != null
                && (job.getPlatform().name() + ":OWNER:" + job.getUserId()).equals(job.getSourceNamespace());
        if (!suppliedInSelection) {
            if (job.getInputKind() == ImportInputKind.PLATFORM_ARCHIVE
                    && job.getSourceAccountId() == null && ownerFallback) {
                throw new BadRequestException(
                        "Source account identifier is required before archive normalization");
            }
            return;
        }
        JsonNode value = metadata.get("sourceAccount");
        if (!value.isTextual()) throw new BadRequestException("Source account identifier must be text");
        String account = value.asString().trim();
        if (account.isEmpty() || account.length() > 220
                || account.chars().anyMatch(Character::isISOControl)) {
            throw new BadRequestException("Source account identifier is invalid");
        }
        if (job.getPlatform() == null || (job.getInputKind() != ImportInputKind.PLATFORM_ARCHIVE
                && job.getInputKind() != ImportInputKind.SINGLE_LIST)) {
            throw new BadRequestException("Source account identifier is only valid for platform lists or archives");
        }
        if (job.getSourceAccountId() != null) {
            if (!job.getSourceAccountId().equals(account)) {
                throw new BadRequestException("Source account does not match the uploaded account");
            }
            return;
        }
        if (job.getInputKind() == ImportInputKind.PLATFORM_ARCHIVE
                && (job.getState() != ImportJobState.AWAITING_SELECTION
                || importItemRepository.countByJobId(job.getId()) != 0)) {
            throw new ConflictException("Source account can only be bound before normalization");
        }
        job.setSourceAccountId(account);
        job.setSourceNamespace(job.getPlatform().name() + ":ACCOUNT:" + account);
    }

    private int jsonDepth(JsonNode node) {
        if (node == null || node.isValueNode()) return 1;
        int childDepth = 0;
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> property : node.properties()) {
                childDepth = Math.max(childDepth, jsonDepth(property.getValue()));
            }
        } else if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) {
                childDepth = Math.max(childDepth, jsonDepth(node.get(index)));
            }
        }
        return childDepth + 1;
    }

    private String serializeStoredPlan(ImportSelection selection, long planGeneration) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("schemaVersion", STORED_PLAN_SCHEMA_VERSION);
        root.put("planGeneration", planGeneration);
        ObjectNode selectionNode = root.putObject("selection");
        selectionNode.put("history", selection.history());
        selectionNode.put("watchlist", selection.watchlist());
        ArrayNode listIds = selectionNode.putArray("listIds");
        selection.listIds().stream().sorted().forEach(listIds::add);
        if (selection.metadata() == null) selectionNode.putNull("metadata");
        else selectionNode.set("metadata", canonicalizeJson(selection.metadata()));
        return writeJson(root);
    }

    private JsonNode canonicalizeJson(JsonNode node) {
        if (node.isObject()) {
            ObjectNode canonical = objectMapper.createObjectNode();
            List<Map.Entry<String, JsonNode>> properties = new ArrayList<>();
            node.properties().forEach(properties::add);
            properties.sort(Map.Entry.comparingByKey());
            for (Map.Entry<String, JsonNode> property : properties) {
                canonical.set(property.getKey(), canonicalizeJson(property.getValue()));
            }
            return canonical;
        }
        if (node.isArray()) {
            ArrayNode canonical = objectMapper.createArrayNode();
            for (int index = 0; index < node.size(); index++) {
                canonical.add(canonicalizeJson(node.get(index)));
            }
            return canonical;
        }
        return node.deepCopy();
    }

    private long readPlanGeneration(String selectionJson) {
        if (selectionJson == null || selectionJson.isBlank()) return 0;
        try {
            JsonNode plan = objectMapper.readTree(selectionJson);
            JsonNode generation = plan.get("planGeneration");
            if (generation == null || generation.isNull()) return 0;
            long value = generation.asLong(-1);
            if (value < 0) throw new BadRequestException("Stored import generation is invalid");
            return value;
        } catch (JacksonException exception) {
            throw new IllegalStateException("Stored import selection is invalid", exception);
        }
    }

    private Map<UUID, ImportItem> loadAndValidateItems(UUID jobId, long generation, Set<UUID> requestedIds) {
        if (requestedIds.isEmpty()) throw new BadRequestException("Import resolution items are required");
        List<ImportItem> items = importItemRepository.findAllByJobIdAndPlanGenerationAndIdIn(
                jobId, generation, requestedIds);
        Map<UUID, ImportItem> itemsById = new LinkedHashMap<>();
        items.forEach(item -> itemsById.put(item.getId(), item));
        if (itemsById.size() != requestedIds.size()) {
            throw new BadRequestException("One or more resolution items do not belong to this import plan");
        }
        return itemsById;
    }

    private LinkedHashSet<UUID> collectItemIds(List<ImportResolutionOperation> decisions) {
        LinkedHashSet<UUID> ids = new LinkedHashSet<>();
        for (ImportResolutionOperation decision : decisions) {
            if (decision == null || decision.itemId() == null) {
                throw new BadRequestException("Resolution item id is required");
            }
            ids.add(decision.itemId());
            if (decision instanceof ImportResolutionOperation.ResolveReviewOrder order) {
                if (order.beforeItemId() == null) throw new BadRequestException("Review ordering target is required");
                ids.add(order.beforeItemId());
            }
        }
        return ids;
    }

    private PreparedTargetResolution validateOperation(
            UUID ownerId, ImportResolutionOperation operation, Map<UUID, ImportItem> items,
            Map<UUID, ConfirmedWatchScopeSnapshot> confirmedWatchScopes) {
        ImportItem item = requireItem(operation.itemId(), items);
        if (operation instanceof ImportResolutionOperation.ResolveTarget resolveTarget) {
            if (isPreparedSeriesExpansion(item)) {
                throw new BadRequestException(
                        "Series target is frozen after episode expansion; cancel and create a new import");
            }
            return prepareTargetResolution(item, resolveTarget);
        } else if (operation instanceof ImportResolutionOperation.EditField editField) {
            if (isPreparedSeriesExpansion(item)
                    && editField.field() == ImportResolutionOperation.EditableField.WATCHED_DATE) {
                throw new BadRequestException(
                        "Series watch date is frozen after episode expansion; cancel and create a new import");
            }
            validateEditedField(item, editField);
        } else if (operation instanceof ImportResolutionOperation.AssignPass assignPass) {
            if (item.getNormalizedKind() == ImportRecordKind.SERIES_EXPANSION) {
                throw new BadRequestException("Assign pass to the expanded episode items, not the series parent");
            }
            if (assignPass.passNumber() < 1 || item.getNormalizedKind() != ImportRecordKind.DIARY_EVENT) {
                throw new BadRequestException("Pass assignment is not valid for this import item");
            }
        } else if (operation instanceof ImportResolutionOperation.ConfirmWatchScope confirmScope) {
            if (item.getNormalizedKind() != ImportRecordKind.WATCHED_SUMMARY
                    && item.getNormalizedKind() != ImportRecordKind.WATCHED_MARKER
                    && !"deferred_opinion_replay".equals(item.getObjectKind())
                    && item.getNormalizedKind() != ImportRecordKind.SERIES_EXPANSION) {
                throw new BadRequestException("Watch scope is not valid for this import item");
            }
            if (item.getNormalizedKind() == ImportRecordKind.SERIES_EXPANSION) {
                throw new BadRequestException("Series expansion uses the complete released episode set");
            }
            MediaReference scopeTarget = confirmedWatchScopeTarget(item);
            if (confirmScope.scope() == ImportResolutionOperation.WatchScope.SPECIFIC_EPISODES) {
                String targetSeriesId = scopeTarget.type() == ContentType.SERIES
                        ? scopeTarget.tmdbId() : scopeTarget.seriesTmdbId();
                if (confirmScope.episodeReferences().stream()
                        .anyMatch(reference -> !targetSeriesId.equals(reference.seriesTmdbId())
                                || (scopeTarget.type() == ContentType.SEASON
                                && !scopeTarget.seasonNumber().equals(reference.seasonNumber())))) {
                    throw new BadRequestException(
                            "Watch scope episodes must belong to the resolved series or season");
                }
            }
            confirmedWatchScopes.put(item.getId(),
                    validateWatchScopeSnapshot(ownerId, item, scopeTarget, confirmScope));
        } else if (operation instanceof ImportResolutionOperation.LinkImportedList linkList) {
            if (item.getNormalizedKind() != ImportRecordKind.LIST_METADATA) {
                throw new BadRequestException("Only an imported list can be linked to an existing list");
            }
            if (linkList.targetListId() == null) throw new BadRequestException("Target list id is required");
            UserList targetList = userListRepository.findById(linkList.targetListId())
                    .orElseThrow(() -> new NotFoundException("User list not found"));
            if (targetList.getUser() == null || !ownerId.equals(targetList.getUser().getId())) {
                throw new NotFoundException("User list not found");
            }
        } else if (operation instanceof ImportResolutionOperation.ResolveReviewOrder order) {
            validateReviewOrder(item, requireItem(order.beforeItemId(), items));
        } else if (operation instanceof ImportResolutionOperation.RemoveNominee) {
            requireKind(item, ImportRecordKind.TEMPLATE_OPTION, "Only a template nominee can be removed");
        } else if (operation instanceof ImportResolutionOperation.RemoveCategory
                || operation instanceof ImportResolutionOperation.SetCategoryOpen) {
            requireKind(item, ImportRecordKind.TEMPLATE_CATEGORY, "Only a template category can be changed");
        } else if (operation instanceof ImportResolutionOperation.SkipItem
                && item.getNormalizedKind() == ImportRecordKind.TEMPLATE) {
            throw new BadRequestException("Cancel the import to skip the entire Picks template");
        } else if (operation instanceof ImportResolutionOperation.SkipItem
                && item.getNormalizedKind() == ImportRecordKind.TEMPLATE_CATEGORY
                && isFixedCategory(item)) {
            throw new BadRequestException("A fixed template category must be removed explicitly");
        }
        return null;
    }

    private MediaReference confirmedWatchScopeTarget(ImportItem item) {
        if (item.getResolvedTargetJson() == null || item.getResolvedTargetJson().isBlank()) {
            throw new BadRequestException("Resolve the series or season before confirming its watch scope");
        }
        try {
            ImportTarget target = objectMapper.treeToValue(
                    objectMapper.readTree(item.getResolvedTargetJson()), ImportTarget.class);
            if (!(target instanceof ImportTarget.ContentTarget contentTarget)
                    || (contentTarget.content().type() != ContentType.SERIES
                    && contentTarget.content().type() != ContentType.SEASON)) {
                throw new BadRequestException("Watch scope requires a resolved series or season");
            }
            return contentTarget.content();
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new BadRequestException("Watch scope target is invalid");
        }
    }

    private ConfirmedWatchScopeSnapshot validateWatchScopeSnapshot(
            UUID ownerId, ImportItem item, MediaReference target,
            ImportResolutionOperation.ConfirmWatchScope confirmation) {
        var owner = userRepository.findById(ownerId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        Instant now = clock.instant();
        ConfirmedWatchScopeSnapshot current = watchScopeSnapshot(item);
        if (current == null || !target.equals(current.target())) {
            throw new ConflictException("Episode scope preview is unavailable; refresh the import preview and try again");
        }
        if (!confirmation.scheduleVersion().equals(current.scheduleVersion())) {
            throw new ConflictException("Episode schedule changed; refresh the import preview and confirm again");
        }
        if (!current.validUntil().isAfter(now)) {
            throw new ConflictException("Episode schedule expired; refresh the import preview and confirm again");
        }
        var marker = localScheduleMarker(target, owner.getPreferredRegion(), owner.getPreferredLanguage());
        if (marker.isPresent() != current.localCalendarMarkerPresent()
                || marker.map(CalendarScheduleCompleteness::getScopeVersion)
                .orElse(0L) != current.localCalendarScopeVersion()) {
            throw new ConflictException("Episode schedule changed; refresh the import preview and confirm again");
        }
        Set<com.watchwise.watchwise_api.diarycompletion.model.EpisodeCoordinate> selected = new LinkedHashSet<>();
        for (MediaReference episode : confirmation.episodeReferences()) {
            selected.add(new com.watchwise.watchwise_api.diarycompletion.model.EpisodeCoordinate(
                    episode.seasonNumber(), episode.episodeNumber()));
        }
        if (confirmation.scope() == ImportResolutionOperation.WatchScope.ALL_RELEASED_EPISODES) {
            if (!selected.equals(Set.copyOf(current.episodes()))) {
                throw new ConflictException("Previewed episode set changed; refresh the import preview and confirm again");
            }
        } else if (!Set.copyOf(current.episodes()).containsAll(selected)) {
            throw new BadRequestException("Confirmed episodes are outside the current released schedule");
        }
        return current;
    }

    private ConfirmedWatchScopeSnapshot watchScopeSnapshot(ImportItem item) {
        JsonNode value = readPayload(item.getPayloadJson()).path("resolution").path("watchScopeSnapshot");
        if (!value.isObject()) return null;
        try {
            return objectMapper.treeToValue(value, ConfirmedWatchScopeSnapshot.class);
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new BadRequestException("Prepared watch scope snapshot is invalid");
        }
    }

    private Optional<CalendarScheduleCompleteness> localScheduleMarker(
            MediaReference target, String region, String language) {
        return target.type() == ContentType.SERIES
                ? scheduleCompletenessRepository.findSeriesIdentity(target.tmdbId(), region, language)
                : scheduleCompletenessRepository.findSeasonIdentity(
                        target.seriesTmdbId(), target.seasonNumber(), region, language);
    }

    private void validateEditedField(ImportItem item, ImportResolutionOperation.EditField editField) {
        String value = editField.value();
        if (editField.field() == null || value == null) {
            throw new BadRequestException("Edited field and value are required");
        }
        if (!isEditableForKind(item.getNormalizedKind(), editField.field())) {
            throw new BadRequestException("Edited field is not valid for this import item");
        }
        switch (editField.field()) {
            case TITLE, CATEGORY_NAME -> {
                if (value.isBlank()) throw new BadRequestException("Edited field cannot be blank");
                if (item.getNormalizedKind() == ImportRecordKind.LIST_METADATA && value.length() > 255) {
                    throw new BadRequestException("List name cannot exceed 255 characters");
                }
                if ((item.getNormalizedKind() == ImportRecordKind.TEMPLATE
                        || item.getNormalizedKind() == ImportRecordKind.TEMPLATE_CATEGORY)
                        && value.length() > 120) {
                    throw new BadRequestException("Picks template names cannot exceed 120 characters");
                }
            }
            case DESCRIPTION -> {
                if (item.getNormalizedKind() != ImportRecordKind.LIST_ITEM
                        && item.getNormalizedKind() != ImportRecordKind.LIST_METADATA && value.isBlank()) {
                    throw new BadRequestException("Edited field cannot be blank");
                }
                if ((item.getNormalizedKind() == ImportRecordKind.LIST_ITEM
                        || item.getNormalizedKind() == ImportRecordKind.LIST_METADATA) && value.length() > 400) {
                    throw new BadRequestException("List description cannot exceed 400 characters");
                }
            }
            case REVIEW -> {
                if (value.isBlank()) throw new BadRequestException("Edited field cannot be blank");
                if (value.length() > ImportedReviewTextNormalizer.MAX_REVIEW_LENGTH) {
                    throw new BadRequestException("Review cannot exceed 20000 characters");
                }
            }
            case COVER_IMAGE -> validateCoverImage(value);
            case RELEASE_YEAR -> {
                int year = parseInteger(value, "Release year");
                int currentYear = LocalDate.now(clock).getYear();
                if (year < 1870 || year > currentYear + 5) {
                    throw new BadRequestException("Release year is outside the supported range");
                }
            }
            case RATING -> {
                try {
                    java.math.BigDecimal score = new java.math.BigDecimal(value);
                    if (score.signum() < 0 || score.compareTo(java.math.BigDecimal.TEN) > 0) {
                        throw new BadRequestException("Rating must be between 0 and 10");
                    }
                } catch (NumberFormatException exception) {
                    throw new BadRequestException("Rating must be numeric");
                }
            }
            case WATCHED_DATE, ELIGIBILITY_START, ELIGIBILITY_END -> {
                try {
                    LocalDate.parse(value);
                } catch (DateTimeParseException exception) {
                    throw new BadRequestException("Edited date must use ISO format");
                }
            }
            case WATCHED_IN_THEATER -> {
                if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
                    throw new BadRequestException("Theater flag must be true or false");
                }
            }
        }
    }

    private boolean listMetadataLengthsValid(ImportItem item) {
        JsonNode root = readPayload(item.getPayloadJson());
        JsonNode source = sourcePayload(root);
        JsonNode fields = root.path("resolution").path("fields");
        JsonNode titleNode = fields.path("TITLE").isTextual() ? fields.path("TITLE") : source.path("title");
        JsonNode descriptionNode = fields.path("DESCRIPTION").isTextual()
                ? fields.path("DESCRIPTION") : source.path("description");
        String title = titleNode.isTextual() ? titleNode.asString() : null;
        String description = descriptionNode.isTextual() ? descriptionNode.asString() : null;
        if (item.getNormalizedKind() == ImportRecordKind.LIST_ITEM) {
            return description == null || description.length() <= 400;
        }
        return title != null && !title.isBlank() && title.length() <= 255
                && (description == null || description.length() <= 400);
    }

    private boolean isEditableForKind(
            ImportRecordKind kind, ImportResolutionOperation.EditableField field) {
        return switch (field) {
            case TITLE -> Set.of(ImportRecordKind.DIARY_EVENT, ImportRecordKind.REVIEW,
                    ImportRecordKind.WATCHED_MARKER, ImportRecordKind.WATCHED_SUMMARY,
                    ImportRecordKind.CURRENT_RATING, ImportRecordKind.LIST_METADATA,
                    ImportRecordKind.LIST_ITEM, ImportRecordKind.WATCHLIST_ITEM,
                    ImportRecordKind.SERIES_EXPANSION, ImportRecordKind.TEMPLATE,
                    ImportRecordKind.TEMPLATE_CATEGORY).contains(kind);
            case DESCRIPTION -> kind == ImportRecordKind.LIST_METADATA || kind == ImportRecordKind.LIST_ITEM
                    || kind == ImportRecordKind.TEMPLATE;
            case RELEASE_YEAR -> Set.of(ImportRecordKind.DIARY_EVENT, ImportRecordKind.REVIEW,
                    ImportRecordKind.WATCHED_MARKER, ImportRecordKind.WATCHED_SUMMARY,
                    ImportRecordKind.CURRENT_RATING, ImportRecordKind.LIST_ITEM,
                    ImportRecordKind.WATCHLIST_ITEM, ImportRecordKind.SERIES_EXPANSION).contains(kind);
            case RATING -> kind == ImportRecordKind.DIARY_EVENT || kind == ImportRecordKind.REVIEW
                    || kind == ImportRecordKind.CURRENT_RATING;
            case REVIEW -> kind == ImportRecordKind.DIARY_EVENT || kind == ImportRecordKind.REVIEW
                    || kind == ImportRecordKind.SERIES_EXPANSION;
            case WATCHED_DATE -> kind == ImportRecordKind.DIARY_EVENT || kind == ImportRecordKind.REVIEW
                    || kind == ImportRecordKind.WATCHED_MARKER || kind == ImportRecordKind.SERIES_EXPANSION;
            case WATCHED_IN_THEATER -> kind == ImportRecordKind.DIARY_EVENT;
            case COVER_IMAGE, ELIGIBILITY_START, ELIGIBILITY_END -> kind == ImportRecordKind.TEMPLATE;
            case CATEGORY_NAME -> kind == ImportRecordKind.TEMPLATE_CATEGORY;
        };
    }

    private void validateCoverImage(String value) {
        try {
            java.net.URI uri = java.net.URI.create(value);
            if (uri.getHost() == null || uri.getUserInfo() != null
                    || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))) {
                throw new BadRequestException("Cover image must be an HTTP or HTTPS URL");
            }
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException("Cover image must be a valid URL");
        }
    }

    private void validateTemplateEligibilityPlan(List<ImportResolutionOperation> decisions,
                                                 Map<UUID, ImportItem> items) {
        Set<UUID> changedTemplates = decisions.stream()
                .filter(ImportResolutionOperation.EditField.class::isInstance)
                .map(ImportResolutionOperation.EditField.class::cast)
                .filter(edit -> edit.field() == ImportResolutionOperation.EditableField.ELIGIBILITY_START
                        || edit.field() == ImportResolutionOperation.EditableField.ELIGIBILITY_END)
                .map(ImportResolutionOperation.EditField::itemId)
                .filter(id -> items.get(id) != null && items.get(id).getNormalizedKind() == ImportRecordKind.TEMPLATE)
                .collect(java.util.stream.Collectors.toSet());
        for (UUID templateId : changedTemplates) {
            ImportItem template = items.get(templateId);
            JsonNode payload = readPayload(template.getPayloadJson());
            JsonNode source = sourcePayload(payload);
            JsonNode fields = payload.path("resolution").path("fields");
            String startValue = fields.path("ELIGIBILITY_START").isTextual()
                    ? fields.path("ELIGIBILITY_START").asString() : textual(source.path("eligibilityStart"));
            String endValue = fields.path("ELIGIBILITY_END").isTextual()
                    ? fields.path("ELIGIBILITY_END").asString() : textual(source.path("eligibilityEnd"));
            LocalDate start = dateValue(startValue);
            LocalDate end = dateValue(endValue);
            boolean invalidPair = (start == null) != (end == null)
                    || start != null && end != null && start.isAfter(end);
            if (invalidPair) {
                template.setOutcome("NORMALIZATION_ISSUE");
                template.setIssue("TEMPLATE_ELIGIBILITY_PERIOD_INVALID: Provide both dates with start no later than end.");
            } else if (template.getIssue() != null
                    && template.getIssue().startsWith("TEMPLATE_ELIGIBILITY_PERIOD_INVALID")) {
                template.setOutcome("RESOLVED");
                template.setIssue(null);
            }
        }
    }

    private LocalDate dateValue(String value) {
        if (value == null) return null;
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            throw new BadRequestException("Edited date must use ISO format");
        }
    }

    private void validateTarget(ImportItem item, ImportTarget target) {
        if (target == null) throw new BadRequestException("Resolved target is required");
        if (target instanceof ImportTarget.PersonTarget) {
            if (item.getNormalizedKind() != ImportRecordKind.TEMPLATE_OPTION) {
                throw new BadRequestException("A person target is not valid for this import item");
            }
            return;
        }
        ContentType type = ((ImportTarget.ContentTarget) target).content().type();
        if (item.getNormalizedKind() == ImportRecordKind.WATCHLIST_ITEM
                && type != ContentType.MOVIE && type != ContentType.SERIES) {
            throw new BadRequestException("Watchlist targets must be movies or series");
        }
    }

    private PreparedTargetResolution prepareTargetResolution(
            ImportItem item, ImportResolutionOperation.ResolveTarget operation) {
        CandidateEnvelope envelope = candidateEnvelope(item);
        ImportTarget target;
        AliasQuery query;
        if (operation.target() == null) {
            if (operation.candidateId() == null || operation.candidateId().isBlank() || envelope == null) {
                throw new BadRequestException("Candidate is not available for this import item");
            }
            target = envelope.candidates().get(operation.candidateId());
            if (target == null) throw new BadRequestException("Candidate does not belong to this import item");
            query = envelope.query();
        } else {
            target = operation.target();
            query = envelope == null ? sourceAliasQuery(item) : envelope.query();
        }
        validateTarget(item, target);
        validateAliasTarget(query, target);
        return new PreparedTargetResolution(target, query);
    }

    private CandidateEnvelope candidateEnvelope(ImportItem item) {
        String storedJson = item.getResolvedTargetJson();
        if (storedJson == null || storedJson.isBlank()) return null;
        JsonNode root;
        try {
            root = objectMapper.readTree(storedJson);
        } catch (JacksonException exception) {
            throw new BadRequestException("Candidate envelope is invalid");
        }
        if (root == null || !root.isObject()) throw new BadRequestException("Candidate envelope is invalid");

        boolean hasEnvelopeField = root.has("schemaVersion") || root.has("query") || root.has("candidates");
        if (!hasEnvelopeField) {
            try {
                ImportTarget storedTarget = objectMapper.treeToValue(root, ImportTarget.class);
                if (storedTarget != null) return null;
            } catch (JacksonException | IllegalArgumentException exception) {
                throw new BadRequestException("Candidate envelope is invalid");
            }
            throw new BadRequestException("Candidate envelope is invalid");
        }

        JsonNode schemaVersion = root.get("schemaVersion");
        JsonNode candidateNodes = root.get("candidates");
        if (schemaVersion == null || !schemaVersion.isInt() || schemaVersion.asInt() != 1
                || candidateNodes == null || !candidateNodes.isArray()) {
            throw new BadRequestException("Candidate envelope is invalid");
        }
        AliasQuery query = parseAliasQuery(root.get("query"), "Candidate envelope is invalid");
        Map<String, ImportTarget> candidates = new LinkedHashMap<>();
        for (JsonNode candidate : candidateNodes) {
            if (candidate == null || !candidate.isObject()) {
                throw new BadRequestException("Candidate envelope is invalid");
            }
            JsonNode candidateId = candidate.get("candidateId");
            JsonNode targetNode = candidate.get("target");
            if (candidateId == null || !candidateId.isString() || candidateId.asString().isBlank()
                    || targetNode == null || !targetNode.isObject()) {
                throw new BadRequestException("Candidate envelope is invalid");
            }
            try {
                ImportTarget target = objectMapper.treeToValue(targetNode, ImportTarget.class);
                if (candidates.putIfAbsent(candidateId.asString(), target) != null) {
                    throw new BadRequestException("Candidate envelope is invalid");
                }
                validateTarget(item, target);
                validateAliasTarget(query, target);
            } catch (JacksonException | IllegalArgumentException exception) {
                throw new BadRequestException("Candidate envelope is invalid");
            }
        }
        return new CandidateEnvelope(query, candidates);
    }

    private AliasQuery sourceAliasQuery(ImportItem item) {
        JsonNode queryNode;
        try {
            queryNode = queryNodeFrom(item.getResolvedTargetJson());
            if (queryNode == null) queryNode = queryNodeFrom(item.getPayloadJson());
        } catch (IllegalStateException exception) {
            throw new BadRequestException("Candidate query is invalid");
        }
        if (queryNode == null) throw new BadRequestException("Candidate query is invalid");
        return parseAliasQuery(queryNode, "Candidate query is invalid");
    }

    private AliasQuery parseAliasQuery(JsonNode queryNode, String invalidMessage) {
        if (queryNode == null || !queryNode.isObject()) throw new BadRequestException(invalidMessage);
        JsonNode kindNode = queryNode.get("kind");
        JsonNode titleNode = queryNode.get("title");
        JsonNode yearNode = queryNode.get("year");
        JsonNode contentTypeNode = queryNode.get("contentType");
        if (kindNode == null || !kindNode.isString() || titleNode == null || !titleNode.isString()) {
            throw new BadRequestException(invalidMessage);
        }
        try {
            AliasQuery.Kind kind = AliasQuery.Kind.valueOf(kindNode.asString());
            Integer year = null;
            if (yearNode != null && !yearNode.isNull()) {
                if (!yearNode.isInt()) throw new BadRequestException(invalidMessage);
                year = yearNode.asInt();
            }
            ContentType contentType = null;
            if (contentTypeNode != null && !contentTypeNode.isNull()) {
                if (!contentTypeNode.isString()) throw new BadRequestException(invalidMessage);
                contentType = ContentType.valueOf(contentTypeNode.asString());
            }
            return new AliasQuery(kind, titleNode.asString(), year, contentType);
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException(invalidMessage);
        }
    }

    private void validateAliasTarget(AliasQuery query, ImportTarget target) {
        if (query.kind() == AliasQuery.Kind.PERSON) {
            if (!(target instanceof ImportTarget.PersonTarget)) {
                throw new BadRequestException("Person query requires a person target");
            }
            return;
        }
        if (!(target instanceof ImportTarget.ContentTarget contentTarget)) {
            throw new BadRequestException("Content query requires a content target");
        }
        if (query.contentType() != null && contentTarget.content().type() != query.contentType()) {
            throw new BadRequestException("Resolved target type does not match the import query");
        }
    }

    private void validateReviewOrder(ImportItem item, ImportItem beforeItem) {
        requireKind(item, ImportRecordKind.REVIEW, "Only reviews can be ordered");
        requireKind(beforeItem, ImportRecordKind.REVIEW, "Review order target is invalid");
        String itemKey = item.getResolvedContentKey() == null ? item.getNormalizedContentKey() : item.getResolvedContentKey();
        String beforeKey = beforeItem.getResolvedContentKey() == null
                ? beforeItem.getNormalizedContentKey() : beforeItem.getResolvedContentKey();
        if (itemKey == null || !itemKey.equals(beforeKey)) {
            throw new BadRequestException("Review order can only be resolved for the same content");
        }
        LocalDate itemDate = payloadDate(item.getPayloadJson());
        LocalDate beforeDate = payloadDate(beforeItem.getPayloadJson());
        if (itemDate != null && beforeDate != null && itemDate.isAfter(beforeDate)) {
            throw new BadRequestException("Review order cannot contradict known chronology");
        }
    }

    private void validateFixedCategoryConstraints(
            UUID jobId,
            long generation,
            List<ImportResolutionOperation> decisions,
            Map<UUID, ImportItem> requestedItems) {
        Set<String> checkedCategories = new LinkedHashSet<>();
        for (ImportResolutionOperation decision : decisions) {
            if (!(decision instanceof ImportResolutionOperation.SkipItem)
                    && !(decision instanceof ImportResolutionOperation.RemoveNominee)) continue;
            ImportItem nominee = requestedItems.get(decision.itemId());
            if (nominee.getNormalizedKind() != ImportRecordKind.TEMPLATE_OPTION) continue;
            String parentSourceId = nominee.getParentSourceId();
            if (parentSourceId == null || parentSourceId.isBlank()) {
                throw new BadRequestException("Template nominee has no parent category");
            }
            if (!checkedCategories.add(parentSourceId)) continue;

            List<ImportItem> categoryItems = importItemRepository
                    .findAllByJobIdAndPlanGenerationAndSourceIdAndNormalizedKind(
                            jobId, generation, parentSourceId, ImportRecordKind.TEMPLATE_CATEGORY);
            if (categoryItems.size() != 1) {
                throw new BadRequestException("Template nominee parent category is missing or ambiguous");
            }
            ImportItem category = categoryItems.getFirst();
            if (isCategoryOpenedOrRemoved(category, decisions) || !isFixedCategory(category)) continue;

            List<ImportItem> nominees = importItemRepository
                    .findAllByJobIdAndPlanGenerationAndParentSourceIdAndNormalizedKind(
                            jobId, generation, parentSourceId, ImportRecordKind.TEMPLATE_OPTION);
            long remaining = nominees.stream()
                    .filter(this::isActiveNominee)
                    .filter(candidate -> !isRemovedInRequest(candidate.getId(), decisions))
                    .count();
            if (remaining == 0) {
                throw new BadRequestException(
                        "A fixed template category must retain at least one nominee or be opened explicitly");
            }
        }
    }

    private void validateTemplateCategoryConstraints(UUID jobId, long generation,
                                                     List<ImportResolutionOperation> decisions) {
        List<ImportItem> roots = importItemRepository.findAllByJobIdAndPlanGenerationAndNormalizedKind(
                jobId, generation, ImportRecordKind.TEMPLATE);
        if (roots.isEmpty()) return;
        if (roots.size() != 1) throw new BadRequestException("Import contains multiple template roots");
        List<ImportItem> categories = importItemRepository.findAllByJobIdAndPlanGenerationAndNormalizedKind(
                jobId, generation, ImportRecordKind.TEMPLATE_CATEGORY);
        long activeCategories = categories.stream().filter(category -> {
            if (category.getDecision() == ImportDecision.REMOVE_CATEGORY
                    || category.getDecision() == ImportDecision.SKIP_ITEM) return false;
            return decisions.stream().noneMatch(decision -> category.getId().equals(decision.itemId())
                    && (decision instanceof ImportResolutionOperation.RemoveCategory
                    || decision instanceof ImportResolutionOperation.SkipItem));
        }).count();
        if (activeCategories == 0) {
            throw new BadRequestException("A Picks template must retain at least one category");
        }
    }

    private boolean isRemovedInRequest(UUID itemId, List<ImportResolutionOperation> decisions) {
        return decisions.stream().anyMatch(decision -> itemId.equals(decision.itemId())
                && (decision instanceof ImportResolutionOperation.SkipItem
                || decision instanceof ImportResolutionOperation.RemoveNominee));
    }

    private boolean isActiveNominee(ImportItem item) {
        return item.getDecision() != ImportDecision.SKIP_ITEM
                && item.getDecision() != ImportDecision.REMOVE_NOMINEE;
    }

    private boolean isCategoryOpenedOrRemoved(
            ImportItem category, List<ImportResolutionOperation> decisions) {
        for (ImportResolutionOperation decision : decisions) {
            if (!category.getId().equals(decision.itemId())) continue;
            if (decision instanceof ImportResolutionOperation.RemoveCategory) return true;
            if (decision instanceof ImportResolutionOperation.SetCategoryOpen setOpen) return setOpen.open();
        }
        if (category.getDecision() == ImportDecision.REMOVE_CATEGORY) return true;
        if (category.getDecision() != ImportDecision.SET_CATEGORY_OPEN) return false;
        JsonNode payload = readPayload(category.getPayloadJson());
        JsonNode setOpen = payload.path("resolution").path("setCategoryOpen");
        return setOpen.path("open").asBoolean(false);
    }

    private boolean isFixedCategory(ImportItem category) {
        JsonNode source = sourcePayload(readPayload(category.getPayloadJson()));
        String optionMode = source.path("optionMode").asString();
        if (optionMode != null && !optionMode.isBlank()) return "FIXED".equalsIgnoreCase(optionMode);
        JsonNode nominees = source.get("nominees");
        return nominees != null && nominees.isArray() && !nominees.isEmpty();
    }

    private JsonNode sourcePayload(JsonNode payload) {
        if (payload.isObject() && payload.path(RESOLUTION_OVERLAY_MARKER).asInt(-1)
                == RESOLUTION_OVERLAY_SCHEMA_VERSION && payload.get("source") != null) {
            return payload.get("source");
        }
        return payload;
    }

    private LocalDate payloadDate(String payload) {
        if (payload == null || payload.isBlank()) return null;
        try {
            JsonNode node = sourcePayload(objectMapper.readTree(payload));
            JsonNode value = node.get("watchedDate");
            if (value == null || value.isNull()) value = node.get("date");
            if (value == null || value.isNull()) return null;
            return LocalDate.parse(value.asString());
        } catch (JacksonException | DateTimeParseException exception) {
            return null;
        }
    }

    private void applyDecision(UUID ownerId, ImportJob job, ImportResolutionOperation operation,
                               Map<UUID, ImportItem> items, PreparedTargetResolution preparedTarget,
                               ConfirmedWatchScopeSnapshot confirmedWatchScopeSnapshot) {
        ImportItem item = items.get(operation.itemId());
        ResolutionMetadata metadata = resolutionMetadata(operation);
        if (operation instanceof ImportResolutionOperation.ResolveTarget) {
            externalContentAliasService.remember(ownerId, preparedTarget.query(), preparedTarget.target(),
                    AliasTrust.USER_CONFIRMED);
        }
        item.setDecision(metadata.decision());
        item.setPayloadJson(appendDecisionOverlay(item, operation, metadata.overlayKey()));
        if (confirmedWatchScopeSnapshot != null) {
            item.setPayloadJson(appendConfirmedWatchScopeSnapshot(item.getPayloadJson(), confirmedWatchScopeSnapshot));
        }
        if (operation instanceof ImportResolutionOperation.ResolveTarget) {
            item.setOutcome("MATCHED");
        } else if (operation instanceof ImportResolutionOperation.AssignPass) {
            item.setOutcome("RESOLVED");
            item.setIssue(null);
        } else if (operation instanceof ImportResolutionOperation.ConfirmWatchScope
                && "NORMALIZATION_ISSUE".equals(item.getOutcome())
                && item.getIssue() != null
                && (item.getIssue().startsWith("WATCH_SCOPE_")
                || ("deferred_opinion_replay".equals(item.getObjectKind())
                && item.getIssue().startsWith("OPINION_EVIDENCE_PENDING")))) {
            item.setOutcome("RESOLVED");
            item.setIssue(null);
        } else if (operation instanceof ImportResolutionOperation.EditField editField
                && editField.field() == ImportResolutionOperation.EditableField.REVIEW
                && "NORMALIZATION_ISSUE".equals(item.getOutcome())
                && item.getIssue() != null
                && item.getIssue().startsWith(ImportedReviewTextNormalizer.REVIEW_TOO_LONG_ISSUE)) {
            item.setOutcome("RESOLVED");
            item.setIssue(null);
        } else if (operation instanceof ImportResolutionOperation.EditField editField
                && (editField.field() == ImportResolutionOperation.EditableField.TITLE
                || editField.field() == ImportResolutionOperation.EditableField.DESCRIPTION)
                && "NORMALIZATION_ISSUE".equals(item.getOutcome())
                && item.getIssue() != null
                && (item.getIssue().startsWith("LIST_ITEM_DESCRIPTION_TOO_LONG")
                || item.getIssue().startsWith("LIST_METADATA_FIELDS_TOO_LONG"))
                && listMetadataLengthsValid(item)) {
            item.setOutcome("RESOLVED");
            item.setIssue(null);
        } else if (operation instanceof ImportResolutionOperation.SkipItem
                || operation instanceof ImportResolutionOperation.RemoveNominee
                || operation instanceof ImportResolutionOperation.RemoveCategory) {
            item.setOutcome("RESOLVED");
        }
        if (preparedTarget != null) {
            ImportTarget target = preparedTarget.target();
            item.setResolvedTargetJson(writeJson(objectMapper.valueToTree(target)));
            item.setResolvedContentKey(contentKey(target));
            item.setIssue(null);
        } else if (operation instanceof ImportResolutionOperation.SkipItem
                || operation instanceof ImportResolutionOperation.RemoveNominee
                || operation instanceof ImportResolutionOperation.RemoveCategory) {
            item.setResolvedTargetJson(null);
            item.setResolvedContentKey(null);
        }
        if ((operation instanceof ImportResolutionOperation.SkipItem
                || operation instanceof ImportResolutionOperation.ConfirmWatchScope)
                && (item.getNormalizedKind() == ImportRecordKind.WATCHED_SUMMARY
                || item.getNormalizedKind() == ImportRecordKind.WATCHED_MARKER)) {
            stageDeferredReplayForSourceDecision(ownerId, job, item, operation, confirmedWatchScopeSnapshot);
        }
    }

    private void stageDeferredReplayForSourceDecision(UUID ownerId, ImportJob job, ImportItem sourceItem,
                                                      ImportResolutionOperation operation,
                                                      ConfirmedWatchScopeSnapshot confirmedWatchScopeSnapshot) {
        ImportDeferredRecord deferred = deferredRecordRepository.findPendingBySourceIdentityForUpdate(ownerId,
                sourceItem.getSourceNamespace(), sourceItem.getObjectKind(), sourceItem.getSourceId()).orElse(null);
        if (deferred == null) return;

        long generation = readPlanGeneration(job.getSelectionJson());
        NormalizedImportRecord replayRecord = deferredOpinionReplayStager.toRecord(deferred, job.getSourceNamespace());
        UUID replayId = DeferredOpinionReplayStager.stableItemId(job.getId(), generation, replayRecord.itemId());
        ImportItem replay = importItemRepository.findById(replayId).orElse(null);
        if (replay == null) {
            if (importItemRepository.countByJobIdAndPlanGeneration(job.getId(), generation)
                    >= importLimits.maxRecords()) {
                throw new BadRequestException("Import contains too many records to stage the deferred opinion");
            }
            replay = deferredOpinionReplayStager.toImportItem(job.getId(), generation, replayRecord,
                    importItemRepository.findMaxOrdinal(job.getId(), generation) + 1);
        } else if (!job.getId().equals(replay.getJobId()) || replay.getPlanGeneration() != generation
                || !"deferred_opinion_replay".equals(replay.getObjectKind())
                || !deferred.getId().toString().equals(replay.getSourceId())) {
            throw new IllegalStateException("Deferred replay item identity does not match its source record");
        }

        ImportResolutionOperation replayOperation;
        String overlayKey;
        if (operation instanceof ImportResolutionOperation.ConfirmWatchScope confirmation) {
            replayOperation = new ImportResolutionOperation.ConfirmWatchScope(replayId, confirmation.scope(),
                    confirmation.scheduleVersion(), confirmation.episodeReferences());
            overlayKey = "confirmWatchScope";
        } else {
            replayOperation = new ImportResolutionOperation.SkipItem(replayId);
            overlayKey = "skipItem";
        }
        replay.setDecision(resolutionMetadata(replayOperation).decision());
        replay.setPayloadJson(appendDecisionOverlay(replay, replayOperation, overlayKey));
        if (confirmedWatchScopeSnapshot != null) {
            replay.setPayloadJson(appendConfirmedWatchScopeSnapshot(
                    replay.getPayloadJson(), confirmedWatchScopeSnapshot));
        }
        replay.setOutcome("RESOLVED");
        replay.setIssue(null);
        importItemRepository.save(replay);
    }

    private String appendConfirmedWatchScopeSnapshot(String payload,
                                                     ConfirmedWatchScopeSnapshot snapshot) {
        JsonNode parsed = readPayload(payload);
        if (!parsed.isObject() || !parsed.path("resolution").isObject()) {
            throw new IllegalStateException("Confirmed watch scope resolution overlay is missing");
        }
        ObjectNode root = (ObjectNode) parsed.deepCopy();
        ((ObjectNode) root.get("resolution")).set("confirmedWatchScopeSnapshot", objectMapper.valueToTree(snapshot));
        return writeJson(root);
    }

    private JsonNode queryNodeFrom(String payload) {
        if (payload == null || payload.isBlank()) return null;
        JsonNode root = readPayload(payload);
        JsonNode candidateQuery = root.get("query");
        if (candidateQuery != null && candidateQuery.isObject()) return candidateQuery;
        JsonNode overlayQuery = root.get("sourceQuery");
        if (overlayQuery != null && overlayQuery.isObject()) return overlayQuery;
        JsonNode source = sourcePayload(root);
        JsonNode sourceQuery = source.get("sourceQuery");
        if (sourceQuery != null && sourceQuery.isObject()) return sourceQuery;
        JsonNode nestedQuery = source.get("query");
        return nestedQuery != null && nestedQuery.isObject() ? nestedQuery : null;
    }

    private String appendDecisionOverlay(ImportItem item, ImportResolutionOperation operation, String overlayKey) {
        JsonNode original = readPayload(item.getPayloadJson());
        ObjectNode overlay;
        ObjectNode resolution;
        if (original.isObject() && original.path(RESOLUTION_OVERLAY_MARKER).asInt(-1)
                == RESOLUTION_OVERLAY_SCHEMA_VERSION && original.get("source") != null
                && original.get("resolution") != null && original.get("resolution").isObject()) {
            overlay = (ObjectNode) original.deepCopy();
            resolution = (ObjectNode) overlay.get("resolution");
        } else {
            overlay = objectMapper.createObjectNode();
            overlay.put(RESOLUTION_OVERLAY_MARKER, RESOLUTION_OVERLAY_SCHEMA_VERSION);
            overlay.set("source", original);
            resolution = objectMapper.createObjectNode();
            resolution.set("fields", objectMapper.createObjectNode());
            overlay.set("resolution", resolution);
        }
        JsonNode sourceQuery = queryNodeFrom(item.getResolvedTargetJson());
        if (sourceQuery != null) overlay.set("sourceQuery", sourceQuery.deepCopy());
        if (operation instanceof ImportResolutionOperation.EditField editField) {
            ((ObjectNode) resolution.get("fields")).put(editField.field().name(), editField.value());
        } else {
            resolution.set(overlayKey, objectMapper.valueToTree(operation));
        }
        if (operation instanceof ImportResolutionOperation.AssignPass
                && item.getIssue() != null && item.getIssue().contains("MATCH_REQUIRED")) {
            resolution.put("linkExistingOnly", true);
        }
        return writeJson(overlay);
    }

    private JsonNode readPayload(String payload) {
        if (payload == null || payload.isBlank()) return NullNode.getInstance();
        try {
            return objectMapper.readTree(payload);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Import item payload is invalid", exception);
        }
    }

    private String contentKey(ImportTarget target) {
        if (target instanceof ImportTarget.PersonTarget person) return "PERSON|" + person.personTmdbId();
        MediaReference content = ((ImportTarget.ContentTarget) target).content();
        return switch (content.type()) {
            case MOVIE, SERIES -> "CONTENT|" + content.type().name() + "|" + content.tmdbId();
            case SEASON -> "CONTENT|SEASON|" + content.seriesTmdbId() + "|" + content.seasonNumber();
            case EPISODE -> "CONTENT|EPISODE|" + content.seriesTmdbId() + "|"
                    + content.seasonNumber() + "|" + content.episodeNumber();
        };
    }

    private ResolutionMetadata resolutionMetadata(ImportResolutionOperation operation) {
        if (operation instanceof ImportResolutionOperation.ResolveTarget) {
            return new ResolutionMetadata(ImportDecision.RESOLVE_TARGET, "resolveTarget");
        }
        if (operation instanceof ImportResolutionOperation.SkipItem) {
            return new ResolutionMetadata(ImportDecision.SKIP_ITEM, "skipItem");
        }
        if (operation instanceof ImportResolutionOperation.EditField) {
            return new ResolutionMetadata(ImportDecision.EDIT_FIELD, "fields");
        }
        if (operation instanceof ImportResolutionOperation.AssignPass) {
            return new ResolutionMetadata(ImportDecision.ASSIGN_PASS, "assignPass");
        }
        if (operation instanceof ImportResolutionOperation.ConfirmWatchScope) {
            return new ResolutionMetadata(ImportDecision.CONFIRM_WATCH_SCOPE, "confirmWatchScope");
        }
        if (operation instanceof ImportResolutionOperation.LinkImportedList) {
            return new ResolutionMetadata(ImportDecision.LINK_IMPORTED_LIST, "linkImportedList");
        }
        if (operation instanceof ImportResolutionOperation.ResolveReviewOrder) {
            return new ResolutionMetadata(ImportDecision.RESOLVE_REVIEW_ORDER, "resolveReviewOrder");
        }
        if (operation instanceof ImportResolutionOperation.RemoveNominee) {
            return new ResolutionMetadata(ImportDecision.REMOVE_NOMINEE, "removeNominee");
        }
        if (operation instanceof ImportResolutionOperation.RemoveCategory) {
            return new ResolutionMetadata(ImportDecision.REMOVE_CATEGORY, "removeCategory");
        }
        if (operation instanceof ImportResolutionOperation.SetCategoryOpen) {
            return new ResolutionMetadata(ImportDecision.SET_CATEGORY_OPEN, "setCategoryOpen");
        }
        throw new BadRequestException("Unsupported import resolution operation");
    }

    private ImportItem requireItem(UUID id, Map<UUID, ImportItem> items) {
        ImportItem item = items.get(id);
        if (item == null) throw new BadRequestException("Resolution item does not belong to this import plan");
        return item;
    }

    private void requireKind(ImportItem item, ImportRecordKind expected, String message) {
        if (item.getNormalizedKind() != expected) throw new BadRequestException(message);
    }

    private boolean isPreparedSeriesExpansion(ImportItem item) {
        if (item.getNormalizedKind() != ImportRecordKind.SERIES_EXPANSION) return false;
        if ("EXPANSION_PREPARED".equals(item.getOutcome())) return true;
        JsonNode payload = readPayload(item.getPayloadJson());
        JsonNode metadata = payload.get(LetterboxdSeriesExpansionApplier.EXPANSION_FIELD);
        if (metadata == null && payload.path("source").isObject()) {
            metadata = payload.path("source").get(LetterboxdSeriesExpansionApplier.EXPANSION_FIELD);
        }
        return metadata != null && metadata.isObject() && metadata.path("schemaVersion").asInt(-1) == 1;
    }

    private List<ImportItem> changedItems(List<ImportResolutionOperation> decisions, Map<UUID, ImportItem> items) {
        LinkedHashSet<UUID> ids = new LinkedHashSet<>();
        for (ImportResolutionOperation decision : decisions) {
            ids.add(decision.itemId());
            if (decision instanceof ImportResolutionOperation.ResolveReviewOrder order) ids.add(order.beforeItemId());
        }
        return ids.stream().map(items::get).toList();
    }

    private ImportPreviewItemDTO toPreviewItem(ImportJob job, ImportItem item) {
        PreviewWatchDate watchDate = previewWatchDate(item);
        PreviewItemTitle itemTitle = previewItemTitle(item);
        PreviewItemDescription itemDescription = previewItemDescription(item);
        PreviewReviewText reviewText = previewReviewText(item);
        PreviewTemplateEligibility eligibility = previewTemplateEligibility(item);
        return new ImportPreviewItemDTO(
                item.getId(), item.getOrdinal(), item.getNormalizedKind(),
                previewParentItemId(job, item), templateTargetType(item),
                eligibility.start(), eligibility.end(),
                item.getDecision(), item.getOutcome(), item.getIssue(),
                watchDate.date(), watchDate.timestamp(), watchDate.precision(),
                previewResolvedTarget(item), itemTitle.text(), itemTitle.length(),
                itemTitle.truncated(), itemDescription.text(), itemDescription.length(),
                itemDescription.truncated(), reviewText.text(), reviewText.length(),
                reviewText.truncated(), watchScopePreview(job, item));
    }

    private UUID previewParentItemId(ImportJob job, ImportItem item) {
        if ((item.getNormalizedKind() != ImportRecordKind.TEMPLATE_CATEGORY
                && item.getNormalizedKind() != ImportRecordKind.TEMPLATE_OPTION)
                || item.getParentSourceId() == null) return null;
        String seed = job.getId() + ":" + readPlanGeneration(job.getSelectionJson()) + ":"
                + item.getParentSourceId();
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }

    private PickAllowedType templateTargetType(ImportItem item) {
        if (item.getNormalizedKind() != ImportRecordKind.TEMPLATE_CATEGORY
                && item.getNormalizedKind() != ImportRecordKind.TEMPLATE_OPTION) return null;
        JsonNode source = sourcePayload(readPayload(item.getPayloadJson()));
        JsonNode value = item.getNormalizedKind() == ImportRecordKind.TEMPLATE_OPTION
                ? source.path("targetType") : source.path("type");
        if (!value.isTextual()) return null;
        try {
            return PickAllowedType.valueOf(value.asString());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Staged template target type is invalid", exception);
        }
    }

    private PreviewTemplateEligibility previewTemplateEligibility(ImportItem item) {
        if (item.getNormalizedKind() != ImportRecordKind.TEMPLATE) {
            return new PreviewTemplateEligibility(null, null);
        }
        JsonNode root = readPayload(item.getPayloadJson());
        JsonNode source = sourcePayload(root);
        JsonNode fields = root.path("resolution").path("fields");
        String start = fields.path("ELIGIBILITY_START").isTextual()
                ? fields.path("ELIGIBILITY_START").asString() : textual(source.path("eligibilityStart"));
        String end = fields.path("ELIGIBILITY_END").isTextual()
                ? fields.path("ELIGIBILITY_END").asString() : textual(source.path("eligibilityEnd"));
        return new PreviewTemplateEligibility(dateValue(start), dateValue(end));
    }

    private JsonNode previewResolvedTarget(ImportItem item) {
        JsonNode target = optionalJson(item.getResolvedTargetJson());
        if (target == null || item.getNormalizedKind() != ImportRecordKind.TEMPLATE_OPTION) return target;
        JsonNode source = sourcePayload(readPayload(item.getPayloadJson()));
        JsonNode context = source.path("nominee").path("contextContent");
        if (!context.isObject()) return target;

        JsonNode candidates = target.path("candidates");
        if (candidates.isArray()) {
            ObjectNode projected = (ObjectNode) target.deepCopy();
            ArrayNode projectedCandidates = (ArrayNode) projected.path("candidates");
            for (JsonNode value : projectedCandidates) {
                JsonNode candidateTarget = value.path("target");
                if (candidateTarget.isObject() && "PERSON".equals(candidateTarget.path("kind").asString())) {
                    ((ObjectNode) candidateTarget).set("contextContent", context.deepCopy());
                }
            }
            return projected;
        }
        if (target.isObject() && "PERSON".equals(target.path("kind").asString())) {
            ObjectNode projected = (ObjectNode) target.deepCopy();
            projected.set("contextContent", context.deepCopy());
            return projected;
        }
        return target;
    }

    private ImportWatchScopePreviewDTO watchScopePreview(ImportJob job, ImportItem item) {
        if (item.getNormalizedKind() != ImportRecordKind.WATCHED_SUMMARY
                && item.getNormalizedKind() != ImportRecordKind.WATCHED_MARKER
                && !"deferred_opinion_replay".equals(item.getObjectKind())) return null;
        try {
            JsonNode value = readPayload(item.getPayloadJson()).path("resolution").path("watchScopePreview");
            if (!value.isObject()) return ImportWatchScopePreviewDTO.unavailable("WATCH_SCOPE_SNAPSHOT_PENDING");
            return objectMapper.treeToValue(value, ImportWatchScopePreviewDTO.class);
        } catch (JacksonException | IllegalArgumentException exception) {
            return ImportWatchScopePreviewDTO.unavailable("WATCH_SCOPE_TARGET_INVALID");
        }
    }

    private PreviewItemTitle previewItemTitle(ImportItem item) {
        JsonNode root = readPayload(item.getPayloadJson());
        JsonNode source = sourcePayload(root);
        JsonNode fields = root.path("resolution").path("fields");
        String text = switch (item.getNormalizedKind()) {
            case LIST_METADATA, TEMPLATE -> fieldText(fields.path("TITLE"), source.path("title"));
            case TEMPLATE_CATEGORY -> fieldText(fields.path("CATEGORY_NAME"), source.path("name"));
            case TEMPLATE_OPTION -> templateNomineeLabel(source.path("nominee"));
            default -> null;
        };
        if (text == null) return new PreviewItemTitle(null, null, false);
        int length = text.length();
        int previewLength = Math.min(length, 255);
        if (previewLength < length && previewLength > 0 && Character.isHighSurrogate(text.charAt(previewLength - 1))
                && Character.isLowSurrogate(text.charAt(previewLength))) {
            previewLength--;
        }
        return new PreviewItemTitle(text.substring(0, previewLength), length, length > 255);
    }

    private PreviewItemDescription previewItemDescription(ImportItem item) {
        if (item.getNormalizedKind() != ImportRecordKind.LIST_METADATA
                && item.getNormalizedKind() != ImportRecordKind.LIST_ITEM
                && item.getNormalizedKind() != ImportRecordKind.TEMPLATE
                && item.getNormalizedKind() != ImportRecordKind.TEMPLATE_CATEGORY) {
            return new PreviewItemDescription(null, null, false);
        }
        JsonNode root = readPayload(item.getPayloadJson());
        JsonNode source = sourcePayload(root);
        JsonNode fields = root.path("resolution").path("fields");
        String text = item.getNormalizedKind() == ImportRecordKind.TEMPLATE_CATEGORY
                ? textual(source.path("description"))
                : fieldText(fields.path("DESCRIPTION"), source.path("description"));
        if (text == null) return new PreviewItemDescription(null, null, false);
        int length = text.length();
        int previewLength = Math.min(length, 400);
        if (previewLength < length && previewLength > 0 && Character.isHighSurrogate(text.charAt(previewLength - 1))
                && Character.isLowSurrogate(text.charAt(previewLength))) {
            previewLength--;
        }
        return new PreviewItemDescription(text.substring(0, previewLength), length, length > 400);
    }

    private String fieldText(JsonNode edited, JsonNode source) {
        return edited.isTextual() ? edited.asString() : textual(source);
    }

    private String templateNomineeLabel(JsonNode nominee) {
        if (nominee == null || nominee.isMissingNode() || nominee.isNull()) return null;
        if (nominee.isTextual()) return nominee.asString();
        String title = textual(nominee.path("name"));
        if (title == null) title = textual(nominee.path("title"));
        if (title != null) return title;
        String personId = textual(nominee.path("personTmdbId"));
        if (personId != null) return "Person TMDB ID " + personId;
        String contentId = textual(nominee.path("tmdbId"));
        if (contentId != null) return "TMDB ID " + contentId;
        JsonNode season = nominee.path("seasonNumber");
        JsonNode episode = nominee.path("episodeNumber");
        if (season.isIntegralNumber() && episode.isIntegralNumber()) {
            return "Season " + season.asInt() + ", episode " + episode.asInt();
        }
        return null;
    }

    private PreviewReviewText previewReviewText(ImportItem item) {
        if ("ABSORBED".equals(item.getOutcome())) return new PreviewReviewText(null, null, false);
        JsonNode payload = readPayload(item.getPayloadJson());
        JsonNode source = sourcePayload(payload);
        JsonNode fields = payload.path("resolution").path("fields");
        JsonNode value = fields.path("REVIEW").isTextual() ? fields.path("REVIEW") : source.path("review");
        if (!value.isTextual()) return new PreviewReviewText(null, null, false);
        String normalized = reviewTextNormalizer.normalize(value.asString());
        if (normalized == null) return new PreviewReviewText(null, null, false);
        int length = normalized.length();
        boolean truncated = length > ImportedReviewTextNormalizer.MAX_REVIEW_LENGTH;
        int previewLength = Math.min(length, ImportedReviewTextNormalizer.MAX_REVIEW_LENGTH);
        if (truncated && previewLength > 0 && Character.isHighSurrogate(normalized.charAt(previewLength - 1))
                && Character.isLowSurrogate(normalized.charAt(previewLength))) {
            previewLength--;
        }
        String preview = normalized.substring(0, previewLength);
        return new PreviewReviewText(preview, length, truncated);
    }

    private PreviewWatchDate previewWatchDate(ImportItem item) {
        if (item.getNormalizedKind() != ImportRecordKind.DIARY_EVENT
                && item.getNormalizedKind() != ImportRecordKind.SERIES_EXPANSION) {
            return new PreviewWatchDate(null, null, null);
        }
        JsonNode payload = readPayload(item.getPayloadJson());
        JsonNode source = payload.path("source").isObject() ? payload.path("source") : payload;
        JsonNode fields = payload.path("resolution").path("fields");
        String editedWatchedDate = textual(fields.get("WATCHED_DATE"));
        if (editedWatchedDate != null) {
            try {
                return new PreviewWatchDate(LocalDate.parse(editedWatchedDate), null, ImportDatePrecision.DAY);
            } catch (DateTimeParseException ignored) {
                return new PreviewWatchDate(null, null, ImportDatePrecision.UNKNOWN);
            }
        }
        String watchedAt = textual(source.get("watchedAt"));
        if (watchedAt != null) {
            try {
                return new PreviewWatchDate(null, Instant.parse(watchedAt), ImportDatePrecision.TIMESTAMP);
            } catch (DateTimeParseException ignored) {
                return new PreviewWatchDate(null, null, ImportDatePrecision.UNKNOWN);
            }
        }
        String watchedDate = textual(source.get("watchedDate"));
        if (watchedDate == null) watchedDate = textual(source.get("watchedOn"));
        if (watchedDate != null) {
            try {
                return new PreviewWatchDate(LocalDate.parse(watchedDate), null, ImportDatePrecision.DAY);
            } catch (DateTimeParseException ignored) {
                return new PreviewWatchDate(null, null, ImportDatePrecision.UNKNOWN);
            }
        }
        return new PreviewWatchDate(null, null, ImportDatePrecision.UNKNOWN);
    }

    private String textual(JsonNode value) {
        return value != null && value.isTextual() && !value.asString().isBlank() ? value.asString() : null;
    }

    private JsonNode optionalJson(String value) {
        return value == null || value.isBlank() ? null : readPayload(value);
    }

    private ImportPlanVersion version(ImportJob job, long version) {
        return new ImportPlanVersion(version, digest(job.getId(), version, job.getSelectionJson()));
    }

    private String digest(UUID jobId, long version, String selectionJson) {
        String canonical = "{\"jobId\":\"" + jobId + "\",\"previewVersion\":" + version
                + ",\"selection\":" + selectionJson + "}";
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private int parseInteger(String value, String label) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new BadRequestException(label + " must be an integer");
        }
    }

    private String writeJson(Object value) {
        try {
            String json = objectMapper.writeValueAsString(value);
            if (json.getBytes(StandardCharsets.UTF_8).length > importLimits.maxFieldBytes()) {
                throw new BadRequestException("Resolution data exceeds the maximum field size");
            }
            return json;
        } catch (JacksonException exception) {
            throw new IllegalStateException("Import plan data could not be serialized", exception);
        }
    }

    private record CandidateEnvelope(AliasQuery query, Map<String, ImportTarget> candidates) {
    }

    private record PreparedTargetResolution(ImportTarget target, AliasQuery query) {
    }

    private record ResolutionMetadata(ImportDecision decision, String overlayKey) {
    }

    private record PreviewWatchDate(LocalDate date, Instant timestamp, ImportDatePrecision precision) {
    }

    private record PreviewItemTitle(String text, Integer length, boolean truncated) {
    }

    private record PreviewReviewText(String text, Integer length, boolean truncated) {
    }

    private record PreviewItemDescription(String text, Integer length, boolean truncated) {
    }

    private record PreviewTemplateEligibility(LocalDate start, LocalDate end) {
    }
}
