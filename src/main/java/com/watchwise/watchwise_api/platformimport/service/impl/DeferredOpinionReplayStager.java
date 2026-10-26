package com.watchwise.watchwise_api.platformimport.service.impl;

import com.watchwise.watchwise_api.content.dto.MediaReference;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.platformimport.entity.ImportDeferredRecord;
import com.watchwise.watchwise_api.platformimport.entity.ImportJob;
import com.watchwise.watchwise_api.platformimport.entity.ImportItem;
import com.watchwise.watchwise_api.platformimport.model.ImportRecordKind;
import com.watchwise.watchwise_api.platformimport.model.ImportSourceKey;
import com.watchwise.watchwise_api.platformimport.model.ImportSourceKey;
import com.watchwise.watchwise_api.platformimport.model.ImportTarget;
import com.watchwise.watchwise_api.platformimport.model.NormalizedImportRecord;
import com.watchwise.watchwise_api.platformimport.repository.ImportDeferredRecordRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.function.Consumer;

@Component
public class DeferredOpinionReplayStager {
    private static final int PAGE_SIZE = 200;

    private final ImportDeferredRecordRepository deferredRepository;
    private final ObjectMapper objectMapper;

    public DeferredOpinionReplayStager(ImportDeferredRecordRepository deferredRepository, ObjectMapper objectMapper) {
        this.deferredRepository = deferredRepository;
        this.objectMapper = objectMapper;
    }

    public void emit(UUID userId, String importNamespace, Set<String> relevantContentKeys,
                     Set<ImportSourceKey> matchingScopeSources,
                     Consumer<NormalizedImportRecord> sink) {
        if (userId == null || importNamespace == null || importNamespace.isBlank()
                || relevantContentKeys == null || matchingScopeSources == null || sink == null) {
            throw new IllegalArgumentException("Deferred opinion replay requires an import owner and namespace");
        }
        if (relevantContentKeys.isEmpty() && matchingScopeSources.isEmpty()) return;
        int pageNumber = 0;
        Page<ImportDeferredRecord> page;
        do {
            page = deferredRepository.findPendingByUserId(userId, PageRequest.of(pageNumber++, PAGE_SIZE));
            for (ImportDeferredRecord deferred : page.getContent()) {
                if (matchingScopeSources.contains(new ImportSourceKey(deferred.getSourceNamespace(),
                        deferred.getObjectKind(), deferred.getSourceId()))
                        || targetMatches(deferred, relevantContentKeys)) {
                    sink.accept(toRecord(deferred, importNamespace));
                }
            }
        } while (page.hasNext());
    }

    public NormalizedImportRecord toRecord(ImportDeferredRecord deferred, String importNamespace) {
        try {
            JsonNode provenance = objectMapper.readTree(deferred.getProvenance());
            MediaReference content = contentReference(provenance);
            ImportRecordKind kind = opinionKind(provenance, deferred.getNormalizedPayload());
            JsonNode payloadNode = objectMapper.readTree(deferred.getNormalizedPayload());
            if (payloadNode == null || !payloadNode.isObject()) {
                throw new IllegalStateException("Deferred opinion payload is invalid");
            }
            ObjectNode payload = (ObjectNode) payloadNode.deepCopy();
            payload.putObject("_deferredReplay")
                    .put("deferredRecordId", deferred.getId().toString())
                    .put("originalSourceNamespace", deferred.getSourceNamespace())
                    .put("originalObjectKind", deferred.getObjectKind())
                    .put("originalSourceId", deferred.getSourceId())
                    .put("originalKind", kind.name());

            ImportSourceKey replayKey = new ImportSourceKey(importNamespace,
                    "deferred_opinion_replay", deferred.getId().toString());
            return new NormalizedImportRecord("deferred-opinion:" + deferred.getId(), kind, replayKey,
                    null, new ImportTarget.ContentTarget(content), payload, 0);
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new IllegalStateException("Deferred opinion could not be prepared for replay", exception);
        }
    }

    public ImportItem toImportItem(UUID jobId, long generation, NormalizedImportRecord record, long ordinal) {
        UUID itemId = stableItemId(jobId, generation, record.itemId());
        ImportItem item = ImportItem.builder().id(itemId).jobId(jobId).planGeneration(generation)
                .normalizedKind(record.kind()).sourceNamespace(record.sourceKey().namespace())
                .objectKind(record.sourceKey().objectKind()).sourceId(record.sourceKey().sourceId())
                .sourceEventKey(record.sourceKey().sourceId()).parentSourceId(record.parentSourceId())
                .parserVersion("deferred-opinion-replay-v1").payloadJson(writeJson(record.payload()))
                .resolvedTargetJson(record.target() == null ? null : writeJson(record.target()))
                .resolvedContentKey(contentKey(record.target())).ordinal(ordinal).outcome("RESOLVED").build();
        return item;
    }

    public static UUID stableItemId(UUID jobId, long generation, String itemId) {
        String seed = jobId + ":" + generation + ":" + itemId;
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }

    private boolean targetMatches(ImportDeferredRecord deferred, Set<String> contentKeys) {
        try {
            JsonNode provenance = objectMapper.readTree(deferred.getProvenance());
            String contentType = provenance.path("contentType").asString();
            String seriesId = contentType.equals("SERIES")
                    ? requiredText(provenance, "tmdbId") : requiredText(provenance, "seriesTmdbId");
            Integer season = contentType.equals("SEASON") ? provenance.path("seasonNumber").asInt(-1) : null;
            String seriesKey = "CONTENT|SERIES|" + seriesId;
            if (contentKeys.contains(seriesKey)) return true;
            String seasonPrefix = "CONTENT|SEASON|" + seriesId + "|";
            String episodePrefix = "CONTENT|EPISODE|" + seriesId + "|";
            return contentKeys.stream().anyMatch(key -> {
                if (contentType.equals("SERIES")) {
                    return key.startsWith(seasonPrefix) || key.startsWith(episodePrefix);
                }
                return key.equals(seasonPrefix + season) || key.startsWith(episodePrefix + season + "|");
            });
        } catch (JacksonException | IllegalStateException exception) {
            throw new IllegalStateException("Deferred opinion target could not be matched to this import", exception);
        }
    }

    private String contentKey(ImportTarget target) {
        if (!(target instanceof ImportTarget.ContentTarget contentTarget)) return null;
        MediaReference content = contentTarget.content();
        return switch (content.type()) {
            case MOVIE, SERIES -> "CONTENT|" + content.type().name() + "|" + content.tmdbId();
            case SEASON -> "CONTENT|SEASON|" + content.seriesTmdbId() + "|" + content.seasonNumber();
            case EPISODE -> "CONTENT|EPISODE|" + content.seriesTmdbId() + "|"
                    + content.seasonNumber() + "|" + content.episodeNumber();
        };
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Deferred opinion replay could not be serialized", exception);
        }
    }

    private MediaReference contentReference(JsonNode provenance) {
        ContentType type = ContentType.valueOf(provenance.path("contentType").asString());
        if (type == ContentType.SERIES) {
            return MediaReference.series(requiredText(provenance, "tmdbId"));
        }
        if (type == ContentType.SEASON) {
            JsonNode season = provenance.path("seasonNumber");
            if (!season.canConvertToInt() || season.asInt() < 0) {
                throw new IllegalStateException("Deferred season opinion has no valid season number");
            }
            return MediaReference.season(requiredText(provenance, "seriesTmdbId"), season.asInt());
        }
        throw new IllegalStateException("Only series and season opinions can be deferred");
    }

    private ImportRecordKind opinionKind(JsonNode provenance, String payload) throws JacksonException {
        String normalizedKind = provenance.path("normalizedKind").asString();
        if (ImportRecordKind.REVIEW.name().equals(normalizedKind)
                || ImportRecordKind.CURRENT_RATING.name().equals(normalizedKind)) {
            return ImportRecordKind.valueOf(normalizedKind);
        }
        JsonNode root = objectMapper.readTree(payload);
        JsonNode source = root.path("source").isObject() ? root.path("source") : root;
        return source.path("review").isTextual() && !source.path("review").asString().isBlank()
                ? ImportRecordKind.REVIEW : ImportRecordKind.CURRENT_RATING;
    }

    private String requiredText(JsonNode source, String field) {
        JsonNode value = source.path(field);
        if (!value.isTextual() || value.asString().isBlank()) {
            throw new IllegalStateException("Deferred opinion target is missing " + field);
        }
        return value.asString();
    }
}
