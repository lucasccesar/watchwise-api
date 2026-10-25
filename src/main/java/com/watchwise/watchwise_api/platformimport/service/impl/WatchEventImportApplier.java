package com.watchwise.watchwise_api.platformimport.service.impl;

import com.watchwise.watchwise_api.content.dto.MediaReference;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.diaryentry.dto.WatchEventCommand;
import com.watchwise.watchwise_api.diaryentry.dto.WatchMutationResult;
import com.watchwise.watchwise_api.diaryentry.service.DiaryWatchCommandService;
import com.watchwise.watchwise_api.platformimport.entity.ImportItem;
import com.watchwise.watchwise_api.platformimport.model.ImportDecision;
import com.watchwise.watchwise_api.platformimport.model.ImportRecordKind;
import com.watchwise.watchwise_api.platformimport.model.ImportSourceKey;
import com.watchwise.watchwise_api.platformimport.model.SourceApplicationDecision;
import com.watchwise.watchwise_api.platformimport.model.ImportTarget;
import com.watchwise.watchwise_api.platformimport.repository.ImportItemRepository;
import com.watchwise.watchwise_api.platformimport.service.ImportExecutionContext;
import com.watchwise.watchwise_api.platformimport.service.ImportLedger;
import com.watchwise.watchwise_api.platformimport.service.ImportUnitApplier;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

@Service
public class WatchEventImportApplier implements ImportUnitApplier {

    private static final String RESOLUTION_MARKER = "_watchwiseResolution";
    private static final int RESOLUTION_SCHEMA_VERSION = 1;

    private final ImportItemRepository itemRepository;
    private final ContentRepository contentRepository;
    private final DiaryWatchCommandService diaryWatchCommandService;
    private final ImportLedger ledger;
    private final ImportPassPlanner passPlanner;
    private final ObjectMapper objectMapper;

    public WatchEventImportApplier(ImportItemRepository itemRepository, ContentRepository contentRepository,
                                   DiaryWatchCommandService diaryWatchCommandService, ImportLedger ledger,
                                   ImportPassPlanner passPlanner, ObjectMapper objectMapper) {
        this.itemRepository = itemRepository;
        this.contentRepository = contentRepository;
        this.diaryWatchCommandService = diaryWatchCommandService;
        this.ledger = ledger;
        this.passPlanner = passPlanner;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(ImportRecordKind kind) {
        return kind == ImportRecordKind.DIARY_EVENT;
    }

    @Override
    public void apply(ImportExecutionContext context, UUID unitId) {
        ImportItem item = itemRepository.findById(unitId).orElseThrow(
                () -> new IllegalStateException("Staged diary event was not found"));
        if (!item.getJobId().equals(context.jobId()) || item.getPlanGeneration() != context.planGeneration()
                || !item.getSourceNamespace().equals(context.sourceNamespace())
                || item.getNormalizedKind() != ImportRecordKind.DIARY_EVENT) {
            throw new IllegalStateException("Staged diary event does not belong to this import plan");
        }

        ParsedEvent event = parse(item);
        Content content = findPreparedContent(event.media());
        ImportPassPlanner.Plan passPlan = passPlanner.plan(context.ownerId(), List.of(
                new ImportPassPlanner.WatchEvent(item.getId(), content.getId(), item.getSourceNamespace(),
                        event.watchedDate(), event.assignedPass(), event.linkExistingOnly())));
        String issue = passPlan.issues().get(item.getId());
        if (issue != null) throw new PassAssignmentRequiredException(issue);

        Integer watchNumber = passPlan.watchNumbers().get(item.getId());
        if (watchNumber == null) {
            throw new IllegalStateException("Diary event pass plan did not assign a watch number");
        }
        WatchEventCommand command = new WatchEventCommand(event.media(), event.watchedDate(), watchNumber,
                event.review(), event.score(), null, List.of(), event.watchedAt());
        java.util.Optional<WatchMutationResult> result = diaryWatchCommandService.recordPreparedImport(
                context.ownerId(), command, passPlan.linkedExistingPasses().contains(item.getId()));
        if (result.isEmpty()) {
            throw new PassAssignmentRequiredException("PASS_ASSIGNMENT_CONFLICT: Watch pass " + watchNumber
                    + " changed during import planning; assign a different pass or link an existing watch.");
        }
        ledger.markApplied(context.ownerId(), sourceKey(item), "DIARY_ENTRY", result.get().diaryEntryId());
    }

    private ParsedEvent parse(ImportItem item) {
        try {
            JsonNode storedPayload = objectMapper.readTree(item.getPayloadJson());
            JsonNode source = storedPayload;
            JsonNode resolution = objectMapper.createObjectNode();
            if (storedPayload.isObject() && storedPayload.path(RESOLUTION_MARKER).asInt(-1)
                    == RESOLUTION_SCHEMA_VERSION && storedPayload.path("source").isObject()) {
                source = storedPayload.path("source");
                resolution = storedPayload.path("resolution");
            }

            ImportTarget target = objectMapper.treeToValue(
                    objectMapper.readTree(item.getResolvedTargetJson()), ImportTarget.class);
            if (!(target instanceof ImportTarget.ContentTarget contentTarget)) {
                throw new IllegalStateException("Diary event must resolve to content");
            }
            MediaReference media = contentTarget.content();
            if (media.type() != ContentType.MOVIE && media.type() != ContentType.EPISODE) {
                throw new IllegalStateException("Diary events support only movies and episodes");
            }

            LocalDate watchedDate = parseDate(firstText(resolution.path("fields"), "watchedOn", "watchedDate"),
                    firstText(source, "watchedOn", "watchedDate", "date"));
            Instant watchedAt = parseInstant(firstText(resolution.path("fields"), "sourceWatchedAt"),
                    text(source.path("sourceWatchedAt")));
            Integer score = parseInteger(firstText(resolution.path("fields"), "score"), source.path("score"));
            String review = firstText(resolution.path("fields"), "review", "comment");
            if (review == null) review = text(source.path("review"));

            Integer assignedPass = null;
            if (item.getDecision() == ImportDecision.ASSIGN_PASS) {
                JsonNode passNumber = resolution.path("assignPass").path("passNumber");
                if (!passNumber.canConvertToInt()) {
                    throw new IllegalStateException("Assigned watch pass is invalid");
                }
                assignedPass = passNumber.asInt();
            }
            boolean linkExistingOnly = resolution.path("linkExistingOnly").asBoolean(false)
                    || item.getIssue() != null && item.getIssue().contains("MATCH_REQUIRED");
            return new ParsedEvent(media, watchedDate, watchedAt, score, review, assignedPass, linkExistingOnly);
        } catch (JacksonException | DateTimeParseException exception) {
            throw new IllegalStateException("Staged diary event payload is invalid", exception);
        }
    }

    private Content findPreparedContent(MediaReference media) {
        Content content = switch (media.type()) {
            case MOVIE -> contentRepository.findByTmdbIdAndType(media.tmdbId(), ContentType.MOVIE)
                    .orElseThrow(() -> new IllegalStateException("Resolved movie content is missing"));
            case EPISODE -> contentRepository.findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType(
                            media.seriesTmdbId(), media.seasonNumber(), media.episodeNumber(), ContentType.EPISODE)
                    .orElseThrow(() -> new IllegalStateException("Resolved episode content is missing"));
            default -> throw new IllegalStateException("Diary events support only movies and episodes");
        };
        if (content.getType() != media.type()
                || media.type() == ContentType.MOVIE && !media.tmdbId().equals(content.getTmdbId())
                || media.type() == ContentType.EPISODE
                && (!media.seriesTmdbId().equals(content.getSeriesTmdbId())
                || !media.seasonNumber().equals(content.getSeasonNumber())
                || !media.episodeNumber().equals(content.getEpisodeNumber()))) {
            throw new IllegalStateException("Resolved content does not match the staged diary event");
        }
        return content;
    }

    private ImportSourceKey sourceKey(ImportItem item) {
        return new ImportSourceKey(item.getSourceNamespace(), item.getObjectKind(), item.getSourceId());
    }

    private LocalDate parseDate(String override, String source) {
        String value = override == null ? source : override;
        return value == null ? null : LocalDate.parse(value);
    }

    private Instant parseInstant(String override, String source) {
        String value = override == null ? source : override;
        return value == null ? null : Instant.parse(value);
    }

    private Integer parseInteger(String override, JsonNode source) {
        if (override != null) return Integer.valueOf(override);
        return source != null && source.canConvertToInt() ? source.asInt() : null;
    }

    private String firstText(JsonNode node, String... names) {
        for (String name : names) {
            String value = text(node.path(name));
            if (value != null) return value;
        }
        return null;
    }

    private String text(JsonNode node) {
        return node != null && node.isTextual() ? node.asString() : null;
    }

    private record ParsedEvent(MediaReference media, LocalDate watchedDate, Instant watchedAt,
                               Integer score, String review, Integer assignedPass, boolean linkExistingOnly) { }

    public static final class PassAssignmentRequiredException extends RuntimeException {
        public PassAssignmentRequiredException(String message) {
            super(message);
        }

        public SourceApplicationDecision decision() {
            return SourceApplicationDecision.MATCH_REQUIRED;
        }
    }
}
