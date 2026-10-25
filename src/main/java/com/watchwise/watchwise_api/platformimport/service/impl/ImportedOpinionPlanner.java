package com.watchwise.watchwise_api.platformimport.service.impl;

import com.watchwise.watchwise_api.content.dto.MediaReference;
import com.watchwise.watchwise_api.contentreview.service.HistoricalOpinion;
import com.watchwise.watchwise_api.platformimport.entity.ImportItem;
import com.watchwise.watchwise_api.platformimport.model.ImportDecision;
import com.watchwise.watchwise_api.platformimport.model.ImportRecordKind;
import com.watchwise.watchwise_api.platformimport.model.ImportSourceKey;
import com.watchwise.watchwise_api.platformimport.model.ImportTarget;
import com.watchwise.watchwise_api.platformimport.model.ImportedOpinionUnit;
import com.watchwise.watchwise_api.platformimport.model.NormalizedHistoryPlan;
import com.watchwise.watchwise_api.platformimport.repository.ImportItemRepository;
import com.watchwise.watchwise_api.platformimport.service.ImportExecutionContext;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

@Component
public class ImportedOpinionPlanner {
    private static final int MAX_BATCH_SIZE = 200;
    private static final List<ImportRecordKind> OPINION_KINDS = List.of(
            ImportRecordKind.REVIEW, ImportRecordKind.CURRENT_RATING);
    private static final List<String> ACTIVE_OUTCOMES = List.of("MATCHED", "RESOLVED");

    private final ImportItemRepository itemRepository;
    private final ObjectMapper objectMapper;
    private final ImportedReviewTextNormalizer reviewTextNormalizer;

    public ImportedOpinionPlanner(ImportItemRepository itemRepository, ObjectMapper objectMapper,
                                  ImportedReviewTextNormalizer reviewTextNormalizer) {
        this.itemRepository = itemRepository;
        this.objectMapper = objectMapper;
        this.reviewTextNormalizer = reviewTextNormalizer;
    }

    public List<ImportedOpinionUnit> nextBatch(ImportExecutionContext context, NormalizedHistoryPlan historyPlan,
                                                UUID afterItemId, int requestedSize) {
        if (context == null || historyPlan == null || !context.jobId().equals(historyPlan.jobId())) {
            throw new IllegalArgumentException("Opinion planning context does not match the history plan");
        }
        if (requestedSize < 1) throw new IllegalArgumentException("Opinion batch size must be positive");

        long afterOrdinal = -1;
        UUID cursorId = new UUID(0, 0);
        if (afterItemId != null) {
            ImportItem cursor = itemRepository.findById(afterItemId)
                    .filter(item -> context.jobId().equals(item.getJobId())
                            && context.planGeneration() == item.getPlanGeneration())
                    .orElseThrow(() -> new IllegalArgumentException("Opinion cursor does not belong to this plan"));
            afterOrdinal = cursor.getOrdinal();
            cursorId = cursor.getId();
        }

        int size = Math.min(requestedSize, MAX_BATCH_SIZE);
        return itemRepository.findHistoryRowsAfter(context.jobId(), context.ownerId(), context.planGeneration(),
                        OPINION_KINDS, ACTIVE_OUTCOMES, afterOrdinal, cursorId, PageRequest.of(0, size))
                .stream()
                .filter(item -> item.getDecision() != ImportDecision.SKIP_ITEM)
                .map(item -> toUnit(context, item))
                .toList();
    }

    public ImportedOpinionUnit unit(ImportExecutionContext context, UUID itemId) {
        if (context == null || itemId == null) throw new IllegalArgumentException("Opinion item is required");
        ImportItem item = itemRepository.findById(itemId)
                .filter(candidate -> context.jobId().equals(candidate.getJobId())
                        && context.planGeneration() == candidate.getPlanGeneration()
                        && OPINION_KINDS.contains(candidate.getNormalizedKind())
                        && ACTIVE_OUTCOMES.contains(candidate.getOutcome()))
                .orElseThrow(() -> new IllegalArgumentException("Opinion item is not pending in this import plan"));
        return toUnit(context, item);
    }

    private ImportedOpinionUnit toUnit(ImportExecutionContext context, ImportItem item) {
        if (!context.sourceNamespace().equals(item.getSourceNamespace())) {
            throw new IllegalStateException("Opinion item belongs to another source namespace");
        }
        ImportTarget target = readTarget(item.getResolvedTargetJson());
        if (!(target instanceof ImportTarget.ContentTarget contentTarget)) {
            throw new IllegalStateException("Opinion item does not resolve to content");
        }
        MediaReference content = contentTarget.content();
        JsonNode payload = effectivePayload(item.getPayloadJson());
        Integer score = score(payload);
        String text = reviewTextNormalizer.normalize(text(payload.path("review")));
        HistoricalOpinion review = text == null || text.isBlank() ? null
                : new HistoricalOpinion(content, positiveWatchNumber(payload), text, score,
                null, publishedOn(payload), text(payload.path("contextJson")));
        Integer initialScore = item.getNormalizedKind() == ImportRecordKind.CURRENT_RATING || review == null
                ? score : null;
        return new ImportedOpinionUnit(item.getId().toString(),
                new ImportSourceKey(item.getSourceNamespace(), item.getObjectKind(), item.getSourceId()),
                content, review, initialScore);
    }

    private ImportTarget readTarget(String json) {
        try {
            return objectMapper.treeToValue(objectMapper.readTree(json), ImportTarget.class);
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new IllegalStateException("Resolved opinion target is invalid", exception);
        }
    }

    private JsonNode effectivePayload(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode source = root.path("source").isObject() ? root.path("source") : root;
            if (!root.path("resolution").path("fields").isObject()) return source;
            var effective = source.deepCopy();
            JsonNode fields = root.path("resolution").path("fields");
            if (fields.path("REVIEW").isTextual()) effective = set(effective, "review", fields.path("REVIEW"));
            if (fields.path("RATING").isTextual()) effective = set(effective, "score", fields.path("RATING"));
            return effective;
        } catch (JacksonException exception) {
            throw new IllegalStateException("Opinion item payload is invalid", exception);
        }
    }

    private JsonNode set(JsonNode target, String field, JsonNode value) {
        if (!target.isObject()) return target;
        ObjectNode copy = (ObjectNode) target.deepCopy();
        if (field.equals("score")) {
            try {
                copy.put(field, Integer.parseInt(value.asString()));
            } catch (NumberFormatException exception) {
                throw new IllegalStateException("Edited opinion score is invalid", exception);
            }
        } else {
            copy.set(field, value.deepCopy());
        }
        return copy;
    }

    private Integer score(JsonNode payload) {
        JsonNode value = payload.path("score");
        if (value.isMissingNode() || value.isNull()) return null;
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 1 || value.intValue() > 10) {
            throw new IllegalStateException("Normalized opinion score is invalid");
        }
        return value.intValue();
    }

    private int positiveWatchNumber(JsonNode payload) {
        JsonNode value = payload.path("watchNumber");
        return value.isIntegralNumber() && value.canConvertToInt() && value.intValue() > 0
                ? value.intValue() : 1;
    }

    private LocalDate publishedOn(JsonNode payload) {
        JsonNode value = payload.path("sourceRecordedOn");
        if (!value.isTextual() || value.asString().isBlank()) return null;
        try {
            return LocalDate.parse(value.asString());
        } catch (DateTimeParseException exception) {
            throw new IllegalStateException("Opinion publication date is invalid", exception);
        }
    }

    private String text(JsonNode node) {
        return node.isTextual() ? node.asString() : null;
    }
}
