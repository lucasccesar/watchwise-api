package com.watchwise.watchwise_api.platformimport.service.impl;

import com.watchwise.watchwise_api.content.dto.ContentRefCreationDTO;
import com.watchwise.watchwise_api.content.dto.MediaReference;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.content.service.ContentService;
import com.watchwise.watchwise_api.diaryentry.entity.ImportedWatchAssertion;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.diaryentry.repository.ImportedWatchAssertionRepository;
import com.watchwise.watchwise_api.platformimport.entity.ImportItem;
import com.watchwise.watchwise_api.platformimport.model.ImportRecordKind;
import com.watchwise.watchwise_api.platformimport.model.ImportSourceKey;
import com.watchwise.watchwise_api.platformimport.model.ImportTarget;
import com.watchwise.watchwise_api.platformimport.model.NormalizedImportRecord;
import com.watchwise.watchwise_api.platformimport.repository.ImportItemRepository;
import com.watchwise.watchwise_api.platformimport.service.ImportExecutionContext;
import com.watchwise.watchwise_api.platformimport.service.ImportLedger;
import com.watchwise.watchwise_api.platformimport.service.ImportUnitApplier;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
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
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public boolean supports(ImportRecordKind kind) {
        return kind == ImportRecordKind.WATCHED_SUMMARY;
    }

    @Override
    public void apply(ImportExecutionContext context, UUID itemId) {
        ImportItem item = itemRepository.findById(itemId)
                .filter(candidate -> context.jobId().equals(candidate.getJobId())
                        && context.planGeneration() == candidate.getPlanGeneration()
                        && candidate.getNormalizedKind() == ImportRecordKind.WATCHED_SUMMARY)
                .orElseThrow(() -> new IllegalArgumentException("Watched summary does not belong to this import"));
        ImportTarget target = readTarget(item.getResolvedTargetJson());
        if (!(target instanceof ImportTarget.ContentTarget contentTarget)) {
            throw new IllegalStateException("Watched summary must resolve to a content target before applying");
        }

        MediaReference media = contentTarget.content();
        ensureContentReference(media);
        List<NormalizedImportRecord> events = detailEvents(context.ownerId(), media);
        NormalizedImportRecord summary = new NormalizedImportRecord(item.getId().toString(),
                ImportRecordKind.WATCHED_SUMMARY, sourceKey(item), null, target,
                readPayload(item.getPayloadJson()), Math.toIntExact(item.getOrdinal()));
        WatchedSummaryReconciler.Decision decision = reconciler.reconcile(context, events, List.of(summary)).getFirst();
        if (decision.disposition() == WatchedSummaryReconciler.Disposition.COVERED_BY_DETAIL_HISTORY) {
            ledger.markSkipped(context.ownerId(), sourceKey(item), null, null, "DETAIL_HISTORY_COVERS_SUMMARY");
            return;
        }
        if (decision.disposition() != WatchedSummaryReconciler.Disposition.STORE_UNKNOWN_ASSERTION
                || decision.assertion() == null) {
            throw new IllegalStateException("Watched summary cannot be reconciled without a resolved target");
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
        contentService.getOrCreateReference(reference);
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
}
