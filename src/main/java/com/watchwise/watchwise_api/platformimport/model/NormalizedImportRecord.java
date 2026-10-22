package com.watchwise.watchwise_api.platformimport.model;

import com.watchwise.watchwise_api.content.entity.ContentType;
import tools.jackson.databind.JsonNode;

public record NormalizedImportRecord(
        String itemId,
        ImportRecordKind kind,
        ImportSourceKey sourceKey,
        String parentSourceId,
        ImportTarget target,
        JsonNode payload,
        int ordinal) {
    public NormalizedImportRecord {
        if (itemId == null || itemId.isBlank() || kind == null || sourceKey == null || payload == null) {
            throw new IllegalArgumentException("Normalized import record fields are required");
        }
        if (target == null && !hasQuery(payload)) {
            throw new IllegalArgumentException("An unresolved import record requires an alias query");
        }
        if (ordinal < 0) throw new IllegalArgumentException("Ordinal must not be negative");
    }

    private static boolean hasQuery(JsonNode payload) {
        JsonNode query = payload.get("sourceQuery");
        if (query == null) query = payload.get("query");
        if (query == null) query = payload.path("source").path("sourceQuery");
        if (query == null || !query.isObject()) query = payload.path("source").path("query");
        if (query == null || !query.isObject() || !query.path("kind").isTextual()
                || !query.path("title").isTextual()) return false;
        try {
            AliasQuery.Kind kind = AliasQuery.Kind.valueOf(query.path("kind").asString());
            String title = query.path("title").asString();
            Integer year = null;
            if (query.hasNonNull("year")) {
                if (!query.path("year").canConvertToInt()) return false;
                year = query.path("year").asInt();
            }
            ContentType contentType = null;
            if (query.hasNonNull("contentType")) {
                if (!query.path("contentType").isTextual()) return false;
                contentType = ContentType.valueOf(query.path("contentType").asString());
            }
            new AliasQuery(kind, title, year, contentType);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
