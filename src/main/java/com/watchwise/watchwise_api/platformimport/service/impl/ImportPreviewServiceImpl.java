package com.watchwise.watchwise_api.platformimport.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.pagination.PageRequestFactory;
import com.watchwise.watchwise_api.content.dto.MediaReference;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.platformimport.dto.ImportPreviewItemDTO;
import com.watchwise.watchwise_api.platformimport.entity.ImportItem;
import com.watchwise.watchwise_api.platformimport.entity.ImportJob;
import com.watchwise.watchwise_api.platformimport.model.ImportDecision;
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
import com.watchwise.watchwise_api.platformimport.repository.ImportItemRepository;
import com.watchwise.watchwise_api.platformimport.repository.ImportJobRepository;
import com.watchwise.watchwise_api.platformimport.service.ImportLimits;
import com.watchwise.watchwise_api.platformimport.service.ExternalContentAliasService;
import com.watchwise.watchwise_api.platformimport.service.ImportModeRegistry;
import com.watchwise.watchwise_api.platformimport.service.ImportPreviewService;
import com.watchwise.watchwise_api.userlist.entity.UserList;
import com.watchwise.watchwise_api.userlist.repository.UserListRepository;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
    private final ImportModeRegistry importModeRegistry;
    private final ExternalContentAliasService externalContentAliasService;
    private final UserListRepository userListRepository;
    private final ObjectMapper objectMapper;
    private final ImportLimits importLimits;
    private final Clock clock;
    private final PageRequestFactory pageRequestFactory;

    public ImportPreviewServiceImpl(
            ImportJobRepository importJobRepository,
            ImportItemRepository importItemRepository,
            ImportModeRegistry importModeRegistry,
            ExternalContentAliasService externalContentAliasService,
            UserListRepository userListRepository,
            ObjectMapper objectMapper,
            ImportLimits importLimits,
            Clock clock,
            PageRequestFactory pageRequestFactory) {
        this.importJobRepository = importJobRepository;
        this.importItemRepository = importItemRepository;
        this.importModeRegistry = importModeRegistry;
        this.externalContentAliasService = externalContentAliasService;
        this.userListRepository = userListRepository;
        this.objectMapper = objectMapper;
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

        long nextVersion = nextVersion(job.getPreviewVersion());
        long nextGeneration = nextVersion(readPlanGeneration(job.getSelectionJson()));
        String storedSelection = serializeStoredPlan(selection, nextGeneration);
        LocalDateTime now = LocalDateTime.now(clock);

        job.setPreviewVersion(nextVersion);
        job.setSelectionJson(storedSelection);
        job.setState(job.getInputKind() == ImportInputKind.PLATFORM_ARCHIVE
                ? ImportJobState.ANALYZING : ImportJobState.RESOLVING);
        job.setWorkPhase(job.getInputKind() == ImportInputKind.PLATFORM_ARCHIVE ? "NORMALIZE" : "RESOLVE");
        job.setWorkPending(true);
        job.setNextRunAt(now);
        job.setUpdatedAt(now);
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
        for (ImportResolutionOperation decision : decisions) {
            if (!changedItemIds.add(decision.itemId())) {
                throw new BadRequestException("Only one resolution per item is allowed in a request");
            }
            PreparedTargetResolution preparedTarget = validateOperation(ownerId, decision, itemsById);
            if (preparedTarget != null) preparedTargets.put(decision.itemId(), preparedTarget);
        }
        validateFixedCategoryConstraints(jobId, generation, decisions, itemsById);

        for (ImportResolutionOperation decision : decisions) {
            applyDecision(ownerId, decision, itemsById, preparedTargets.get(decision.itemId()));
        }
        importItemRepository.saveAll(changedItems(decisions, itemsById));

        long nextVersion = nextVersion(job.getPreviewVersion());
        LocalDateTime now = LocalDateTime.now(clock);
        job.setPreviewVersion(nextVersion);
        job.setState(ImportJobState.RESOLVING);
        job.setWorkPhase("RESOLVE");
        job.setWorkPending(true);
        job.setNextRunAt(now);
        job.setUpdatedAt(now);
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
                .map(this::toPreviewItem);
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
            UUID ownerId, ImportResolutionOperation operation, Map<UUID, ImportItem> items) {
        ImportItem item = requireItem(operation.itemId(), items);
        if (operation instanceof ImportResolutionOperation.ResolveTarget resolveTarget) {
            return prepareTargetResolution(item, resolveTarget);
        } else if (operation instanceof ImportResolutionOperation.EditField editField) {
            validateEditedField(item, editField);
        } else if (operation instanceof ImportResolutionOperation.AssignPass assignPass) {
            if (assignPass.passNumber() < 1 || item.getNormalizedKind() != ImportRecordKind.DIARY_EVENT
                    && item.getNormalizedKind() != ImportRecordKind.SERIES_EXPANSION) {
                throw new BadRequestException("Pass assignment is not valid for this import item");
            }
        } else if (operation instanceof ImportResolutionOperation.ConfirmWatchScope confirmScope) {
            if (item.getNormalizedKind() != ImportRecordKind.WATCHED_SUMMARY
                    && item.getNormalizedKind() != ImportRecordKind.WATCHED_MARKER
                    && item.getNormalizedKind() != ImportRecordKind.SERIES_EXPANSION) {
                throw new BadRequestException("Watch scope is not valid for this import item");
            }
            if (confirmScope.scope() == ImportResolutionOperation.WatchScope.SPECIFIC_EPISODES) {
                String seriesId = confirmScope.episodeReferences().getFirst().seriesTmdbId();
                if (confirmScope.episodeReferences().stream()
                        .anyMatch(reference -> !seriesId.equals(reference.seriesTmdbId()))) {
                    throw new BadRequestException("A watch scope can contain episodes from only one series");
                }
            }
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
                && item.getNormalizedKind() == ImportRecordKind.TEMPLATE_CATEGORY
                && isFixedCategory(item)) {
            throw new BadRequestException("A fixed template category must be removed explicitly");
        }
        return null;
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
            case TITLE, DESCRIPTION, REVIEW, CATEGORY_NAME -> {
                if (value.isBlank()) throw new BadRequestException("Edited field cannot be blank");
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

    private boolean isEditableForKind(
            ImportRecordKind kind, ImportResolutionOperation.EditableField field) {
        return switch (field) {
            case TITLE -> Set.of(ImportRecordKind.DIARY_EVENT, ImportRecordKind.REVIEW,
                    ImportRecordKind.WATCHED_MARKER, ImportRecordKind.WATCHED_SUMMARY,
                    ImportRecordKind.CURRENT_RATING, ImportRecordKind.LIST_METADATA,
                    ImportRecordKind.LIST_ITEM, ImportRecordKind.WATCHLIST_ITEM,
                    ImportRecordKind.SERIES_EXPANSION, ImportRecordKind.TEMPLATE,
                    ImportRecordKind.TEMPLATE_CATEGORY, ImportRecordKind.TEMPLATE_OPTION).contains(kind);
            case DESCRIPTION -> kind == ImportRecordKind.LIST_METADATA || kind == ImportRecordKind.TEMPLATE;
            case RELEASE_YEAR -> Set.of(ImportRecordKind.DIARY_EVENT, ImportRecordKind.REVIEW,
                    ImportRecordKind.WATCHED_MARKER, ImportRecordKind.WATCHED_SUMMARY,
                    ImportRecordKind.CURRENT_RATING, ImportRecordKind.LIST_ITEM,
                    ImportRecordKind.WATCHLIST_ITEM, ImportRecordKind.SERIES_EXPANSION,
                    ImportRecordKind.TEMPLATE_OPTION).contains(kind);
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

    private void applyDecision(UUID ownerId, ImportResolutionOperation operation, Map<UUID, ImportItem> items,
                               PreparedTargetResolution preparedTarget) {
        ImportItem item = items.get(operation.itemId());
        ResolutionMetadata metadata = resolutionMetadata(operation);
        if (operation instanceof ImportResolutionOperation.ResolveTarget) {
            externalContentAliasService.remember(ownerId, preparedTarget.query(), preparedTarget.target(),
                    AliasTrust.USER_CONFIRMED);
        }
        item.setDecision(metadata.decision());
        item.setPayloadJson(appendDecisionOverlay(item, operation, metadata.overlayKey()));
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

    private List<ImportItem> changedItems(List<ImportResolutionOperation> decisions, Map<UUID, ImportItem> items) {
        LinkedHashSet<UUID> ids = new LinkedHashSet<>();
        for (ImportResolutionOperation decision : decisions) {
            ids.add(decision.itemId());
            if (decision instanceof ImportResolutionOperation.ResolveReviewOrder order) ids.add(order.beforeItemId());
        }
        return ids.stream().map(items::get).toList();
    }

    private ImportPreviewItemDTO toPreviewItem(ImportItem item) {
        return new ImportPreviewItemDTO(item.getId(), item.getNormalizedKind(), item.getDecision(), item.getOutcome());
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
}
