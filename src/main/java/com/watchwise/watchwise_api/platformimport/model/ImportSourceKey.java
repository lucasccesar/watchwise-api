package com.watchwise.watchwise_api.platformimport.model;

public record ImportSourceKey(String namespace, String objectKind, String sourceId) {
    public ImportSourceKey {
        if (blank(namespace) || blank(objectKind) || blank(sourceId)) {
            throw new IllegalArgumentException("Source identity fields are required");
        }
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
