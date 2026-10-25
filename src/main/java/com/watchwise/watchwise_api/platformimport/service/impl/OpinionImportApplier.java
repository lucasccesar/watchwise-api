package com.watchwise.watchwise_api.platformimport.service.impl;

import com.watchwise.watchwise_api.content.dto.ContentRefCreationDTO;
import com.watchwise.watchwise_api.content.dto.MediaReference;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.exception.TmdbUnavailableException;
import com.watchwise.watchwise_api.content.service.ContentService;
import com.watchwise.watchwise_api.contentrating.service.ContentRatingService;
import com.watchwise.watchwise_api.contentreview.service.HistoricalOpinion;
import com.watchwise.watchwise_api.contentreview.service.HistoricalOpinionRestoreService;
import com.watchwise.watchwise_api.contentreview.service.HistoricalViewingEvidence;
import com.watchwise.watchwise_api.diarycompletion.model.EpisodeCoordinate;
import com.watchwise.watchwise_api.diarycompletion.model.VersionedCompletionScope;
import com.watchwise.watchwise_api.diarycompletion.service.CompletionScopeLoader;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.platformimport.entity.ImportDeferredRecord;
import com.watchwise.watchwise_api.platformimport.entity.ImportItem;
import com.watchwise.watchwise_api.platformimport.model.ImportRecordKind;
import com.watchwise.watchwise_api.platformimport.model.ImportedOpinionUnit;
import com.watchwise.watchwise_api.platformimport.repository.ImportDeferredRecordRepository;
import com.watchwise.watchwise_api.platformimport.repository.ImportItemRepository;
import com.watchwise.watchwise_api.platformimport.service.ImportExecutionContext;
import com.watchwise.watchwise_api.platformimport.service.ImportLedger;
import com.watchwise.watchwise_api.platformimport.service.ImportUnitApplier;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
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
    private final CompletionScopeLoader scopeLoader;
    private final DiaryEntryRepository diaryEntryRepository;
    private final UserRepository userRepository;
    private final ImportDeferredRecordRepository deferredRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OpinionImportApplier(
            ImportedOpinionPlanner planner,
            ImportItemRepository itemRepository,
            ImportLedger ledger,
            HistoricalOpinionRestoreService reviewRestoreService,
            ContentRatingService ratingService,
            ContentService contentService,
            CompletionScopeLoader scopeLoader,
            DiaryEntryRepository diaryEntryRepository,
            UserRepository userRepository,
            ImportDeferredRecordRepository deferredRepository,
            ObjectMapper objectMapper,
            Clock clock) {
        this.planner = planner;
        this.itemRepository = itemRepository;
        this.ledger = ledger;
        this.reviewRestoreService = reviewRestoreService;
        this.ratingService = ratingService;
        this.contentService = contentService;
        this.scopeLoader = scopeLoader;
        this.diaryEntryRepository = diaryEntryRepository;
        this.userRepository = userRepository;
        this.deferredRepository = deferredRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public boolean supports(ImportRecordKind kind) {
        return kind == ImportRecordKind.REVIEW || kind == ImportRecordKind.CURRENT_RATING;
    }

    @Override
    public void apply(ImportExecutionContext context, UUID itemId) {
        ImportedOpinionUnit unit = planner.unit(context, itemId);
        ImportItem item = itemRepository.findById(itemId).orElseThrow();
        if (unit.review() == null && unit.initialScore() == null) {
            ledger.markSkipped(context.ownerId(), unit.sourceKey(), null, null, "EMPTY_OPINION");
            return;
        }
        if (unit.review() != null && unit.review().comment().length() > MAX_REVIEW_LENGTH) {
            defer(context, item, unit, "REVIEW_TEXT_TOO_LONG");
            return;
        }

        Optional<HistoricalViewingEvidence> evidence = findCompletePass(context.ownerId(), unit.content());
        if (evidence.isEmpty()) {
            defer(context, item, unit, "VIEWING_EVIDENCE_NOT_PROVEN");
            return;
        }
        ensureContentReference(unit.content());

        String targetType = null;
        UUID targetId = null;
        if (unit.initialScore() != null) {
            Optional<UUID> restoredRating = ratingService.restoreInitial(
                    context.ownerId(), unit.content(), unit.initialScore(), evidence.get());
            if (restoredRating.isPresent()) {
                targetType = "CONTENT_RATING";
                targetId = restoredRating.get();
            } else if (unit.review() == null) {
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
                    context.ownerId(), verifiedPassReview, evidence.get());
            if (restoredReview.isPresent()) {
                targetType = "CONTENT_REVIEW";
                targetId = restoredReview.get();
            }
        }

        if (targetId == null) {
            ledger.markSkipped(context.ownerId(), unit.sourceKey(), null, null, "NO_OPINION_CREATED");
        } else {
            ledger.markApplied(context.ownerId(), unit.sourceKey(), targetType, targetId);
        }
    }

    private Optional<HistoricalViewingEvidence> findCompletePass(UUID ownerId, MediaReference content) {
        if (content.type() != ContentType.SERIES && content.type() != ContentType.SEASON) return Optional.empty();
        User owner = userRepository.findById(ownerId).orElseThrow();
        String seriesId = content.type() == ContentType.SERIES ? content.tmdbId() : content.seriesTmdbId();
        Instant now = clock.instant();
        VersionedCompletionScope scope;
        try {
            scope = scopeLoader.load(content, owner.getPreferredRegion(), owner.getPreferredLanguage(), now);
        } catch (NotFoundException | TmdbUnavailableException unavailableScope) {
            return Optional.empty();
        }
        if (!scope.scope().releaseComplete() || !scope.validUntil().isAfter(now)
                || scope.scope().episodes().isEmpty()) return Optional.empty();

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
                        && entry.getValue().coordinates.equals(scope.scope().episodes())
                        && !entry.getValue().eventIds.isEmpty())
                .findFirst()
                .map(entry -> new HistoricalViewingEvidence(content, entry.getKey(), scope.scheduleVersion(),
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
        contentService.getOrCreateReference(reference);
    }

    private void defer(ImportExecutionContext context, ImportItem item, ImportedOpinionUnit unit, String reason) {
        ObjectNode provenance = objectMapper.createObjectNode().put("reason", reason)
                .put("contentType", unit.content().type().name());
        if (unit.content().tmdbId() != null) provenance.put("tmdbId", unit.content().tmdbId());
        if (unit.content().seriesTmdbId() != null) provenance.put("seriesTmdbId", unit.content().seriesTmdbId());
        if (unit.content().seasonNumber() != null) provenance.put("seasonNumber", unit.content().seasonNumber());
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

    private static final class PassEvents {
        private final Set<EpisodeCoordinate> coordinates = new HashSet<>();
        private final List<UUID> eventIds = new ArrayList<>();
        private boolean hasDuplicateCoordinate;
    }
}
