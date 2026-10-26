package com.watchwise.watchwise_api.platformimport.service.impl;

import com.watchwise.watchwise_api.platformimport.entity.ImportItem;
import com.watchwise.watchwise_api.platformimport.model.ImportRecordKind;
import com.watchwise.watchwise_api.platformimport.model.ImportSourceKey;
import com.watchwise.watchwise_api.platformimport.model.ImportTarget;
import com.watchwise.watchwise_api.platformimport.model.NormalizedImportRecord;
import com.watchwise.watchwise_api.platformimport.model.PreparedListImport;
import com.watchwise.watchwise_api.platformimport.model.PreparedWatchlistImport;
import com.watchwise.watchwise_api.platformimport.repository.ImportItemRepository;
import com.watchwise.watchwise_api.platformimport.service.ImportExecutionContext;
import com.watchwise.watchwise_api.platformimport.service.ImportListWriter;
import com.watchwise.watchwise_api.platformimport.service.ImportWatchlistWriter;
import com.watchwise.watchwise_api.platformimport.service.ImportUnitApplier;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

@Component
public class CollectionImportApplier implements ImportUnitApplier {
    private static final int WATCHLIST_BATCH_SIZE = 200;
    private static final UUID FIRST_ID = new UUID(0, 0);
    private final ImportItemRepository itemRepository;
    private final ImportListWriter listWriter;
    private final ImportWatchlistWriter watchlistWriter;
    private final ObjectMapper objectMapper;

    public CollectionImportApplier(ImportItemRepository itemRepository, ImportListWriter listWriter,
                                   ImportWatchlistWriter watchlistWriter,
                                   ObjectMapper objectMapper) {
        this.itemRepository = itemRepository;
        this.listWriter = listWriter;
        this.watchlistWriter = watchlistWriter;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(ImportRecordKind kind) {
        return kind == ImportRecordKind.LIST_METADATA || kind == ImportRecordKind.LIST_ITEM
                || kind == ImportRecordKind.WATCHLIST_ITEM;
    }

    @Override
    public ImportSourceKey sourceKey(ImportExecutionContext context, ImportItem item) {
        return switch (item.getNormalizedKind()) {
            case LIST_METADATA -> listWriter.operationKey(context, item);
            case LIST_ITEM -> listWriter.memberKey(context, item);
            default -> ImportUnitApplier.super.sourceKey(context, item);
        };
    }

    @Override
    public void apply(ImportExecutionContext context, UUID unitId) {
        ImportItem item = itemRepository.findById(unitId)
                .filter(candidate -> context.jobId().equals(candidate.getJobId())
                        && context.planGeneration() == candidate.getPlanGeneration())
                .orElseThrow(() -> new IllegalArgumentException("Collection item does not belong to this import"));
        if (item.getNormalizedKind() == ImportRecordKind.LIST_ITEM) {
            applyListMemberAfterParent(context, item);
            return;
        }
        if (item.getNormalizedKind() == ImportRecordKind.WATCHLIST_ITEM) {
            applyWatchlistBatch(context);
            return;
        }

        JsonNode payload = effectivePayload(item);
        String name = text(payload, "title");
        String description = text(payload, "description");
        UUID linkedListId = linkedListId(item);
        ImportSourceKey identityKey = listIdentity(item);
        ImportSourceKey operationKey = listWriter.operationKey(context, item);
        long itemCount = itemRepository.countByJobIdAndPlanGenerationAndParentSourceIdAndNormalizedKind(
                context.jobId(), context.planGeneration(), item.getSourceId(), ImportRecordKind.LIST_ITEM);
        JsonNode declaredCount = payload.get("sourceItemCount");
        Long declaredItemCount = null;
        if (declaredCount != null && !declaredCount.isNull()) {
            if (!declaredCount.isIntegralNumber() || !declaredCount.canConvertToLong() || declaredCount.longValue() < 0) {
                throw new IllegalStateException("Declared source list item count is invalid");
            }
            declaredItemCount = declaredCount.longValue();
        }
        PreparedListImport plan = new PreparedListImport(identityKey, operationKey, name, description,
                item.getId(), itemCount, declaredItemCount, linkedListId);
        if (plan.declaredItemCount() != null && plan.declaredItemCount() != plan.itemCount()) {
            throw new ListItemCountMismatchException(plan.declaredItemCount(), plan.itemCount());
        }
        listWriter.apply(context, plan);
    }

    private void applyWatchlistBatch(ImportExecutionContext context) {
        List<ImportItem> page = itemRepository.findWatchlistItemsAfter(context.jobId(), context.planGeneration(),
                -1, -1, FIRST_ID.toString(), WATCHLIST_BATCH_SIZE);
        if (page.isEmpty()) throw new IllegalStateException("Pending watchlist batch is unexpectedly empty");
        applyWatchlistPage(context, page);
    }

    private void applyWatchlistPage(ImportExecutionContext context, List<ImportItem> page) {
        java.util.ArrayList<NormalizedImportRecord> records = new java.util.ArrayList<>(page.size());
        java.util.HashSet<com.watchwise.watchwise_api.content.dto.MediaReference> sourceWatched = new java.util.HashSet<>();
        java.util.Map<UUID, ImportTarget.ContentTarget> targets = new java.util.LinkedHashMap<>();
        java.util.List<String> contentKeys = page.stream().map(ImportItem::getResolvedContentKey)
                .filter(key -> key != null && !key.isBlank()).distinct().toList();
        java.util.Set<String> watchedContentKeys = contentKeys.isEmpty() ? java.util.Set.of()
                : java.util.Set.copyOf(itemRepository.findSourceWatchEvidenceContentKeys(
                context.jobId(), context.planGeneration(), contentKeys));
        for (ImportItem item : page) {
            ImportTarget target = readTarget(item.getResolvedTargetJson());
            if (!(target instanceof ImportTarget.ContentTarget contentTarget)) {
                throw new IllegalStateException("Watchlist item must resolve to a content target");
            }
            targets.put(item.getId(), contentTarget);
        }
        java.util.List<String> seriesIds = targets.values().stream().map(ImportTarget.ContentTarget::content)
                .filter(reference -> reference.type() == com.watchwise.watchwise_api.content.entity.ContentType.SERIES)
                .map(com.watchwise.watchwise_api.content.dto.MediaReference::tmdbId).distinct().toList();
        java.util.Set<String> watchedSeriesIds = java.util.Set.of();
        if (!seriesIds.isEmpty()) {
            try {
                watchedSeriesIds = java.util.Set.copyOf(itemRepository.findSourceWatchedSeriesIds(
                        context.jobId(), context.planGeneration(), objectMapper.writeValueAsString(seriesIds)));
            } catch (JacksonException exception) {
                throw new IllegalStateException("Source watchlist evidence could not be serialized", exception);
            }
        }
        for (ImportItem item : page) {
            ImportTarget.ContentTarget contentTarget = targets.get(item.getId());
            if (watchedContentKeys.contains(item.getResolvedContentKey())) {
                sourceWatched.add(contentTarget.content());
            }
            if (contentTarget.content().type() == com.watchwise.watchwise_api.content.entity.ContentType.SERIES
                    && watchedSeriesIds.contains(contentTarget.content().tmdbId())) {
                sourceWatched.add(contentTarget.content());
            }
            records.add(new NormalizedImportRecord(item.getId().toString(), item.getNormalizedKind(),
                    new ImportSourceKey(item.getSourceNamespace(), item.getObjectKind(), item.getSourceId()),
                    item.getParentSourceId(), contentTarget, readPayload(item.getPayloadJson()),
                    Math.toIntExact(item.getOrdinal())));
        }
        watchlistWriter.apply(context, new PreparedWatchlistImport(records, sourceWatched));
    }

    @Override
    public boolean isComplete(ImportExecutionContext context, UUID unitId) {
        ImportItem item = itemRepository.findById(unitId).orElse(null);
        if (item == null) return true;
        if (item.getNormalizedKind() == ImportRecordKind.WATCHLIST_ITEM) {
            return "APPLIED".equals(item.getOutcome()) || "SKIPPED".equals(item.getOutcome());
        }
        if (item.getNormalizedKind() != ImportRecordKind.LIST_ITEM) return true;
        if ("APPLIED".equals(item.getOutcome()) || "SKIPPED".equals(item.getOutcome())) return true;
        ImportItem parent = parent(context, item);
        return parent == null || "APPLIED".equals(parent.getOutcome()) || "SKIPPED".equals(parent.getOutcome());
    }

    private void applyListMemberAfterParent(ImportExecutionContext context, ImportItem item) {
        ImportItem parent = parent(context, item);
        if (parent == null || parent.getDecision() == com.watchwise.watchwise_api.platformimport.model.ImportDecision.SKIP_ITEM
                || "SKIPPED".equals(parent.getOutcome())) {
            listWriter.skipMember(context, item, parent == null ? "LIST_PARENT_MISSING" : "LIST_PARENT_SKIPPED");
        } else if ("APPLIED".equals(parent.getOutcome())) {
            listWriter.skipMember(context, item, "LIST_PARENT_ALREADY_APPLIED");
        }
    }

    private ImportItem parent(ImportExecutionContext context, ImportItem item) {
        if (item.getParentSourceId() == null) return null;
        return itemRepository.findFirstByJobIdAndPlanGenerationAndNormalizedKindAndSourceId(
                context.jobId(), context.planGeneration(), ImportRecordKind.LIST_METADATA, item.getParentSourceId())
                .orElse(null);
    }

    private JsonNode effectivePayload(ImportItem item) {
        try {
            JsonNode root = objectMapper.readTree(item.getPayloadJson());
            JsonNode source = root.path("source").isObject() ? root.path("source") : root;
            JsonNode fields = root.path("resolution").path("fields");
            if (!fields.path("TITLE").isTextual() && !fields.path("DESCRIPTION").isTextual()) return source;
            var copy = source.deepCopy();
            var object = (tools.jackson.databind.node.ObjectNode) copy;
            if (fields.path("TITLE").isTextual()) object.set("title", fields.path("TITLE").deepCopy());
            if (fields.path("DESCRIPTION").isTextual()) {
                String description = fields.path("DESCRIPTION").asString();
                if (description.isBlank()) object.remove("description");
                else object.put("description", description);
            }
            return object;
        } catch (JacksonException exception) {
            throw new IllegalStateException("List import payload is invalid", exception);
        }
    }

    private String text(JsonNode payload, String field) {
        JsonNode value = payload.path(field);
        return value.isTextual() ? value.asString() : null;
    }

    private UUID linkedListId(ImportItem item) {
        try {
            JsonNode value = objectMapper.readTree(item.getPayloadJson()).path("resolution")
                    .path("linkImportedList").path("targetListId");
            return value.isTextual() ? UUID.fromString(value.asString()) : null;
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new IllegalStateException("Imported list target is invalid", exception);
        }
    }

    private ImportSourceKey listIdentity(ImportItem item) {
        try {
            JsonNode identity = objectMapper.readTree(item.getPayloadJson()).path("resolution").path("listIdentity");
            if (identity.isObject()) {
                return new ImportSourceKey(identity.path("namespace").asString(),
                        identity.path("objectKind").asString(), identity.path("sourceId").asString());
            }
            return new ImportSourceKey(item.getSourceNamespace(), item.getObjectKind(), item.getSourceId());
        } catch (JacksonException exception) {
            throw new IllegalStateException("Resolved list identity is invalid", exception);
        }
    }

    private ImportTarget readTarget(String json) {
        try {
            return objectMapper.treeToValue(objectMapper.readTree(json), ImportTarget.class);
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new IllegalStateException("Resolved collection content is invalid", exception);
        }
    }

    private JsonNode readPayload(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Watchlist import payload is invalid", exception);
        }
    }
}
