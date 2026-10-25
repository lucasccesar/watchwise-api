package com.watchwise.watchwise_api.platformimport.service.impl;

import com.watchwise.watchwise_api.content.dto.MediaReference;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.contentrating.service.ContentRatingService;
import com.watchwise.watchwise_api.contentreview.service.HistoricalOpinion;
import com.watchwise.watchwise_api.contentreview.service.HistoricalOpinionRestoreService;
import com.watchwise.watchwise_api.contentreview.service.HistoricalViewingEvidence;
import com.watchwise.watchwise_api.contentreview.service.HistoricalViewingEvidenceVerifier;
import com.watchwise.watchwise_api.diaryentry.dto.WatchEventCommand;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.platformimport.entity.ImportItem;
import com.watchwise.watchwise_api.platformimport.model.ImportDecision;
import com.watchwise.watchwise_api.platformimport.model.ImportRecordKind;
import com.watchwise.watchwise_api.platformimport.model.ImportTarget;
import com.watchwise.watchwise_api.platformimport.model.SeriesExpansionPlan;
import com.watchwise.watchwise_api.platformimport.repository.ExternalImportLinkRepository;
import com.watchwise.watchwise_api.platformimport.repository.ImportItemRepository;
import com.watchwise.watchwise_api.platformimport.repository.ImportJobRepository;
import com.watchwise.watchwise_api.platformimport.service.ImportExecutionContext;
import com.watchwise.watchwise_api.platformimport.service.ImportLedger;
import com.watchwise.watchwise_api.platformimport.service.ImportUnitApplier;
import com.watchwise.watchwise_api.platformimport.service.ImportUnitTerminalListener;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class LetterboxdSeriesExpansionApplier implements ImportUnitApplier, ImportUnitTerminalListener {
    static final String EXPANSION_FIELD = "_watchwiseExpansion";
    static final int STAGING_BATCH_SIZE = 200;

    private final ImportItemRepository itemRepository;
    private final ImportJobRepository jobRepository;
    private final ExternalImportLinkRepository linkRepository;
    private final ContentRepository contentRepository;
    private final DiaryEntryRepository diaryEntryRepository;
    private final ImportLedger ledger;
    private final LetterboxdSeriesExpansionPlanner planner;
    private final HistoricalOpinionRestoreService opinionRestoreService;
    private final HistoricalViewingEvidenceVerifier evidenceVerifier;
    private final ContentRatingService contentRatingService;
    private final ObjectMapper objectMapper;
    private final EntityManager entityManager;
    private final Clock clock;

    public LetterboxdSeriesExpansionApplier(ImportItemRepository itemRepository,
                                            ImportJobRepository jobRepository,
                                            ExternalImportLinkRepository linkRepository,
                                            ContentRepository contentRepository,
                                            DiaryEntryRepository diaryEntryRepository,
                                            ImportLedger ledger,
                                            LetterboxdSeriesExpansionPlanner planner,
                                            HistoricalOpinionRestoreService opinionRestoreService,
                                            HistoricalViewingEvidenceVerifier evidenceVerifier,
                                            ContentRatingService contentRatingService,
                                            ObjectMapper objectMapper,
                                            EntityManager entityManager,
                                            Clock clock) {
        this.itemRepository = itemRepository;
        this.jobRepository = jobRepository;
        this.linkRepository = linkRepository;
        this.contentRepository = contentRepository;
        this.diaryEntryRepository = diaryEntryRepository;
        this.ledger = ledger;
        this.planner = planner;
        this.opinionRestoreService = opinionRestoreService;
        this.evidenceVerifier = evidenceVerifier;
        this.contentRatingService = contentRatingService;
        this.objectMapper = objectMapper;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    @Override
    public boolean supports(ImportRecordKind kind) {
        return kind == ImportRecordKind.SERIES_EXPANSION;
    }

    @Override
    public void apply(ImportExecutionContext context, UUID unitId) {
        ImportItem parent = itemRepository.findById(unitId)
                .orElseThrow(() -> new IllegalStateException("Staged series expansion was not found"));
        validateContext(context, parent);
        ObjectNode payload = parsePayload(parent.getPayloadJson());
        JsonNode stored = expansionMetadata(payload);
        if (stored != null) {
            parent.setOutcome("EXPANDING");
            return;
        }

        SeriesExpansionPlan plan = planner.plan(context.ownerId(), parent, Instant.now(clock));
        if (plan.children().isEmpty()) {
            throw new LetterboxdSeriesExpansionPlanner.SeriesExpansionIssueException(
                    "SERIES_EXPANSION_EMPTY: No child events were selected from the saved calendar.");
        }
        long nextOrdinal = itemRepository.findMaxOrdinal(parent.getJobId(), parent.getPlanGeneration());
        if (nextOrdinal > Long.MAX_VALUE - plan.children().size()) {
            throw new LetterboxdSeriesExpansionPlanner.SeriesExpansionIssueException(
                    "SERIES_EXPANSION_VOLUME: Child event ordinals exceed the supported record range.");
        }

        List<ImportItem> batch = new ArrayList<>(STAGING_BATCH_SIZE);
        for (int index = 0; index < plan.children().size(); index++) {
            WatchEventCommand command = plan.children().get(index);
            batch.add(childItem(context, parent, plan, command, nextOrdinal + index + 1,
                    plan.childPassIssues().get(index), false));
            if (batch.size() == STAGING_BATCH_SIZE) persistBatch(batch);
        }
        if (!batch.isEmpty()) persistBatch(batch);

        payload.set(EXPANSION_FIELD, expansionMetadata(plan, nextOrdinal));
        parent.setPayloadJson(writeJson(payload));
        parent.setOutcome("EXPANDING");
    }

    @Override
    public boolean isComplete(ImportExecutionContext context, UUID unitId) {
        ImportItem parent = itemRepository.findById(unitId)
                .orElseThrow(() -> new IllegalStateException("Staged series expansion was not found"));
        validateContext(context, parent);
        return "APPLIED".equals(parent.getOutcome());
    }

    public boolean hasPreparedPlan(ImportItem parent) {
        return expansionMetadata(parsePayload(parent.getPayloadJson())) != null;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int stageForPreview(ImportExecutionContext context, UUID parentId, SeriesExpansionPlan plan) {
        ImportItem parent = itemRepository.findById(parentId)
                .orElseThrow(() -> new IllegalStateException("Staged series expansion was not found"));
        validateContext(context, parent);
        if (!parent.getSourceId().equals(plan.parentKey())) {
            throw new IllegalStateException("Series expansion plan belongs to another source record");
        }
        ObjectNode payload = parsePayload(parent.getPayloadJson());
        if (expansionMetadata(payload) != null) return 0;
        if (plan.children().isEmpty()) {
            throw new LetterboxdSeriesExpansionPlanner.SeriesExpansionIssueException(
                    "SERIES_EXPANSION_EMPTY: No child events were selected from the saved calendar.");
        }
        long nextOrdinal = itemRepository.findMaxOrdinal(parent.getJobId(), parent.getPlanGeneration());
        if (nextOrdinal > Long.MAX_VALUE - plan.children().size()) {
            throw new LetterboxdSeriesExpansionPlanner.SeriesExpansionIssueException(
                    "SERIES_EXPANSION_VOLUME: Child event ordinals exceed the supported record range.");
        }

        int issueCount = 0;
        List<ImportItem> batch = new ArrayList<>(STAGING_BATCH_SIZE);
        for (int index = 0; index < plan.children().size(); index++) {
            String childIssue = plan.childPassIssues().get(index);
            if (childIssue != null) issueCount++;
            batch.add(childItem(context, parent, plan, plan.children().get(index), nextOrdinal + index + 1,
                    childIssue, true));
            if (batch.size() == STAGING_BATCH_SIZE) persistBatch(batch);
        }
        if (!batch.isEmpty()) persistBatch(batch);

        payload.set(EXPANSION_FIELD, expansionMetadata(plan, nextOrdinal));
        parent.setPayloadJson(writeJson(payload));
        parent.setIssue(null);
        parent.setOutcome("EXPANSION_PREPARED");
        itemRepository.saveAndFlush(parent);
        return issueCount;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markPreviewIssue(ImportExecutionContext context, UUID parentId, String issue) {
        ImportItem parent = itemRepository.findById(parentId)
                .orElseThrow(() -> new IllegalStateException("Staged series expansion was not found"));
        validateContext(context, parent);
        if (hasPreparedPlan(parent)) return;
        parent.setOutcome("NORMALIZATION_ISSUE");
        parent.setIssue(issue);
        itemRepository.saveAndFlush(parent);
    }

    @Override
    public void onTerminal(ImportExecutionContext context, ImportItem child) {
        if (child.getParentSourceId() == null || !"series_diary_child".equals(child.getObjectKind())) return;
        List<ImportItem> parents = itemRepository.findAllByJobIdAndPlanGenerationAndSourceIdAndNormalizedKind(
                context.jobId(), context.planGeneration(), child.getParentSourceId(), ImportRecordKind.SERIES_EXPANSION);
        if (parents.isEmpty()) return;
        if (parents.size() != 1) throw new IllegalStateException("Series expansion parent identity is ambiguous");
        ImportItem parent = parents.getFirst();
        ObjectNode payload = parsePayload(parent.getPayloadJson());
        JsonNode metadata = expansionMetadata(payload);
        ObjectNode opinionPayload = effectiveParentPayload(payload);
        if (metadata == null || !metadata.isObject() || !"EXPANDING".equals(parent.getOutcome())) return;
        int expectedChildren = metadata.path("childCount").asInt(-1);
        long lastChildOrdinal = metadata.path("lastChildOrdinal").asLong(-1);
        if (expectedChildren <= 0 || lastChildOrdinal < 0) {
            throw new IllegalStateException("Stored series expansion child boundary is invalid");
        }
        if (child.getOrdinal() != lastChildOrdinal) return;

        long appliedChildren = itemRepository.countByJobIdAndPlanGenerationAndParentSourceIdAndNormalizedKindAndObjectKindAndOutcome(
                context.jobId(), context.planGeneration(), parent.getSourceId(), ImportRecordKind.DIARY_EVENT,
                "series_diary_child", "APPLIED");
        long skippedChildren = itemRepository.countByJobIdAndPlanGenerationAndParentSourceIdAndNormalizedKindAndObjectKindAndOutcome(
                context.jobId(), context.planGeneration(), parent.getSourceId(), ImportRecordKind.DIARY_EVENT,
                "series_diary_child", "SKIPPED");
        if (!"APPLIED".equals(child.getOutcome()) && !"SKIPPED".equals(child.getOutcome())) {
            appliedChildren++;
        }
        if (appliedChildren + skippedChildren != expectedChildren) {
            throw new IllegalStateException("Final series child reached before every staged child was terminal");
        }
        if (skippedChildren > 0) {
            pauseParentForIssue(context, parent,
                    "SERIES_CHILD_SKIPPED: A child episode was skipped or already consumed; explicitly skip the parent to leave its opinion unrestored.");
            return;
        }

        List<UUID> eventIds = linkRepository.findAppliedSeriesChildDiaryIds(context.ownerId(),
                parent.getSourceNamespace(), parent.getSourceId() + ":");
        if (eventIds.size() != appliedChildren) {
            pauseParentForIssue(context, parent,
                    "SERIES_CHILD_EVIDENCE_MISSING: Applied episode links do not match the frozen child set; explicitly skip the parent to leave its opinion unrestored.");
            return;
        }
        Set<Integer> selectedPasses = diaryEntryRepository.findAllById(eventIds).stream()
                .map(com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry::getWatchNumber)
                .collect(java.util.stream.Collectors.toSet());
        if (selectedPasses.size() > 1) {
            if (hasParentOpinion(opinionPayload)) {
                pauseParentForIssue(context, parent,
                        "SERIES_PASS_CONFLICT: Episode diary entries were retained on different passes. "
                                + "The parent review and rating were not restored; explicitly skip the parent item to acknowledge this.");
                return;
            }
            MediaReference series = parentContent(parent);
            markParentApplied(context, parent, series);
            parent.setIssue(null);
            itemRepository.save(parent);
            return;
        }
        MediaReference series = parentContent(parent);
        int targetPass = selectedPasses.size() == 1 ? selectedPasses.iterator().next()
                : metadata.path("targetPass").asInt(0);
        String scopeHash = metadata.path("scopeHash").asString(null);
        if (!eventIds.isEmpty()) {
            HistoricalViewingEvidence evidence = new HistoricalViewingEvidence(
                    series, targetPass, scopeHash, Set.copyOf(eventIds));
            Integer currentRating = integer(opinionPayload, "currentRatingScore");
            Integer reviewScore = integer(opinionPayload, "reviewScoreAtPublication");
            String reviewText = text(opinionPayload, "review");
            boolean reviewProvidesCurrentScore = reviewText != null && !reviewText.isBlank()
                    && currentRating != null && currentRating.equals(reviewScore);
            if (currentRating != null && !reviewProvidesCurrentScore) {
                if (eventIds.size() != expectedChildren) {
                    pauseParentForIssue(context, parent,
                            "SERIES_RATING_EVIDENCE_INCOMPLETE: The frozen child set does not prove a complete current series pass; explicitly skip the parent to leave its rating unrestored.");
                    return;
                }
                Content seriesEntity = contentRepository.findByTmdbIdAndType(series.tmdbId(), ContentType.SERIES)
                        .orElseThrow(() -> new IllegalStateException("Resolved series content is missing"));
                HistoricalViewingEvidenceVerifier.Verification verification = evidenceVerifier.verify(
                        context.ownerId(), seriesEntity, evidence, false, Instant.now(clock));
                if (!verification.completionScopeProven()) {
                    pauseParentForIssue(context, parent,
                            "SERIES_RATING_EVIDENCE_INCOMPLETE: The current calendar no longer proves the frozen series pass; explicitly skip the parent to leave its rating unrestored.");
                    return;
                }
                contentRatingService.restoreInitial(context.ownerId(), series, currentRating, evidence);
            }
            restoreParentOpinion(context.ownerId(), series, targetPass, opinionPayload, evidence);
        }

        markParentApplied(context, parent, series);
        parent.setIssue(null);
        itemRepository.save(parent);
    }

    private void markParentApplied(ImportExecutionContext context, ImportItem parent, MediaReference series) {
        Content entity = contentRepository.findByTmdbIdAndType(series.tmdbId(), ContentType.SERIES)
                .orElseThrow(() -> new IllegalStateException("Resolved series content is missing"));
        ledger.markApplied(context.ownerId(), parentKey(parent), "CONTENT", entity.getId());
        parent.setOutcome("APPLIED");
    }

    private void pauseParentForIssue(ImportExecutionContext context, ImportItem parent, String issue) {
        parent.setOutcome("NORMALIZATION_ISSUE");
        parent.setIssue(issue);
        itemRepository.save(parent);
        var job = jobRepository.findByIdForUpdate(context.jobId()).orElseThrow();
        job.setState(com.watchwise.watchwise_api.platformimport.model.ImportJobState.READY);
        job.setWorkPending(false);
        job.setNextRunAt(null);
        job.setLeaseToken(null);
        job.setLeaseUntil(null);
    }

    private ImportItem childItem(ImportExecutionContext context, ImportItem parent, SeriesExpansionPlan plan,
                                 WatchEventCommand command, long ordinal, String issue, boolean previewIssue) {
        String coordinates = command.content().type() == ContentType.MOVIE
                ? "MOVIE:" + command.content().tmdbId()
                : command.content().seriesTmdbId() + ":" + command.content().seasonNumber()
                + ":" + command.content().episodeNumber();
        String sourceId = parent.getSourceId() + ":" + coordinates;
        UUID id = UUID.nameUUIDFromBytes((context.jobId() + ":" + context.planGeneration() + ":" + sourceId)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ObjectNode source = objectMapper.createObjectNode();
        if (command.watchedDate() != null) source.put("watchedDate", command.watchedDate().toString());
        ObjectNode fields = objectMapper.createObjectNode();
        if (command.watchedDate() != null) fields.put("watchedDate", command.watchedDate().toString());
        if (Boolean.TRUE.equals(command.watchedInTheater())) fields.put("watchedInTheater", true);
        ObjectNode resolution = objectMapper.createObjectNode();
        resolution.set("fields", fields);
        ImportDecision decision = null;
        if (command.requestedWatchNumber() != null) {
            ObjectNode assignPass = objectMapper.createObjectNode();
            assignPass.put("passNumber", command.requestedWatchNumber());
            resolution.set("assignPass", assignPass);
            decision = ImportDecision.ASSIGN_PASS;
        }
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("_watchwiseResolution", 1);
        payload.set("source", source);
        payload.set("resolution", resolution);
        try {
            String target = objectMapper.writeValueAsString(new ImportTarget.ContentTarget(command.content()));
            return ImportItem.builder().id(id).jobId(context.jobId()).planGeneration(context.planGeneration())
                    .normalizedKind(ImportRecordKind.DIARY_EVENT).sourceNamespace(parent.getSourceNamespace())
                    .objectKind("series_diary_child").sourceId(sourceId).sourceEventKey(sourceId)
                    .parentSourceId(parent.getSourceId()).parserVersion(parent.getParserVersion())
                    .payloadJson(writeJson(payload)).resolvedTargetJson(target)
                    .resolvedContentKey(contentKey(command.content())).decision(decision).issue(issue)
                    .outcome(issue != null && previewIssue ? "NORMALIZATION_ISSUE" : "MATCHED")
                    .ordinal(ordinal).build();
        } catch (JacksonException exception) {
            throw new IllegalStateException("Series child target could not be serialized", exception);
        }
    }

    private ObjectNode expansionMetadata(SeriesExpansionPlan plan, long previousMaxOrdinal) {
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("schemaVersion", 1);
        metadata.put("targetPass", plan.targetPass());
        metadata.put("scopeHash", plan.scopeHash());
        metadata.put("selectedAt", plan.selectedAt().toString());
        metadata.put("childCount", plan.children().size());
        metadata.put("firstChildOrdinal", previousMaxOrdinal + 1);
        metadata.put("lastChildOrdinal", previousMaxOrdinal + plan.children().size());
        return metadata;
    }

    private JsonNode expansionMetadata(ObjectNode payload) {
        JsonNode metadata = payload.get(EXPANSION_FIELD);
        if (metadata != null) return metadata;
        JsonNode source = payload.path("source");
        return source.isObject() ? source.get(EXPANSION_FIELD) : null;
    }

    private ObjectNode effectiveParentPayload(ObjectNode payload) {
        JsonNode source = payload.path("source");
        if (payload.path("_watchwiseResolution").asInt(-1) != 1 || !source.isObject()) return payload;
        ObjectNode effective = (ObjectNode) source.deepCopy();
        JsonNode fields = payload.path("resolution").path("fields");
        JsonNode review = fields.has("REVIEW") ? fields.get("REVIEW") : fields.get("review");
        if (review != null && review.isTextual()) effective.set("review", review.deepCopy());
        return effective;
    }

    private void persistBatch(List<ImportItem> batch) {
        itemRepository.saveAllAndFlush(batch);
        batch.forEach(entityManager::detach);
        batch.clear();
    }

    private void restoreParentOpinion(UUID ownerId, MediaReference series, int watchNumber,
                                      JsonNode payload, HistoricalViewingEvidence evidence) {
        String comment = text(payload, "review");
        if (comment == null || comment.isBlank()) return;
        Integer score = integer(payload, "reviewScoreAtPublication");
        LocalDate publishedOn = date(payload, "reviewSourceRecordedOn", "sourceRecordedOn");
        Instant publishedAt = instant(payload, "reviewSourcePublishedAt", "sourcePublishedAt");
        opinionRestoreService.restore(ownerId,
                new HistoricalOpinion(series, watchNumber, comment, score, publishedAt, publishedOn,
                        text(payload, "reviewContextJson")), evidence);
    }

    private boolean hasParentOpinion(JsonNode payload) {
        String review = text(payload, "review");
        return integer(payload, "currentRatingScore") != null
                || integer(payload, "reviewScoreAtPublication") != null
                || (review != null && !review.isBlank());
    }

    private MediaReference parentContent(ImportItem parent) {
        try {
            ImportTarget target = objectMapper.readValue(parent.getResolvedTargetJson(), ImportTarget.class);
            if (target instanceof ImportTarget.ContentTarget contentTarget
                    && contentTarget.content().type() == ContentType.SERIES) return contentTarget.content();
            throw new IllegalStateException("Series expansion parent must target a series");
        } catch (JacksonException exception) {
            throw new IllegalStateException("Series expansion parent target is invalid", exception);
        }
    }

    private String contentKey(MediaReference media) {
        return switch (media.type()) {
            case MOVIE -> "CONTENT|MOVIE|" + media.tmdbId();
            case EPISODE -> "CONTENT|EPISODE|" + media.seriesTmdbId() + "|" + media.seasonNumber()
                    + "|" + media.episodeNumber();
            default -> throw new IllegalArgumentException("Series expansion children must be movies or episodes");
        };
    }


    private com.watchwise.watchwise_api.platformimport.model.ImportSourceKey parentKey(ImportItem parent) {
        return new com.watchwise.watchwise_api.platformimport.model.ImportSourceKey(
                parent.getSourceNamespace(), parent.getObjectKind(), parent.getSourceId());
    }

    private void validateContext(ImportExecutionContext context, ImportItem parent) {
        if (!parent.getJobId().equals(context.jobId()) || parent.getPlanGeneration() != context.planGeneration()
                || !parent.getSourceNamespace().equals(context.sourceNamespace())
                || parent.getNormalizedKind() != ImportRecordKind.SERIES_EXPANSION) {
            throw new IllegalStateException("Staged series expansion does not belong to this import plan");
        }
    }

    private ObjectNode parsePayload(String value) {
        try {
            JsonNode node = objectMapper.readTree(value);
            if (node instanceof ObjectNode objectNode) return objectNode;
            throw new IllegalStateException("Series expansion payload must be an object");
        } catch (JacksonException exception) {
            throw new IllegalStateException("Series expansion payload is invalid", exception);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Series expansion checkpoint could not be serialized", exception);
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asString() : null;
    }

    private Integer integer(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.canConvertToInt() ? value.asInt() : null;
    }

    private LocalDate date(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = text(node, field);
            if (value != null) return LocalDate.parse(value);
        }
        return null;
    }

    private Instant instant(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = text(node, field);
            if (value != null) return Instant.parse(value);
        }
        return null;
    }
}
