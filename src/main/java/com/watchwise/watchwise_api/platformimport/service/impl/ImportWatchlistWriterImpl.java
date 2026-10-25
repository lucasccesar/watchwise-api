package com.watchwise.watchwise_api.platformimport.service.impl;

import com.watchwise.watchwise_api.content.dto.ContentRefCreationDTO;
import com.watchwise.watchwise_api.content.dto.MediaReference;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.service.ContentService;
import com.watchwise.watchwise_api.platformimport.entity.ImportItem;
import com.watchwise.watchwise_api.platformimport.model.ImportDecision;
import com.watchwise.watchwise_api.platformimport.model.ImportLinkState;
import com.watchwise.watchwise_api.platformimport.model.ImportRecordKind;
import com.watchwise.watchwise_api.platformimport.model.ImportTarget;
import com.watchwise.watchwise_api.platformimport.model.LinkReservation;
import com.watchwise.watchwise_api.platformimport.model.NormalizedImportRecord;
import com.watchwise.watchwise_api.platformimport.model.PreparedWatchlistImport;
import com.watchwise.watchwise_api.platformimport.repository.ImportItemRepository;
import com.watchwise.watchwise_api.platformimport.service.ImportExecutionContext;
import com.watchwise.watchwise_api.platformimport.service.ImportLedger;
import com.watchwise.watchwise_api.platformimport.service.ImportWatchlistWriter;
import com.watchwise.watchwise_api.watchlist.service.ImportedWatchlistItem;
import com.watchwise.watchwise_api.watchlist.service.ImportedWatchlistWriteResult;
import com.watchwise.watchwise_api.watchlist.service.WatchlistEntryService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class ImportWatchlistWriterImpl implements ImportWatchlistWriter {
    private final ImportItemRepository itemRepository;
    private final ImportLedger ledger;
    private final ContentService contentService;
    private final WatchlistEntryService watchlistEntryService;
    private final ObjectMapper objectMapper;

    public ImportWatchlistWriterImpl(ImportItemRepository itemRepository, ImportLedger ledger,
                                     ContentService contentService, WatchlistEntryService watchlistEntryService,
                                     ObjectMapper objectMapper) {
        this.itemRepository = itemRepository;
        this.ledger = ledger;
        this.contentService = contentService;
        this.watchlistEntryService = watchlistEntryService;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public int apply(ImportExecutionContext context, PreparedWatchlistImport plan) {
        if (context == null || plan == null) throw new IllegalArgumentException("Watchlist import context is required");
        List<ImportItem> rows = new ArrayList<>(plan.items().size());
        List<NormalizedImportRecord> candidates = new ArrayList<>();
        Map<String, UUID> contentIdByKey = new HashMap<>();
        Map<UUID, ImportItem> itemById = new LinkedHashMap<>();
        Set<MediaReference> seenInBatch = new HashSet<>();

        for (NormalizedImportRecord record : plan.items()) {
            UUID itemId = parseItemId(record.itemId());
            ImportItem item = itemRepository.findById(itemId)
                    .filter(row -> context.jobId().equals(row.getJobId())
                            && context.planGeneration() == row.getPlanGeneration()
                            && row.getNormalizedKind() == ImportRecordKind.WATCHLIST_ITEM)
                    .orElseThrow(() -> new IllegalArgumentException("Watchlist item does not belong to this import"));
            rows.add(item);
            itemById.put(itemId, item);
            if (item.getDecision() == ImportDecision.SKIP_ITEM) {
                markSkipped(context, item, record.sourceKey(), "USER_SKIPPED", null);
                continue;
            }
            if (!(record.target() instanceof ImportTarget.ContentTarget target)) {
                markSkipped(context, item, record.sourceKey(), "UNRESOLVED_WATCHLIST_CONTENT", null);
                continue;
            }
            MediaReference reference = target.content();
            if (reference.type() != ContentType.MOVIE && reference.type() != ContentType.SERIES) {
                markSkipped(context, item, record.sourceKey(), "UNSUPPORTED_WATCHLIST_CONTENT_TYPE", null);
                continue;
            }
            if (plan.sourceWatched().contains(reference)) {
                markSkipped(context, item, record.sourceKey(), "SOURCE_DECLARED_WATCHED", null);
                continue;
            }
            LinkReservation reservation = ledger.reserve(context.ownerId(), context.jobId(), record.sourceKey(),
                    ImportEventDeduplicator.payloadHash(item.getPayloadJson()));
            if (reservation.state() == ImportLinkState.APPLIED || reservation.state() == ImportLinkState.SKIPPED
                    || reservation.state() == ImportLinkState.TOMBSTONE) {
                item.setOutcome("SKIPPED");
                item.setIssue(reservation.state() == ImportLinkState.TOMBSTONE
                        ? "SOURCE_WATCHLIST_ITEM_TOMBSTONED" : "SOURCE_WATCHLIST_ITEM_ALREADY_CONSUMED");
                continue;
            }
            if (reservation.owningJobId() != null && !reservation.owningJobId().equals(context.jobId())) {
                throw new ImportUnitTransaction.WaitForOtherJobException();
            }
            if (!seenInBatch.add(reference)) {
                markSkipped(context, item, record.sourceKey(), "DUPLICATE_SOURCE_CONTENT", null);
                continue;
            }

            UUID contentId = contentIdByKey.get(item.getResolvedContentKey());
            if (contentId == null) {
                contentId = contentService.getOrCreateReference(toCreation(reference)).id();
                contentIdByKey.put(item.getResolvedContentKey(), contentId);
            }
            candidates.add(record);
        }

        if (candidates.isEmpty()) {
            itemRepository.saveAll(rows);
            return 0;
        }

        List<ImportedWatchlistItem> prepared = new ArrayList<>(candidates.size());
        Map<UUID, UUID> contentIdsByItemId = new LinkedHashMap<>();
        for (NormalizedImportRecord record : candidates) {
            UUID itemId = UUID.fromString(record.itemId());
            ImportItem item = itemById.get(itemId);
            MediaReference reference = ((ImportTarget.ContentTarget) record.target()).content();
            UUID contentId = contentIdByKey.get(item.getResolvedContentKey());
            contentIdsByItemId.put(itemId, contentId);
            prepared.add(new ImportedWatchlistItem(contentId, reference));
        }

        List<ImportedWatchlistWriteResult> results = watchlistEntryService.appendImportedEntries(
                context.ownerId(), prepared);
        Map<UUID, ImportedWatchlistWriteResult> resultByContentId = new HashMap<>();
        results.forEach(result -> resultByContentId.put(result.contentId(), result));
        int inserted = 0;
        for (NormalizedImportRecord record : candidates) {
            UUID itemId = UUID.fromString(record.itemId());
            ImportItem item = itemById.get(itemId);
            UUID contentId = contentIdsByItemId.get(itemId);
            ImportedWatchlistWriteResult result = resultByContentId.get(contentId);
            if (result.outcome() == ImportedWatchlistWriteResult.Outcome.INSERTED) {
                ledger.markApplied(context.ownerId(), record.sourceKey(), "WATCHLIST_ENTRY", result.entryId());
                item.setOutcome("APPLIED");
                item.setIssue(null);
                inserted++;
            } else {
                markSkipped(context, item, record.sourceKey(), result.reason(), result.entryId());
            }
        }
        itemRepository.saveAll(rows);
        return inserted;
    }

    private void markSkipped(ImportExecutionContext context, ImportItem item,
                             com.watchwise.watchwise_api.platformimport.model.ImportSourceKey sourceKey,
                             String reason, UUID targetId) {
        LinkReservation reservation = ledger.reserve(context.ownerId(), context.jobId(), sourceKey,
                ImportEventDeduplicator.payloadHash(item.getPayloadJson()));
        if (reservation.state() == ImportLinkState.IN_PROGRESS) {
            if (reservation.owningJobId() != null && !reservation.owningJobId().equals(context.jobId())) {
                throw new ImportUnitTransaction.WaitForOtherJobException();
            }
            ledger.markSkipped(context.ownerId(), sourceKey, targetId == null ? null : "WATCHLIST_ENTRY",
                    targetId, reason);
        }
        item.setOutcome("SKIPPED");
        item.setIssue(reason);
    }

    private ContentRefCreationDTO toCreation(MediaReference reference) {
        return new ContentRefCreationDTO(reference.tmdbId(), reference.type(), null, null, null, null, null);
    }

    private UUID parseItemId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Watchlist import item id is invalid", exception);
        }
    }

}
