package com.watchwise.watchwise_api.platformimport.model;

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
        if (itemId == null || itemId.isBlank() || kind == null || sourceKey == null || target == null || payload == null) {
            throw new IllegalArgumentException("Normalized import record fields are required");
        }
        if (ordinal < 0) throw new IllegalArgumentException("Ordinal must not be negative");
    }
}
