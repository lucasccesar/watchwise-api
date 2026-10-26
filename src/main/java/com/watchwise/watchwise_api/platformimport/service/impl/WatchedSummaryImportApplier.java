package com.watchwise.watchwise_api.platformimport.service.impl;

import com.watchwise.watchwise_api.content.dto.ContentRefCreationDTO;
import com.watchwise.watchwise_api.content.dto.MediaReference;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.content.service.ContentService;
import com.watchwise.watchwise_api.calendar.entity.CalendarScheduleCompleteness;
import com.watchwise.watchwise_api.calendar.repository.CalendarScheduleCompletenessRepository;
import com.watchwise.watchwise_api.diaryentry.entity.ImportedWatchAssertion;
import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.diaryentry.repository.ImportedWatchAssertionRepository;
import com.watchwise.watchwise_api.diaryentry.dto.WatchEventCommand;
import com.watchwise.watchwise_api.diaryentry.service.DiaryWatchCommandService;
import com.watchwise.watchwise_api.diarycompletion.model.CompletionScope;
import com.watchwise.watchwise_api.diarycompletion.model.EpisodeCoordinate;
import com.watchwise.watchwise_api.contentreview.service.HistoricalViewingEvidence;
import com.watchwise.watchwise_api.platformimport.entity.ImportItem;
import com.watchwise.watchwise_api.platformimport.model.ImportRecordKind;
import com.watchwise.watchwise_api.platformimport.model.ImportResolutionOperation;
import com.watchwise.watchwise_api.platformimport.model.ConfirmedWatchScopeSnapshot;
import com.watchwise.watchwise_api.platformimport.model.ImportLinkState;
import com.watchwise.watchwise_api.platformimport.model.ImportSourceKey;
import com.watchwise.watchwise_api.platformimport.model.LinkReservation;
import com.watchwise.watchwise_api.platformimport.model.ImportTarget;
import com.watchwise.watchwise_api.platformimport.model.NormalizedImportRecord;
import com.watchwise.watchwise_api.platformimport.repository.ImportItemRepository;
import com.watchwise.watchwise_api.platformimport.service.ImportExecutionContext;
import com.watchwise.watchwise_api.platformimport.service.ImportLimits;
import com.watchwise.watchwise_api.platformimport.service.ImportLedger;
import com.watchwise.watchwise_api.platformimport.service.ImportUnitApplier;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class WatchedSummaryImportApplier implements ImportUnitApplier {
    private final ImportItemRepository itemRepository;
    private final ImportLedger ledger;
    private final WatchedSummaryReconciler reconciler;
    private final DiaryEntryRepository diaryEntryRepository;
    private final ImportedWatchAssertionRepository assertionRepository;
    private final ContentRepository contentRepository;
    private final ContentService contentService;
    private final UserRepository userRepository;
    private final CalendarScheduleCompletenessRepository scheduleCompletenessRepository;
    private final DiaryWatchCommandService diaryWatchCommandService;
    private final ImportLimits importLimits;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public WatchedSummaryImportApplier(
            ImportItemRepository itemRepository,
            ImportLedger ledger,
            WatchedSummaryReconciler reconciler,
            DiaryEntryRepository diaryEntryRepository,
            ImportedWatchAssertionRepository assertionRepository,
            ContentRepository contentRepository,
            ContentService contentService,
            UserRepository userRepository,
            CalendarScheduleCompletenessRepository scheduleCompletenessRepository,
            DiaryWatchCommandService diaryWatchCommandService,
            ImportLimits importLimits,
            ObjectMapper objectMapper,
            Clock clock) {
        this.itemRepository = itemRepository;
        this.ledger = ledger;
        this.reconciler = reconciler;
        this.diaryEntryRepository = diaryEntryRepository;
        this.assertionRepository = assertionRepository;
        this.contentRepository = contentRepository;
        this.contentService = contentService;
        this.userRepository = userRepository;
        this.scheduleCompletenessRepository = scheduleCompletenessRepository;
        this.diaryWatchCommandService = diaryWatchCommandService;
        this.importLimits = importLimits;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public boolean supports(ImportRecordKind kind) {
        return kind == ImportRecordKind.WATCHED_SUMMARY || kind == ImportRecordKind.WATCHED_MARKER;
    }

    @Override
    public void apply(ImportExecutionContext context, UUID itemId) {
        ImportItem item = itemRepository.findById(itemId)
                .filter(candidate -> context.jobId().equals(candidate.getJobId())
                        && context.planGeneration() == candidate.getPlanGeneration()
                        && (candidate.getNormalizedKind() == ImportRecordKind.WATCHED_SUMMARY
                        || candidate.getNormalizedKind() == ImportRecordKind.WATCHED_MARKER))
                .orElseThrow(() -> new IllegalArgumentException("Watched history does not belong to this import"));
        JsonNode sourcePayload = readPayload(item.getPayloadJson());
        if (item.getNormalizedKind() == ImportRecordKind.WATCHED_MARKER
                && ("AUXILIARY_EVIDENCE".equals(item.getOutcome())
                || "auxiliary_watch_marker".equals(item.getObjectKind())
                || sourcePayload.path("auxiliaryWatchEvidence").asBoolean(false))) {
            throw new IllegalStateException("Auxiliary watch evidence cannot be applied as user history");
        }
        ImportTarget target = readTarget(item.getResolvedTargetJson());
        if (!(target instanceof ImportTarget.ContentTarget contentTarget)) {
            throw new IllegalStateException("Watched history must resolve to a content target before applying");
        }

        MediaReference media = contentTarget.content();
        ensureContentReference(media);
        List<NormalizedImportRecord> events = detailEvents(context.ownerId(), media);
        NormalizedImportRecord summary = new NormalizedImportRecord(item.getId().toString(),
                ImportRecordKind.WATCHED_SUMMARY, sourceKey(item), null, target, sourcePayload,
                Math.toIntExact(item.getOrdinal()));
        WatchedSummaryReconciler.Decision decision = reconciler.reconcile(context, events, List.of(summary)).getFirst();
        if (decision.disposition() == WatchedSummaryReconciler.Disposition.COVERED_BY_DETAIL_HISTORY) {
            String reason = item.getNormalizedKind() == ImportRecordKind.WATCHED_MARKER
                    ? "DETAIL_HISTORY_COVERS_WATCH_MARKER" : "DETAIL_HISTORY_COVERS_SUMMARY";
            ledger.markSkipped(context.ownerId(), sourceKey(item), null, null, reason);
            item.setOutcome("SKIPPED");
            item.setIssue(reason);
            itemRepository.save(item);
            return;
        }
        if (decision.disposition() != WatchedSummaryReconciler.Disposition.STORE_UNKNOWN_ASSERTION
                || decision.assertion() == null) {
            throw new IllegalStateException("Watched history cannot be reconciled without a resolved target");
        }

        Content content = requireContent(media);
        User owner = userRepository.findById(context.ownerId()).orElseThrow();
        var assertionPlan = decision.assertion();
        ImportedWatchAssertion assertion = assertionRepository.saveAndFlush(ImportedWatchAssertion.builder()
                .user(owner).content(content).sourceNamespace(item.getSourceNamespace())
                .objectKind(item.getObjectKind()).sourceId(item.getSourceId())
                .declaredDate(assertionPlan.declaredDate()).declaredPlays(assertionPlan.declaredPlays())
                .coverage("UNKNOWN").createdAt(LocalDateTime.now(clock)).build());
        ledger.markApplied(context.ownerId(), sourceKey(item), "IMPORTED_WATCH_ASSERTION", assertion.getId());
    }

    public HistoricalViewingEvidence applyConfirmedScope(
            ImportExecutionContext context,
            ImportItem item,
            MediaReference media,
            ImportResolutionOperation.ConfirmWatchScope confirmation,
            ConfirmedWatchScopeSnapshot snapshot) {
        User owner = userRepository.findById(context.ownerId()).orElseThrow();
        Instant now = clock.instant();
        if (snapshot == null || !media.equals(snapshot.target())
                || !confirmation.scheduleVersion().equals(snapshot.scheduleVersion())
                || !snapshot.validUntil().isAfter(now)) {
            throw new WatchScopeConfirmationException(
                    "WATCH_SCOPE_SCHEDULE_CHANGED: Refresh the preview and confirm a current scope.");
        }
        verifyLocalScheduleRevision(media, owner, snapshot);
        CompletionScope scope = new CompletionScope(snapshot.target(), Set.copyOf(snapshot.episodes()),
                snapshot.releaseComplete());

        Set<EpisodeCoordinate> selected = selectedCoordinates(media, confirmation, scope);
        if (selected.isEmpty() || selected.size() > importLimits.maxRecords()
                || !scope.episodes().containsAll(selected)) {
            throw new WatchScopeConfirmationException("WATCH_SCOPE_EPISODES_UNKNOWN: Choose only episodes in the released schedule.");
        }

        String seriesId = media.type() == ContentType.SERIES ? media.tmdbId() : media.seriesTmdbId();
        Map<EpisodeCoordinate, Content> episodeContent = new LinkedHashMap<>();
        for (EpisodeCoordinate coordinate : selected.stream()
                .sorted(Comparator.comparingInt(EpisodeCoordinate::seasonNumber)
                        .thenComparingInt(EpisodeCoordinate::episodeNumber)).toList()) {
            MediaReference episode = MediaReference.episode(seriesId,
                    coordinate.seasonNumber(), coordinate.episodeNumber());
            contentService.getOrCreateReferenceFromVerifiedImport(new ContentRefCreationDTO(null, ContentType.EPISODE,
                    seriesId, coordinate.seasonNumber(), coordinate.episodeNumber(), null, null));
            Content content = contentRepository.findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType(
                            seriesId, coordinate.seasonNumber(), coordinate.episodeNumber(), ContentType.EPISODE)
                    .orElseThrow(() -> new IllegalStateException("Confirmed episode reference is missing"));
            if (content.getType() != ContentType.EPISODE
                    || !seriesId.equals(content.getSeriesTmdbId())
                    || !Integer.valueOf(coordinate.seasonNumber()).equals(content.getSeasonNumber())
                    || !Integer.valueOf(coordinate.episodeNumber()).equals(content.getEpisodeNumber())) {
                throw new WatchScopeConfirmationException("WATCH_SCOPE_EPISODES_UNKNOWN: Episode coordinates do not match the series.");
            }
            episodeContent.put(coordinate, content);
        }

        int watchNumber = diaryEntryRepository.findEpisodeCompletionWatches(context.ownerId(), List.of(seriesId)).stream()
                .filter(watch -> selected.contains(new EpisodeCoordinate(watch.getSeasonNumber(), watch.getEpisodeNumber())))
                .mapToInt(DiaryEntryRepository.EpisodeCompletionWatch::getWatchNumber).max().orElse(0);
        if (watchNumber == 0) {
            watchNumber = episodeContent.values().stream()
                    .mapToInt(content -> diaryEntryRepository.findMaxWatchNumber(context.ownerId(), content.getId()))
                    .max().orElse(0);
        }
        if (watchNumber == Integer.MAX_VALUE) {
            throw new WatchScopeConfirmationException("WATCH_SCOPE_PASS_EXHAUSTED: No additional viewing pass is available.");
        }
        if (watchNumber == 0) watchNumber = 1;
        int confirmedWatchNumber = watchNumber;
        Set<UUID> eventIds = new HashSet<>();

        for (Map.Entry<EpisodeCoordinate, Content> entry : episodeContent.entrySet()) {
            EpisodeCoordinate coordinate = entry.getKey();
            Content content = entry.getValue();
            ImportSourceKey childKey = confirmedEpisodeKey(item, coordinate);
            LinkReservation reservation = ledger.reserve(context.ownerId(), context.jobId(), childKey,
                    ImportEventDeduplicator.payloadHash(coordinate.seasonNumber() + ":" + coordinate.episodeNumber()
                            + ":" + snapshot.scheduleVersion()));
            if (reservation.state() == ImportLinkState.APPLIED) {
                diaryEntryRepository.findFirstByUserIdAndContentIdAndWatchNumber(
                                context.ownerId(), content.getId(), confirmedWatchNumber)
                        .map(DiaryEntry::getId).ifPresent(eventIds::add);
                continue;
            }
            if (reservation.state() == ImportLinkState.SKIPPED || reservation.state() == ImportLinkState.TOMBSTONE) {
                throw new WatchScopeConfirmationException(
                        "WATCH_SCOPE_EPISODE_ALREADY_REMOVED: A previously confirmed episode was removed; confirm a new scope.");
            }
            if (!context.jobId().equals(reservation.owningJobId())) {
                throw new ImportUnitTransaction.WaitForOtherJobException();
            }

            var existing = diaryEntryRepository.findFirstByUserIdAndContentIdAndWatchNumber(
                    context.ownerId(), content.getId(), confirmedWatchNumber);
            if (existing.isPresent()) {
                ledger.markApplied(context.ownerId(), childKey, "DIARY_ENTRY", existing.get().getId());
                eventIds.add(existing.get().getId());
                continue;
            }

            var created = diaryWatchCommandService.recordPreparedImport(context.ownerId(), new WatchEventCommand(
                    MediaReference.episode(seriesId, coordinate.seasonNumber(), coordinate.episodeNumber()),
                    null, confirmedWatchNumber, null, null, null, List.of(), null), false);
            UUID diaryEntryId = created.map(result -> result.diaryEntryId()).orElseGet(() ->
                    diaryEntryRepository.findFirstByUserIdAndContentIdAndWatchNumber(
                            context.ownerId(), content.getId(), confirmedWatchNumber)
                            .map(diaryEntry -> diaryEntry.getId())
                            .orElseThrow(() -> new IllegalStateException("Confirmed episode was not recorded")));
            ledger.markApplied(context.ownerId(), childKey, "DIARY_ENTRY", diaryEntryId);
            eventIds.add(diaryEntryId);
        }
        return new HistoricalViewingEvidence(media, confirmedWatchNumber, snapshot.scheduleVersion(), eventIds);
    }

    public HistoricalViewingEvidence applyConfirmedScope(ImportExecutionContext context, UUID itemId) {
        ImportItem item = itemRepository.findById(itemId).orElseThrow();
        ImportResolutionOperation.ConfirmWatchScope confirmation = confirmedScope(item);
        if (confirmation == null) {
            throw new WatchScopeConfirmationException("WATCH_SCOPE_INVALID: Confirmed scope is missing.");
        }
        ImportTarget target = readTarget(item.getResolvedTargetJson());
        if (!(target instanceof ImportTarget.ContentTarget contentTarget)) {
            throw new WatchScopeConfirmationException("WATCH_SCOPE_TARGET_INVALID: Resolve the series or season first.");
        }
        return applyConfirmedScope(context, item, contentTarget.content(), confirmation,
                confirmedScopeSnapshot(item));
    }

    private void verifyLocalScheduleRevision(MediaReference media, User owner,
                                             ConfirmedWatchScopeSnapshot snapshot) {
        var marker = media.type() == ContentType.SERIES
                ? scheduleCompletenessRepository.findSeriesIdentity(media.tmdbId(), owner.getPreferredRegion(),
                        owner.getPreferredLanguage())
                : scheduleCompletenessRepository.findSeasonIdentity(media.seriesTmdbId(), media.seasonNumber(),
                        owner.getPreferredRegion(), owner.getPreferredLanguage());
        if (marker.isPresent() != snapshot.localCalendarMarkerPresent()
                || marker.map(CalendarScheduleCompleteness::getScopeVersion)
                .orElse(0L) != snapshot.localCalendarScopeVersion()) {
            throw new WatchScopeConfirmationException(
                    "WATCH_SCOPE_SCHEDULE_CHANGED: The local calendar schedule changed after confirmation; refresh and confirm again.");
        }
    }

    public boolean isPreparedScopeCurrent(UUID ownerId, MediaReference media,
                                          ConfirmedWatchScopeSnapshot snapshot) {
        if (snapshot == null || !media.equals(snapshot.target()) || !snapshot.validUntil().isAfter(clock.instant())) {
            return false;
        }
        User owner = userRepository.findById(ownerId).orElseThrow();
        var marker = media.type() == ContentType.SERIES
                ? scheduleCompletenessRepository.findSeriesIdentity(media.tmdbId(), owner.getPreferredRegion(),
                        owner.getPreferredLanguage())
                : scheduleCompletenessRepository.findSeasonIdentity(media.seriesTmdbId(), media.seasonNumber(),
                        owner.getPreferredRegion(), owner.getPreferredLanguage());
        return marker.isPresent() == snapshot.localCalendarMarkerPresent()
                && marker.map(CalendarScheduleCompleteness::getScopeVersion)
                .orElse(0L) == snapshot.localCalendarScopeVersion();
    }

    private Set<EpisodeCoordinate> selectedCoordinates(MediaReference media,
                                                       ImportResolutionOperation.ConfirmWatchScope confirmation,
                                                       CompletionScope scope) {
        String seriesId = media.type() == ContentType.SERIES ? media.tmdbId() : media.seriesTmdbId();
        Set<EpisodeCoordinate> selected = new HashSet<>();
        for (MediaReference episode : confirmation.episodeReferences()) {
            if (episode.type() != ContentType.EPISODE || !seriesId.equals(episode.seriesTmdbId())
                    || (media.type() == ContentType.SEASON
                    && !media.seasonNumber().equals(episode.seasonNumber()))) {
                throw new WatchScopeConfirmationException("WATCH_SCOPE_EPISODES_UNKNOWN: Episode coordinates do not belong to the confirmed scope.");
            }
            selected.add(new EpisodeCoordinate(episode.seasonNumber(), episode.episodeNumber()));
        }
        if (confirmation.scope() == ImportResolutionOperation.WatchScope.ALL_RELEASED_EPISODES
                && !selected.equals(scope.episodes())) {
            throw new WatchScopeConfirmationException("WATCH_SCOPE_SCHEDULE_CHANGED: The previewed episode set changed; confirm the updated set.");
        }
        if (confirmation.scope() == ImportResolutionOperation.WatchScope.SPECIFIC_EPISODES
                && !scope.episodes().containsAll(selected)) {
            throw new WatchScopeConfirmationException("WATCH_SCOPE_EPISODES_UNKNOWN: Choose only episodes in the released schedule.");
        }
        return Set.copyOf(selected);
    }

    public ImportResolutionOperation.ConfirmWatchScope confirmedScope(ImportItem item) {
        JsonNode value = readPayload(item.getPayloadJson()).path("resolution").path("confirmWatchScope");
        if (!value.isObject()) return null;
        try {
            ImportResolutionOperation.WatchScope scope = ImportResolutionOperation.WatchScope.valueOf(
                    value.path("scope").asString());
            String scheduleVersion = value.path("scheduleVersion").asString();
            List<MediaReference> episodes = new ArrayList<>();
            JsonNode references = value.path("episodeReferences");
            if (!references.isArray()) throw new IllegalStateException("Confirmed episode list is invalid");
            for (JsonNode reference : references) episodes.add(objectMapper.treeToValue(reference, MediaReference.class));
            return new ImportResolutionOperation.ConfirmWatchScope(item.getId(), scope, scheduleVersion, episodes);
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new WatchScopeConfirmationException("WATCH_SCOPE_INVALID: The confirmed scope is invalid.");
        }
    }

    public ConfirmedWatchScopeSnapshot confirmedScopeSnapshot(ImportItem item) {
        JsonNode value = readPayload(item.getPayloadJson()).path("resolution").path("confirmedWatchScopeSnapshot");
        if (!value.isObject()) return null;
        try {
            return objectMapper.treeToValue(value, ConfirmedWatchScopeSnapshot.class);
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new WatchScopeConfirmationException("WATCH_SCOPE_SNAPSHOT_INVALID: The prepared schedule snapshot is invalid.");
        }
    }

    public ConfirmedWatchScopeSnapshot preparedScopeSnapshot(ImportItem item) {
        ConfirmedWatchScopeSnapshot confirmed = confirmedScopeSnapshot(item);
        if (confirmed != null) return confirmed;
        JsonNode value = readPayload(item.getPayloadJson()).path("resolution").path("watchScopeSnapshot");
        if (!value.isObject()) return null;
        try {
            return objectMapper.treeToValue(value, ConfirmedWatchScopeSnapshot.class);
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new WatchScopeConfirmationException("WATCH_SCOPE_SNAPSHOT_INVALID: The prepared schedule snapshot is invalid.");
        }
    }

    private ImportSourceKey confirmedEpisodeKey(ImportItem parent, EpisodeCoordinate coordinate) {
        String child = parent.getSourceNamespace() + ":" + parent.getObjectKind() + ":" + parent.getSourceId()
                + ":" + coordinate.seasonNumber() + ":" + coordinate.episodeNumber();
        String sourceId = UUID.nameUUIDFromBytes(child.getBytes(StandardCharsets.UTF_8)).toString();
        return new ImportSourceKey(parent.getSourceNamespace(), "confirmed_episode", sourceId);
    }

    private List<NormalizedImportRecord> detailEvents(UUID ownerId, MediaReference summary) {
        List<NormalizedImportRecord> events = new ArrayList<>();
        if (summary.type() == ContentType.MOVIE) {
            Content movie = contentRepository.findByTmdbIdAndType(summary.tmdbId(), ContentType.MOVIE).orElseThrow();
            if (!diaryEntryRepository.findByUserIdAndContentIdAndWatchNumberGreaterThan(
                    ownerId, movie.getId(), 0).isEmpty()) {
                events.add(event("movie:" + movie.getId(), MediaReference.movie(summary.tmdbId())));
            }
            return events;
        }

        String seriesId = summary.type() == ContentType.SERIES ? summary.tmdbId() : summary.seriesTmdbId();
        diaryEntryRepository.findEpisodeCompletionWatches(ownerId, List.of(seriesId)).stream()
                .filter(watch -> summary.type() == ContentType.SERIES
                        || (summary.seasonNumber().equals(watch.getSeasonNumber())
                        && (summary.type() != ContentType.EPISODE
                        || summary.episodeNumber().equals(watch.getEpisodeNumber()))))
                .forEach(watch -> events.add(event("episode:" + watch.getDiaryEntryId(),
                        MediaReference.episode(watch.getSeriesTmdbId(), watch.getSeasonNumber(), watch.getEpisodeNumber()))));
        return events;
    }

    private NormalizedImportRecord event(String itemId, MediaReference media) {
        return new NormalizedImportRecord(itemId, ImportRecordKind.DIARY_EVENT,
                new ImportSourceKey("watchwise-existing", "diary_event", itemId), null,
                new ImportTarget.ContentTarget(media), objectMapper.createObjectNode(), 0);
    }

    private void ensureContentReference(MediaReference media) {
        ContentRefCreationDTO reference = switch (media.type()) {
            case MOVIE -> new ContentRefCreationDTO(media.tmdbId(), ContentType.MOVIE, null, null,
                    null, null, null);
            case SERIES -> new ContentRefCreationDTO(media.tmdbId(), ContentType.SERIES, null, null,
                    null, null, null);
            case SEASON -> new ContentRefCreationDTO(null, ContentType.SEASON, media.seriesTmdbId(),
                    media.seasonNumber(), null, null, null);
            case EPISODE -> new ContentRefCreationDTO(null, ContentType.EPISODE, media.seriesTmdbId(),
                    media.seasonNumber(), media.episodeNumber(), null, null);
        };
        contentService.getOrCreateReferenceFromVerifiedImport(reference);
    }

    private Content requireContent(MediaReference media) {
        return switch (media.type()) {
            case MOVIE, SERIES -> contentRepository.findByTmdbIdAndType(media.tmdbId(), media.type())
                    .orElseThrow(() -> new IllegalStateException("Summary content reference is missing"));
            case SEASON -> contentRepository.findByTypeAndSeriesTmdbIdAndSeasonNumberIn(
                            ContentType.SEASON, media.seriesTmdbId(), List.of(media.seasonNumber()))
                    .stream().findFirst()
                    .orElseThrow(() -> new IllegalStateException("Summary season reference is missing"));
            case EPISODE -> contentRepository.findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType(
                            media.seriesTmdbId(), media.seasonNumber(), media.episodeNumber(), ContentType.EPISODE)
                    .orElseThrow(() -> new IllegalStateException("Summary episode reference is missing"));
        };
    }

    private ImportTarget readTarget(String json) {
        try {
            return objectMapper.treeToValue(objectMapper.readTree(json), ImportTarget.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Resolved watched-summary target is invalid", exception);
        }
    }

    private JsonNode readPayload(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Watched-summary payload is invalid", exception);
        }
    }

    private ImportSourceKey sourceKey(ImportItem item) {
        return new ImportSourceKey(item.getSourceNamespace(), item.getObjectKind(), item.getSourceId());
    }

    public static final class WatchScopeConfirmationException extends RuntimeException {
        public WatchScopeConfirmationException(String message) { super(message); }
    }
}
