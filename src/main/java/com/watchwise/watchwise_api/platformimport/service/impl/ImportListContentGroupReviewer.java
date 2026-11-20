package com.watchwise.watchwise_api.platformimport.service.impl;

import com.watchwise.watchwise_api.platformimport.entity.ExternalImportLink;
import com.watchwise.watchwise_api.platformimport.entity.ImportItem;
import com.watchwise.watchwise_api.platformimport.model.ImportDecision;
import com.watchwise.watchwise_api.platformimport.model.ImportLinkState;
import com.watchwise.watchwise_api.platformimport.model.ImportRecordKind;
import com.watchwise.watchwise_api.platformimport.model.ImportTarget;
import com.watchwise.watchwise_api.platformimport.repository.ExternalImportLinkRepository;
import com.watchwise.watchwise_api.platformimport.repository.ImportItemRepository;
import com.watchwise.watchwise_api.platformimport.service.ImportExecutionContext;
import com.watchwise.watchwise_api.userlist.dto.UserListItemScope;
import com.watchwise.watchwise_api.userlist.service.UserListItemService;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class ImportListContentGroupReviewer {
    private static final int BATCH_SIZE = 200;
    private static final String REVIEW_ISSUE = "LIST_ITEM_CONTENT_GROUP_CONFLICT";
    private static final String REVIEW_MESSAGE = REVIEW_ISSUE
            + ": This list cannot mix content type groups. Skip the item to continue.";
    private static final UUID FIRST_ID = new UUID(0, 0);

    private final ImportItemRepository itemRepository;
    private final ExternalImportLinkRepository externalImportLinkRepository;
    private final UserListItemService userListItemService;
    private final ImportUnitTransaction unitTransaction;
    private final ObjectMapper objectMapper;

    public ImportListContentGroupReviewer(ImportItemRepository itemRepository,
                                          ExternalImportLinkRepository externalImportLinkRepository,
                                          UserListItemService userListItemService,
                                          ImportUnitTransaction unitTransaction,
                                          ObjectMapper objectMapper) {
        this.itemRepository = itemRepository;
        this.externalImportLinkRepository = externalImportLinkRepository;
        this.userListItemService = userListItemService;
        this.unitTransaction = unitTransaction;
        this.objectMapper = objectMapper;
    }

    public boolean review(ImportExecutionContext context) {
        long afterOrdinal = -1;
        UUID afterId = FIRST_ID;
        while (true) {
            List<ImportItem> parents = itemRepository.findItemsOfKindAfter(context.jobId(),
                    context.planGeneration(), ImportRecordKind.LIST_METADATA.name(), afterOrdinal,
                    afterId.toString(), BATCH_SIZE);
            if (parents.isEmpty()) return true;
            afterOrdinal = parents.getLast().getOrdinal();
            afterId = parents.getLast().getId();

            List<ImportItem> pendingReview = parents.stream()
                    .filter(parent -> !isReviewed(parent) || shouldSkipMembers(parent))
                    .toList();
            Map<ListIdentity, ExternalImportLink> linksByIdentity = loadListLinks(context, pendingReview);
            Map<UUID, UUID> targetListIds = targetListIds(pendingReview, linksByIdentity);
            Map<UUID, UserListItemScope> scopesByListId = targetListIds.isEmpty()
                    ? Map.of() : userListItemService.getItemScopeByListIds(targetListIds.values());

            for (ImportItem parent : pendingReview) {
                boolean parentSkipped = shouldSkipMembers(parent, linksByIdentity.get(identity(parent)));
                UserListItemScope destinationScope = destinationScope(parent, linksByIdentity, targetListIds,
                        scopesByListId);
                if (!reviewMembers(context, parent, destinationScope, parentSkipped)) return false;
            }
            if (parents.size() < BATCH_SIZE) return true;
        }
    }

    private Map<ListIdentity, ExternalImportLink> loadListLinks(ImportExecutionContext context,
                                                                 List<ImportItem> parents) {
        Map<String, Set<String>> sourceIdsByNamespace = new LinkedHashMap<>();
        for (ImportItem parent : parents) {
            ListIdentity identity = identity(parent);
            sourceIdsByNamespace.computeIfAbsent(identity.namespace(), ignored -> new LinkedHashSet<>())
                    .add(identity.sourceId());
        }
        Map<ListIdentity, ExternalImportLink> result = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : sourceIdsByNamespace.entrySet()) {
            for (ExternalImportLink link : externalImportLinkRepository
                    .findAllByUserIdAndSourceNamespaceAndObjectKindAndSourceIdIn(context.ownerId(),
                            entry.getKey(), "user_list", entry.getValue())) {
                result.put(new ListIdentity(link.getSourceNamespace(), link.getSourceId()), link);
            }
        }
        return result;
    }

    private Map<UUID, UUID> targetListIds(List<ImportItem> parents,
                                          Map<ListIdentity, ExternalImportLink> linksByIdentity) {
        Map<UUID, UUID> result = new LinkedHashMap<>();
        for (ImportItem parent : parents) {
            ExternalImportLink link = linksByIdentity.get(identity(parent));
            if (link != null && link.getState() == ImportLinkState.APPLIED
                    && "USER_LIST".equals(link.getTargetType()) && link.getTargetId() != null) {
                result.put(parent.getId(), link.getTargetId());
            } else {
                UUID explicitTarget = explicitTarget(parent);
                if (explicitTarget != null) result.put(parent.getId(), explicitTarget);
            }
        }
        return result;
    }

    private UserListItemScope destinationScope(ImportItem parent,
                                               Map<ListIdentity, ExternalImportLink> linksByIdentity,
                                               Map<UUID, UUID> targetListIds,
                                               Map<UUID, UserListItemScope> scopesByListId) {
        ExternalImportLink link = linksByIdentity.get(identity(parent));
        if (link != null && (link.getState() == ImportLinkState.TOMBSTONE
                || link.getState() == ImportLinkState.SKIPPED)) return null;
        UUID targetListId = targetListIds.get(parent.getId());
        return targetListId == null ? null : scopesByListId.get(targetListId);
    }

    private boolean reviewMembers(ImportExecutionContext context, ImportItem parent,
                                  UserListItemScope destinationScope, boolean parentSkipped) {
        List<ImportUnitTransaction.CollectionValidationIssueUpdate> updates = new ArrayList<>(BATCH_SIZE);
        UserListItemScope sourceScope = destinationScope;
        long afterRank = -1;
        long afterOrdinal = -1;
        UUID afterId = FIRST_ID;
        while (true) {
            List<ImportItem> members = itemRepository.findListItemsAfter(context.jobId(), context.planGeneration(),
                    parent.getSourceId(), afterRank, afterOrdinal, afterId.toString(), BATCH_SIZE);
            if (members.isEmpty()) break;
            ImportItem last = members.getLast();
            afterRank = sourceRank(last);
            afterOrdinal = last.getOrdinal();
            afterId = last.getId();
            for (ImportItem item : members) {
                sourceScope = updateReviewState(item, sourceScope, parentSkipped, updates);
                if (updates.size() == BATCH_SIZE && !flush(context, updates)) return false;
            }
            if (members.size() < BATCH_SIZE) break;
        }
        if (!flush(context, updates)) return false;
        String reviewedPayload = markReviewed(parent);
        if (reviewedPayload == null) return true;
        return unitTransaction.saveCollectionValidationUpdates(context, List.of(
                new ImportUnitTransaction.CollectionValidationIssueUpdate(
                        parent.getId(), reviewedPayload, null, null, false)));
    }

    private UserListItemScope updateReviewState(ImportItem item, UserListItemScope expectedScope,
                                                boolean parentSkipped,
                                                List<ImportUnitTransaction.CollectionValidationIssueUpdate> updates) {
        String currentIssue = item.getIssue();
        boolean currentGroupIssue = currentIssue != null && currentIssue.startsWith(REVIEW_ISSUE);
        if (parentSkipped || item.getDecision() == ImportDecision.SKIP_ITEM) {
            if (currentGroupIssue) updates.add(update(item, "RESOLVED", null));
            return expectedScope;
        }

        UserListItemScope resolvedScope = contentScope(item);
        if (resolvedScope == null) {
            if (currentGroupIssue) updates.add(update(item, "RESOLVED", null));
            return expectedScope;
        }

        if (expectedScope == null) expectedScope = resolvedScope;
        boolean incompatible = expectedScope == UserListItemScope.LIST
                || expectedScope == UserListItemScope.MIXED || expectedScope != resolvedScope;
        if (!incompatible) {
            if (currentGroupIssue) updates.add(update(item, "RESOLVED", null));
            return expectedScope;
        }
        if (currentIssue == null || currentGroupIssue) {
            updates.add(update(item, "NORMALIZATION_ISSUE", REVIEW_MESSAGE));
        }
        return expectedScope;
    }

    private ImportUnitTransaction.CollectionValidationIssueUpdate update(ImportItem item,
                                                                          String outcome, String issue) {
        return new ImportUnitTransaction.CollectionValidationIssueUpdate(
                item.getId(), null, outcome, issue, true);
    }

    private boolean flush(ImportExecutionContext context,
                          List<ImportUnitTransaction.CollectionValidationIssueUpdate> updates) {
        if (updates.isEmpty()) return true;
        boolean saved = unitTransaction.saveCollectionValidationUpdates(context, List.copyOf(updates));
        updates.clear();
        return saved;
    }

    private UserListItemScope contentScope(ImportItem item) {
        if (item.getResolvedTargetJson() == null || item.getResolvedTargetJson().isBlank()) return null;
        try {
            JsonNode root = objectMapper.readTree(item.getResolvedTargetJson());
            if (root.has("candidates")) return null;
            ImportTarget target = objectMapper.treeToValue(root, ImportTarget.class);
            if (target instanceof ImportTarget.ContentTarget contentTarget) {
                return UserListItemScope.forContentType(contentTarget.content().type());
            }
            return null;
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new IllegalStateException("Resolved list member target is invalid", exception);
        }
    }

    private boolean shouldSkipMembers(ImportItem parent) {
        return parent.getDecision() == ImportDecision.SKIP_ITEM || "SKIPPED".equals(parent.getOutcome());
    }

    private boolean shouldSkipMembers(ImportItem parent, ExternalImportLink link) {
        return shouldSkipMembers(parent) || link != null
                && (link.getState() == ImportLinkState.TOMBSTONE || link.getState() == ImportLinkState.SKIPPED);
    }

    private boolean isReviewed(ImportItem parent) {
        return readPayload(parent).path("resolution").path("listContentGroupReview").asBoolean(false);
    }

    private String markReviewed(ImportItem parent) {
        try {
            JsonNode parsed = objectMapper.readTree(parent.getPayloadJson());
            if (!parsed.isObject()) throw new IllegalStateException("List metadata payload is invalid");
            ObjectNode root = (ObjectNode) parsed.deepCopy();
            JsonNode existingResolution = root.get("resolution");
            ObjectNode resolution;
            if (existingResolution != null && existingResolution.isObject()) {
                resolution = (ObjectNode) existingResolution;
            } else {
                resolution = root.putObject("resolution");
            }
            if (resolution.path("listContentGroupReview").asBoolean(false)) return null;
            resolution.put("listContentGroupReview", true);
            return objectMapper.writeValueAsString(root);
        } catch (JacksonException exception) {
            throw new IllegalStateException("List metadata review marker could not be stored", exception);
        }
    }

    private JsonNode readPayload(ImportItem item) {
        try {
            return objectMapper.readTree(item.getPayloadJson());
        } catch (JacksonException exception) {
            throw new IllegalStateException("List metadata payload is invalid", exception);
        }
    }

    private UUID explicitTarget(ImportItem parent) {
        JsonNode target = readPayload(parent).path("resolution").path("linkImportedList").path("targetListId");
        if (!target.isTextual()) return null;
        try {
            return UUID.fromString(target.asString());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Imported list target is invalid", exception);
        }
    }

    private ListIdentity identity(ImportItem parent) {
        JsonNode identity = readPayload(parent).path("resolution").path("listIdentity");
        String namespace = identity.path("namespace").asString(parent.getSourceNamespace());
        String sourceId = identity.path("sourceId").asString(parent.getSourceId());
        return new ListIdentity(namespace, sourceId);
    }

    private long sourceRank(ImportItem item) {
        try {
            JsonNode payload = readPayload(item);
            JsonNode source = payload.path("source").isObject() ? payload.path("source") : payload;
            JsonNode rootRank = payload.get("rank");
            JsonNode rank = rootRank == null || rootRank.isNull() ? source.get("rank") : rootRank;
            String rankText = rank == null || rank.isNull() ? null
                    : rank.isTextual() ? rank.asString() : rank.toString();
            if (rankText != null && rankText.matches("^[0-9]+$")) return Long.parseLong(rankText);
            return item.getOrdinal() + 1;
        } catch (RuntimeException exception) {
            throw new IllegalStateException("List member source rank is invalid", exception);
        }
    }

    private record ListIdentity(String namespace, String sourceId) { }
}
