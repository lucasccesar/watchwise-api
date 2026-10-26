package com.watchwise.watchwise_api.platformimport.service.impl;

import com.watchwise.watchwise_api.content.dto.ContentRefCreationDTO;
import com.watchwise.watchwise_api.content.dto.MediaReference;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.service.ContentService;
import com.watchwise.watchwise_api.contentrating.service.ContentRatingService;
import com.watchwise.watchwise_api.contentreview.service.HistoricalOpinion;
import com.watchwise.watchwise_api.contentreview.service.HistoricalOpinionRestoreService;
import com.watchwise.watchwise_api.contentreview.service.HistoricalViewingEvidence;
import com.watchwise.watchwise_api.diarycompletion.model.EpisodeCoordinate;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.platformimport.entity.ImportDeferredRecord;
import com.watchwise.watchwise_api.platformimport.entity.ImportItem;
import com.watchwise.watchwise_api.platformimport.model.ImportRecordKind;
import com.watchwise.watchwise_api.platformimport.model.ConfirmedWatchScopeSnapshot;
import com.watchwise.watchwise_api.platformimport.model.ImportSourceKey;
import com.watchwise.watchwise_api.platformimport.model.ImportedOpinionUnit;
import com.watchwise.watchwise_api.platformimport.repository.ImportDeferredRecordRepository;
import com.watchwise.watchwise_api.platformimport.entity.ExternalImportLink;
import com.watchwise.watchwise_api.platformimport.repository.ImportItemRepository;
import com.watchwise.watchwise_api.platformimport.service.ImportExecutionContext;
import com.watchwise.watchwise_api.platformimport.service.ImportLedger;
import com.watchwise.watchwise_api.platformimport.service.ImportUnitApplier;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Component
public class OpinionImportApplier implements ImportUnitApplier {
    private static final int MAX_REVIEW_LENGTH = 20_000;

    private final ImportedOpinionPlanner planner;
    private final ImportItemRepository itemRepository;
    private final ImportLedger ledger;
    private final HistoricalOpinionRestoreService reviewRestoreService;
    private final ContentRatingService ratingService;
    private final ContentService contentService;
    private final DiaryEntryRepository diaryEntryRepository;
    private final ImportDeferredRecordRepository deferredRepository;
    private final com.watchwise.watchwise_api.platformimport.repository.ExternalImportLinkRepository externalLinkRepository;
    private final WatchedSummaryImportApplier watchedSummaryImportApplier;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OpinionImportApplier(
            ImportedOpinionPlanner planner,
            ImportItemRepository itemRepository,
            ImportLedger ledger,
            HistoricalOpinionRestoreService reviewRestoreService,
            ContentRatingService ratingService,
            ContentService contentService,
            DiaryEntryRepository diaryEntryRepository,
            ImportDeferredRecordRepository deferredRepository,
            com.watchwise.watchwise_api.platformimport.repository.ExternalImportLinkRepository externalLinkRepository,
            WatchedSummaryImportApplier watchedSummaryImportApplier,
            ObjectMapper objectMapper,
            Clock clock) {
        this.planner = planner;
        this.itemRepository = itemRepository;
        this.ledger = ledger;
        this.reviewRestoreService = reviewRestoreService;
        this.ratingService = ratingService;
        this.contentService = contentService;
        this.diaryEntryRepository = diaryEntryRepository;
        this.deferredRepository = deferredRepository;
        this.externalLinkRepository = externalLinkRepository;
        this.watchedSummaryImportApplier = watchedSummaryImportApplier;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public boolean supports(ImportRecordKind kind) {
        return kind == ImportRecordKind.REVIEW || kind == ImportRecordKind.CURRENT_RATING;
    }

    @Override
    public void apply(ImportExecutionContext context, UUID itemId) {
        ImportItem item = itemRepository.findById(itemId).orElseThrow();
        ImportDeferredRecord replayRecord = deferredRecordForUpdate(context, item);
        if ("deferred_opinion_replay".equals(item.getObjectKind()) && replayRecord == null) {
            ledger.markSkipped(context.ownerId(), sourceKey(item), null, null, "DEFERRED_OPINION_ALREADY_RESOLVED");
            item.setOutcome("SKIPPED");
            item.setIssue("DEFERRED_OPINION_ALREADY_RESOLVED");
            return;
        }
        if (item.getDecision() == com.watchwise.watchwise_api.platformimport.model.ImportDecision.SKIP_ITEM) {
            skipReplay(context, item, replayRecord);
            return;
        }
        ImportedOpinionUnit unit = planner.unit(context, itemId);
        if (unit.review() == null && unit.initialScore() == null) {
            ledger.markSkipped(context.ownerId(), unit.sourceKey(), null, null, "EMPTY_OPINION");
            return;
        }
        if (unit.review() != null && unit.review().comment().length() > MAX_REVIEW_LENGTH) {
            if (replayRecord != null) {
                throw new DeferredOpinionPendingException(
                        ImportedReviewTextNormalizer.REVIEW_TOO_LONG_ISSUE
                                + ": Edit the review before restoring this deferred opinion.");
            }
            defer(context, item, unit, "REVIEW_TEXT_TOO_LONG");
            return;
        }

        ConfirmedWatchScopeSnapshot preparedScope = watchedSummaryImportApplier.preparedScopeSnapshot(item);
        if (preparedScope == null) {
            if (replayRecord != null) {
                throw new DeferredOpinionPendingException(
                        "WATCH_SCOPE_SNAPSHOT_PENDING: Refresh the schedule or explicitly skip this opinion.");
            }
            defer(context, item, unit, "WATCH_SCOPE_SNAPSHOT_UNAVAILABLE");
            return;
        }
        var confirmedScope = replayRecord == null ? null : watchedSummaryImportApplier.confirmedScope(item);
        if (confirmedScope != null) {
            if (!preparedScope.scheduleVersion().equals(confirmedScope.scheduleVersion())) {
                throw new DeferredOpinionPendingException(
                        "WATCH_SCOPE_SNAPSHOT_MISSING: Refresh the preview and confirm the watch scope again.");
            }
        }
        Optional<HistoricalViewingEvidence> evidence = findCompletePass(
                context.ownerId(), unit.content(), preparedScope);
        if (evidence.isEmpty()) {
            if (replayRecord != null) {
                throw new DeferredOpinionPendingException(
                        "DEFERRED_OPINION_EVIDENCE_PENDING: Complete a full pass or explicitly skip this opinion.");
            }
            defer(context, item, unit, "VIEWING_EVIDENCE_NOT_PROVEN");
            return;
        }
        ensureContentReference(unit.content());

        String targetType = null;
        UUID targetId = null;
        if (unit.initialScore() != null) {
            Optional<UUID> restoredRating = ratingService.restoreInitial(context.ownerId(), unit.content(),
                    unit.initialScore(), evidence.get(), preparedScope);
            if (restoredRating.isPresent()) {
                targetType = "CONTENT_RATING";
                targetId = restoredRating.get();
            } else if (unit.review() == null) {
                if (replayRecord != null) {
                    completeDeferred(context, replayRecord, unit.sourceKey(), null, null,
                            true, "EXISTING_RATING_PRESERVED");
                    return;
                }
                ledger.markSkipped(context.ownerId(), unit.sourceKey(), null, null, "EXISTING_RATING_PRESERVED");
                return;
            }
        }

        if (unit.review() != null) {
            HistoricalOpinion source = unit.review();
            HistoricalOpinion verifiedPassReview = new HistoricalOpinion(unit.content(), evidence.get().watchNumber(),
                    source.comment(), source.scoreAtPublication(), source.sourcePublishedAt(),
                    source.sourcePublishedOn(), source.contextJson());
            Optional<UUID> restoredReview = reviewRestoreService.restore(
                    context.ownerId(), verifiedPassReview, evidence.get(), preparedScope);
            if (restoredReview.isPresent()) {
                targetType = "CONTENT_REVIEW";
                targetId = restoredReview.get();
            }
        }

        if (targetId == null) {
            if (replayRecord != null) {
                completeDeferred(context, replayRecord, unit.sourceKey(), null, null, true, "NO_OPINION_CREATED");
                return;
            }
            ledger.markSkipped(context.ownerId(), unit.sourceKey(), null, null, "NO_OPINION_CREATED");
        } else {
            if (replayRecord != null) {
                completeDeferred(context, replayRecord, unit.sourceKey(), targetType, targetId, false, null);
                return;
            }
            ledger.markApplied(context.ownerId(), unit.sourceKey(), targetType, targetId);
        }
    }

    private Optional<HistoricalViewingEvidence> findCompletePass(
            UUID ownerId, MediaReference content, ConfirmedWatchScopeSnapshot snapshot) {
        Instant now = clock.instant();
        if (snapshot == null || !content.equals(snapshot.target()) || !snapshot.releaseComplete()
                || !snapshot.validUntil().isAfter(now) || snapshot.episodes().isEmpty()
                || !watchedSummaryImportApplier.isPreparedScopeCurrent(ownerId, content, snapshot)) {
            return Optional.empty();
        }
        return findCompletePass(ownerId, content, Set.copyOf(snapshot.episodes()), true,
                snapshot.validUntil(), snapshot.scheduleVersion(), now);
    }

    private Optional<HistoricalViewingEvidence> findCompletePass(
            UUID ownerId, MediaReference content, Set<EpisodeCoordinate> expectedEpisodes,
            boolean releaseComplete, Instant validUntil, String scheduleVersion, Instant now) {
        if (!releaseComplete || !validUntil.isAfter(now) || expectedEpisodes.isEmpty()) return Optional.empty();
        String seriesId = content.type() == ContentType.SERIES ? content.tmdbId() : content.seriesTmdbId();

        Map<Integer, PassEvents> passes = new HashMap<>();
        for (DiaryEntryRepository.EpisodeCompletionWatch watch : diaryEntryRepository
                .findEpisodeCompletionWatches(ownerId, List.of(seriesId))) {
            Integer season = watch.getSeasonNumber();
            Integer episode = watch.getEpisodeNumber();
            Integer watchNumber = watch.getWatchNumber();
            if (season == null || season <= 0 || episode == null || episode <= 0
                    || watchNumber == null || watchNumber <= 0
                    || (content.type() == ContentType.SEASON && !content.seasonNumber().equals(season))) continue;
            EpisodeCoordinate coordinate = new EpisodeCoordinate(season, episode);
            PassEvents pass = passes.computeIfAbsent(watchNumber, ignored -> new PassEvents());
            if (!pass.coordinates.add(coordinate)) pass.hasDuplicateCoordinate = true;
            pass.eventIds.add(watch.getDiaryEntryId());
        }

        return passes.entrySet().stream()
                .sorted(Map.Entry.<Integer, PassEvents>comparingByKey().reversed())
                .filter(entry -> !entry.getValue().hasDuplicateCoordinate
                        && entry.getValue().coordinates.equals(expectedEpisodes)
                        && !entry.getValue().eventIds.isEmpty())
                .findFirst()
                .map(entry -> new HistoricalViewingEvidence(content, entry.getKey(), scheduleVersion,
                        Set.copyOf(entry.getValue().eventIds)));
    }

    private void ensureContentReference(MediaReference content) {
        ContentRefCreationDTO reference = switch (content.type()) {
            case SERIES -> new ContentRefCreationDTO(content.tmdbId(), ContentType.SERIES,
                    null, null, null, null, null);
            case SEASON -> new ContentRefCreationDTO(null, ContentType.SEASON, content.seriesTmdbId(),
                    content.seasonNumber(), null, null, null);
            default -> throw new IllegalArgumentException("Historical opinions require a series or season");
        };
        contentService.getOrCreateReferenceFromVerifiedImport(reference);
    }

    private void defer(ImportExecutionContext context, ImportItem item, ImportedOpinionUnit unit, String reason) {
        ObjectNode provenance = objectMapper.createObjectNode().put("reason", reason)
                .put("resolutionState", "PENDING")
                .put("contentType", unit.content().type().name())
                .put("normalizedKind", item.getNormalizedKind().name());
        if (unit.content().tmdbId() != null) provenance.put("tmdbId", unit.content().tmdbId());
        if (unit.content().seriesTmdbId() != null) provenance.put("seriesTmdbId", unit.content().seriesTmdbId());
        if (unit.content().seasonNumber() != null) provenance.put("seasonNumber", unit.content().seasonNumber());
        if (item.getResolvedTargetJson() != null) provenance.put("resolvedTargetJson", item.getResolvedTargetJson());
        try {
            ImportDeferredRecord deferred = deferredRepository.saveAndFlush(ImportDeferredRecord.builder()
                    .userId(context.ownerId()).sourceNamespace(item.getSourceNamespace())
                    .objectKind(item.getObjectKind()).sourceId(item.getSourceId())
                    .normalizedPayload(item.getPayloadJson() == null ? "{}" : item.getPayloadJson())
                    .provenance(objectMapper.writeValueAsString(provenance))
                    .createdAt(LocalDateTime.now(clock)).build());
            ledger.markApplied(context.ownerId(), unit.sourceKey(), "IMPORT_DEFERRED_RECORD", deferred.getId());
        } catch (JacksonException exception) {
            throw new IllegalStateException("Deferred opinion provenance could not be serialized", exception);
        }
    }

    private ImportDeferredRecord deferredRecordForUpdate(ImportExecutionContext context, ImportItem item) {
        if (!"deferred_opinion_replay".equals(item.getObjectKind())) return null;
        UUID deferredId;
        try {
            deferredId = UUID.fromString(item.getSourceId());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Deferred replay identity is invalid", exception);
        }
        ImportDeferredRecord deferred = deferredRepository.findByIdAndUserIdForUpdate(deferredId, context.ownerId())
                .orElse(null);
        if (deferred == null) return null;
        JsonNode payload = readPayload(item.getPayloadJson());
        JsonNode marker = payload.path("_deferredReplay");
        if (!marker.isObject()) marker = payload.path("source").path("_deferredReplay");
        if (!deferredId.toString().equals(marker.path("deferredRecordId").asString())
                || !deferred.getSourceNamespace().equals(marker.path("originalSourceNamespace").asString())
                || !deferred.getObjectKind().equals(marker.path("originalObjectKind").asString())
                || !deferred.getSourceId().equals(marker.path("originalSourceId").asString())) {
            throw new IllegalStateException("Deferred replay provenance does not match its stored record");
        }
        ExternalImportLink link = externalLinkRepository.findByUserIdAndSourceNamespaceAndObjectKindAndSourceId(
                context.ownerId(), deferred.getSourceNamespace(), deferred.getObjectKind(), deferred.getSourceId())
                .orElseThrow(() -> new IllegalStateException("Deferred source link is missing"));
        if (link.getState() != com.watchwise.watchwise_api.platformimport.model.ImportLinkState.APPLIED
                || !"IMPORT_DEFERRED_RECORD".equals(link.getTargetType())
                || !deferredId.equals(link.getTargetId())) {
            return null;
        }
        return deferred;
    }

    private void skipReplay(ImportExecutionContext context, ImportItem item, ImportDeferredRecord deferred) {
        if (deferred == null) {
            ledger.markSkipped(context.ownerId(), sourceKey(item), null, null, "DEFERRED_OPINION_ALREADY_RESOLVED");
            return;
        }
        completeDeferred(context, deferred, sourceKey(item), null, null, true, "USER_SKIPPED_DEFERRED_OPINION");
    }

    private void completeDeferred(ImportExecutionContext context, ImportDeferredRecord deferred,
                                  ImportSourceKey replayKey, String targetType, UUID targetId,
                                  boolean skipped, String reason) {
        ImportSourceKey originalKey = new ImportSourceKey(deferred.getSourceNamespace(),
                deferred.getObjectKind(), deferred.getSourceId());
        if (!ledger.completeDeferred(context.ownerId(), originalKey, deferred.getId(),
                targetType, targetId, skipped, reason)) {
            throw new IllegalStateException("Deferred source link changed before opinion restoration");
        }
        markDeferredResolved(deferred, skipped, targetType, targetId, reason);
        if (skipped) ledger.markSkipped(context.ownerId(), replayKey, null, null, reason);
        else ledger.markApplied(context.ownerId(), replayKey, targetType, targetId);
    }

    private void markDeferredResolved(ImportDeferredRecord deferred, boolean skipped, String targetType,
                                      UUID targetId, String reason) {
        try {
            ObjectNode provenance = (ObjectNode) readPayload(deferred.getProvenance()).deepCopy();
            provenance.put("resolutionState", skipped ? "RESOLVED_SKIPPED" : "RESOLVED_RESTORED");
            provenance.put("resolvedAt", clock.instant().toString());
            if (reason != null) provenance.put("resolutionReason", reason);
            if (targetType != null) provenance.put("targetType", targetType);
            if (targetId != null) provenance.put("targetId", targetId.toString());
            deferred.setProvenance(objectMapper.writeValueAsString(provenance));
            deferredRepository.save(deferred);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Deferred opinion resolution could not be stored", exception);
        }
    }

    private ImportSourceKey sourceKey(ImportItem item) {
        return new ImportSourceKey(item.getSourceNamespace(), item.getObjectKind(), item.getSourceId());
    }

    private JsonNode readPayload(String payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Deferred opinion payload is invalid", exception);
        }
    }

    private static final class PassEvents {
        private final Set<EpisodeCoordinate> coordinates = new HashSet<>();
        private final List<UUID> eventIds = new ArrayList<>();
        private boolean hasDuplicateCoordinate;
    }

    public static final class DeferredOpinionPendingException extends RuntimeException {
        public DeferredOpinionPendingException(String message) { super(message); }
    }
}
