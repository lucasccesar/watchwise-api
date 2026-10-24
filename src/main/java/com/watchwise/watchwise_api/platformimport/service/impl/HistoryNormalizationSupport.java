package com.watchwise.watchwise_api.platformimport.service.impl;

import com.watchwise.watchwise_api.content.dto.MediaReference;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.platformimport.entity.ImportItem;
import com.watchwise.watchwise_api.platformimport.entity.ImportJob;
import com.watchwise.watchwise_api.platformimport.model.AliasQuery;
import com.watchwise.watchwise_api.platformimport.model.ImportDecision;
import com.watchwise.watchwise_api.platformimport.model.ImportRecordKind;
import com.watchwise.watchwise_api.platformimport.model.ImportTarget;
import com.watchwise.watchwise_api.platformimport.model.NormalizedHistoryPlan;
import com.watchwise.watchwise_api.platformimport.repository.ImportItemRepository;
import com.watchwise.watchwise_api.platformimport.repository.ImportJobRepository;
import com.watchwise.watchwise_api.platformimport.service.ImportExecutionContext;
import com.watchwise.watchwise_api.platformimport.service.ImportIdentityNormalizer;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class HistoryNormalizationSupport {
    private static final int BATCH_SIZE = 200;
    private static final List<String> ACTIVE_OUTCOMES = List.of("MATCHED", "RESOLVED", "NORMALIZATION_ISSUE");
    private static final List<ImportRecordKind> HISTORY_KINDS = List.of(
            ImportRecordKind.DIARY_EVENT, ImportRecordKind.REVIEW,
            ImportRecordKind.WATCHED_MARKER, ImportRecordKind.CURRENT_RATING);
    private static final List<ImportRecordKind> EVENT_KINDS = List.of(
            ImportRecordKind.DIARY_EVENT, ImportRecordKind.REVIEW);
    private static final List<ImportRecordKind> CONTENT_FRAGMENT_KINDS = List.of(
            ImportRecordKind.WATCHED_MARKER, ImportRecordKind.CURRENT_RATING);
    private static final List<ImportRecordKind> LETTERBOXD_TARGET_KINDS = List.of(
            ImportRecordKind.DIARY_EVENT, ImportRecordKind.REVIEW, ImportRecordKind.WATCHED_MARKER,
            ImportRecordKind.CURRENT_RATING, ImportRecordKind.SERIES_EXPANSION);

    private final ImportItemRepository itemRepository;
    private final ImportJobRepository jobRepository;
    private final ImportUnitTransaction unitTransaction;
    private final ImportIdentityNormalizer identityNormalizer;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public HistoryNormalizationSupport(ImportItemRepository itemRepository, ImportJobRepository jobRepository,
                                       ImportUnitTransaction unitTransaction,
                                       ImportIdentityNormalizer identityNormalizer,
                                       ObjectMapper objectMapper, Clock clock) {
        this.itemRepository = itemRepository;
        this.jobRepository = jobRepository;
        this.unitTransaction = unitTransaction;
        this.identityNormalizer = identityNormalizer;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public NormalizedHistoryPlan normalize(ImportExecutionContext context, boolean trakt) {
        validateContext(context);
        if (trakt) normalizeTraktEpisodeTargets(context);
        normalizeEventGroups(context, trakt);
        normalizeContentFragments(context, trakt);
        return new NormalizedHistoryPlan(context.jobId(),
                count(context, ImportRecordKind.DIARY_EVENT)
                        + count(context, ImportRecordKind.SERIES_EXPANSION),
                count(context, ImportRecordKind.WATCHED_MARKER),
                count(context, ImportRecordKind.CURRENT_RATING),
                count(context, ImportRecordKind.REVIEW),
                itemRepository.countHistoryIssues(context.jobId(), context.ownerId(), context.planGeneration()));
    }

    private void validateContext(ImportExecutionContext context) {
        if (context == null) throw new IllegalArgumentException("History normalization context is required");
        ImportJob job = jobRepository.findOwnedById(context.jobId(), context.ownerId())
                .orElseThrow(() -> new IllegalStateException("Import job does not belong to the normalization owner"));
        if (job.getPreviewVersion() != context.previewVersion()
                || job.getState() != com.watchwise.watchwise_api.platformimport.model.ImportJobState.RESOLVING
                || !"RESOLVE".equals(job.getWorkPhase()) || !job.isWorkPending()
                || job.getLeaseToken() == null || !job.getLeaseToken().equals(context.leaseToken())
                || job.getLeaseUntil() == null || !job.getLeaseUntil().isAfter(LocalDateTime.now(clock))
                || !context.sourceNamespace().equals(job.getSourceNamespace())
                || readPlanGeneration(job.getSelectionJson()) != context.planGeneration()) {
            throw new IllegalStateException("History normalization context is stale");
        }
    }

    private long readPlanGeneration(String selectionJson) {
        try {
            JsonNode root = objectMapper.readTree(selectionJson);
            JsonNode version = root.get("schemaVersion");
            JsonNode generation = root.get("planGeneration");
            if (version == null && generation == null) return 0;
            if (version == null || !version.isInt() || version.asInt() != 1
                    || generation == null || !generation.canConvertToLong() || generation.asLong() < 0) {
                throw new IllegalStateException("Stored import plan generation is invalid");
            }
            return generation.asLong();
        } catch (JacksonException exception) {
            throw new IllegalStateException("Stored import plan is invalid", exception);
        }
    }

    private void normalizeTraktEpisodeTargets(ImportExecutionContext context) {
        long afterOrdinal = -1;
        UUID afterId = new UUID(0, 0);
        while (true) {
            List<ImportItem> page = itemRepository.findHistoryRowsAfter(context.jobId(), context.ownerId(),
                    context.planGeneration(), HISTORY_KINDS, ACTIVE_OUTCOMES, afterOrdinal, afterId,
                    PageRequest.of(0, BATCH_SIZE));
            if (page.isEmpty()) return;
            List<ImportUnitTransaction.HistoryNormalizationUpdate> updates = new ArrayList<>(page.size());
            for (ImportItem item : page) {
                afterOrdinal = item.getOrdinal();
                afterId = item.getId();
                if (item.getDecision() == ImportDecision.SKIP_ITEM) continue;
                String problem = itemProblem(item, context);
                if (problem != null) {
                    updates.add(issue(item, problem));
                    continue;
                }
                try {
                    ImportTarget target = readTarget(item.getResolvedTargetJson());
                    if (!(target instanceof ImportTarget.ContentTarget contentTarget)) {
                        updates.add(issue(item, "History content resolved to a non-content target."));
                        continue;
                    }
                    MediaReference media = contentTarget.content();
                    EpisodeCoordinates coordinates = episodeCoordinates(item.getPayloadJson());
                    if (coordinates == null) {
                        String targetKey = contentKey(target);
                        if (!targetKey.equals(item.getResolvedContentKey())
                                || "NORMALIZATION_ISSUE".equals(item.getOutcome()) || item.getIssue() != null) {
                            updates.add(update(item, item.getNormalizedKind(), item.getPayloadJson(),
                                    item.getResolvedTargetJson(), targetKey, "MATCHED", null));
                        }
                        continue;
                    }
                    if (media.type() == ContentType.SERIES) {
                        media = coordinates.episodeNumber() == null
                                ? MediaReference.season(media.tmdbId(), coordinates.seasonNumber())
                                : MediaReference.episode(media.tmdbId(), coordinates.seasonNumber(), coordinates.episodeNumber());
                        target = new ImportTarget.ContentTarget(media);
                    } else if (media.type() != ContentType.SEASON && media.type() != ContentType.EPISODE) {
                        updates.add(issue(item, "Trakt episode coordinates did not resolve to a series or episode."));
                        continue;
                    } else if ((media.type() == ContentType.SEASON || media.type() == ContentType.EPISODE)
                            && (!coordinates.seasonNumber().equals(media.seasonNumber())
                            || (coordinates.episodeNumber() != null
                            && !coordinates.episodeNumber().equals(media.episodeNumber())))) {
                        updates.add(issue(item, "Trakt episode coordinates conflict with the resolved content target."));
                        continue;
                    }
                    String targetKey = contentKey(target);
                    updates.add(update(item, item.getNormalizedKind(), item.getPayloadJson(), writeJson(target),
                            targetKey, "MATCHED", null));
                } catch (IllegalArgumentException exception) {
                    updates.add(issue(item, "Trakt episode or season coordinates are invalid."));
                }
            }
            save(context, updates);
            renewLease(context);
            if (page.size() < BATCH_SIZE) return;
        }
    }

    private void normalizeEventGroups(ImportExecutionContext context, boolean trakt) {
        String afterKey = "";
        while (true) {
            List<String> keys = itemRepository.findSourceEventKeysAfter(context.jobId(), context.ownerId(),
                    context.planGeneration(), afterKey, EVENT_KINDS, ACTIVE_OUTCOMES, PageRequest.of(0, BATCH_SIZE));
            if (keys.isEmpty()) return;
            Map<String, List<ImportItem>> recordsByEvent = new LinkedHashMap<>();
            for (ImportItem item : itemRepository.findHistoryEventGroups(context.jobId(), context.ownerId(),
                    context.planGeneration(), keys, EVENT_KINDS, ACTIVE_OUTCOMES)) {
                recordsByEvent.computeIfAbsent(item.getSourceEventKey(), ignored -> new ArrayList<>()).add(item);
            }
            List<UUID> eventIds = recordsByEvent.values().stream().flatMap(Collection::stream)
                    .filter(item -> item.getNormalizedKind() == ImportRecordKind.DIARY_EVENT)
                    .filter(item -> item.getDecision() != ImportDecision.SKIP_ITEM)
                    .map(ImportItem::getId).toList();
            Set<UUID> consumedEventIds = eventIds.isEmpty() ? Set.of()
                    : Set.copyOf(itemRepository.findConsumedHistoryItemIds(context.ownerId(), context.jobId(),
                    context.planGeneration(), eventIds));
            for (String key : keys) {
                afterKey = key;
                List<ImportItem> group = recordsByEvent.getOrDefault(key, List.of()).stream()
                        .filter(item -> item.getDecision() != ImportDecision.SKIP_ITEM).toList();
                normalizeEventGroup(context, group, consumedEventIds, trakt);
            }
            renewLease(context);
            if (keys.size() < BATCH_SIZE) return;
        }
    }

    private void normalizeEventGroup(ImportExecutionContext context, List<ImportItem> group,
                                    Set<UUID> consumedEventIds, boolean trakt) {
        if (group.isEmpty()) return;
        String problem = groupProblem(context, group);
        List<ImportItem> events = group.stream()
                .filter(item -> item.getNormalizedKind() == ImportRecordKind.DIARY_EVENT).toList();
        List<ImportItem> reviews = group.stream()
                .filter(item -> item.getNormalizedKind() == ImportRecordKind.REVIEW).toList();
        if (problem == null && events.size() > 1) problem = "One source event key identifies multiple diary events.";
        if (problem == null && reviews.size() > 1) problem = "One source event key identifies multiple review fragments.";
        if (problem == null && !events.isEmpty() && !reviews.isEmpty()
                && !sameContent(events.getFirst(), reviews.getFirst())) {
            problem = "Diary and review fragments resolve to different content identities.";
        }
        if (problem != null) {
            String message = problem;
            save(context, group.stream().map(item -> issue(item, message)).toList());
            return;
        }
        if (events.isEmpty()) {
            for (ImportItem item : group) {
                if ("NORMALIZATION_ISSUE".equals(item.getOutcome()) || item.getIssue() != null) {
                    save(context, List.of(update(item, item.getNormalizedKind(), item.getPayloadJson(),
                            item.getResolvedTargetJson(), item.getResolvedContentKey(), "MATCHED", null)));
                }
            }
            return;
        }

        ImportItem event = events.getFirst();
        ImportTarget target = readTarget(event.getResolvedTargetJson());
        ContentType type = contentType(target);
        if (type == ContentType.SERIES) {
            if (trakt) {
                save(context, group.stream().map(item -> issue(item,
                        "Trakt series rows cannot be normalized as diary events.")).toList());
            } else {
                normalizeLetterboxdSeriesEvent(context, event, reviews.isEmpty() ? null : reviews.getFirst(),
                        consumedEventIds.contains(event.getId()));
            }
            return;
        }
        if (type == ContentType.SEASON) {
            save(context, group.stream().filter(item -> item.getNormalizedKind() == ImportRecordKind.DIARY_EVENT)
                    .map(item -> issue(item, "SEASON content cannot be normalized as a diary watch event.")).toList());
            return;
        }
        if (reviews.isEmpty()) {
            if ("NORMALIZATION_ISSUE".equals(event.getOutcome()) || event.getIssue() != null) {
                save(context, List.of(update(event, event.getNormalizedKind(), event.getPayloadJson(),
                        event.getResolvedTargetJson(), event.getResolvedContentKey(), "MATCHED", null)));
            }
            return;
        }
        ImportItem review = reviews.getFirst();
        if (consumedEventIds.contains(event.getId())) {
            save(context, List.of(
                    update(event, event.getNormalizedKind(), event.getPayloadJson(), event.getResolvedTargetJson(),
                            event.getResolvedContentKey(), "MATCHED", null),
                    update(review, review.getNormalizedKind(), review.getPayloadJson(), review.getResolvedTargetJson(),
                            review.getResolvedContentKey(), "ABSORBED", null)));
            return;
        }

        ObjectNode eventPayload = readObject(event.getPayloadJson());
        JsonNode reviewPayload = readPayload(review.getPayloadJson());
        JsonNode reviewText = reviewPayload.get("review");
        if (reviewText != null && reviewText.isTextual() && !reviewText.asString().isBlank()) {
            eventPayload.set("review", reviewText.deepCopy());
        }
        JsonNode score = reviewPayload.get("score");
        if (score != null && score.isIntegralNumber()) eventPayload.set("score", score.deepCopy());

        save(context, List.of(
                update(event, ImportRecordKind.DIARY_EVENT, writeJson(eventPayload), event.getResolvedTargetJson(),
                        event.getResolvedContentKey(), "MATCHED", null),
                update(review, review.getNormalizedKind(), review.getPayloadJson(), review.getResolvedTargetJson(),
                        review.getResolvedContentKey(), "ABSORBED", null)));
    }

    private void normalizeLetterboxdSeriesEvent(ImportExecutionContext context, ImportItem event,
                                                ImportItem review, boolean consumed) {
        if (consumed) {
            List<ImportUnitTransaction.HistoryNormalizationUpdate> updates = new ArrayList<>(2);
            updates.add(update(event, ImportRecordKind.SERIES_EXPANSION, event.getPayloadJson(),
                    event.getResolvedTargetJson(), event.getResolvedContentKey(), "MATCHED", null));
            if (review != null) updates.add(update(review, review.getNormalizedKind(), review.getPayloadJson(),
                    review.getResolvedTargetJson(), review.getResolvedContentKey(), "ABSORBED", null));
            save(context, updates);
            return;
        }

        ObjectNode parentPayload = readObject(event.getPayloadJson());
        List<ImportUnitTransaction.HistoryNormalizationUpdate> updates = new ArrayList<>(2);
        if (review != null) {
            JsonNode reviewPayload = readPayload(review.getPayloadJson());
            mergeSeriesReviewSnapshot(parentPayload, reviewPayload);
            updates.add(update(review, review.getNormalizedKind(), review.getPayloadJson(),
                    review.getResolvedTargetJson(), review.getResolvedContentKey(), "ABSORBED", null));
        }
        updates.add(update(event, ImportRecordKind.SERIES_EXPANSION, writeJson(parentPayload),
                event.getResolvedTargetJson(), event.getResolvedContentKey(), "MATCHED", null));
        save(context, updates);
    }

    private void mergeSeriesReviewSnapshot(ObjectNode parentPayload, JsonNode reviewPayload) {
        JsonNode text = reviewPayload.get("review");
        if (text != null && text.isTextual() && !text.asString().isBlank()) parentPayload.set("review", text.deepCopy());
        JsonNode score = reviewPayload.get("score");
        if (score != null && score.isIntegralNumber()) parentPayload.set("reviewScoreAtPublication", score.deepCopy());
        JsonNode sourceRecordedOn = reviewPayload.get("sourceRecordedOn");
        if (sourceRecordedOn != null && sourceRecordedOn.isTextual()) {
            parentPayload.set("reviewSourceRecordedOn", sourceRecordedOn.deepCopy());
        }
    }

    private String groupProblem(ImportExecutionContext context, List<ImportItem> group) {
        String version = group.getFirst().getParserVersion();
        if (version == null || version.isBlank()) return "History fragment has no parser version.";
        for (ImportItem item : group) {
            if (!context.sourceNamespace().equals(item.getSourceNamespace())) {
                return "History fragment belongs to a different source namespace.";
            }
            if (!version.equals(item.getParserVersion())) {
                return "History fragments from different parser versions cannot be merged.";
            }
            if (item.getResolvedContentKey() == null || item.getResolvedTargetJson() == null
                    || itemProblem(item, context) != null) {
                return "History fragment has an unresolved or invalid content identity.";
            }
        }
        for (int left = 0; left < group.size(); left++) {
            for (int right = left + 1; right < group.size(); right++) {
                if (!sameSourceIdentity(group.get(left), group.get(right))) {
                    return "History fragments with the same source event key have incompatible identity payloads.";
                }
            }
        }
        return null;
    }

    private boolean sameSourceIdentity(ImportItem left, ImportItem right) {
        if (!java.util.Objects.equals(left.getResolvedContentKey(), right.getResolvedContentKey())) return false;
        AliasQuery leftQuery = queryForPayload(left.getPayloadJson());
        AliasQuery rightQuery = queryForPayload(right.getPayloadJson());
        if (leftQuery == null || rightQuery == null) return false;
        JsonNode leftUriNode = readPayload(left.getPayloadJson()).path("sourceContentUri");
        JsonNode rightUriNode = readPayload(right.getPayloadJson()).path("sourceContentUri");
        String leftUri = leftUriNode.isTextual() ? leftUriNode.asString() : null;
        String rightUri = rightUriNode.isTextual() ? rightUriNode.asString() : null;
        if (leftUri != null && rightUri != null && !leftUri.equals(rightUri)) return false;
        if (!identityNormalizer.normalizeTitle(leftQuery.title())
                .equals(identityNormalizer.normalizeTitle(rightQuery.title()))) return false;
        return leftQuery.year() == null || rightQuery.year() == null || leftQuery.year().equals(rightQuery.year());
    }

    private boolean sameContent(ImportItem left, ImportItem right) {
        return java.util.Objects.equals(left.getResolvedContentKey(), right.getResolvedContentKey());
    }

    private void normalizeContentFragments(ImportExecutionContext context, boolean trakt) {
        Collection<ImportRecordKind> keyKinds = trakt ? CONTENT_FRAGMENT_KINDS : LETTERBOXD_TARGET_KINDS;
        String afterKey = "";
        while (true) {
            List<String> keys = itemRepository.findResolvedContentKeysAfter(context.jobId(), context.ownerId(),
                    context.planGeneration(), afterKey, keyKinds, ACTIVE_OUTCOMES,
                    PageRequest.of(0, BATCH_SIZE));
            if (keys.isEmpty()) return;
            for (String key : keys) {
                afterKey = key;
                normalizeContentGroup(context, key, trakt);
            }
            renewLease(context);
            if (keys.size() < BATCH_SIZE) return;
        }
    }

    private void normalizeContentGroup(ImportExecutionContext context, String contentKey, boolean trakt) {
        List<ImportItem> first = itemRepository.findHistoryTargetFragmentsAfter(context.jobId(), context.ownerId(),
                context.planGeneration(), contentKey, LETTERBOXD_TARGET_KINDS, ACTIVE_OUTCOMES,
                -1, new UUID(0, 0), PageRequest.of(0, BATCH_SIZE));
        if (first.isEmpty()) return;
        first = first.stream().filter(item -> item.getDecision() != ImportDecision.SKIP_ITEM).toList();
        if (first.isEmpty()) return;
        ImportTarget target = first.stream().filter(item -> item.getResolvedTargetJson() != null)
                .map(item -> readTarget(item.getResolvedTargetJson())).findFirst().orElse(null);
        if (target == null || !contentKey.equals(contentKey(target))) {
            save(context, first.stream().map(item -> issue(item,
                    "Content fragments do not share a valid resolved target identity.")).toList());
            return;
        }
        ContentType targetType = contentType(target);
        if (!trakt && (targetType == ContentType.MOVIE || targetType == ContentType.EPISODE)) {
            boolean hasMarker = itemRepository.existsHistoryUnit(context.jobId(), context.ownerId(),
                    context.planGeneration(), contentKey, ImportRecordKind.WATCHED_MARKER, ACTIVE_OUTCOMES,
                    ImportDecision.SKIP_ITEM);
            boolean hasRating = itemRepository.existsHistoryUnit(context.jobId(), context.ownerId(),
                    context.planGeneration(), contentKey, ImportRecordKind.CURRENT_RATING, ACTIVE_OUTCOMES,
                    ImportDecision.SKIP_ITEM);
            if (!hasMarker && !hasRating) return;
        }
        String groupProblem = contentGroupProblem(context, contentKey, LETTERBOXD_TARGET_KINDS);
        if (groupProblem != null) {
            walkTargetFragments(context, contentKey, LETTERBOXD_TARGET_KINDS, page ->
                    save(context, page.stream().filter(item -> item.getDecision() != ImportDecision.SKIP_ITEM)
                            .map(item -> issue(item, groupProblem)).toList()));
            return;
        }

        if (!trakt && targetType == ContentType.SERIES) {
            normalizeLetterboxdSeriesGroup(context, contentKey);
            return;
        }

        boolean diaryExists = itemRepository.existsHistoryUnit(context.jobId(), context.ownerId(),
                context.planGeneration(), contentKey, ImportRecordKind.DIARY_EVENT, ACTIVE_OUTCOMES,
                ImportDecision.SKIP_ITEM);
        if (targetType == ContentType.MOVIE || targetType == ContentType.EPISODE) {
            markMarkers(context, contentKey, diaryExists);
            markRatings(context, contentKey, ImportRecordKind.DIARY_EVENT, diaryExists, false);
        } else {
            clearFragmentIssues(context, contentKey);
        }
    }

    private void normalizeLetterboxdSeriesGroup(ImportExecutionContext context, String contentKey) {
        walkTargetFragments(context, contentKey, List.of(ImportRecordKind.DIARY_EVENT), page ->
                save(context, page.stream().filter(item -> item.getDecision() != ImportDecision.SKIP_ITEM)
                        .map(item -> update(item, ImportRecordKind.SERIES_EXPANSION, item.getPayloadJson(),
                                item.getResolvedTargetJson(), item.getResolvedContentKey(), "MATCHED", null)).toList()));
        boolean hasExpansion = itemRepository.existsHistoryUnit(context.jobId(), context.ownerId(),
                context.planGeneration(), contentKey, ImportRecordKind.SERIES_EXPANSION, ACTIVE_OUTCOMES,
                ImportDecision.SKIP_ITEM);
        if (!hasExpansion) {
            walkTargetFragments(context, contentKey, List.of(ImportRecordKind.WATCHED_MARKER), page -> {
                List<ImportUnitTransaction.HistoryNormalizationUpdate> updates = page.stream()
                        .filter(item -> item.getDecision() != ImportDecision.SKIP_ITEM)
                        .map(item -> update(item, ImportRecordKind.SERIES_EXPANSION, item.getPayloadJson(),
                                item.getResolvedTargetJson(), contentKey, "MATCHED", null)).toList();
                save(context, updates);
            });
            hasExpansion = itemRepository.existsHistoryUnit(context.jobId(), context.ownerId(),
                    context.planGeneration(), contentKey, ImportRecordKind.SERIES_EXPANSION, ACTIVE_OUTCOMES,
                    ImportDecision.SKIP_ITEM);
        }
        if (!hasExpansion) {
            clearFragmentIssues(context, contentKey);
            return;
        }

        walkTargetFragments(context, contentKey, List.of(ImportRecordKind.WATCHED_MARKER), page ->
                save(context, page.stream().filter(item -> item.getDecision() != ImportDecision.SKIP_ITEM)
                        .map(item -> update(item, item.getNormalizedKind(), item.getPayloadJson(),
                                item.getResolvedTargetJson(), item.getResolvedContentKey(), "ABSORBED", null)).toList()));
        mergeSeriesReviews(context, contentKey);
        boolean hasRating = itemRepository.existsHistoryUnit(context.jobId(), context.ownerId(),
                context.planGeneration(), contentKey, ImportRecordKind.CURRENT_RATING, ACTIVE_OUTCOMES,
                ImportDecision.SKIP_ITEM);
        if (hasRating) markRatings(context, contentKey, ImportRecordKind.SERIES_EXPANSION, true, true);
    }

    private void mergeSeriesReviews(ImportExecutionContext context, String contentKey) {
        walkTargetFragments(context, contentKey, List.of(ImportRecordKind.REVIEW), page -> {
            List<String> eventKeys = page.stream().filter(item -> item.getDecision() != ImportDecision.SKIP_ITEM)
                    .map(ImportItem::getSourceEventKey).filter(java.util.Objects::nonNull).distinct().toList();
            if (eventKeys.isEmpty()) return;
            Map<String, List<ImportItem>> parentsByEvent = new LinkedHashMap<>();
            for (ImportItem parent : itemRepository.findHistoryEventGroups(context.jobId(), context.ownerId(),
                    context.planGeneration(), eventKeys, List.of(ImportRecordKind.SERIES_EXPANSION), ACTIVE_OUTCOMES)) {
                parentsByEvent.computeIfAbsent(parent.getSourceEventKey(), ignored -> new ArrayList<>()).add(parent);
            }
            List<UUID> parentIds = parentsByEvent.values().stream().flatMap(Collection::stream)
                    .map(ImportItem::getId).distinct().toList();
            Set<UUID> consumedParentIds = parentIds.isEmpty() ? Set.of()
                    : Set.copyOf(itemRepository.findConsumedHistoryItemIds(context.ownerId(), context.jobId(),
                    context.planGeneration(), parentIds));
            List<ImportUnitTransaction.HistoryNormalizationUpdate> updates = new ArrayList<>();
            for (ImportItem review : page) {
                if (review.getDecision() == ImportDecision.SKIP_ITEM) continue;
                List<ImportItem> parents = parentsByEvent.getOrDefault(review.getSourceEventKey(), List.of());
                if (parents.isEmpty()) continue;
                if (parents.size() != 1) {
                    updates.add(issue(review, "One series review event matches multiple expansion parents."));
                    continue;
                }
                ImportItem parent = parents.getFirst();
                if (consumedParentIds.contains(parent.getId())) {
                    updates.add(update(review, review.getNormalizedKind(), review.getPayloadJson(),
                            review.getResolvedTargetJson(), review.getResolvedContentKey(), "ABSORBED", null));
                    continue;
                }
                ObjectNode parentPayload = readObject(parent.getPayloadJson());
                mergeSeriesReviewSnapshot(parentPayload, readPayload(review.getPayloadJson()));
                updates.add(update(parent, ImportRecordKind.SERIES_EXPANSION, writeJson(parentPayload),
                        parent.getResolvedTargetJson(), parent.getResolvedContentKey(), "MATCHED", null));
                updates.add(update(review, review.getNormalizedKind(), review.getPayloadJson(),
                        review.getResolvedTargetJson(), review.getResolvedContentKey(), "ABSORBED", null));
            }
            save(context, updates);
        });
    }

    private String contentGroupProblem(ImportExecutionContext context, String contentKey,
                                       Collection<ImportRecordKind> kinds) {
        String parserVersion = null;
        long afterOrdinal = -1;
        UUID afterId = new UUID(0, 0);
        while (true) {
            List<ImportItem> page = itemRepository.findHistoryTargetFragmentsAfter(context.jobId(), context.ownerId(),
                    context.planGeneration(), contentKey, kinds, ACTIVE_OUTCOMES,
                    afterOrdinal, afterId, PageRequest.of(0, BATCH_SIZE));
            if (page.isEmpty()) return null;
            for (ImportItem item : page) {
                afterOrdinal = item.getOrdinal();
                afterId = item.getId();
                if (item.getDecision() == ImportDecision.SKIP_ITEM) continue;
                String problem = itemProblem(item, context);
                if (problem != null) return problem;
                if (parserVersion == null) parserVersion = item.getParserVersion();
                else if (!parserVersion.equals(item.getParserVersion())) {
                    return "Content fragments from different parser versions cannot be merged.";
                }
            }
            renewLease(context);
            if (page.size() < BATCH_SIZE) return null;
        }
    }

    private void markMarkers(ImportExecutionContext context, String contentKey, boolean diaryExists) {
        walkTargetFragments(context, contentKey, List.of(ImportRecordKind.WATCHED_MARKER), page -> {
            List<ImportUnitTransaction.HistoryNormalizationUpdate> updates = new ArrayList<>(page.size());
            for (ImportItem item : page) {
                if (item.getDecision() == ImportDecision.SKIP_ITEM) continue;
                String problem = itemProblem(item, context);
                if (problem != null) updates.add(issue(item, problem));
                else updates.add(update(item, item.getNormalizedKind(), item.getPayloadJson(),
                        item.getResolvedTargetJson(), contentKey,
                        diaryExists ? "ABSORBED" : "MATCHED", null));
            }
            save(context, updates);
        });
    }

    private void markRatings(ImportExecutionContext context, String contentKey, ImportRecordKind eventKind,
                             boolean parentExists, boolean seriesExpansion) {
        CurrentRatingSelection selection = selectCurrentRating(context, contentKey);
        if (!parentExists) return;
        LatestEvent latest = latestEvent(context, contentKey, eventKind);
        String issue = selection.issue() != null ? selection.issue() : latest.issue();
        if (issue != null) {
            String reason = issue;
            walkTargetFragments(context, contentKey, List.of(ImportRecordKind.CURRENT_RATING), page ->
                    save(context, page.stream().filter(item -> item.getDecision() != ImportDecision.SKIP_ITEM)
                            .map(item -> issue(item, reason)).toList()));
            return;
        }
        if (latest.event() == null || latest.consumed()) {
            walkTargetFragments(context, contentKey, List.of(ImportRecordKind.CURRENT_RATING), page ->
                    save(context, page.stream().filter(item -> item.getDecision() != ImportDecision.SKIP_ITEM)
                            .map(item -> update(item, item.getNormalizedKind(), item.getPayloadJson(),
                                    item.getResolvedTargetJson(), item.getResolvedContentKey(), "ABSORBED", null)).toList()));
            return;
        }

        ImportItem selectedRating = selection.item();
        Integer score = selection.score();
        if (selectedRating == null || score == null) {
            walkTargetFragments(context, contentKey, List.of(ImportRecordKind.CURRENT_RATING), page ->
                    save(context, page.stream().filter(item -> item.getDecision() != ImportDecision.SKIP_ITEM)
                            .map(item -> update(item, item.getNormalizedKind(), item.getPayloadJson(),
                                    item.getResolvedTargetJson(), item.getResolvedContentKey(), "ABSORBED", null)).toList()));
            return;
        }

        ObjectNode eventPayload = readObject(latest.event().getPayloadJson());
        if (seriesExpansion) {
            eventPayload.put("currentRatingScore", score);
            JsonNode ratedOn = readPayload(selectedRating.getPayloadJson()).get("sourceRecordedOn");
            if (ratedOn != null && ratedOn.isTextual()) {
                eventPayload.set("currentRatingSourceRecordedOn", ratedOn.deepCopy());
            }
        } else {
            eventPayload.put("score", score);
        }
        save(context, List.of(update(latest.event(), latest.event().getNormalizedKind(), writeJson(eventPayload),
                latest.event().getResolvedTargetJson(), latest.event().getResolvedContentKey(), "MATCHED", null)));
        walkTargetFragments(context, contentKey, List.of(ImportRecordKind.CURRENT_RATING), page ->
                save(context, page.stream().filter(item -> item.getDecision() != ImportDecision.SKIP_ITEM)
                        .map(item -> update(item, item.getNormalizedKind(), item.getPayloadJson(),
                                item.getResolvedTargetJson(), item.getResolvedContentKey(), "ABSORBED", null)).toList()));
    }

    private CurrentRatingSelection selectCurrentRating(ImportExecutionContext context, String contentKey) {
        ImportItem selected = null;
        LocalDate selectedDate = null;
        Integer selectedScore = null;
        boolean conflict = false;
        long afterOrdinal = -1;
        UUID afterId = new UUID(0, 0);
        while (true) {
            List<ImportItem> page = itemRepository.findHistoryTargetFragmentsAfter(context.jobId(), context.ownerId(),
                    context.planGeneration(), contentKey, List.of(ImportRecordKind.CURRENT_RATING), ACTIVE_OUTCOMES,
                    afterOrdinal, afterId, PageRequest.of(0, BATCH_SIZE));
            if (page.isEmpty()) break;
            for (ImportItem item : page) {
                afterOrdinal = item.getOrdinal();
                afterId = item.getId();
                if (item.getDecision() == ImportDecision.SKIP_ITEM) continue;
                if (selected != null && !java.util.Objects.equals(selected.getParserVersion(), item.getParserVersion())) {
                    conflict = true;
                }
                JsonNode payload = readPayload(item.getPayloadJson());
                Integer score = payload.path("score").canConvertToInt() ? payload.path("score").asInt() : null;
                LocalDate ratedOn = localDate(payload.path("sourceRecordedOn"));
                if (score == null || score < 1 || score > 10 || itemProblem(item, context) != null) {
                    conflict = true;
                } else if (selected == null || compareRatingDate(ratedOn, selectedDate) > 0
                        || (java.util.Objects.equals(ratedOn, selectedDate)
                        && item.getOrdinal() > selected.getOrdinal())) {
                    if (selected != null && java.util.Objects.equals(ratedOn, selectedDate)
                            && !score.equals(selectedScore)) conflict = true;
                    selected = item;
                    selectedDate = ratedOn;
                    selectedScore = score;
                } else if (java.util.Objects.equals(ratedOn, selectedDate) && !score.equals(selectedScore)) {
                    conflict = true;
                }
            }
            renewLease(context);
            if (page.size() < BATCH_SIZE) break;
        }
        return conflict
                ? new CurrentRatingSelection(selected, selectedScore, "Current rating fragments conflict for the same content.")
                : new CurrentRatingSelection(selected, selectedScore, null);
    }

    private int compareRatingDate(LocalDate left, LocalDate right) {
        if (left == null && right == null) return 0;
        if (left == null) return -1;
        if (right == null) return 1;
        return left.compareTo(right);
    }

    private LatestEvent latestEvent(ImportExecutionContext context, String contentKey, ImportRecordKind eventKind) {
        ImportItem latest = null;
        HistoryMoment latestMoment = null;
        int eventCount = 0;
        int undatedCount = 0;
        boolean tied = false;
        boolean latestConsumed = false;
        long afterOrdinal = -1;
        UUID afterId = new UUID(0, 0);
        while (true) {
            List<ImportItem> page = itemRepository.findHistoryTargetFragmentsAfter(context.jobId(), context.ownerId(),
                    context.planGeneration(), contentKey, List.of(eventKind), ACTIVE_OUTCOMES,
                    afterOrdinal, afterId, PageRequest.of(0, BATCH_SIZE));
            if (page.isEmpty()) break;
            List<UUID> pageIds = page.stream().map(ImportItem::getId).toList();
            var consumedIds = java.util.Set.copyOf(itemRepository.findConsumedHistoryItemIds(context.ownerId(),
                    context.jobId(), context.planGeneration(), pageIds));
            for (ImportItem item : page) {
                afterOrdinal = item.getOrdinal();
                afterId = item.getId();
                if (item.getDecision() == ImportDecision.SKIP_ITEM) continue;
                eventCount++;
                HistoryMoment moment = moment(readPayload(item.getPayloadJson()));
                if (moment == null) {
                    undatedCount++;
                    continue;
                }
                HistoryMoment previousLatestMoment = latestMoment;
                int comparison = previousLatestMoment == null ? 1 : moment.compareTo(previousLatestMoment);
                if (previousLatestMoment == null || comparison > 0) {
                    latest = item;
                    latestMoment = moment;
                    latestConsumed = consumedIds.contains(item.getId());
                    if (previousLatestMoment == null
                            || moment.date().isAfter(previousLatestMoment.date())
                            || (moment.date().equals(previousLatestMoment.date())
                            && moment.hasTime() && previousLatestMoment.hasTime())) {
                        tied = false;
                    }
                }
                if (previousLatestMoment != null && moment.date().equals(previousLatestMoment.date())
                        && (!moment.hasTime() || !previousLatestMoment.hasTime() || comparison == 0)) {
                    tied = true;
                }
            }
            renewLease(context);
            if (page.size() < BATCH_SIZE) break;
        }
        if (eventCount == 0) return new LatestEvent(null, false, null);
        if (tied) return new LatestEvent(latest, latestConsumed,
                "The latest viewing date is tied without enough time information to choose an event.");
        if (undatedCount > 0 && eventCount > 1) {
            return new LatestEvent(latest, latestConsumed,
                    "The latest viewing cannot be determined because an event has no watched date.");
        }
        if (latest == null && eventCount == 1) {
            List<ImportItem> only = itemRepository.findHistoryTargetFragmentsAfter(context.jobId(), context.ownerId(),
                    context.planGeneration(), contentKey, List.of(eventKind), ACTIVE_OUTCOMES,
                    -1, new UUID(0, 0), PageRequest.of(0, 2));
            if (only.size() == 1) {
                var consumed = itemRepository.findConsumedHistoryItemIds(context.ownerId(), context.jobId(),
                        context.planGeneration(), List.of(only.getFirst().getId()));
                return new LatestEvent(only.getFirst(), consumed.contains(only.getFirst().getId()), null);
            }
        }
        return new LatestEvent(latest, latestConsumed, null);
    }

    private HistoryMoment moment(JsonNode payload) {
        JsonNode sourceWatchedAt = payload.get("sourceWatchedAt");
        if (sourceWatchedAt != null && sourceWatchedAt.isTextual()) {
            try {
                OffsetDateTime dateTime = OffsetDateTime.parse(sourceWatchedAt.asString());
                LocalDate watchedOn = localDate(payload.path("watchedOn"));
                return new HistoryMoment(watchedOn == null ? dateTime.toLocalDate() : watchedOn,
                        dateTime.toInstant(), true);
            } catch (RuntimeException ignored) {
                try {
                    Instant instant = Instant.parse(sourceWatchedAt.asString());
                    LocalDate watchedOn = localDate(payload.path("watchedOn"));
                    return new HistoryMoment(watchedOn == null
                            ? instant.atOffset(java.time.ZoneOffset.UTC).toLocalDate() : watchedOn, instant, true);
                } catch (RuntimeException ignoredAgain) {
                    return null;
                }
            }
        }
        LocalDate date = localDate(payload.path("watchedOn"));
        return date == null ? null : new HistoryMoment(date, null, false);
    }

    private void clearFragmentIssues(ImportExecutionContext context, String contentKey) {
        clearFragmentIssues(context, contentKey, null);
    }

    private void clearFragmentIssues(ImportExecutionContext context, String contentKey, ImportRecordKind onlyKind) {
        Collection<ImportRecordKind> kinds = onlyKind == null ? CONTENT_FRAGMENT_KINDS : List.of(onlyKind);
        walkTargetFragments(context, contentKey, kinds, page -> {
            List<ImportUnitTransaction.HistoryNormalizationUpdate> updates = page.stream()
                    .filter(item -> item.getDecision() != ImportDecision.SKIP_ITEM)
                    .filter(item -> "NORMALIZATION_ISSUE".equals(item.getOutcome()) || item.getIssue() != null)
                    .map(item -> update(item, item.getNormalizedKind(), item.getPayloadJson(),
                            item.getResolvedTargetJson(), item.getResolvedContentKey(), "MATCHED", null)).toList();
            save(context, updates);
        });
    }

    private void walkTargetFragments(ImportExecutionContext context, String contentKey,
                                     Collection<ImportRecordKind> kinds,
                                     java.util.function.Consumer<List<ImportItem>> consumer) {
        long afterOrdinal = -1;
        UUID afterId = new UUID(0, 0);
        while (true) {
            List<ImportItem> page = itemRepository.findHistoryTargetFragmentsAfter(context.jobId(), context.ownerId(),
                    context.planGeneration(), contentKey, kinds, ACTIVE_OUTCOMES, afterOrdinal, afterId,
                    PageRequest.of(0, BATCH_SIZE));
            if (page.isEmpty()) return;
            ImportItem last = page.getLast();
            consumer.accept(page);
            renewLease(context);
            afterOrdinal = last.getOrdinal();
            afterId = last.getId();
            if (page.size() < BATCH_SIZE) return;
        }
    }

    private String itemProblem(ImportItem item, ImportExecutionContext context) {
        if (!context.jobId().equals(item.getJobId()) || item.getPlanGeneration() != context.planGeneration()
                || !context.sourceNamespace().equals(item.getSourceNamespace())) {
            return "History fragment is outside its job, source namespace, or plan generation.";
        }
        if (item.getParserVersion() == null || item.getParserVersion().isBlank()) {
            return "History fragment has no parser version.";
        }
        if (item.getResolvedTargetJson() == null || item.getResolvedContentKey() == null) {
            return "History fragment has no resolved content identity.";
        }
        try {
            ImportTarget target = readTarget(item.getResolvedTargetJson());
            if (!(target instanceof ImportTarget.ContentTarget)) {
                return "History fragment resolved to a non-content target.";
            }
            if (!item.getResolvedContentKey().equals(contentKey(target))) {
                return "History fragment target key does not match its resolved target.";
            }
        } catch (RuntimeException exception) {
            return "History fragment target is invalid.";
        }
        return null;
    }

    private EpisodeCoordinates episodeCoordinates(String payloadJson) {
        JsonNode query = queryNodeFor(payloadJson);
        if (query == null || !query.has("seasonNumber")) return null;
        if (!"CONTENT".equals(query.path("kind").asString())
                || !ContentType.SERIES.name().equals(query.path("contentType").asString())) {
            throw new IllegalArgumentException();
        }
        JsonNode seasonNode = query.get("seasonNumber");
        if (!seasonNode.canConvertToInt() || seasonNode.asInt() < 0) throw new IllegalArgumentException();
        Integer episode = null;
        JsonNode episodeNode = query.get("episodeNumber");
        if (episodeNode != null && !episodeNode.isNull()) {
            if (!episodeNode.canConvertToInt() || episodeNode.asInt() < 1) throw new IllegalArgumentException();
            episode = episodeNode.asInt();
        }
        return new EpisodeCoordinates(seasonNode.asInt(), episode);
    }

    private AliasQuery queryForPayload(String payloadJson) {
        JsonNode query = queryNodeFor(payloadJson);
        if (query == null || !query.path("title").isTextual() || !query.path("kind").isTextual()) return null;
        try {
            return objectMapper.treeToValue(query, AliasQuery.class);
        } catch (JacksonException | IllegalArgumentException exception) {
            return null;
        }
    }

    private JsonNode queryNodeFor(String payloadJson) {
        JsonNode payload = readPayload(payloadJson);
        JsonNode source = payload.path("source");
        JsonNode query = payload.path("sourceQuery");
        if (!query.isObject()) query = payload.path("query");
        if (!query.isObject()) query = source.path("sourceQuery");
        if (!query.isObject()) query = source.path("query");
        return query.isObject() ? query : null;
    }

    private void save(ImportExecutionContext context,
                      List<ImportUnitTransaction.HistoryNormalizationUpdate> updates) {
        for (int start = 0; start < updates.size(); start += BATCH_SIZE) {
            List<ImportUnitTransaction.HistoryNormalizationUpdate> batch = updates.subList(start,
                    Math.min(start + BATCH_SIZE, updates.size()));
            if (!unitTransaction.saveHistoryNormalization(context, List.copyOf(batch))) {
                throw new IllegalStateException("History normalization execution became stale");
            }
        }
    }

    private ImportUnitTransaction.HistoryNormalizationUpdate issue(ImportItem item, String message) {
        return update(item, item.getNormalizedKind(), item.getPayloadJson(), item.getResolvedTargetJson(),
                item.getResolvedContentKey(), "NORMALIZATION_ISSUE", message);
    }

    private ImportUnitTransaction.HistoryNormalizationUpdate update(ImportItem item, ImportRecordKind kind,
                                                                     String payload, String targetJson,
                                                                     String contentKey, String outcome, String issue) {
        return new ImportUnitTransaction.HistoryNormalizationUpdate(item.getId(), kind, payload, targetJson,
                contentKey, outcome, issue);
    }

    private long count(ImportExecutionContext context, ImportRecordKind kind) {
        return itemRepository.countHistoryUnits(context.jobId(), context.ownerId(), context.planGeneration(), kind,
                List.of("MATCHED", "RESOLVED"), ImportDecision.SKIP_ITEM);
    }

    private void renewLease(ImportExecutionContext context) {
        if (!unitTransaction.renewHistoryNormalizationLease(context)) {
            throw new IllegalStateException("History normalization execution became stale");
        }
    }

    private ImportTarget readTarget(String json) {
        try {
            return objectMapper.treeToValue(objectMapper.readTree(json), ImportTarget.class);
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new IllegalStateException("History target is invalid", exception);
        }
    }

    private ContentType contentType(ImportTarget target) {
        return target instanceof ImportTarget.ContentTarget contentTarget ? contentTarget.content().type() : null;
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

    private ObjectNode readObject(String json) {
        JsonNode payload = readPayload(json);
        if (!payload.isObject()) throw new IllegalStateException("History payload is not an object");
        return (ObjectNode) payload.deepCopy();
    }

    private JsonNode readPayload(String json) {
        if (json == null || json.isBlank()) return objectMapper.createObjectNode();
        try {
            return objectMapper.readTree(json);
        } catch (JacksonException exception) {
            throw new IllegalStateException("History payload is invalid", exception);
        }
    }

    private LocalDate localDate(JsonNode value) {
        if (value == null || !value.isTextual()) return null;
        try {
            return LocalDate.parse(value.asString());
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("History payload could not be serialized", exception);
        }
    }

    private record EpisodeCoordinates(Integer seasonNumber, Integer episodeNumber) { }
    private record CurrentRatingSelection(ImportItem item, Integer score, String issue) { }
    private record LatestEvent(ImportItem event, boolean consumed, String issue) { }
    private record HistoryMoment(LocalDate date, Instant instant, boolean hasTime) implements Comparable<HistoryMoment> {
        @Override
        public int compareTo(HistoryMoment other) {
            if (hasTime && other.hasTime) return instant.compareTo(other.instant);
            int dateOrder = date.compareTo(other.date);
            if (dateOrder != 0) return dateOrder;
            if (hasTime != other.hasTime) return hasTime ? 1 : -1;
            return 0;
        }
    }
}
