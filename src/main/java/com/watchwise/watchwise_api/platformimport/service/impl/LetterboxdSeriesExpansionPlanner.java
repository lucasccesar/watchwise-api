package com.watchwise.watchwise_api.platformimport.service.impl;

import com.watchwise.watchwise_api.calendar.service.CalendarEpisodeSchedule;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleLookup;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleProvider;
import com.watchwise.watchwise_api.calendar.service.CalendarSeriesSchedule;
import com.watchwise.watchwise_api.content.dto.ContentRefCreationDTO;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.dto.MediaReference;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.service.ContentService;
import com.watchwise.watchwise_api.diaryentry.dto.WatchEventCommand;
import com.watchwise.watchwise_api.platformimport.entity.ImportItem;
import com.watchwise.watchwise_api.platformimport.model.ImportTarget;
import com.watchwise.watchwise_api.platformimport.model.SeriesExpansionPlan;
import com.watchwise.watchwise_api.platformimport.repository.ImportItemRepository;
import com.watchwise.watchwise_api.platformimport.service.ImportLimits;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class LetterboxdSeriesExpansionPlanner {
    private static final int PASS_PLAN_BATCH_SIZE = 200;

    private final CalendarScheduleProvider calendarScheduleProvider;
    private final UserRepository userRepository;
    private final ContentService contentService;
    private final ImportPassPlanner passPlanner;
    private final ObjectMapper objectMapper;
    private final TheaterTagMatcher theaterTagMatcher;
    private final ImportItemRepository importItemRepository;
    private final ImportLimits importLimits;

    public LetterboxdSeriesExpansionPlanner(CalendarScheduleProvider calendarScheduleProvider,
                                            UserRepository userRepository, ContentService contentService,
                                            ImportPassPlanner passPlanner, ObjectMapper objectMapper,
                                            TheaterTagMatcher theaterTagMatcher,
                                            ImportItemRepository importItemRepository, ImportLimits importLimits) {
        this.calendarScheduleProvider = calendarScheduleProvider;
        this.userRepository = userRepository;
        this.contentService = contentService;
        this.passPlanner = passPlanner;
        this.objectMapper = objectMapper;
        this.theaterTagMatcher = theaterTagMatcher;
        this.importItemRepository = importItemRepository;
        this.importLimits = importLimits;
    }

    public SeriesExpansionPlan plan(UUID owner, ImportItem sourceRecord, Instant now) {
        if (owner == null || sourceRecord == null || now == null) {
            throw new IllegalArgumentException("Series expansion planning inputs are required");
        }
        try {
            JsonNode payload = objectMapper.readTree(sourceRecord.getPayloadJson());
            JsonNode source = payload.path("source").isObject() ? payload.path("source") : payload;
            ImportTarget target = objectMapper.readValue(sourceRecord.getResolvedTargetJson(), ImportTarget.class);
            if (!(target instanceof ImportTarget.ContentTarget contentTarget)) {
                throw issue("SERIES_EXPANSION_TARGET_INVALID", "Series expansion requires a resolved content target.");
            }
            MediaReference media = contentTarget.content();
            LocalDate editedDate = date(payload.path("resolution").path("fields"),
                    "WATCHED_DATE", "watchedOn", "watchedDate");
            LocalDate sourceDate = editedDate == null ? date(source, "watchedOn", "watchedDate") : editedDate;
            List<Child> children;
            String scopeHash;
            if (media.type() == ContentType.SERIES) {
                SeriesSelection selection = seriesChildren(owner, media.tmdbId(), sourceDate, now);
                children = selection.children();
                scopeHash = selection.scopeHash();
            } else if (media.type() == ContentType.EPISODE || media.type() == ContentType.MOVIE) {
                children = List.of(new Child(media, sourceDate,
                        media.type() == ContentType.MOVIE && theaterTagMatcher.matches(tags(source)) ? Boolean.TRUE : null,
                        media.type() == ContentType.EPISODE ? LocalDate.MIN : null));
                scopeHash = sha256(key(media));
            } else {
                throw issue("SERIES_EXPANSION_TARGET_INVALID", "Only movies, series, and episode leaves can be expanded.");
            }
            if (children.isEmpty()) {
                throw issue("SERIES_EXPANSION_EMPTY", "No released regular episodes were available in the saved calendar.");
            }
            long predictedRecordCount = importItemRepository.countByJobIdAndPlanGeneration(
                    sourceRecord.getJobId(), sourceRecord.getPlanGeneration()) + children.size();
            if (predictedRecordCount > importLimits.maxRecords()) {
                throw issue("IMPORT_VOLUME_LIMIT", "Expansion predicts " + predictedRecordCount
                        + " total records, exceeding the import limit of " + importLimits.maxRecords() + ".");
            }
            return assignPasses(owner, sourceRecord, children, scopeHash, now);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Series expansion source record is invalid", exception);
        }
    }

    private SeriesSelection seriesChildren(UUID owner, String seriesId, LocalDate sourceDate, Instant now) {
        User user = userRepository.findById(owner)
                .orElseThrow(() -> issue("SERIES_EXPANSION_OWNER_MISSING", "Import owner could not be loaded."));
        CalendarScheduleLookup lookup = calendarScheduleProvider.loadSeries(
                seriesId, user.getPreferredRegion(), user.getPreferredLanguage());
        if (!(lookup instanceof CalendarScheduleLookup.FoundSeries found)) {
            throw issue("SERIES_CALENDAR_UNAVAILABLE",
                    "The saved series calendar is unavailable; refresh calendar data and retry the import.");
        }
        if (!found.schedule().completeSchedule()) {
            throw issue("SERIES_CALENDAR_INCOMPLETE",
                    "The saved series calendar is incomplete; refresh it until a complete schedule is available.");
        }
        LocalDate importDate = now.atZone(ZoneOffset.UTC).toLocalDate();
        List<Child> children = new ArrayList<>();
        found.schedule().seasons().stream()
                .filter(season -> season.schedule().seasonNumber() != null && season.schedule().seasonNumber() > 0)
                .sorted(Comparator.comparing(season -> season.schedule().seasonNumber()))
                .forEach(season -> season.schedule().episodes().stream()
                        .filter(episode -> episode.episodeNumber() != null && episode.episodeNumber() > 0)
                        .filter(episode -> episode.releaseDate() != null && !episode.releaseDate().isAfter(importDate))
                        .sorted(Comparator.comparing(CalendarEpisodeSchedule::episodeNumber))
                        .forEach(episode -> children.add(new Child(
                                MediaReference.episode(seriesId, season.schedule().seasonNumber(), episode.episodeNumber()),
                                compatibleDate(sourceDate, episode.releaseDate()), null, episode.releaseDate()))));
        if (children.isEmpty()) {
            throw issue("SERIES_EXPANSION_EMPTY", "The saved calendar has no released regular episodes to import.");
        }
        return new SeriesSelection(List.copyOf(children), scheduleVersion(found.schedule(), user));
    }

    private SeriesExpansionPlan assignPasses(UUID owner, ImportItem sourceRecord, List<Child> children,
                                             String scopeHash, Instant now) {
        List<PreparedChild> prepared = children.stream().map(child -> {
            ContentRefDTO content = contentService.getOrCreateReference(toCreation(child.content()));
            UUID childId = UUID.nameUUIDFromBytes((sourceRecord.getSourceId() + ":" + key(child.content()))
                    .getBytes(StandardCharsets.UTF_8));
            return new PreparedChild(child, content.id(), childId);
        }).toList();

        List<Integer> passNumbers = new ArrayList<>(prepared.size());
        Map<UUID, Integer> plannedPassByChild = new java.util.HashMap<>();
        java.util.Set<UUID> requiresPassChoice = new java.util.HashSet<>();
        Map<Integer, String> childPassIssues = new java.util.LinkedHashMap<>();
        for (int start = 0; start < prepared.size(); start += PASS_PLAN_BATCH_SIZE) {
            List<ImportPassPlanner.WatchEvent> batch = prepared.subList(start,
                            Math.min(start + PASS_PLAN_BATCH_SIZE, prepared.size())).stream()
                    .map(child -> new ImportPassPlanner.WatchEvent(child.childId(), child.contentId(),
                            sourceRecord.getSourceNamespace(), child.child().watchedDate(), null, false))
                    .toList();
            ImportPassPlanner.Plan plan = passPlanner.plan(owner, batch);
            for (int offset = 0; offset < batch.size(); offset++) {
                ImportPassPlanner.WatchEvent event = batch.get(offset);
                if (plan.issues().containsKey(event.itemId())) {
                    requiresPassChoice.add(event.itemId());
                    childPassIssues.put(start + offset, plan.issues().get(event.itemId()));
                    passNumbers.addAll(plan.matchingPasses().getOrDefault(event.itemId(), List.of()));
                    continue;
                }
                Integer pass = plan.watchNumbers().get(event.itemId());
                if (pass == null) {
                    throw new IllegalStateException("Pass planning omitted an episode without an issue");
                }
                plannedPassByChild.put(event.itemId(), pass);
                passNumbers.add(pass);
            }
        }
        int targetPass = passNumbers.stream().mapToInt(Integer::intValue).max().orElse(1);
        List<WatchEventCommand> commands = new ArrayList<>(prepared.size());
        for (int index = 0; index < prepared.size(); index++) {
            PreparedChild child = prepared.get(index);
            Integer proposedPass = requiresPassChoice.contains(child.childId())
                    ? null : plannedPassByChild.get(child.childId());
            commands.add(new WatchEventCommand(child.child().content(), child.child().watchedDate(), proposedPass,
                    null, null, child.child().watchedInTheater(), List.of(), null));
        }
        return new SeriesExpansionPlan(sourceRecord.getSourceId(), targetPass, commands, scopeHash, now,
                childPassIssues);
    }

    private String scheduleVersion(CalendarSeriesSchedule schedule, User user) {
        StringBuilder canonical = new StringBuilder().append(schedule.key().tmdbId()).append('\n')
                .append(user.getPreferredRegion()).append('\n').append(user.getPreferredLanguage()).append('\n');
        schedule.expectedEpisodeCountsBySeason().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> canonical.append("expected:").append(entry.getKey()).append(':')
                        .append(entry.getValue()).append('\n'));
        schedule.seasons().stream().sorted(Comparator.comparing(season -> season.schedule().seasonNumber()))
                .forEach(season -> {
                    canonical.append("season:").append(season.schedule().seasonNumber()).append(':')
                            .append(season.expectedEpisodeCount()).append(':').append(season.isFullyRepresented()).append('\n');
                    season.schedule().episodes().stream().sorted(Comparator.comparing(CalendarEpisodeSchedule::episodeNumber))
                            .forEach(episode -> canonical.append("episode:").append(episode.episodeNumber()).append(':')
                                    .append(episode.releaseDate()).append(':').append(episode.releaseTime()).append('\n'));
                });
        return sha256(canonical.toString());
    }

    private ContentRefCreationDTO toCreation(MediaReference media) {
        return new ContentRefCreationDTO(media.tmdbId(), media.type(), media.seriesTmdbId(), media.seasonNumber(),
                media.episodeNumber(), null, null);
    }

    private String key(MediaReference media) {
        return media.type() == ContentType.MOVIE ? "MOVIE:" + media.tmdbId()
                : "EPISODE:" + media.seriesTmdbId() + ":" + media.seasonNumber() + ":" + media.episodeNumber();
    }

    private LocalDate compatibleDate(LocalDate watchedDate, LocalDate releaseDate) {
        return watchedDate != null && !watchedDate.isBefore(releaseDate) ? watchedDate : null;
    }

    private LocalDate date(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isTextual() && !value.asString().isBlank()) return LocalDate.parse(value.asString());
        }
        return null;
    }

    private List<String> tags(JsonNode source) {
        JsonNode node = source.path("tags");
        if (!node.isArray()) return List.of();
        List<String> tags = new ArrayList<>();
        for (JsonNode tag : node) if (tag.isTextual()) tags.add(tag.asString());
        return List.copyOf(tags);
    }

    private SeriesExpansionIssueException issue(String code, String message) {
        return new SeriesExpansionIssueException(code + ": " + message);
    }

    private String sha256(String value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private record Child(MediaReference content, LocalDate watchedDate, Boolean watchedInTheater,
                         LocalDate releaseDate) { }
    private record PreparedChild(Child child, UUID contentId, UUID childId) { }
    private record SeriesSelection(List<Child> children, String scopeHash) { }

    public static final class SeriesExpansionIssueException extends IllegalArgumentException {
        public SeriesExpansionIssueException(String message) {
            super(message);
        }
    }
}
