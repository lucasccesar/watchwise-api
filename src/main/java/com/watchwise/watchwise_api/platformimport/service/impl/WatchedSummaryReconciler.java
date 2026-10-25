package com.watchwise.watchwise_api.platformimport.service.impl;

import com.watchwise.watchwise_api.content.dto.MediaReference;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.platformimport.model.ImportRecordKind;
import com.watchwise.watchwise_api.platformimport.model.ImportSourceKey;
import com.watchwise.watchwise_api.platformimport.model.ImportTarget;
import com.watchwise.watchwise_api.platformimport.model.NormalizedImportRecord;
import com.watchwise.watchwise_api.platformimport.service.ImportExecutionContext;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

@Component
public class WatchedSummaryReconciler {

    private final ObjectMapper objectMapper;

    public WatchedSummaryReconciler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<Decision> reconcile(
            ImportExecutionContext context,
            List<NormalizedImportRecord> events,
            List<NormalizedImportRecord> summaries) {
        if (context == null || events == null || summaries == null) {
            throw new IllegalArgumentException("Summary reconciliation inputs are required");
        }
        List<NormalizedImportRecord> detailEvents = events.stream()
                .filter(record -> record.kind() == ImportRecordKind.DIARY_EVENT
                        || record.kind() == ImportRecordKind.SERIES_EXPANSION)
                .filter(record -> record.target() instanceof ImportTarget.ContentTarget)
                .toList();
        return summaries.stream()
                .filter(record -> record.kind() == ImportRecordKind.WATCHED_SUMMARY)
                .map(summary -> decision(summary, detailEvents))
                .toList();
    }

    private Decision decision(NormalizedImportRecord summary, List<NormalizedImportRecord> events) {
        if (!(summary.target() instanceof ImportTarget.ContentTarget target)) {
            return new Decision(summary.itemId(), summary.sourceKey(), Disposition.PENDING_TARGET_RESOLUTION, null);
        }
        MediaReference media = target.content();
        if (events.stream().anyMatch(event -> covers(event, media))) {
            return new Decision(summary.itemId(), summary.sourceKey(), Disposition.COVERED_BY_DETAIL_HISTORY, null);
        }
        if (media.type() != ContentType.MOVIE && media.type() != ContentType.SERIES
                && media.type() != ContentType.SEASON && media.type() != ContentType.EPISODE) {
            return new Decision(summary.itemId(), summary.sourceKey(), Disposition.PENDING_TARGET_RESOLUTION, null);
        }

        JsonNode payload = readPayload(summary.payload().toString());
        Integer plays = positiveInteger(payload.path("declaredPlays"));
        LocalDate declaredDate = localDate(payload.path("declaredLastWatchedOn"));
        return new Decision(summary.itemId(), summary.sourceKey(), Disposition.STORE_UNKNOWN_ASSERTION,
                new SummaryAssertion(media, declaredDate, plays, "UNKNOWN"));
    }

    private boolean covers(NormalizedImportRecord event, MediaReference summary) {
        if (!(event.target() instanceof ImportTarget.ContentTarget target)) return false;
        MediaReference watched = target.content();
        if (summary.type() == ContentType.MOVIE) {
            return watched.type() == ContentType.MOVIE && summary.tmdbId().equals(watched.tmdbId());
        }
        String summarySeriesId = summary.type() == ContentType.SERIES
                ? summary.tmdbId() : summary.seriesTmdbId();
        String watchedSeriesId = watched.type() == ContentType.SERIES
                ? watched.tmdbId() : watched.seriesTmdbId();
        if (!summarySeriesId.equals(watchedSeriesId)) return false;
        if (summary.type() == ContentType.SERIES) {
            return watched.type() == ContentType.SERIES || watched.type() == ContentType.SEASON
                    || watched.type() == ContentType.EPISODE;
        }
        if (summary.type() == ContentType.EPISODE) {
            return watched.type() == ContentType.EPISODE
                    && summary.seasonNumber().equals(watched.seasonNumber())
                    && summary.episodeNumber().equals(watched.episodeNumber());
        }
        return watched.type() == ContentType.SEASON
                && summary.seasonNumber().equals(watched.seasonNumber());
    }

    private JsonNode readPayload(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Normalized summary payload is invalid", exception);
        }
    }

    private Integer positiveInteger(JsonNode node) {
        return node.isIntegralNumber() && node.canConvertToInt() && node.intValue() > 0
                ? node.intValue() : null;
    }

    private LocalDate localDate(JsonNode node) {
        if (!node.isTextual() || node.asString().isBlank()) return null;
        try {
            return LocalDate.parse(node.asString());
        } catch (DateTimeParseException exception) {
            throw new IllegalStateException("Normalized summary date is invalid", exception);
        }
    }

    public enum Disposition {
        COVERED_BY_DETAIL_HISTORY,
        STORE_UNKNOWN_ASSERTION,
        PENDING_TARGET_RESOLUTION
    }

    public record SummaryAssertion(
            MediaReference content,
            LocalDate declaredDate,
            Integer declaredPlays,
            String coverage) {
    }

    public record Decision(
            String itemId,
            ImportSourceKey sourceKey,
            Disposition disposition,
            SummaryAssertion assertion) {
    }
}
