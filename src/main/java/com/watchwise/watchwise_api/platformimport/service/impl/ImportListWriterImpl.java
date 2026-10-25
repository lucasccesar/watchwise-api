package com.watchwise.watchwise_api.platformimport.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.content.dto.ContentRefCreationDTO;
import com.watchwise.watchwise_api.content.dto.MediaReference;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.content.service.ContentService;
import com.watchwise.watchwise_api.platformimport.entity.ExternalImportLink;
import com.watchwise.watchwise_api.platformimport.entity.ImportItem;
import com.watchwise.watchwise_api.platformimport.model.ImportDecision;
import com.watchwise.watchwise_api.platformimport.model.ImportLinkState;
import com.watchwise.watchwise_api.platformimport.model.ImportSourceKey;
import com.watchwise.watchwise_api.platformimport.model.ImportTarget;
import com.watchwise.watchwise_api.platformimport.model.LinkReservation;
import com.watchwise.watchwise_api.platformimport.model.PreparedListImport;
import com.watchwise.watchwise_api.platformimport.repository.ExternalImportLinkRepository;
import com.watchwise.watchwise_api.platformimport.repository.ImportItemRepository;
import com.watchwise.watchwise_api.platformimport.service.ImportExecutionContext;
import com.watchwise.watchwise_api.platformimport.service.ImportLedger;
import com.watchwise.watchwise_api.platformimport.service.ImportListWriter;
import com.watchwise.watchwise_api.userlist.dto.UserListCreationDTO;
import com.watchwise.watchwise_api.userlist.entity.UserList;
import com.watchwise.watchwise_api.userlist.repository.UserListRepository;
import com.watchwise.watchwise_api.userlist.service.ImportedUserListItemResult;
import com.watchwise.watchwise_api.userlist.service.UserListItemService;
import com.watchwise.watchwise_api.userlist.service.UserListService;
import com.watchwise.watchwise_api.userlist.entity.UserListVisibility;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class ImportListWriterImpl implements ImportListWriter {
    private static final int BATCH_SIZE = 200;
    private static final UUID FIRST_ID = new UUID(0, 0);
    private static final String IDENTITY_KIND = "user_list";
    private static final String MANIFEST_KIND = "user_list_import";
    private static final String MEMBER_KIND = "user_list_member";

    private final ImportItemRepository itemRepository;
    private final ExternalImportLinkRepository linkRepository;
    private final ImportLedger ledger;
    private final UserListRepository userListRepository;
    private final UserListService userListService;
    private final UserListItemService userListItemService;
    private final ContentService contentService;
    private final ContentRepository contentRepository;
    private final ObjectMapper objectMapper;

    public ImportListWriterImpl(ImportItemRepository itemRepository,
                                ExternalImportLinkRepository linkRepository,
                                ImportLedger ledger,
                                UserListRepository userListRepository,
                                UserListService userListService,
                                UserListItemService userListItemService,
                                ContentService contentService,
                                ContentRepository contentRepository,
                                ObjectMapper objectMapper) {
        this.itemRepository = itemRepository;
        this.linkRepository = linkRepository;
        this.ledger = ledger;
        this.userListRepository = userListRepository;
        this.userListService = userListService;
        this.userListItemService = userListItemService;
        this.contentService = contentService;
        this.contentRepository = contentRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public ImportSourceKey operationKey(ImportExecutionContext context, ImportItem metadataItem) {
        String title = text(effectivePayload(metadataItem), "title");
        String description = text(effectivePayload(metadataItem), "description");
        UUID linkedListId = linkedListId(metadataItem);
        MessageDigest digest = newDigest();
        addPart(digest, "watchwise-user-list-manifest-v1");
        addPart(digest, metadataItem.getSourceId());
        addPart(digest, title);
        addPart(digest, description);
        addPart(digest, linkedListId == null ? null : linkedListId.toString());

        long afterOrdinal = -1;
        UUID afterId = FIRST_ID;
        while (true) {
            List<ImportItem> page = itemRepository.findListItemsAfter(context.jobId(), context.planGeneration(),
                    metadataItem.getSourceId(), afterOrdinal, afterId.toString(), BATCH_SIZE);
            if (page.isEmpty()) break;
            for (ImportItem item : page) {
                afterOrdinal = item.getOrdinal();
                afterId = item.getId();
                if (item.getDecision() == ImportDecision.SKIP_ITEM) {
                    addPart(digest, "SKIPPED");
                    continue;
                }
                addPart(digest, item.getResolvedContentKey());
                JsonNode payload = effectivePayload(item);
                addPart(digest, text(payload, "position"));
                addPart(digest, text(payload, "rank"));
                addPart(digest, text(payload, "description"));
            }
        }
        return new ImportSourceKey(context.sourceNamespace(), MANIFEST_KIND, HexFormat.of().formatHex(digest.digest()));
    }

    @Override
    public ImportSourceKey memberKey(ImportExecutionContext context, ImportItem item) {
        String parentSourceId = item.getParentSourceId();
        String contentKey = item.getResolvedContentKey();
        if (parentSourceId == null || parentSourceId.isBlank()) {
            return new ImportSourceKey(item.getSourceNamespace(), item.getObjectKind(), item.getSourceId());
        }
        MessageDigest digest = newDigest();
        addPart(digest, "watchwise-user-list-member-v1");
        addPart(digest, parentSourceId);
        if (contentKey == null || contentKey.isBlank()) {
            addPart(digest, "unresolved-source-item");
            addPart(digest, item.getSourceId());
        } else {
            addPart(digest, contentKey);
        }
        return new ImportSourceKey(context.sourceNamespace(), MEMBER_KIND, HexFormat.of().formatHex(digest.digest()));
    }

    @Override
    @Transactional
    public UUID apply(ImportExecutionContext context, PreparedListImport plan) {
        ImportItem metadataItem = itemRepository.findById(plan.parentItemId())
                .filter(item -> item.getJobId().equals(context.jobId())
                        && item.getPlanGeneration() == context.planGeneration()
                        && item.getNormalizedKind().name().equals("LIST_METADATA"))
                .orElseThrow(() -> new IllegalArgumentException("List metadata does not belong to this import"));
        validateMetadata(plan.name(), plan.description());

        ImportSourceKey identityKey = new ImportSourceKey(plan.sourceKey().namespace(), IDENTITY_KIND,
                plan.sourceKey().sourceId());
        LinkReservation identity = ledger.reserve(context.ownerId(), context.jobId(), identityKey,
                plan.operationKey().sourceId());
        if (identity.state() == ImportLinkState.TOMBSTONE || identity.state() == ImportLinkState.SKIPPED) {
            skipAllMembers(context, metadataItem, "SOURCE_LIST_TOMBSTONED");
            ledger.markSkipped(context.ownerId(), plan.operationKey(), null, null, "SOURCE_LIST_TOMBSTONED");
            metadataItem.setOutcome("SKIPPED");
            metadataItem.setIssue("SOURCE_LIST_TOMBSTONED");
            return null;
        }
        if (identity.state() == ImportLinkState.IN_PROGRESS && identity.owningJobId() != null
                && !identity.owningJobId().equals(context.jobId())) {
            throw new ImportUnitTransaction.WaitForOtherJobException();
        }

        prepareContentReferences(context, metadataItem);
        UUID listId = resolveDestination(context, plan, identity);
        if (listId == null) {
            skipAllMembers(context, metadataItem, "SOURCE_LIST_TOMBSTONED");
            ledger.markSkipped(context.ownerId(), plan.operationKey(), null, null, "SOURCE_LIST_TOMBSTONED");
            metadataItem.setOutcome("SKIPPED");
            metadataItem.setIssue("SOURCE_LIST_TOMBSTONED");
            return null;
        }

        processMembers(context, metadataItem, listId);
        ledger.markApplied(context.ownerId(), plan.operationKey(), "USER_LIST_IMPORT", listId);
        return listId;
    }

    @Override
    @Transactional
    public void skipMember(ImportExecutionContext context, ImportItem item, String reason) {
        reserveAndSkip(context, item, memberKey(context, item), reason);
        itemRepository.save(item);
    }

    private UUID resolveDestination(ImportExecutionContext context, PreparedListImport plan,
                                   LinkReservation identity) {
        ImportSourceKey identityKey = new ImportSourceKey(plan.sourceKey().namespace(), IDENTITY_KIND,
                plan.sourceKey().sourceId());

        UUID linkedTarget = plan.linkedListId();
        if (identity.state() == ImportLinkState.APPLIED) {
            ExternalImportLink existing = linkRepository.findByUserIdAndSourceNamespaceAndObjectKindAndSourceId(
                    context.ownerId(), identityKey.namespace(), identityKey.objectKind(), identityKey.sourceId())
                    .orElseThrow(() -> new IllegalStateException("Imported list identity link is missing"));
            if (!"USER_LIST".equals(existing.getTargetType()) || existing.getTargetId() == null) {
                throw new IllegalStateException("Imported list identity link has an invalid target");
            }
            if (linkedTarget != null && !linkedTarget.equals(existing.getTargetId())) {
                throw new BadRequestException("This source list is already linked to another Watchwise list");
            }
            linkedTarget = existing.getTargetId();
        }

        if (linkedTarget != null) {
            UserList target = userListRepository.findById(linkedTarget)
                    .orElseThrow(() -> new NotFoundException("User list not found"));
            if (target.getUser() == null || !context.ownerId().equals(target.getUser().getId())) {
                throw new NotFoundException("User list not found");
            }
            if (identity.state() == ImportLinkState.IN_PROGRESS) {
                ledger.markApplied(context.ownerId(), identityKey, "USER_LIST", target.getId());
            }
            return target.getId();
        }

        var created = userListService.createUserList(context.ownerId(), new UserListCreationDTO(
                plan.name(), plan.description(), UserListVisibility.PUBLIC));
        ledger.markApplied(context.ownerId(), identityKey, "USER_LIST", created.id());
        return created.id();
    }

    private void processMembers(ImportExecutionContext context, ImportItem metadataItem, UUID listId) {
        int nextPosition = userListItemService.nextImportedPosition(context.ownerId(), listId);
        long afterOrdinal = -1;
        UUID afterId = FIRST_ID;
        while (true) {
            List<ImportItem> page = itemRepository.findListItemsAfter(context.jobId(), context.planGeneration(),
                    metadataItem.getSourceId(), afterOrdinal, afterId.toString(), BATCH_SIZE);
            if (page.isEmpty()) break;
            afterOrdinal = page.getLast().getOrdinal();
            afterId = page.getLast().getId();
            nextPosition = processMemberBatch(context, listId, metadataItem.getSourceId(), page, nextPosition);
            itemRepository.saveAll(page);
        }
    }

    private int processMemberBatch(ImportExecutionContext context, UUID listId, String parentSourceId,
                                   List<ImportItem> page, int nextPosition) {
        List<String> contentKeys = page.stream().filter(item -> item.getDecision() != ImportDecision.SKIP_ITEM)
                .map(ImportItem::getResolvedContentKey).filter(key -> key != null && !key.isBlank()).distinct().toList();
        Set<UUID> firstRows = contentKeys.isEmpty() ? Set.of()
                : Set.copyOf(itemRepository.findFirstListItemsForContentKeys(context.jobId(),
                        context.planGeneration(), parentSourceId, contentKeys));

        Map<UUID, ImportSourceKey> memberKeys = new LinkedHashMap<>();
        Map<UUID, UUID> contentIds = new LinkedHashMap<>();
        Map<UUID, MediaReference> referencesByItemId = new LinkedHashMap<>();
        Map<UUID, String> descriptionsByItemId = new LinkedHashMap<>();
        Map<UUID, String> descriptionByContentId = new LinkedHashMap<>();
        for (ImportItem item : page) {
            ImportSourceKey memberKey = memberKey(context, item);
            if (item.getDecision() == ImportDecision.SKIP_ITEM) {
                reserveAndSkip(context, item, memberKey, "USER_SKIPPED");
                continue;
            }
            if (!firstRows.contains(item.getId())) {
                item.setOutcome("SKIPPED");
                item.setIssue("DUPLICATE_LIST_MEMBER");
                continue;
            }
            LinkReservation reservation = ledger.reserve(context.ownerId(), context.jobId(), memberKey,
                    ImportEventDeduplicator.payloadHash(item.getPayloadJson()));
            if (reservation.state() == ImportLinkState.TOMBSTONE || reservation.state() == ImportLinkState.SKIPPED
                    || reservation.state() == ImportLinkState.APPLIED) {
                item.setOutcome("SKIPPED");
                item.setIssue(reservation.state() == ImportLinkState.TOMBSTONE
                        ? "SOURCE_MEMBER_TOMBSTONED" : "SOURCE_MEMBER_ALREADY_CONSUMED");
                continue;
            }
            if (reservation.owningJobId() != null && !reservation.owningJobId().equals(context.jobId())) {
                throw new ImportUnitTransaction.WaitForOtherJobException();
            }

            ImportTarget target = readTarget(item.getResolvedTargetJson());
            if (!(target instanceof ImportTarget.ContentTarget contentTarget)) {
                throw new IllegalStateException("List item must resolve to a content target");
            }
            String itemDescription = text(effectivePayload(item), "description");
            if (itemDescription != null && itemDescription.length() > 400) {
                throw new BadRequestException("Imported list item description cannot exceed 400 characters");
            }
            referencesByItemId.put(item.getId(), contentTarget.content());
            descriptionsByItemId.put(item.getId(), itemDescription);
            memberKeys.put(item.getId(), memberKey);
        }

        Map<String, UUID> contentIdByKey = findContentIds(referencesByItemId.values().stream().distinct().toList());
        for (Map.Entry<UUID, MediaReference> entry : referencesByItemId.entrySet()) {
            UUID contentId = contentIdByKey.get(contentKey(entry.getValue()));
            if (contentId == null) throw new NotFoundException("Prepared list content reference not found");
            contentIds.put(entry.getKey(), contentId);
            descriptionByContentId.putIfAbsent(contentId, descriptionsByItemId.get(entry.getKey()));
        }
        List<UUID> uniqueContentIds = contentIds.values().stream().distinct().toList();
        if (uniqueContentIds.isEmpty()) return nextPosition;
        var batchResult = userListItemService.appendImportedItems(
                context.ownerId(), listId, uniqueContentIds, descriptionByContentId, nextPosition);
        Map<UUID, ImportedUserListItemResult> writeByContentId = new HashMap<>();
        batchResult.items().forEach(write -> writeByContentId.put(write.contentId(), write));
        for (ImportItem item : page) {
            UUID contentId = contentIds.get(item.getId());
            if (contentId == null) continue;
            ImportedUserListItemResult write = writeByContentId.get(contentId);
            ImportSourceKey sourceKey = memberKeys.get(item.getId());
            if (write.inserted()) {
                ledger.markApplied(context.ownerId(), sourceKey, "USER_LIST_ITEM", write.itemId());
                item.setOutcome("APPLIED");
                item.setIssue(null);
            } else {
                ledger.markSkipped(context.ownerId(), sourceKey, "USER_LIST_ITEM", write.itemId(),
                        "CONTENT_ALREADY_IN_LIST");
                item.setOutcome("SKIPPED");
                item.setIssue("CONTENT_ALREADY_IN_LIST");
            }
        }
        return batchResult.nextPosition();
    }

    private void prepareContentReferences(ImportExecutionContext context, ImportItem metadataItem) {
        long afterOrdinal = -1;
        UUID afterId = FIRST_ID;
        while (true) {
            List<ImportItem> page = itemRepository.findListItemsAfter(context.jobId(), context.planGeneration(),
                    metadataItem.getSourceId(), afterOrdinal, afterId.toString(), BATCH_SIZE);
            if (page.isEmpty()) return;
            afterOrdinal = page.getLast().getOrdinal();
            afterId = page.getLast().getId();
            List<String> contentKeys = page.stream().filter(item -> item.getDecision() != ImportDecision.SKIP_ITEM)
                    .map(ImportItem::getResolvedContentKey).filter(key -> key != null && !key.isBlank()).distinct().toList();
            Set<UUID> firstRows = contentKeys.isEmpty() ? Set.of()
                    : Set.copyOf(itemRepository.findFirstListItemsForContentKeys(context.jobId(),
                    context.planGeneration(), metadataItem.getSourceId(), contentKeys));
            for (ImportItem item : page) {
                if (item.getDecision() == ImportDecision.SKIP_ITEM || !firstRows.contains(item.getId())) continue;
                ImportTarget target = readTarget(item.getResolvedTargetJson());
                if (!(target instanceof ImportTarget.ContentTarget contentTarget)) {
                    throw new IllegalStateException("List item must resolve to a content target");
                }
                contentService.getOrCreateReference(toCreation(contentTarget.content()));
            }
        }
    }

    private Map<String, UUID> findContentIds(List<MediaReference> references) {
        Map<String, UUID> result = new HashMap<>();
        List<String> movieIds = references.stream().filter(ref -> ref.type() == ContentType.MOVIE)
                .map(MediaReference::tmdbId).distinct().toList();
        List<String> seriesIds = references.stream().filter(ref -> ref.type() == ContentType.SERIES)
                .map(MediaReference::tmdbId).distinct().toList();
        if (!movieIds.isEmpty()) contentRepository.findByTypeAndTmdbIdIn(ContentType.MOVIE, movieIds)
                .forEach(content -> result.put(contentKey(MediaReference.movie(content.getTmdbId())), content.getId()));
        if (!seriesIds.isEmpty()) contentRepository.findByTypeAndTmdbIdIn(ContentType.SERIES, seriesIds)
                .forEach(content -> result.put(contentKey(MediaReference.series(content.getTmdbId())), content.getId()));

        Map<String, Set<Integer>> seasonsBySeries = new LinkedHashMap<>();
        Map<String, Map<Integer, Set<Integer>>> episodesBySeries = new LinkedHashMap<>();
        for (MediaReference reference : references) {
            if (reference.type() == ContentType.SEASON) {
                seasonsBySeries.computeIfAbsent(reference.seriesTmdbId(), ignored -> new HashSet<>())
                        .add(reference.seasonNumber());
            } else if (reference.type() == ContentType.EPISODE) {
                episodesBySeries.computeIfAbsent(reference.seriesTmdbId(), ignored -> new LinkedHashMap<>())
                        .computeIfAbsent(reference.seasonNumber(), ignored -> new HashSet<>())
                        .add(reference.episodeNumber());
            }
        }
        seasonsBySeries.forEach((seriesId, seasons) -> contentRepository
                .findByTypeAndSeriesTmdbIdAndSeasonNumberIn(ContentType.SEASON, seriesId, seasons)
                .forEach(content -> result.put(contentKey(MediaReference.season(seriesId, content.getSeasonNumber())),
                        content.getId())));
        episodesBySeries.forEach((seriesId, bySeason) -> bySeason.forEach((season, episodes) -> contentRepository
                .findByTypeAndSeriesTmdbIdAndSeasonNumberAndEpisodeNumberIn(
                        ContentType.EPISODE, seriesId, season, episodes)
                .forEach(content -> result.put(contentKey(MediaReference.episode(seriesId,
                        content.getSeasonNumber(), content.getEpisodeNumber())), content.getId()))));
        return result;
    }

    private String contentKey(MediaReference reference) {
        return switch (reference.type()) {
            case MOVIE, SERIES -> "CONTENT|" + reference.type().name() + "|" + reference.tmdbId();
            case SEASON -> "CONTENT|SEASON|" + reference.seriesTmdbId() + "|" + reference.seasonNumber();
            case EPISODE -> "CONTENT|EPISODE|" + reference.seriesTmdbId() + "|" + reference.seasonNumber()
                    + "|" + reference.episodeNumber();
        };
    }

    private void reserveAndSkip(ImportExecutionContext context, ImportItem item, ImportSourceKey key, String reason) {
        LinkReservation reservation = ledger.reserve(context.ownerId(), context.jobId(), key,
                ImportEventDeduplicator.payloadHash(item.getPayloadJson()));
        if (reservation.state() == ImportLinkState.IN_PROGRESS && reservation.owningJobId() != null
                && !reservation.owningJobId().equals(context.jobId())) {
            throw new ImportUnitTransaction.WaitForOtherJobException();
        }
        if (reservation.state() == ImportLinkState.IN_PROGRESS
                && (reservation.owningJobId() == null || reservation.owningJobId().equals(context.jobId()))) {
            ledger.markSkipped(context.ownerId(), key, null, null, reason);
        }
        item.setOutcome("SKIPPED");
        item.setIssue(reason);
    }

    private void skipAllMembers(ImportExecutionContext context, ImportItem metadataItem, String reason) {
        long afterOrdinal = -1;
        UUID afterId = FIRST_ID;
        while (true) {
            List<ImportItem> page = itemRepository.findListItemsAfter(context.jobId(), context.planGeneration(),
                    metadataItem.getSourceId(), afterOrdinal, afterId.toString(), BATCH_SIZE);
            if (page.isEmpty()) return;
            afterOrdinal = page.getLast().getOrdinal();
            afterId = page.getLast().getId();
            for (ImportItem item : page) reserveAndSkip(context, item, memberKey(context, item), reason);
            itemRepository.saveAll(page);
        }
    }

    private void validateMetadata(String name, String description) {
        if (name == null || name.isBlank() || name.length() > 255) {
            throw new BadRequestException("Imported list name must contain 1 to 255 characters");
        }
        if (description != null && description.length() > 400) {
            throw new BadRequestException("Imported list description cannot exceed 400 characters");
        }
    }

    private ContentRefCreationDTO toCreation(MediaReference reference) {
        return switch (reference.type()) {
            case MOVIE, SERIES -> new ContentRefCreationDTO(reference.tmdbId(), reference.type(),
                    null, null, null, null, null);
            case SEASON -> new ContentRefCreationDTO(null, ContentType.SEASON, reference.seriesTmdbId(),
                    reference.seasonNumber(), null, null, null);
            case EPISODE -> new ContentRefCreationDTO(null, ContentType.EPISODE, reference.seriesTmdbId(),
                    reference.seasonNumber(), reference.episodeNumber(), null, null);
        };
    }

    private ImportTarget readTarget(String json) {
        try {
            return objectMapper.treeToValue(objectMapper.readTree(json), ImportTarget.class);
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new IllegalStateException("Resolved list item target is invalid", exception);
        }
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
            throw new IllegalStateException("List metadata payload is invalid", exception);
        }
    }

    private String text(JsonNode payload, String field) {
        JsonNode value = payload.path(field);
        return value.isTextual() ? value.asString() : null;
    }

    private UUID linkedListId(ImportItem item) {
        try {
            JsonNode node = objectMapper.readTree(item.getPayloadJson()).path("resolution")
                    .path("linkImportedList").path("targetListId");
            return node.isTextual() ? UUID.fromString(node.asString()) : null;
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new IllegalStateException("Imported list target resolution is invalid", exception);
        }
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void addPart(MessageDigest digest, String value) {
        byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
}
