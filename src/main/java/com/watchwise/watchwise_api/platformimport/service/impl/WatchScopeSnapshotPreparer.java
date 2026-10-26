package com.watchwise.watchwise_api.platformimport.service.impl;

import com.watchwise.watchwise_api.calendar.entity.CalendarScheduleCompleteness;
import com.watchwise.watchwise_api.calendar.repository.CalendarScheduleCompletenessRepository;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.exception.TmdbUnavailableException;
import com.watchwise.watchwise_api.content.dto.MediaReference;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.diarycompletion.model.EpisodeCoordinate;
import com.watchwise.watchwise_api.diarycompletion.model.VersionedCompletionScope;
import com.watchwise.watchwise_api.diarycompletion.service.CompletionScopeLoader;
import com.watchwise.watchwise_api.platformimport.dto.ImportWatchScopePreviewDTO;
import com.watchwise.watchwise_api.platformimport.entity.ImportItem;
import com.watchwise.watchwise_api.platformimport.model.ConfirmedWatchScopeSnapshot;
import com.watchwise.watchwise_api.platformimport.model.ImportDecision;
import com.watchwise.watchwise_api.platformimport.model.ImportRecordKind;
import com.watchwise.watchwise_api.platformimport.model.ImportResolutionOperation;
import com.watchwise.watchwise_api.platformimport.model.ImportTarget;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Component
public class WatchScopeSnapshotPreparer {
    private static final int OVERLAY_SCHEMA_VERSION = 1;

    private final CompletionScopeLoader completionScopeLoader;
    private final CalendarScheduleCompletenessRepository scheduleCompletenessRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public WatchScopeSnapshotPreparer(
            CompletionScopeLoader completionScopeLoader,
            CalendarScheduleCompletenessRepository scheduleCompletenessRepository,
            UserRepository userRepository,
            ObjectMapper objectMapper,
            Clock clock) {
        this.completionScopeLoader = completionScopeLoader;
        this.scheduleCompletenessRepository = scheduleCompletenessRepository;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public Prepared prepare(UUID ownerId, ImportItem item, Map<MediaReference, LoadedScope> cache) {
        if (!supports(item) || item.getDecision() == ImportDecision.SKIP_ITEM
                || !List.of("MATCHED", "RESOLVED").contains(item.getOutcome())) return null;
        MediaReference target = target(item.getResolvedTargetJson());
        if (target == null) return null;
        LoadedScope loaded = cache.get(target);
        if (loaded == null) {
            User owner = userRepository.findById(ownerId).orElseThrow();
            loaded = load(target, owner);
            cache.put(target, loaded);
        }
        JsonNode original = read(item.getPayloadJson());
        ObjectNode envelope = envelope(original);
        ObjectNode resolution = (ObjectNode) envelope.get("resolution");
        ImportResolutionOperation.ConfirmWatchScope confirmation = confirmation(resolution);
        ConfirmedWatchScopeSnapshot previouslyConfirmed = confirmedSnapshot(resolution);
        if (loaded.snapshot() == null) {
            resolution.set("watchScopePreview", objectMapper.valueToTree(
                    ImportWatchScopePreviewDTO.unavailable(loaded.issue())));
            boolean needsConfirmation = confirmation != null;
            return new Prepared(item.getId(), write(envelope),
                    needsConfirmation ? "NORMALIZATION_ISSUE" : item.getOutcome(),
                    needsConfirmation ? loaded.issue() : item.getIssue(), needsConfirmation);
        }

        ConfirmedWatchScopeSnapshot snapshot = loaded.snapshot();
        List<EpisodeCoordinate> episodes = snapshot.episodes().stream()
                .sorted(Comparator.comparingInt(EpisodeCoordinate::seasonNumber)
                        .thenComparingInt(EpisodeCoordinate::episodeNumber)).toList();
        resolution.set("watchScopePreview", objectMapper.valueToTree(new ImportWatchScopePreviewDTO(
                true, null, snapshot.scheduleVersion(), snapshot.validUntil(), snapshot.releaseComplete(), episodes)));
        resolution.set("watchScopeSnapshot", objectMapper.valueToTree(snapshot));

        String issue = confirmation == null ? null : confirmationIssue(target, confirmation, snapshot);
        if (issue == null && previouslyConfirmed != null && !sameSchedule(previouslyConfirmed, snapshot)) {
            issue = "WATCH_SCOPE_SCHEDULE_CHANGED: The schedule changed after confirmation; refresh and confirm again.";
        }
        if (confirmation != null && issue == null) {
            resolution.set("confirmedWatchScopeSnapshot", objectMapper.valueToTree(snapshot));
        }
        boolean waiting = issue != null;
        String outcome = waiting ? "NORMALIZATION_ISSUE" : item.getOutcome();
        if (!waiting && "NORMALIZATION_ISSUE".equals(item.getOutcome())
                && item.getIssue() != null && item.getIssue().startsWith("WATCH_SCOPE_")) {
            outcome = "RESOLVED";
        }
        return new Prepared(item.getId(), write(envelope), outcome,
                waiting ? issue : null, waiting);
    }

    private LoadedScope load(MediaReference target, User owner) {
        Instant now = clock.instant();
        try {
            VersionedCompletionScope loaded = completionScopeLoader.load(target, owner.getPreferredRegion(),
                    owner.getPreferredLanguage(), now);
            if (!target.equals(loaded.scope().reference()) || !loaded.validUntil().isAfter(now)) {
                return new LoadedScope(null, "WATCH_SCOPE_SCHEDULE_UNAVAILABLE");
            }
            Optional<CalendarScheduleCompleteness> marker = localMarker(
                    target, owner.getPreferredRegion(), owner.getPreferredLanguage());
            ConfirmedWatchScopeSnapshot snapshot = new ConfirmedWatchScopeSnapshot(target,
                    loaded.scheduleVersion(), loaded.validUntil(), loaded.scope().releaseComplete(),
                    loaded.scope().episodes().stream().sorted(Comparator
                            .comparingInt(EpisodeCoordinate::seasonNumber)
                            .thenComparingInt(EpisodeCoordinate::episodeNumber)).toList(),
                    marker.isPresent(), marker.map(CalendarScheduleCompleteness::getScopeVersion).orElse(0L));
            return new LoadedScope(snapshot, null);
        } catch (NotFoundException | TmdbUnavailableException unavailable) {
            return new LoadedScope(null, "WATCH_SCOPE_SCHEDULE_UNAVAILABLE");
        }
    }

    private String confirmationIssue(MediaReference target, ImportResolutionOperation.ConfirmWatchScope confirmation,
                                     ConfirmedWatchScopeSnapshot snapshot) {
        if (!target.equals(snapshot.target()) || !confirmation.scheduleVersion().equals(snapshot.scheduleVersion())) {
            return "WATCH_SCOPE_SCHEDULE_CHANGED: Refresh the preview and confirm the current episode set.";
        }
        Set<EpisodeCoordinate> selected = new LinkedHashSet<>();
        String seriesId = target.type() == ContentType.SERIES ? target.tmdbId() : target.seriesTmdbId();
        for (MediaReference episode : confirmation.episodeReferences()) {
            if (episode.type() != ContentType.EPISODE || !seriesId.equals(episode.seriesTmdbId())
                    || (target.type() == ContentType.SEASON
                    && !target.seasonNumber().equals(episode.seasonNumber()))) {
                return "WATCH_SCOPE_EPISODES_UNKNOWN: Only episode references can be confirmed.";
            }
            selected.add(new EpisodeCoordinate(episode.seasonNumber(), episode.episodeNumber()));
        }
        if (selected.size() != confirmation.episodeReferences().size()) {
            return "WATCH_SCOPE_EPISODES_UNKNOWN: Duplicate episode references cannot be confirmed.";
        }
        Set<EpisodeCoordinate> released = Set.copyOf(snapshot.episodes());
        if (confirmation.scope() == ImportResolutionOperation.WatchScope.ALL_RELEASED_EPISODES
                ? !selected.equals(released) : !released.containsAll(selected)) {
            return "WATCH_SCOPE_SCHEDULE_CHANGED: The previewed episode set changed; confirm the updated set.";
        }
        return null;
    }

    private ImportResolutionOperation.ConfirmWatchScope confirmation(ObjectNode resolution) {
        JsonNode value = resolution.path("confirmWatchScope");
        if (!value.isObject()) return null;
        try {
            return objectMapper.treeToValue(value, ImportResolutionOperation.ConfirmWatchScope.class);
        } catch (JacksonException | IllegalArgumentException exception) {
            return null;
        }
    }

    private ConfirmedWatchScopeSnapshot confirmedSnapshot(ObjectNode resolution) {
        JsonNode value = resolution.path("confirmedWatchScopeSnapshot");
        if (!value.isObject()) return null;
        try {
            return objectMapper.treeToValue(value, ConfirmedWatchScopeSnapshot.class);
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new IllegalStateException("Stored confirmed watch scope snapshot is invalid", exception);
        }
    }

    private boolean sameSchedule(ConfirmedWatchScopeSnapshot confirmed, ConfirmedWatchScopeSnapshot current) {
        return confirmed.target().equals(current.target())
                && confirmed.scheduleVersion().equals(current.scheduleVersion())
                && confirmed.releaseComplete() == current.releaseComplete()
                && Set.copyOf(confirmed.episodes()).equals(Set.copyOf(current.episodes()))
                && confirmed.localCalendarMarkerPresent() == current.localCalendarMarkerPresent()
                && confirmed.localCalendarScopeVersion() == current.localCalendarScopeVersion();
    }

    private boolean supports(ImportItem item) {
        return item.getNormalizedKind() == ImportRecordKind.WATCHED_SUMMARY
                || item.getNormalizedKind() == ImportRecordKind.WATCHED_MARKER
                || item.getNormalizedKind() == ImportRecordKind.REVIEW
                || item.getNormalizedKind() == ImportRecordKind.CURRENT_RATING;
    }

    private MediaReference target(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            ImportTarget resolved = objectMapper.treeToValue(objectMapper.readTree(json), ImportTarget.class);
            if (!(resolved instanceof ImportTarget.ContentTarget contentTarget)) return null;
            MediaReference reference = contentTarget.content();
            return reference.type() == ContentType.SERIES || reference.type() == ContentType.SEASON
                    ? reference : null;
        } catch (JacksonException | IllegalArgumentException exception) {
            return null;
        }
    }

    private Optional<CalendarScheduleCompleteness> localMarker(MediaReference target, String region,
                                                               String language) {
        return target.type() == ContentType.SERIES
                ? scheduleCompletenessRepository.findSeriesIdentity(target.tmdbId(), region, language)
                : scheduleCompletenessRepository.findSeasonIdentity(
                        target.seriesTmdbId(), target.seasonNumber(), region, language);
    }

    private ObjectNode envelope(JsonNode payload) {
        if (payload.isObject() && payload.path("_watchwiseResolution").asInt(-1) == OVERLAY_SCHEMA_VERSION
                && payload.path("source").isObject() && payload.path("resolution").isObject()) {
            return (ObjectNode) payload.deepCopy();
        }
        ObjectNode result = objectMapper.createObjectNode().put("_watchwiseResolution", OVERLAY_SCHEMA_VERSION);
        result.set("source", payload.deepCopy());
        ObjectNode resolution = result.putObject("resolution");
        resolution.set("fields", objectMapper.createObjectNode());
        return result;
    }

    private JsonNode read(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Watch scope preparation payload is invalid", exception);
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Watch scope preparation could not be serialized", exception);
        }
    }

    public record Prepared(UUID itemId, String payloadJson, String outcome, String issue, boolean waitingForUser) { }

    public record LoadedScope(ConfirmedWatchScopeSnapshot snapshot, String issue) { }
}
